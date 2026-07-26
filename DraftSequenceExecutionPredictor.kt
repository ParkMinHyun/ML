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
 * Besides admission, [estimateDraftSequenceMs] snapshots the model for [CaptureAvailablePacer], which owns
 * captureAvailable pacing and only consumes these estimates.
 *
 * Owned by the draft-saving task manager and shared by the profilers it creates across captures, so the model
 * lives exactly as long as the draft pipeline it learned from.
 */
class DraftSequenceExecutionPredictor {

    private val workloadDurationTrend = WorkloadDurationTrend()
    private val workloadSequenceResidual = WorkloadSequenceResidual()

    @Synchronized
    fun decideAdmission(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): AdmissionDecision {
        // Memoized per decision: cold-workload estimates scan sibling baselines, and the suffix walk reuses the result.
        val workloadPredictedMs = workloadDurationTrend.estimateDurationMsByWorkload(workloadSequenceKey.workloadKeys)
        val sequencePredictedMs = sumPredictedMs(workloadSequenceKey, workloadPredictedMs)
        val sequenceUpperBoundMs = estimateUpperBoundMs(workloadSequenceKey, workloadPredictedMs)
        // Only OPTIONAL work is gated, and only by the learned sequence upper bound: budget deficits are observed
        // separately and consumed by [CaptureAvailablePacer] as the captureAvailable pacing delay. Since the other
        // policies always admit, a rejection here is by definition an OPTIONAL one.
        val admit = when (workloadSequenceKey.headWorkloadKey.policy) {
            WorkloadPolicy.OPTIONAL ->
                admitsOptionalWorkload(sequencePredictedMs, sequenceUpperBoundMs, preExecutionMetrics.budgetMs)
            WorkloadPolicy.REQUIRED, WorkloadPolicy.RESERVED -> true
        }
        if (!admit) {
            CLog.w(TAG, "[mhyun2.park] reject admission by upper bound - predictedMs=%f, upperBoundMs=%f, budgetMs=%d", sequencePredictedMs, sequenceUpperBoundMs, preExecutionMetrics.budgetMs)
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

    private fun estimateUpperBoundMs(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        // Walk tail -> head. Monotonic inclusion: each suffix is bounded by at least its own residual-inflated bound
        // and at least its head estimate plus the already-corrected tail, so nested-sequence bounds never invert.
        // (Since exp(C) >= 1, the tail step's max() already returns that bound - the last workload needs no case.)
        val workloadKeys = workloadSequenceKey.workloadKeys
        var correctedUpperBoundMs = 0.0
        var suffixPredictedMs = 0.0
        for (index in workloadKeys.indices.reversed()) {
            val headPredictedMs = workloadPredictedMs.getValue(workloadKeys[index])
            suffixPredictedMs += headPredictedMs
            // This suffix's own bound: its point sum inflated by the residual margin learned for that exact suffix.
            val suffixKey = WorkloadSequenceKey(workloadKeys.subList(index, workloadKeys.size))
            val suffixUpperBoundMs = if (suffixPredictedMs <= 0.0) {
                0.0
            } else {
                suffixPredictedMs * exp(workloadSequenceResidual.estimateScore(suffixKey))
            }
            correctedUpperBoundMs = maxOf(suffixUpperBoundMs, headPredictedMs + correctedUpperBoundMs)
        }
        return correctedUpperBoundMs
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
        val reservedWorkloadKeys = selectReservedWorkloadKeys(workloadSequenceKey)
        if (reservedWorkloadKeys.isEmpty()) {
            return WatchdogTimeoutDecision(timeoutMs = preExecutionMetrics.budgetMs.coerceAtLeast(0L), decision = null)
        }

        // The reserve is RESERVED-only, so this internal decision's admit bit is unused.
        val decision = decideAdmission(WorkloadSequenceKey(reservedWorkloadKeys), preExecutionMetrics)
        val remainingBudgetMs = preExecutionMetrics.budgetMs - decision.executionPrediction.sequencePredictedUpperBoundMs
        val timeoutMs = when {
            remainingBudgetMs.isNaN() || remainingBudgetMs <= 0.0 -> 0L
            remainingBudgetMs >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> remainingBudgetMs.roundToLong().coerceAtLeast(0L)
        }

        return WatchdogTimeoutDecision(timeoutMs, decision)
    }

    /** RESERVED workloads in the observed draft sequence, including a RESERVED head for encoding-only captures. */
    private fun selectReservedWorkloadKeys(workloadSequenceKey: WorkloadSequenceKey): List<WorkloadKey> {
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
     * Point estimate for [workloadSequenceKey] - the one input [CaptureAvailablePacer] paces captureAvailable
     * callbacks with. No upper bound is offered: pacing bounds a capture by observed draft wall time, not by this
     * model, so exposing one only invites re-coupling the two (see the pacer's draftSequencePacingDurationMs).
     */
    @Synchronized
    fun estimateDraftSequenceMs(workloadSequenceKey: WorkloadSequenceKey): Double {
        val workloadPredictedMs = workloadDurationTrend.estimateDurationMsByWorkload(workloadSequenceKey.workloadKeys)
        return sumPredictedMs(workloadSequenceKey, workloadPredictedMs)
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
        private val baselineByWorkload = mutableMapOf<WorkloadKey, EqualWeightedMean>()
        private val conditionScores = RecencyWeightedDistribution()
        private var learnedConditionFactor = 1.0

        /** Reads one condition snapshot and estimates every workload without recomputing its weighted median. */
        fun estimateDurationMsByWorkload(workloadKeys: List<WorkloadKey>): Map<WorkloadKey, Double> {
            val conditionSnapshot = learnedConditionFactor
            return workloadKeys.associateWith { workloadKey -> estimateDurationMs(workloadKey, conditionSnapshot) }
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
                baselineByWorkload.getOrPut(workloadKey) { EqualWeightedMean() }.observe(observedMs / conditionSnapshot)
            }
            learnedConditionFactor = conditionScores.median().takeIf { it > 0.0 } ?: 1.0
        }

        private fun estimateDurationMs(workloadKey: WorkloadKey, conditionFactor: Double): Double {
            baselineByWorkload[workloadKey]?.let { return it.meanMs() * conditionFactor }
            return estimateColdDurationMs(workloadKey, conditionFactor)
        }

        /**
         * A size with no baseline yet: scale the slowest same-family sibling by the megapixel ratio, so a cold size
         * is priced from an observed one rather than from nothing (a MP24 sibling prices cold MP12 at half). Zero
         * when the family has never run at any size - the only case this model cannot price. Runs until this size
         * is observed once, after which [estimateDurationMs] returns its own baseline and never comes back here.
         */
        private fun estimateColdDurationMs(workloadKey: WorkloadKey, conditionFactor: Double): Double {
            var siblingBaselineMs = 0.0
            var siblingMegaPixels = 0
            for ((candidateKey, candidateBaseline) in baselineByWorkload) {
                if (candidateKey.isWorkloadFamily(workloadKey) && candidateBaseline.meanMs() > siblingBaselineMs) {
                    siblingBaselineMs = candidateBaseline.meanMs()
                    siblingMegaPixels = candidateKey.sizeBucket.megaPixels
                }
            }
            if (siblingMegaPixels <= 0) {
                return 0.0
            }
            val megaPixelRatio = workloadKey.sizeBucket.megaPixels.toDouble() / siblingMegaPixels.toDouble()
            return siblingBaselineMs * megaPixelRatio * conditionFactor
        }

        /**
         * Running mean of duration with the capture's shared condition divided out. Every sample weighs 1/n, the
         * deliberate opposite of the recency-weighted condition it is paired with: the baseline converges to a stable
         * per-size anchor instead of chasing a transient, so a size not shot for a while keeps its true structure.
         */
        private class EqualWeightedMean {
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
            return residuals.expectedMaximum()
        }

        fun observe(
            workloadDurations: Map<WorkloadKey, Long>,
            admissionDecisions: Collection<AdmissionDecision>,
        ) {
            val residualScores = admissionDecisions.mapNotNull { decision ->
                val actualMs = sumFlooredActualMs(decision, workloadDurations) ?: return@mapNotNull null
                val predictedMs = decision.executionPrediction.sequencePredictedDurationMs
                if (predictedMs <= 0.0) {
                    return@mapNotNull null
                }
                decision.workloadSequenceKey to maxOf(0.0, ln(actualMs / predictedMs))
            }
            if (residualScores.isEmpty()) {
                return
            }

            decay()
            residualScores.forEach { (workloadSequenceKey, score) ->
                globalResiduals.add(score)
                residualsBySequence.getOrPut(workloadSequenceKey) { RecencyWeightedDistribution() }.add(score)
            }
        }

        /**
         * Containment-scoped actual: each workload contributes max(decision-time prediction, actual), so one overrun
         * is retained even when another workload happened to run fast. Incomplete sequences cannot be scored.
         */
        private fun sumFlooredActualMs(
            decision: AdmissionDecision,
            workloadDurations: Map<WorkloadKey, Long>,
        ): Double? {
            var flooredTotalMs = 0.0
            for (workloadKey in decision.workloadSequenceKey.workloadKeys) {
                val actualMs = workloadDurations[workloadKey] ?: return null
                val predictedMs = decision.workloadPredictedMs[workloadKey] ?: 0.0
                flooredTotalMs += maxOf(predictedMs, actualMs.toDouble())
            }
            return flooredTotalMs.takeIf { it > 0.0 }
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
