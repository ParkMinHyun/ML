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
 * draft actually ran rather than around the whole draft task. The callback does carry a capture id, but the deficits
 * are anchored on the newest capture the pipeline has committed, not on whichever capture the callback belongs to -
 * see [computeBacklogDeficitMs].
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
     * Timeout deadline of the newest capture the pipeline has committed to the draft pipeline, and the only thing
     * [computeTimeToDeadlineMs] counts down to. The draft pipeline stamps it as it takes the capture in, so it always
     * names the capture whose work went into the admitted backlog last - which is what [computeBacklogDeficitMs] has
     * to price against, and why a later stamping point (a draft start) would leave it naming the queue's head instead.
     *
     * Deliberately outside [session]: a deadline belongs to a capture, not to a burst, and the newest one stays the
     * right answer across a drain. Absent until the first committed capture whose onShutter stamped one
     * (delayed-shutter IPP captures never do).
     */
    private var latestCaptureDeadlineMs: Long? = null

    /**
     * Returns the larger of the current draft-budget deficit and the admitted-backlog timeout deficit. The same
     * decision is queued as the admission record consumed by the next draft start.
     *
     * The backlog deficit prices the queued work against what is left of this capture's timeout window, which is
     * derived here rather than passed in: the pipeline stamps the deadline at onShutter, so how much remains is simply
     * how far that deadline still is from now.
     *
     * Opens the burst session when none is open - see [session] for why this is the only place that does. The very
     * first callback of a burst then finds no snapshot and answers "no delay", which is what leaves an idle pipeline
     * unpaced.
     */
    @Synchronized
    override fun decideDelay(): CaptureAvailablePacingDecision? {
        val session = openSession()
        val snapshot = session.pacingSnapshot ?: return null
        val nowUptimeMs = SystemClock.uptimeMillis()
        val timeToDeadlineMs = computeTimeToDeadlineMs(nowUptimeMs)
        val backlogMs = session.backlogMsAt(nowUptimeMs)
        val draftSequencePacingDurationMs = snapshot.draftSequencePacingDurationMs
        val levelDeficitMs = computeLevelDeficitMs(
            draftSequenceBudgetMs = snapshot.draftSequenceBudgetMs,
            draftSequencePacingDurationMs = draftSequencePacingDurationMs,
        )
        val backlogDeficitMs = computeBacklogDeficitMs(
            backlogMs = backlogMs,
            timeToDeadlineMs = timeToDeadlineMs,
            draftSequencePacingDurationMs = draftSequencePacingDurationMs,
        )

        val decision = CaptureAvailablePacingDecision(
            delayMs = maxOf(levelDeficitMs, backlogDeficitMs),
            backlogMs = backlogMs,
            levelDeficitMs = levelDeficitMs,
            backlogDeficitMs = backlogDeficitMs,
            queuedDraftCount = session.queuedDraftCount,
            queuedPredictedWorkMs = session.queuedPredictedWorkMs,
            timeToDeadlineMs = timeToDeadlineMs,
            decisionUptimeMs = nowUptimeMs,
            snapshot = snapshot,
        )
        session.queuePacingDecision(decision)
        return decision
    }

    /**
     * Refreshes the pacing snapshot and consumes the oldest admitted callback. A null key means a draft with no
     * predictable workloads (e.g. JPEG passthrough): the admission record is still consumed so the FIFO stays paired
     * with draft starts, but it contributes zero predicted work and leaves the pacing snapshot as-is.
     *
     * No session means no captureAvailable callback has been decided yet, so there is no admission to pair with and
     * nothing to pace: this draft runs unpaced and the next callback opens the session.
     */
    @Synchronized
    fun startDraftSequence(plannedSequenceKey: WorkloadSequenceKey?, budgetMs: Long): CaptureAvailablePacingDecision? {
        val session = session ?: return null
        if (plannedSequenceKey == null) {
            return session.updatePacingSnapshot(null)
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

        return session.updatePacingSnapshot(
            CaptureAvailablePacingSnapshot(
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
    fun setCaptureDeadlineMs(timeoutTimestampMs: Long) {
        latestCaptureDeadlineMs = timeoutTimestampMs
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
     * How much of the newest capture's timeout window is still ahead at [nowUptimeMs] - what the paced draft has to
     * finish inside. The window length is the clamp, not a term: a capture that never stamped a deadline is priced as
     * if its window just opened, and a deadline already in the past leaves nothing, so a missing or late stamp can
     * only cost pacing pressure, never invent a delay out of a bad timestamp.
     */
    private fun computeTimeToDeadlineMs(nowUptimeMs: Long): Long {
        val deadlineUptimeMs = latestCaptureDeadlineMs ?: return MakerFeature.CAPTURE_TIMEOUT_MS
        return (deadlineUptimeMs - nowUptimeMs).coerceIn(0L, MakerFeature.CAPTURE_TIMEOUT_MS)
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
 * How far past its deadline the admitted work is projected to finish: the queue drains over [backlogMs], then one more
 * draft runs for [draftSequencePacingDurationMs], against the [timeToDeadlineMs] left in the window it is priced in.
 * Work minus time left, the same shape [computeLevelDeficitMs] has - the two differ only in whose window they read and
 * whether the queue counts.
 *
 * The window is the newest committed capture's, not that of the capture this callback gates. That capture has not
 * shuttered yet, so its deadline would be `now + delay + CAPTURE_TIMEOUT_MS` - the delay being solved for appears on
 * both sides, and the deficit stops being computable. Anchoring on a deadline already stamped keeps it arithmetic, and
 * the newest committed one is the deadline of the queue's last item, which is what the whole queue has to fit inside.
 *
 * The capture timeout length is deliberately absent here: charging the elapsed part of the window and then subtracting
 * the whole window cancels to exactly this, so the constant only belongs where the window is bounded.
 */
internal fun computeBacklogDeficitMs(
    backlogMs: Long,
    timeToDeadlineMs: Long,
    draftSequencePacingDurationMs: Double,
): Long = ceil(
    backlogMs + draftSequencePacingDurationMs - timeToDeadlineMs,
).toLong().coerceAtLeast(0L)

/** What one draft start hands the next captureAvailable decision to price with. */
data class CaptureAvailablePacingSnapshot(
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
    /** What was left of this capture's timeout window when it was paced; the backlog deficit's only window input. */
    val timeToDeadlineMs: Long,
    val decisionUptimeMs: Long,
    val snapshot: CaptureAvailablePacingSnapshot,
)
