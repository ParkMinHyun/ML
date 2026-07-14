package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.watermark.Watermark.WatermarkType
import kotlin.math.ceil

/**
 * Paces captureAvailable callbacks from observed draft timings: [observeDraftMeasured] and [observeSojourn] feed in
 * the whole-draft-task time and the pre-draft latency measured by the APM layer, [observeDraftStart] refreshes the
 * pacing prediction at every draft start, and [decideDelay] converts it into a callback delay per admission. It
 * consumes [DraftSequenceExecutionPredictor] for the point-prediction floor and for re-projecting the observed
 * reserve onto the demoted shape; it owns pacing and session-demotion state only, no learning state.
 *
 * A burst session spans from the first draft after [clear] until the next [clear] (draft task queue drained or
 * closed), so session-scoped state lives here rather than in the per-capture profiler.
 */
class CaptureAvailablePacer(
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictor.instance,
) {

    private var pacingPrediction: CaptureAvailablePacingPrediction? = null
    private val backlogClock = AdmittedBacklogClock()
    private val pendingDecisions = ArrayDeque<CaptureAvailablePacingDecision>()
    private val sessionDemotions = mutableSetOf<SessionDemotion>()
    private var sessionId = 0

    /**
     * Largest observed pre-draft latency this session (from [MultiPicCaptureTimeApmData], via [observeSojourn]): the
     * capture-available elapsed measured from takePicture, i.e. the frame-collection / capture phase that precedes
     * the app-side draft task and that the backlog clock cannot see. Reserved alongside the backlog so the timeout
     * allowance is measured from the request, not from the draft queue. Demotion-independent, so it only resets with
     * the session at [clear].
     */
    private var observedSojournMs = 0L

    /**
     * Largest draft-task execution time observed this session - the base of the per-capture reserve. Fed from
     * [DraftTimeApmData] (via [observeDraftMeasured], through [CaptureAvailableApmPolicy]), which times the whole
     * draft task outside its process lock, so it is the true end-to-end draft cost, not just node execution. A
     * measured wall time, never a synthetic upper bound, so it cannot inflate to a value the device never produced.
     * Session-scoped (reset only at [clear]): rather than resetting on demotion, [observeDraftStart] re-projects it
     * onto the current shape by subtracting the predicted duration of the demoted workloads, so the reserve tracks
     * what this session now runs while keeping the observed thermal state.
     */
    private var sessionObservedMaxDraftMs = 0.0

    /**
     * Observes a draft start: refreshes the pacing prediction from the draft sequence's current budget and rebases
     * the admitted-backlog clock. The per-capture reserve is the session observed max draft time re-projected onto
     * the current shape - minus the predicted duration of the session-demoted workloads (full planned point
     * prediction minus session-preferred point prediction) - floored by the session-preferred point prediction so a
     * cold session still paces. This keeps the reserve on measured drafts while following demotion without discarding
     * the observed history.
     *
     * Returns the pending pacing decision this draft start consumes - the callback delay that gated this capture -
     * for the metrics store. Same FIFO discipline as the backlog clock: the capture starting now is the oldest
     * waiting admission, and mismatches from skipped callbacks do not accumulate past [clear].
     */
    @Synchronized
    fun observeDraftStart(workloadSequenceKey: WorkloadSequenceKey, budgetMs: Long): CaptureAvailablePacingDecision? {
        val sessionPreferredKey = sessionPreferredWorkloadSequenceKey(workloadSequenceKey)
        val estimate = predictor.estimateDraftPath(sessionPreferredKey)
        val demotedWorkloadMs = if (sessionDemotions.isEmpty()) {
            0.0
        } else {
            (predictor.estimateDraftPath(workloadSequenceKey).predictedMs - estimate.predictedMs).coerceAtLeast(0.0)
        }
        val draftReserveMs = maxOf(sessionObservedMaxDraftMs - demotedWorkloadMs, estimate.predictedMs)
        pacingPrediction = CaptureAvailablePacingPrediction(
            draftStartBudgetMs = budgetMs,
            mandatoryReserveUpperBoundMs = estimate.mandatoryReserveUpperBoundMs,
            preferredDraftPathPredictedMs = estimate.predictedMs,
            preferredDraftPathUpperBoundMs = draftReserveMs,
            workloadSequenceKey = sessionPreferredKey.toReplayString(),
        )
        backlogClock.rebaseOnDraftStart(draftReserveMs)
        return pendingDecisions.removeFirstOrNull()
    }

    /**
     * Records one completed draft task's execution wall time ([DraftTimeApmData.getElapsedTime], measured outside
     * the task's process lock) into the session observed max. Fed by [CaptureAvailableApmPolicy] as it reads the
     * draft timings the APM layer collected.
     */
    @Synchronized
    fun observeDraftMeasured(draftWallMs: Long) {
        if (draftWallMs > 0L) {
            sessionObservedMaxDraftMs = maxOf(sessionObservedMaxDraftMs, draftWallMs.toDouble())
        }
    }

    /**
     * Records one capture's observed pre-draft latency (from [MultiPicCaptureTimeApmData], read via
     * [CaptureAvailableApmPolicy]) into the session sojourn max. This capture-phase latency precedes the draft task,
     * so it complements the backlog clock rather than double-counting the draft time the backlog already holds.
     */
    @Synchronized
    fun observeSojourn(sojournMs: Long) {
        if (sojournMs > 0L) {
            observedSojournMs = maxOf(observedSojournMs, sojournMs)
        }
    }

    /**
     * Planned draft path minus session-demoted workloads - what this session will actually run. Mirrors the
     * admission rules in [DraftSequenceExecutionProfiler]: a demoted BOKEH drops out alone; demoted YUV_EFFECTS
     * drops Filter and Overlay Watermark, and Decoding unless Frame Watermark still requires it.
     */
    private fun sessionPreferredWorkloadSequenceKey(workloadSequenceKey: WorkloadSequenceKey): WorkloadSequenceKey {
        if (sessionDemotions.isEmpty()) {
            return workloadSequenceKey
        }
        val hasFrameWatermark = workloadSequenceKey.hasFrameWatermark()
        val preferredKeys = workloadSequenceKey.workloadKeys.filter { workloadKey ->
            when (workloadKey) {
                is WorkloadKey.Bokeh -> SessionDemotion.BOKEH !in sessionDemotions
                is WorkloadKey.Filter -> SessionDemotion.YUV_EFFECTS !in sessionDemotions
                is WorkloadKey.Watermark -> SessionDemotion.YUV_EFFECTS !in sessionDemotions ||
                    workloadKey.watermarkType == WatermarkType.FRAME
                is WorkloadKey.Decoding -> SessionDemotion.YUV_EFFECTS !in sessionDemotions || hasFrameWatermark
                is WorkloadKey.DynamicFunction, is WorkloadKey.Encoding -> true
            }
        }
        return if (preferredKeys.isEmpty()) workloadSequenceKey else WorkloadSequenceKey(preferredKeys)
    }

    /**
     * Decides the captureAvailable pacing delay for one admission. Null until the first draft sequence is observed,
     * so a fresh process' first capture is never paced.
     *
     * The delay is the larger of two deficits against the per-capture draft reserve (the session observed max draft
     * time re-projected onto the current shape, floored by the session-preferred point prediction):
     * - Level deficit: how short the last observed draft start was - the observation-anchored floor.
     * - Backlog deficit: admitted-but-undrained reserved work plus the observed pre-draft sojourn, measured against
     *   the capture timeout. Every admission reserves the observed draft time, so the slack between reserve and a
     *   typical draft grows with queue depth - the margin that absorbs service-time drift (thermal ramps) hitting
     *   callbacks that were already released and can no longer be paced. The timeout itself is the burst allowance,
     *   so an empty pipeline always paces 0. No tuned constant or threshold: only observed draft times, the observed
     *   sojourn, and the existing capture timeout.
     */
    @Synchronized
    fun decideDelay(): CaptureAvailablePacingDecision? {
        val prediction = pacingPrediction ?: return null

        val levelDeficitMs = positiveCeilMs(
            prediction.preferredDraftPathUpperBoundMs - prediction.draftStartBudgetMs.coerceAtLeast(0L),
        )
        val backlogMs = backlogClock.backlogMs()
        val queuedDraftCount = backlogClock.queuedCount()
        val queuedPredictedWorkMs = backlogClock.queuedReservedWorkMs()
        // Delaying the callback lets the backlog drain before the next capture's timeout clock starts, so the
        // admitted capture keeps its full preferred-path budget once latency + backlog - delay fits the timeout.
        val backlogDeficitMs = positiveCeilMs(
            backlogMs + observedSojournMs + prediction.preferredDraftPathUpperBoundMs -
                MakerFeature.CAPTURE_TIMEOUT_MS,
        )
        val delayMs = maxOf(levelDeficitMs, backlogDeficitMs)

        backlogClock.onCallbackAdmitted(prediction.preferredDraftPathUpperBoundMs, delayMs)

        val decision = CaptureAvailablePacingDecision(
            delayMs = delayMs,
            backlogMs = backlogMs,
            levelDeficitMs = levelDeficitMs,
            backlogDeficitMs = backlogDeficitMs,
            queuedDraftCount = queuedDraftCount,
            queuedPredictedWorkMs = queuedPredictedWorkMs,
            decisionUptimeMs = SystemClock.uptimeMillis(),
            prediction = prediction,
        )
        pendingDecisions.addLast(decision)
        return decision
    }

    /** Burst-session ordinal for metrics: increments every time the drained pipeline clears the pacer. */
    @Synchronized
    fun currentSessionId(): Int = sessionId

    /**
     * Whether [demotion]'s workload group is demoted for the current burst session. Pure session-state query - the
     * admission decision that acts on it lives in [DraftSequenceExecutionProfiler]; the pacer only holds the state
     * because it is the one object with burst-session lifecycle (reset at [clear]).
     */
    @Synchronized
    fun isSessionDemoted(demotion: SessionDemotion): Boolean = demotion in sessionDemotions

    /**
     * Records [demotion]'s group as demoted for the rest of the burst session, until the pipeline drains at [clear].
     * The observed-draft reserve is not reset here: [observeDraftStart] re-projects it onto the new shape by
     * subtracting the demoted workloads' predicted duration.
     */
    @Synchronized
    fun demoteForSession(demotion: SessionDemotion) {
        sessionDemotions += demotion
    }

    /** Clears pacing state when the draft task queue is fully drained. */
    @Synchronized
    fun clear() {
        pacingPrediction = null
        backlogClock.clear()
        pendingDecisions.clear()
        sessionDemotions.clear()
        sessionObservedMaxDraftMs = 0.0
        observedSojournMs = 0L
        sessionId++
    }

    private fun positiveCeilMs(valueMs: Double): Long = ceil(valueMs).toLong().coerceAtLeast(0L)

    /**
     * Admitted-backlog clock: reserved upper bounds of captures whose callback was released but whose draft has not
     * started yet, plus the uptime when the pipeline's reserve is spent. Reserving each admission at its upper
     * bound (not its point prediction) makes the ramp-absorbing margin scale with queue depth. Not
     * self-synchronized - only touched under the pacer's monitor.
     */
    private class AdmittedBacklogClock {
        private val waitingReservedMsQueue = ArrayDeque<Double>()
        private var waitingReservedMsTotal = 0.0
        private var busyUntilUptimeMs = 0L

        /** Admitted-but-undrained reserved draft work left, measured from now. */
        fun backlogMs(): Long = (busyUntilUptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)

        /** Callbacks released whose draft has not started yet. */
        fun queuedCount(): Int = waitingReservedMsQueue.size

        /** Reserved work of the waiting callbacks, for metrics. */
        fun queuedReservedWorkMs(): Double = waitingReservedMsTotal

        /** One callback released: its capture joins the waiting backlog and extends the busy horizon by its reserve. */
        fun onCallbackAdmitted(reservedMs: Double, delayMs: Long) {
            waitingReservedMsQueue.addLast(reservedMs)
            waitingReservedMsTotal += reservedMs
            busyUntilUptimeMs = maxOf(SystemClock.uptimeMillis() + delayMs, busyUntilUptimeMs) +
                ceil(reservedMs).toLong()
        }

        /**
         * A draft actually started: rebase on observation. The oldest waiting admission is the capture starting
         * now; the pipeline stays reserved for its fresh upper bound plus the admissions still waiting behind it.
         * Push/pop mismatches from skipped callbacks do not accumulate - every draft start recomputes the clock
         * from scratch.
         */
        fun rebaseOnDraftStart(startingReservedMs: Double) {
            waitingReservedMsQueue.removeFirstOrNull()?.let { admittedReservedMs ->
                waitingReservedMsTotal = (waitingReservedMsTotal - admittedReservedMs).coerceAtLeast(0.0)
            }
            if (waitingReservedMsQueue.isEmpty()) {
                waitingReservedMsTotal = 0.0
            }
            busyUntilUptimeMs = SystemClock.uptimeMillis() +
                ceil(startingReservedMs + waitingReservedMsTotal).toLong()
        }

        fun clear() {
            waitingReservedMsQueue.clear()
            waitingReservedMsTotal = 0.0
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
 * Workload groups whose first in-session rejection sticks until [CaptureAvailablePacer.clear]:
 * [BOKEH] stands alone, [YUV_EFFECTS] is the dependent Decoding-Filter-Overlay Watermark enhancement chain.
 */
enum class SessionDemotion {
    BOKEH,
    YUV_EFFECTS,
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
    val backlogDeficitMs: Long,
    val queuedDraftCount: Int,
    val queuedPredictedWorkMs: Double,
    val decisionUptimeMs: Long,
    val prediction: CaptureAvailablePacingPrediction,
)

/** Snapshot of one runtime pacing decision for the [CaptureMetrics] observability store. */
fun CaptureAvailablePacingDecision.toCaptureAvailablePacingMetrics(): CaptureAvailablePacingMetrics {
    return CaptureAvailablePacingMetrics(
        decisionUptimeMs = decisionUptimeMs,
        appliedDelayMs = delayMs,
        levelDeficitMs = levelDeficitMs,
        backlogDeficitMs = backlogDeficitMs,
        backlogMs = backlogMs,
        queuedDraftCount = queuedDraftCount,
        queuedPredictedWorkMs = queuedPredictedWorkMs,
        draftStartBudgetMs = prediction.draftStartBudgetMs,
        mandatoryReserveUpperBoundMs = prediction.mandatoryReserveUpperBoundMs,
        preferredDraftPathPredictedMs = prediction.preferredDraftPathPredictedMs,
        preferredDraftPathUpperBoundMs = prediction.preferredDraftPathUpperBoundMs,
        workloadSequenceKey = prediction.workloadSequenceKey,
    )
}
