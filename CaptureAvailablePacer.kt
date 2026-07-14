package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.watermark.Watermark.WatermarkType
import kotlin.math.ceil

internal fun captureAvailableLevelDeficitMs(draftStartBudgetMs: Long, preferredUpperBoundMs: Double): Long {
    return positiveCeilMs(preferredUpperBoundMs - draftStartBudgetMs.coerceAtLeast(0L))
}

internal fun captureAvailableBacklogDeficitMs(
    backlogMs: Long,
    observedSojournMs: Long,
    preferredUpperBoundMs: Double,
): Long {
    return positiveCeilMs(
        backlogMs + observedSojournMs + preferredUpperBoundMs - MakerFeature.CAPTURE_TIMEOUT_MS,
    )
}

internal fun captureAvailableDelayMs(levelDeficitMs: Long, backlogDeficitMs: Long): Long {
    return maxOf(levelDeficitMs, backlogDeficitMs)
}

private fun positiveCeilMs(valueMs: Double): Long = ceil(valueMs).toLong().coerceAtLeast(0L)

/**
 * Paces captureAvailable callbacks for one burst session. Draft starts refresh the current reserve, APM timings
 * update the observed session maxima, and each admission is paired with the next draft start through one FIFO.
 */
class CaptureAvailablePacer(
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictor.instance,
) {

    private var pacingPrediction: CaptureAvailablePacingPrediction? = null
    private val pendingDecisions = ArrayDeque<CaptureAvailablePacingDecision>()
    private val sessionDemotions = mutableSetOf<SessionDemotion>()

    private var observedMaxDraftMs = 0L
    private var observedSojournMs = 0L
    private var busyUntilUptimeMs = 0L

    /** Refreshes the pacing prediction and consumes the oldest admitted callback. */
    @Synchronized
    fun observeDraftStart(workloadSequenceKey: WorkloadSequenceKey, budgetMs: Long): CaptureAvailablePacingDecision? {
        val preferredSequenceKey = preferredSequenceKey(workloadSequenceKey)
        val preferredEstimate = predictor.estimateDraftPath(preferredSequenceKey)
        val demotedWorkloadMs = if (preferredSequenceKey == workloadSequenceKey) {
            0.0
        } else {
            (predictor.estimateDraftPath(workloadSequenceKey).predictedMs - preferredEstimate.predictedMs)
                .coerceAtLeast(0.0)
        }
        val draftReserveMs = maxOf(
            observedMaxDraftMs.toDouble() - demotedWorkloadMs,
            preferredEstimate.predictedMs,
        )

        pacingPrediction = CaptureAvailablePacingPrediction(
            draftStartBudgetMs = budgetMs,
            mandatoryReserveUpperBoundMs = preferredEstimate.mandatoryReserveUpperBoundMs,
            preferredDraftPathPredictedMs = preferredEstimate.predictedMs,
            preferredDraftPathUpperBoundMs = draftReserveMs,
            workloadSequenceKey = preferredSequenceKey.toReplayString(),
        )
        return rebaseBacklogOnDraftStart(draftReserveMs)
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
        val reservedMs = prediction.preferredDraftPathUpperBoundMs
        val queuedReservedWorkMs = sumQueuedReservedWorkMs()
        val levelDeficitMs = captureAvailableLevelDeficitMs(
            draftStartBudgetMs = prediction.draftStartBudgetMs,
            preferredUpperBoundMs = reservedMs,
        )
        val backlogDeficitMs = captureAvailableBacklogDeficitMs(
            backlogMs = backlogMs,
            observedSojournMs = observedSojournMs,
            preferredUpperBoundMs = reservedMs,
        )
        val delayMs = captureAvailableDelayMs(levelDeficitMs, backlogDeficitMs)

        val decision = CaptureAvailablePacingDecision(
            delayMs = delayMs,
            backlogMs = backlogMs,
            levelDeficitMs = levelDeficitMs,
            backlogDeficitMs = backlogDeficitMs,
            queuedDraftCount = pendingDecisions.size,
            queuedPredictedWorkMs = queuedReservedWorkMs,
            decisionUptimeMs = nowUptimeMs,
            prediction = prediction,
        )
        pendingDecisions.addLast(decision)
        busyUntilUptimeMs = maxOf(nowUptimeMs + delayMs, busyUntilUptimeMs) +
            ceil(reservedMs).toLong()
        return decision
    }

    /** Atomically applies a model decision to a sticky session-demotion group. */
    @Synchronized
    fun admitWithSessionDemotion(demotion: SessionDemotion, modelAdmit: Boolean): Boolean {
        if (demotion in sessionDemotions) {
            return false
        }
        if (!modelAdmit) {
            sessionDemotions += demotion
        }
        return modelAdmit
    }

    /** Returns whether the workload group is already demoted in this burst session. */
    @Synchronized
    fun isSessionDemoted(demotion: SessionDemotion): Boolean = demotion in sessionDemotions

    /** Clears all pacing state when the draft task queue drains. */
    @Synchronized
    fun clear() {
        pacingPrediction = null
        pendingDecisions.clear()
        sessionDemotions.clear()
        observedMaxDraftMs = 0L
        observedSojournMs = 0L
        busyUntilUptimeMs = 0L
    }

    private fun preferredSequenceKey(workloadSequenceKey: WorkloadSequenceKey): WorkloadSequenceKey {
        if (sessionDemotions.isEmpty()) {
            return workloadSequenceKey
        }

        val bokehDemoted = SessionDemotion.BOKEH in sessionDemotions
        val yuvEffectsDemoted = SessionDemotion.YUV_EFFECTS in sessionDemotions
        val hasFrameWatermark = yuvEffectsDemoted && workloadSequenceKey.hasFrameWatermark()
        val preferredWorkloads = workloadSequenceKey.workloadKeys.filterNot { workloadKey ->
            when (workloadKey) {
                is WorkloadKey.Bokeh -> bokehDemoted
                is WorkloadKey.Filter -> yuvEffectsDemoted
                is WorkloadKey.Watermark -> yuvEffectsDemoted && workloadKey.watermarkType != WatermarkType.FRAME
                is WorkloadKey.Decoding -> yuvEffectsDemoted && !hasFrameWatermark
                is WorkloadKey.DynamicFunction, is WorkloadKey.Encoding -> false
            }
        }
        return if (preferredWorkloads.isEmpty()) {
            workloadSequenceKey
        } else {
            WorkloadSequenceKey(preferredWorkloads)
        }
    }

    /** Reuses the decision FIFO as the admitted-reserve FIFO instead of maintaining a second queue. */
    private fun rebaseBacklogOnDraftStart(startingReservedMs: Double): CaptureAvailablePacingDecision? {
        val gatingDecision = pendingDecisions.removeFirstOrNull()
        busyUntilUptimeMs = SystemClock.uptimeMillis() +
            ceil(startingReservedMs + sumQueuedReservedWorkMs()).toLong()
        return gatingDecision
    }

    private fun sumQueuedReservedWorkMs(): Double {
        return pendingDecisions.sumOf { decision ->
            decision.prediction.preferredDraftPathUpperBoundMs
        }
    }

    companion object {
        @JvmStatic
        val instance = CaptureAvailablePacer()
    }
}

/** Workload groups whose first rejection sticks until [CaptureAvailablePacer.clear]. */
enum class SessionDemotion {
    BOKEH,
    YUV_EFFECTS,
}

/** Latest runtime prediction for captureAvailable pacing. */
data class CaptureAvailablePacingPrediction(
    val draftStartBudgetMs: Long,
    val mandatoryReserveUpperBoundMs: Double,
    val preferredDraftPathPredictedMs: Double,
    val preferredDraftPathUpperBoundMs: Double,
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
        draftStartBudgetMs = prediction.draftStartBudgetMs,
        mandatoryReserveUpperBoundMs = prediction.mandatoryReserveUpperBoundMs,
        preferredDraftPathPredictedMs = prediction.preferredDraftPathPredictedMs,
        preferredDraftPathUpperBoundMs = prediction.preferredDraftPathUpperBoundMs,
        workloadSequenceKey = prediction.workloadSequenceKey,
    )
}
