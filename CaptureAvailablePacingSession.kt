package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.maker.MakerFeature
import kotlin.math.ceil

/**
 * State of one burst and the arithmetic that maintains it: everything [CaptureAvailablePacer.clear] discards, and
 * nothing it must keep. Owning the admitted-work FIFO and the backlog clock derived from it keeps the two in agreement.
 *
 * Not synchronized: the pacer reaches every method under its own lock.
 */
internal class CaptureAvailablePacingSession {

    /**
     * When this session began, doubling as its identity: offline grouping reads "changed = new session", and a creation
     * uptime can never repeat one an earlier session - or an earlier pacer instance - saw, which the plain ordinal it
     * replaced did on every camera close/open.
     */
    val createdUptimeMs: Long = SystemClock.uptimeMillis()

    /**
     * Latest draft-start snapshot the next [CaptureAvailablePacer.decideDelay] prices against; null until this burst's
     * first draft start, which is what makes an idle pipeline answer "no delay". Only [updatePacingSnapshot] writes it,
     * so the snapshot and the clock it rebased cannot disagree.
     */
    var pacingSnapshot: CaptureAvailablePacingSnapshot? = null
        private set

    private val pendingDecisions = ArrayDeque<CaptureAvailablePacingDecision>()
    private val maxDraftSequenceDurationMsBySize = mutableMapOf<SizeBucket, Long>()

    /**
     * When the admitted queue drains, so an estimate of elapsed work rather than a safety bound: it advances by each
     * capture's point prediction plus the learned between-node overhead. The point sum alone omits the inter-node and
     * deinit time and that shortfall compounds with queue depth into a timeout, while summing k whole-draft reserves
     * would price a queue at k times the burst's worst case, which no run reaches. The margin comes instead from
     * the one reserve [CaptureAvailablePacer.decideDelay] adds for the capture being paced, and underruns cannot
     * accumulate because every draft start rebases this clock onto the real one.
     *
     * An absolute uptime, unlike the durations around it: [backlogMsAt] turns it into "how much is left".
     */
    private var backlogEndTimeMs = 0L

    /**
     * Timeout deadline of the newest capture the pipeline has committed - what [timeToDeadlineMsAt] counts down to. It
     * is stamped as the capture is taken in, so it always names the capture whose work entered the backlog last, which
     * is what [computeBacklogDeficitMs] must price against; a later stamping point (a draft start) would leave it
     * naming the queue's head instead. Absent until this burst's first committed capture stamps one (delayed-shutter
     * IPP captures never do), and scoped to the session because a deadline outliving its burst survives only as a past
     * instant - which would price the next burst's backlog against no window at all.
     */
    private var latestCaptureDeadlineMs: Long? = null

    val queuedDraftCount: Int get() = pendingDecisions.size

    /** Point work of every queued draft - the part of pending occupancy the metrics report separately. */
    val queuedPredictedWorkMs: Double
        get() = pendingDecisions.sumOf { it.snapshot.workloadSequencePredictedDurationMs }

    /**
     * Adopts the starting draft's snapshot and rebases the clock onto it. The decision FIFO doubles as the admitted
     * work queue rather than a second one: pop the admission this draft start consumes, then restart the clock from the
     * draft starting now plus everything still queued behind it, one between-node overhead each. A null [snapshot]
     * (JPEG passthrough) adds no starting work and leaves the previous snapshot standing.
     */
    fun updatePacingSnapshot(
        snapshot: CaptureAvailablePacingSnapshot?,
    ): CaptureAvailablePacingDecision? {
        if (snapshot != null) {
            pacingSnapshot = snapshot
        }
        val gatingDecision = pendingDecisions.removeFirstOrNull()
        val startingPredictedMs = snapshot?.workloadSequencePredictedDurationMs ?: 0.0
        val draftOverheadMs = pacingSnapshot?.draftSequenceOverheadDurationMs ?: 0.0
        val pendingDraftWorkMs = queuedPredictedWorkMs + draftOverheadMs * (pendingDecisions.size + 1)
        backlogEndTimeMs = SystemClock.uptimeMillis() + ceil(startingPredictedMs + pendingDraftWorkMs).toLong()
        return gatingDecision
    }

    /** Raises [sizeBucket]'s max toward one completed draft's real duration; non-positive means no observation. */
    fun updateMaxDraftSequenceDurationMs(sizeBucket: SizeBucket, draftSequenceDurationMs: Long) {
        if (draftSequenceDurationMs <= 0L) {
            return
        }
        maxDraftSequenceDurationMsBySize[sizeBucket] =
            maxOf(maxDraftSequenceDurationMsBySize[sizeBucket] ?: 0L, draftSequenceDurationMs)
    }

    /** Records the deadline stamped at a committed capture's onShutter; the newest committed capture's stands. */
    fun updateCaptureDeadlineMs(deadlineUptimeMs: Long) {
        latestCaptureDeadlineMs = deadlineUptimeMs
    }

    /**
     * Queues one decided callback and advances the clock past the draft it admits. Read back off the decision, so the
     * clock can only advance by the delay and work that decision was actually built on.
     */
    fun queuePacingDecision(decision: CaptureAvailablePacingDecision) {
        pendingDecisions.addLast(decision)
        val snapshot = decision.snapshot
        val draftWorkMs = snapshot.workloadSequencePredictedDurationMs + snapshot.draftSequenceOverheadDurationMs
        backlogEndTimeMs = maxOf(decision.decisionUptimeMs + decision.delayMs, backlogEndTimeMs) + ceil(draftWorkMs).toLong()
    }

    /**
     * Duration to set aside for a draft of [sizeBucket]: this burst's measured max, minus the work a demotion took out
     * of this draft, floored by the model's whole-draft prediction.
     *
     * The max was measured on undemoted drafts, so a reduced draft must not be charged for work it no longer runs. The
     * floor is what a burst's first capture prices with, and it keeps the reserve at one whole draft's occupancy - the
     * same thing the backlog clock charges per queued draft - rather than at the node point sum.
     */
    fun reserveDraftSequenceDurationMs(
        sizeBucket: SizeBucket,
        demotedWorkloadPredictedDurationMs: Double,
        draftSequencePredictedDurationMs: Double,
    ): Double = maxOf(
        maxDraftSequenceDurationMs(sizeBucket) - demotedWorkloadPredictedDurationMs,
        draftSequencePredictedDurationMs,
    )

    /**
     * Measured max duration to price [sizeBucket] by. Reading the draft's own size keeps a heavy other-size draft (a
     * MP24 burst) from inflating a MP12 capture's reserve; a size not measured this burst falls back to the heaviest
     * one that was - conservative while cold, exact once its own size has run, and never above a duration this
     * pipeline really produced.
     */
    private fun maxDraftSequenceDurationMs(sizeBucket: SizeBucket): Long =
        maxDraftSequenceDurationMsBySize[sizeBucket]
            ?: maxDraftSequenceDurationMsBySize.values.maxOrNull()
            ?: 0L

    /** Admitted work still ahead of [nowUptimeMs] on the backlog clock. */
    fun backlogMsAt(nowUptimeMs: Long): Long = (backlogEndTimeMs - nowUptimeMs).coerceAtLeast(0L)

    /**
     * How much of that capture's window is still ahead at [nowUptimeMs] - what the paced draft has to finish inside.
     * The window length is the clamp, not a term: an unstamped capture is priced as if its window just opened and a
     * past deadline leaves nothing, so a missing or late stamp can only cost pacing pressure, never invent a delay.
     */
    fun timeToDeadlineMsAt(nowUptimeMs: Long): Long {
        val deadlineUptimeMs = latestCaptureDeadlineMs ?: return MakerFeature.CAPTURE_TIMEOUT_MS
        return (deadlineUptimeMs - nowUptimeMs).coerceIn(0L, MakerFeature.CAPTURE_TIMEOUT_MS)
    }

}
