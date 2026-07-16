package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.maker.MakerFeature
import kotlin.math.ceil

internal fun captureAvailableLevelDeficitMs(draftStartBudgetMs: Long, draftSequenceCeilingMs: Double): Long {
    return positiveCeilMs(draftSequenceCeilingMs - draftStartBudgetMs.coerceAtLeast(0L))
}

internal fun captureAvailableBacklogDeficitMs(
    backlogMs: Long,
    observedSojournMs: Long,
    draftSequenceCeilingMs: Double,
): Long {
    return positiveCeilMs(
        backlogMs + observedSojournMs + draftSequenceCeilingMs - MakerFeature.CAPTURE_TIMEOUT_MS,
    )
}

internal fun captureAvailableDelayMs(levelDeficitMs: Long, backlogDeficitMs: Long): Long {
    return maxOf(levelDeficitMs, backlogDeficitMs)
}

private fun positiveCeilMs(valueMs: Double): Long = ceil(valueMs).toLong().coerceAtLeast(0L)

/**
 * Paces captureAvailable callbacks for one burst session. Draft starts refresh the current ceiling, APM timings
 * update the observed session maxima, and each admission is paired with the next draft start through one FIFO.
 * Asks the [DraftSequenceAdmissionPolicy] which draft sequence a planned one becomes in this session.
 */
class CaptureAvailablePacer(
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictor.instance,
    private val admissionPolicy: DraftSequenceAdmissionPolicy = DraftSequenceAdmissionPolicy.instance,
) {

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
        val draftSequence = admissionPolicy.draftSequence(plannedSequenceKey)
        val draftSequenceEstimate = predictor.estimateDraftSequence(draftSequence)
        val demotedWorkloadMs = if (draftSequence == plannedSequenceKey) {
            0.0
        } else {
            (predictor.estimateDraftSequence(plannedSequenceKey).predictedMs - draftSequenceEstimate.predictedMs)
                .coerceAtLeast(0.0)
        }
        val draftCeilingMs = maxOf(
            observedMaxDraftMs.toDouble() - demotedWorkloadMs,
            draftSequenceEstimate.predictedMs,
        )

        pacingPrediction = CaptureAvailablePacingPrediction(
            draftStartBudgetMs = budgetMs,
            mandatoryReserveUpperBoundMs = draftSequenceEstimate.mandatoryReserveUpperBoundMs,
            draftSequencePredictedMs = draftSequenceEstimate.predictedMs,
            draftSequenceCeilingMs = draftCeilingMs,
            workloadSequenceKey = draftSequence.toReplayString(),
        )
        return rebaseBacklogOnDraftStart(draftSequenceEstimate.predictedMs)
    }

    /** Records the APM timings observed together for one capture. */
    @Synchronized
    fun observeDraftTimings(draftWallMs: Long, sojournMs: Long) {
        if (draftWallMs > 0L) {
            observedMaxDraftMs = maxOf(observedMaxDraftMs, draftWallMs)
        }
        if (sojournMs > 0L) {
            observedSojournMs = maxOf(observedSojournMs, sojournMs)
        }
    }

    /**
     * Returns the larger of the current draft-budget deficit and the admitted-backlog timeout deficit. The same
     * decision is queued as the admission record consumed by the next draft start.
     */
    @Synchronized
    fun decideDelay(): CaptureAvailablePacingDecision? {
        val prediction = pacingPrediction ?: return null
        val nowUptimeMs = SystemClock.uptimeMillis()
        val backlogMs = (busyUntilUptimeMs - nowUptimeMs).coerceAtLeast(0L)
        val draftCeilingMs = prediction.draftSequenceCeilingMs
        val queuedPredictedWorkMs = sumQueuedPredictedWorkMs()
        val levelDeficitMs = captureAvailableLevelDeficitMs(
            draftStartBudgetMs = prediction.draftStartBudgetMs,
            draftSequenceCeilingMs = draftCeilingMs,
        )
        val backlogDeficitMs = captureAvailableBacklogDeficitMs(
            backlogMs = backlogMs,
            observedSojournMs = observedSojournMs,
            draftSequenceCeilingMs = draftCeilingMs,
        )
        val delayMs = captureAvailableDelayMs(levelDeficitMs, backlogDeficitMs)

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
            ceil(prediction.draftSequencePredictedMs).toLong()
        return decision
    }

    /** Burst-session ordinal for metrics: increments every time the drained pipeline clears the pacer. */
    @Synchronized
    fun currentSessionId(): Int = sessionId

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
            decision.prediction.draftSequencePredictedMs
        }
    }

    companion object {
        @JvmStatic
        val instance = CaptureAvailablePacer()
    }
}

/** Latest runtime prediction for captureAvailable pacing. */
data class CaptureAvailablePacingPrediction(
    val draftStartBudgetMs: Long,
    /** Model upper bound of the RESERVED tail alone. Classifies log severity; never reaches the delay. */
    val mandatoryReserveUpperBoundMs: Double,
    val draftSequencePredictedMs: Double,
    /**
     * Draft time both deficits set aside for the capture being paced. Not a model bound despite sitting beside one:
     * it is the session's observed max draft wall time re-projected onto this draft sequence, floored by the point
     * prediction (all a session's first capture has). Called a ceiling, not a reserve, because "reserve" in this
     * model always means the RESERVED-policy tail - a different quantity, computed a different way.
     */
    val draftSequenceCeilingMs: Double,
    val workloadSequenceKey: String,
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
        draftSequencePredictedMs = prediction.draftSequencePredictedMs,
        draftSequenceCeilingMs = prediction.draftSequenceCeilingMs,
        workloadSequenceKey = prediction.workloadSequenceKey,
    )
}
