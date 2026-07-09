package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.core2.util.CLog
import kotlin.math.ceil
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
 * Besides admission, it feeds captureAvailable pacing: [updateCaptureAvailablePacing] refreshes the pacing prediction
 * and [decideCaptureAvailablePacing] converts it into a callback delay per admission.
 */
class DraftSequenceExecutionPredictor {

    private val workloadKeyDurationTrendMap = mutableMapOf<WorkloadKey, WorkloadDurationTrend>()
    private val workloadSequenceKeyResidualMap = mutableMapOf<WorkloadSequenceKey, MutableList<ResidualSample>>()
    private val globalResidualSamples = mutableListOf<ResidualSample>()
    private var captureAvailablePacingPrediction: CaptureAvailablePacingPrediction? = null
    private val backlogClock = AdmittedBacklogClock()

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
     * separately and consumed by [decideCaptureAvailablePacing] as the captureAvailable pacing delay.
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
     * the sequence's mandatory RESERVED tail. REQUIRED workloads are measured but not reserved for.
     * [WatchdogTimeoutDecision.decision] is null when the sequence has no mandatory tail - the whole budget is
     * granted and there is nothing to calibrate.
     */
    @Synchronized
    fun predictWatchdogTimeout(
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): WatchdogTimeoutDecision {
        val tailKeys = reservedTailWorkloadKeys(workloadSequenceKey)
        if (tailKeys.isEmpty()) {
            return WatchdogTimeoutDecision(timeoutMs = preExecutionMetrics.budgetMs.coerceAtLeast(0L), decision = null)
        }

        // The tail is RESERVED-only, so this internal decision's admit bit is unused.
        val decision = predictAdmission(WorkloadSequenceKey(tailKeys), preExecutionMetrics)
        val remainingBudgetMs = preExecutionMetrics.budgetMs - decision.executionPrediction.sequencePredictedUpperBoundMs
        val timeoutMs = when {
            remainingBudgetMs.isNaN() || remainingBudgetMs <= 0.0 -> 0L
            remainingBudgetMs >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> remainingBudgetMs.roundToLong().coerceAtLeast(0L)
        }

        return WatchdogTimeoutDecision(timeoutMs, decision)
    }

    /** RESERVED workloads after the head - the mandatory tail that both the watchdog and pacing reserve against. */
    private fun reservedTailWorkloadKeys(workloadSequenceKey: WorkloadSequenceKey): List<WorkloadKey> {
        return workloadSequenceKey.workloadKeys
            .drop(1)
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
            workloadSequenceKeyResidualMap.getOrPut(workloadSequenceKey) { mutableListOf() } += sample
            globalResidualSamples += sample
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
        workloadSequenceKeyResidualMap.values.forEach { it.decayAndPrune() }
        globalResidualSamples.decayAndPrune()
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
     * Updates the latest captureAvailable pacing prediction from the draft sequence's first leading-node budget:
     * first-leading budget, mandatory RESERVED-tail upper bound, and preferred draft-path upper bound.
     * The head is any leading workload (OPTIONAL Bokeh/Filter or REQUIRED Watermark/DynamicFunction); a RESERVED head is
     * rejected because the encoding would already be the first node, leaving no pre-encoding budget to pace from.
     * A heavy REQUIRED-only capture (e.g. Watermark + encoding, no Bokeh/Filter) is paced through this path too.
     * Level-based on purpose: every capture overwrites it, so a fresh burst paces from its own budget instead of a
     * stale trend carried over from the previous burst.
     */
    @Synchronized
    fun updateCaptureAvailablePacing(workloadSequenceKey: WorkloadSequenceKey, budgetMs: Long) {
        val workloadPredictedMs = workloadSequenceKey.workloadKeys.associateWith(::estimateWorkloadMs)
        val preferredDraftPathUpperBoundMs = estimateUpperBoundMs(workloadSequenceKey, workloadPredictedMs)
        val tailWorkloadKeys = reservedTailWorkloadKeys(workloadSequenceKey)
        val mandatoryReserveUpperBoundMs = if (tailWorkloadKeys.isEmpty()) {
            0.0
        } else {
            estimateUpperBoundMs(WorkloadSequenceKey(tailWorkloadKeys), tailWorkloadKeys.associateWith(::estimateWorkloadMs))
        }
        val preferredDraftPathPredictedMs = sumPredictedMs(workloadSequenceKey, workloadPredictedMs)
        captureAvailablePacingPrediction = CaptureAvailablePacingPrediction(
            firstLeadingBudgetMs = budgetMs,
            mandatoryReserveUpperBoundMs = mandatoryReserveUpperBoundMs,
            preferredDraftPathPredictedMs = preferredDraftPathPredictedMs,
            preferredDraftPathUpperBoundMs = preferredDraftPathUpperBoundMs,
            workloadSequenceKey = workloadSequenceKey.toReplayString(),
        )

        backlogClock.rebaseOnDraftStart(preferredDraftPathPredictedMs)
    }

    /**
     * Decides the captureAvailable pacing delay for one admission. Null until the first leading sequence is observed,
     * so a fresh process' first capture is never paced.
     *
     * The delay is the larger of two deficits against the preferred draft-path upper bound:
     * - Level deficit: how short the last observed draft start was - the observation-anchored floor.
     * - Backlog deficit: admitted-but-undrained draft work measured against the capture timeout. The timeout itself
     *   is the burst allowance, so an empty pipeline always paces 0 and delays start only once the allowance is
     *   genuinely spent; from there each admission also advances the clock by its own predicted duration, which is
     *   what spaces callbacks arriving mid-draft out to the service rate instead of re-applying one stale level
     *   correction. No tuned constant or threshold: only learned predictions and the existing capture timeout.
     */
    @Synchronized
    fun decideCaptureAvailablePacing(): CaptureAvailablePacingDecision? {
        val prediction = captureAvailablePacingPrediction ?: return null

        val levelDeficitMs = positiveCeilMs(
            prediction.preferredDraftPathUpperBoundMs - prediction.firstLeadingBudgetMs.coerceAtLeast(0L),
        )
        val backlogMs = backlogClock.backlogMs()
        // Delaying the callback lets the backlog drain before the next capture's timeout clock starts, so the
        // admitted capture keeps its full preferred-path budget once backlog - delay fits the capture timeout.
        val backlogDeficitMs = positiveCeilMs(
            backlogMs + prediction.preferredDraftPathUpperBoundMs - MakerFeature.CAPTURE_TIMEOUT_MS,
        )
        val delayMs = maxOf(levelDeficitMs, backlogDeficitMs)

        backlogClock.onCallbackAdmitted(prediction.preferredDraftPathPredictedMs, delayMs)

        return CaptureAvailablePacingDecision(
            delayMs = delayMs,
            backlogMs = backlogMs,
            levelDeficitMs = levelDeficitMs,
            prediction = prediction,
        )
    }

    private fun positiveCeilMs(valueMs: Double): Long = ceil(valueMs).toLong().coerceAtLeast(0L)

    /** Clears captureAvailable pacing state when the draft task queue is fully drained. */
    @Synchronized
    fun clearCaptureAvailablePacing() {
        captureAvailablePacingPrediction = null
        backlogClock.clear()
    }

    /**
     * Admitted-backlog clock for captureAvailable pacing: predicted durations of captures whose callback was
     * released but whose draft has not started yet, plus the uptime when the pipeline is predicted to go idle.
     * Not self-synchronized - only touched under the predictor's monitor.
     */
    private class AdmittedBacklogClock {
        private val waitingPredictedMsQueue = ArrayDeque<Double>()
        private var busyUntilUptimeMs = 0L

        /** Admitted-but-undrained draft work left, measured from now. */
        fun backlogMs(): Long = (busyUntilUptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)

        /** One callback released: its capture joins the waiting backlog and extends the busy horizon by its prediction. */
        fun onCallbackAdmitted(predictedMs: Double, delayMs: Long) {
            waitingPredictedMsQueue.addLast(predictedMs)
            busyUntilUptimeMs = maxOf(SystemClock.uptimeMillis() + delayMs, busyUntilUptimeMs) +
                ceil(predictedMs).toLong()
        }

        /**
         * A draft actually started: rebase on observation. The oldest waiting admission is the capture starting
         * now; the pipeline stays busy for its fresh prediction plus the admissions still waiting behind it.
         * Push/pop mismatches (skipped callbacks, RESERVED-head captures) do not accumulate - every draft start
         * recomputes the clock from scratch.
         */
        fun rebaseOnDraftStart(startingPredictedMs: Double) {
            waitingPredictedMsQueue.removeFirstOrNull()
            busyUntilUptimeMs = SystemClock.uptimeMillis() +
                ceil(startingPredictedMs + waitingPredictedMsQueue.sum()).toLong()
        }

        fun clear() {
            waitingPredictedMsQueue.clear()
            busyUntilUptimeMs = 0L
        }
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

/**
 * Latest runtime prediction for captureAvailable pacing, refreshed at every draft start. The policy delays the
 * callback by learned deficits only; [mandatoryReserveUpperBoundMs] only classifies log severity when the reserve
 * itself is at risk.
 */
data class CaptureAvailablePacingPrediction(
    val firstLeadingBudgetMs: Long,
    val mandatoryReserveUpperBoundMs: Double,
    val preferredDraftPathPredictedMs: Double,
    val preferredDraftPathUpperBoundMs: Double,
    val workloadSequenceKey: String,
)

/** One captureAvailable pacing decision: the callback delay plus the inputs that produced it, for policy logging. */
data class CaptureAvailablePacingDecision(
    val delayMs: Long,
    val backlogMs: Long,
    val levelDeficitMs: Long,
    val prediction: CaptureAvailablePacingPrediction,
)
