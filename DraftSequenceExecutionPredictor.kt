package com.samsung.android.camera.core2.ml

import com.samsung.android.camera.core2.util.CLog
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

private const val TAG = "DraftSequenceExecutionPredictor"

/** Pure OPTIONAL gate shared by runtime prediction and fixed-prediction offline replay. */
internal fun shouldAdmitOptionalWorkload(
    sequencePredictedMs: Double,
    sequenceUpperBoundMs: Double,
    budgetMs: Long,
): Boolean {
    return sequencePredictedMs <= 0.0 || sequenceUpperBoundMs <= budgetMs
}

/**
 * Sequence-aware, phase-aware, adaptive upper bound.
 *
 * Point prediction is the sum of per-workload EWMA values. The safety margin is a multiplicative residual calibrated
 * from positive residual ratios captured before workload EWMA updates; exact decision-sequence residuals win, with
 * global residuals as the cold-sequence fallback.
 *
 * Besides admission, [estimateDraftPath] snapshots the model for [CaptureAvailablePacer], which owns
 * captureAvailable pacing and only consumes these estimates.
 */
class DraftSequenceExecutionPredictor {

    private val workloadKeyDurationTrendMap = mutableMapOf<WorkloadKey, WorkloadDurationTrend>()
    private val workloadSequenceKeyResidualMap = mutableMapOf<WorkloadSequenceKey, MutableList<ResidualSample>>()
    private val globalResidualSamples = mutableListOf<ResidualSample>()

    @Synchronized
    fun predictAdmission(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): AdmissionDecision {
        // Memoized per decision: estimateWorkloadMs scans sibling models, and estimateUpperBoundMs would otherwise
        // rescan them O(n^2) through the suffix walk.
        val workloadPredictedMs = workloadSequenceKey.workloadKeys.associateWith(::estimateWorkloadMs)
        val sequencePredictedMs = sumPredictedMs(workloadSequenceKey, workloadPredictedMs)
        val sequenceUpperBoundMs = estimateUpperBoundMs(workloadSequenceKey, workloadPredictedMs)
        val admit = when (workloadSequenceKey.headWorkloadKey.policy) {
            WorkloadPolicy.OPTIONAL -> fitsUpperBoundBudget(
                sequencePredictedMs,
                sequenceUpperBoundMs,
                preExecutionMetrics.budgetMs,
            )
            WorkloadPolicy.REQUIRED, WorkloadPolicy.RESERVED -> true
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
     * OPTIONAL gate. Admission is bounded by the learned sequence upper bound only; budget deficits are observed
     * separately and consumed by [CaptureAvailablePacer] as the captureAvailable pacing delay.
     */
    private fun fitsUpperBoundBudget(
        sequencePredictedMs: Double,
        sequenceUpperBoundMs: Double,
        budgetMs: Long,
    ): Boolean {
        val admit = shouldAdmitOptionalWorkload(sequencePredictedMs, sequenceUpperBoundMs, budgetMs)
        if (!admit) {
            CLog.w(TAG, "[mhyun2.park] reject admission by upper bound - predictedMs=%f, upperBoundMs=%f, budgetMs=%d", sequencePredictedMs, sequenceUpperBoundMs, budgetMs)
        }

        return admit
    }

    private fun estimateWorkloadMs(workloadKey: WorkloadKey): Double {
        // Conservative cross-size coupling: every same-type sibling bucket informs this workload. A smaller sibling
        // scales UP by the megapixel ratio (a well-sampled MP12 x2 bounds an under-sampled MP24); a larger sibling is
        // used as-is, never scaled down (a heavy MP24 bounds MP12 at its full value, not halved) - the ratio floors at
        // 1.0. max() over siblings only raises the estimate; the ratio-1.0 sibling is the workload's own model.
        // Predict-time only: the scaled value is never fed back into any model, so buckets never learn from each other.
        return workloadKeyDurationTrendMap.entries
            .filter { it.key.javaClass == workloadKey.javaClass }
            .maxOfOrNull { (siblingKey, model) ->
                model.predictionMs() * siblingKey.sizeBucket.sizeRatio(workloadKey.sizeBucket).coerceAtLeast(1.0)
            }
            ?: 0.0
    }

    private fun estimateUpperBoundMs(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        // Walk tail -> head. Monotonic inclusion: each suffix's bound is at least its own raw bound and at least its
        // head EWMA plus the already-corrected tail, so nested-sequence bounds never invert. (Since exp(C) >= 1, the
        // tail step max() already returns the raw bound, so no special case for the last workload is needed.)
        val workloadKeys = workloadSequenceKey.workloadKeys
        var corrected = 0.0
        var suffixPredictedMs = 0.0
        for (index in workloadKeys.indices.reversed()) {
            val workloadEstimateMs = workloadPredictedMs.getValue(workloadKeys[index])
            suffixPredictedMs += workloadEstimateMs
            val suffixRawUpperBoundMs = estimateRawUpperBoundMs(
                WorkloadSequenceKey(workloadKeys.subList(index, workloadKeys.size)),
                suffixPredictedMs,
            )
            corrected = maxOf(suffixRawUpperBoundMs, workloadEstimateMs + corrected)
        }
        return corrected
    }

    private fun estimateRawUpperBoundMs(
        workloadSequenceKey: WorkloadSequenceKey,
        predictedMs: Double,
    ): Double {
        if (predictedMs <= 0.0) {
            return 0.0
        }

        return predictedMs * exp(residualScoreFor(workloadSequenceKey))
    }

    private fun sumPredictedMs(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        return workloadSequenceKey.workloadKeys.sumOf { workloadPredictedMs.getValue(it) }
    }

    private fun residualScoreFor(workloadSequenceKey: WorkloadSequenceKey): Double {
        val sequenceSamples = workloadSequenceKeyResidualMap[workloadSequenceKey]
        return weightedQuantileScore(
            if (sequenceSamples.isNullOrEmpty()) globalResidualSamples else sequenceSamples,
        )
    }

    private fun weightedQuantileScore(samples: List<ResidualSample>): Double {
        val weightedSamples = samples.filter { it.weight > 0.0 }
        if (weightedSamples.isEmpty()) {
            return 0.0
        }

        val totalWeight = weightedSamples.sumOf { it.weight }
        val targetWeight = totalWeight * quantileForSampleSize(
            effectiveSampleSize(weightedSamples.map { it.weight }),
        )
        var cumulativeWeight = 0.0
        for (sample in weightedSamples.sortedBy { it.score }) {
            cumulativeWeight += sample.weight
            if (cumulativeWeight >= targetWeight) {
                return sample.score
            }
        }

        return weightedSamples.maxOf { it.score }
    }

    private fun quantileForSampleSize(sampleSize: Double): Double {
        if (sampleSize <= 0.0) {
            return 0.0
        }
        return 1.0 - 1.0 / (sampleSize + 1.0)
    }

    private fun effectiveSampleSize(weights: List<Double>): Double {
        val sumW = weights.sum()
        val sumW2 = weights.sumOf { it * it }
        if (sumW <= 0.0 || sumW2 <= 0.0) {
            return 0.0
        }

        return (sumW * sumW) / sumW2
    }

    /**
     * Watchdog budget for the OPTIONAL workload at the head of [workloadSequenceKey]: the time left after reserving
     * the sequence's mandatory RESERVED work. REQUIRED workloads are measured but not reserved for.
     * [WatchdogTimeoutDecision.decision] is null when the sequence has no mandatory reserve - the whole budget is
     * granted and there is nothing to calibrate.
     */
    @Synchronized
    fun predictWatchdogTimeout(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): WatchdogTimeoutDecision {
        val reserveWorkloadKeys = mandatoryReserveWorkloadKeys(workloadSequenceKey)
        if (reserveWorkloadKeys.isEmpty()) {
            return WatchdogTimeoutDecision(timeoutMs = preExecutionMetrics.budgetMs.coerceAtLeast(0L), decision = null)
        }

        // The reserve is RESERVED-only, so this internal decision's admit bit is unused.
        val decision = predictAdmission(WorkloadSequenceKey(reserveWorkloadKeys), preExecutionMetrics)
        val remainingBudgetMs = preExecutionMetrics.budgetMs - decision.executionPrediction.sequencePredictedUpperBoundMs
        val timeoutMs = when {
            remainingBudgetMs.isNaN() || remainingBudgetMs <= 0.0 -> 0L
            remainingBudgetMs >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> remainingBudgetMs.roundToLong().coerceAtLeast(0L)
        }

        return WatchdogTimeoutDecision(timeoutMs, decision)
    }

    /** RESERVED workloads in the observed draft path, including a RESERVED head for encoding-only captures. */
    private fun mandatoryReserveWorkloadKeys(workloadSequenceKey: WorkloadSequenceKey): List<WorkloadKey> {
        return workloadSequenceKey.workloadKeys
            .filter { plannedWorkloadKey -> plannedWorkloadKey.policy == WorkloadPolicy.RESERVED }
    }

    @Synchronized
    fun learnFromCapture(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
    ) {
        val validWorkloadDurations = workloadDurations.filterValues { it > 0L }

        addResidualSamples(validWorkloadDurations, admissionDecisions)

        validWorkloadDurations.forEach { (workloadKey, durationMs) ->
            workloadKeyDurationTrendMap.getOrPut(workloadKey) { WorkloadDurationTrend() }
                .observeDurationMs(durationMs.toDouble())
        }
    }

    private fun addResidualSamples(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
    ) {
        val samples = admissionDecisions.mapNotNull { decision ->
            val actualMs = clampedActualMs(decision, workloadDurations) ?: return@mapNotNull null
            val score = logResidualScore(
                predictedMs = decision.executionPrediction.sequencePredictedDurationMs,
                actualMs = actualMs,
            ) ?: return@mapNotNull null
            decision.workloadSequenceKey to ResidualSample(score = score)
        }

        if (samples.isEmpty()) {
            return
        }

        decayResidualWeights()
        samples.forEach { (workloadSequenceKey, sample) ->
            globalResidualSamples += sample
            workloadSequenceKeyResidualMap.getOrPut(workloadSequenceKey) { mutableListOf() } += sample
        }
    }

    /**
     * Containment-scoped actual: each workload contributes max(its decision-time prediction, its actual), so a
     * single workload's overrun is reflected at the sequence's scale even when another workload happened to run
     * fast that capture. Returns null if any workload in the sequence did not run (incomplete sequence cannot be
     * scored).
     */
    private fun clampedActualMs(
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

    private fun logResidualScore(predictedMs: Double, actualMs: Double): Double? {
        if (predictedMs <= 0.0 || actualMs <= 0.0) {
            return null
        }
        return maxOf(0.0, ln(actualMs / predictedMs))
    }

    private fun decayResidualWeights() {
        globalResidualSamples.decayAndPrune()
        workloadSequenceKeyResidualMap.values.forEach { it.decayAndPrune() }
    }

    private fun MutableList<ResidualSample>.decayAndPrune() {
        for (index in indices) {
            this[index] = this[index].decayed(SCORE_WEIGHT_DECAY)
        }
        // ponytail: drop the negligible tail so the process-wide singleton's sample lists stay bounded.
        // At decay=0.9 a sample older than ~130 captures weighs < 1e-6 and no longer moves any quantile.
        removeAll { it.weight < SCORE_WEIGHT_PRUNE_THRESHOLD }
    }

    /**
     * Point estimate for [workloadSequenceKey] plus its mandatory RESERVED-work upper bound, read in one consistent
     * model snapshot - the inputs [CaptureAvailablePacer] paces captureAvailable callbacks with. The sequence's own
     * upper bound is deliberately not offered: pacing bounds a capture by observed draft wall time, not by this
     * model, so exposing one only invites re-coupling the two (see the pacer's preferredDraftPathCeilingMs).
     */
    @Synchronized
    fun estimateDraftPath(workloadSequenceKey: WorkloadSequenceKey): DraftPathEstimate {
        val workloadPredictedMs = workloadSequenceKey.workloadKeys.associateWith(::estimateWorkloadMs)
        val reserveWorkloadKeys = mandatoryReserveWorkloadKeys(workloadSequenceKey)
        return DraftPathEstimate(
            predictedMs = sumPredictedMs(workloadSequenceKey, workloadPredictedMs),
            mandatoryReserveUpperBoundMs = if (reserveWorkloadKeys.isEmpty()) {
                0.0
            } else {
                estimateUpperBoundMs(
                    WorkloadSequenceKey(reserveWorkloadKeys),
                    reserveWorkloadKeys.associateWith(workloadPredictedMs::getValue),
                )
            },
        )
    }

    /** Per-workload duration level, EWMA-tracked - the point-prediction half of the model. */
    private class WorkloadDurationTrend {
        private var sampleCount: Int = 0
        private var levelMs: Double? = null

        fun predictionMs(): Double = levelMs ?: 0.0

        fun observeDurationMs(actualMs: Double) {
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

    private data class ResidualSample(
        val score: Double,
        val weight: Double = 1.0,
    ) {
        fun decayed(factor: Double): ResidualSample = copy(weight = weight * factor)
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

/**
 * One admission decision. [executionPrediction] is the persisted decision record (replay-ready); the remaining
 * fields are decision-time calibration inputs that [DraftSequenceExecutionPredictor.learnFromCapture] consumes once
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

/** One consistent model snapshot for a draft path: point sum plus the mandatory RESERVED tail's upper bound. */
data class DraftPathEstimate(
    val predictedMs: Double,
    val mandatoryReserveUpperBoundMs: Double,
)
