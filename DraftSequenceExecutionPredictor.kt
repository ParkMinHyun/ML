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
            nodeSnapshot = SequencePredictionSnapshot(
                sequenceKey = nodeKey,
                predictedMs = nodePrediction.predictedMs,
            ),
            sequenceSnapshot = SequencePredictionSnapshot(
                sequenceKey = sequenceKey,
                predictedMs = sequencePrediction.predictedMs,
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
            val actualMs = actualSequenceDuration(snapshot.sequenceKey, workloadDurations) ?: return@mapNotNull null
            val score = score(snapshot.predictedMs, actualMs) ?: return@mapNotNull null
            snapshot.sequenceKey to ScoreSample(score = score, sourcePredictedMs = snapshot.predictedMs)
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
    }

    private fun predictSequence(sequenceKey: WorkloadSequenceKey): SequencePrediction {
        val predictedMs = predictedDuration(sequenceKey)
        val upperBoundMs = correctedUpperBound(sequenceKey)
        return SequencePrediction(predictedMs, upperBoundMs)
    }

    private fun correctedUpperBound(sequenceKey: WorkloadSequenceKey): Double {
        var correctedTailUpperBound = 0.0
        for (index in sequenceKey.workloads.indices.reversed()) {
            val suffixWorkloads = sequenceKey.workloads.drop(index)
            val suffixKey = WorkloadSequenceKey(suffixWorkloads)
            val rawUpperBound = rawUpperBound(suffixKey)
            correctedTailUpperBound = if (index == sequenceKey.workloads.lastIndex) {
                rawUpperBound
            } else {
                maxOf(rawUpperBound, predictedWorkloadDuration(sequenceKey.workloads[index]) + correctedTailUpperBound)
            }
        }
        return correctedTailUpperBound
    }

    private fun rawUpperBound(sequenceKey: WorkloadSequenceKey): Double {
        val predictedMs = predictedDuration(sequenceKey)
        if (predictedMs <= 0.0) {
            return 0.0
        }

        val score = calibratedScore(sequenceKey, predictedMs)
        return sequenceKey.workloads.sumOf { workloadKey ->
            val workloadPredictedMs = predictedWorkloadDuration(workloadKey)
            if (workloadPredictedMs <= 0.0) {
                0.0
            } else {
                val workloadWeight = workloadPredictedMs / predictedMs
                workloadPredictedMs * exp(score * workloadWeight)
            }
        }
    }

    private fun predictedDuration(sequenceKey: WorkloadSequenceKey): Double {
        return sequenceKey.workloads.sumOf(::predictedWorkloadDuration)
    }

    private fun predictedWorkloadDuration(workloadKey: WorkloadKey): Double {
        return workloadModels[workloadKey]?.predictionMs() ?: 0.0
    }

    private fun calibratedScore(sequenceKey: WorkloadSequenceKey, targetPredictedMs: Double): Double {
        return calibratedScore(
            targetPredictedMs = targetPredictedMs,
            sequenceSamples = sequenceScoreSamples[sequenceKey].orEmpty(),
            globalSamples = globalScoreSamples,
        )
    }

    private fun actualSequenceDuration(
        sequenceKey: WorkloadSequenceKey,
        workloadDurations: Map<WorkloadKey, Long>,
    ): Double? {
        var total = 0.0
        for (workloadKey in sequenceKey.workloads) {
            val durationMs = workloadDurations[workloadKey] ?: return null
            total += durationMs.toDouble()
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

        private const val WORKLOAD_EWMA_ALPHA = 0.20
        private const val SCORE_WEIGHT_DECAY = 1.0 - WORKLOAD_EWMA_ALPHA
    }
}

data class SeqPawDecision(
    val executionPrediction: ExecutionPrediction,
    val nodeSnapshot: SequencePredictionSnapshot,
    val sequenceSnapshot: SequencePredictionSnapshot,
)

data class SeqPawTimeoutDecision(
    val timeoutMs: Long,
    val decision: SeqPawDecision,
)

data class SequencePredictionSnapshot(
    val sequenceKey: WorkloadSequenceKey,
    val predictedMs: Double,
)

data class ScoreSample(
    val score: Double,
    val sourcePredictedMs: Double,
    val weight: Double = 1.0,
) {
    fun decayed(factor: Double): ScoreSample = copy(weight = weight * factor)
}

fun ScoreSample.scaledForTarget(targetPredictedMs: Double): ScoreSample? {
    if (sourcePredictedMs <= 0.0 || targetPredictedMs <= 0.0) {
        return null
    }

    val smallerWeight = minOf(sourcePredictedMs, targetPredictedMs)
    val largerWeight = maxOf(sourcePredictedMs, targetPredictedMs)
    val relativeWeight = smallerWeight / largerWeight
    return copy(score = score * relativeWeight)
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
    targetPredictedMs: Double,
    sequenceSamples: List<ScoreSample>,
    globalSamples: List<ScoreSample>,
): Double {
    if (targetPredictedMs <= 0.0) {
        return 0.0
    }

    val weightedSamples = (globalSamples + sequenceSamples).mapNotNull { sample ->
        sample.scaledForTarget(targetPredictedMs)
    }
    return quantileScore(weightedSamples)
}

private fun Double.roundToNonNegativeLong(): Long = when {
    isNaN() || this <= 0.0 -> 0L
    this >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
    else -> roundToLong().coerceAtLeast(0L)
}
