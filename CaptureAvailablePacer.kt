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
    fun decideDelay(draftSequenceDurationMs: Long, captureAvailableLatencyMs: Long): CaptureAvailablePacingDecision?
}

/**
 * Paces captureAvailable callbacks for one burst session. Draft starts refresh the draft-sequence duration estimate,
 * observed APM timings update the session maxima, and each admission is paired with the next draft start through one
 * FIFO.
 * Asks the [DraftSequenceAdmissionPolicy] which sequence key a planned one becomes in this session's plan.
 * The APM side consumes this only through [CaptureAvailablePacingDecider]; ownership stays with the draft pipeline.
 */
class CaptureAvailablePacer(
    private val predictor: DraftSequenceExecutionPredictor,
    private val admissionPolicy: DraftSequenceAdmissionPolicy,
) : CaptureAvailablePacingDecider {

    /**
     * Everything scoped to the burst session in progress, absent while the pipeline is idle. [clear] drops it whole
     * rather than resetting field by field, so "clear resets the session and nothing else" holds by construction - a
     * new session field cannot be forgotten there. The pacer itself outlives every session on purpose: the APM side
     * holds this one reference, and a pacer between sessions must stay reachable to answer "no delay".
     */
    private var session: CaptureAvailablePacingSession? = null

    /**
     * Refreshes the pacing prediction and consumes the oldest admitted callback. A null key means a draft with no
     * predictable workloads (e.g. JPEG passthrough): the admission record is still consumed so the FIFO stays paired
     * with draft starts, but it contributes zero predicted work and leaves the pacing prediction as-is.
     */
    @Synchronized
    fun startDraftSequence(plannedSequenceKey: WorkloadSequenceKey?, budgetMs: Long): CaptureAvailablePacingDecision? {
        val session = activeSession()
        if (plannedSequenceKey == null) {
            return session.startDraftSequence(null)
        }
        val draftSequenceKey = admissionPolicy.resolveDraftSequenceKey(plannedSequenceKey)
        val draftSequencePredictedDurationMs = predictor.estimateDraftSequenceMs(draftSequenceKey)
        val demotedWorkloadMs = if (draftSequenceKey == plannedSequenceKey) {
            0.0
        } else {
            (predictor.estimateDraftSequenceMs(plannedSequenceKey) - draftSequencePredictedDurationMs)
                .coerceAtLeast(0.0)
        }
        val currentSizeBucket = plannedSequenceKey.headWorkloadKey.sizeBucket
        // Floored by the whole-draft estimate, not the node point sum: the reserve prices the same draft occupancy
        // the backlog clock does, so a session with no observed max yet cannot reserve less than one draft's wall.
        val draftSequencePacingDurationMs = maxOf(
            session.maxDraftSequenceDurationMs(currentSizeBucket).toDouble() - demotedWorkloadMs,
            predictor.estimateDraftSequenceWallMs(draftSequenceKey),
        )

        return session.startDraftSequence(
            CaptureAvailablePacingPrediction(
                draftSequenceBudgetMs = budgetMs,
                draftSequencePredictedDurationMs = draftSequencePredictedDurationMs,
                draftSequenceOverheadDurationMs = predictor.estimateDraftOverheadMs(),
                draftSequencePacingDurationMs = draftSequencePacingDurationMs,
                draftSequenceKey = draftSequenceKey.toReplayString(),
            ),
        )
    }

    /**
     * Records the APM timings observed for this capture (non-positive values mean no observation), then returns the
     * larger of the current draft-budget deficit and the admitted-backlog timeout deficit. The same decision is
     * queued as the admission record consumed by the next draft start.
     */
    @Synchronized
    override fun decideDelay(
        draftSequenceDurationMs: Long,
        captureAvailableLatencyMs: Long,
    ): CaptureAvailablePacingDecision? {
        val session = activeSession()
        session.observeCaptureTimings(draftSequenceDurationMs, captureAvailableLatencyMs)

        val prediction = session.pacingPrediction ?: return null
        val nowUptimeMs = SystemClock.uptimeMillis()
        val backlogMs = session.backlogMsAt(nowUptimeMs)
        val draftSequencePacingDurationMs = prediction.draftSequencePacingDurationMs
        val levelDeficitMs = computeCaptureAvailableLevelDeficitMs(
            draftSequenceBudgetMs = prediction.draftSequenceBudgetMs,
            draftSequencePacingDurationMs = draftSequencePacingDurationMs,
        )
        val backlogDeficitMs = computeCaptureAvailableBacklogDeficitMs(
            backlogMs = backlogMs,
            maxCaptureAvailableLatencyMs = session.maxCaptureAvailableLatencyMs,
            draftSequencePacingDurationMs = draftSequencePacingDurationMs,
        )

        val decision = CaptureAvailablePacingDecision(
            delayMs = maxOf(levelDeficitMs, backlogDeficitMs),
            backlogMs = backlogMs,
            levelDeficitMs = levelDeficitMs,
            backlogDeficitMs = backlogDeficitMs,
            queuedDraftCount = session.queuedDraftCount,
            queuedPredictedWorkMs = session.queuedPredictedWorkMs,
            maxCaptureAvailableLatencyMs = session.maxCaptureAvailableLatencyMs,
            maxDraftSequenceDurationMs = session.maxDraftSequenceDurationMs,
            decisionUptimeMs = nowUptimeMs,
            prediction = prediction,
        )
        session.admit(decision)
        return decision
    }

    /**
     * Records one completed draft's real wall against its own size. The same wall also teaches the predictor's
     * overhead trend, but that is model learning and the draft pipeline feeds it there directly.
     */
    @Synchronized
    fun observeDraftMeasured(sizeBucket: SizeBucket, draftWallMs: Long) {
        activeSession().observeDraftSequenceDuration(sizeBucket, draftWallMs)
    }

    /** Burst-session identity for metrics: a new value every time the drained pipeline clears the pacer. */
    @Synchronized
    fun readCurrentSessionId(): Long = activeSession().createdUptimeMs

    /** Ends the burst session when the draft task queue drains; the next capture starts the next one. */
    @Synchronized
    fun clear() {
        session = null
    }

    /**
     * The session in progress, started by the first capture that needs it. Starting it here rather than in [clear]
     * is what lets [CaptureAvailablePacingSession.createdUptimeMs] mark the burst's real beginning instead of the
     * previous burst's drain, and leaves an idle pipeline holding no session at all.
     */
    private fun activeSession(): CaptureAvailablePacingSession =
        session ?: CaptureAvailablePacingSession().also { session = it }
}

/**
 * The two deficits, stateless so a decision can be re-derived from persisted pacing inputs alone. Offline replay
 * calls these exact functions to answer "what would today's pacing delay be"; a copy of this arithmetic would let the
 * two drift and report a pacing change as no change. The delay they feed is their plain max, written out at both call
 * sites - a wrapper around `maxOf` is not arithmetic that can drift.
 */
internal fun computeCaptureAvailableLevelDeficitMs(
    draftSequenceBudgetMs: Long,
    draftSequencePacingDurationMs: Double,
): Long = ceil(draftSequencePacingDurationMs - draftSequenceBudgetMs.coerceAtLeast(0L)).toLong().coerceAtLeast(0L)

internal fun computeCaptureAvailableBacklogDeficitMs(
    backlogMs: Long,
    maxCaptureAvailableLatencyMs: Long,
    draftSequencePacingDurationMs: Double,
): Long = ceil(
    backlogMs + maxCaptureAvailableLatencyMs + draftSequencePacingDurationMs - MakerFeature.CAPTURE_TIMEOUT_MS,
).toLong().coerceAtLeast(0L)

/** Latest runtime prediction for captureAvailable pacing. */
data class CaptureAvailablePacingPrediction(
    val draftSequenceBudgetMs: Long,
    val draftSequencePredictedDurationMs: Double,
    /** Learned between-node overhead added once per queued draft to the backlog clock (clock work = predicted + this). */
    val draftSequenceOverheadDurationMs: Double,
    /**
     * Draft-sequence duration estimate both deficits set aside for the capture being paced. Not a model bound despite
     * sitting beside one: it is the session's observed max draft wall time re-projected onto this draft sequence,
     * floored by the point prediction (all a session's first capture has).
     */
    val draftSequencePacingDurationMs: Double,
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
    /** Session max takePicture-to-captureAvailable elapsed consumed by the backlog deficit at decision time. */
    val maxCaptureAvailableLatencyMs: Long,
    /** Session max measured draft sequence duration at decision time, before re-projection onto the demoted shape. */
    val maxDraftSequenceDurationMs: Long,
    val decisionUptimeMs: Long,
    val prediction: CaptureAvailablePacingPrediction,
)
