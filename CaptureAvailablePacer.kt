package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import kotlin.math.ceil

internal const val PACING_WINDOW_DRAFT_COUNT = 2.0

/**
 * The one call CaptureAvailableApmPolicy makes into the draft pipeline. A single method on purpose: reading the backlog
 * against "now", pricing the delay, and recording the admission must happen under one lock, or concurrent callbacks
 * double-admit against a stale backlog. Takes nothing: the open session holds the latest committed backlog deadline.
 * The decision prices a two-Draft prospective horizon and deliberately applies half of its projected deficit; it
 * passes no numeric deficit share to node-time Admission.
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
     * Half of the two-Draft prospective timeout deficit, queued as the callback record the next draft start consumes.
     * One reserve represents the Draft that starts after this decision and the other the future Draft whose capture is
     * released by pacing. Applying half is an intuitive coordination heuristic, not an exact fixed-point result: no
     * half-deficit value is passed to Admission. Pacing relies on node-time Admission to shed optional work if
     * residual pressure leaves its suffix upper bound above the live budget, while the current-capture level deficit
     * stays diagnostic.
     */
    @Synchronized
    override fun decideDelay(): CaptureAvailablePacingDecision? {
        val session = openSession()
        val snapshot = session.pacingSnapshot ?: return null
        val nowUptimeMs = SystemClock.uptimeMillis()
        val timeToDeadlineMs = session.timeToDeadlineMsAt(nowUptimeMs)
        val backlogMs = session.backlogMsAt(nowUptimeMs)
        val backlogGrowthMs = session.observeBacklogGrowthMs(backlogMs)
        val draftSequenceReservedDurationMs = snapshot.draftSequenceReservedDurationMs
        val pacingDelayMs = computePacingDelayMs(
            backlogMs = backlogMs,
            backlogGrowthMs = backlogGrowthMs,
            timeToDeadlineMs = timeToDeadlineMs,
            draftSequenceReservedDurationMs = draftSequenceReservedDurationMs,
        )

        val decision = CaptureAvailablePacingDecision(
            delayMs = pacingDelayMs,
            backlogMs = backlogMs,
            backlogGrowthMs = backlogGrowthMs,
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
        val expectedDraftSequenceDurationMs = session.getReservedDraftSequenceDurationMs()
        val demotedWorkloadPredictedDurationMs =
            predictor.estimateDemotedWorkloadDurationMs(workloadSequenceKey, draftSequenceKey)
        val draftSequenceReservedDurationMs = maxOf(
            expectedDraftSequenceDurationMs - demotedWorkloadPredictedDurationMs,
            predictor.estimateDraftSequenceDurationMs(draftSequenceKey),
        )

        return session.dequeuePacingDecision(
            CaptureAvailablePacingSnapshot(
                draftSequenceKey = draftSequenceKey.toReplayString(),
                draftSequenceBudgetMs = budgetMs,
                draftSequenceReservedDurationMs = draftSequenceReservedDurationMs,
                draftSequenceOverheadDurationMs = predictor.estimateDraftSequenceOverheadDurationMs(),
                workloadSequencePredictedDurationMs = predictor.estimateWorkloadSequenceDurationMs(draftSequenceKey),
                draftSequenceWorkloadKey = draftSequenceKey,
            ),
        )
    }

    /**
     * Pairs with [startDraftSequence]: teaches the measured wall to subsequent reserves, then re-anchors the backlog
     * clock. This is the instant the pipeline knows the most - the predictor has just learned from this draft, one
     * line before this call - and with nothing running the admitted queue is the whole of the remaining work.
     */
    @Synchronized
    fun endDraftSequence(draftSequenceDurationMs: Long) {
        val session = captureAvailablePacingSession ?: return
        session.observeDraftSequenceDurationMs(draftSequenceDurationMs)
        session.rebaseBacklogClock(
            draftSequenceOverheadDurationMs = predictor.estimateDraftSequenceOverheadDurationMs(),
            estimateWorkloadSequenceDurationMs = predictor::estimateWorkloadSequenceDurationMs,
        )
    }

    /**
     * A capture that entered the Draft pipeline and will run no draft sequence at all - the original image is saved as
     * it stands, which is what every task still queued behind a capture timeout is marked to do. It consumes the
     * admission [startDraftSequence] would have consumed, because the FIFO pairs admissions with pipeline entries
     * rather than with node chains, and this is the only other way out of it: a capture that took a slot and never
     * gave it back would sit in the admitted queue for the rest of the burst and shift every later decision onto the
     * wrong Draft.
     *
     * It leaves the clock alone deliberately. [endDraftSequence] rebuilds it from the admitted queue at the next draft
     * end, so a price this pop just retired cannot outlive one draft, and re-anchoring here would only move that
     * correction earlier by less than one draft wall.
     */
    @Synchronized
    fun skipDraftSequence() {
        captureAvailablePacingSession?.dequeuePacingDecision(null)
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
     * [DraftSequenceAdmissionPolicy.reset] on the same drain, so the demoted shape a reserve is projected onto and the
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
 * Pacing deliberately applies half of the projected deficit across two Draft reserves: the Draft that starts after
 * this decision and the future Draft admitted by the delayed callback. This is an intuitive coordination heuristic,
 * not an exact fixed-point derivation, and no half-deficit value is transferred to Admission. Pacing relies on
 * Admission's later ordinary node-time budget test to shed optional work if residual pressure remains.
 *
 * [backlogGrowthMs] projects the queue one arrival ahead, which is what lets the delay land on a shutter that has not
 * fired yet; without it the decision trails the ramp and its delay arrives after the capture it was meant to save. It
 * is a difference and never a running total, so it cannot wind up: once pacing holds the queue flat the samples fall
 * to zero and the term retires itself.
 *
 * The delay is bounded by one representative Draft plus that growth, which is the share this decision is entitled to
 * hold a shutter for. Reading the delay back as
 * `draftSequenceReservedDurationMs + (backlogMs + backlogGrowthMs - timeToDeadlineMs) / 2` splits it in two: the
 * Draft this capture itself adds to the pipeline, and half of however far the admitted queue already overruns the
 * window. The second part is a debt earlier captures ran up, and holding one shutter cannot retire it - the queued
 * work does not finish any sooner for waiting, and the deadline being priced belongs to a capture already inside the
 * queue. So only the growth's worth of it is honoured here; residual pressure past that is Admission's to shed.
 */
internal fun computePacingDelayMs(
    backlogMs: Long,
    backlogGrowthMs: Double,
    timeToDeadlineMs: Long,
    draftSequenceReservedDurationMs: Double,
): Long {
    val estimatedCompletionTimeMs = backlogMs + backlogGrowthMs + (draftSequenceReservedDurationMs * PACING_WINDOW_DRAFT_COUNT)
    val deadlineDeficitMs = estimatedCompletionTimeMs - timeToDeadlineMs.coerceAtLeast(0L)
    val pacingDelayMs = deadlineDeficitMs / PACING_WINDOW_DRAFT_COUNT
    val pacingDelayUpperBoundMs = (draftSequenceReservedDurationMs + backlogGrowthMs).toLong()

    return ceil(pacingDelayMs).toLong().coerceIn(0L, pacingDelayUpperBoundMs)
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
    /**
     * The demoted shape [draftSequenceKey] spells out, kept typed so a queued draft can be re-priced against a
     * predictor that has learned since it was admitted. [draftSequenceKey] stays a string because it is what the
     * metrics store and the offline replay read, and neither can hold a live key.
     */
    val draftSequenceWorkloadKey: WorkloadSequenceKey,
)

/** One captureAvailable pacing decision and the inputs that produced it. */
data class CaptureAvailablePacingDecision(
    val delayMs: Long,
    val backlogMs: Long,
    /** Learned per-callback growth of [backlogMs] in this burst, added once to the completion-time estimate. */
    val backlogGrowthMs: Double,
    val queuedDraftCount: Int,
    val queuedPredictedWorkMs: Double,
    /** Window remaining on the newest committed capture deadline when this decision was made. */
    val timeToDeadlineMs: Long,
    val decisionUptimeMs: Long,
    val snapshot: CaptureAvailablePacingSnapshot,
)
