package com.samsung.android.camera.core2.ml

import com.samsung.android.camera.core2.util.CLog
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

private const val TAG = "DraftSequenceExecutionPredictor"

/**
 * Sequence-aware, phase-aware, adaptive upper bound.
 *
 * Point prediction is the sum of per-workload EWMA values. The safety margin is a multiplicative residual calibrated
 * from positive residual ratios captured before workload EWMA updates; exact decision-sequence residuals win, with
 * global residuals as the cold-sequence fallback.
 *
 * Besides admission, [estimateDraftSequence] snapshots the model for [CaptureAvailablePacer], which owns
 * captureAvailable pacing and only consumes these estimates.
 *
 * Owned by the draft-saving task manager and shared by the profilers it creates across captures, so the model
 * lives exactly as long as the draft pipeline it learned from.
 */
class DraftSequenceExecutionPredictor {

    // Point model as a multiplicative decomposition: duration(size) ≈ baseline(size) × condition.
    // The per-size baseline is the duration with the transient condition divided out (structural, stable); the one
    // shared condition carries the transient throttling across sizes, so a size not shot under it is corrected by it.
    private val workloadKeyBaselineMap = mutableMapOf<WorkloadKey, BaselineMean>()
    // Condition = weighted MEDIAN of recent duration/baseline ratios: decay ([SCORE_WEIGHT_DECAY]) weights recent
    // shots more (a burst's later shots outweigh its first), while the median keeps one stalled draft (GC/transient)
    // from distorting it - the EWMA it replaces chased such a spike. Reuses the residual model's decay + weighted
    // quantile, so no new constant.
    private val conditionSamples = mutableListOf<ResidualSample>()

    private val workloadSequenceKeyResidualMap = mutableMapOf<WorkloadSequenceKey, MutableList<ResidualSample>>()
    private val globalResidualSamples = mutableListOf<ResidualSample>()

    /**
     * Whole-draft time the per-workload point sum does not capture: inter-node gaps, deinit, and scheduling between
     * node executions. The pacer prices a queued draft by its real pipeline occupancy (point sum + this) instead of
     * node processing alone. Tracked as the decay-weighted mean of recent samples: recency-weighted like the old EWMA
     * (a later shot outweighs the first) but reusing the residual model's decay, so no standalone alpha. The mean (not
     * the condition's median) because a right-skewed overhead's median under-prices, and under-pricing this clock term
     * risks a timeout; a single unpredictable spike is absorbed by the pacer's ceiling, not chased here.
     * Sequence-independent by construction, so it is a single sample list rather than a per-sequence one.
     */
    private val overheadSamples = mutableListOf<ResidualSample>()

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
        val admit = admitsOptionalWorkload(sequencePredictedMs, sequenceUpperBoundMs, budgetMs)
        if (!admit) {
            CLog.w(TAG, "[mhyun2.park] reject admission by upper bound - predictedMs=%f, upperBoundMs=%f, budgetMs=%d", sequencePredictedMs, sequenceUpperBoundMs, budgetMs)
        }

        return admit
    }

    /** Shared thermal condition: weighted median of the recent duration/baseline ratios (1.0 until any is seen). */
    private fun conditionFactor(): Double =
        computeWeightedQuantileScore(conditionSamples, MEDIAN_QUANTILE).takeIf { it > 0.0 } ?: 1.0

    private fun estimateWorkloadMs(workloadKey: WorkloadKey): Double {
        val condition = conditionFactor()
        workloadKeyBaselineMap[workloadKey]?.let { return it.meanMs() * condition }

        // Cold size (never observed): scale a same-family sibling's baseline by the linear megapixel ratio, then apply
        // the shared condition. A plain ratio is the cold-start prior (a 24MP sibling prices cold 12MP at half), used
        // only until this size is observed once, after which the branch above returns its exact baseline.
        val sibling = workloadKeyBaselineMap.entries
            .filter { (siblingKey, _) -> siblingKey.isWorkloadFamily(workloadKey) }
            .maxByOrNull { (_, baseline) -> baseline.meanMs() }
            ?: return 0.0
        val megaPixelRatio =
            workloadKey.sizeBucket.megaPixels.toDouble() / sibling.key.sizeBucket.megaPixels.toDouble()
        return sibling.value.meanMs() * megaPixelRatio * condition
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

        return predictedMs * exp(estimateResidualScore(workloadSequenceKey))
    }

    private fun sumPredictedMs(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        return workloadSequenceKey.workloadKeys.sumOf { workloadPredictedMs.getValue(it) }
    }

    private fun estimateResidualScore(workloadSequenceKey: WorkloadSequenceKey): Double {
        val sequenceSamples = workloadSequenceKeyResidualMap[workloadSequenceKey]
        val samples = if (sequenceSamples.isNullOrEmpty()) globalResidualSamples else sequenceSamples
        val effectiveSampleSize = computeEffectiveSampleSize(samples.filter { it.weight > 0.0 }.map { it.weight })
        return computeWeightedQuantileScore(samples, computeQuantileForSampleSize(effectiveSampleSize))
    }

    /**
     * Weighted score at [quantileFraction] of the decayed samples. The residual upper bound passes a high adaptive
     * quantile (safety tail); the condition passes the median ([MEDIAN_QUANTILE], a robust central estimate).
     */
    private fun computeWeightedQuantileScore(samples: List<ResidualSample>, quantileFraction: Double): Double {
        val weightedSamples = samples.filter { it.weight > 0.0 }
        if (weightedSamples.isEmpty()) {
            return 0.0
        }

        val targetWeight = weightedSamples.sumOf { it.weight } * quantileFraction
        var cumulativeWeight = 0.0
        for (sample in weightedSamples.sortedBy { it.score }) {
            cumulativeWeight += sample.weight
            if (cumulativeWeight >= targetWeight) {
                return sample.score
            }
        }

        return weightedSamples.maxOf { it.score }
    }

    private fun computeQuantileForSampleSize(sampleSize: Double): Double {
        if (sampleSize <= 0.0) {
            return 0.0
        }
        return 1.0 - 1.0 / (sampleSize + 1.0)
    }

    private fun computeEffectiveSampleSize(weights: List<Double>): Double {
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
        draftWallMs: Long,
    ) {
        val validWorkloadDurations = workloadDurations.filterValues { it > 0L }

        addResidualSamples(validWorkloadDurations, admissionDecisions)

        // Multiplicative decomposition update. The shared condition ages once per capture (like the residual model),
        // then takes this capture's duration/baseline ratios; each size's condition-stripped baseline updates from the
        // pre-update condition snapshot. Reading PRE-update snapshots keeps the two from feeding back within one
        // observation: the condition (weighted median) carries a thermal ramp across sizes, the baseline (running
        // mean) stays a stable per-size anchor, and only the transient movement is shared - no concurrent-sibling
        // double-count.
        val conditionSnapshot = conditionFactor()
        conditionSamples.decayAndPrune()
        validWorkloadDurations.forEach { (workloadKey, durationMs) ->
            val observedMs = durationMs.toDouble()
            val baselineSnapshot = workloadKeyBaselineMap[workloadKey]?.meanMs()
            if (baselineSnapshot != null && baselineSnapshot > 0.0) {
                conditionSamples += ResidualSample(score = observedMs / baselineSnapshot)
            }
            workloadKeyBaselineMap.getOrPut(workloadKey) { BaselineMean() }.observe(observedMs / conditionSnapshot)
        }

        // Everything in the draft wall not accounted for by node processing is the between-node overhead the point
        // sum misses. Learn it only from fully measured drafts (both terms positive), never below zero. Ages once per
        // capture like the residual/condition models, then takes this capture's overhead.
        val nodeProcessingMs = validWorkloadDurations.values.sum()
        if (draftWallMs > 0L && nodeProcessingMs > 0L) {
            overheadSamples.decayAndPrune()
            overheadSamples += ResidualSample(score = (draftWallMs - nodeProcessingMs).coerceAtLeast(0L).toDouble())
        }
    }

    private fun addResidualSamples(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
    ) {
        val samples = admissionDecisions.mapNotNull { decision ->
            val actualMs = computeClampedActualMs(decision, workloadDurations) ?: return@mapNotNull null
            val score = computeLogResidualScore(
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

    private fun computeLogResidualScore(predictedMs: Double, actualMs: Double): Double? {
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
            this[index] = this[index].decay(SCORE_WEIGHT_DECAY)
        }
        // ponytail: drop the negligible tail so this long-lived model's sample lists stay bounded.
        // At decay=0.9 a sample older than ~130 captures weighs < 1e-6 and no longer moves any quantile.
        removeAll { it.weight < SCORE_WEIGHT_PRUNE_THRESHOLD }
    }

    /**
     * Point estimate for [workloadSequenceKey] plus its mandatory RESERVED-work upper bound, read in one consistent
     * model snapshot - the inputs [CaptureAvailablePacer] paces captureAvailable callbacks with. The sequence's own
     * upper bound is deliberately not offered: pacing bounds a capture by observed draft wall time, not by this
     * model, so exposing one only invites re-coupling the two (see the pacer's sessionPlannedCeilingMs).
     */
    @Synchronized
    fun estimateDraftSequence(workloadSequenceKey: WorkloadSequenceKey): DraftSequenceEstimate {
        val workloadPredictedMs = workloadSequenceKey.workloadKeys.associateWith(::estimateWorkloadMs)
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
            draftOverheadMs = overheadMean(),
        )
    }

    /**
     * Between-node draft overhead: the decay-weighted mean of recent (draftWall - nodeSum) samples, 0.0 until any is
     * seen. Recency-weighted via the shared [SCORE_WEIGHT_DECAY] (a later burst shot outweighs the first) without a
     * standalone alpha; the mean rather than a quantile so a right-skewed overhead is not systematically under-priced.
     */
    private fun overheadMean(): Double {
        val weighted = overheadSamples.filter { it.weight > 0.0 }
        if (weighted.isEmpty()) {
            return 0.0
        }
        return weighted.sumOf { it.score * it.weight } / weighted.sumOf { it.weight }
    }

    /**
     * One size's structural duration baseline with the shared thermal condition divided out: the running mean of
     * (observed / condition-at-observation). Every sample weighs 1/n, so it converges to a stable average and never
     * chases the condition within a sample - a stale size keeps its true baseline while the shared condition
     * ([conditionSamples], a decayed weighted median) carries the transient throttling.
     */
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

    private data class ResidualSample(
        val score: Double,
        val weight: Double = 1.0,
    ) {
        fun decay(factor: Double): ResidualSample = copy(weight = weight * factor)
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

        // One recency control for every learned trend: decay sets the residual safety quantile, the condition's
        // recency, and the overhead mean's recency (effective ESS=(1+d)/(1-d) -> ~19 recent captures at 0.9),
        // self-calibrating to each device. MEDIAN_QUANTILE is the median (a robust centre), not a tunable knob.
        private const val SCORE_WEIGHT_DECAY = 0.90
        private const val SCORE_WEIGHT_PRUNE_THRESHOLD = 1e-6
        private const val MEDIAN_QUANTILE = 0.50
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
 * One consistent model snapshot for a draft sequence: point sum, the mandatory RESERVED tail's upper bound, and the
 * learned between-node overhead the point sum omits (added to the point sum when pricing pipeline occupancy).
 */
data class DraftSequenceEstimate(
    val predictedMs: Double,
    val mandatoryReserveUpperBoundMs: Double,
    val draftOverheadMs: Double,
)
