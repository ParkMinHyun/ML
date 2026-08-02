package com.samsung.android.camera.core2.ml

import com.samsung.android.camera.core2.util.CLog
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

private const val TAG = "DraftSequenceExecutionPredictor"

/**
 * Sequence-aware duration model with an adaptive upper bound.
 *
 * Point prediction is the sum of per-workload baselines times one shared thermal-condition factor. The safety margin is
 * a multiplicative residual calibrated from positive residual ratios captured before model updates, keyed on the exact
 * decision sequence with a global fallback for cold ones.
 *
 * The upper bound is for admission only - [CaptureAvailablePacer] reads the estimate family and never the bound. Owned
 * by the draft-saving task manager and shared by every profiler it creates, so the model lives exactly as long as the
 * draft pipeline it learned from.
 */
class DraftSequenceExecutionPredictor {

    private val workloadDurationTrend = WorkloadDurationTrend()
    private val workloadSequenceResidual = WorkloadSequenceResidual()
    private val draftDurationOverhead = DraftDurationOverhead()

    @Synchronized
    fun decideAdmission(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): AdmissionDecision {
        // Memoized per decision: cold-workload estimates scan sibling baselines, and the suffix walk reuses the result.
        val workloadPredictedMs = workloadDurationTrend.estimateDurationMsByWorkload(workloadSequenceKey.workloadKeys)
        val workloadSequencePredictedMs = sumPredictedMs(workloadSequenceKey, workloadPredictedMs)
        val workloadSequenceUpperBoundMs = estimateUpperBoundMs(workloadSequenceKey, workloadPredictedMs)
        // Only OPTIONAL work is gated, and only by the learned upper bound - budget deficits are the pacer's business.
        // The other policies always admit, so a rejection here is by definition an OPTIONAL one.
        val admit = when (workloadSequenceKey.headWorkloadKey.policy) {
            WorkloadPolicy.OPTIONAL -> admitsOptionalWorkload(
                workloadSequencePredictedMs,
                workloadSequenceUpperBoundMs,
                preExecutionMetrics.budgetMs,
            )
            WorkloadPolicy.REQUIRED, WorkloadPolicy.RESERVED -> true
        }
        if (!admit) {
            CLog.w(
                TAG,
                "[mhyun2.park] reject admission by upper bound - predictedMs=%f, upperBoundMs=%f, budgetMs=%d",
                workloadSequencePredictedMs,
                workloadSequenceUpperBoundMs,
                preExecutionMetrics.budgetMs,
            )
        }
        return AdmissionDecision(
            executionPrediction = ExecutionPrediction(
                admit = admit,
                sequencePredictedDurationMs = workloadSequencePredictedMs,
                sequencePredictedUpperBoundMs = workloadSequenceUpperBoundMs,
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
        // Walk tail -> head, each suffix bounded by both its own residual-inflated bound and its head estimate plus the
        // corrected tail, so nested-sequence bounds never invert. exp(C) >= 1, so the tail step needs no special case.
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
     * Watchdog budget for the OPTIONAL workload at the head of [workloadSequenceKey]: what is left after reserving the
     * sequence's RESERVED tail (REQUIRED work is measured but not reserved for). With no tail to reserve, the whole
     * budget is granted and [WatchdogTimeoutDecision.decision] is null - nothing to calibrate.
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

    /**
     * Learns every trend this capture can teach: per-workload baselines, the sequence residual margin, and the time
     * outside node processing. A non-positive [draftSequenceDurationMs] was not measured, so the overhead trend keeps
     * its history.
     */
    @Synchronized
    fun learnFromCapture(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
        draftSequenceDurationMs: Long,
    ) {
        val validWorkloadDurations = workloadDurations.filterValues { it > 0L }
        workloadSequenceResidual.observe(validWorkloadDurations, admissionDecisions)
        workloadDurationTrend.observe(validWorkloadDurations)
        draftDurationOverhead.observe(validWorkloadDurations.values.sum(), draftSequenceDurationMs)
    }

    /**
     * Point estimate for [workloadSequenceKey]: node processing alone, which is what the backlog clock prices a queued
     * draft by. No upper bound is offered - pacing reserves by the burst's observed durations, not by this model, and
     * exposing one only invites re-coupling the two.
     */
    @Synchronized
    fun estimateWorkloadSequenceDurationMs(workloadSequenceKey: WorkloadSequenceKey): Double {
        val workloadPredictedMs = workloadDurationTrend.estimateDurationMsByWorkload(workloadSequenceKey.workloadKeys)
        return sumPredictedMs(workloadSequenceKey, workloadPredictedMs)
    }

    /** Learned time outside node processing, added once per draft - by the backlog clock per queued draft, too. */
    @Synchronized
    fun estimateDraftSequenceOverheadDurationMs(): Double = draftDurationOverhead.estimateMs()

    /**
     * Whole-draft occupancy: the two estimates above. Every caller pricing a real draft wants this one - adding the
     * overhead at the call site is one edit away from forgetting to.
     */
    @Synchronized
    fun estimateDraftSequenceDurationMs(workloadSequenceKey: WorkloadSequenceKey): Double =
        estimateWorkloadSequenceDurationMs(workloadSequenceKey) + draftDurationOverhead.estimateMs()

    /**
     * Time outside node processing: inter-node gaps, deinit, scheduling. Recency-weighted mean, not median - the
     * distribution is right-skewed and a median under-prices it.
     */
    private class DraftDurationOverhead {
        private val overheadScores = RecencyWeightedDistribution()
        private var learnedOverheadMs = 0.0

        fun estimateMs(): Double = learnedOverheadMs

        fun observe(nodeProcessingMs: Long, draftSequenceDurationMs: Long) {
            if (draftSequenceDurationMs <= 0L || nodeProcessingMs <= 0L) {
                return
            }

            overheadScores.decay()
            overheadScores.add((draftSequenceDurationMs - nodeProcessingMs).coerceAtLeast(0L).toDouble())
            learnedOverheadMs = overheadScores.mean()
        }
    }

    /**
     * Per-workload duration trend as a multiplicative decomposition:
     * duration(size) ≈ baseline(size) × shared condition.
     *
     * The per-size baseline is condition-stripped and structurally stable; the shared condition is the recency-weighted
     * median of recent duration/baseline ratios, robust to one stalled draft while carrying sustained thermal
     * throttling across workload families and sizes.
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
         * A size with no baseline yet: scale the slowest same-family sibling by the megapixel ratio, so a cold size is
         * priced from an observed one rather than from nothing (a MP24 sibling prices cold MP12 at half). Zero only
         * when the family has never run at any size. Unreachable once this size has been observed once.
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
         * Running mean of duration with the capture's shared condition divided out. Equal 1/n weights, the deliberate
         * opposite of the recency-weighted condition it pairs with: the baseline converges to a stable per-size anchor
         * instead of chasing a transient, so a size not shot for a while keeps its structure.
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
     * Upper-bound residuals per sequence, pooled globally as the cold-sequence fallback: a sequence with its own
     * residual history is bounded by it, everything else by the pool.
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
         * Each workload contributes max(decision-time prediction, actual), so one overrun survives another workload
         * happening to run fast. Null for an incomplete sequence, which cannot be scored.
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
         * The OPTIONAL gate itself, stateless so a decision can be re-derived from a persisted prediction alone:
         * offline replay calls this exact function, and a second copy of the rule would drift and report a gate change
         * as no change.
         */
        internal fun admitsOptionalWorkload(
            workloadSequencePredictedMs: Double,
            workloadSequenceUpperBoundMs: Double,
            budgetMs: Long,
        ): Boolean {
            return workloadSequencePredictedMs <= 0.0 || workloadSequenceUpperBoundMs <= budgetMs
        }
    }
}

/**
 * One admission decision: [executionPrediction] is the persisted, replay-ready record, and the rest are decision-time
 * calibration inputs [DraftSequenceExecutionPredictor.learnFromCapture] consumes at capture end. They stay out of
 * [ExecutionPrediction] because a typed key and a per-workload map do not belong in the metrics store.
 */
data class AdmissionDecision(
    val executionPrediction: ExecutionPrediction,
    /** Sequence this decision was made for; capture-end score samples are keyed by it. */
    val workloadSequenceKey: WorkloadSequenceKey,
    /** Per-workload decision-time prediction, clamping workload underruns so one workload's spike is not diluted. */
    val workloadPredictedMs: Map<WorkloadKey, Double>,
)

data class WatchdogTimeoutDecision(
    val timeoutMs: Long,
    /** Reservation decision backing [timeoutMs]; null when the sequence had no mandatory tail to reserve. */
    val decision: AdmissionDecision?,
)
