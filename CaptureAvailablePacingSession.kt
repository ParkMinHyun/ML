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
     * What the next [CaptureAvailablePacer.decideDelay] prices against; null until this burst's first dequeue, which is
     * what makes an idle pipeline answer "no delay". Only [dequeuePacingDecision] writes it, so the snapshot and the
     * clock it rebased cannot disagree.
     */
    var pacingSnapshot: CaptureAvailablePacingSnapshot? = null
        private set

    private val pendingDecisions = ArrayDeque<CaptureAvailablePacingDecision>()
    private val maxDraftSequenceDurationMsBySize = mutableMapOf<SizeBucket, Long>()

    /**
     * When the admitted queue drains, so an estimate of elapsed work rather than a safety bound: it advances by each
     * capture's point prediction plus the learned between-node overhead. The point sum alone omits the inter-node and
     * deinit time and that shortfall compounds with queue depth into a timeout. The decision adds a two-Draft reserve
     * horizon and leaves half of its deficit to Admission; prediction errors cannot accumulate indefinitely because
     * every draft start rebases this clock onto the real one.
     *
     * An absolute uptime, unlike the durations around it: [backlogMsAt] turns it into "how much is left".
     */
    private var backlogEndTimeMs = 0L

    /**
     * Deadline of the newest capture committed to the Draft pipeline. It is deliberately one session-local value:
     * each later capture replaces it, and dropping the session drops the deadline with the backlog it described.
     */
    private var backlogDeadlineMs: Long? = null

    val queuedDraftCount: Int get() = pendingDecisions.size

    /** Point work of every queued draft - the part of pending occupancy the metrics report separately. */
    val queuedPredictedWorkMs: Double
        get() = pendingDecisions.sumOf { it.snapshot.workloadSequencePredictedDurationMs }

    /**
     * Queues one decided callback and advances the clock past the draft it admits. Read back off the decision, so the
     * clock can only advance by the delay and work that decision was actually built on.
     */
    fun queuePacingDecision(decision: CaptureAvailablePacingDecision) {
        pendingDecisions.addLast(decision)
        val snapshot = decision.snapshot
        val draftWorkMs = snapshot.workloadSequencePredictedDurationMs + snapshot.draftSequenceOverheadDurationMs
        backlogEndTimeMs =
            maxOf(decision.decisionUptimeMs + decision.delayMs, backlogEndTimeMs) + ceil(draftWorkMs).toLong()
    }

    /**
     * Pairs with [queuePacingDecision]: hands back the oldest queued decision, adopts [snapshot] as what the next one
     * is priced with, and rebases the clock. All three at once because the decision FIFO doubles as the admitted work
     * queue - a pop that skipped the rebase would leave the clock counting work that has already left the queue.
     *
     * A null [snapshot] is a draft with no predictable workloads (JPEG passthrough): it leaves the previous snapshot
     * standing and brings no point work of its own, though it still occupies the pipeline for one overhead.
     */
    fun dequeuePacingDecision(
        snapshot: CaptureAvailablePacingSnapshot?,
    ): CaptureAvailablePacingDecision? {
        if (snapshot != null) {
            pacingSnapshot = snapshot
        }
        val gatingDecision = pendingDecisions.removeFirstOrNull()
        rebaseBacklogClock(snapshot?.workloadSequencePredictedDurationMs ?: 0.0)
        return gatingDecision
    }

    /**
     * Restarts the clock from now: the draft leaving the queue, plus everything still queued behind it, each priced at
     * its point work plus one between-node overhead. Restarting on every dequeue is what keeps the prediction error the
     * clock accumulates while queueing from compounding across a burst.
     */
    private fun rebaseBacklogClock(startingWorkloadPredictedDurationMs: Double) {
        val draftOverheadDurationMs = pacingSnapshot?.draftSequenceOverheadDurationMs ?: 0.0
        val startingDraftWorkMs = startingWorkloadPredictedDurationMs + draftOverheadDurationMs
        val queuedDraftWorkMs = queuedPredictedWorkMs + draftOverheadDurationMs * pendingDecisions.size
        backlogEndTimeMs = SystemClock.uptimeMillis() + ceil(startingDraftWorkMs + queuedDraftWorkMs).toLong()
    }

    /**
     * Updates the duration context used by subsequent pacing decisions. A zero duration represents cancellation and
     * therefore does not become an observed maximum.
     */
    fun updateMaxDraftSequenceDurationMs(sizeBucket: SizeBucket, draftSequenceDurationMs: Long) {
        if (draftSequenceDurationMs > 0L) {
            maxDraftSequenceDurationMsBySize[sizeBucket] =
                maxOf(maxDraftSequenceDurationMsBySize[sizeBucket] ?: 0L, draftSequenceDurationMs)
        }
    }

    /** Replaces the backlog deadline with the newest committed capture's timeout timestamp. */
    fun updateBacklogDeadlineMs(deadlineUptimeMs: Long) {
        backlogDeadlineMs = deadlineUptimeMs
    }

    /**
     * Measured max duration to price [sizeBucket] by. Reading the draft's own size keeps a heavy other-size draft (a
     * MP24 burst) from inflating a MP12 capture's reserve; a size not measured this burst falls back to the heaviest
     * one that was - conservative while cold, exact once its own size has run, and never above a duration this
     * pipeline really produced.
     */
    fun getMaxDraftSequenceDurationMs(sizeBucket: SizeBucket): Long =
        maxDraftSequenceDurationMsBySize[sizeBucket]
            ?: maxDraftSequenceDurationMsBySize.values.maxOrNull()
            ?: 0L

    /** Admitted work still ahead of [nowUptimeMs] on the backlog clock. */
    fun backlogMsAt(nowUptimeMs: Long): Long = (backlogEndTimeMs - nowUptimeMs).coerceAtLeast(0L)

    /** Remaining part of the latest committed capture's timeout window, or a fresh window before one is available. */
    fun timeToDeadlineMsAt(nowUptimeMs: Long): Long {
        val deadlineUptimeMs = backlogDeadlineMs ?: return MakerFeature.CAPTURE_TIMEOUT_MS
        return (deadlineUptimeMs - nowUptimeMs).coerceIn(0L, MakerFeature.CAPTURE_TIMEOUT_MS)
    }
}
