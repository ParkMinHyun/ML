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
     * session's first draft start, which is what makes an idle pipeline answer "no delay".
     */
    var pacingPrediction: CaptureAvailablePacingPrediction? = null

    private val pendingDecisions = ArrayDeque<CaptureAvailablePacingDecision>()
    private val observedMaxDraftMsBySize = mutableMapOf<SizeBucket, Long>()

    /**
     * When the admitted queue drains, so an estimate of elapsed work rather than a safety bound: it advances by each
     * capture's point prediction plus the learned between-node overhead snapshotted into the pacing prediction,
     * never by its ceiling. The point sum alone under-prices real pipeline occupancy (it omits the inter-node/deinit
     * time), and that shortfall compounds with queue depth into a timeout; adding the overhead prices each queued
     * draft by its real occupancy. The ceiling is still avoided here - summing k ceilings would price a queue at k
     * times the session worst case, which no observed burst reaches. The timeout margin comes from the ceiling that
     * [CaptureAvailablePacer.decideDelay] adds for the single capture being paced; underruns here do not accumulate
     * because every draft start rebases this clock onto the real one.
     */
    private var busyUntilUptimeMs = 0L

    var observedMaxDraftMs = 0L
        private set
    var observedSojournMs = 0L
        private set

    val queuedDraftCount: Int get() = pendingDecisions.size

    /** Records one capture's APM timings into the session maxima; non-positive values mean no observation. */
    fun observeApmTimings(draftWallMs: Long, sojournMs: Long) {
        if (draftWallMs > 0L) {
            observedMaxDraftMs = maxOf(observedMaxDraftMs, draftWallMs)
        }
        if (sojournMs > 0L) {
            observedSojournMs = maxOf(observedSojournMs, sojournMs)
        }
    }

    /** Records one completed draft's real wall against its own size; non-positive means no observation. */
    fun observeDraftWall(sizeBucket: SizeBucket, draftWallMs: Long) {
        if (draftWallMs <= 0L) {
            return
        }
        observedMaxDraftMsBySize[sizeBucket] = maxOf(observedMaxDraftMsBySize[sizeBucket] ?: 0L, draftWallMs)
    }

    /**
     * Observed max draft wall to price [sizeBucket] by, fed by the draft pipeline at completion ([observeDraftWall])
     * where both the size and the real wall are known. Reading the current draft's own size keeps a heavy other-size
     * draft (a MP24 burst) from inflating a MP12 capture's reserve; a size not yet measured this session falls back
     * to the size-agnostic max - conservative for a genuinely cold size, exact once its own size has run.
     */
    fun observedMaxDraftMsFor(sizeBucket: SizeBucket?): Long =
        sizeBucket?.let { observedMaxDraftMsBySize[it] } ?: observedMaxDraftMs

    /** Admitted work still ahead of [nowUptimeMs] on the backlog clock. */
    fun backlogMsAt(nowUptimeMs: Long): Long = (busyUntilUptimeMs - nowUptimeMs).coerceAtLeast(0L)

    /** Point work of every queued draft - the part of pending occupancy the metrics report separately. */
    fun queuedPredictedWorkMs(): Double = pendingDecisions.sumOf { it.prediction.sessionPlannedPredictedMs }

    /**
     * Queues one admitted callback and advances the clock past the draft it admits. Everything is read back off the
     * decision, so the clock can only ever advance by the delay and work that decision was actually built on.
     */
    fun admit(decision: CaptureAvailablePacingDecision) {
        pendingDecisions.addLast(decision)
        val prediction = decision.prediction
        val draftWorkMs = prediction.sessionPlannedPredictedMs + prediction.sessionPlannedDraftOverheadMs
        busyUntilUptimeMs = maxOf(decision.decisionUptimeMs + decision.delayMs, busyUntilUptimeMs) +
            ceil(draftWorkMs).toLong()
    }

    /**
     * Reuses the decision FIFO as the admitted-work FIFO instead of maintaining a second queue: pops the admission
     * this draft start consumes, then restarts the clock from the draft starting now plus everything still queued
     * behind it, each priced at its point work plus one between-node overhead - so one overhead per queued draft,
     * and one more for the starting draft.
     */
    fun rebaseBacklogOnDraftStart(startingPredictedMs: Double): CaptureAvailablePacingDecision? {
        val gatingDecision = pendingDecisions.removeFirstOrNull()
        val draftOverheadMs = pacingPrediction?.sessionPlannedDraftOverheadMs ?: 0.0
        val pendingDraftWorkMs = queuedPredictedWorkMs() + draftOverheadMs * (pendingDecisions.size + 1)
        busyUntilUptimeMs = SystemClock.uptimeMillis() + ceil(startingPredictedMs + pendingDraftWorkMs).toLong()
        return gatingDecision
    }
}
