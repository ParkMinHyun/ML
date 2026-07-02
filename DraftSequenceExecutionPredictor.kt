package com.samsung.android.camera.core2.ml

import com.samsung.android.camera.core2.node.MultiFrameNodeBase
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Sequence-aware, phase-aware, adaptive upper bound.
 *
 * Point prediction is the sum of per-workload EWMA values. The safety margin is a multiplicative residual calibrated at
 * decision-sequence level from positive residual ratios captured before workload EWMA updates.
 */
class DraftSequenceExecutionPredictor {

    private val workloadKeyModelMap = mutableMapOf<WorkloadKey, WorkloadEwmaModel>()
    private val workloadKeySequenceScoreMap = mutableMapOf<WorkloadKeySequence, MutableList<ScoreSample>>()
    private val workloadKeySequenceShapeScoreMap = mutableMapOf<WorkloadKeySequenceShape, MutableList<ScoreSample>>()

    private val taskQueueBudgetTrendMap = mutableMapOf<Class<out MultiFrameNodeBase>, BudgetTrend>()

    @Synchronized
    fun predictAdmission(
        workloadKeySequence: WorkloadKeySequence,
        preExecutionMetrics: PreExecutionMetrics,
    ): AdmissionDecision {
        val headWorkloadKey = workloadKeySequence.workloadKeys.first()
        val headWorkloadKeySequence = WorkloadKeySequence(listOf(headWorkloadKey))
        val headWorkloadPrediction = predictSequence(headWorkloadKeySequence)
        val workloadSequencePrediction = predictSequence(workloadKeySequence)
        val admit = admitUnderBudgetPolicy(
            headWorkloadKey,
            workloadSequencePrediction,
            preExecutionMetrics.budgetMs,
        )
        return AdmissionDecision(
            executionPrediction = ExecutionPrediction(
                admit = admit,
                nodePredictedDurationMs = headWorkloadPrediction.predictedMs.roundToNonNegativeLong(),
                nodePredictedUpperBoundMs = headWorkloadPrediction.upperBoundMs.roundToNonNegativeLong(),
                sequencePredictedDurationMs = workloadSequencePrediction.predictedMs.roundToNonNegativeLong(),
                sequencePredictedUpperBoundMs = workloadSequencePrediction.upperBoundMs.roundToNonNegativeLong(),
            ),
            sequenceSnapshot = SequencePredictionSnapshot(
                workloadKeySequence = workloadKeySequence,
                predictedMs = workloadSequencePrediction.predictedMs,
                workloadPredictedMs = workloadKeySequence.workloadKeys.associateWith(::predictedWorkloadDuration),
            ),
        )
    }

    @Synchronized
    fun predictWatchdogTimeout(
        workloadKeySequence: WorkloadKeySequence,
        preExecutionMetrics: PreExecutionMetrics,
    ): WatchdogTimeoutDecision {
        val decision = predictAdmission(workloadKeySequence, preExecutionMetrics)
        val timeoutMs = (preExecutionMetrics.budgetMs - decision.executionPrediction.sequencePredictedUpperBoundMs)
            .coerceAtLeast(0L)

        return WatchdogTimeoutDecision(timeoutMs, decision)
    }

    @Synchronized
    fun observeQueuePressureBudget(
        queuePressureGroup: Class<out MultiFrameNodeBase>,
        budgetMs: Long,
    ) {
        taskQueueBudgetTrendMap.getOrPut(queuePressureGroup) { BudgetTrend(WORKLOAD_EWMA_ALPHA) }.observe(budgetMs)
    }

    private fun admitUnderBudgetPolicy(
        headWorkloadKey: WorkloadKey,
        workloadSequencePrediction: SequencePrediction,
        budgetMs: Long,
    ): Boolean {
        if (workloadSequencePrediction.isColdStart) {
            return true
        }
        val queuePressureGroup = headWorkloadKey.queuePressureGroup
        if (queuePressureGroup != null) {
            return admitsUnderQueue(queuePressureGroup, workloadSequencePrediction.upperBoundMs, budgetMs)
        }
        return workloadSequencePrediction.upperBoundMs <= budgetMs
    }

    /**
     * ADMIT gate. Deadline safety alone (UB <= budget) only reacts once the queue is at the cliff, so a heavy head
     * (e.g. 24MP) can build a backlog the mandatory tail can no longer undo. This additionally holds the queue
     * stable: while this group's budget is shrinking shot over shot (trend < 0 = falling behind the shutter rate),
     * it keeps [ADMIT_RUNWAY_SHOTS] shots of headroom before a shot stops fitting. No device-specific millisecond
     * threshold: the trigger is derived from the measured budget trend and the learned upper bound.
     */
    private fun admitsUnderQueue(
        queuePressureGroup: Class<out MultiFrameNodeBase>,
        upperBoundMs: Double,
        budgetMs: Long,
    ): Boolean {
        val slackMs = budgetMs - upperBoundMs
        if (slackMs < 0.0) {
            return false
        }
        val trendMs = taskQueueBudgetTrendMap[queuePressureGroup]?.trendMs ?: return true
        if (trendMs >= 0.0) {
            return true
        }
        return slackMs >= ADMIT_RUNWAY_SHOTS * -trendMs
    }

    @Synchronized
    fun updateCapture(
        workloadDurations: Map<WorkloadKey, Long>,
        predictionSnapshots: Collection<SequencePredictionSnapshot>,
    ) {
        val validWorkloadDurations = workloadDurations.filterValues { it > 0L }

        addScoreSamples(validWorkloadDurations, predictionSnapshots)

        validWorkloadDurations.forEach { (workloadKey, durationMs) ->
            workloadKeyModelMap.getOrPut(workloadKey) { WorkloadEwmaModel() }.update(durationMs.toDouble())
        }
    }

    private fun addScoreSamples(
        workloadDurations: Map<WorkloadKey, Long>,
        predictionSnapshots: Collection<SequencePredictionSnapshot>,
    ) {
        val samples = predictionSnapshots.mapNotNull { snapshot ->
            val clampedActualMs = clampedActualDuration(snapshot, workloadDurations) ?: return@mapNotNull null
            val score = score(snapshot.predictedMs, clampedActualMs) ?: return@mapNotNull null
            snapshot.workloadKeySequence to ScoreSample(score = score)
        }

        if (samples.isEmpty()) {
            return
        }

        decayScoreSamples()
        samples.forEach { (workloadKeySequence, sample) ->
            workloadKeySequenceScoreMap.getOrPut(workloadKeySequence) { mutableListOf() } += sample
            workloadKeySequenceShapeScoreMap.getOrPut(workloadKeySequence.shape) { mutableListOf() } += sample
        }
    }

    private fun decayScoreSamples() {
        workloadKeySequenceScoreMap.values.forEach { it.decayWeights() }
        workloadKeySequenceShapeScoreMap.values.forEach { it.decayWeights() }
    }

    private fun MutableList<ScoreSample>.decayWeights() {
        for (index in indices) {
            this[index] = this[index].decayed(SCORE_WEIGHT_DECAY)
        }
        // ponytail: drop the negligible tail so the process-wide singleton's sample lists stay bounded.
        // At decay=0.9 a sample older than ~130 captures weighs < 1e-6 and no longer moves any quantile.
        removeAll { it.weight < SCORE_WEIGHT_PRUNE_THRESHOLD }
    }

    private fun predictSequence(workloadKeySequence: WorkloadKeySequence): SequencePrediction {
        val predictedMs = predictedDuration(workloadKeySequence)
        val upperBoundMs = correctedUpperBound(workloadKeySequence)
        return SequencePrediction(predictedMs, upperBoundMs)
    }

    private fun correctedUpperBound(workloadKeySequence: WorkloadKeySequence): Double {
        // Walk tail -> head. Monotonic inclusion: each suffix's bound is at least its own raw bound and at least its
        // head EWMA plus the already-corrected tail, so nested-sequence bounds never invert. (Since exp(C) >= 1, the
        // tail step max() already returns the raw bound, so no special case for the last workload is needed.)
        val workloadKeys = workloadKeySequence.workloadKeys
        var corrected = 0.0
        for (index in workloadKeys.indices.reversed()) {
            val suffixRawUpperBound = rawUpperBound(WorkloadKeySequence(workloadKeys.drop(index)))
            corrected = maxOf(suffixRawUpperBound, predictedWorkloadDuration(workloadKeys[index]) + corrected)
        }
        return corrected
    }

    private fun rawUpperBound(workloadKeySequence: WorkloadKeySequence): Double {
        val predictedMs = predictedDuration(workloadKeySequence)
        if (predictedMs <= 0.0) {
            return 0.0
        }

        return predictedMs * exp(calibratedScore(workloadKeySequence))
    }

    private fun predictedDuration(workloadKeySequence: WorkloadKeySequence): Double {
        return workloadKeySequence.workloadKeys.sumOf(::predictedWorkloadDuration)
    }

    private fun predictedWorkloadDuration(workloadKey: WorkloadKey): Double {
        // Conservative cross-size coupling: every same-type sibling bucket informs this workload, its EWMA level
        // scaled by the megapixel ratio - a well-sampled MP12 x2 bounds an under-sampled MP24, and a heavy MP24 /2
        // keeps the following MP12 conservative. max() lets cross-size only raise the estimate, never lower it;
        // the sibling with ratio 1.0 is the workload's own model. Predict-time only: the scaled value is never fed
        // back into any model, so buckets never learn from each other.
        return workloadKeyModelMap
            .filterKeys { it.javaClass == workloadKey.javaClass }
            .maxOfOrNull { (siblingKey, model) ->
                model.predictionMs() * siblingKey.sizeBucket.sizeRatio(workloadKey.sizeBucket)
            }
            ?: 0.0
    }

    private fun calibratedScore(workloadKeySequence: WorkloadKeySequence): Double {
        return calibratedScore(
            sequenceSamples = workloadKeySequenceScoreMap[workloadKeySequence].orEmpty(),
            compatibleSamples = workloadKeySequenceShapeScoreMap[workloadKeySequence.shape].orEmpty(),
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
        for (workloadKey in snapshot.workloadKeySequence.workloadKeys) {
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

    private class BudgetTrend(private val alpha: Double) {
        private var lastBudgetMs: Long? = null
        var trendMs: Double? = null
            private set

        fun observe(budgetMs: Long) {
            lastBudgetMs?.let { prev ->
                val deltaMs = (budgetMs - prev).toDouble()
                trendMs = trendMs?.let { it + alpha * (deltaMs - it) } ?: deltaMs
            }
            lastBudgetMs = budgetMs
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

        // Queue runway: shots of budget headroom to preserve before divergence forces a timeout. A small,
        // device-independent shot count -- not a per-model millisecond threshold.
        private const val ADMIT_RUNWAY_SHOTS = 2
    }
}

data class AdmissionDecision(
    val executionPrediction: ExecutionPrediction,
    val sequenceSnapshot: SequencePredictionSnapshot,
)

data class WatchdogTimeoutDecision(
    val timeoutMs: Long,
    val decision: AdmissionDecision,
)

data class SequencePredictionSnapshot(
    val workloadKeySequence: WorkloadKeySequence,
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
    compatibleSamples: List<ScoreSample>,
): Double {
    // Exact sequence residuals win; otherwise borrow only the same workload-type sequence shape.
    return if (sequenceSamples.isNotEmpty()) {
        quantileScore(sequenceSamples)
    } else {
        quantileScore(compatibleSamples)
    }
}

private fun Double.roundToNonNegativeLong(): Long = when {
    isNaN() || this <= 0.0 -> 0L
    this >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
    else -> roundToLong().coerceAtLeast(0L)
}
