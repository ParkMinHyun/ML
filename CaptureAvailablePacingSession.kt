package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import kotlin.math.ceil

/**
 * State of one burst session and the arithmetic that maintains it: everything [CaptureAvailablePacer.clear]
 * discards, and nothing it must keep. Owns the admitted-work FIFO and the backlog clock derived from it, so the two
 * can never disagree - the pacer reads what it needs to price a delay and hands back the decision it made.
 *
 * Not synchronized: the pacer reaches every method under its own lock, which is the same lock the APM callback and
 * the draft pipeline already contend for.
 */
internal class CaptureAvailablePacingSession {

    /**
     * When this session began, which doubles as its identity: offline grouping reads "changed = new session", and a
     * creation uptime can never repeat one an earlier session - or an earlier pacer instance - saw. The plain
     * ordinal this replaced restarted at 0 whenever the draft pipeline was recreated (camera close/open), so two
     * camera sessions merged into one whenever neither had drained.
     */
    val createdUptimeMs: Long = SystemClock.uptimeMillis()

    /**
     * Latest draft-start snapshot the next [CaptureAvailablePacer.decideDelay] prices against; null until this
     * session's first draft start, which is what makes an idle pipeline answer "no delay". Only
     * [updatePacingSnapshot] writes it, so the stored snapshot and the clock it rebased can never disagree.
     */
    var pacingSnapshot: CaptureAvailablePacingSnapshot? = null
        private set

    private val pendingDecisions = ArrayDeque<CaptureAvailablePacingDecision>()
    private val maxDraftSequenceDurationMsBySize = mutableMapOf<SizeBucket, Long>()

    /**
     * When the admitted queue drains, so an estimate of elapsed work rather than a safety bound: it advances by each
     * capture's point prediction plus the learned between-node overhead snapshotted into the pacing snapshot,
     * never by its draft-sequence duration estimate. The point sum alone under-prices real pipeline occupancy (it
     * omits the inter-node/deinit time), and that shortfall compounds with queue depth into a timeout; adding the
     * overhead prices each queued draft by its real occupancy. The duration estimate is still avoided here - summing
     * k conservative estimates would price a queue at k times the session worst case, which no observed burst reaches.
     * The timeout margin comes from the estimate that [CaptureAvailablePacer.decideDelay] adds for the single capture
     * being paced; underruns here do not accumulate because every draft start rebases this clock onto the real one.
     *
     * An absolute uptime, unlike the durations around it: [backlogMsAt] is what turns it into "how much is left".
     */
    private var backlogEndTimeMs = 0L

    val queuedDraftCount: Int get() = pendingDecisions.size

    /** Point work of every queued draft - the part of pending occupancy the metrics report separately. */
    val queuedPredictedWorkMs: Double
        get() = pendingDecisions.sumOf { it.snapshot.draftSequencePredictedDurationMs }

    /**
     * Adopts the starting draft's snapshot and rebases the clock onto it. Reuses the decision FIFO as the
     * admitted-work FIFO instead of maintaining a second queue: pops the admission this draft start consumes, then
     * restarts the clock from the draft starting now plus everything still queued behind it, each priced at its point
     * work plus one between-node overhead - so one overhead per queued draft, and one more for the starting draft.
     *
     * A null [snapshot] is a draft with no predictable workloads (e.g. JPEG passthrough): it contributes zero starting
     * work and leaves the previous snapshot standing, so the next pacing decision still has one.
     */
    fun updatePacingSnapshot(
        snapshot: CaptureAvailablePacingSnapshot?,
    ): CaptureAvailablePacingDecision? {
        if (snapshot != null) {
            pacingSnapshot = snapshot
        }
        val gatingDecision = pendingDecisions.removeFirstOrNull()
        val startingPredictedMs = snapshot?.draftSequencePredictedDurationMs ?: 0.0
        val draftOverheadMs = pacingSnapshot?.draftSequenceOverheadDurationMs ?: 0.0
        val pendingDraftWorkMs = queuedPredictedWorkMs + draftOverheadMs * (pendingDecisions.size + 1)
        backlogEndTimeMs = SystemClock.uptimeMillis() + ceil(startingPredictedMs + pendingDraftWorkMs).toLong()
        return gatingDecision
    }

    /** Records one completed draft's real duration against its own size; non-positive means no observation. */
    fun updateDraftSequenceDuration(sizeBucket: SizeBucket, draftSequenceDurationMs: Long) {
        if (draftSequenceDurationMs <= 0L) {
            return
        }
        maxDraftSequenceDurationMsBySize[sizeBucket] =
            maxOf(maxDraftSequenceDurationMsBySize[sizeBucket] ?: 0L, draftSequenceDurationMs)
    }

    /**
     * Queues one decided callback - the [queuedDraftCount] and [queuedPredictedWorkMs] this adds to - and advances the
     * clock past the draft it admits. Everything is read back off the decision, so the clock can only ever advance by
     * the delay and work that decision was actually built on.
     */
    fun queuePacingDecision(decision: CaptureAvailablePacingDecision) {
        pendingDecisions.addLast(decision)
        val snapshot = decision.snapshot
        val draftWorkMs = snapshot.draftSequencePredictedDurationMs + snapshot.draftSequenceOverheadDurationMs
        backlogEndTimeMs = maxOf(decision.decisionUptimeMs + decision.delayMs, backlogEndTimeMs) + ceil(draftWorkMs).toLong()
    }

    /**
     * Measured max duration to price [sizeBucket] by, fed by the draft pipeline at completion
     * ([updateDraftSequenceDuration]) where both the size and the real duration are known. Reading the current
     * draft's own size keeps a heavy other-size draft (a MP24 burst) from inflating a MP12 capture's reserve; a size
     * not yet measured this session falls back to the heaviest size this session did measure - conservative for a
     * genuinely cold size, exact once its own size has run.
     *
     * The APM-fed maximum this fallback replaced measured a wider span (the whole draft task, outside the draft
     * process lock), so a cold size priced by a number tens of milliseconds above any wall the map holds.
     */
    fun getMaxDraftSequenceDurationMs(sizeBucket: SizeBucket): Long =
        maxDraftSequenceDurationMsBySize[sizeBucket]
            ?: maxDraftSequenceDurationMsBySize.values.maxOrNull()
            ?: 0L

    /** Admitted work still ahead of [nowUptimeMs] on the backlog clock. */
    fun backlogMsAt(nowUptimeMs: Long): Long = (backlogEndTimeMs - nowUptimeMs).coerceAtLeast(0L)

}
