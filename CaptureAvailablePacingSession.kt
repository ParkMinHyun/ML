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
    private var maxDraftSequenceDurationMs = 0L

    /**
     * When the admitted queue drains, so an estimate of elapsed work rather than a safety bound: it advances by each
     * capture's point prediction plus the learned between-node overhead. The point sum alone omits the inter-node and
     * deinit time and that shortfall compounds with queue depth into a timeout. The decision adds a two-Draft reserve
     * horizon and deliberately applies half of its projected deficit as the pacing action. No numeric half is passed
     * to Admission; this heuristic relies on its later node-time budget test to shed optional work under residual
     * pressure.
     * Prediction errors cannot accumulate indefinitely because every draft end rebases this clock onto the real one.
     *
     * An absolute uptime, unlike the durations around it: [backlogMsAt] turns it into "how much is left".
     */
    private var backlogEndTimeMs = 0L

    /**
     * Deadline of the newest capture committed to the Draft pipeline. It is deliberately one session-local value:
     * each later capture replaces it, and dropping the session drops the deadline with the backlog it described.
     */
    private var backlogDeadlineMs: Long? = null

    /**
     * How much the admitted backlog grew between each pair of this burst's callbacks. The decision prices the queue
     * as it stands, but the callback it gates is released one arrival later, so a burst filling faster than it drains
     * is already behind by one of these before its delay takes effect. Recency-weighted like every other learned
     * trend here, so a burst that has begun to converge stops paying for how it started - and per burst, because it
     * describes this shot cadence against this thermal state, not the device.
     */
    private val backlogGrowthsMs = RecencyWeightedDistribution()

    /** The previous callback's backlog, the other half of a difference; null until this burst has decided once. */
    private var lastBacklogMs: Long? = null

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
     * Pairs with [queuePacingDecision]: hands back the oldest queued decision and adopts [snapshot] as what the next
     * one is priced with. The clock is deliberately not rebuilt here - the draft this pop starts is work the clock
     * already counts, so a rebase at this instant would have to re-add that draft out of band. [rebaseBacklogClock]
     * does it where the draft is over and the queue is the whole of the remainder.
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
        return pendingDecisions.removeFirstOrNull()
    }

    /**
     * Restarts the clock from now over the admitted queue alone, each queued draft priced at the point work
     * [estimateWorkloadSequenceDurationMs] reports for its shape plus one [draftSequenceOverheadDurationMs]. Called
     * when a draft ends or is cancelled, the one instant nothing is running: the queue is then the whole of the
     * remaining work, so the clock needs no running draft carried alongside it.
     *
     * Point work is re-asked per queued draft rather than summed off the snapshots they were admitted with. Those
     * snapshots are as old as the queue is deep, and while the pipeline heats up every one of them is cheap - a level
     * error in the completion-time estimate. A level error is exactly what the growth trend cannot correct: growth is
     * a difference of two consecutive backlogs, so a bias present in both cancels out of every sample it teaches.
     *
     * Restarting here is also what keeps the prediction error the clock accumulates while queueing from compounding
     * across a burst.
     */
    fun rebaseBacklogClock(
        draftSequenceOverheadDurationMs: Double,
        estimateWorkloadSequenceDurationMs: (WorkloadSequenceKey) -> Double,
    ) {
        val queuedDraftWorkMs = pendingDecisions.sumOf { decision ->
            estimateWorkloadSequenceDurationMs(decision.snapshot.draftSequenceWorkloadKey) +
                draftSequenceOverheadDurationMs
        }
        backlogEndTimeMs = SystemClock.uptimeMillis() + ceil(queuedDraftWorkMs).toLong()
    }

    /**
     * Updates the duration context used by subsequent pacing decisions. A non-positive duration was never measured -
     * a draft the pipeline never started - so it does not become an observed maximum.
     */
    fun updateMaxDraftSequenceDurationMs(draftSequenceDurationMs: Long) {
        if (draftSequenceDurationMs > 0L) {
            maxDraftSequenceDurationMs = maxOf(maxDraftSequenceDurationMs, draftSequenceDurationMs)
        }
    }

    /** Replaces the backlog deadline with the newest committed capture's timeout timestamp. */
    fun updateBacklogDeadlineMs(deadlineUptimeMs: Long) {
        backlogDeadlineMs = deadlineUptimeMs
    }

    /**
     * The heaviest Draft this burst has measured, whatever frame size produced it. Every Draft in the burst shares
     * one thermal and memory state, so the heaviest observed occupancy is the honest price of the next one; scoping
     * it per size only let a lighter size keep a cheap reserve while the heavy one was throttling the same CPU.
     * Never above a duration this pipeline really produced.
     */
    fun getMaxDraftSequenceDurationMs(): Long = maxDraftSequenceDurationMs

    /** Admitted work still ahead of [nowUptimeMs] on the backlog clock. */
    fun backlogMsAt(nowUptimeMs: Long): Long = (backlogEndTimeMs - nowUptimeMs).coerceAtLeast(0L)

    /**
     * Teaches [backlogMs] to the growth trend and reads the trend back including it. Call exactly once per decision,
     * with the value [backlogMsAt] just returned: the trend is a difference of consecutive callbacks, so a caller
     * that read the backlog without teaching it would silently measure an arbitrary subset of the burst.
     *
     * Floored at zero because a draining queue teaches a negative sample and lowers the trend, but pacing may become
     * more careful within a burst and never less. The mean, not [RecencyWeightedDistribution.expectedMaximum], keeps
     * the clock the estimate its own contract declares - the safety bound lives in the reserve.
     */
    fun observeBacklogGrowthMs(backlogMs: Long): Double {
        lastBacklogMs?.let { previousBacklogMs ->
            backlogGrowthsMs.decay()
            backlogGrowthsMs.add((backlogMs - previousBacklogMs).toDouble())
        }
        lastBacklogMs = backlogMs
        return backlogGrowthsMs.mean().coerceAtLeast(0.0)
    }

    /** Remaining part of the latest committed capture's timeout window, or a fresh window before one is available. */
    fun timeToDeadlineMsAt(nowUptimeMs: Long): Long {
        val deadlineUptimeMs = backlogDeadlineMs ?: return MakerFeature.CAPTURE_TIMEOUT_MS
        return (deadlineUptimeMs - nowUptimeMs).coerceIn(0L, MakerFeature.CAPTURE_TIMEOUT_MS)
    }
}
