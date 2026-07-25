package com.samsung.android.camera.core2.ml

import com.samsung.android.camera.core2.util.CLog
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

private const val TAG = "DraftSequenceExecutionPredictor"

/**
 * Sequence-aware, phase-aware, adaptive upper bound.
 *
 * Point prediction is the sum of per-workload baselines multiplied by one shared thermal-condition factor. The safety
 * margin is a multiplicative residual calibrated from positive residual ratios captured before model updates; exact
 * decision-sequence residuals win, with global residuals as the cold-sequence fallback.
 *
 * Besides admission, [estimateDraftSequence] snapshots the model for [CaptureAvailablePacer], which owns
 * captureAvailable pacing and only consumes these estimates.
 *
 * Owned by the draft-saving task manager and shared by the profilers it creates across captures, so the model
 * lives exactly as long as the draft pipeline it learned from.
 */
class DraftSequenceExecutionPredictor {

    private val workloadDurationTrend = WorkloadDurationTrend()
    private val workloadSequenceResidual = WorkloadSequenceResidual()

    @Synchronized
    fun predictAdmission(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): AdmissionDecision {
        // Memoized per decision: cold-workload estimates scan sibling baselines, and the suffix walk reuses the result.
        val workloadPredictedMs = workloadDurationTrend.estimateAll(workloadSequenceKey.workloadKeys)
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
        val admit = admitsOptionalWorkload(sequencePredictedMs, sequenceUpperBoundMs, budgetMs)
        if (!admit) {
            CLog.w(TAG, "[mhyun2.park] reject admission by upper bound - predictedMs=%f, upperBoundMs=%f, budgetMs=%d", sequencePredictedMs, sequenceUpperBoundMs, budgetMs)
        }

        return admit
    }

    private fun estimateUpperBoundMs(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        // Walk tail -> head. Monotonic inclusion: each suffix's bound is at least its own raw bound and at least its
        // head estimate plus the already-corrected tail, so nested-sequence bounds never invert. (Since exp(C) >= 1,
        // the tail step max() already returns the raw bound, so no special case for the last workload is needed.)
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

        return predictedMs * exp(workloadSequenceResidual.estimateScore(workloadSequenceKey))
    }

    private fun sumPredictedMs(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        return workloadSequenceKey.workloadKeys.sumOf { workloadPredictedMs.getValue(it) }
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
        val reserveWorkloadKeys = selectMandatoryReserveWorkloadKeys(workloadSequenceKey)
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

    /** RESERVED workloads in the observed draft sequence, including a RESERVED head for encoding-only captures. */
    private fun selectMandatoryReserveWorkloadKeys(workloadSequenceKey: WorkloadSequenceKey): List<WorkloadKey> {
        return workloadSequenceKey.workloadKeys
            .filter { plannedWorkloadKey -> plannedWorkloadKey.policy == WorkloadPolicy.RESERVED }
    }

    @Synchronized
    fun learnFromCapture(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
    ) {
        val validWorkloadDurations = workloadDurations.filterValues { it > 0L }
        workloadSequenceResidual.observe(validWorkloadDurations, admissionDecisions)
        workloadDurationTrend.observe(validWorkloadDurations)
    }

    /**
     * Point estimate for [workloadSequenceKey] plus its mandatory RESERVED-work upper bound, read in one consistent
     * model snapshot - the inputs [CaptureAvailablePacer] paces captureAvailable callbacks with. The sequence's own
     * upper bound is deliberately not offered: pacing bounds a capture by observed draft wall time, not by this
     * model, so exposing one only invites re-coupling the two (see the pacer's sessionPlannedCeilingMs).
     */
    @Synchronized
    fun estimateDraftSequence(workloadSequenceKey: WorkloadSequenceKey): DraftSequenceEstimate {
        val workloadPredictedMs = workloadDurationTrend.estimateAll(workloadSequenceKey.workloadKeys)
        val reserveWorkloadKeys = selectMandatoryReserveWorkloadKeys(workloadSequenceKey)
        return DraftSequenceEstimate(
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

    /**
     * Per-workload duration trend as a multiplicative decomposition:
     * duration(size) ≈ baseline(size) × shared condition.
     *
     * The per-size baseline is condition-stripped and structurally stable. The shared condition is the recency-
     * weighted median of recent duration/baseline ratios, robust to one stalled draft while carrying sustained
     * thermal throttling across workload families and sizes.
     */
    private class WorkloadDurationTrend {
        private val baselineByWorkload = mutableMapOf<WorkloadKey, BaselineMean>()
        private val conditionScores = RecencyWeightedDistribution()
        private var learnedConditionFactor = 1.0

        /** Reads one condition snapshot and estimates every workload without recomputing its weighted median. */
        fun estimateAll(workloadKeys: List<WorkloadKey>): Map<WorkloadKey, Double> {
            val conditionSnapshot = learnedConditionFactor
            return workloadKeys.associateWith { workloadKey -> estimate(workloadKey, conditionSnapshot) }
        }

        fun observe(workloadDurations: Map<WorkloadKey, Long>) {
            // Both halves read the PRE-update condition so they cannot feed back within one capture.
            val conditionSnapshot = learnedConditionFactor
            conditionScores.decay()
            workloadDurations.forEach { (workloadKey, durationMs) ->
                val observedMs = durationMs.toDouble()
                val baselineSnapshot = baselineByWorkload[workloadKey]?.meanMs()
                if (baselineSnapshot != null && baselineSnapshot > 0.0) {
                    conditionScores.add(observedMs / baselineSnapshot)
                }
                baselineByWorkload.getOrPut(workloadKey) { BaselineMean() }.observe(observedMs / conditionSnapshot)
            }
            learnedConditionFactor = conditionScores.median().takeIf { it > 0.0 } ?: 1.0
        }

        private fun estimate(workloadKey: WorkloadKey, conditionFactor: Double): Double {
            baselineByWorkload[workloadKey]?.let { return it.meanMs() * conditionFactor }

            // A cold size scales the slowest same-family sibling by megapixel ratio until its first observation.
            val sibling = findSlowestSibling(workloadKey) ?: return 0.0
            val megaPixelRatio =
                workloadKey.sizeBucket.megaPixels.toDouble() / sibling.key.sizeBucket.megaPixels.toDouble()
            return sibling.value.meanMs() * megaPixelRatio * conditionFactor
        }

        private fun findSlowestSibling(workloadKey: WorkloadKey): Map.Entry<WorkloadKey, BaselineMean>? {
            var slowestSibling: Map.Entry<WorkloadKey, BaselineMean>? = null
            for (candidate in baselineByWorkload.entries) {
                if (!candidate.key.isWorkloadFamily(workloadKey)) {
                    continue
                }
                val currentSlowest = slowestSibling
                if (currentSlowest == null || candidate.value.meanMs() > currentSlowest.value.meanMs()) {
                    slowestSibling = candidate
                }
            }
            return slowestSibling
        }

        /** Running mean of duration with the capture's shared condition divided out. */
        private class BaselineMean {
            private var sampleCount: Int = 0
            private var mean: Double = 0.0

            fun meanMs(): Double = mean

            fun observe(baselineSampleMs: Double) {
                if (baselineSampleMs <= 0.0) {
                    return
                }
                sampleCount++
                mean += (baselineSampleMs - mean) / sampleCount
            }
        }
    }

    /**
     * Sequence-specific upper-bound residual distributions with a global cold-sequence fallback: a sequence shot
     * often enough to have its own residual history is bounded by it, everything else by the pooled one.
     */
    private class WorkloadSequenceResidual {
        private val residualsBySequence = mutableMapOf<WorkloadSequenceKey, RecencyWeightedDistribution>()
        private val globalResiduals = RecencyWeightedDistribution()

        fun estimateScore(workloadSequenceKey: WorkloadSequenceKey): Double {
            val sequenceResiduals = residualsBySequence[workloadSequenceKey]
            val residuals = if (sequenceResiduals == null || sequenceResiduals.isEmpty()) {
                globalResiduals
            } else {
                sequenceResiduals
            }
            val quantileFraction = computeQuantileForSampleSize(residuals.effectiveSampleSize())
            return residuals.quantile(quantileFraction)
        }

        fun observe(
            workloadDurations: Map<WorkloadKey, Long>,
            admissionDecisions: Collection<AdmissionDecision>,
        ) {
            val scores = admissionDecisions.mapNotNull { decision ->
                val actualMs = computeClampedActualMs(decision, workloadDurations) ?: return@mapNotNull null
                val predictedMs = decision.executionPrediction.sequencePredictedDurationMs
                if (predictedMs <= 0.0) {
                    return@mapNotNull null
                }
                decision.workloadSequenceKey to maxOf(0.0, ln(actualMs / predictedMs))
            }
            if (scores.isEmpty()) {
                return
            }

            decay()
            scores.forEach { (workloadSequenceKey, score) ->
                globalResiduals.add(score)
                residualsBySequence.getOrPut(workloadSequenceKey) { RecencyWeightedDistribution() }.add(score)
            }
        }

        /**
         * Containment-scoped actual: each workload contributes max(decision-time prediction, actual), so one overrun
         * is retained even when another workload happened to run fast. Incomplete sequences cannot be scored.
         */
        private fun computeClampedActualMs(
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

        private fun decay() {
            globalResiduals.decay()
            val sequenceIterator = residualsBySequence.values.iterator()
            while (sequenceIterator.hasNext()) {
                val sequenceResiduals = sequenceIterator.next()
                sequenceResiduals.decay()
                if (sequenceResiduals.isEmpty()) {
                    sequenceIterator.remove()
                }
            }
        }

        private fun computeQuantileForSampleSize(sampleSize: Double): Double {
            if (sampleSize <= 0.0) {
                return 0.0
            }
            return 1.0 - 1.0 / (sampleSize + 1.0)
        }
    }

    companion object {
        /**
         * The OPTIONAL gate itself, stateless so a decision can be re-derived from a persisted prediction alone.
         * Offline replay calls this exact function to answer "what would today's model admit", the same way it
         * replays through a real [DraftSequenceAdmissionPolicy]; a copy of this rule would let the two drift and
         * report a gate change as no change.
         */
        internal fun admitsOptionalWorkload(
            sequencePredictedMs: Double,
            sequenceUpperBoundMs: Double,
            budgetMs: Long,
        ): Boolean {
            return sequencePredictedMs <= 0.0 || sequenceUpperBoundMs <= budgetMs
        }
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

/**
 * One consistent Predictor snapshot for a draft sequence: its point sum and mandatory RESERVED-tail upper bound.
 */
data class DraftSequenceEstimate(
    val predictedMs: Double,
    val mandatoryReserveUpperBoundMs: Double,
)
