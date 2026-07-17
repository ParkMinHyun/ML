package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.maker.MakerFeature
import kotlin.math.ceil

/**
 * The one call CaptureAvailableApmPolicy exchanges with the draft pipeline: it hands over the timings it observed
 * and receives back the delay decision data. Deliberately a single method - reading the admitted backlog against
 * "now", computing the delay, and recording the admission for the next draft start must happen under one lock, or
 * concurrent captureAvailable callbacks would double-admit against a stale backlog.
 */
fun interface CaptureAvailablePacingDecider {
    fun decideDelay(observedDraftWallMs: Long, observedSojournMs: Long): CaptureAvailablePacingDecision?
}

/**
 * Paces captureAvailable callbacks for one burst session. Draft starts refresh the current ceiling, observed APM
 * timings update the session maxima, and each admission is paired with the next draft start through one FIFO.
 * Asks the [DraftSequenceAdmissionPolicy] which sequence key a planned one becomes in this session's plan.
 * The APM side consumes this only through [CaptureAvailablePacingDecider]; ownership stays with the draft pipeline.
 */
class CaptureAvailablePacer(
    private val predictor: DraftSequenceExecutionPredictor,
    private val admissionPolicy: DraftSequenceAdmissionPolicy,
) : CaptureAvailablePacingDecider {

    private var pacingPrediction: CaptureAvailablePacingPrediction? = null
    private val pendingDecisions = ArrayDeque<CaptureAvailablePacingDecision>()
    private var sessionId = 0

    private var observedMaxDraftMs = 0L
    private var observedSojournMs = 0L

    /**
     * When the admitted queue drains, so an estimate of elapsed work rather than a safety bound: it advances by each
     * capture's point prediction, never by its ceiling. Summing k ceilings would price a queue at k times the session
     * worst case, which no observed burst reaches, and the over-pricing compounds with queue depth. The timeout
     * margin comes from the ceiling that [decideDelay] adds for the single capture being paced; underruns here do not
     * accumulate because every draft start rebases this clock onto the real one.
     */
    private var busyUntilUptimeMs = 0L

    /**
     * Refreshes the pacing prediction and consumes the oldest admitted callback. A null key means a draft with no
     * predictable workloads (e.g. JPEG passthrough): the admission record is still consumed so the FIFO stays paired
     * with draft starts, but it contributes zero predicted work and leaves the pacing prediction as-is.
     */
    @Synchronized
    fun observeDraftStart(plannedSequenceKey: WorkloadSequenceKey?, budgetMs: Long): CaptureAvailablePacingDecision? {
        if (plannedSequenceKey == null) {
            return rebaseBacklogOnDraftStart(0.0)
        }
        val sessionPlannedSequenceKey = admissionPolicy.resolveSessionPlannedSequenceKey(plannedSequenceKey)
        val sessionPlannedEstimate = predictor.estimateDraftSequence(sessionPlannedSequenceKey)
        val demotedWorkloadMs = if (sessionPlannedSequenceKey == plannedSequenceKey) {
            0.0
        } else {
            (predictor.estimateDraftSequence(plannedSequenceKey).predictedMs - sessionPlannedEstimate.predictedMs)
                .coerceAtLeast(0.0)
        }
        val sessionPlannedCeilingMs = maxOf(
            observedMaxDraftMs.toDouble() - demotedWorkloadMs,
            sessionPlannedEstimate.predictedMs,
        )

        pacingPrediction = CaptureAvailablePacingPrediction(
            draftStartBudgetMs = budgetMs,
            mandatoryReserveUpperBoundMs = sessionPlannedEstimate.mandatoryReserveUpperBoundMs,
            sessionPlannedPredictedMs = sessionPlannedEstimate.predictedMs,
            sessionPlannedCeilingMs = sessionPlannedCeilingMs,
            sessionPlannedSequenceKey = sessionPlannedSequenceKey.toReplayString(),
        )
        return rebaseBacklogOnDraftStart(sessionPlannedEstimate.predictedMs)
    }

    /**
     * Records the APM timings observed for this capture (non-positive values mean no observation), then returns the
     * larger of the current draft-budget deficit and the admitted-backlog timeout deficit. The same decision is
     * queued as the admission record consumed by the next draft start.
     */
    @Synchronized
    override fun decideDelay(draftWallMs: Long, sojournMs: Long): CaptureAvailablePacingDecision? {
        if (draftWallMs > 0L) {
            observedMaxDraftMs = maxOf(observedMaxDraftMs, draftWallMs)
        }
        if (sojournMs > 0L) {
            observedSojournMs = maxOf(observedSojournMs, sojournMs)
        }

        val prediction = pacingPrediction ?: return null
        val nowUptimeMs = SystemClock.uptimeMillis()
        val backlogMs = (busyUntilUptimeMs - nowUptimeMs).coerceAtLeast(0L)
        val sessionPlannedCeilingMs = prediction.sessionPlannedCeilingMs
        val queuedPredictedWorkMs = sumQueuedPredictedWorkMs()
        val levelDeficitMs = computeCaptureAvailableLevelDeficitMs(
            draftStartBudgetMs = prediction.draftStartBudgetMs,
            sessionPlannedCeilingMs = sessionPlannedCeilingMs,
        )
        val backlogDeficitMs = computeCaptureAvailableBacklogDeficitMs(
            backlogMs = backlogMs,
            observedSojournMs = observedSojournMs,
            sessionPlannedCeilingMs = sessionPlannedCeilingMs,
        )
        val delayMs = computeCaptureAvailableDelayMs(levelDeficitMs, backlogDeficitMs)

        val decision = CaptureAvailablePacingDecision(
            delayMs = delayMs,
            backlogMs = backlogMs,
            levelDeficitMs = levelDeficitMs,
            backlogDeficitMs = backlogDeficitMs,
            queuedDraftCount = pendingDecisions.size,
            queuedPredictedWorkMs = queuedPredictedWorkMs,
            observedSojournMs = observedSojournMs,
            observedMaxDraftMs = observedMaxDraftMs,
            decisionUptimeMs = nowUptimeMs,
            prediction = prediction,
        )
        pendingDecisions.addLast(decision)
        busyUntilUptimeMs = maxOf(nowUptimeMs + delayMs, busyUntilUptimeMs) +
            ceil(prediction.sessionPlannedPredictedMs).toLong()
        return decision
    }

    /** Burst-session ordinal for metrics: increments every time the drained pipeline clears the pacer. */
    @Synchronized
    fun readCurrentSessionId(): Int = sessionId

    /** Clears all pacing state when the draft task queue drains. */
    @Synchronized
    fun clear() {
        pacingPrediction = null
        pendingDecisions.clear()
        observedMaxDraftMs = 0L
        observedSojournMs = 0L
        busyUntilUptimeMs = 0L
        sessionId++
    }

    /** Reuses the decision FIFO as the admitted-work FIFO instead of maintaining a second queue. */
    private fun rebaseBacklogOnDraftStart(startingPredictedMs: Double): CaptureAvailablePacingDecision? {
        val gatingDecision = pendingDecisions.removeFirstOrNull()
        busyUntilUptimeMs = SystemClock.uptimeMillis() +
            ceil(startingPredictedMs + sumQueuedPredictedWorkMs()).toLong()
        return gatingDecision
    }

    private fun sumQueuedPredictedWorkMs(): Double {
        return pendingDecisions.sumOf { decision ->
            decision.prediction.sessionPlannedPredictedMs
        }
    }

    companion object {
        /**
         * The two deficits and their max, stateless so a decision can be re-derived from persisted pacing inputs
         * alone. Offline replay calls these exact functions to answer "what would today's pacing delay be"; a copy
         * of this arithmetic would let the two drift and report a pacing change as no change.
         */
        internal fun computeCaptureAvailableLevelDeficitMs(
            draftStartBudgetMs: Long,
            sessionPlannedCeilingMs: Double,
        ): Long {
            return ceilToPositiveMs(sessionPlannedCeilingMs - draftStartBudgetMs.coerceAtLeast(0L))
        }

        internal fun computeCaptureAvailableBacklogDeficitMs(
            backlogMs: Long,
            observedSojournMs: Long,
            sessionPlannedCeilingMs: Double,
        ): Long {
            return ceilToPositiveMs(
                backlogMs + observedSojournMs + sessionPlannedCeilingMs - MakerFeature.CAPTURE_TIMEOUT_MS,
            )
        }

        internal fun computeCaptureAvailableDelayMs(levelDeficitMs: Long, backlogDeficitMs: Long): Long {
            return maxOf(levelDeficitMs, backlogDeficitMs)
        }

        private fun ceilToPositiveMs(valueMs: Double): Long = ceil(valueMs).toLong().coerceAtLeast(0L)
    }
}

/** Latest runtime prediction for captureAvailable pacing. */
data class CaptureAvailablePacingPrediction(
    val draftStartBudgetMs: Long,
    /** Model upper bound of the RESERVED tail alone. Classifies log severity; never reaches the delay. */
    val mandatoryReserveUpperBoundMs: Double,
    val sessionPlannedPredictedMs: Double,
    /**
     * Draft time both deficits set aside for the capture being paced. Not a model bound despite sitting beside one:
     * it is the session's observed max draft wall time re-projected onto this draft sequence, floored by the point
     * prediction (all a session's first capture has). Called a ceiling, not a reserve, because "reserve" in this
     * model always means the RESERVED-policy tail - a different quantity, computed a different way.
     */
    val sessionPlannedCeilingMs: Double,
    val sessionPlannedSequenceKey: String,
)

/** One captureAvailable pacing decision and the inputs that produced it. */
data class CaptureAvailablePacingDecision(
    val delayMs: Long,
    val backlogMs: Long,
    val levelDeficitMs: Long,
    val backlogDeficitMs: Long,
    val queuedDraftCount: Int,
    val queuedPredictedWorkMs: Double,
    /** Session max pre-draft latency consumed by the backlog deficit at decision time. */
    val observedSojournMs: Long,
    /** Session max measured draft wall time at decision time, before re-projection onto the demoted shape. */
    val observedMaxDraftMs: Long,
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
        observedSojournMs = observedSojournMs,
        observedMaxDraftMs = observedMaxDraftMs,
        draftStartBudgetMs = prediction.draftStartBudgetMs,
        mandatoryReserveUpperBoundMs = prediction.mandatoryReserveUpperBoundMs,
        sessionPlannedPredictedMs = prediction.sessionPlannedPredictedMs,
        sessionPlannedCeilingMs = prediction.sessionPlannedCeilingMs,
        sessionPlannedSequenceKey = prediction.sessionPlannedSequenceKey,
    )
}
