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
 *
 * Besides admission, it feeds captureAvailable pacing: [observeCaptureAvailableSlack] caches the first leading-node
 * budget's headroom above the RESERVE tail, exposed via [captureAvailableSlackMs].
 */
class DraftSequenceExecutionPredictor {

    private val durationTrendByWorkload = mutableMapOf<WorkloadKey, WorkloadDurationTrend>()
    private val residualsBySequence = mutableMapOf<WorkloadSequenceKey, MutableList<ResidualSample>>()
    private val residualsByShape = mutableMapOf<WorkloadSequenceShape, MutableList<ResidualSample>>()
    private var observedSlackMs: Long? = null

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
            WorkloadPolicy.ADMIT -> fitsUpperBoundBudget(
                sequencePredictedMs,
                sequenceUpperBoundMs,
                preExecutionMetrics.budgetMs,
            )
            WorkloadPolicy.OBSERVE, WorkloadPolicy.RESERVE -> true
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
     * ADMIT gate. Admission is bounded by the learned sequence upper bound only; budget slack is observed separately
     * and consumed by [captureAvailableSlackMs] as captureAvailable pacing.
     */
    private fun fitsUpperBoundBudget(
        sequencePredictedMs: Double,
        sequenceUpperBoundMs: Double,
        budgetMs: Long,
    ): Boolean {
        // Cold start: nothing learned for this sequence yet, so admit and let the models start learning.
        if (sequencePredictedMs <= 0.0) {
            return true
        }

        val admit = sequenceUpperBoundMs <= budgetMs
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
        return durationTrendByWorkload.entries
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
        for (index in workloadKeys.indices.reversed()) {
            val suffixRawUpperBoundMs = estimateRawUpperBoundMs(
                WorkloadSequenceKey(workloadKeys.drop(index)),
                workloadPredictedMs,
            )
            corrected = maxOf(suffixRawUpperBoundMs, workloadPredictedMs.getValue(workloadKeys[index]) + corrected)
        }
        return corrected
    }

    private fun estimateRawUpperBoundMs(
        workloadSequenceKey: WorkloadSequenceKey,
        workloadPredictedMs: Map<WorkloadKey, Double>,
    ): Double {
        val predictedMs = sumPredictedMs(workloadSequenceKey, workloadPredictedMs)
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
        return selectResidualScore(
            sequenceSamples = residualsBySequence[workloadSequenceKey].orEmpty(),
            compatibleSamples = residualsByShape[workloadSequenceKey.shape].orEmpty(),
        )
    }

    private fun selectResidualScore(
        sequenceSamples: List<ResidualSample>,
        compatibleSamples: List<ResidualSample>,
    ): Double {
        // Exact sequence residuals win; otherwise borrow only the same workload-type sequence shape.
        return if (sequenceSamples.isNotEmpty()) {
            weightedQuantileScore(sequenceSamples)
        } else {
            weightedQuantileScore(compatibleSamples)
        }
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
     * Watchdog budget for the ADMIT workload at the head of [workloadSequenceKey]: the time left after reserving
     * the sequence's mandatory RESERVE tail. OBSERVE workloads are measured but not reserved for.
     * [WatchdogTimeoutDecision.decision] is null when the sequence has no mandatory tail - the whole budget is
     * granted and there is nothing to calibrate.
     */
    @Synchronized
    fun predictWatchdogTimeout(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): WatchdogTimeoutDecision {
        val tailKeys = reserveTailKeys(workloadSequenceKey)
        if (tailKeys.isEmpty()) {
            return WatchdogTimeoutDecision(timeoutMs = preExecutionMetrics.budgetMs.coerceAtLeast(0L), decision = null)
        }

        // The tail is RESERVE-only, so this internal decision's admit bit is unused.
        val decision = predictAdmission(WorkloadSequenceKey(tailKeys), preExecutionMetrics)
        val remainingBudgetMs = preExecutionMetrics.budgetMs - decision.executionPrediction.sequencePredictedUpperBoundMs
        val timeoutMs = when {
            remainingBudgetMs.isNaN() || remainingBudgetMs <= 0.0 -> 0L
            remainingBudgetMs >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> remainingBudgetMs.roundToLong().coerceAtLeast(0L)
        }

        return WatchdogTimeoutDecision(timeoutMs, decision)
    }

    /** RESERVE workloads after the head - the mandatory tail that both the watchdog and pacing reserve against. */
    private fun reserveTailKeys(workloadSequenceKey: WorkloadSequenceKey): List<WorkloadKey> {
        return workloadSequenceKey.workloadKeys
            .drop(1)
            .filter { plannedWorkloadKey -> plannedWorkloadKey.policy == WorkloadPolicy.RESERVE }
    }

    @Synchronized
    fun learnFromCapture(
        workloadDurations: Map<WorkloadKey, Long>,
        admissionDecisions: Collection<AdmissionDecision>,
    ) {
        val validWorkloadDurations = workloadDurations.filterValues { it > 0L }

        addResidualSamples(validWorkloadDurations, admissionDecisions)

        validWorkloadDurations.forEach { (workloadKey, durationMs) ->
            durationTrendByWorkload.getOrPut(workloadKey) { WorkloadDurationTrend() }
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
            residualsBySequence.getOrPut(workloadSequenceKey) { mutableListOf() } += sample
            residualsByShape.getOrPut(workloadSequenceKey.shape) { mutableListOf() } += sample
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
        residualsBySequence.values.forEach { it.decayAndPrune() }
        residualsByShape.values.forEach { it.decayAndPrune() }
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
     * Caches captureAvailable pacing slack from the draft sequence's first leading-node budget: the headroom left
     * above the learned RESERVE tail (encoding) - the same reserve [predictWatchdogTimeout] grants against.
     * The head is any leading workload (ADMIT Bokeh/Filter or OBSERVE Watermark/DynamicFunction); a RESERVE head is
     * rejected because the encoding would already be the first node, leaving no pre-encoding budget to pace from.
     * A heavy OBSERVE-only capture (e.g. Watermark + encoding, no Bokeh/Filter) is paced through this path too.
     * Level-based on purpose: every capture overwrites it, so a fresh burst paces from its own budget instead of a
     * stale trend carried over from the previous burst.
     */
    @Synchronized
    fun observeCaptureAvailableSlack(workloadSequenceKey: WorkloadSequenceKey, budgetMs: Long) {
        if (workloadSequenceKey.headWorkloadKey.policy == WorkloadPolicy.RESERVE) {
            return
        }

        val tailKeys = reserveTailKeys(workloadSequenceKey)
        val reserveUpperBoundMs = if (tailKeys.isEmpty()) {
            0.0
        } else {
            estimateUpperBoundMs(WorkloadSequenceKey(tailKeys), tailKeys.associateWith(::estimateWorkloadMs))
        }
        observedSlackMs = (budgetMs - reserveUpperBoundMs).roundToLong()
    }

    /**
     * CaptureAvailable pacing slack: budget headroom above the RESERVE tail of the latest observed sequence.
     * Consumers spend it 1:1 as an advance on legacy service-rate pacing - 1ms of slack is 1ms of advance, no tuning
     * constant - so the budget parks at the reserve instead of draining until the admission gate rejects. Negative
     * means the budget is already below the reserve (full pacing; admission is rejecting in the same state).
     * Null until the first ADMIT sequence is observed - callers keep legacy pacing until then.
     */
    @Synchronized
    fun captureAvailableSlackMs(): Long? = observedSlackMs

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
