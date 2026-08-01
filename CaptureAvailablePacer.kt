package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.maker.MakerFeature
import kotlin.math.ceil

/**
 * The one call CaptureAvailableApmPolicy exchanges with the draft pipeline: it asks for the delay decision data.
 * Deliberately a single method - reading the admitted backlog against "now", computing the delay, and recording the
 * admission for the next draft start must happen under one lock, or concurrent captureAvailable callbacks would
 * double-admit against a stale backlog.
 *
 * Takes nothing: every timing the decision needs is pipeline-owned state the pacer already holds, measured where the
 * draft actually ran rather than around the whole draft task.
 */
fun interface CaptureAvailablePacingDecider {
    fun decideDelay(): CaptureAvailablePacingDecision?
}

/**
 * Paces captureAvailable callbacks for one burst session. Draft starts refresh the draft-sequence duration estimate,
 * completed drafts update the session maxima, and each admission is paired with the next draft start through one
 * FIFO.
 * Asks the [DraftSequenceAdmissionPolicy] which sequence key a planned one becomes in this session's plan.
 * The APM side consumes this only through [CaptureAvailablePacingDecider]; ownership stays with the draft pipeline.
 */
class CaptureAvailablePacer(
    private val predictor: DraftSequenceExecutionPredictor,
    private val admissionPolicy: DraftSequenceAdmissionPolicy,
) : CaptureAvailablePacingDecider {

    /**
     * Everything scoped to the burst session in progress, absent while the pipeline is idle. [clear] drops it whole
     * rather than resetting field by field, so "clear resets the session and nothing else" holds by construction - a
     * new session field cannot be forgotten there. The pacer itself outlives every session on purpose: the APM side
     * holds this one reference, and a pacer between sessions must stay reachable to answer "no delay".
     *
     * Opened by [openSession], which only [decideDelay] calls: a burst's first captureAvailable callback always
     * precedes its first draft start, so every other entry point can require an existing session instead of one. Keeps
     * [CaptureAvailablePacingSession.createdUptimeMs] - the burst identity offline grouping reads - stamped by the
     * event that really opens the burst, and leaves a metrics read or a late completion unable to open one.
     */
    private var session: CaptureAvailablePacingSession? = null

    /**
     * Timeout deadline of the newest capture the pipeline has taken in - an absolute uptime, not a window length like
     * the [MakerFeature.CAPTURE_TIMEOUT_MS] it is measured against. Deliberately outside [session]: a deadline belongs
     * to a capture, not to a burst, and the newest one stays the right answer across a drain. Absent until the first
     * capture whose onShutter stamped one (delayed-shutter IPP captures never do).
     */
    private var latestCaptureTimeoutMs: Long? = null

    /**
     * Returns the larger of the current draft-budget deficit and the admitted-backlog timeout deficit. The same
     * decision is queued as the admission record consumed by the next draft start.
     *
     * The backlog deficit additionally charges what this capture's timeout window has already spent by now, which is
     * derived here rather than passed in: the deadline was stamped at onShutter, so the spent part is simply how far
     * the decision sits past it.
     *
     * Opens the burst session when none is open - see [session] for why this is the only place that does. The very
     * first callback of a burst then finds no prediction and answers "no delay", which is what leaves an idle pipeline
     * unpaced.
     */
    @Synchronized
    override fun decideDelay(): CaptureAvailablePacingDecision? {
        val session = openSession()
        val prediction = session.pacingPrediction ?: return null
        val nowUptimeMs = SystemClock.uptimeMillis()
        val shutterElapsedMs = computeShutterElapsedMs(nowUptimeMs)
        val backlogMs = session.backlogMsAt(nowUptimeMs)
        val draftSequencePacingDurationMs = prediction.draftSequencePacingDurationMs
        val levelDeficitMs = computeLevelDeficitMs(
            draftSequenceBudgetMs = prediction.draftSequenceBudgetMs,
            draftSequencePacingDurationMs = draftSequencePacingDurationMs,
        )
        val backlogDeficitMs = computeBacklogDeficitMs(
            backlogMs = backlogMs,
            shutterElapsedMs = shutterElapsedMs,
            draftSequencePacingDurationMs = draftSequencePacingDurationMs,
        )

        val decision = CaptureAvailablePacingDecision(
            delayMs = maxOf(levelDeficitMs, backlogDeficitMs),
            backlogMs = backlogMs,
            levelDeficitMs = levelDeficitMs,
            backlogDeficitMs = backlogDeficitMs,
            queuedDraftCount = session.queuedDraftCount,
            queuedPredictedWorkMs = session.queuedPredictedWorkMs,
            shutterElapsedMs = shutterElapsedMs,
            decisionUptimeMs = nowUptimeMs,
            prediction = prediction,
        )
        session.queueDecision(decision)
        return decision
    }

    /**
     * Refreshes the pacing prediction and consumes the oldest admitted callback. A null key means a draft with no
     * predictable workloads (e.g. JPEG passthrough): the admission record is still consumed so the FIFO stays paired
     * with draft starts, but it contributes zero predicted work and leaves the pacing prediction as-is.
     *
     * No session means no captureAvailable callback has been decided yet, so there is no admission to pair with and
     * nothing to pace: this draft runs unpaced and the next callback opens the session.
     */
    @Synchronized
    fun startDraftSequence(plannedSequenceKey: WorkloadSequenceKey?, budgetMs: Long): CaptureAvailablePacingDecision? {
        val session = session ?: return null
        if (plannedSequenceKey == null) {
            return session.updateDraftSequencePrediction(null)
        }
        val draftSequenceKey = admissionPolicy.resolveDraftSequenceKey(plannedSequenceKey)
        val draftSequencePredictedDurationMs = predictor.estimateDraftSequenceMs(draftSequenceKey)
        val demotedWorkloadMs = if (draftSequenceKey == plannedSequenceKey) {
            0.0
        } else {
            (predictor.estimateDraftSequenceMs(plannedSequenceKey) - draftSequencePredictedDurationMs)
                .coerceAtLeast(0.0)
        }
        val currentSizeBucket = plannedSequenceKey.headWorkloadKey.sizeBucket
        // Floored by the whole-draft estimate, not the node point sum: the reserve prices the same draft occupancy
        // the backlog clock does, so a session with no observed max yet cannot reserve less than one draft's wall.
        val draftSequencePacingDurationMs = maxOf(
            session.getMaxDraftSequenceDurationMs(currentSizeBucket).toDouble() - demotedWorkloadMs,
            predictor.estimateDraftSequenceWallMs(draftSequenceKey),
        )

        return session.updateDraftSequencePrediction(
            CaptureAvailablePacingPrediction(
                draftSequenceBudgetMs = budgetMs,
                draftSequencePredictedDurationMs = draftSequencePredictedDurationMs,
                draftSequenceOverheadDurationMs = predictor.estimateDraftOverheadMs(),
                draftSequencePacingDurationMs = draftSequencePacingDurationMs,
                draftSequenceKey = draftSequenceKey.toReplayString(),
            ),
        )
    }

    /**
     * Pairs with [startDraftSequence]: records the completed draft's real wall against its own size. The same wall also
     * teaches the predictor's overhead trend, but that is model learning and the draft pipeline feeds it there directly.
     */
    @Synchronized
    fun endDraftSequence(sizeBucket: SizeBucket, draftWallMs: Long) {
        session?.updateDraftSequenceDuration(sizeBucket, draftWallMs)
    }

    /**
     * Takes in the deadline stamped at this capture's onShutter, called when the pipeline accepts the capture - before
     * its draft is queued, and so before the callback that paces it is decided. The pacer cannot read this itself: the
     * captureAvailable callback carries no capture identity, and the APM timing data for a capture is not published
     * until it completes.
     */
    @Synchronized
    fun setCaptureTimeoutMs(timeoutTimestampMs: Long) {
        latestCaptureTimeoutMs = timeoutTimestampMs
    }

    /**
     * Burst-session identity for metrics: a new value every time the drained pipeline clears the pacer, null while no
     * burst is open. Reading it can never open one - a metrics read must not decide where a burst begins.
     */
    @Synchronized
    fun getCurrentSessionId(): Long? = session?.createdUptimeMs

    /** Ends the burst session when the draft task queue drains; the next captureAvailable callback opens the next. */
    @Synchronized
    fun clear() {
        session = null
    }

    /**
     * The burst session, opened if this is the burst's first callback. Called by [decideDelay] and nowhere else.
     */
    private fun openSession(): CaptureAvailablePacingSession =
        session ?: CaptureAvailablePacingSession().also { session = it }

    /**
     * How much of the newest capture's timeout window is already spent at [nowUptimeMs]. Clamped to the window: a
     * capture that never stamped a deadline charges nothing, and a stale deadline cannot charge more than the whole
     * window, so a missing or late stamp can only cost pacing pressure, never invent a delay out of a bad timestamp.
     */
    private fun computeShutterElapsedMs(nowUptimeMs: Long): Long {
        val deadlineUptimeMs = latestCaptureTimeoutMs ?: return 0L
        return (nowUptimeMs - (deadlineUptimeMs - MakerFeature.CAPTURE_TIMEOUT_MS))
            .coerceIn(0L, MakerFeature.CAPTURE_TIMEOUT_MS)
    }

}

/**
 * The two deficits, stateless so a decision can be re-derived from persisted pacing inputs alone. Offline replay
 * calls these exact functions to answer "what would today's pacing delay be"; a copy of this arithmetic would let the
 * two drift and report a pacing change as no change. The delay they feed is their plain max, written out at both call
 * sites - a wrapper around `maxOf` is not arithmetic that can drift.
 */
internal fun computeLevelDeficitMs(
    draftSequenceBudgetMs: Long,
    draftSequencePacingDurationMs: Double,
): Long = ceil(draftSequencePacingDurationMs - draftSequenceBudgetMs.coerceAtLeast(0L)).toLong().coerceAtLeast(0L)

/**
 * How far past the capture timeout the paced draft is projected to finish. The three terms are the whole window a
 * capture spends, and they are strictly sequential, so they sum: the deadline is stamped at onShutter, so
 * [shutterElapsedMs] is already gone when the callback is decided, then the draft waits out [backlogMs] of admitted
 * work, then runs for [draftSequencePacingDurationMs].
 */
internal fun computeBacklogDeficitMs(
    backlogMs: Long,
    shutterElapsedMs: Long,
    draftSequencePacingDurationMs: Double,
): Long = ceil(
    backlogMs + shutterElapsedMs + draftSequencePacingDurationMs - MakerFeature.CAPTURE_TIMEOUT_MS,
).toLong().coerceAtLeast(0L)

/** Latest runtime prediction for captureAvailable pacing. */
data class CaptureAvailablePacingPrediction(
    val draftSequenceBudgetMs: Long,
    val draftSequencePredictedDurationMs: Double,
    /** Learned between-node overhead added once per queued draft to the backlog clock (clock work = predicted + this). */
    val draftSequenceOverheadDurationMs: Double,
    /**
     * Draft-sequence duration estimate both deficits set aside for the capture being paced. Not a model bound despite
     * sitting beside one: it is the session's observed max draft wall time re-projected onto this draft sequence,
     * floored by the point prediction (all a session's first capture has).
     */
    val draftSequencePacingDurationMs: Double,
    val draftSequenceKey: String,
)

/** One captureAvailable pacing decision and the inputs that produced it. */
data class CaptureAvailablePacingDecision(
    val delayMs: Long,
    val backlogMs: Long,
    val levelDeficitMs: Long,
    val backlogDeficitMs: Long,
    val queuedDraftCount: Int,
    val queuedPredictedWorkMs: Double,
    /** This capture's onShutter-to-decision elapsed - the timeout window already spent when it was paced. */
    val shutterElapsedMs: Long,
    val decisionUptimeMs: Long,
    val prediction: CaptureAvailablePacingPrediction,
)
