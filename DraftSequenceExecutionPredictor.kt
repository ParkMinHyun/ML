package com.samsung.android.camera.core2.ml

import com.samsung.android.camera.core2.util.CLog
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

private const val TAG = "DraftSequenceExecutionPredictor"

/**
 * Sequence-aware, phase-aware, adaptive upper bound.
 *
 * Point prediction is the sum of per-workload EWMA values. The safety margin is a multiplicative residual calibrated at
 * decision-sequence level from positive residual ratios captured before workload EWMA updates.
 */
class DraftSequenceExecutionPredictor {

    private val workloadDurationTrendMap = mutableMapOf<WorkloadKey, WorkloadDurationTrend>()
    private val workloadSequenceKeyScoreMap = mutableMapOf<WorkloadSequenceKey, MutableList<ScoreSample>>()
    private val workloadSequenceShapeScoreMap = mutableMapOf<WorkloadSequenceShape, MutableList<ScoreSample>>()
    private val captureAvailableBudgetTrend = BudgetTrend(WORKLOAD_EWMA_ALPHA)

    @Synchronized
    fun predictAdmission(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): AdmissionDecision {
        // Memoized per decision: predictedWorkloadDuration scans the sibling models, and the suffix walk in
        // correctedSequenceUpperBound would otherwise rescan them O(n^2) times per decision.
        val workloadPredictedMs = workloadSequenceKey.workloadKeys.associateWith(::predictedWorkloadDuration)
        val sequencePredictedMs = predictedSequenceDuration(workloadSequenceKey, workloadPredictedMs)
        val sequenceUpperBoundMs = correctedSequenceUpperBound(workloadSequenceKey, workloadPredictedMs)
        val admit = when (workloadSequenceKey.headWorkloadKey.policy) {
            WorkloadPolicy.ADMIT -> admitUnderBudgetPolicy(
                sequencePredictedMs,
                sequenceUpperBoundMs,
                preExecutionMetrics.budgetMs,
            )
            WorkloadPolicy.OBSERVE, WorkloadPolicy.COMPLETE -> true
        }
        return AdmissionDecision(
            executionPrediction = ExecutionPrediction(
                admit = admit,
                sequencePredictedDurationMs = sequencePredictedMs,
                sequencePredictedUpperBoundMs = sequenceUpperBoundMs,
                workloadSequenceKey = workloadSequenceKey.toReplayString(),
            ),
            workloadSequenceKey = workloadSequenceKey,
            workloadPredictedMs = workloadPredictedMs,
        )
    }

    /**
     * Watchdog budget for the ADMIT workload at the head of [workloadSequenceKey]: the time left after reserving
     * the sequence's mandatory COMPLETE tail. OBSERVE workloads are measured but not reserved for.
     * [WatchdogTimeoutDecision.decision] is null when the sequence has no mandatory tail - the whole budget is
     * granted and there is nothing to calibrate.
     */
    @Synchronized
    fun predictWatchdogTimeout(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): WatchdogTimeoutDecision {
        val reserveWorkloadKeys = workloadSequenceKey.workloadKeys
            .drop(1)
            .filter { plannedWorkloadKey -> plannedWorkloadKey.policy == WorkloadPolicy.COMPLETE }
        if (reserveWorkloadKeys.isEmpty()) {
            return WatchdogTimeoutDecision(timeoutMs = preExecutionMetrics.budgetMs.coerceAtLeast(0L), decision = null)
        }

        // The reserve tail is COMPLETE-only, so this internal decision's admit bit is unused.
        val decision = predictAdmission(WorkloadSequenceKey(reserveWorkloadKeys), preExecutionMetrics)
        val timeoutMs = (preExecutionMetrics.budgetMs - decision.executionPrediction.sequencePredictedUpperBoundMs)
            .roundToNonNegativeLong()

        return WatchdogTimeoutDecision(timeoutMs, decision)
    }

    /** Feeds the draft sequence's first ADMIT budget into the captureAvailable back-pressure trend. */
    @Synchronized
    fun observeBudget(workloadSequenceKey: WorkloadSequenceKey, budgetMs: Long) {
        if (workloadSequenceKey.headWorkloadKey.policy == WorkloadPolicy.ADMIT) {
            captureAvailableBudgetTrend.observe(budgetMs)
        }
    }

    /**
     * Capture-available pressure derived from the first ADMIT budget trend. A negative trend is diagnostic pressure:
     * legacy service-rate pacing should be retained because the observed budget is already falling.
     */
    @Synchronized
    fun predictCaptureAvailableDelayMs(): Long = captureAvailableBudgetTrend.fallingDelayMs(CAPTURE_AVAILABLE_DELAY_SCALE)

    /**
     * Positive budget recovery can be spent as an advance credit against legacy service-rate delay. Only one EWMA step
     * of recovery is spent per callback, so a large budget reset cannot immediately collapse the delay to zero.
     */
    @Synchronized
    fun predictCaptureAvailableAdvanceMs(): Long = captureAvailableBudgetTrend.risingAdvanceMs(CAPTURE_AVAILABLE_ADVANCE_SCALE)

    @Synchronized
    fun hasCaptureAvailableBudgetTrend(): Boolean = captureAvailableBudgetTrend.hasTrend()

    /**
     * ADMIT gate. Admission is bounded by the learned sequence upper bound only; budget trend is observed separately
     * and consumed by [predictCaptureAvailableDelayMs] as captureAvailable back-pressure.
     */
    private fun admitUnderBudgetPolicy(
        sequencePredictedMs: Double,
        sequenceUpperBoundMs: Double,
        budgetMs: Long,
    ): Boolean {
        // Cold start: nothing learned for this sequence yet, so admit and let the models start learning.
        if (sequencePredictedMs <= 0.0) {
            return true
        }

        val slackMs = budgetMs - sequenceUpperBoundMs
        val rejectedByUpperBound = slackMs < 0.0

        if (rejectedByUpperBound) {
            CLog.w(TAG, "[mhyun2.park] reject admission by upper bound - predictedMs=%f, upperBoundMs=%f, budgetMs=%d", sequencePredictedMs, sequenceUpperBoundMs, budgetMs)
        }

        return !rejectedByUpperBound
    }

    @Synchronized
    fun updateCapture(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
    ) {
        val validWorkloadDurations = workloadDurations.filterValues { it > 0L }

        addScoreSamples(validWorkloadDurations, admissionDecisions)

        validWorkloadDurations.forEach { (workloadKey, durationMs) ->
            workloadDurationTrendMap.getOrPut(workloadKey) { WorkloadDurationTrend() }.update(durationMs.toDouble())
        }
    }

    private fun addScoreSamples(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
    ) {
        val samples = admissionDecisions.mapNotNull { decision ->
            val clampedActualMs = clampedSequenceActualDuration(decision, workloadDurations) ?: return@mapNotNull null
            val score = score(decision.executionPrediction.sequencePredictedDurationMs, clampedActualMs) ?: return@mapNotNull null
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
        return workloadDurationTrendMap.entries
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

    /** Per-workload duration level, EWMA-tracked - the point-prediction half of the model. */
    private class WorkloadDurationTrend {
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

    /** Budget delta between observations, EWMA-tracked - negative means the queue is falling behind the shutter rate. */
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

        fun hasTrend(): Boolean = trendMs != null

        fun fallingDelayMs(delayScale: Double): Long {
            return trendMs
                ?.takeIf { it < 0.0 }
                ?.let { -it * delayScale }
                ?.roundToNonNegativeLong()
                ?: 0L
        }

        fun risingAdvanceMs(advanceScale: Double): Long {
            return trendMs
                ?.takeIf { it > 0.0 }
                ?.let { it * advanceScale }
                ?.roundToNonNegativeLong()
                ?: 0L
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
        private const val CAPTURE_AVAILABLE_DELAY_SCALE = 1.0 / (1.0 - WORKLOAD_EWMA_ALPHA)
        private const val CAPTURE_AVAILABLE_ADVANCE_SCALE = WORKLOAD_EWMA_ALPHA
    }
}

/**
 * One admission decision. [executionPrediction] is the persisted decision record (replay-ready); the remaining
 * fields are decision-time calibration inputs that [DraftSequenceExecutionPredictor.updateCapture] consumes once
 * at capture end. They stay outside [ExecutionPrediction] on purpose: a typed key and a per-workload map do not
 * belong in the metrics store.
 */
data class AdmissionDecision(
    val executionPrediction: ExecutionPrediction,
    /** Sequence this decision was made for; capture-end score samples are keyed by it. */
    val workloadSequenceKey: WorkloadSequenceKey,
    /** Per-workload decision-time prediction, used to clamp workload underruns so one workload's spike is not diluted. */
    val workloadPredictedMs: Map<WorkloadKey, Double>,
)

data class WatchdogTimeoutDecision(
    val timeoutMs: Long,
    /** Reservation decision backing [timeoutMs]; null when the sequence had no mandatory tail to reserve. */
    val decision: AdmissionDecision?,
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
