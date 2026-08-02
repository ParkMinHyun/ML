package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import kotlin.math.ceil

/**
 * The one call CaptureAvailableApmPolicy makes into the draft pipeline. A single method on purpose: reading the backlog
 * against "now", pricing the delay, and recording the admission must happen under one lock, or concurrent callbacks
 * double-admit against a stale backlog. Takes nothing - the open session holds every timing, and the deficits anchor on
 * the newest committed capture rather than on the callback's own (see [computeBacklogDeficitMs]).
 */
fun interface CaptureAvailablePacingDecider {
    fun decideDelay(): CaptureAvailablePacingDecision?
}

/**
 * Paces captureAvailable callbacks for one burst: draft starts refresh the pacing snapshot, completed drafts update the
 * session maxima, and one FIFO pairs each admission with the next draft start. Planned sequence keys are re-projected
 * onto the session's demoted shape by [DraftSequenceAdmissionPolicy]; the APM side sees only the decider interface.
 */
class CaptureAvailablePacer(
    private val predictor: DraftSequenceExecutionPredictor,
    private val admissionPolicy: DraftSequenceAdmissionPolicy,
) : CaptureAvailablePacingDecider {

    /**
     * The burst in progress and the pacer's only mutable state, so [clear] resets everything by dropping it. The pacer
     * outlives every session: the APM side holds this one reference and must stay able to answer "no delay".
     *
     * Only [decideDelay] opens one. A burst's first callback always precedes its first draft start, so every other
     * entry point requires a session and drops what arrives without one - nothing arriving before that callback can be
     * paced. It also keeps [CaptureAvailablePacingSession.createdUptimeMs], the burst identity offline grouping reads,
     * stamped by the event that really opens the burst.
     */
    private var captureAvailablePacingSession: CaptureAvailablePacingSession? = null

    /**
     * The larger of the draft-budget deficit and the admitted-backlog timeout deficit, queued as the admission record
     * the next draft start consumes. Opens the burst session when none is open; that first callback then finds no
     * snapshot and answers "no delay", which is what leaves an idle pipeline unpaced.
     */
    @Synchronized
    override fun decideDelay(): CaptureAvailablePacingDecision? {
        val session = openSession()
        val snapshot = session.pacingSnapshot ?: return null
        val nowUptimeMs = SystemClock.uptimeMillis()
        val timeToDeadlineMs = session.timeToDeadlineMsAt(nowUptimeMs)
        val backlogMs = session.backlogMsAt(nowUptimeMs)
        val draftSequenceReservedDurationMs = snapshot.draftSequenceReservedDurationMs
        val levelDeficitMs = computeLevelDeficitMs(
            draftSequenceBudgetMs = snapshot.draftSequenceBudgetMs,
            draftSequenceReservedDurationMs = draftSequenceReservedDurationMs,
        )
        val backlogDeficitMs = computeBacklogDeficitMs(
            backlogMs = backlogMs,
            timeToDeadlineMs = timeToDeadlineMs,
            draftSequenceReservedDurationMs = draftSequenceReservedDurationMs,
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
     * Refreshes the pacing snapshot and consumes the oldest admitted callback. A null key is a draft with no
     * predictable workloads (JPEG passthrough): its admission is still consumed to keep the FIFO paired with draft
     * starts, but it adds no predicted work and leaves the snapshot standing. No session means nothing was paced yet,
     * so this draft runs unpaced and the next callback opens one.
     */
    @Synchronized
    fun startDraftSequence(plannedSequenceKey: WorkloadSequenceKey?, budgetMs: Long): CaptureAvailablePacingDecision? {
        val session = captureAvailablePacingSession ?: return null
        if (plannedSequenceKey == null) {
            return session.updatePacingSnapshot(null)
        }
        val draftSequenceKey = admissionPolicy.resolveDraftSequenceKey(plannedSequenceKey)
        val draftSequencePredictedDurationMs = predictor.estimateWorkloadSequenceDurationMs(draftSequenceKey)
        val demotedWorkloadMs = if (draftSequenceKey == plannedSequenceKey) {
            0.0
        } else {
            (predictor.estimateWorkloadSequenceDurationMs(plannedSequenceKey) - draftSequencePredictedDurationMs)
                .coerceAtLeast(0.0)
        }
        val currentSizeBucket = plannedSequenceKey.headWorkloadKey.sizeBucket
        // Floored by the whole-draft estimate, not the node point sum: the reserve prices the same draft occupancy
        // the backlog clock does, so a session with no observed max yet still reserves one whole draft's duration.
        val draftSequenceReservedDurationMs = maxOf(
            session.getMaxDraftSequenceDurationMs(currentSizeBucket).toDouble() - demotedWorkloadMs,
            predictor.estimateDraftSequenceDurationMs(draftSequenceKey),
        )

        return session.updatePacingSnapshot(
            CaptureAvailablePacingSnapshot(
                draftSequenceBudgetMs = budgetMs,
                draftSequencePredictedDurationMs = draftSequencePredictedDurationMs,
                draftSequenceOverheadDurationMs = predictor.estimateDraftSequenceOverheadDurationMs(),
                draftSequenceReservedDurationMs = draftSequenceReservedDurationMs,
                draftSequenceKey = draftSequenceKey.toReplayString(),
            ),
        )
    }

    /** Pairs with [startDraftSequence]: records the completed draft's real duration against its own size. */
    @Synchronized
    fun endDraftSequence(sizeBucket: SizeBucket, draftSequenceDurationMs: Long) {
        captureAvailablePacingSession?.updateDraftSequenceDuration(sizeBucket, draftSequenceDurationMs)
    }

    /**
     * Takes in the deadline stamped at this capture's onShutter, pushed in when the pipeline accepts the capture: the
     * pacer cannot read it itself, since the callback carries no capture identity and a capture's APM timings are not
     * published until it completes. Dropped when no session is open - with no snapshot to price against there is
     * nothing to pace, so a deadline held over that stretch could not have changed a decision.
     */
    @Synchronized
    fun setCaptureDeadlineMs(timeoutTimestampMs: Long) {
        captureAvailablePacingSession?.updateCaptureDeadlineMs(timeoutTimestampMs)
    }

    /**
     * Burst identity for metrics: a new value per session, null while none is open. Reading it can never open one - a
     * metrics read must not decide where a burst begins.
     */
    @Synchronized
    fun getCurrentSessionId(): Long? = captureAvailablePacingSession?.createdUptimeMs

    /**
     * Ends the burst session when the draft task queue drains, and again at pipeline close. Paired with
     * [DraftSequenceAdmissionPolicy.clear] on the same drain, so the demoted shape a reserve is projected onto and the
     * maxima it is projected from always describe one burst.
     */
    @Synchronized
    fun clear() {
        captureAvailablePacingSession = null
    }

    /** The burst session, opened if this callback is the burst's first. Called by [decideDelay] and nowhere else. */
    private fun openSession(): CaptureAvailablePacingSession =
        captureAvailablePacingSession
            ?: CaptureAvailablePacingSession().also { captureAvailablePacingSession = it }

}

/**
 * The two deficits, stateless so a decision can be re-derived from persisted pacing inputs alone: offline replay calls
 * these exact functions, and a second copy of the arithmetic would drift and report a pacing change as no change. Their
 * plain max is written out at both call sites - a wrapper around `maxOf` is not arithmetic that can drift.
 */
internal fun computeLevelDeficitMs(
    draftSequenceBudgetMs: Long,
    draftSequenceReservedDurationMs: Double,
): Long = ceil(draftSequenceReservedDurationMs - draftSequenceBudgetMs.coerceAtLeast(0L)).toLong().coerceAtLeast(0L)

/**
 * How far past its deadline the admitted work is projected to finish: the queue drains over [backlogMs], then one more
 * draft runs for [draftSequenceReservedDurationMs], against the [timeToDeadlineMs] left. Work minus time left, the same
 * shape [computeLevelDeficitMs] has.
 *
 * The window is the newest committed capture's, not that of the capture this callback gates: that one has not shuttered
 * yet, so its deadline would be `now + delay + CAPTURE_TIMEOUT_MS` - the delay being solved for on both sides, and no
 * deficit to compute. An already-stamped deadline keeps it arithmetic, and the newest committed one bounds the whole
 * queue behind it. The timeout length itself is absent because charging the window's spent part and then subtracting
 * the whole window cancels to exactly this.
 */
internal fun computeBacklogDeficitMs(
    backlogMs: Long,
    timeToDeadlineMs: Long,
    draftSequenceReservedDurationMs: Double,
): Long = ceil(
    backlogMs + draftSequenceReservedDurationMs - timeToDeadlineMs,
).toLong().coerceAtLeast(0L)

/** What one draft start hands the next captureAvailable decision to price with. */
data class CaptureAvailablePacingSnapshot(
    val draftSequenceBudgetMs: Long,
    val draftSequencePredictedDurationMs: Double,
    /** Learned between-node overhead added once per queued draft to the backlog clock (clock = predicted + this). */
    val draftSequenceOverheadDurationMs: Double,
    /**
     * Draft-sequence duration both deficits set aside for the capture being paced: the session's observed max
     * re-projected onto this sequence, floored by the model's whole-draft estimate - all a burst's first capture has,
     * and still the larger of the two on roughly one decision in seven.
     */
    val draftSequenceReservedDurationMs: Double,
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
