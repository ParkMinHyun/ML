package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.maker.MakerFeature
import kotlin.math.ceil

/**
 * Paces captureAvailable callbacks from [DraftSequenceExecutionPredictor] estimates: [observeDraftStart] refreshes
 * the pacing prediction at every draft start and [decideDelay] converts it into a callback delay per admission.
 * Pure consumer of the predictor - it owns pacing state only, no learning state.
 */
class CaptureAvailablePacer(
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictor.instance,
) {

    private var pacingPrediction: CaptureAvailablePacingPrediction? = null
    private val backlogClock = AdmittedBacklogClock()

    /**
     * Observes a draft start: refreshes the pacing prediction from the draft sequence's current budget
     * (draft-start budget, mandatory RESERVED upper bound, preferred draft-path upper bound) and rebases the
     * admitted-backlog clock. The head is the first workload paced for this draft: OPTIONAL Bokeh/Filter, REQUIRED
     * Watermark/DynamicFunction, or RESERVED Encoding for encoding-only captures. Level-based on purpose: every
     * capture overwrites it, so a fresh burst paces from its own budget instead of a stale trend carried over from
     * the previous burst.
     */
    @Synchronized
    fun observeDraftStart(workloadSequenceKey: WorkloadSequenceKey, budgetMs: Long) {
        val estimate = predictor.estimateDraftPath(workloadSequenceKey)
        pacingPrediction = CaptureAvailablePacingPrediction(
            draftStartBudgetMs = budgetMs,
            mandatoryReserveUpperBoundMs = estimate.mandatoryReserveUpperBoundMs,
            preferredDraftPathPredictedMs = estimate.predictedMs,
            preferredDraftPathUpperBoundMs = estimate.upperBoundMs,
            workloadSequenceKey = workloadSequenceKey.toReplayString(),
        )
        backlogClock.rebaseOnDraftStart(estimate.predictedMs)
    }

    /**
     * Decides the captureAvailable pacing delay for one admission. Null until the first draft sequence is observed,
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
    fun decideDelay(): CaptureAvailablePacingDecision? {
        val prediction = pacingPrediction ?: return null

        val levelDeficitMs = positiveCeilMs(
            prediction.preferredDraftPathUpperBoundMs - prediction.draftStartBudgetMs.coerceAtLeast(0L),
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

    /** Clears pacing state when the draft task queue is fully drained. */
    @Synchronized
    fun clear() {
        pacingPrediction = null
        backlogClock.clear()
    }

    private fun positiveCeilMs(valueMs: Double): Long = ceil(valueMs).toLong().coerceAtLeast(0L)

    /**
     * Admitted-backlog clock: predicted durations of captures whose callback was released but whose draft has not
     * started yet, plus the uptime when the pipeline is predicted to go idle. Not self-synchronized - only touched
     * under the pacer's monitor.
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
         * Push/pop mismatches from skipped callbacks do not accumulate - every draft start recomputes the clock
         * from scratch.
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

    companion object {
        /** Process-wide pacer shared by the profiler (draft starts) and the APM policy (admissions). */
        @JvmStatic
        val instance = CaptureAvailablePacer()
    }
}

/**
 * Latest runtime prediction for captureAvailable pacing, refreshed at every draft start. The policy delays the
 * callback by learned deficits only; [mandatoryReserveUpperBoundMs] only classifies log severity when the reserve
 * itself is at risk.
 */
data class CaptureAvailablePacingPrediction(
    val draftStartBudgetMs: Long,
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
