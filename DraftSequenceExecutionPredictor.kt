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
    private val workloadSequenceKeyScoreMap = mutableMapOf<WorkloadSequenceKey, MutableList<ScoreSample>>()
    private val workloadSequenceShapeScoreMap = mutableMapOf<WorkloadSequenceShape, MutableList<ScoreSample>>()

    private val queuePressureBudgetTrendMap = mutableMapOf<Class<out MultiFrameNodeBase>, BudgetTrend>()

    @Synchronized
    fun predictAdmission(
        workloadSequenceKey: WorkloadSequenceKey,
        queuePressureGroup: Class<out MultiFrameNodeBase>?,
        preExecutionMetrics: PreExecutionMetrics,
    ): AdmissionDecision {
        // Memoized per decision: predictedWorkloadDuration scans the sibling models, and the suffix walk in
        // correctedSequenceUpperBound would otherwise rescan them O(n^2) times per decision.
        val workloadPredictedMs = workloadSequenceKey.workloadKeys.associateWith(::predictedWorkloadDuration)
        val sequencePredictedMs = predictedSequenceDuration(workloadSequenceKey, workloadPredictedMs)
        val sequenceUpperBoundMs = correctedSequenceUpperBound(workloadSequenceKey, workloadPredictedMs)
        val admit = admitUnderBudgetPolicy(
            queuePressureGroup,
            sequencePredictedMs,
            sequenceUpperBoundMs,
            preExecutionMetrics.budgetMs,
        )
        return AdmissionDecision(
            executionPrediction = ExecutionPrediction(
                admit = admit,
                sequencePredictedDurationMs = sequencePredictedMs.roundToNonNegativeLong(),
                sequencePredictedUpperBoundMs = sequenceUpperBoundMs.roundToNonNegativeLong(),
                workloadSequenceKey = workloadSequenceKey.toReplayString(),
                queuePressureGroup = queuePressureGroup?.simpleName,
            ),
            workloadSequenceKey = workloadSequenceKey,
            sequencePredictedMs = sequencePredictedMs,
            workloadPredictedMs = workloadPredictedMs,
        )
    }

    @Synchronized
    fun predictWatchdogTimeout(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): WatchdogTimeoutDecision {
        // The watchdog reservation is not queue-gated; this internal decision's admit bit is unused.
        val decision = predictAdmission(workloadSequenceKey, queuePressureGroup = null, preExecutionMetrics)
        val timeoutMs = (preExecutionMetrics.budgetMs - decision.executionPrediction.sequencePredictedUpperBoundMs)
            .coerceAtLeast(0L)

        return WatchdogTimeoutDecision(timeoutMs, decision)
    }

    @Synchronized
    fun observeQueuePressureBudget(
        queuePressureGroup: Class<out MultiFrameNodeBase>,
        budgetMs: Long,
    ) {
        queuePressureBudgetTrendMap.getOrPut(queuePressureGroup) { BudgetTrend(WORKLOAD_EWMA_ALPHA) }.observe(budgetMs)
    }

    private fun admitUnderBudgetPolicy(
        queuePressureGroup: Class<out MultiFrameNodeBase>?,
        sequencePredictedMs: Double,
        sequenceUpperBoundMs: Double,
        budgetMs: Long,
    ): Boolean {
        return when {
            // Cold start: nothing learned for this sequence yet, so admit and let the models start learning.
            sequencePredictedMs <= 0.0 -> true
            queuePressureGroup != null -> admitsUnderQueue(queuePressureGroup, sequenceUpperBoundMs, budgetMs)
            else -> sequenceUpperBoundMs <= budgetMs
        }
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
        val trendMs = queuePressureBudgetTrendMap[queuePressureGroup]?.trendMs ?: return true
        if (trendMs >= 0.0) {
            return true
        }
        return slackMs >= ADMIT_RUNWAY_SHOTS * -trendMs
    }

    @Synchronized
    fun updateCapture(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
    ) {
        val validWorkloadDurations = workloadDurations.filterValues { it > 0L }

        addScoreSamples(validWorkloadDurations, admissionDecisions)

        validWorkloadDurations.forEach { (workloadKey, durationMs) ->
            workloadKeyModelMap.getOrPut(workloadKey) { WorkloadEwmaModel() }.update(durationMs.toDouble())
        }
    }

    private fun addScoreSamples(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
    ) {
        val samples = admissionDecisions.mapNotNull { decision ->
            val clampedActualMs = clampedSequenceActualDuration(decision, workloadDurations) ?: return@mapNotNull null
            val score = score(decision.sequencePredictedMs, clampedActualMs) ?: return@mapNotNull null
            decision.workloadSequenceKey to ScoreSample(score = score)
        }

        if (samples.isEmpty()) {
            return
        }

        decayScoreSamples()
        samples.forEach { (workloadSequenceKey, sample) ->
            workloadSequenceKeyScoreMap.getOrPut(workloadSequenceKey) { mutableListOf() } += sample
            workloadSequenceShapeScoreMap.getOrPut(workloadSequenceKey.shape) { mutableListOf() } += sample
        }
    }

    private fun decayScoreSamples() {
        workloadSequenceKeyScoreMap.values.forEach { it.decayWeights() }
        workloadSequenceShapeScoreMap.values.forEach { it.decayWeights() }
    }

    private fun MutableList<ScoreSample>.decayWeights() {
        for (index in indices) {
            this[index] = this[index].decayed(SCORE_WEIGHT_DECAY)
        }
        // ponytail: drop the negligible tail so the process-wide singleton's sample lists stay bounded.
        // At decay=0.9 a sample older than ~130 captures weighs < 1e-6 and no longer moves any quantile.
        removeAll { it.weight < SCORE_WEIGHT_PRUNE_THRESHOLD }
    }

    private fun correctedSequenceUpperBound(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        // Walk tail -> head. Monotonic inclusion: each suffix's bound is at least its own raw bound and at least its
        // head EWMA plus the already-corrected tail, so nested-sequence bounds never invert. (Since exp(C) >= 1, the
        // tail step max() already returns the raw bound, so no special case for the last workload is needed.)
        val workloadKeys = workloadSequenceKey.workloadKeys
        var corrected = 0.0
        for (index in workloadKeys.indices.reversed()) {
            val suffixRawUpperBound = rawSequenceUpperBound(WorkloadSequenceKey(workloadKeys.drop(index)), workloadPredictedMs)
            corrected = maxOf(suffixRawUpperBound, workloadPredictedMs.getValue(workloadKeys[index]) + corrected)
        }
        return corrected
    }

    private fun rawSequenceUpperBound(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        val predictedMs = predictedSequenceDuration(workloadSequenceKey, workloadPredictedMs)
        if (predictedMs <= 0.0) {
            return 0.0
        }

        return predictedMs * exp(calibratedSequenceScore(workloadSequenceKey))
    }

    private fun predictedSequenceDuration(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        return workloadSequenceKey.workloadKeys.sumOf { workloadPredictedMs.getValue(it) }
    }

    private fun predictedWorkloadDuration(workloadKey: WorkloadKey): Double {
        // Conservative cross-size coupling: every same-type sibling bucket informs this workload, its EWMA level
        // scaled by the megapixel ratio - a well-sampled MP12 x2 bounds an under-sampled MP24, and a heavy MP24 /2
        // keeps the following MP12 conservative. max() lets cross-size only raise the estimate, never lower it;
        // the sibling with ratio 1.0 is the workload's own model. Predict-time only: the scaled value is never fed
        // back into any model, so buckets never learn from each other.
        return workloadKeyModelMap.entries
            .filter { it.key.javaClass == workloadKey.javaClass }
            .maxOfOrNull { (siblingKey, model) ->
                model.predictionMs() * siblingKey.sizeBucket.sizeRatio(workloadKey.sizeBucket)
            }
            ?: 0.0
    }

    private fun calibratedSequenceScore(workloadSequenceKey: WorkloadSequenceKey): Double {
        return calibratedScore(
            sequenceSamples = workloadSequenceKeyScoreMap[workloadSequenceKey].orEmpty(),
            compatibleSamples = workloadSequenceShapeScoreMap[workloadSequenceKey.shape].orEmpty(),
        )
    }

    /**
     * Containment-scoped actual: each workload contributes max(its decision-time prediction, its actual), so a
     * single workload's overrun is reflected at the sequence's scale even when another workload happened to run
     * fast that capture. Returns null if any workload in the sequence did not run (incomplete sequence cannot be
     * scored).
     */
    private fun clampedSequenceActualDuration(
        decision: AdmissionDecision,
        workloadDurations: Map<WorkloadKey, Long>,
    ): Double? {
        var total = 0.0
        for (workloadKey in decision.workloadSequenceKey.workloadKeys) {
            val actualMs = workloadDurations[workloadKey] ?: return null
            val predictedMs = decision.workloadPredictedMs[workloadKey] ?: 0.0
            total += maxOf(predictedMs, actualMs.toDouble())
        }
        return total.takeIf { it > 0.0 }
    }

    private class WorkloadEwmaModel {
        private var sampleCount: Int = 0
        private var levelMs: Double? = null

        fun predictionMs(): Double = levelMs ?: 0.0

        fun update(actualMs: Double) {
            if (actualMs <= 0.0) {
                return
            }

            sampleCount++
            val currentLevelMs = levelMs
            levelMs = if (currentLevelMs == null) {
                actualMs
            } else {
                val effectiveAlpha = maxOf(WORKLOAD_EWMA_ALPHA, 1.0 / sampleCount)
                currentLevelMs + effectiveAlpha * (actualMs - currentLevelMs)
            }
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

/**
 * One admission decision. [executionPrediction] is the persisted decision record (rounded, replay-ready);
 * the remaining fields are decision-time calibration inputs that [DraftSequenceExecutionPredictor.updateCapture]
 * consumes once at capture end. They stay outside [ExecutionPrediction] on purpose: residual scoring needs the
 * unrounded values, and neither a Double duplicate nor a per-workload map belongs in the metrics store.
 */
data class AdmissionDecision(
    val executionPrediction: ExecutionPrediction,
    /** Sequence this decision was made for; capture-end score samples are keyed by it. */
    val workloadSequenceKey: WorkloadSequenceKey,
    /** Decision-time sequence prediction, kept unrounded for capture-end residual scoring. */
    val sequencePredictedMs: Double,
    /** Per-workload decision-time prediction, used to clamp workload underruns so one workload's spike is not diluted. */
    val workloadPredictedMs: Map<WorkloadKey, Double>,
)

data class WatchdogTimeoutDecision(
    val timeoutMs: Long,
    val decision: AdmissionDecision,
)

internal data class ScoreSample(
    val score: Double,
    val weight: Double = 1.0,
) {
    fun decayed(factor: Double): ScoreSample = copy(weight = weight * factor)
}

internal fun score(predictedMs: Double, actualMs: Double): Double? {
    if (predictedMs <= 0.0 || actualMs <= 0.0) {
        return null
    }
    return maxOf(0.0, ln(actualMs / predictedMs))
}

internal fun effectiveSampleSize(weights: List<Double>): Double {
    val sumW = weights.sum()
    val sumW2 = weights.sumOf { it * it }
    if (sumW <= 0.0 || sumW2 <= 0.0) {
        return 0.0
    }

    return (sumW * sumW) / sumW2
}

internal fun adaptiveQuantile(effectiveSampleSize: Double): Double {
    if (effectiveSampleSize <= 0.0) {
        return 0.0
    }
    return 1.0 - 1.0 / (effectiveSampleSize + 1.0)
}

internal fun quantileScore(samples: List<ScoreSample>): Double {
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

internal fun calibratedScore(
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
