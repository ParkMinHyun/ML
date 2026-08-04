package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import kotlin.math.ceil

internal const val PACING_WINDOW_DRAFT_COUNT = 2.0

/**
 * The one call CaptureAvailableApmPolicy makes into the draft pipeline. A single method on purpose: reading the backlog
 * against "now", pricing the delay, and recording the admission must happen under one lock, or concurrent callbacks
 * double-admit against a stale backlog. Takes nothing: the open session holds the latest committed backlog deadline,
 * and the decision shares a two-Draft prospective deficit with Admission.
 */
fun interface CaptureAvailablePacingDecider {
    fun decideDelay(): CaptureAvailablePacingDecision?
}

/**
 * Paces captureAvailable callbacks for one burst: draft starts refresh the pacing snapshot, completed drafts update the
 * session maxima, and one FIFO pairs each admission with the next draft start. The sequence key a draft start hands in
 * is re-projected onto the burst's demoted shape by [DraftSequenceAdmissionPolicy]; the APM side sees only the decider.
 */
class CaptureAvailablePacer(
    private val predictor: DraftSequenceExecutionPredictor,
    private val admissionPolicy: DraftSequenceAdmissionPolicy,
) : CaptureAvailablePacingDecider {

    /**
     * The burst in progress and the pacer's only mutable state, so [clear] resets everything by dropping it. The pacer
     * outlives every session: the APM side holds this one reference and must stay able to answer "no delay".
     *
     * Only [decideDelay] opens a session. The deadline setter intentionally drops values received before that first
     * callback: without a pacing snapshot there is no decision they could affect. The session's
     * [CaptureAvailablePacingSession.createdUptimeMs] remains the burst identity offline grouping reads.
     */
    private var captureAvailablePacingSession: CaptureAvailablePacingSession? = null

    /**
     * Half of the two-Draft prospective timeout deficit, queued as the admission record the next draft start consumes.
     * One reserve represents the Draft that starts after this decision and the other the future Draft whose capture is
     * released by pacing. Admission owns the remaining half, so the current-capture level deficit stays diagnostic.
     */
    @Synchronized
    override fun decideDelay(): CaptureAvailablePacingDecision? {
        val session = openSession()
        val snapshot = session.pacingSnapshot ?: return null
        val nowUptimeMs = SystemClock.uptimeMillis()
        val timeToDeadlineMs = session.timeToDeadlineMsAt(nowUptimeMs)
        val backlogMs = session.backlogMsAt(nowUptimeMs)
        val draftSequenceReservedDurationMs = snapshot.draftSequenceReservedDurationMs
        val pacingDelayMs = computePacingDelayMs(
            backlogMs = backlogMs,
            timeToDeadlineMs = timeToDeadlineMs,
            draftSequenceReservedDurationMs = draftSequenceReservedDurationMs,
        )

        val decision = CaptureAvailablePacingDecision(
            delayMs = pacingDelayMs,
            backlogMs = backlogMs,
            queuedDraftCount = session.queuedDraftCount,
            queuedPredictedWorkMs = session.queuedPredictedWorkMs,
            timeToDeadlineMs = timeToDeadlineMs,
            decisionUptimeMs = nowUptimeMs,
            snapshot = snapshot,
        )
        session.queuePacingDecision(decision)
        return decision
    }

    /**
     * Refreshes the pacing snapshot and consumes the oldest admitted callback. A null key is a draft with no
     * predictable workloads (JPEG passthrough): its admission is still consumed to keep the FIFO paired with draft
     * starts, but it adds no predicted work and leaves the snapshot standing. No session means nothing was paced yet,
     * so this draft runs unpaced and the next callback opens one.
     */
    @Synchronized
    fun startDraftSequence(
        workloadSequenceKey: WorkloadSequenceKey?,
        budgetMs: Long,
    ): CaptureAvailablePacingDecision? {
        val session = captureAvailablePacingSession ?: return null
        if (workloadSequenceKey == null) {
            return session.dequeuePacingDecision(null)
        }
        val draftSequenceKey = admissionPolicy.resolveDraftSequenceKey(workloadSequenceKey)
        // The burst's measured max was measured on undemoted drafts, so drop what demotion took out of this one, and
        // floor it by the whole-draft estimate - all a burst's first capture has, and the same occupancy the backlog
        // clock charges per queued draft, which the node point sum alone would under-price.
        val maxDraftSequenceDurationMs =
            session.getMaxDraftSequenceDurationMs(workloadSequenceKey.headWorkloadKey.sizeBucket)
        val demotedWorkloadPredictedDurationMs =
            predictor.estimateDemotedWorkloadDurationMs(workloadSequenceKey, draftSequenceKey)
        val draftSequenceReservedDurationMs = maxOf(
            maxDraftSequenceDurationMs - demotedWorkloadPredictedDurationMs,
            predictor.estimateDraftSequenceDurationMs(draftSequenceKey),
        )

        return session.dequeuePacingDecision(
            CaptureAvailablePacingSnapshot(
                draftSequenceKey = draftSequenceKey.toReplayString(),
                draftSequenceBudgetMs = budgetMs,
                draftSequenceReservedDurationMs = draftSequenceReservedDurationMs,
                draftSequenceOverheadDurationMs = predictor.estimateDraftSequenceOverheadDurationMs(),
                workloadSequencePredictedDurationMs = predictor.estimateWorkloadSequenceDurationMs(draftSequenceKey),
            ),
        )
    }

    /** Pairs with [startDraftSequence] and records the result for subsequent pacing decisions. */
    @Synchronized
    fun endDraftSequence(sizeBucket: SizeBucket, draftSequenceDurationMs: Long) {
        captureAvailablePacingSession?.updateMaxDraftSequenceDurationMs(sizeBucket, draftSequenceDurationMs)
    }

    /** Completes a cancelled FIFO draft without feeding a non-observation into the size-scoped maximum. */
    @Synchronized
    fun cancelDraftSequence(sizeBucket: SizeBucket) {
        captureAvailablePacingSession?.updateMaxDraftSequenceDurationMs(sizeBucket, 0L)
    }

    /**
     * Replaces the deadline that the admitted backlog is priced against with the newest committed capture's deadline.
     * A missing session means the first pacing callback has not opened this burst yet, so the value is intentionally
     * dropped rather than opening state from this secondary hook.
     */
    @Synchronized
    fun setCaptureDeadlineMs(timeoutTimestampMs: Long) {
        captureAvailablePacingSession?.updateBacklogDeadlineMs(timeoutTimestampMs)
    }

    /**
     * Burst identity for metrics: a new value per session, null while none is open. Reading it can never open one - a
     * metrics read must not decide where a burst begins.
     */
    @Synchronized
    fun getCurrentSessionId(): Long? = captureAvailablePacingSession?.createdUptimeMs

    /**
     * Ends the burst session when the draft task queue drains, and again at pipeline close. Paired with
     * [DraftSequenceAdmissionPolicy.clear] on the same drain, so the demoted shape a reserve is projected onto and the
     * maxima it is projected from always describe one burst.
     */
    @Synchronized
    fun clear() {
        captureAvailablePacingSession = null
    }

    /** Returns the burst session, opening it for the first pacing callback. */
    private fun openSession(): CaptureAvailablePacingSession =
        captureAvailablePacingSession
            ?: CaptureAvailablePacingSession().also { captureAvailablePacingSession = it }

}

/**
 * Stateless calculations shared by runtime decisions and offline replay so the two cannot drift.
 */
internal fun computeLevelDeficitMs(
    draftSequenceBudgetMs: Long,
    draftSequenceReservedDurationMs: Double,
): Long = ceil(draftSequenceReservedDurationMs - draftSequenceBudgetMs.coerceAtLeast(0L)).toLong().coerceAtLeast(0L)

/**
 * Pacing's half of the projected deficit across two Draft reserves: the Draft that starts after this decision and the
 * future Draft admitted by the delayed callback. Admission is intentionally left the other half, preventing pacing
 * from suppressing every quality demotion as pressure rises.
 */
internal fun computePacingDelayMs(
    backlogMs: Long,
    timeToDeadlineMs: Long,
    draftSequenceReservedDurationMs: Double,
): Long {
    val estimatedCompletionTimeMs = backlogMs + (draftSequenceReservedDurationMs * PACING_WINDOW_DRAFT_COUNT)
    val deadlineDeficitMs = estimatedCompletionTimeMs - timeToDeadlineMs.coerceAtLeast(0L)
    val pacingDelayMs = deadlineDeficitMs / PACING_WINDOW_DRAFT_COUNT

    return ceil(pacingDelayMs).toLong().coerceAtLeast(0L)
}

/** What one draft start hands the next captureAvailable decision to price with. */
data class CaptureAvailablePacingSnapshot(
    val draftSequenceBudgetMs: Long,
    /** Node processing alone - the point work the backlog clock advances by once per queued draft. */
    val workloadSequencePredictedDurationMs: Double,
    /** Learned between-node overhead added once per queued draft to the backlog clock (clock = predicted + this). */
    val draftSequenceOverheadDurationMs: Double,
    /**
     * Representative Draft duration used once for the current post-decision Draft and once for the future paced Draft.
     * It is the session's observed max re-projected onto this sequence, floored by the whole-Draft estimate.
     */
    val draftSequenceReservedDurationMs: Double,
    val draftSequenceKey: String,
)

/** One captureAvailable pacing decision and the inputs that produced it. */
data class CaptureAvailablePacingDecision(
    val delayMs: Long,
    val backlogMs: Long,
    val queuedDraftCount: Int,
    val queuedPredictedWorkMs: Double,
    /** Window remaining on the newest committed capture deadline when this decision was made. */
    val timeToDeadlineMs: Long,
    val decisionUptimeMs: Long,
    val snapshot: CaptureAvailablePacingSnapshot,
)
