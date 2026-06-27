package com.samsung.android.camera.core2.ml

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * SeqPAW-UB: Sequence-aware, phase-aware, adaptive upper bound.
 *
 * Point prediction is the sum of per-workload EWMA values. The safety margin is a multiplicative residual calibrated at
 * decision-sequence level from positive residual ratios captured before workload EWMA updates.
 */
class DraftSequenceExecutionPredictor {

    private val workloadModels = mutableMapOf<WorkloadKey, WorkloadEwmaModel>()
    private val sequenceScoreSamples = mutableMapOf<WorkloadSequenceKey, MutableList<ScoreSample>>()
    private val globalScoreSamples = mutableListOf<ScoreSample>()

    @Synchronized
    fun predictAdmission(
        sequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): SeqPawDecision {
        val nodeKey = WorkloadSequenceKey(listOf(sequenceKey.workloads.first()))
        val nodePrediction = predictSequence(nodeKey)
        val sequencePrediction = predictSequence(sequenceKey)
        return SeqPawDecision(
            executionPrediction = ExecutionPrediction(
                admit = sequencePrediction.isColdStart ||
                        sequencePrediction.upperBoundMs <= preExecutionMetrics.budgetMs,
                nodePredictedDurationMs = nodePrediction.predictedMs.roundToNonNegativeLong(),
                nodePredictedUpperBoundMs = nodePrediction.upperBoundMs.roundToNonNegativeLong(),
                sequencePredictedDurationMs = sequencePrediction.predictedMs.roundToNonNegativeLong(),
                sequencePredictedUpperBoundMs = sequencePrediction.upperBoundMs.roundToNonNegativeLong(),
            ),
            sequenceSnapshot = SequencePredictionSnapshot(
                sequenceKey = sequenceKey,
                predictedMs = sequencePrediction.predictedMs,
                workloadPredictedMs = sequenceKey.workloads.associateWith(::predictedWorkloadDuration),
            ),
        )
    }

    @Synchronized
    fun predictWatchdogTimeout(
        sequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): SeqPawTimeoutDecision {
        val decision = predictAdmission(sequenceKey, preExecutionMetrics)
        val timeoutMs = (preExecutionMetrics.budgetMs - decision.executionPrediction.sequencePredictedUpperBoundMs)
            .coerceAtLeast(0L)

        return SeqPawTimeoutDecision(timeoutMs, decision)
    }

    @Synchronized
    fun updateCapture(
        workloadDurations: Map<WorkloadKey, Long>,
        predictionSnapshots: Collection<SequencePredictionSnapshot>,
    ) {
        val validWorkloadDurations = workloadDurations.filterValues { it > 0L }

        addScoreSamples(validWorkloadDurations, predictionSnapshots)

        validWorkloadDurations.forEach { (workloadKey, durationMs) ->
            workloadModels.getOrPut(workloadKey) { WorkloadEwmaModel() }.update(durationMs.toDouble())
        }
    }

    private fun addScoreSamples(
        workloadDurations: Map<WorkloadKey, Long>,
        predictionSnapshots: Collection<SequencePredictionSnapshot>,
    ) {
        val samples = predictionSnapshots.mapNotNull { snapshot ->
            val clampedActualMs = clampedActualDuration(snapshot, workloadDurations) ?: return@mapNotNull null
            val score = score(snapshot.predictedMs, clampedActualMs) ?: return@mapNotNull null
            snapshot.sequenceKey to ScoreSample(score = score)
        }

        if (samples.isEmpty()) {
            return
        }

        decayScoreSamples()
        samples.forEach { (sequenceKey, sample) ->
            sequenceScoreSamples.getOrPut(sequenceKey) { mutableListOf() } += sample
            globalScoreSamples += sample
        }
    }

    private fun decayScoreSamples() {
        globalScoreSamples.decayWeights()
        sequenceScoreSamples.values.forEach { it.decayWeights() }
    }

    private fun MutableList<ScoreSample>.decayWeights() {
        for (index in indices) {
            this[index] = this[index].decayed(SCORE_WEIGHT_DECAY)
        }
        // ponytail: drop the negligible tail so the process-wide singleton's sample lists stay bounded.
        // At decay=0.9 a sample older than ~130 captures weighs < 1e-6 and no longer moves any quantile.
        removeAll { it.weight < SCORE_WEIGHT_PRUNE_THRESHOLD }
    }

    private fun predictSequence(sequenceKey: WorkloadSequenceKey): SequencePrediction {
        val predictedMs = predictedDuration(sequenceKey)
        val upperBoundMs = correctedUpperBound(sequenceKey)
        return SequencePrediction(predictedMs, upperBoundMs)
    }

    private fun correctedUpperBound(sequenceKey: WorkloadSequenceKey): Double {
        // Walk tail -> head. Monotonic inclusion: each suffix's bound is at least its own raw bound and at least its
        // head EWMA plus the already-corrected tail, so nested-sequence bounds never invert. (Since exp(C) >= 1, the
        // tail step max() already returns the raw bound, so no special case for the last workload is needed.)
        val workloads = sequenceKey.workloads
        var corrected = 0.0
        for (index in workloads.indices.reversed()) {
            val suffixRawUpperBound = rawUpperBound(WorkloadSequenceKey(workloads.drop(index)))
            corrected = maxOf(suffixRawUpperBound, predictedWorkloadDuration(workloads[index]) + corrected)
        }
        return corrected
    }

    private fun rawUpperBound(sequenceKey: WorkloadSequenceKey): Double {
        val predictedMs = predictedDuration(sequenceKey)
        if (predictedMs <= 0.0) {
            return 0.0
        }

        return predictedMs * exp(calibratedScore(sequenceKey))
    }

    private fun predictedDuration(sequenceKey: WorkloadSequenceKey): Double {
        return sequenceKey.workloads.sumOf(::predictedWorkloadDuration)
    }

    private fun predictedWorkloadDuration(workloadKey: WorkloadKey): Double {
        workloadModels[workloadKey]?.let { return it.predictionMs() }
        // New variant not seen yet: borrow the slowest model of the same workload type as a conservative cold-start
        // estimate, so it gets a real bound instead of a blind admit. Self-corrects once it has its own samples.
        return workloadModels
            .filterKeys { it::class == workloadKey::class }
            .values.maxOfOrNull { it.predictionMs() } ?: 0.0
    }

    private fun calibratedScore(sequenceKey: WorkloadSequenceKey): Double {
        return calibratedScore(
            sequenceSamples = sequenceScoreSamples[sequenceKey].orEmpty(),
            globalSamples = globalScoreSamples,
        )
    }

    /**
     * Containment-scoped actual: each node contributes max(its decision-time prediction, its actual), so a single
     * node's overrun is reflected at the sequence's scale even when another node happened to run fast that capture.
     * Returns null if any node in the sequence did not run (incomplete sequence cannot be scored).
     */
    private fun clampedActualDuration(
        snapshot: SequencePredictionSnapshot,
        workloadDurations: Map<WorkloadKey, Long>,
    ): Double? {
        var total = 0.0
        for (workloadKey in snapshot.sequenceKey.workloads) {
            val actualMs = workloadDurations[workloadKey] ?: return null
            val predictedMs = snapshot.workloadPredictedMs[workloadKey] ?: 0.0
            total += maxOf(predictedMs, actualMs.toDouble())
        }
        return total.takeIf { it > 0.0 }
    }

    private data class SequencePrediction(
        val predictedMs: Double,
        val upperBoundMs: Double,
    ) {
        val isColdStart: Boolean
            get() = predictedMs <= 0.0
    }

    private class WorkloadEwmaModel {
        private var sampleCount: Int = 0
        private var levelMs: Double? = null

        fun predictionMs(): Double = levelMs ?: 0.0

        fun update(actualMs: Double) {
            if (actualMs <= 0.0) {
                return
            }

            val currentLevelMs = levelMs
            if (currentLevelMs == null) {
                levelMs = actualMs
                sampleCount = 1
                return
            }

            val nextSampleCount = sampleCount + 1
            val effectiveAlpha = maxOf(WORKLOAD_EWMA_ALPHA, 1.0 / nextSampleCount.toDouble())
            levelMs = currentLevelMs + effectiveAlpha * (actualMs - currentLevelMs)
            sampleCount = nextSampleCount
        }
    }

    companion object {
        /** Process-wide learned model shared by profilers created across captures. */
        @JvmStatic
        val instance = DraftSequenceExecutionPredictor()

        // Decoupled: alpha only tracks the workload level (responsive enough for thermal-throttle ramps);
        // decay sets the residual memory / safety quantile (effective ESS=(1+d)/(1-d) -> ~95th-pct bound at 0.9),
        // which self-calibrates to each device's throttling magnitude. Do not re-couple decay to 1 - alpha.
        private const val WORKLOAD_EWMA_ALPHA = 0.30
        private const val SCORE_WEIGHT_DECAY = 0.90
        private const val SCORE_WEIGHT_PRUNE_THRESHOLD = 1e-6
    }
}

data class SeqPawDecision(
    val executionPrediction: ExecutionPrediction,
    val sequenceSnapshot: SequencePredictionSnapshot,
)

data class SeqPawTimeoutDecision(
    val timeoutMs: Long,
    val decision: SeqPawDecision,
)

data class SequencePredictionSnapshot(
    val sequenceKey: WorkloadSequenceKey,
    val predictedMs: Double,
    /** Per-workload decision-time prediction, used to clamp node underruns so one node's spike is not diluted. */
    val workloadPredictedMs: Map<WorkloadKey, Double>,
)

data class ScoreSample(
    val score: Double,
    val weight: Double = 1.0,
) {
    fun decayed(factor: Double): ScoreSample = copy(weight = weight * factor)
}


fun score(predictedMs: Double, actualMs: Double): Double? {
    if (predictedMs <= 0.0 || actualMs <= 0.0) {
        return null
    }
    return maxOf(0.0, ln(actualMs / predictedMs))
}

fun effectiveSampleSize(weights: List<Double>): Double {
    val sumW = weights.sum()
    if (sumW <= 0.0) {
        return 0.0
    }

    val sumW2 = weights.sumOf { it * it }
    if (sumW2 <= 0.0) {
        return 0.0
    }

    return (sumW * sumW) / sumW2
}

fun adaptiveQuantile(effectiveSampleSize: Double): Double {
    if (effectiveSampleSize <= 0.0) {
        return 0.0
    }
    return 1.0 - 1.0 / (effectiveSampleSize + 1.0)
}

fun quantileScore(samples: List<ScoreSample>): Double {
    val weightedSamples = samples.filter { it.weight > 0.0 }
    if (weightedSamples.isEmpty()) {
        return 0.0
    }

    val totalWeight = weightedSamples.sumOf { it.weight }
    val targetWeight = totalWeight * adaptiveQuantile(effectiveSampleSize(weightedSamples.map { it.weight }))
    var cumulativeWeight = 0.0
    for (sample in weightedSamples.sortedBy { it.score }) {
        cumulativeWeight += sample.weight
        if (cumulativeWeight >= targetWeight) {
            return sample.score
        }
    }

    return weightedSamples.maxOf { it.score }
}

fun calibratedScore(
    sequenceSamples: List<ScoreSample>,
    globalSamples: List<ScoreSample>,
): Double {
    // Each sequence's bound comes from its OWN residual tail (defensible, no cross-sequence contamination).
    // The pooled-global tail is only a cold-start prior for a sequence we have never observed yet.
    return if (sequenceSamples.isNotEmpty()) {
        quantileScore(sequenceSamples)
    } else {
        quantileScore(globalSamples)
    }
}

private fun Double.roundToNonNegativeLong(): Long = when {
    isNaN() || this <= 0.0 -> 0L
    this >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
    else -> roundToLong().coerceAtLeast(0L)
}
