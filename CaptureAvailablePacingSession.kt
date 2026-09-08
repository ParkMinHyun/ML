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

    val createdUptimeMs: Long = SystemClock.uptimeMillis()
    var pacingSnapshot: CaptureAvailablePacingSnapshot? = null
        private set

    private val pendingDecisions = ArrayDeque<CaptureAvailablePacingDecision>()
    private val draftSequenceDurationsMs = RecencyWeightedDistribution()
    private var backlogEndTimeMs = 0L
    private var backlogDeadlineMs: Long? = null
    private val backlogGrowthsMs = RecencyWeightedDistribution()
    private var lastBacklogMs: Long? = null

    val queuedDraftCount: Int get() = pendingDecisions.size
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

    fun observeDraftSequenceDurationMs(draftSequenceDurationMs: Long) {
        if (draftSequenceDurationMs > 0L) {
            draftSequenceDurationsMs.decay()
            draftSequenceDurationsMs.add(draftSequenceDurationMs.toDouble())
        }
    }

    fun updateBacklogDeadlineMs(deadlineUptimeMs: Long) {
        backlogDeadlineMs = deadlineUptimeMs
    }

    fun getReservedDraftSequenceDurationMs(): Double = draftSequenceDurationsMs.expectedMaximum()

    fun backlogMsAt(nowUptimeMs: Long): Long = (backlogEndTimeMs - nowUptimeMs).coerceAtLeast(0L)

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
