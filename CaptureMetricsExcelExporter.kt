package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.Build
import com.samsung.android.camera.core2.container.DynamicShotMode
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.watermark.Watermark.WatermarkType
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.ceil
import kotlin.math.floor

class CaptureMetricsExcelExporter(
    private val context: Context,
    private val repository: CaptureMetricsRepository,
) {

    private class EnrichedCaptureRow(
        val row: CaptureRow,
        val sessionSummary: SessionSummary,
        val wallBase: WallBaseDiagnostics,
    )

    /**
     * Cross-capture measurements for evaluating a future draft-wall-time-based pacing clock. They quantify the two
     * obstacles a wall-based clock hits: completion-lag (the freshest wall observable at a decision lags the drafts
     * actually in flight) and pricing error (the recorded backlog vs the pipeline's real time-to-free). Computed from
     * the session's draft-start/end timeline, so they need the whole group, not one row.
     */
    private class WallBaseDiagnostics(
        /** Drafts started but not yet finished at this capture's pacing decision - the occupancy a wall must price. */
        val inFlightDraftCountAtDecision: Int?,
        /** Wall of the most recently finished draft as of the decision - the freshest wall a wall-EWMA could see. */
        val freshestCompletedDraftWallMs: Long?,
        /**
         * Session max draft wall of the SAME draft size as this capture, observed before its decision. The pacer's
         * ceiling falls back to [observedMaxDraftMs] (max over all sizes) only while this size is still cold; the gap
         * between the two is the cross-size contamination a heavy other-size draft (e.g. MP24) can add to this size's
         * (e.g. MP12) reserve.
         */
        val sizeScopedObservedMaxDraftMs: Long?,
        /**
         * Session max draft wall over every size, observed before this capture's decision - the pacer's cold-size
         * fallback. Reconstructed rather than recorded: the runtime no longer carries it on the decision, so a capture
         * whose Draft timeline is missing a start/end pair is invisible here and the max reads low on those rows.
         */
        val observedMaxDraftMs: Long?,
    )

    /**
     * One measured queue snapshot reconstructed from completed Draft start/end timestamps. Backlog is the remaining
     * wall time until every earlier Draft has completed; queue depth counts earlier Drafts that have not started yet.
     * Outstanding depth additionally includes a Draft already running at the snapshot.
     */
    private class Rq3QueueState(
        val traceComplete: Boolean,
        val realBacklogMs: Long?,
        val realQueueDepth: Int?,
        val realOutstandingDraftCount: Int?,
    )

    /** Per-shot RQ3 row. The pacing decision persisted on shot i is the decision consumed before shot i starts. */
    private class Rq3CaptureRow(
        val deviceModel: String,
        val capture: CaptureRow,
        val runId: Int,
        val runShotIndex: Int,
        val runShotCount: Int,
        val startingOverheatLevel: Int?,
        val sizeBucket: String?,
        val isLowMemory: Boolean?,
        val pacingDecisionRecorded: Boolean,
        val pacingObservationAvailable: Boolean,
        val pacingObservationSource: String,
        val delayAppliesBeforeShotIndex: Int?,
        val appliedDelayMs: Long,
        val transitionDelayMs: Long?,
        val pacedTransition: Boolean?,
        val cumulativeDelayTraceComplete: Boolean,
        val cumulativeTransitionDelayMs: Long?,
        val releaseUptimeMs: Long?,
        val beforeDelayState: Rq3QueueState,
        val atReleaseState: Rq3QueueState,
    )

    /** One row per ppSequenceId-reset-delimited experiment run for direct RQ3 table aggregation. */
    private class Rq3RunSummary(
        val deviceModel: String,
        val runId: Int,
        val shotCount: Int,
        val analyzedShotCount: Int,
        val startingOverheatLevel: Int?,
        val sizeBucket: String?,
        val isLowMemory: Boolean?,
        val bokehDecisionCoveragePercent: Double?,
        val bokehAdmitPercent: Double?,
        val bokehExecutionPercent: Double?,
        val filterDecisionCoveragePercent: Double?,
        val filterAdmitPercent: Double?,
        val filterExecutionPercent: Double?,
        val transitionCount: Int,
        val recordedPacingDecisionCount: Int,
        val pacingObservationCount: Int,
        val pacingDecisionCoveragePercent: Double?,
        val pacingObservationCoveragePercent: Double?,
        val pacedTransitionCount: Int,
        val pacedPercent: Double?,
        val totalDelayMs: Long?,
        val positiveDelayP50Ms: Double?,
        val positiveDelayP95Ms: Double?,
        val realTraceCoveragePercent: Double?,
        val realBacklogMeanMs: Double?,
        val realBacklogP50Ms: Double?,
        val realBacklogP95Ms: Double?,
        val highBacklogPercent: Double?,
        val realQueueDepthMean: Double?,
        val maxRealBacklogMs: Long?,
        val maxRealBacklogAtReleaseMs: Long?,
        val maxRealQueueDepth: Int?,
        val maxRealQueueDepthAtRelease: Int?,
        val timeoutMarginSampleCount: Int,
        val timeoutMarginCoveragePercent: Double?,
        val timeoutMarginP5Ms: Double?,
        val shotToShotP95Ms: Double?,
        val burstSpanMs: Long?,
    )

    private class Rq3Export(
        val captures: List<Rq3CaptureRow>,
        val summaries: List<Rq3RunSummary>,
    )

    /** RQ1 values over one fixed shot prefix. Null means that prefix was not fully observed. */
    private class Rq1PrefixSummary(
        val multiAndSingleCompletedPercent: Double?,
        val multiFrameCompletedPercent: Double?,
        val pacingActivatedPercent: Double?,
        val positiveDelayP50Ms: Double?,
    )

    /** One factual run, reduced to the raw inputs and derived values used by the RQ1 tables. */
    private class Rq1RunSummary(
        val deviceModel: String,
        val runId: Int,
        val sourceShotCount: Int,
        val analyzedShotCount: Int,
        val isComplete30ShotRun: Boolean,
        val runStatus: String,
        val includedForRq1: Boolean,
        val resultProvenance: String,
        val startingOverheatLevel: Int?,
        val startingLevelSourceShotIndex: Int?,
        val sizeBucket: String?,
        val isLowMemory: Boolean?,
        val draftConfiguration: String,
        val firstTimeoutShot: Int?,
        val firstTimeoutDraftEndUptimeMs: Long?,
        val timeoutObservationShot: Int,
        val timeoutEventObserved: Boolean,
        val firstTimeoutOverheatLevel: Int?,
        val firstWatchdogShot: Int?,
        val timeoutCountThrough30: Int,
        val watchdogCountThrough30: Int,
        val firstAdmissionSkipShot: Int?,
        val admissionOnsetTraceComplete: Boolean,
        val firstPacingDelayShot: Int?,
        val pacingOnsetTraceComplete: Boolean,
        val admissionDecisionCoveragePercent: Double?,
        val pacingObservationCoveragePercent: Double?,
        val deadlineCoveragePercent: Double?,
        val slackSampleCount: Int,
        val slackP5Percent: Double?,
        val multiAndSingleCompletedAt5Percent: Double?,
        val multiAndSingleCompletedAt30Percent: Double?,
        val multiFrameCompletedAt5Percent: Double?,
        val multiFrameCompletedAt30Percent: Double?,
        val pacingActivatedAt5Percent: Double?,
        val pacingActivatedAt30Percent: Double?,
        val positiveDelayP50At5Ms: Double?,
        val positiveDelayP50At30Ms: Double?,
    )

    /** Workbook-local factual aggregate. Arm/condition labels still come from the experiment manifest. */
    private class Rq1ConditionSummary(
        val deviceModel: String,
        val startingOverheatLevel: Int?,
        val sizeBucket: String?,
        val isLowMemory: Boolean?,
        val draftConfiguration: String,
        val sourceRunCount: Int,
        val includedRunCount: Int,
        val complete30ShotRunCount: Int,
        val incompleteRunCount: Int,
        val timeoutRunCount: Int,
        val watchdogRunCount: Int,
        val firstTimeoutEarliestShot: Int?,
        val firstTimeoutKmMedianShot: Int?,
        val firstTimeoutOverheatLevelMin: Int?,
        val firstTimeoutOverheatLevelMax: Int?,
        val firstAdmissionSkipEarliestShot: Int?,
        val firstAdmissionSkipKmMedianShot: Int?,
        val admissionOnsetEligibleRunCount: Int,
        val firstPacingDelayEarliestShot: Int?,
        val firstPacingDelayKmMedianShot: Int?,
        val pacingOnsetEligibleRunCount: Int,
        val timeoutCountThrough30: Int,
        val slackEligibleRunCount: Int,
        val slackSampleCount: Int,
        val slackP5Percent: Double?,
        val multiAndSingleAt5EligibleRunCount: Int,
        val multiAndSingleCompletedAt5Percent: Double?,
        val multiAndSingleAt30EligibleRunCount: Int,
        val multiAndSingleCompletedAt30Percent: Double?,
        val multiFrameAt5EligibleRunCount: Int,
        val multiFrameCompletedAt5Percent: Double?,
        val multiFrameAt30EligibleRunCount: Int,
        val multiFrameCompletedAt30Percent: Double?,
        val pacingAt5EligibleRunCount: Int,
        val pacingActivatedAt5Percent: Double?,
        val pacingAt30EligibleRunCount: Int,
        val pacingActivatedAt30Percent: Double?,
        val positiveDelayAt5EventCount: Int,
        val positiveDelayP50At5Ms: Double?,
        val positiveDelayAt30EventCount: Int,
        val positiveDelayP50At30Ms: Double?,
    )

    private class Rq1Export(
        val runs: List<Rq1RunSummary>,
        val conditions: List<Rq1ConditionSummary>,
    )

    /**
     * Machine-readable validity boundary for deriving another policy arm from one recorded runtime trace. The rows do
     * not invent a counterfactual outcome: they say how far the source trace remains action-equivalent and where a
     * dedicated run becomes necessary.
     */
    private class ReplayScopeRow(
        val deviceModel: String,
        val runId: Int,
        val sourceTraceRequirement: String,
        val sourceTraceRequirementSatisfied: Boolean?,
        val targetPolicy: String,
        val analyzedShotCount: Int,
        val startingOverheatLevel: Int?,
        val sizeBucket: String?,
        val isLowMemory: Boolean?,
        val draftConfiguration: String,
        val recordedFirstAdmissionSkipShot: Int?,
        val recordedFirstAnyAdmissionSkipShot: Int?,
        val recordedFirstPacingDelayShot: Int?,
        val recordedFirstTimeoutShot: Int?,
        val actionEvidenceComplete: Boolean,
        val firstDivergenceShot: Int?,
        val firstDivergenceUptimeMs: Long?,
        val exactPrefixEndShot: Int,
        val actionEquivalentThrough30: Boolean,
        val outcomeProvenance: String,
        val exactTargetTimeoutShot: Int?,
        val timeoutCompletedBeforeDivergence: Boolean?,
        val timeoutOutcomeProvenance: String,
        val dedicatedRunRequiredForFirstTimeoutOutcome: Boolean,
        val dedicatedRunRequiredForFull30ShotTrace: Boolean,
        val certificationStatus: String,
        val targetOutcomeFactualOnRecordedTrace: Boolean,
        val publicationEligibleWithoutArmManifest: Boolean,
        val dedicatedFactualArmRequiredForPublication: Boolean,
        val interpretation: String,
    )

    private class EventObservation(
        val observationShot: Int,
        val eventObserved: Boolean,
    )

    private data class Rq1ConditionKey(
        val deviceModel: String,
        val startingOverheatLevel: Int?,
        val sizeBucket: String?,
        val isLowMemory: Boolean?,
        val draftConfiguration: String,
    )

    /** First classified node's size bucket (MP12/MP24/...) - the draft's working resolution. */
    private fun draftSizeBucketOf(cap: CaptureRow): String? =
        cap.nodeRows.firstOrNull()?.node?.workloadKey
            ?.let { key -> Regex("sizeBucket=([A-Za-z0-9]+)").find(key)?.groupValues?.get(1) }

    /** Per capture: in-flight count, freshest completed wall, and the observed maxima as of its pacing decision. */
    private fun computeWallBaseDiagnostics(group: List<CaptureRow>, member: CaptureRow): WallBaseDiagnostics {
        val decisionMs = member.pacingReplay?.before?.decisionUptimeMs ?: member.draftStartUptimeMs
            ?: return WallBaseDiagnostics(null, null, null, null)
        val memberSize = draftSizeBucketOf(member)
        // The pacer's maxima reset when the pipeline drains (a new pacer session), so scope both to the same pacer
        // session to reconstruct what it actually held. In-flight/freshest are physical pipeline facts, so they stay
        // session-agnostic.
        val memberPacerSession = member.metrics.draftSequenceMetrics?.pacerSessionId
        var inFlight = 0
        var freshestEndMs = Long.MIN_VALUE
        var freshestWallMs: Long? = null
        var sizeScopedMaxMs: Long? = null
        var observedMaxMs: Long? = null
        for (other in group) {
            if (other === member) {
                continue
            }
            val startMs = other.draftStartUptimeMs ?: continue
            val endMs = other.draftEndUptimeMs ?: continue
            if (startMs <= decisionMs && endMs > decisionMs) {
                inFlight++
            }
            if (endMs <= decisionMs && endMs > freshestEndMs) {
                freshestEndMs = endMs
                freshestWallMs = other.draftSequenceDurationMs
            }
            val samePacerSession = other.metrics.draftSequenceMetrics?.pacerSessionId == memberPacerSession
            if (endMs > decisionMs || !samePacerSession) {
                continue
            }
            val wallMs = other.draftSequenceDurationMs ?: continue
            if (observedMaxMs == null || wallMs > observedMaxMs) {
                observedMaxMs = wallMs
            }
            val sameSize = memberSize != null && draftSizeBucketOf(other) == memberSize
            if (sameSize && (sizeScopedMaxMs == null || wallMs > sizeScopedMaxMs)) {
                sizeScopedMaxMs = wallMs
            }
        }
        return WallBaseDiagnostics(inFlight, freshestWallMs, sizeScopedMaxMs, observedMaxMs)
    }

    /**
     * Reconstructs the real queue state at [snapshotUptimeMs] from earlier captures in the same experiment run.
     * Returning null-valued diagnostics on an incomplete prior Draft timeline prevents a censored trace from being
     * mistaken for zero backlog.
     */
    private fun computeRq3QueueState(
        earlierCaptures: List<CaptureRow>,
        snapshotUptimeMs: Long?,
    ): Rq3QueueState {
        if (snapshotUptimeMs == null) {
            return Rq3QueueState(false, null, null, null)
        }

        val earlierDrafts = earlierCaptures.filter { capture -> capture.metrics.draftSequenceMetrics != null }
        val traceComplete = earlierDrafts.all { capture ->
            capture.draftStartUptimeMs != null && capture.draftEndUptimeMs != null
        }
        if (!traceComplete) {
            return Rq3QueueState(false, null, null, null)
        }

        val outstandingDrafts = earlierDrafts.filter { capture ->
            capture.draftEndUptimeMs?.let { endMs -> endMs > snapshotUptimeMs } == true
        }
        val drainEndUptimeMs = outstandingDrafts.mapNotNull { capture -> capture.draftEndUptimeMs }.maxOrNull()
        val realBacklogMs = drainEndUptimeMs?.minus(snapshotUptimeMs)?.coerceAtLeast(0L) ?: 0L
        val realQueueDepth = outstandingDrafts.count { capture ->
            capture.draftStartUptimeMs?.let { startMs -> startMs > snapshotUptimeMs } == true
        }
        return Rq3QueueState(
            traceComplete = true,
            realBacklogMs = realBacklogMs,
            realQueueDepth = realQueueDepth,
            realOutstandingDraftCount = outstandingDrafts.size,
        )
    }

    /**
     * Builds the RQ3 rows without reusing pacer-session grouping: a successful pacer may drain the pipeline during
     * one 30-shot trial, while an experiment run remains continuous until ppSequenceId resets.
     */
    private fun buildRq3Export(captures: List<CaptureRow>): Rq3Export {
        val captureRows = mutableListOf<Rq3CaptureRow>()
        val summaries = mutableListOf<Rq3RunSummary>()

        groupRq3ExperimentRuns(captures).forEachIndexed { runIndex, run ->
            val runId = runIndex + 1
            val startingNode = run.asSequence()
                .mapNotNull { capture -> capture.nodeRows.firstOrNull()?.node }
                .firstOrNull()
            val startingOverheatLevel = startingNode?.preExecutionMetrics?.thermalSnapshot?.overheatLevel
            val sizeBucket = run.asSequence().mapNotNull(::draftSizeBucketOf).firstOrNull()
            val isLowMemory = startingNode?.preExecutionMetrics?.memorySnapshot?.isLowMemory
            var cumulativeTransitionDelayMs = 0L
            var cumulativeDelayTraceComplete = true
            val runRows = mutableListOf<Rq3CaptureRow>()
            // A current-schema run has at least one pacer session/decision marker. Within such a run, a Draft row
            // with no consumed decision is a factual zero-delay observation, not a missing measurement. Keeping this
            // run-level guard avoids rewriting a wholly legacy workbook's historical nulls as current semantics.
            val hasCurrentPacingInstrumentation = run.any { capture ->
                capture.metrics.draftSequenceMetrics?.pacerSessionId != null ||
                    capture.metrics.draftSequenceMetrics?.captureAvailablePacing != null
            }

            run.forEachIndexed { shotOffset, capture ->
                val shotIndex = shotOffset + 1
                val draftMetrics = capture.metrics.draftSequenceMetrics
                val pacing = draftMetrics?.captureAvailablePacing
                val pacerSessionId = draftMetrics?.pacerSessionId
                val previousPacerSessionId = run.getOrNull(shotOffset - 1)
                    ?.metrics?.draftSequenceMetrics?.pacerSessionId
                val startsRecordedPacerSession = shotOffset == 0 ||
                    pacerSessionId != previousPacerSessionId
                val noGatingDecisionObservation = pacing == null && draftMetrics != null &&
                    hasCurrentPacingInstrumentation
                val sessionBootstrapObservation = noGatingDecisionObservation && startsRecordedPacerSession
                val pacingObservationAvailable = pacing != null || noGatingDecisionObservation
                val pacingObservationSource = when {
                    pacing != null -> PACING_OBSERVATION_RECORDED_DECISION
                    sessionBootstrapObservation -> PACING_OBSERVATION_SESSION_BOOTSTRAP_ZERO
                    noGatingDecisionObservation -> PACING_OBSERVATION_NO_GATING_DECISION_ZERO
                    else -> PACING_OBSERVATION_MISSING
                }
                val appliedDelayMs = pacing?.appliedDelayMs ?: 0L
                // captureAvailablePacing is written when this Draft starts after dequeueing its gating decision, so
                // the persisted delay belongs to the incoming transition of this shot. Shot 1 has no in-run incoming
                // transition even if the database grouping accidentally retained an older decision.
                val delayAppliesBeforeShotIndex = shotIndex.takeIf { shot -> shot > 1 }
                val transitionDelayMs = appliedDelayMs.takeIf {
                    delayAppliesBeforeShotIndex != null && pacingObservationAvailable
                }

                val decisionUptimeMs = pacing?.decisionUptimeMs
                val releaseUptimeMs = decisionUptimeMs?.plus(appliedDelayMs)
                val earlierCaptures = run.take(shotOffset)
                val knownEmptyExperimentStart = noGatingDecisionObservation && shotOffset == 0
                val beforeDelayState = if (knownEmptyExperimentStart) {
                    Rq3QueueState(true, 0L, 0, 0)
                } else {
                    computeRq3QueueState(earlierCaptures, decisionUptimeMs)
                }
                val atReleaseState = if (knownEmptyExperimentStart) {
                    Rq3QueueState(true, 0L, 0, 0)
                } else {
                    computeRq3QueueState(earlierCaptures, releaseUptimeMs)
                }

                if (transitionDelayMs != null) {
                    cumulativeTransitionDelayMs += transitionDelayMs
                } else if (delayAppliesBeforeShotIndex != null) {
                    cumulativeDelayTraceComplete = false
                }

                runRows.add(
                    Rq3CaptureRow(
                        deviceModel = Build.MODEL,
                        capture = capture,
                        runId = runId,
                        runShotIndex = shotIndex,
                        runShotCount = run.size,
                        startingOverheatLevel = startingOverheatLevel,
                        sizeBucket = sizeBucket,
                        isLowMemory = isLowMemory,
                        pacingDecisionRecorded = pacing != null,
                        pacingObservationAvailable = pacingObservationAvailable,
                        pacingObservationSource = pacingObservationSource,
                        delayAppliesBeforeShotIndex = delayAppliesBeforeShotIndex,
                        appliedDelayMs = appliedDelayMs,
                        transitionDelayMs = transitionDelayMs,
                        pacedTransition = transitionDelayMs?.let { delayMs -> delayMs > 0L },
                        cumulativeDelayTraceComplete = cumulativeDelayTraceComplete,
                        cumulativeTransitionDelayMs = cumulativeTransitionDelayMs.takeIf {
                            cumulativeDelayTraceComplete
                        },
                        releaseUptimeMs = releaseUptimeMs,
                        beforeDelayState = beforeDelayState,
                        atReleaseState = atReleaseState,
                    )
                )
            }

            val analyzedRows = runRows.take(RQ_TARGET_SHOT_COUNT)
            val eligibleTransitionRows = analyzedRows.filter { row ->
                row.delayAppliesBeforeShotIndex?.let { shot -> shot <= RQ_TARGET_SHOT_COUNT } == true
            }
            val transitionRows = eligibleTransitionRows.filter { row -> row.transitionDelayMs != null }
            val transitionTraceComplete = transitionRows.size == eligibleTransitionRows.size
            val delayMagnitudeTraceAvailable = transitionTraceComplete && eligibleTransitionRows.isNotEmpty()
            val positiveDelays = transitionRows.mapNotNull { row ->
                row.transitionDelayMs?.takeIf { delayMs -> delayMs > 0L }
            }
            val pacedTransitionCount = positiveDelays.size
            val recordedPacingDecisionCount = eligibleTransitionRows.count { row -> row.pacingDecisionRecorded }
            val pacingObservationCount = eligibleTransitionRows.count { row -> row.pacingObservationAvailable }
            val realTraceRows = analyzedRows.count { row -> row.beforeDelayState.realBacklogMs != null }
            val beforeDelayTraceComplete = analyzedRows.all { row -> row.beforeDelayState.traceComplete }
            val atReleaseTraceComplete = analyzedRows.all { row -> row.atReleaseState.traceComplete }
            val realBacklogs = analyzedRows.mapNotNull { row -> row.beforeDelayState.realBacklogMs }
            val realQueueDepths = analyzedRows.mapNotNull { row -> row.beforeDelayState.realQueueDepth }
            val bokehDecisionRows = analyzedRows.mapNotNull { row -> row.capture.bokehDecisionRow }
            val filterDecisionRows = analyzedRows.mapNotNull { row -> row.capture.filterDecisionRow }
            val timeoutMargins = analyzedRows.mapNotNull { row -> row.capture.timeoutMarginMs }
            val timeoutMarginTraceComplete = analyzedRows.isNotEmpty() &&
                timeoutMargins.size == analyzedRows.size
            val shotToShotTimes = analyzedRows.drop(1).mapNotNull { row -> row.capture.metrics.shotToShotTimeMs }
            val completeShotToShotTrace = analyzedRows.isNotEmpty() &&
                shotToShotTimes.size == analyzedRows.size - 1
            summaries.add(
                Rq3RunSummary(
                    deviceModel = Build.MODEL,
                    runId = runId,
                    shotCount = run.size,
                    analyzedShotCount = analyzedRows.size,
                    startingOverheatLevel = startingOverheatLevel,
                    sizeBucket = sizeBucket,
                    isLowMemory = isLowMemory,
                    bokehDecisionCoveragePercent = percent(
                        bokehDecisionRows.size,
                        analyzedRows.size,
                    ),
                    bokehAdmitPercent = percent(
                        bokehDecisionRows.count { row -> row.wasAdmitted == true },
                        bokehDecisionRows.size,
                    ),
                    bokehExecutionPercent = percent(
                        analyzedRows.count { row -> row.capture.bokehDecisionRow?.wasExecuted == true },
                        analyzedRows.size,
                    ),
                    filterDecisionCoveragePercent = percent(
                        filterDecisionRows.size,
                        analyzedRows.size,
                    ),
                    filterAdmitPercent = percent(
                        filterDecisionRows.count { row -> row.wasAdmitted == true },
                        filterDecisionRows.size,
                    ),
                    filterExecutionPercent = percent(
                        analyzedRows.count { row -> row.capture.filterDecisionRow?.wasExecuted == true },
                        analyzedRows.size,
                    ),
                    transitionCount = eligibleTransitionRows.size,
                    recordedPacingDecisionCount = recordedPacingDecisionCount,
                    pacingObservationCount = pacingObservationCount,
                    pacingDecisionCoveragePercent = percent(
                        recordedPacingDecisionCount,
                        eligibleTransitionRows.size,
                    ),
                    pacingObservationCoveragePercent = percent(
                        pacingObservationCount,
                        eligibleTransitionRows.size,
                    ),
                    pacedTransitionCount = pacedTransitionCount,
                    pacedPercent = if (transitionTraceComplete) {
                        percent(pacedTransitionCount, eligibleTransitionRows.size)
                    } else {
                        null
                    },
                    totalDelayMs = transitionRows.takeIf { transitionTraceComplete }
                        ?.sumOf { row -> row.transitionDelayMs ?: 0L },
                    positiveDelayP50Ms = conditionalPositiveDelayPercentile(
                        positiveDelays,
                        delayMagnitudeTraceAvailable,
                        0.50,
                    ),
                    positiveDelayP95Ms = conditionalPositiveDelayPercentile(
                        positiveDelays,
                        delayMagnitudeTraceAvailable,
                        0.95,
                    ),
                    realTraceCoveragePercent = percent(realTraceRows, analyzedRows.size),
                    realBacklogMeanMs = realBacklogs.takeIf { beforeDelayTraceComplete && it.isNotEmpty() }
                        ?.average(),
                    realBacklogP50Ms = realBacklogs.takeIf { beforeDelayTraceComplete }
                        ?.let { values -> inclusivePercentile(values, 0.50) },
                    realBacklogP95Ms = realBacklogs.takeIf { beforeDelayTraceComplete }
                        ?.let { values -> inclusivePercentile(values, 0.95) },
                    highBacklogPercent = realBacklogs.takeIf { beforeDelayTraceComplete && it.isNotEmpty() }
                        ?.let { values ->
                            percent(
                                values.count { backlogMs ->
                                    backlogMs > RQ3_HIGH_BACKLOG_FRACTION * MakerFeature.CAPTURE_TIMEOUT_MS
                                },
                                values.size,
                            )
                        },
                    realQueueDepthMean = realQueueDepths.takeIf {
                        beforeDelayTraceComplete && it.isNotEmpty()
                    }?.average(),
                    maxRealBacklogMs = if (beforeDelayTraceComplete) {
                        realBacklogs.maxOrNull()
                    } else {
                        null
                    },
                    maxRealBacklogAtReleaseMs = if (atReleaseTraceComplete) {
                        analyzedRows.mapNotNull { row -> row.atReleaseState.realBacklogMs }.maxOrNull()
                    } else {
                        null
                    },
                    maxRealQueueDepth = if (beforeDelayTraceComplete) {
                        realQueueDepths.maxOrNull()
                    } else {
                        null
                    },
                    maxRealQueueDepthAtRelease = if (atReleaseTraceComplete) {
                        analyzedRows.mapNotNull { row -> row.atReleaseState.realQueueDepth }.maxOrNull()
                    } else {
                        null
                    },
                    timeoutMarginSampleCount = timeoutMargins.size,
                    timeoutMarginCoveragePercent = percent(timeoutMargins.size, analyzedRows.size),
                    timeoutMarginP5Ms = timeoutMargins.takeIf { timeoutMarginTraceComplete }
                        ?.let { values -> inclusivePercentile(values, 0.05) },
                    shotToShotP95Ms = shotToShotTimes.takeIf { completeShotToShotTrace }
                        ?.let { values -> inclusivePercentile(values, 0.95) },
                    burstSpanMs = shotToShotTimes.takeIf { completeShotToShotTrace }?.sum(),
                )
            )
            captureRows.addAll(runRows)
        }

        return Rq3Export(captureRows, summaries)
    }

    private fun buildRq1Export(captures: List<Rq3CaptureRow>): Rq1Export {
        val runRows = captures.groupBy { row -> row.runId }
            .toSortedMap()
            .values
            .map { rows -> rows.sortedBy { row -> row.runShotIndex } }
        val runSummaries = runRows.map(::buildRq1RunSummary)
        val runEntries = runRows.zip(runSummaries)

        val conditionSummaries = runEntries.groupBy { (_, summary) ->
            Rq1ConditionKey(
                deviceModel = summary.deviceModel,
                startingOverheatLevel = summary.startingOverheatLevel,
                sizeBucket = summary.sizeBucket,
                isLowMemory = summary.isLowMemory,
                draftConfiguration = summary.draftConfiguration,
            )
        }.map { (key, entries) ->
            buildRq1ConditionSummary(key, entries)
        }.sortedWith(
            compareBy<Rq1ConditionSummary>(
                { summary -> summary.deviceModel },
                { summary -> summary.startingOverheatLevel ?: Int.MAX_VALUE },
                { summary -> summary.sizeBucket.orEmpty() },
                { summary -> summary.isLowMemory ?: false },
                { summary -> summary.draftConfiguration },
            )
        )

        return Rq1Export(runSummaries, conditionSummaries)
    }

    private fun buildReplayScopeRows(
        captures: List<Rq3CaptureRow>,
        runs: List<Rq1RunSummary>,
    ): List<ReplayScopeRow> {
        val capturesByRun = captures.groupBy { row -> row.runId }
        return runs.flatMap { run ->
            val runRows = capturesByRun[run.runId].orEmpty()
                .filter { row -> row.runShotIndex <= RQ_TARGET_SHOT_COUNT }
                .sortedBy { row -> row.runShotIndex }
            val firstAnyAdmissionSkipCandidate = runRows.firstOrNull { row ->
                row.capture.nodeRows.any { nodeRow ->
                    nodeRow.isAdmissionWorkload && nodeRow.wasAdmitted == false
                }
            }
            val admissionRowsBeforeAnySkip = firstAnyAdmissionSkipCandidate?.let { candidate ->
                runRows.takeWhile { row -> row.runShotIndex < candidate.runShotIndex }
            } ?: runRows
            // The candidate's explicit false proves the action on that row. Earlier rows still need every planned
            // admission decision so an unobserved Decoding/Watermark rejection cannot hide an earlier divergence.
            val anyAdmissionActionEvidenceComplete = admissionRowsBeforeAnySkip.all { row ->
                hasCompleteAdmissionActionEvidence(row.capture)
            }
            val firstAnyAdmissionSkipShot = firstAnyAdmissionSkipCandidate?.runShotIndex
            // A false proves the shot-level boundary, but timestamp certification is stricter. If another controlled
            // decision is missing on this same shot, the observed false might not be the earliest action in uptime.
            val admissionDivergenceUptimeMs = firstAnyAdmissionSkipCandidate?.capture
                ?.takeIf(::hasCompleteAdmissionActionEvidence)
                ?.let { capture ->
                    capture.nodeRows.firstOrNull { row ->
                        row.isAdmissionWorkload && row.wasAdmitted == false
                    }?.node?.startUptimeMs ?: capture.draftStartUptimeMs
                }
            val pacingDivergenceUptimeMs = run.firstPacingDelayShot?.let { onsetShot ->
                runRows.firstOrNull { row -> row.runShotIndex == onsetShot }
                    ?.capture?.metrics?.draftSequenceMetrics?.captureAvailablePacing?.decisionUptimeMs
            }
            val baselineDivergenceShot = listOfNotNull(
                firstAnyAdmissionSkipShot,
                run.firstPacingDelayShot,
            ).minOrNull()
            val baselineDivergenceUptimeMs = when {
                firstAnyAdmissionSkipShot == null -> pacingDivergenceUptimeMs
                run.firstPacingDelayShot == null -> admissionDivergenceUptimeMs
                firstAnyAdmissionSkipShot < run.firstPacingDelayShot -> admissionDivergenceUptimeMs
                run.firstPacingDelayShot < firstAnyAdmissionSkipShot -> pacingDivergenceUptimeMs
                admissionDivergenceUptimeMs == null || pacingDivergenceUptimeMs == null -> null
                else -> minOf(admissionDivergenceUptimeMs, pacingDivergenceUptimeMs)
            }

            listOf(
            replayScopeRow(
                run = run,
                targetPolicy = REPLAY_TARGET_RECORDED_RUNTIME,
                firstDivergenceShot = null,
                firstDivergenceUptimeMs = null,
                actionEvidenceComplete = true,
                factualRecordedTarget = true,
                recordedFirstAnyAdmissionSkipShot = firstAnyAdmissionSkipShot,
                interpretation = "The exported capture, timeout, admission, pacing, queue, and cost values are " +
                    "observed runtime facts.",
            ),
            replayScopeRow(
                run = run,
                targetPolicy = REPLAY_TARGET_BASELINE,
                firstDivergenceShot = baselineDivergenceShot,
                firstDivergenceUptimeMs = baselineDivergenceUptimeMs,
                actionEvidenceComplete = anyAdmissionActionEvidenceComplete && run.pacingOnsetTraceComplete,
                factualRecordedTarget = false,
                recordedFirstAnyAdmissionSkipShot = firstAnyAdmissionSkipShot,
                interpretation = "Exact only before the first recorded admission skip on any controlled workload " +
                    "or delayed arrival. Values after that boundary are trace-conditioned estimates, not a factual " +
                    "no-control run.",
            ),
            replayScopeRow(
                run = run,
                targetPolicy = REPLAY_TARGET_ADMISSION_ONLY,
                firstDivergenceShot = run.firstPacingDelayShot,
                firstDivergenceUptimeMs = pacingDivergenceUptimeMs,
                actionEvidenceComplete = run.pacingOnsetTraceComplete,
                factualRecordedTarget = false,
                recordedFirstAnyAdmissionSkipShot = firstAnyAdmissionSkipShot,
                interpretation = "Exact only before the first arrival delayed by pacing. Removing that delay changes " +
                    "later deadlines, queue state, thermal state, and predictor observations.",
            ),
            replayScopeRow(
                run = run,
                targetPolicy = REPLAY_TARGET_PACING_ONLY,
                firstDivergenceShot = firstAnyAdmissionSkipShot,
                firstDivergenceUptimeMs = admissionDivergenceUptimeMs,
                actionEvidenceComplete = anyAdmissionActionEvidenceComplete,
                factualRecordedTarget = false,
                recordedFirstAnyAdmissionSkipShot = firstAnyAdmissionSkipShot,
                interpretation = "Exact only before the first admission skip on Bokeh, Decoding, Filter, or Overlay " +
                    "Watermark. Restoring skipped work changes draft duration, backlog, thermal state, and predictor " +
                    "learning.",
            ),
            replayScopeRow(
                run = run,
                targetPolicy = REPLAY_TARGET_RQ3_NO_PACING,
                firstDivergenceShot = run.firstPacingDelayShot,
                firstDivergenceUptimeMs = pacingDivergenceUptimeMs,
                actionEvidenceComplete = run.pacingOnsetTraceComplete,
                factualRecordedTarget = false,
                recordedFirstAnyAdmissionSkipShot = firstAnyAdmissionSkipShot,
                interpretation = "RQ3 No-pacing is factual only from an actual Admission-only/NO_PACING run with " +
                    "the same admitted-workload protocol. A Full workbook is exact only before pacing diverges.",
            ),
            )
        }
    }

    private fun replayScopeRow(
        run: Rq1RunSummary,
        targetPolicy: String,
        firstDivergenceShot: Int?,
        firstDivergenceUptimeMs: Long?,
        actionEvidenceComplete: Boolean,
        factualRecordedTarget: Boolean,
        recordedFirstAnyAdmissionSkipShot: Int?,
        interpretation: String,
    ): ReplayScopeRow {
        val certifiedFirstDivergenceShot = firstDivergenceShot.takeIf {
            factualRecordedTarget || actionEvidenceComplete
        }
        val divergenceWithinObservedTrace = certifiedFirstDivergenceShot?.takeIf { shot ->
            shot <= run.analyzedShotCount
        }
        val exactPrefixEndShot = if (!factualRecordedTarget && !actionEvidenceComplete) {
            0
        } else {
            divergenceWithinObservedTrace
                ?.minus(1)
                ?.coerceAtLeast(0)
                ?: run.analyzedShotCount
        }
        val actionEquivalentThrough30 = actionEvidenceComplete && run.isComplete30ShotRun &&
            (certifiedFirstDivergenceShot == null || certifiedFirstDivergenceShot > RQ_TARGET_SHOT_COUNT)
        val timeoutCompletedBeforeDivergence = if (
            run.firstTimeoutShot != null && divergenceWithinObservedTrace != null
        ) {
            val timeoutEndUptimeMs = run.firstTimeoutDraftEndUptimeMs
            if (timeoutEndUptimeMs != null && firstDivergenceUptimeMs != null) {
                timeoutEndUptimeMs < firstDivergenceUptimeMs
            } else {
                null
            }
        } else {
            null
        }
        val exactTargetTimeoutShot = run.firstTimeoutShot?.takeIf {
            factualRecordedTarget || actionEvidenceComplete &&
                (divergenceWithinObservedTrace == null || timeoutCompletedBeforeDivergence == true)
        }
        val outcomeProvenance = when {
            factualRecordedTarget -> RESULT_PROVENANCE_FACTUAL
            !actionEvidenceComplete -> RESULT_PROVENANCE_INSUFFICIENT_ACTION_COVERAGE
            actionEquivalentThrough30 -> RESULT_PROVENANCE_EQUIVALENT_THROUGH_30
            divergenceWithinObservedTrace != null -> RESULT_PROVENANCE_SOURCE_TRACE_AFTER_DIVERGENCE
            else -> RESULT_PROVENANCE_EXACT_OBSERVED_PREFIX
        }
        val timeoutOutcomeProvenance = when {
            factualRecordedTarget -> TIMEOUT_PROVENANCE_FACTUAL_RECORDED_TRACE
            exactTargetTimeoutShot != null -> TIMEOUT_PROVENANCE_EXACT_ACTION_EQUIVALENT_PREFIX
            actionEquivalentThrough30 -> TIMEOUT_PROVENANCE_EXACT_NO_TIMEOUT_THROUGH_30
            else -> TIMEOUT_PROVENANCE_NOT_IDENTIFIABLE
        }

        return ReplayScopeRow(
            deviceModel = run.deviceModel,
            runId = run.runId,
            sourceTraceRequirement = if (factualRecordedTarget) {
                REPLAY_RECORDED_SOURCE_TRACE_REQUIREMENT
            } else {
                REPLAY_SOURCE_TRACE_REQUIREMENT
            },
            sourceTraceRequirementSatisfied = if (factualRecordedTarget) true else null,
            targetPolicy = targetPolicy,
            analyzedShotCount = run.analyzedShotCount,
            startingOverheatLevel = run.startingOverheatLevel,
            sizeBucket = run.sizeBucket,
            isLowMemory = run.isLowMemory,
            draftConfiguration = run.draftConfiguration,
            recordedFirstAdmissionSkipShot = run.firstAdmissionSkipShot,
            recordedFirstAnyAdmissionSkipShot = recordedFirstAnyAdmissionSkipShot,
            recordedFirstPacingDelayShot = run.firstPacingDelayShot,
            recordedFirstTimeoutShot = run.firstTimeoutShot,
            actionEvidenceComplete = actionEvidenceComplete,
            firstDivergenceShot = certifiedFirstDivergenceShot,
            firstDivergenceUptimeMs = firstDivergenceUptimeMs.takeIf {
                factualRecordedTarget || actionEvidenceComplete
            },
            exactPrefixEndShot = exactPrefixEndShot,
            actionEquivalentThrough30 = actionEquivalentThrough30,
            outcomeProvenance = outcomeProvenance,
            exactTargetTimeoutShot = exactTargetTimeoutShot,
            timeoutCompletedBeforeDivergence = timeoutCompletedBeforeDivergence,
            timeoutOutcomeProvenance = timeoutOutcomeProvenance,
            dedicatedRunRequiredForFirstTimeoutOutcome = !factualRecordedTarget &&
                !actionEquivalentThrough30 && exactTargetTimeoutShot == null,
            dedicatedRunRequiredForFull30ShotTrace = !factualRecordedTarget && !actionEquivalentThrough30,
            certificationStatus = if (factualRecordedTarget) {
                REPLAY_CERTIFICATION_FACTUAL_RECORDED_TARGET
            } else {
                REPLAY_CERTIFICATION_CONDITIONAL_ON_FULL_SOURCE
            },
            targetOutcomeFactualOnRecordedTrace = factualRecordedTarget,
            publicationEligibleWithoutArmManifest = false,
            dedicatedFactualArmRequiredForPublication = !factualRecordedTarget,
            interpretation = interpretation,
        )
    }

    private fun buildRq1RunSummary(rows: List<Rq3CaptureRow>): Rq1RunSummary {
        val analyzedRows = rows.take(RQ_TARGET_SHOT_COUNT)
        val sourceShotCount = rows.size
        val isComplete30ShotRun = sourceShotCount >= RQ_TARGET_SHOT_COUNT
        val draftConfiguration = draftConfigurationOf(analyzedRows)
        val firstLevelRow = analyzedRows.firstOrNull { row -> row.capture.nodeRows.isNotEmpty() }
        val firstTimeoutRow = analyzedRows.firstOrNull { row -> row.capture.hasTimeoutFailure }
        val firstWatchdogRow = analyzedRows.firstOrNull { row -> row.capture.hasWatchdogFailure }
        val firstAdmissionSkipCandidate = analyzedRows.firstOrNull { row ->
            isAdmissionSkip(row.capture, draftConfiguration)
        }
        val admissionRowsBeforeOnset = firstAdmissionSkipCandidate?.let { candidate ->
            analyzedRows.take((candidate.runShotIndex - 1).coerceAtLeast(0))
        } ?: analyzedRows
        // An explicit false already proves the onset on the candidate row. Requiring a later optional decision from
        // that same row would discard a valid Bokeh skip merely because the demoted downstream Filter was not profiled.
        val admissionOnsetTraceComplete = draftConfiguration != DRAFT_CONFIGURATION_UNKNOWN &&
            admissionRowsBeforeOnset.all { row ->
                hasExpectedAdmissionDecision(row.capture, draftConfiguration)
            }
        val firstAdmissionSkipRow = firstAdmissionSkipCandidate.takeIf { admissionOnsetTraceComplete }
        val eligibleTransitions = analyzedRows.filter { row ->
            row.delayAppliesBeforeShotIndex?.let { shot -> shot <= RQ_TARGET_SHOT_COUNT } == true
        }
        val firstPacingCandidate = eligibleTransitions.firstOrNull { row ->
            row.transitionDelayMs?.let { delayMs -> delayMs > 0L } == true
        }
        val pacingTransitionsThroughOnset = firstPacingCandidate?.let { candidate ->
            eligibleTransitions.takeWhile { row -> row.runShotIndex <= candidate.runShotIndex }
        } ?: eligibleTransitions
        val pacingOnsetTraceComplete = pacingTransitionsThroughOnset.all { row ->
            row.pacingObservationAvailable && row.transitionDelayMs != null
        }
        val firstPacingRow = firstPacingCandidate.takeIf { pacingOnsetTraceComplete }
        val deadlineRows = analyzedRows.filter { row ->
            row.capture.metrics.timeoutTimestampMs != null && row.capture.draftEndUptimeMs != null
        }
        val slackPercentages = deadlineRows.mapNotNull { row -> timeoutSlackPercent(row.capture) }
        val deadlineTraceComplete = analyzedRows.isNotEmpty() && deadlineRows.size == analyzedRows.size
        val prefix5 = summarizeRq1Prefix(analyzedRows, RQ1_SHORT_BURST_SHOT_COUNT, draftConfiguration)
        val prefix30 = summarizeRq1Prefix(analyzedRows, RQ_TARGET_SHOT_COUNT, draftConfiguration)
        val expectedAdmissionDecisionsPerShot = when (draftConfiguration) {
            DRAFT_CONFIGURATION_M_PLUS_S -> 2
            DRAFT_CONFIGURATION_M, DRAFT_CONFIGURATION_S -> 1
            else -> 0
        }
        val recordedAdmissionDecisionCount = analyzedRows.sumOf { row ->
            when (draftConfiguration) {
                DRAFT_CONFIGURATION_M_PLUS_S -> listOf(
                    row.capture.bokehDecisionRow,
                    row.capture.filterDecisionRow,
                ).count { decision -> decision?.wasAdmitted != null }
                DRAFT_CONFIGURATION_M -> if (row.capture.bokehDecisionRow?.wasAdmitted != null) 1 else 0
                DRAFT_CONFIGURATION_S -> if (row.capture.filterDecisionRow?.wasAdmitted != null) 1 else 0
                else -> 0
            }
        }
        val expectedAdmissionDecisionCount = expectedAdmissionDecisionsPerShot * analyzedRows.size
        val includedForRq1 = isComplete30ShotRun || firstTimeoutRow != null
        val runStatus = when {
            firstTimeoutRow != null -> RQ1_RUN_STATUS_CAPTURE_TIMEOUT
            firstWatchdogRow != null -> RQ1_RUN_STATUS_WATCHDOG_ONLY
            isComplete30ShotRun -> RQ1_RUN_STATUS_COMPLETE
            else -> RQ1_RUN_STATUS_INCOMPLETE
        }

        return Rq1RunSummary(
            deviceModel = Build.MODEL,
            runId = rows.first().runId,
            sourceShotCount = sourceShotCount,
            analyzedShotCount = analyzedRows.size,
            isComplete30ShotRun = isComplete30ShotRun,
            runStatus = runStatus,
            includedForRq1 = includedForRq1,
            resultProvenance = RESULT_PROVENANCE_FACTUAL,
            startingOverheatLevel = firstLevelRow?.capture?.nodeRows?.firstOrNull()?.node
                ?.preExecutionMetrics?.thermalSnapshot?.overheatLevel,
            startingLevelSourceShotIndex = firstLevelRow?.runShotIndex,
            sizeBucket = analyzedRows.asSequence().mapNotNull { row -> draftSizeBucketOf(row.capture) }.firstOrNull(),
            isLowMemory = firstLevelRow?.capture?.nodeRows?.firstOrNull()?.node
                ?.preExecutionMetrics?.memorySnapshot?.isLowMemory,
            draftConfiguration = draftConfiguration,
            firstTimeoutShot = firstTimeoutRow?.runShotIndex,
            firstTimeoutDraftEndUptimeMs = firstTimeoutRow?.capture?.draftEndUptimeMs,
            timeoutObservationShot = firstTimeoutRow?.runShotIndex ?: analyzedRows.size,
            timeoutEventObserved = firstTimeoutRow != null,
            firstTimeoutOverheatLevel = firstTimeoutRow?.capture?.nodeRows?.firstOrNull()?.node
                ?.preExecutionMetrics?.thermalSnapshot?.overheatLevel,
            firstWatchdogShot = firstWatchdogRow?.runShotIndex,
            timeoutCountThrough30 = analyzedRows.count { row -> row.capture.hasTimeoutFailure },
            watchdogCountThrough30 = analyzedRows.count { row -> row.capture.hasWatchdogFailure },
            firstAdmissionSkipShot = firstAdmissionSkipRow?.runShotIndex,
            admissionOnsetTraceComplete = admissionOnsetTraceComplete,
            firstPacingDelayShot = firstPacingRow?.delayAppliesBeforeShotIndex,
            pacingOnsetTraceComplete = pacingOnsetTraceComplete,
            admissionDecisionCoveragePercent = percent(
                recordedAdmissionDecisionCount,
                expectedAdmissionDecisionCount,
            ),
            pacingObservationCoveragePercent = percent(
                eligibleTransitions.count { row -> row.pacingObservationAvailable },
                eligibleTransitions.size,
            ),
            deadlineCoveragePercent = percent(deadlineRows.size, analyzedRows.size),
            slackSampleCount = slackPercentages.size,
            slackP5Percent = slackPercentages.takeIf { deadlineTraceComplete }
                ?.let { values -> inclusivePercentile(values, 0.05) },
            multiAndSingleCompletedAt5Percent = prefix5.multiAndSingleCompletedPercent,
            multiAndSingleCompletedAt30Percent = prefix30.multiAndSingleCompletedPercent,
            multiFrameCompletedAt5Percent = prefix5.multiFrameCompletedPercent,
            multiFrameCompletedAt30Percent = prefix30.multiFrameCompletedPercent,
            pacingActivatedAt5Percent = prefix5.pacingActivatedPercent,
            pacingActivatedAt30Percent = prefix30.pacingActivatedPercent,
            positiveDelayP50At5Ms = prefix5.positiveDelayP50Ms,
            positiveDelayP50At30Ms = prefix30.positiveDelayP50Ms,
        )
    }

    private fun summarizeRq1Prefix(
        rows: List<Rq3CaptureRow>,
        prefixShotCount: Int,
        draftConfiguration: String,
    ): Rq1PrefixSummary {
        if (rows.size < prefixShotCount) {
            return Rq1PrefixSummary(null, null, null, null)
        }

        val prefixRows = rows.take(prefixShotCount)
        val hasCompleteMDecisions = draftConfiguration in setOf(
            DRAFT_CONFIGURATION_M_PLUS_S,
            DRAFT_CONFIGURATION_M,
        ) && prefixRows.all { row -> row.capture.bokehDecisionRow != null }
        val hasCompleteMsDecisions = draftConfiguration == DRAFT_CONFIGURATION_M_PLUS_S &&
            prefixRows.all { row ->
                row.capture.bokehDecisionRow != null && row.capture.filterDecisionRow != null
            }
        val transitions = prefixRows.filter { row ->
            row.delayAppliesBeforeShotIndex?.let { shot -> shot <= prefixShotCount } == true
        }
        val transitionTraceComplete = transitions.size == prefixShotCount - 1 &&
            transitions.all { row -> row.pacingObservationAvailable && row.transitionDelayMs != null }
        val positiveDelays = transitions.mapNotNull { row ->
            row.transitionDelayMs?.takeIf { delayMs -> delayMs > 0L }
        }

        return Rq1PrefixSummary(
            multiAndSingleCompletedPercent = if (hasCompleteMsDecisions) {
                percent(
                    prefixRows.count { row ->
                        row.capture.bokehDecisionRow?.wasExecuted == true &&
                            row.capture.filterDecisionRow?.wasExecuted == true
                    },
                    prefixRows.size,
                )
            } else {
                null
            },
            multiFrameCompletedPercent = if (hasCompleteMDecisions) {
                percent(
                    prefixRows.count { row -> row.capture.bokehDecisionRow?.wasExecuted == true },
                    prefixRows.size,
                )
            } else {
                null
            },
            pacingActivatedPercent = if (transitionTraceComplete) {
                percent(positiveDelays.size, transitions.size)
            } else {
                null
            },
            positiveDelayP50Ms = if (transitionTraceComplete) {
                inclusivePercentile(positiveDelays, 0.50)
            } else {
                null
            },
        )
    }

    private fun buildRq1ConditionSummary(
        key: Rq1ConditionKey,
        entries: List<Pair<List<Rq3CaptureRow>, Rq1RunSummary>>,
    ): Rq1ConditionSummary {
        val includedEntries = entries.filter { (_, summary) -> summary.includedForRq1 }
        val includedRuns = includedEntries.map { (_, summary) -> summary }
        val timeoutLevels = includedRuns.mapNotNull { summary -> summary.firstTimeoutOverheatLevel }
        val slackEligibleEntries = includedEntries.filter { (_, summary) -> summary.slackP5Percent != null }
        val slackPercentages = slackEligibleEntries.flatMap { (rows, _) ->
            rows.take(RQ_TARGET_SHOT_COUNT).mapNotNull { row -> timeoutSlackPercent(row.capture) }
        }

        fun eventObservations(
            eligible: (Rq1RunSummary) -> Boolean = { true },
            selector: (Rq1RunSummary) -> Int?,
        ): List<EventObservation> =
            includedRuns.filter(eligible).map { summary ->
                val eventShot = selector(summary)
                EventObservation(
                    observationShot = eventShot ?: summary.analyzedShotCount,
                    eventObserved = eventShot != null,
                )
            }

        fun pooledPositiveDelays(prefixShotCount: Int): List<Long> = includedEntries.flatMap { (rows, summary) ->
            val pacingTraceComplete = when (prefixShotCount) {
                RQ1_SHORT_BURST_SHOT_COUNT -> summary.pacingActivatedAt5Percent != null
                RQ_TARGET_SHOT_COUNT -> summary.pacingActivatedAt30Percent != null
                else -> false
            }
            if (!pacingTraceComplete || rows.size < prefixShotCount) {
                emptyList()
            } else {
                rows.take(prefixShotCount).mapNotNull { row ->
                    row.transitionDelayMs?.takeIf { delayMs ->
                        delayMs > 0L &&
                            row.delayAppliesBeforeShotIndex?.let { shot -> shot <= prefixShotCount } == true
                    }
                }
            }
        }
        val positiveDelaysAt5 = pooledPositiveDelays(RQ1_SHORT_BURST_SHOT_COUNT)
        val positiveDelaysAt30 = pooledPositiveDelays(RQ_TARGET_SHOT_COUNT)

        return Rq1ConditionSummary(
            deviceModel = key.deviceModel,
            startingOverheatLevel = key.startingOverheatLevel,
            sizeBucket = key.sizeBucket,
            isLowMemory = key.isLowMemory,
            draftConfiguration = key.draftConfiguration,
            sourceRunCount = entries.size,
            includedRunCount = includedRuns.size,
            complete30ShotRunCount = entries.count { (_, summary) -> summary.isComplete30ShotRun },
            incompleteRunCount = entries.count { (_, summary) -> !summary.isComplete30ShotRun },
            timeoutRunCount = entries.count { (_, summary) -> summary.timeoutEventObserved },
            watchdogRunCount = entries.count { (_, summary) -> summary.watchdogCountThrough30 > 0 },
            firstTimeoutEarliestShot = includedRuns.mapNotNull { summary -> summary.firstTimeoutShot }.minOrNull(),
            firstTimeoutKmMedianShot = kaplanMeierMedian(eventObservations { summary -> summary.firstTimeoutShot }),
            firstTimeoutOverheatLevelMin = timeoutLevels.minOrNull(),
            firstTimeoutOverheatLevelMax = timeoutLevels.maxOrNull(),
            firstAdmissionSkipEarliestShot =
                includedRuns.filter { summary -> summary.admissionOnsetTraceComplete }
                    .mapNotNull { summary -> summary.firstAdmissionSkipShot }.minOrNull(),
            firstAdmissionSkipKmMedianShot =
                kaplanMeierMedian(
                    eventObservations(
                        eligible = { summary -> summary.admissionOnsetTraceComplete },
                        selector = { summary -> summary.firstAdmissionSkipShot },
                    )
                ),
            admissionOnsetEligibleRunCount = includedRuns.count { summary ->
                summary.admissionOnsetTraceComplete
            },
            firstPacingDelayEarliestShot =
                includedRuns.filter { summary -> summary.pacingOnsetTraceComplete }
                    .mapNotNull { summary -> summary.firstPacingDelayShot }.minOrNull(),
            firstPacingDelayKmMedianShot =
                kaplanMeierMedian(
                    eventObservations(
                        eligible = { summary -> summary.pacingOnsetTraceComplete },
                        selector = { summary -> summary.firstPacingDelayShot },
                    )
                ),
            pacingOnsetEligibleRunCount = includedRuns.count { summary ->
                summary.pacingOnsetTraceComplete
            },
            timeoutCountThrough30 = includedRuns.sumOf { summary -> summary.timeoutCountThrough30 },
            slackEligibleRunCount = slackEligibleEntries.size,
            slackSampleCount = slackPercentages.size,
            slackP5Percent = inclusivePercentile(slackPercentages, 0.05),
            multiAndSingleAt5EligibleRunCount = includedRuns.count { summary ->
                summary.multiAndSingleCompletedAt5Percent != null
            },
            multiAndSingleCompletedAt5Percent =
                averageOrNull(includedRuns.mapNotNull { summary -> summary.multiAndSingleCompletedAt5Percent }),
            multiAndSingleAt30EligibleRunCount = includedRuns.count { summary ->
                summary.multiAndSingleCompletedAt30Percent != null
            },
            multiAndSingleCompletedAt30Percent =
                averageOrNull(includedRuns.mapNotNull { summary -> summary.multiAndSingleCompletedAt30Percent }),
            multiFrameAt5EligibleRunCount = includedRuns.count { summary ->
                summary.multiFrameCompletedAt5Percent != null
            },
            multiFrameCompletedAt5Percent =
                averageOrNull(includedRuns.mapNotNull { summary -> summary.multiFrameCompletedAt5Percent }),
            multiFrameAt30EligibleRunCount = includedRuns.count { summary ->
                summary.multiFrameCompletedAt30Percent != null
            },
            multiFrameCompletedAt30Percent =
                averageOrNull(includedRuns.mapNotNull { summary -> summary.multiFrameCompletedAt30Percent }),
            pacingAt5EligibleRunCount = includedRuns.count { summary ->
                summary.pacingActivatedAt5Percent != null
            },
            pacingActivatedAt5Percent =
                averageOrNull(includedRuns.mapNotNull { summary -> summary.pacingActivatedAt5Percent }),
            pacingAt30EligibleRunCount = includedRuns.count { summary ->
                summary.pacingActivatedAt30Percent != null
            },
            pacingActivatedAt30Percent =
                averageOrNull(includedRuns.mapNotNull { summary -> summary.pacingActivatedAt30Percent }),
            positiveDelayAt5EventCount = positiveDelaysAt5.size,
            positiveDelayP50At5Ms = inclusivePercentile(positiveDelaysAt5, 0.50),
            positiveDelayAt30EventCount = positiveDelaysAt30.size,
            positiveDelayP50At30Ms = inclusivePercentile(positiveDelaysAt30, 0.50),
        )
    }

    private fun draftConfigurationOf(rows: List<Rq3CaptureRow>): String {
        val workloadKeys = rows.flatMap { row ->
            row.capture.nodeRows.flatMap { nodeRow ->
                listOfNotNull(nodeRow.node.workloadKey, nodeRow.prediction?.workloadSequenceKey)
            }
        }
        val hasMultiFrame = workloadKeys.any { key -> key.contains(ADMIT_BOKEH_PREFIX) }
        val hasSingleFrame = workloadKeys.any { key -> key.contains(ADMIT_FILTER_PREFIX) }
        return when {
            hasMultiFrame && hasSingleFrame -> DRAFT_CONFIGURATION_M_PLUS_S
            hasMultiFrame -> DRAFT_CONFIGURATION_M
            hasSingleFrame -> DRAFT_CONFIGURATION_S
            else -> DRAFT_CONFIGURATION_UNKNOWN
        }
    }

    private fun isAdmissionSkip(capture: CaptureRow, draftConfiguration: String): Boolean {
        return when (draftConfiguration) {
            DRAFT_CONFIGURATION_M_PLUS_S ->
                capture.bokehDecisionRow?.wasAdmitted == false ||
                    capture.filterDecisionRow?.wasAdmitted == false
            DRAFT_CONFIGURATION_M -> capture.bokehDecisionRow?.wasAdmitted == false
            DRAFT_CONFIGURATION_S -> capture.filterDecisionRow?.wasAdmitted == false
            else -> false
        }
    }

    private fun hasExpectedAdmissionDecision(capture: CaptureRow, draftConfiguration: String): Boolean {
        return when (draftConfiguration) {
            DRAFT_CONFIGURATION_M_PLUS_S ->
                capture.bokehDecisionRow?.wasAdmitted != null &&
                    capture.filterDecisionRow?.wasAdmitted != null
            DRAFT_CONFIGURATION_M -> capture.bokehDecisionRow?.wasAdmitted != null
            DRAFT_CONFIGURATION_S -> capture.filterDecisionRow?.wasAdmitted != null
            else -> false
        }
    }

    /**
     * Replay-arm divergence is broader than the paper's RQ1 M/S onset: disabling admission restores every optional
     * workload controlled by [DraftSequenceAdmissionPolicy], including Decoding and Overlay Watermark. Before claiming
     * that no such action occurred, require the recorded decisions to match every controlled key in the planned suffix.
     */
    private fun hasCompleteAdmissionActionEvidence(capture: CaptureRow): Boolean {
        if (capture.nodeRows.any { row -> row.isAdmissionWorkload && row.prediction == null }) {
            return false
        }
        val plannedSequenceKey = capture.plannedWorkloadSequenceKey ?: return false
        val expectedWorkloadKeys = admissionWorkloadKeys(plannedSequenceKey)
        if (expectedWorkloadKeys.isEmpty()) {
            return false
        }
        val observedWorkloadKeys = capture.nodeRows.asSequence()
            .filter { row -> row.isAdmissionWorkload && row.prediction != null }
            .mapNotNull { row -> row.node.workloadKey?.trim() }
            .toList()
        return observedWorkloadKeys == expectedWorkloadKeys
    }

    private fun admissionWorkloadKeys(workloadSequenceKey: String): List<String> = workloadSequenceKey
        .split(">")
        .map { key -> key.trim() }
        .filter(::isAdmissionWorkloadKey)

    private fun isAdmissionWorkloadKey(workloadKey: String): Boolean =
        workloadKey.startsWith(ADMIT_BOKEH_PREFIX) ||
            workloadKey.startsWith(ADMIT_DECODING_PREFIX) ||
            workloadKey.startsWith(ADMIT_FILTER_PREFIX) ||
            workloadKey.startsWith(ADMIT_WATERMARK_PREFIX) && workloadKey.contains(WATERMARK_TYPE_OVERLAY)

    private fun timeoutSlackPercent(capture: CaptureRow): Double? =
        capture.timeoutMarginMs?.let { marginMs ->
            100.0 * marginMs.toDouble() / MakerFeature.CAPTURE_TIMEOUT_MS.toDouble()
        }

    private fun kaplanMeierMedian(observations: List<EventObservation>): Int? {
        if (observations.isEmpty()) {
            return null
        }

        var survival = 1.0
        val eventShots = observations.asSequence()
            .filter { observation -> observation.eventObserved }
            .map { observation -> observation.observationShot }
            .distinct()
            .sorted()
        for (shot in eventShots) {
            val atRiskCount = observations.count { observation -> observation.observationShot >= shot }
            val eventCount = observations.count { observation ->
                observation.eventObserved && observation.observationShot == shot
            }
            if (atRiskCount <= 0 || eventCount <= 0) {
                continue
            }
            survival *= 1.0 - eventCount.toDouble() / atRiskCount.toDouble()
            if (survival <= 0.5) {
                return shot
            }
        }
        return null
    }

    private fun averageOrNull(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.average()

    private fun groupRq3ExperimentRuns(captures: List<CaptureRow>): List<List<CaptureRow>> {
        val runs = mutableListOf<List<CaptureRow>>()
        var currentRun = mutableListOf<CaptureRow>()
        var previousPpSequenceId: Int? = null

        for (capture in captures) {
            val previousId = previousPpSequenceId
            if (currentRun.isNotEmpty() && previousId != null && capture.metrics.ppSequenceId <= previousId) {
                runs.add(currentRun)
                currentRun = mutableListOf()
            }
            currentRun.add(capture)
            previousPpSequenceId = capture.metrics.ppSequenceId
        }
        if (currentRun.isNotEmpty()) {
            runs.add(currentRun)
        }
        return runs
    }

    private fun percent(numerator: Int, denominator: Int): Double? {
        if (denominator <= 0) {
            return null
        }
        return 100.0 * numerator.toDouble() / denominator.toDouble()
    }

    /** Excel PERCENTILE.INC-compatible interpolation without rounding intermediate values. */
    private fun <T : Number> inclusivePercentile(values: List<T>, quantile: Double): Double? {
        if (values.isEmpty()) {
            return null
        }

        val sorted = values.map { value -> value.toDouble() }.sorted()
        val rank = (sorted.size - 1) * quantile.coerceIn(0.0, 1.0)
        val lowerIndex = floor(rank).toInt()
        val upperIndex = ceil(rank).toInt()
        if (lowerIndex == upperIndex) {
            return sorted[lowerIndex]
        }

        val fraction = rank - lowerIndex
        return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * fraction
    }

    /** Conditional positive-delay percentile: a fully observed all-zero trace is 0, not missing. */
    private fun conditionalPositiveDelayPercentile(
        positiveDelays: List<Long>,
        traceAvailable: Boolean,
        quantile: Double,
    ): Double? {
        if (!traceAvailable) {
            return null
        }
        return inclusivePercentile(positiveDelays, quantile) ?: 0.0
    }

    suspend fun export(): File {
        val outputDir = context.getExternalFilesDir(DIR_NAME)
            ?: throw IllegalStateException("External files dir is unavailable")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val outputFile = File(outputDir, FILE_NAME)
        val temporaryOutputFile = File(outputDir, "$FILE_NAME.tmp")

        val metricsList = repository.getAll()

        try {
            StreamingXlsxWorkbook(temporaryOutputFile).use { workbook ->
                val rawCaptures = metricsList.mapIndexed { index, metrics ->
                    val draftMetrics = metrics.draftSequenceMetrics

                    val nodeMetricsList = draftMetrics?.nodeExecutionMetricsList.orEmpty()
                    val predictionList = draftMetrics?.nodeExecutionPredictionList.orEmpty()
                    val nodeRows = nodeMetricsList.mapIndexed { index, node ->
                        NodeRow(
                            node = node,
                            prediction = predictionList.getOrNull(index),
                            sequenceActualDurationMs = nodeMetricsList.drop(index)
                                .sumOf { it.postExecutionMetrics.durationMs }
                                .takeIf { it > 0L },
                        )
                    }

                    CaptureRow(
                        captureIndex = index + 1,
                        metrics = metrics,
                        nodeRows = nodeRows,
                    )
                }

                val enrichedNormalCaptures = processCaptures(rawCaptures)
                val replayCaptures = enrichedNormalCaptures.sortedBy { enriched -> enriched.row.captureIndex }
                val admissionReplayRows = nodeSheetRows(replayCaptures)
                    .filter { row -> row.nodeRow.isAdmissionWorkload && row.nodeRow.prediction != null }
                val rq3Export = buildRq3Export(rawCaptures)
                val rq1Export = buildRq1Export(rq3Export.captures)
                val replayScopeRows = buildReplayScopeRows(rq3Export.captures, rq1Export.runs)
                val caseStudyRows = rq3Export.captures.filter { row ->
                    row.runShotIndex <= RQ_TARGET_SHOT_COUNT
                }

                writeSheet(workbook, "AdmissionReplay", admissionReplayRows, buildAdmissionReplayColumns())
                writeSheet(workbook, "PacingReplay", replayCaptures, buildPacingReplayColumns())
                writeSheet(workbook, "ReplayScope", replayScopeRows, buildReplayScopeColumns())
                writeSheet(workbook, "RQ1Runs", rq1Export.runs, buildRq1RunColumns())
                writeSheet(workbook, "RQ1Conditions", rq1Export.conditions, buildRq1ConditionColumns())
                writeSheet(workbook, "CaseStudyTrace", caseStudyRows, buildCaseStudyColumns())
                writeSheet(workbook, "RQ3Pacing", rq3Export.captures, buildRq3PacingColumns())
                writeSheet(workbook, "RQ3Summary", rq3Export.summaries, buildRq3SummaryColumns())
                writeSheet(workbook, "ReplayNotes", buildReplayNotes(), buildReplayNoteColumns())

                // Write main sheet
                writeSheet(workbook, "Capture", enrichedNormalCaptures, buildCaptureColumns())

                // Write sub-sheets for each category
                generateSubSheets(workbook, enrichedNormalCaptures, "")
            }

            Files.move(
                temporaryOutputFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporaryOutputFile.delete()
        }

        return outputFile
    }

    private fun processCaptures(captures: List<CaptureRow>): List<EnrichedCaptureRow> {
        val usesPacerSessionBoundary = captures.isNotEmpty() &&
            captures.all { it.metrics.draftSequenceMetrics?.pacerSessionId != null }
        val sessionBoundarySource = if (usesPacerSessionBoundary) {
            SESSION_BOUNDARY_PACER
        } else {
            SESSION_BOUNDARY_TIMEOUT_PROXY
        }
        val groups = groupCaptures(captures, usesPacerSessionBoundary)
        groups.forEach(::simulateAdmissionAfter)

        val enriched = mutableListOf<EnrichedCaptureRow>()
        groups.forEachIndexed { sessionId, group ->
            var bokehAdmitCount = 0
            var bokehTotalCount = 0
            var filterAdmitCount = 0
            var filterTotalCount = 0
            val sessionTimeoutShotCount = group.indexOfFirst {
                it.metrics.draftSequenceMetrics?.isTimeout == true
            }.takeIf { it >= 0 }?.let { it + 1 }

            group.forEachIndexed { indexInSession, groupMember ->
                val bokehRows = groupMember.nodeRows.filter { it.isBokehWorkload && it.prediction != null }
                val filterRows = groupMember.nodeRows.filter { it.isFilterWorkload && it.prediction != null }
                bokehAdmitCount += bokehRows.count { it.prediction?.admit == true }
                bokehTotalCount += bokehRows.size
                filterAdmitCount += filterRows.count { it.prediction?.admit == true }
                filterTotalCount += filterRows.size

                val sessionSummary = SessionSummary(
                    sessionId = sessionId,
                    sessionBoundarySource = sessionBoundarySource,
                    sessionShotCount = group.size,
                    sessionCaptureIndex = indexInSession + 1,
                    sessionTimeoutShotCount = sessionTimeoutShotCount,
                    sessionBokehAdmitCount = bokehAdmitCount,
                    sessionBokehTotalCount = bokehTotalCount,
                    sessionBokehAdmitRate = SessionSummary.admitRate(bokehAdmitCount, bokehTotalCount),
                    sessionFilterAdmitCount = filterAdmitCount,
                    sessionFilterTotalCount = filterTotalCount,
                    sessionFilterAdmitRate = SessionSummary.admitRate(filterAdmitCount, filterTotalCount),
                )
                enriched.add(
                    EnrichedCaptureRow(
                        groupMember,
                        sessionSummary,
                        computeWallBaseDiagnostics(group, groupMember),
                    )
                )
            }
        }
        return enriched
    }

    /** Replays the current admission code over recorded predictions while preserving sticky demotion order. */
    private fun simulateAdmissionAfter(captures: List<CaptureRow>) {
        val replayPolicy = DraftSequenceAdmissionPolicy()

        for (capture in captures) {
            for (row in capture.nodeRows) {
                val prediction = row.prediction ?: continue
                val workloadKey = row.replayWorkloadKey() ?: continue

                val modelAdmit = DraftSequenceExecutionPredictor.admitsOptionalWorkload(
                    workloadSequencePredictedMs = prediction.sequencePredictedDurationMs,
                    workloadSequenceUpperBoundMs = prediction.sequencePredictedUpperBoundMs,
                    budgetMs = row.node.preExecutionMetrics.budgetMs,
                )
                val hasFrameWatermark = prediction.workloadSequenceKey?.contains(WATERMARK_TYPE_FRAME) == true

                row.afterModelAdmit = modelAdmit
                row.afterSessionDemotedBeforeDecision =
                    AdmissionGroup.of(workloadKey)?.let(replayPolicy::isDemoted)
                row.afterAdmit = replayPolicy.admit(
                    workloadKey = workloadKey,
                    hasFrameWatermark = hasFrameWatermark,
                    modelAdmit = modelAdmit,
                )
            }
        }
    }

    /**
     * Groups captures into burst sessions. Prefers the recorded runtime pacer session id (increments each time the
     * drained pipeline clears the pacer); rows persisted before that field existed fall back to the legacy
     * timeout-delimited grouping.
     */
    private fun groupCaptures(captures: List<CaptureRow>, usesPacerSessionBoundary: Boolean): List<List<CaptureRow>> {
        val groups = mutableListOf<List<CaptureRow>>()
        var currentGroup = mutableListOf<CaptureRow>()

        if (usesPacerSessionBoundary) {
            for (capture in captures) {
                val previousSessionId = currentGroup.lastOrNull()?.metrics?.draftSequenceMetrics?.pacerSessionId
                val sessionId = capture.metrics.draftSequenceMetrics?.pacerSessionId
                if (currentGroup.isNotEmpty() && previousSessionId != sessionId) {
                    groups.add(currentGroup)
                    currentGroup = mutableListOf()
                }
                currentGroup.add(capture)
            }
            if (currentGroup.isNotEmpty()) {
                groups.add(currentGroup)
            }
            return groups
        }

        for (capture in captures) {
            val isTimeout = capture.metrics.draftSequenceMetrics?.isTimeout == true
            if (currentGroup.isEmpty() && isTimeout) {
                continue
            }

            currentGroup.add(capture)

            if (isTimeout) {
                groups.add(currentGroup)
                currentGroup = mutableListOf()
            }
        }
        if (currentGroup.isNotEmpty()) {
            groups.add(currentGroup)
        }
        return groups
    }

    private fun generateSubSheets(
        workbook: StreamingXlsxWorkbook,
        captures: List<EnrichedCaptureRow>,
        sheetNamePrefix: String,
    ) {
        val nodeNames = nodeSheetRows(captures)
            .map { it.nodeRow.node.nodeName }
            .distinct()
            .sorted()
            .toList()
        val nodeColumns = buildNodeColumns()

        nodeNames.forEach { nodeName ->
            val rows = nodeSheetRows(captures).filter { it.nodeRow.node.nodeName == nodeName }
            writeSheet(workbook, "$sheetNamePrefix$nodeName", rows, nodeColumns)
        }
    }

    private fun <T> writeSheet(
        workbook: StreamingXlsxWorkbook,
        sheetName: String,
        items: Iterable<T>,
        columns: List<Column<T>>,
    ) = writeSheet(workbook, sheetName, items.asSequence(), columns)

    private fun <T> writeSheet(
        workbook: StreamingXlsxWorkbook,
        sheetName: String,
        items: Sequence<T>,
        columns: List<Column<T>>,
    ) {
        workbook.writeSheet(
            requestedName = sheetName,
            columnTitles = columns.map { it.title },
            rows = items,
            values = { item -> columns.map { column -> column.extractor(item) } },
        )
    }

    private class Column<T>(
        val title: String,
        val extractor: (T) -> Any?,
    )

    private data class ReplayNote(
        val topic: String,
        val note: String,
    )

    private class CaptureRow(
        val captureIndex: Int,
        val metrics: CaptureMetrics,
        val nodeRows: List<NodeRow>,
    ) {
        val pacingReplay: PacingReplay? = metrics.draftSequenceMetrics?.captureAvailablePacing?.let(::PacingReplay)

        val firstNodeStartUptimeMs: Long?
            get() = nodeRows.firstOrNull()?.node?.startUptimeMs

        val draftStartUptimeMs: Long?
            get() = metrics.draftSequenceMetrics?.draftStartUptimeMs

        val draftEndUptimeMs: Long?
            get() = metrics.draftSequenceMetrics?.draftEndUptimeMs

        /** Whole-draft wall time; the offline counterpart of the pacer's observed draft timing input. */
        val draftSequenceDurationMs: Long?
            get() {
                val startMs = draftStartUptimeMs ?: return null
                val endMs = draftEndUptimeMs ?: return null
                return endMs - startMs
            }

        /** Deadline minus draft end: positive = finished with margin, negative = blew the capture timeout. */
        val timeoutMarginMs: Long?
            get() {
                val endMs = draftEndUptimeMs ?: return null
                val deadlineMs = metrics.timeoutTimestampMs ?: return null
                return deadlineMs - endMs
            }

        val workloadSequenceDurationMs: Long?
            get() = nodeRows.sumOf { row -> row.node.postExecutionMetrics.durationMs }
                .takeIf { durationMs -> durationMs > 0L }

        /** First decision's configured suffix, before optional nodes are skipped. */
        val plannedWorkloadSequenceKey: String?
            get() = nodeRows.asSequence().mapNotNull { row -> row.prediction?.workloadSequenceKey }.firstOrNull()

        /** Exact measured node sequence delivered by this trace; use it to audit fixed-workload RQ3 comparisons. */
        val executedWorkloadSequenceKey: String?
            get() = nodeRows.asSequence()
                .filter { row -> row.wasExecuted }
                .mapNotNull { row -> row.node.workloadKey }
                .toList()
                .takeIf { keys -> keys.isNotEmpty() }
                ?.joinToString(">")

        val bokehDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isBokehWorkload && it.prediction != null }

        val decodingDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isDecodingWorkload && it.prediction != null }

        val filterDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isFilterWorkload && it.prediction != null }

        val overlayWatermarkDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isOverlayWatermarkWorkload && it.prediction != null }

        val hasTimeoutFailure: Boolean
            get() = metrics.draftSequenceMetrics?.isTimeout == true

        val hasWatchdogFailure: Boolean
            get() = metrics.draftSequenceMetrics?.hasWatchdogTimeout == true ||
                    nodeRows.any { it.node.watchdogTimedOut == true }

        val hasTimeoutOrWatchdogFailure: Boolean
            get() = hasTimeoutFailure || hasWatchdogFailure

        val isFilterPreserved: Boolean
            get() = filterDecisionRow?.wasExecuted == true

        val isBokehExecuted: Boolean
            get() = bokehDecisionRow?.wasExecuted == true

        val isFullFeatureSuccess: Boolean
            get() = policyOutcome() == PolicyOutcome.FULL_FEATURE_SUCCESS

        val isSelectiveBokehSkipSuccess: Boolean
            get() = policyOutcome() == PolicyOutcome.SELECTIVE_BOKEH_SKIP_SUCCESS

        val isBothSkipped: Boolean
            get() = bokehDecisionRow?.wasSkipped == true && filterDecisionRow?.wasSkipped == true

        val bokehPredictedBudgetOverrun: Boolean?
            get() = predictedBudgetOverrun(bokehDecisionRow)

        val bokehObservedBudgetOverrun: Boolean?
            get() = observedBudgetOverrun(bokehDecisionRow)

        val filterPredictedBudgetOverrun: Boolean?
            get() = predictedBudgetOverrun(filterDecisionRow)

        val filterObservedBudgetOverrun: Boolean?
            get() = observedBudgetOverrun(filterDecisionRow)

        /** "Always run everything" proxy risk: any admission decision's recorded UB above its budget. */
        val alwaysRunBudgetRiskByUpperBound: Boolean?
            get() {
                val decisionRows = listOfNotNull(
                    bokehDecisionRow,
                    decodingDecisionRow,
                    filterDecisionRow,
                    overlayWatermarkDecisionRow,
                )
                if (decisionRows.isEmpty()) {
                    return null
                }
                return decisionRows.any { predictedBudgetOverrun(it) == true }
            }

        fun policyOutcome(): PolicyOutcome {
            if (hasTimeoutFailure) {
                return PolicyOutcome.TIMEOUT_FAILURE
            }
            if (hasWatchdogFailure) {
                return PolicyOutcome.WATCHDOG_FAILURE
            }

            val bokehDecision = bokehDecisionRow
            val filterDecision = filterDecisionRow
            val bokehCompleted = bokehDecision?.wasCompleted == true
            val filterCompleted = filterDecision?.wasCompleted == true
            val bokehSkipped = bokehDecision?.wasSkipped == true
            val filterSkipped = filterDecision?.wasSkipped == true

            return when {
                bokehCompleted && filterCompleted -> PolicyOutcome.FULL_FEATURE_SUCCESS
                bokehSkipped && filterCompleted -> PolicyOutcome.SELECTIVE_BOKEH_SKIP_SUCCESS
                bokehDecision?.wasAdmitted == true && filterDecision != null && !filterCompleted ->
                    PolicyOutcome.OBSERVED_FILTER_LOSS_AFTER_BOKEH_ADMIT
                bokehSkipped && filterSkipped -> PolicyOutcome.TAIL_ONLY_SAFE
                else -> PolicyOutcome.OTHER
            }
        }

        private fun predictedBudgetOverrun(decisionRow: NodeRow?): Boolean? {
            val row = decisionRow ?: return null
            val prediction = row.prediction ?: return null
            return prediction.sequencePredictedUpperBoundMs > row.node.preExecutionMetrics.budgetMs
        }

        /** Delegates to [NodeSheetRow.observedActualFeasible] so Capture and node sheets can never disagree. */
        private fun observedBudgetOverrun(decisionRow: NodeRow?): Boolean? {
            val row = decisionRow ?: return null
            val decisionIndex = nodeRows.indexOf(row)
            if (decisionIndex < 0) {
                return null
            }
            return NodeSheetRow(this, decisionIndex + 1, row).observedActualFeasible()?.let { feasible -> !feasible }
        }
    }

    private class SessionSummary(
        val sessionId: Int,
        val sessionBoundarySource: String,
        val sessionShotCount: Int,
        val sessionCaptureIndex: Int,
        val sessionTimeoutShotCount: Int?,
        val sessionBokehAdmitCount: Int,
        val sessionBokehTotalCount: Int,
        val sessionBokehAdmitRate: Double?,
        val sessionFilterAdmitCount: Int,
        val sessionFilterTotalCount: Int,
        val sessionFilterAdmitRate: Double?,
    ) {
        companion object {
            fun admitRate(admitCount: Int, totalCount: Int): Double? {
                if (totalCount <= 0) {
                    return null
                }
                return admitCount.toDouble() / totalCount.toDouble()
            }
        }
    }

    /** Before snapshot plus the current pacing code's counterfactual result over the persisted inputs. */
    private class PacingReplay(
        val before: CaptureAvailablePacingMetrics,
    ) {
        val captureTimeoutMs: Long = MakerFeature.CAPTURE_TIMEOUT_MS
        val shutterToDecisionMs: Long = captureTimeoutMs - before.timeToDeadlineMs

        val afterLevelDeficitMs: Long = computeLevelDeficitMs(
            draftSequenceBudgetMs = before.draftSequenceBudgetMs,
            draftSequenceReservedDurationMs = before.draftSequenceReservedDurationMs,
        )
        val afterAppliedDelayMs: Long = computePacingDelayMs(
            backlogMs = before.backlogMs,
            timeToDeadlineMs = before.timeToDeadlineMs,
            draftSequenceReservedDurationMs = before.draftSequenceReservedDurationMs,
        )
        val delayDeltaMs: Long = afterAppliedDelayMs - before.appliedDelayMs
        val pacingChanged: Boolean = afterAppliedDelayMs != before.appliedDelayMs
        val afterOutcomeStatus: String = if (pacingChanged) {
            PACING_OUTCOME_REQUIRES_REPLAY
        } else {
            PACING_OUTCOME_RECORDED_REUSABLE
        }
    }

    private class NodeSheetRow(
        val capture: CaptureRow,
        val nodeOrder: Int,
        val nodeRow: NodeRow,
        val sessionSummary: SessionSummary? = null,
    ) {
        fun admissionStage(): String? = nodeRow.admissionStage()

        fun admissionSkipReason(): String? {
            val prediction = nodeRow.prediction ?: return null
            if (!nodeRow.isAdmissionWorkload || prediction.admit) {
                return null
            }
            return if (prediction.sequencePredictedUpperBoundMs > nodeRow.node.preExecutionMetrics.budgetMs) {
                ADMISSION_SKIP_REASON_UPPER_BOUND
            } else {
                // The model only rejects when UB exceeds budget, so a skip with UB within budget was forced by
                // session-sticky demotion (or the per-capture chain forcing that preceded it in older rows).
                ADMISSION_SKIP_REASON_SESSION_DEMOTION
            }
        }

        fun observedActualFeasible(): Boolean? {
            if (!isFullyObservedSuffix()) {
                return null
            }
            val actualDurationMs = nodeRow.sequenceActualDurationMs ?: return null
            return actualDurationMs <= nodeRow.node.preExecutionMetrics.budgetMs
        }

        fun sequenceUpperBoundMiss(): Boolean? {
            if (!isFullyObservedSuffix()) {
                return null
            }
            val prediction = nodeRow.prediction ?: return null
            val actualDurationMs = nodeRow.sequenceActualDurationMs ?: return null
            return actualDurationMs > prediction.sequencePredictedUpperBoundMs
        }

        fun decisionOutcome(): DecisionOutcome? {
            val prediction = nodeRow.prediction ?: return null
            if (!nodeRow.isAdmissionWorkload) {
                return null
            }
            if (!prediction.admit) {
                return DecisionOutcome.SKIP_REQUIRES_OFFLINE_ORACLE
            }
            if (capture.hasTimeoutOrWatchdogFailure || nodeRow.node.watchdogTimedOut == true) {
                return DecisionOutcome.UNSAFE_ADMIT
            }

            val actualFeasible = observedActualFeasible() ?: return DecisionOutcome.ADMIT_OUTCOME_NOT_FULLY_OBSERVED
            return if (actualFeasible) {
                DecisionOutcome.CORRECT_ADMIT
            } else {
                DecisionOutcome.UNSAFE_ADMIT
            }
        }

        fun decisionOutcomeLabel(): String? = decisionOutcome()?.label

        /** Model-only before decision inferred from the policy active when these metrics were recorded. */
        fun inferredBeforeModelAdmit(): Boolean? {
            val prediction = nodeRow.prediction ?: return null
            if (!nodeRow.isAdmissionWorkload) {
                return null
            }
            return prediction.sequencePredictedDurationMs <= 0.0 ||
                prediction.sequencePredictedUpperBoundMs <= nodeRow.node.preExecutionMetrics.budgetMs
        }

        fun afterAdmitChanged(): Boolean? {
            val beforeAdmit = nodeRow.prediction?.admit ?: return null
            val afterAdmit = nodeRow.afterAdmit ?: return null
            return beforeAdmit != afterAdmit
        }

        fun afterDecisionOutcome(): String? {
            val prediction = nodeRow.prediction ?: return null
            val afterAdmit = nodeRow.afterAdmit ?: return null
            if (afterAdmit == prediction.admit) {
                return decisionOutcomeLabel()
            }
            if (afterAdmit) {
                return AFTER_ADMIT_REQUIRES_OFFLINE_REPLAY
            }

            val actualFeasible = observedActualFeasible() ?: return AFTER_SKIP_REQUIRES_OFFLINE_REPLAY
            return if (actualFeasible) {
                AFTER_UNNECESSARY_SKIP
            } else {
                AFTER_CORRECT_SKIP
            }
        }

        fun afterObservationStatus(): String? {
            val prediction = nodeRow.prediction ?: return null
            val afterAdmit = nodeRow.afterAdmit ?: return null
            return if (afterAdmit == prediction.admit) {
                observationStatus()
            } else if (!prediction.admit && afterAdmit) {
                AFTER_ADMIT_REQUIRES_OFFLINE_REPLAY
            } else if (observedActualFeasible() != null) {
                AFTER_SKIP_EVALUATED_FROM_RECORDED_ADMIT
            } else {
                AFTER_SKIP_REQUIRES_OFFLINE_REPLAY
            }
        }

        fun observationStatus(): String? {
            if (!nodeRow.isAdmissionWorkload || nodeRow.prediction == null) {
                return null
            }
            return when (decisionOutcome()) {
                DecisionOutcome.CORRECT_ADMIT,
                DecisionOutcome.UNSAFE_ADMIT -> "Online observed"
                DecisionOutcome.ADMIT_OUTCOME_NOT_FULLY_OBSERVED -> "Admit suffix not fully observed"
                DecisionOutcome.SKIP_REQUIRES_OFFLINE_ORACLE -> "Offline oracle required"
                null -> null
            }
        }

        fun isFullyObservedSuffix(): Boolean {
            if (!nodeRow.isAdmissionWorkload || nodeRow.prediction == null) {
                return false
            }

            val suffixRows = capture.nodeRows.drop(nodeOrder - 1)
            return suffixRows.isNotEmpty() && suffixRows.all { it.nodeActualDurationMs != null }
        }
    }

    private class NodeRow(
        val node: NodeExecutionMetrics,
        val prediction: ExecutionPrediction?,
        val sequenceActualDurationMs: Long?,
    ) {
        var afterModelAdmit: Boolean? = null
        var afterSessionDemotedBeforeDecision: Boolean? = null
        var afterAdmit: Boolean? = null

        val nodeActualDurationMs: Long?
            get() = node.postExecutionMetrics.durationMs.takeIf { it > 0L }

        val isBokehWorkload: Boolean
            get() = node.workloadKey?.startsWith(ADMIT_BOKEH_PREFIX) == true

        val isDecodingWorkload: Boolean
            get() = node.workloadKey?.startsWith(ADMIT_DECODING_PREFIX) == true

        val isFilterWorkload: Boolean
            get() = node.workloadKey?.startsWith(ADMIT_FILTER_PREFIX) == true

        val isOverlayWatermarkWorkload: Boolean
            get() = node.workloadKey?.startsWith(ADMIT_WATERMARK_PREFIX) == true &&
                    node.workloadKey?.contains(WATERMARK_TYPE_OVERLAY) == true

        val isAdmissionWorkload: Boolean
            get() = isBokehWorkload || isDecodingWorkload || isFilterWorkload || isOverlayWatermarkWorkload

        val wasAdmitted: Boolean?
            get() = prediction?.admit

        val wasSkipped: Boolean
            get() = prediction?.admit == false

        /** Actual node execution, independent of a shadow/forced admission recommendation. */
        val wasExecuted: Boolean
            get() = node.postExecutionMetrics.durationMs > 0L ||
                node.postExecutionMetrics.gcSnapshot != null ||
                node.postExecutionMetrics.cpuProcessingSnapshot != null

        val wasCompleted: Boolean
            get() = prediction?.admit == true && wasExecuted

        fun admissionStage(): String? {
            return when {
                isBokehWorkload -> ADMISSION_STAGE_BOKEH
                isDecodingWorkload -> ADMISSION_STAGE_DECODING
                isFilterWorkload -> ADMISSION_STAGE_FILTER
                isOverlayWatermarkWorkload -> ADMISSION_STAGE_OVERLAY_WATERMARK
                else -> null
            }
        }

        fun sequencePredictionResidualMs(): Double? {
            val prediction = prediction ?: return null
            val actualDurationMs = sequenceActualDurationMs ?: return null
            return actualDurationMs - prediction.sequencePredictedDurationMs
        }

        fun sequenceUpperBoundSlackMs(): Double? {
            val prediction = prediction ?: return null
            val actualDurationMs = sequenceActualDurationMs ?: return null
            return prediction.sequencePredictedUpperBoundMs - actualDurationMs
        }
    }

    private enum class DecisionOutcome(val label: String) {
        CORRECT_ADMIT("Correct Admit"),
        UNSAFE_ADMIT("Unsafe Admit"),
        ADMIT_OUTCOME_NOT_FULLY_OBSERVED("Admit Outcome Not Fully Observed"),
        SKIP_REQUIRES_OFFLINE_ORACLE("Skip Requires Offline Oracle"),
    }

    private enum class PolicyOutcome(val label: String) {
        FULL_FEATURE_SUCCESS("Full Feature Success"),
        SELECTIVE_BOKEH_SKIP_SUCCESS("Selective Bokeh Skip Success"),
        OBSERVED_FILTER_LOSS_AFTER_BOKEH_ADMIT("Observed Filter Loss after Bokeh Admit"),
        TAIL_ONLY_SAFE("Tail-only Safe"),
        TIMEOUT_FAILURE("Timeout Failure"),
        WATCHDOG_FAILURE("Watchdog Failure"),
        OTHER("Other"),
    }


    private companion object {
        private const val DIR_NAME = "metrics"
        private val FILE_NAME = "${Build.MODEL}_metrics.xlsx"
        private const val RQ_TARGET_SHOT_COUNT = 30
        private const val RQ1_SHORT_BURST_SHOT_COUNT = 5
        private const val RQ3_HIGH_BACKLOG_FRACTION = 0.8
        private const val RESULT_PROVENANCE_FACTUAL = "FACTUAL"
        private const val RESULT_PROVENANCE_EQUIVALENT_THROUGH_30 = "EXACT_EQUIVALENT_THROUGH_30"
        private const val RESULT_PROVENANCE_EXACT_OBSERVED_PREFIX = "EXACT_OBSERVED_PREFIX_ONLY"
        private const val RESULT_PROVENANCE_SOURCE_TRACE_AFTER_DIVERGENCE =
            "EXACT_PREFIX_THEN_SOURCE_TRACE_ONLY"
        private const val RESULT_PROVENANCE_INSUFFICIENT_ACTION_COVERAGE =
            "INSUFFICIENT_ACTION_EVIDENCE"
        private const val TIMEOUT_PROVENANCE_FACTUAL_RECORDED_TRACE = "FACTUAL_RECORDED_TRACE"
        private const val TIMEOUT_PROVENANCE_EXACT_ACTION_EQUIVALENT_PREFIX =
            "EXACT_ACTION_EQUIVALENT_PREFIX"
        private const val TIMEOUT_PROVENANCE_EXACT_NO_TIMEOUT_THROUGH_30 = "EXACT_NO_TIMEOUT_THROUGH_30"
        private const val TIMEOUT_PROVENANCE_NOT_IDENTIFIABLE = "NOT_IDENTIFIABLE_AFTER_DIVERGENCE_OR_CENSORING"
        private const val REPLAY_RECORDED_SOURCE_TRACE_REQUIREMENT = "ANY_SINGLE_RECORDED_RUNTIME_ARM"
        private const val REPLAY_SOURCE_TRACE_REQUIREMENT = "FULL_RUNTIME; OPERATOR_VERIFICATION_REQUIRED"
        private const val REPLAY_CERTIFICATION_FACTUAL_RECORDED_TARGET = "FACTUAL_RECORDED_TARGET"
        private const val REPLAY_CERTIFICATION_CONDITIONAL_ON_FULL_SOURCE =
            "CONDITIONAL_ON_FULL_SOURCE_ASSERTION"
        private const val REPLAY_TARGET_RECORDED_RUNTIME = "RECORDED_RUNTIME"
        private const val REPLAY_TARGET_BASELINE = "ADMISSION_OFF+PACING_OFF"
        private const val REPLAY_TARGET_ADMISSION_ONLY = "ADMISSION_ONLY"
        private const val REPLAY_TARGET_PACING_ONLY = "PACING_ONLY"
        private const val REPLAY_TARGET_RQ3_NO_PACING = "RQ3_NO_PACING_FROM_ADMISSION_ONLY"
        private const val PACING_OBSERVATION_RECORDED_DECISION = "RECORDED_DECISION"
        private const val PACING_OBSERVATION_SESSION_BOOTSTRAP_ZERO = "SESSION_BOOTSTRAP_ZERO"
        private const val PACING_OBSERVATION_NO_GATING_DECISION_ZERO = "NO_GATING_DECISION_ZERO"
        private const val PACING_OBSERVATION_MISSING = "MISSING"
        private const val RQ1_RUN_STATUS_COMPLETE = "COMPLETE_30"
        private const val RQ1_RUN_STATUS_CAPTURE_TIMEOUT = "CAPTURE_TIMEOUT"
        private const val RQ1_RUN_STATUS_WATCHDOG_ONLY = "WATCHDOG_ONLY"
        private const val RQ1_RUN_STATUS_INCOMPLETE = "INCOMPLETE"
        private const val DRAFT_CONFIGURATION_M_PLUS_S = "M+S"
        private const val DRAFT_CONFIGURATION_M = "M"
        private const val DRAFT_CONFIGURATION_S = "S"
        private const val DRAFT_CONFIGURATION_UNKNOWN = "UNKNOWN"
        private const val ADMIT_BOKEH_PREFIX = "BOKEH("
        private const val ADMIT_DECODING_PREFIX = "DECODING("
        private const val ADMIT_FILTER_PREFIX = "FILTER("
        private const val ADMIT_WATERMARK_PREFIX = "WATERMARK("
        private const val WATERMARK_TYPE_OVERLAY = "watermarkType=OVERLAY"
        private const val WATERMARK_TYPE_FRAME = "watermarkType=FRAME"
        private const val ADMISSION_STAGE_BOKEH = "Bokeh"
        private const val ADMISSION_STAGE_DECODING = "Decoding"
        private const val ADMISSION_STAGE_FILTER = "Filter"
        private const val ADMISSION_STAGE_OVERLAY_WATERMARK = "OverlayWatermark"
        private const val ADMISSION_SKIP_REASON_UPPER_BOUND = "upper bound"
        private const val ADMISSION_SKIP_REASON_SESSION_DEMOTION = "session demotion"
        private const val AFTER_ADMIT_REQUIRES_OFFLINE_REPLAY = "After Admit Requires Offline Replay"
        private const val AFTER_SKIP_REQUIRES_OFFLINE_REPLAY = "After Skip Requires Offline Replay"
        private const val AFTER_SKIP_EVALUATED_FROM_RECORDED_ADMIT = "After Skip Evaluated from Recorded Admit"
        private const val AFTER_UNNECESSARY_SKIP = "Unnecessary Skip"
        private const val AFTER_CORRECT_SKIP = "Correct Skip"
        private const val SESSION_BOUNDARY_PACER = "runtime pacer session id"
        private const val SESSION_BOUNDARY_TIMEOUT_PROXY = "timeout-delimited proxy"
        private const val PACING_OUTCOME_RECORDED_REUSABLE = "Recorded outcome reusable"
        private const val PACING_OUTCOME_REQUIRES_REPLAY = "Changed delay requires offline replay"

        private fun nodeSheetRows(captures: List<EnrichedCaptureRow>): Sequence<NodeSheetRow> {
            return captures.asSequence().flatMap { capture ->
                capture.row.nodeRows.asSequence().mapIndexed { index, nodeRow ->
                    NodeSheetRow(
                        capture = capture.row,
                        nodeOrder = index + 1,
                        nodeRow = nodeRow,
                        sessionSummary = capture.sessionSummary,
                    )
                }
            }
        }

        private fun NodeRow.replayWorkloadKey(): WorkloadKey? {
            val workloadKey = node.workloadKey ?: return null
            val sizeBucketName = workloadKey.substringAfter("sizeBucket=", missingDelimiterValue = "")
                .substringBefore(',')
                .substringBefore(')')
            val sizeBucket = SizeBucket.entries.firstOrNull { bucket -> bucket.name == sizeBucketName } ?: return null
            return when {
                isBokehWorkload -> WorkloadKey.Bokeh(sizeBucket)
                isDecodingWorkload -> WorkloadKey.Decoding(sizeBucket)
                isFilterWorkload -> WorkloadKey.Filter(sizeBucket)
                isOverlayWatermarkWorkload -> WorkloadKey.Watermark(sizeBucket, WatermarkType.OVERLAY)
                else -> null
            }
        }

        private fun buildAdmissionReplayColumns(): List<Column<NodeSheetRow>> = listOf(
            Column("captureIndex") { it.capture.captureIndex },
            Column("ppSequenceId") { it.capture.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.capture.metrics.dsMode) },
            Column("dsExtraInfo") { it.capture.metrics.dsExtraInfo },
            Column("isPendingRequest") { it.capture.metrics.draftSequenceMetrics?.isPendingRequest },
            Column("resultImageFormat") { it.capture.metrics.resultImageFormat },
            Column("resultImageWidth") { it.capture.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.capture.metrics.resultImageSize.height },
            Column("sessionId") { it.sessionSummary?.sessionId },
            Column("sessionCaptureIndex") { it.sessionSummary?.sessionCaptureIndex },
            Column("afterSessionBoundarySource") { it.sessionSummary?.sessionBoundarySource },
            Column("nodeOrder") { it.nodeOrder },
            Column("nodeStartUptimeMs") { it.nodeRow.node.startUptimeMs },
            Column("timeoutDeadlineUptimeMs") { it.capture.metrics.timeoutTimestampMs },
            Column("nodeName") { it.nodeRow.node.nodeName },
            Column("admissionStage") { it.admissionStage() },
            Column("workloadKey") { it.nodeRow.node.workloadKey },
            Column("workloadSequenceKey") { it.nodeRow.prediction?.workloadSequenceKey },
            Column("") { "" },
            Column("beforeBudgetMs") { it.nodeRow.node.preExecutionMetrics.budgetMs },
            Column("beforeSequencePredictedDurationMs") {
                it.nodeRow.prediction?.sequencePredictedDurationMs
            },
            Column("beforeSequencePredictedUpperBoundMs") {
                it.nodeRow.prediction?.sequencePredictedUpperBoundMs
            },
            // Decision margin (budget - predicted UB): >0 quantifies admit headroom, <0 the rejection magnitude,
            // so near-threshold decisions can be scored separately from clear-cut ones.
            Column("beforeAdmissionMarginMs") { sheetRow ->
                sheetRow.nodeRow.prediction?.let { prediction ->
                    sheetRow.nodeRow.node.preExecutionMetrics.budgetMs - prediction.sequencePredictedUpperBoundMs
                }
            },
            Column("inferredBeforeModelAdmit") { it.inferredBeforeModelAdmit() },
            Column("beforeEffectiveAdmit") { it.nodeRow.prediction?.admit },
            Column("beforeAdmissionSkipReason") { it.admissionSkipReason() },
            Column("beforeSessionDemotionApplied") {
                val modelAdmit = it.inferredBeforeModelAdmit()
                val effectiveAdmit = it.nodeRow.prediction?.admit
                modelAdmit == true && effectiveAdmit == false
            },
            Column("") { "" },
            Column("afterModelAdmit") { it.nodeRow.afterModelAdmit },
            Column("afterSessionDemotedBeforeDecision") { it.nodeRow.afterSessionDemotedBeforeDecision },
            Column("afterEffectiveAdmit") { it.nodeRow.afterAdmit },
            Column("modelAdmitChanged") {
                val beforeModelAdmit = it.inferredBeforeModelAdmit()
                val afterModelAdmit = it.nodeRow.afterModelAdmit
                if (beforeModelAdmit == null || afterModelAdmit == null) {
                    null
                } else {
                    beforeModelAdmit != afterModelAdmit
                }
            },
            Column("effectiveAdmitChanged") { it.afterAdmitChanged() },
            Column("afterDecisionOutcome") { it.afterDecisionOutcome() },
            Column("afterObservationStatus") { it.afterObservationStatus() },
            Column("") { "" },
            Column("beforeCaptureTimedOut") { it.capture.hasTimeoutFailure },
            Column("beforeCaptureWatchdogFailed") { it.capture.hasWatchdogFailure },
            Column("beforeNodeDurationMs") { it.nodeRow.nodeActualDurationMs },
            Column("beforeSequenceActualDurationMs") { it.nodeRow.sequenceActualDurationMs },
            Column("beforeSuffixFullyObserved") { it.isFullyObservedSuffix() },
            Column("beforeObservedActualFeasible") { it.observedActualFeasible() },
            Column("beforeDecisionOutcome") { it.decisionOutcomeLabel() },
            Column("beforeDecisionObservationStatus") { it.observationStatus() },
            Column("beforeSequencePredictionResidualMs") { it.nodeRow.sequencePredictionResidualMs() },
            Column("beforeSequenceUpperBoundSlackMs") { it.nodeRow.sequenceUpperBoundSlackMs() },
            Column("beforeSequenceUpperBoundMiss") { it.sequenceUpperBoundMiss() },
            Column("") { "" },
            Column("isLowMemory") { it.nodeRow.node.preExecutionMetrics.memorySnapshot.isLowMemory },
            Column("ramAvailablePercent") {
                it.nodeRow.node.preExecutionMetrics.memorySnapshot.ramAvailablePercent
            },
            Column("javaHeapUsedPercent") {
                it.nodeRow.node.preExecutionMetrics.memorySnapshot.javaHeapUsedPercent
            },
            Column("nativeHeapAllocatedPercent") {
                it.nodeRow.node.preExecutionMetrics.memorySnapshot.nativeHeapAllocatedPercent
            },
            Column("overheatLevel") { it.nodeRow.node.preExecutionMetrics.thermalSnapshot.overheatLevel },
            Column("thermalStatus") { it.nodeRow.node.preExecutionMetrics.thermalSnapshot.thermalStatus },
            Column("thermalHeadroom") { it.nodeRow.node.preExecutionMetrics.thermalSnapshot.thermalHeadroom },
            Column("storageUsedPercent") {
                it.nodeRow.node.preExecutionMetrics.storageSnapshot.storageUsedPercent
            },
            Column("beforeWatchdogTimeoutMs") { it.nodeRow.node.watchdogTimeoutMs },
            Column("beforeWatchdogTimedOut") { it.nodeRow.node.watchdogTimedOut },
            // Post-execution contention/GC attribution: separates unsafe admits caused by CPU starvation or
            // blocking GC pauses from systematic prediction misses.
            Column("beforeCpuTimeMs") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.cpuTimeMs },
            Column("beforeWallTimeMs") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.wallTimeMs },
            Column("beforeRunQueueWaitMs") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.runqueueWaitMs },
            Column("beforeCpuUtilizationRatio") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.cpuUtilizationRatio },
            Column("beforeNonvoluntaryCtxSwitches") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.nonvoluntaryCtxSwitches },
            Column("beforeBlockingGcCount") { it.nodeRow.node.postExecutionMetrics.gcSnapshot?.blockingGcCount },
            Column("beforeBlockingGcTimeMs") { it.nodeRow.node.postExecutionMetrics.gcSnapshot?.blockingGcTimeMs },
        )

        private fun buildReplayScopeColumns(): List<Column<ReplayScopeRow>> = listOf(
            Column("deviceModel") { it.deviceModel },
            Column("runId") { it.runId },
            Column("sourceTraceRequirement") { it.sourceTraceRequirement },
            Column("sourceTraceRequirementSatisfied") { it.sourceTraceRequirementSatisfied },
            Column("certificationStatus") { it.certificationStatus },
            Column("targetOutcomeFactualOnRecordedTrace") { it.targetOutcomeFactualOnRecordedTrace },
            Column("publicationEligibleWithoutArmManifest") { it.publicationEligibleWithoutArmManifest },
            Column("dedicatedFactualArmRequiredForPublication") {
                it.dedicatedFactualArmRequiredForPublication
            },
            Column("targetPolicy") { it.targetPolicy },
            Column("analyzedShotCount") { it.analyzedShotCount },
            Column("startingOverheatLevel") { it.startingOverheatLevel },
            Column("sizeBucket") { it.sizeBucket },
            Column("isLowMemory") { it.isLowMemory },
            Column("draftConfiguration") { it.draftConfiguration },
            Column("") { "" },
            Column("recordedFirstAdmissionSkipShot") { it.recordedFirstAdmissionSkipShot },
            Column("recordedFirstAnyAdmissionSkipShot") { it.recordedFirstAnyAdmissionSkipShot },
            Column("recordedFirstPacingDelayShot") { it.recordedFirstPacingDelayShot },
            Column("recordedFirstTimeoutShot") { it.recordedFirstTimeoutShot },
            Column("actionEvidenceComplete") { it.actionEvidenceComplete },
            Column("conditionalFirstDivergenceShot") { it.firstDivergenceShot },
            Column("conditionalFirstDivergenceUptimeMs") { it.firstDivergenceUptimeMs },
            Column("conditionalExactPrefixEndShot") { it.exactPrefixEndShot },
            Column("conditionalActionEquivalentThrough30") { it.actionEquivalentThrough30 },
            Column("conditionalOutcomeProvenance") { it.outcomeProvenance },
            Column("conditionalExactTargetTimeoutShot") { it.exactTargetTimeoutShot },
            Column("conditionalTimeoutCompletedBeforeDivergence") { it.timeoutCompletedBeforeDivergence },
            Column("conditionalTimeoutOutcomeProvenance") { it.timeoutOutcomeProvenance },
            Column("additionalRunRequiredForTimeoutIdentifiability") {
                it.dedicatedRunRequiredForFirstTimeoutOutcome
            },
            Column("additionalRunRequiredForFullTraceIdentifiability") {
                it.dedicatedRunRequiredForFull30ShotTrace
            },
            Column("interpretation") { it.interpretation },
        )

        private fun buildRq1RunColumns(): List<Column<Rq1RunSummary>> = listOf(
            Column("deviceModel") { it.deviceModel },
            Column("runId") { it.runId },
            Column("sourceShotCount") { it.sourceShotCount },
            Column("analyzedShotCount") { it.analyzedShotCount },
            Column("isComplete30ShotRun") { it.isComplete30ShotRun },
            Column("runStatus") { it.runStatus },
            Column("includedForRq1") { it.includedForRq1 },
            Column("resultProvenance") { it.resultProvenance },
            Column("startingOverheatLevel") { it.startingOverheatLevel },
            Column("startingLevelSourceShotIndex") { it.startingLevelSourceShotIndex },
            Column("sizeBucket") { it.sizeBucket },
            Column("isLowMemory") { it.isLowMemory },
            Column("draftConfiguration") { it.draftConfiguration },
            Column("") { "" },
            Column("firstTimeoutShot") { it.firstTimeoutShot },
            Column("firstTimeoutDraftEndUptimeMs") { it.firstTimeoutDraftEndUptimeMs },
            Column("timeoutObservationShot") { it.timeoutObservationShot },
            Column("timeoutEventObserved") { it.timeoutEventObserved },
            Column("firstTimeoutOverheatLevel") { it.firstTimeoutOverheatLevel },
            Column("firstWatchdogShot") { it.firstWatchdogShot },
            Column("timeoutCountThrough30") { it.timeoutCountThrough30 },
            Column("watchdogCountThrough30") { it.watchdogCountThrough30 },
            Column("") { "" },
            Column("firstAdmissionSkipShot") { it.firstAdmissionSkipShot },
            Column("admissionOnsetTraceComplete") { it.admissionOnsetTraceComplete },
            Column("firstPacingDelayShot") { it.firstPacingDelayShot },
            Column("pacingOnsetTraceComplete") { it.pacingOnsetTraceComplete },
            Column("admissionDecisionCoveragePercent") { it.admissionDecisionCoveragePercent },
            Column("pacingObservationCoveragePercent") { it.pacingObservationCoveragePercent },
            Column("deadlineCoveragePercent") { it.deadlineCoveragePercent },
            Column("slackSampleCount") { it.slackSampleCount },
            Column("slackP5Percent") { it.slackP5Percent },
            Column("") { "" },
            Column("multiAndSingleCompletedAt5Percent") { it.multiAndSingleCompletedAt5Percent },
            Column("multiAndSingleCompletedAt30Percent") { it.multiAndSingleCompletedAt30Percent },
            Column("multiFrameCompletedAt5Percent") { it.multiFrameCompletedAt5Percent },
            Column("multiFrameCompletedAt30Percent") { it.multiFrameCompletedAt30Percent },
            Column("pacingActivatedAt5Percent") { it.pacingActivatedAt5Percent },
            Column("pacingActivatedAt30Percent") { it.pacingActivatedAt30Percent },
            Column("positiveDelayP50At5Ms") { it.positiveDelayP50At5Ms },
            Column("positiveDelayP50At30Ms") { it.positiveDelayP50At30Ms },
        )

        private fun buildRq1ConditionColumns(): List<Column<Rq1ConditionSummary>> = listOf(
            Column("deviceModel") { it.deviceModel },
            Column("startingOverheatLevel") { it.startingOverheatLevel },
            Column("sizeBucket") { it.sizeBucket },
            Column("isLowMemory") { it.isLowMemory },
            Column("draftConfiguration") { it.draftConfiguration },
            Column("sourceRunCount") { it.sourceRunCount },
            Column("includedRunCount") { it.includedRunCount },
            Column("complete30ShotRunCount") { it.complete30ShotRunCount },
            Column("incompleteRunCount") { it.incompleteRunCount },
            Column("timeoutRunCount") { it.timeoutRunCount },
            Column("watchdogRunCount") { it.watchdogRunCount },
            Column("") { "" },
            Column("firstTimeoutEarliestShot") { it.firstTimeoutEarliestShot },
            Column("firstTimeoutKmMedianShot") { it.firstTimeoutKmMedianShot },
            Column("firstTimeoutOverheatLevelMin") { it.firstTimeoutOverheatLevelMin },
            Column("firstTimeoutOverheatLevelMax") { it.firstTimeoutOverheatLevelMax },
            Column("firstAdmissionSkipEarliestShot") { it.firstAdmissionSkipEarliestShot },
            Column("firstAdmissionSkipKmMedianShot") { it.firstAdmissionSkipKmMedianShot },
            Column("admissionOnsetEligibleRunCount") { it.admissionOnsetEligibleRunCount },
            Column("firstPacingDelayEarliestShot") { it.firstPacingDelayEarliestShot },
            Column("firstPacingDelayKmMedianShot") { it.firstPacingDelayKmMedianShot },
            Column("pacingOnsetEligibleRunCount") { it.pacingOnsetEligibleRunCount },
            Column("") { "" },
            Column("timeoutCountThrough30") { it.timeoutCountThrough30 },
            Column("slackEligibleRunCount") { it.slackEligibleRunCount },
            Column("slackSampleCount") { it.slackSampleCount },
            Column("slackP5Percent") { it.slackP5Percent },
            Column("multiAndSingleAt5EligibleRunCount") { it.multiAndSingleAt5EligibleRunCount },
            Column("multiAndSingleCompletedAt5Percent") { it.multiAndSingleCompletedAt5Percent },
            Column("multiAndSingleAt30EligibleRunCount") { it.multiAndSingleAt30EligibleRunCount },
            Column("multiAndSingleCompletedAt30Percent") { it.multiAndSingleCompletedAt30Percent },
            Column("multiFrameAt5EligibleRunCount") { it.multiFrameAt5EligibleRunCount },
            Column("multiFrameCompletedAt5Percent") { it.multiFrameCompletedAt5Percent },
            Column("multiFrameAt30EligibleRunCount") { it.multiFrameAt30EligibleRunCount },
            Column("multiFrameCompletedAt30Percent") { it.multiFrameCompletedAt30Percent },
            Column("pacingAt5EligibleRunCount") { it.pacingAt5EligibleRunCount },
            Column("pacingActivatedAt5Percent") { it.pacingActivatedAt5Percent },
            Column("pacingAt30EligibleRunCount") { it.pacingAt30EligibleRunCount },
            Column("pacingActivatedAt30Percent") { it.pacingActivatedAt30Percent },
            Column("positiveDelayAt5EventCount") { it.positiveDelayAt5EventCount },
            Column("positiveDelayP50At5Ms") { it.positiveDelayP50At5Ms },
            Column("positiveDelayAt30EventCount") { it.positiveDelayAt30EventCount },
            Column("positiveDelayP50At30Ms") { it.positiveDelayP50At30Ms },
        )

        private fun buildCaseStudyColumns(): List<Column<Rq3CaptureRow>> = listOf(
            Column("deviceModel") { it.deviceModel },
            Column("runId") { it.runId },
            Column("runShotIndex") { it.runShotIndex },
            Column("sourceRunShotCount") { it.runShotCount },
            Column("captureIndex") { it.capture.captureIndex },
            Column("ppSequenceId") { it.capture.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.capture.metrics.dsMode) },
            Column("dsExtraInfo") { it.capture.metrics.dsExtraInfo },
            Column("isPendingRequest") { it.capture.metrics.draftSequenceMetrics?.isPendingRequest },
            Column("resultImageFormat") { it.capture.metrics.resultImageFormat },
            Column("resultImageWidth") { it.capture.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.capture.metrics.resultImageSize.height },
            Column("startingOverheatLevel") { it.startingOverheatLevel },
            Column("sizeBucket") { it.sizeBucket },
            Column("isLowMemory") { it.isLowMemory },
            Column("plannedWorkloadSequenceKey") { it.capture.plannedWorkloadSequenceKey },
            Column("executedWorkloadSequenceKey") { it.capture.executedWorkloadSequenceKey },
            Column("workloadSequenceDurationMs") { it.capture.workloadSequenceDurationMs },
            Column("shotToShotTimeMs") { it.capture.metrics.shotToShotTimeMs },
            Column("shotToShotWithoutRecordedPacingMs") {
                val shotToShotTimeMs = it.capture.metrics.shotToShotTimeMs
                val transitionDelayMs = it.transitionDelayMs
                if (shotToShotTimeMs != null && transitionDelayMs != null) {
                    (shotToShotTimeMs - transitionDelayMs).coerceAtLeast(0L)
                } else {
                    null
                }
            },
            Column("shotOverheatLevel") {
                it.capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.overheatLevel
            },
            Column("shotThermalStatus") {
                it.capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalStatus
            },
            Column("shotThermalHeadroom") {
                it.capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalHeadroom
            },
            Column("ramAvailablePercent") {
                it.capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot?.ramAvailablePercent
            },
            Column("nativeHeapAllocatedPercent") {
                it.capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot
                    ?.nativeHeapAllocatedPercent
            },
            Column("") { "" },
            Column("captureTimedOut") { it.capture.hasTimeoutFailure },
            Column("captureWatchdogFailed") { it.capture.hasWatchdogFailure },
            Column("timeoutDeadlineRecorded") { it.capture.metrics.timeoutTimestampMs != null },
            Column("captureTimeoutMs") { MakerFeature.CAPTURE_TIMEOUT_MS },
            Column("timeoutDeadlineUptimeMs") { it.capture.metrics.timeoutTimestampMs },
            Column("draftStartUptimeMs") { it.capture.draftStartUptimeMs },
            Column("draftEndUptimeMs") { it.capture.draftEndUptimeMs },
            Column("draftSequenceDurationMs") { it.capture.draftSequenceDurationMs },
            Column("timeoutMarginMs") { it.capture.timeoutMarginMs },
            Column("timeoutSlackPercent") {
                it.capture.timeoutMarginMs?.let { marginMs ->
                    100.0 * marginMs.toDouble() / MakerFeature.CAPTURE_TIMEOUT_MS.toDouble()
                }
            },
            Column("") { "" },
            Column("bokehWorkloadKey") { it.capture.bokehDecisionRow?.node?.workloadKey },
            Column("bokehBudgetMs") {
                it.capture.bokehDecisionRow?.node?.preExecutionMetrics?.budgetMs
            },
            Column("bokehPredictedDurationMs") {
                it.capture.bokehDecisionRow?.prediction?.sequencePredictedDurationMs
            },
            Column("bokehPredictedUpperBoundMs") {
                it.capture.bokehDecisionRow?.prediction?.sequencePredictedUpperBoundMs
            },
            Column("bokehAdmissionMarginMs") {
                val decision = it.capture.bokehDecisionRow
                val prediction = decision?.prediction
                if (decision != null && prediction != null) {
                    decision.node.preExecutionMetrics.budgetMs - prediction.sequencePredictedUpperBoundMs
                } else {
                    null
                }
            },
            Column("bokehRecommendedAdmit") { it.capture.bokehDecisionRow?.wasAdmitted },
            Column("bokehExecuted") { it.capture.bokehDecisionRow?.wasExecuted },
            Column("bokehActualDurationMs") { it.capture.bokehDecisionRow?.nodeActualDurationMs },
            Column("decodingWorkloadKey") { it.capture.decodingDecisionRow?.node?.workloadKey },
            Column("decodingBudgetMs") {
                it.capture.decodingDecisionRow?.node?.preExecutionMetrics?.budgetMs
            },
            Column("decodingPredictedDurationMs") {
                it.capture.decodingDecisionRow?.prediction?.sequencePredictedDurationMs
            },
            Column("decodingPredictedUpperBoundMs") {
                it.capture.decodingDecisionRow?.prediction?.sequencePredictedUpperBoundMs
            },
            Column("decodingAdmissionMarginMs") {
                val decision = it.capture.decodingDecisionRow
                val prediction = decision?.prediction
                if (decision != null && prediction != null) {
                    decision.node.preExecutionMetrics.budgetMs - prediction.sequencePredictedUpperBoundMs
                } else {
                    null
                }
            },
            Column("decodingRecommendedAdmit") { it.capture.decodingDecisionRow?.wasAdmitted },
            Column("decodingExecuted") { it.capture.decodingDecisionRow?.wasExecuted },
            Column("decodingActualDurationMs") { it.capture.decodingDecisionRow?.nodeActualDurationMs },
            Column("filterWorkloadKey") { it.capture.filterDecisionRow?.node?.workloadKey },
            Column("filterBudgetMs") {
                it.capture.filterDecisionRow?.node?.preExecutionMetrics?.budgetMs
            },
            Column("filterPredictedDurationMs") {
                it.capture.filterDecisionRow?.prediction?.sequencePredictedDurationMs
            },
            Column("filterPredictedUpperBoundMs") {
                it.capture.filterDecisionRow?.prediction?.sequencePredictedUpperBoundMs
            },
            Column("filterAdmissionMarginMs") {
                val decision = it.capture.filterDecisionRow
                val prediction = decision?.prediction
                if (decision != null && prediction != null) {
                    decision.node.preExecutionMetrics.budgetMs - prediction.sequencePredictedUpperBoundMs
                } else {
                    null
                }
            },
            Column("filterRecommendedAdmit") { it.capture.filterDecisionRow?.wasAdmitted },
            Column("filterExecuted") { it.capture.filterDecisionRow?.wasExecuted },
            Column("filterActualDurationMs") { it.capture.filterDecisionRow?.nodeActualDurationMs },
            Column("overlayWatermarkWorkloadKey") {
                it.capture.overlayWatermarkDecisionRow?.node?.workloadKey
            },
            Column("overlayWatermarkBudgetMs") {
                it.capture.overlayWatermarkDecisionRow?.node?.preExecutionMetrics?.budgetMs
            },
            Column("overlayWatermarkPredictedDurationMs") {
                it.capture.overlayWatermarkDecisionRow?.prediction?.sequencePredictedDurationMs
            },
            Column("overlayWatermarkPredictedUpperBoundMs") {
                it.capture.overlayWatermarkDecisionRow?.prediction?.sequencePredictedUpperBoundMs
            },
            Column("overlayWatermarkAdmissionMarginMs") {
                val decision = it.capture.overlayWatermarkDecisionRow
                val prediction = decision?.prediction
                if (decision != null && prediction != null) {
                    decision.node.preExecutionMetrics.budgetMs - prediction.sequencePredictedUpperBoundMs
                } else {
                    null
                }
            },
            Column("overlayWatermarkRecommendedAdmit") {
                it.capture.overlayWatermarkDecisionRow?.wasAdmitted
            },
            Column("overlayWatermarkExecuted") { it.capture.overlayWatermarkDecisionRow?.wasExecuted },
            Column("overlayWatermarkActualDurationMs") {
                it.capture.overlayWatermarkDecisionRow?.nodeActualDurationMs
            },
            Column("rq1AdmissionSkipRecommended") {
                it.capture.bokehDecisionRow?.wasAdmitted == false ||
                    it.capture.filterDecisionRow?.wasAdmitted == false
            },
            Column("anyAdmissionSkipRecommended") {
                it.capture.nodeRows.any { row ->
                    row.isAdmissionWorkload && row.wasAdmitted == false
                }
            },
            Column("") { "" },
            Column("pacingDecisionRecorded") { it.pacingDecisionRecorded },
            Column("pacingObservationAvailable") { it.pacingObservationAvailable },
            Column("pacingObservationSource") { it.pacingObservationSource },
            Column("decisionUptimeMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.decisionUptimeMs
            },
            Column("delayAppliesBeforeShotIndex") { it.delayAppliesBeforeShotIndex },
            Column("appliedDelayMs") { it.appliedDelayMs },
            Column("transitionDelayMs") { it.transitionDelayMs },
            Column("pacedTransition") { it.pacedTransition },
            Column("cumulativeDelayTraceComplete") { it.cumulativeDelayTraceComplete },
            Column("cumulativeDelayBeforeShotMs") { it.cumulativeTransitionDelayMs },
            Column("releaseUptimeMs") { it.releaseUptimeMs },
            Column("controllerBacklogMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.backlogMs
            },
            Column("controllerQueuedDraftCount") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.queuedDraftCount
            },
            Column("controllerQueuedPredictedWorkMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.queuedPredictedWorkMs
            },
            Column("controllerDraftSequenceKey") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.draftSequenceKey
            },
            Column("timeToDeadlineMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.timeToDeadlineMs
            },
            Column("draftSequenceBudgetMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.draftSequenceBudgetMs
            },
            Column("controllerLevelDeficitMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.levelDeficitMs
            },
            Column("workloadSequencePredictedDurationMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing
                    ?.workloadSequencePredictedDurationMs
            },
            Column("draftSequenceOverheadDurationMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing
                    ?.draftSequenceOverheadDurationMs
            },
            Column("draftSequenceReservedDurationMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing
                    ?.draftSequenceReservedDurationMs
            },
            Column("estimatedCompletionTimeMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.let { pacing ->
                    pacing.backlogMs + pacing.draftSequenceReservedDurationMs * PACING_WINDOW_DRAFT_COUNT
                }
            },
            Column("deadlineDeficitMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.let { pacing ->
                    pacing.backlogMs + pacing.draftSequenceReservedDurationMs * PACING_WINDOW_DRAFT_COUNT -
                        pacing.timeToDeadlineMs.coerceAtLeast(0L)
                }
            },
            Column("sharedDeficitFormulaDelayMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.let { pacing ->
                    computePacingDelayMs(
                        backlogMs = pacing.backlogMs,
                        timeToDeadlineMs = pacing.timeToDeadlineMs,
                        draftSequenceReservedDurationMs = pacing.draftSequenceReservedDurationMs,
                    )
                }
            },
            Column("sharedDeficitFormulaWouldActivate") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.let { pacing ->
                    computePacingDelayMs(
                        backlogMs = pacing.backlogMs,
                        timeToDeadlineMs = pacing.timeToDeadlineMs,
                        draftSequenceReservedDurationMs = pacing.draftSequenceReservedDurationMs,
                    ) > 0L
                }
            },
            Column("realTraceCompleteBeforeDelay") { it.beforeDelayState.traceComplete },
            Column("realBacklogMs") { it.beforeDelayState.realBacklogMs },
            Column("realQueueDepth") { it.beforeDelayState.realQueueDepth },
            Column("realOutstandingDraftCount") { it.beforeDelayState.realOutstandingDraftCount },
            Column("realBacklogAtReleaseMs") { it.atReleaseState.realBacklogMs },
            Column("realQueueDepthAtRelease") { it.atReleaseState.realQueueDepth },
        )

        private fun buildRq3PacingColumns(): List<Column<Rq3CaptureRow>> = listOf(
            Column("deviceModel") { it.deviceModel },
            Column("runId") { it.runId },
            Column("runShotIndex") { it.runShotIndex },
            Column("runShotCount") { it.runShotCount },
            Column("captureIndex") { it.capture.captureIndex },
            Column("ppSequenceId") { it.capture.metrics.ppSequenceId },
            Column("shotToShotTimeMs") { it.capture.metrics.shotToShotTimeMs },
            Column("shotToShotWithoutRecordedPacingMs") {
                val shotToShotTimeMs = it.capture.metrics.shotToShotTimeMs
                val transitionDelayMs = it.transitionDelayMs
                if (shotToShotTimeMs != null && transitionDelayMs != null) {
                    (shotToShotTimeMs - transitionDelayMs).coerceAtLeast(0L)
                } else {
                    null
                }
            },
            Column("startingOverheatLevel") { it.startingOverheatLevel },
            Column("sizeBucket") { it.sizeBucket },
            Column("isLowMemory") { it.isLowMemory },
            Column("plannedWorkloadSequenceKey") { it.capture.plannedWorkloadSequenceKey },
            Column("executedWorkloadSequenceKey") { it.capture.executedWorkloadSequenceKey },
            Column("captureTimedOut") { it.capture.hasTimeoutFailure },
            Column("captureWatchdogFailed") { it.capture.hasWatchdogFailure },
            Column("bokehRecommendedAdmit") { it.capture.bokehDecisionRow?.wasAdmitted },
            Column("bokehExecuted") { it.capture.bokehDecisionRow?.wasExecuted },
            Column("filterRecommendedAdmit") { it.capture.filterDecisionRow?.wasAdmitted },
            Column("filterExecuted") { it.capture.filterDecisionRow?.wasExecuted },
            Column("timeoutMarginMs") { it.capture.timeoutMarginMs },
            Column("") { "" },
            // The decision persisted on shot i is the gating delay already paid before that shot starts.
            Column("pacingDecisionRecorded") { it.pacingDecisionRecorded },
            Column("pacingObservationAvailable") { it.pacingObservationAvailable },
            Column("pacingObservationSource") { it.pacingObservationSource },
            Column("delayAppliesBeforeShotIndex") { it.delayAppliesBeforeShotIndex },
            Column("appliedDelayMs") { it.appliedDelayMs },
            Column("transitionDelayMs") { it.transitionDelayMs },
            Column("pacedTransition") { it.pacedTransition },
            Column("cumulativeDelayTraceComplete") { it.cumulativeDelayTraceComplete },
            Column("cumulativeTransitionDelayMs") { it.cumulativeTransitionDelayMs },
            Column("") { "" },
            Column("decisionUptimeMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.decisionUptimeMs
            },
            Column("releaseUptimeMs") { it.releaseUptimeMs },
            Column("controllerBacklogMs") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.backlogMs
            },
            Column("realTraceCompleteBeforeDelay") { it.beforeDelayState.traceComplete },
            Column("realBacklogMs") { it.beforeDelayState.realBacklogMs },
            Column("realQueueDepth") { it.beforeDelayState.realQueueDepth },
            Column("realOutstandingDraftCount") { it.beforeDelayState.realOutstandingDraftCount },
            Column("backlogEstimateErrorMs") {
                val controllerBacklogMs =
                    it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.backlogMs
                val realBacklogMs = it.beforeDelayState.realBacklogMs
                if (controllerBacklogMs != null && realBacklogMs != null) {
                    controllerBacklogMs - realBacklogMs
                } else {
                    null
                }
            },
            Column("") { "" },
            Column("realTraceCompleteAtRelease") { it.atReleaseState.traceComplete },
            Column("realBacklogAtReleaseMs") { it.atReleaseState.realBacklogMs },
            Column("realQueueDepthAtRelease") { it.atReleaseState.realQueueDepth },
            Column("realOutstandingDraftCountAtRelease") {
                it.atReleaseState.realOutstandingDraftCount
            },
            Column("") { "" },
            Column("controllerQueuedDraftCount") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.queuedDraftCount
            },
            Column("controllerDraftSequenceKey") {
                it.capture.metrics.draftSequenceMetrics?.captureAvailablePacing?.draftSequenceKey
            },
            Column("draftStartUptimeMs") { it.capture.draftStartUptimeMs },
            Column("draftEndUptimeMs") { it.capture.draftEndUptimeMs },
            Column("draftSequenceDurationMs") { it.capture.draftSequenceDurationMs },
            Column("shotOverheatLevel") {
                it.capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.overheatLevel
            },
            Column("shotThermalStatus") {
                it.capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalStatus
            },
            Column("shotThermalHeadroom") {
                it.capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalHeadroom
            },
        )

        private fun buildRq3SummaryColumns(): List<Column<Rq3RunSummary>> = listOf(
            Column("deviceModel") { it.deviceModel },
            Column("runId") { it.runId },
            Column("shotCount") { it.shotCount },
            Column("analyzedShotCount") { it.analyzedShotCount },
            Column("isComplete30ShotRun") { it.shotCount >= RQ_TARGET_SHOT_COUNT },
            Column("startingOverheatLevel") { it.startingOverheatLevel },
            Column("sizeBucket") { it.sizeBucket },
            Column("isLowMemory") { it.isLowMemory },
            Column("") { "" },
            Column("bokehDecisionCoveragePercent") { it.bokehDecisionCoveragePercent },
            Column("bokehAdmitPercent") { it.bokehAdmitPercent },
            Column("bokehExecutionPercent") { it.bokehExecutionPercent },
            Column("filterDecisionCoveragePercent") { it.filterDecisionCoveragePercent },
            Column("filterAdmitPercent") { it.filterAdmitPercent },
            Column("filterExecutionPercent") { it.filterExecutionPercent },
            Column("") { "" },
            Column("transitionCount") { it.transitionCount },
            Column("recordedPacingDecisionCount") { it.recordedPacingDecisionCount },
            Column("pacingObservationCount") { it.pacingObservationCount },
            Column("pacingDecisionCoveragePercent") { it.pacingDecisionCoveragePercent },
            Column("pacingObservationCoveragePercent") { it.pacingObservationCoveragePercent },
            Column("pacedTransitionCount") { it.pacedTransitionCount },
            Column("pacedPercent") { it.pacedPercent },
            Column("totalDelayMs") { it.totalDelayMs },
            Column("positiveDelayP50Ms") { it.positiveDelayP50Ms },
            Column("positiveDelayP95Ms") { it.positiveDelayP95Ms },
            Column("") { "" },
            Column("realTraceCoveragePercent") { it.realTraceCoveragePercent },
            Column("realBacklogMeanMs") { it.realBacklogMeanMs },
            Column("realBacklogP50Ms") { it.realBacklogP50Ms },
            Column("realBacklogP95Ms") { it.realBacklogP95Ms },
            Column("highBacklogPercent") { it.highBacklogPercent },
            Column("realQueueDepthMean") { it.realQueueDepthMean },
            Column("maxRealBacklogMs") { it.maxRealBacklogMs },
            Column("maxRealQueueDepth") { it.maxRealQueueDepth },
            Column("maxRealBacklogAtReleaseMs") { it.maxRealBacklogAtReleaseMs },
            Column("maxRealQueueDepthAtRelease") { it.maxRealQueueDepthAtRelease },
            Column("") { "" },
            Column("timeoutMarginSampleCount") { it.timeoutMarginSampleCount },
            Column("timeoutMarginCoveragePercent") { it.timeoutMarginCoveragePercent },
            Column("timeoutMarginP5Ms") { it.timeoutMarginP5Ms },
            Column("shotToShotP95Ms") { it.shotToShotP95Ms },
            Column("burstSpanMs") { it.burstSpanMs },
        )

        private fun buildPacingReplayColumns(): List<Column<EnrichedCaptureRow>> = listOf(
            Column("captureIndex") { it.row.captureIndex },
            Column("ppSequenceId") { it.row.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.row.metrics.dsMode) },
            Column("dsExtraInfo") { it.row.metrics.dsExtraInfo },
            Column("isPendingRequest") { it.row.metrics.draftSequenceMetrics?.isPendingRequest },
            Column("resultImageFormat") { it.row.metrics.resultImageFormat },
            Column("resultImageWidth") { it.row.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.row.metrics.resultImageSize.height },
            Column("sessionId") { it.sessionSummary.sessionId },
            Column("sessionCaptureIndex") { it.sessionSummary.sessionCaptureIndex },
            Column("sessionBoundarySource") { it.sessionSummary.sessionBoundarySource },
            Column("pacerSessionId") { it.row.metrics.draftSequenceMetrics?.pacerSessionId },
            Column("timeoutDeadlineUptimeMs") { it.row.metrics.timeoutTimestampMs },
            Column("firstNodeStartUptimeMs") { it.row.firstNodeStartUptimeMs },
            Column("draftStartUptimeMs") { it.row.draftStartUptimeMs },
            Column("draftEndUptimeMs") { it.row.draftEndUptimeMs },
            Column("draftSequenceDurationMs") { it.row.draftSequenceDurationMs },
            // Deadline minus draft end - the pacing outcome each counterfactual delay is scored against.
            Column("timeoutMarginMs") { it.row.timeoutMarginMs },
            Column("workloadSequenceDurationMs") { it.row.workloadSequenceDurationMs },
            Column("beforeCaptureTimedOut") { it.row.hasTimeoutFailure },
            Column("beforeCaptureWatchdogFailed") { it.row.hasWatchdogFailure },
            Column("firstNodeWorkloadKey") { it.row.nodeRows.firstOrNull()?.node?.workloadKey },
            Column("firstNodeIsLowMemory") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot?.isLowMemory
            },
            Column("firstNodeRamAvailablePercent") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot?.ramAvailablePercent
            },
            Column("firstNodeJavaHeapUsedPercent") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot?.javaHeapUsedPercent
            },
            Column("firstNodeNativeHeapAllocatedPercent") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot?.nativeHeapAllocatedPercent
            },
            Column("firstNodeOverheatLevel") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.overheatLevel
            },
            Column("firstNodeThermalStatus") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalStatus
            },
            Column("firstNodeThermalHeadroom") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalHeadroom
            },
            Column("firstNodeStorageUsedPercent") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.storageSnapshot?.storageUsedPercent
            },
            Column("beforePacingDecisionAvailable") { it.row.pacingReplay != null },
            Column("") { "" },
            Column("beforeDecisionUptimeMs") { it.row.pacingReplay?.before?.decisionUptimeMs },
            Column("beforeDecisionToFirstNodeStartMs") {
                val decisionUptimeMs = it.row.pacingReplay?.before?.decisionUptimeMs
                val firstNodeStartUptimeMs = it.row.firstNodeStartUptimeMs
                if (decisionUptimeMs == null || firstNodeStartUptimeMs == null) {
                    null
                } else {
                    firstNodeStartUptimeMs - decisionUptimeMs
                }
            },
            Column("beforeDraftSequenceKey") { it.row.pacingReplay?.before?.draftSequenceKey },
            Column("beforeDraftSequenceBudgetMs") { it.row.pacingReplay?.before?.draftSequenceBudgetMs },
            Column("beforeWorkloadSequencePredictedDurationMs") {
                it.row.pacingReplay?.before?.workloadSequencePredictedDurationMs
            },
            Column("beforeDraftSequenceOverheadDurationMs") {
                it.row.pacingReplay?.before?.draftSequenceOverheadDurationMs
            },
            Column("beforeDraftSequenceReservedDurationMs") {
                it.row.pacingReplay?.before?.draftSequenceReservedDurationMs
            },
            Column("beforeBacklogMs") { it.row.pacingReplay?.before?.backlogMs },
            Column("beforeQueuedDraftCount") { it.row.pacingReplay?.before?.queuedDraftCount },
            Column("beforeQueuedPredictedWorkMs") {
                it.row.pacingReplay?.before?.queuedPredictedWorkMs
            },
            // The runtime records the window it had left; report the spent complement used by the RQ3 aggregation.
            Column("beforeShutterToDecisionMs") { it.row.pacingReplay?.shutterToDecisionMs },
            Column("beforeTimeToDeadlineMs") { it.row.pacingReplay?.before?.timeToDeadlineMs },
            Column("beforeLevelDeficitMs") { it.row.pacingReplay?.before?.levelDeficitMs },
            Column("beforeAppliedDelayMs") { it.row.pacingReplay?.before?.appliedDelayMs },
            // Ceiling calibration: recorded per-capture ceiling minus this draft's wall time
            // (positive = ceiling too high = over-pacing pressure, negative = ceiling undershot the draft).
            Column("draftSequenceReserveErrorMs") {
                val ceilingMs = it.row.pacingReplay?.before?.draftSequenceReservedDurationMs
                val draftSequenceDurationMs = it.row.draftSequenceDurationMs
                if (ceilingMs != null && draftSequenceDurationMs != null) ceilingMs - draftSequenceDurationMs else null
            },
            // This draft's real between-node overhead (wall minus node processing) - what the clock's learned
            // overhead term is calibrated against. Compare to beforeSessionPlannedDraftOverheadMs.
            Column("overheadActualMs") {
                val draftSequenceDurationMs = it.row.draftSequenceDurationMs
                val nodeMs = it.row.workloadSequenceDurationMs
                if (draftSequenceDurationMs != null && nodeMs != null) draftSequenceDurationMs - nodeMs else null
            },
            // Learned overhead the clock added minus what this draft actually needed (positive = learned ran high).
            Column("overheadLearnedMinusActualMs") {
                val learnedMs = it.row.pacingReplay?.before?.draftSequenceOverheadDurationMs
                val draftSequenceDurationMs = it.row.draftSequenceDurationMs
                val nodeMs = it.row.workloadSequenceDurationMs
                if (learnedMs != null && draftSequenceDurationMs != null && nodeMs != null) {
                    learnedMs - (draftSequenceDurationMs - nodeMs)
                } else {
                    null
                }
            },
            // How much the node point sum ALONE under-priced this draft's real pipeline occupancy (wall minus point
            // sum) - the shortfall the overhead term exists to close. Positive = a point-only clock runs fast here,
            // which is the backlog under-pricing that compounds with queue depth into a timeout.
            Column("draftOccupancyUnderpriceMs") {
                val predMs = it.row.pacingReplay?.before?.workloadSequencePredictedDurationMs
                val draftSequenceDurationMs = it.row.draftSequenceDurationMs
                if (predMs != null && draftSequenceDurationMs != null) draftSequenceDurationMs - predMs else null
            },
            // ---- Draft-wall-time-base viability probes (see ReplayNotes "Wall-base pacing") ----
            // Drafts in flight when this capture was paced: the occupancy a wall-based clock must price but cannot yet
            // observe (their walls are only known once they finish). High values = completion-lag territory.
            Column("inFlightDraftCountAtDecision") { it.wallBase.inFlightDraftCountAtDecision },
            // The freshest whole-draft wall observable at the decision (most recently finished draft).
            Column("freshestCompletedDraftWallMs") { it.wallBase.freshestCompletedDraftWallMs },
            // This draft's actual wall minus that freshest observable wall: the completion-lag error a "use the latest
            // observed wall" clock would carry. Large positive during a throttle ramp = the observable wall is stale.
            Column("freshestWallLagErrorMs") {
                val actualMs = it.row.draftSequenceDurationMs
                val freshestMs = it.wallBase.freshestCompletedDraftWallMs
                if (actualMs != null && freshestMs != null) actualMs - freshestMs else null
            },
            // Ground-truth pipeline wait this capture actually hit (draft start minus its release = decision + applied
            // delay): the real backlog to compare against the priced beforeBacklogMs +
            // beforeShutterToDecisionMs.
            Column("realQueueWaitMs") {
                val draftStartMs = it.row.draftStartUptimeMs
                val decisionMs = it.row.pacingReplay?.before?.decisionUptimeMs
                val appliedDelayMs = it.row.pacingReplay?.before?.appliedDelayMs
                if (draftStartMs != null && decisionMs != null && appliedDelayMs != null) {
                    (draftStartMs - decisionMs - appliedDelayMs).coerceAtLeast(0L)
                } else {
                    null
                }
            },
            // Session max draft wall of THIS capture's own draft size, and over every size - the cold-size fallback.
            Column("sizeScopedObservedMaxDraftMs") { it.wallBase.sizeScopedObservedMaxDraftMs },
            Column("observedMaxDraftMs") { it.wallBase.observedMaxDraftMs },
            // How much the all-size fallback would inflate this size's ceiling (cross-size contamination): a heavy
            // MP24 draft raising an MP12 capture's reserve. Only charged while this size is cold, so a non-zero value
            // is an upper bound on the inflation, not proof of it. Clamped at 0 when this size's own max is the larger.
            Column("draftSequenceReserveCrossSizeContaminationMs") {
                val obsMaxMs = it.wallBase.observedMaxDraftMs
                val sizeScopedMs = it.wallBase.sizeScopedObservedMaxDraftMs
                if (obsMaxMs != null && sizeScopedMs != null) (obsMaxMs - sizeScopedMs).coerceAtLeast(0L) else null
            },
            Column("") { "" },
            Column("captureTimeoutMs") { it.row.pacingReplay?.captureTimeoutMs },
            Column("afterLevelDeficitMs") { it.row.pacingReplay?.afterLevelDeficitMs },
            Column("afterAppliedDelayMs") { it.row.pacingReplay?.afterAppliedDelayMs },
            Column("delayDeltaMs") { it.row.pacingReplay?.delayDeltaMs },
            Column("pacingChanged") { it.row.pacingReplay?.pacingChanged },
            Column("afterOutcomeStatus") { it.row.pacingReplay?.afterOutcomeStatus },
        )

        private fun buildReplayNotes(): List<ReplayNote> = listOf(
            ReplayNote(
                topic = "Before / After",
                note = "before columns are recorded runtime decisions; after columns are recalculated with the " +
                    "current shared admission and pacing policy functions.",
            ),
            ReplayNote(
                topic = "Admission prediction scope",
                note = "after admission reuses the recorded point prediction and upper bound. Predictor-learning " +
                    "changes require a separate sequential model replay; this sheet evaluates policy changes over " +
                    "fixed predictions.",
            ),
            ReplayNote(
                topic = "Admission session boundary",
                note = "sticky demotion is replayed over recorded runtime pacer session ids when every row has one; " +
                    "rows persisted before that field fall back to timeout-delimited proxy sessions. " +
                    "sessionBoundarySource says which grouping was used.",
            ),
            ReplayNote(
                topic = "Pacing captureAvailable latency",
                note = "the backlog deficit measures queued work against the timeout window this capture had left at " +
                    "the decision, recorded as beforeTimeToDeadlineMs. beforeShutterToDecisionMs is its complement - " +
                    "the spent part of the window, which every earlier export is keyed on - so the two always sum to " +
                    "captureTimeoutMs. On legacy rows carrying neither, a positive backlog deficit recovers the spent " +
                    "part exactly; a zero deficit only provides a min/max range, so after delay is reported as a range " +
                    "unless both bounds produce the same result.",
            ),
            ReplayNote(
                topic = "Pacing prediction scope",
                note = "after pacing reuses the recorded draft-sequence prediction, ceiling, and backlog. Changes to " +
                    "how the ceiling is derived or to backlog reconstruction require a sequential replay with " +
                    "additional raw runtime observations.",
            ),
            ReplayNote(
                topic = "Case-study pacing formula",
                note = "sharedDeficitFormulaDelayMs re-evaluates the main two-Draft shared-deficit formula on the " +
                    "recorded row inputs. It is a trace-conditioned diagnostic, and is not the active result of a " +
                    "different policy-factory arm such as a fixed-rate or controlled-delay baseline.",
            ),
            ReplayNote(
                topic = "Counterfactual outcomes",
                note = "A Full trace cannot reconstruct a factual Baseline, Admission-only, or Pacing-only outcome " +
                    "after the first policy action that differs. That action changes later arrivals, deadlines, queue " +
                    "state, thermal state, executed workloads, and predictor learning. Values replayed on the recorded " +
                    "state after that point must be labelled TRACE_CONDITIONED_ESTIMATE, never empirical timeout data.",
            ),
            ReplayNote(
                topic = "ReplayScope validity boundary",
                note = "ReplayScope emits one row per run and target arm. conditionalExactPrefixEndShot is the last " +
                    "shot before the first action divergence: min(any controlled admission skip, pacing target shot) " +
                    "for Baseline, the pacing target shot for Admission-only, and any Bokeh/Decoding/Filter/Overlay " +
                    "Watermark skip for Pacing-only. Timeout reuse is stricter: the timed-out Draft must have ended " +
                    "before conditionalFirstDivergenceUptimeMs; shot order alone cannot prove this because an earlier " +
                    "Draft may still run when a future capture is released. A target with no divergence through a " +
                    "complete 30-shot trace is action-equivalent for that horizon.",
            ),
            ReplayNote(
                topic = "Policy arm manifest",
                note = "The base exporter does not persist an admission/pacing arm label. Keep each workbook/metrics " +
                    "database to one arm and record the arm in the experiment manifest or file name. sizeBucket, " +
                    "isLowMemory, and observed startingOverheatLevel describe the recorded trace; the requested " +
                    "condition and repetition number remain operator labels. A blank ReplayScope " +
                    "sourceTraceRequirementSatisfied means its non-recorded-arm certification remains conditional " +
                    "until the operator verifies that this is a Full-runtime workbook. Conditional columns must not " +
                    "be consumed as factual arm results while certificationStatus is " +
                    "CONDITIONAL_ON_FULL_SOURCE_ASSERTION.",
            ),
            ReplayNote(
                topic = "Factual-arm publication protocol",
                note = "Trace identifiability and experimental evidence are separate. additionalRunRequired* says " +
                    "whether this particular source trace can identify the target result. " +
                    "dedicatedFactualArmRequiredForPublication remains true for every non-recorded target so a paper " +
                    "does not silently promote a trace-conditioned diagnostic to an empirical Baseline, " +
                    "Admission-only, Pacing-only, or RQ3 No-pacing arm. Every exported workbook still requires its " +
                    "external arm/condition/repetition manifest; publicationEligibleWithoutArmManifest is therefore " +
                    "always false.",
            ),
            ReplayNote(
                topic = "RQ1 factual scope",
                note = "RQ1Runs and RQ1Conditions aggregate only the recorded runtime trace. Complete runs contribute " +
                    "30 shots; a capture-timeout run may end earlier and contributes an event at its first timeout. " +
                    "A shorter run without capture timeout is INCOMPLETE and excluded from RQ1 event aggregation.",
            ),
            ReplayNote(
                topic = "RQ1 median",
                note = "RQ1Conditions onset and timeout medians are Kaplan-Meier medians. Complete no-event runs are " +
                    "right-censored at shot 30; blank means survival never fell to 0.5, not that the event happened " +
                    "after shot 30.",
            ),
            ReplayNote(
                topic = "RQ1 evidence eligibility",
                note = "An onset is aggregated only when every required decision through that onset (or through the " +
                    "observed censoring point) is present. A WATCHDOG_ONLY run remains in the audit rows but, when it " +
                    "ends before 30 without capture timeout, is excluded from capture-timeout Kaplan-Meier input.",
            ),
            ReplayNote(
                topic = "RQ1 delay aggregation",
                note = "RQ1Conditions positiveDelayP50 fields pool positive transition-delay events across eligible " +
                    "runs. Use RQ1Runs for a run-weighted alternative; do not average already aggregated percentiles.",
            ),
            ReplayNote(
                topic = "Onset indexing",
                note = "RQ1 firstAdmissionSkipShot follows the paper's M/S definition (Bokeh and/or Filter). " +
                    "ReplayScope recordedFirstAnyAdmissionSkipShot is broader because a counterfactual admission-off " +
                    "arm also restores rejected Decoding and Overlay Watermark work. " +
                    "captureAvailablePacing is persisted when a Draft consumes the decision that gated it, so " +
                    "firstPacingDelayShot is the first shot before which a positive delay was actually paid.",
            ),
            ReplayNote(
                topic = "Recommendation vs execution",
                note = "recommendedAdmit comes from the recorded admission decision. Executed means the node has a " +
                    "positive observed duration; the legacy Completed field additionally requires recommendedAdmit=true. " +
                    "They intentionally differ in forced-execution traces: use recommendation for skip onset and " +
                    "Executed for delivered-work/quality rates.",
            ),
            ReplayNote(
                topic = "Timeout deadline coverage",
                note = "Offline timeoutMarginMs and slack are computed only from the persisted raw " +
                    "timeoutTimestampMs and draftEndUptimeMs. A missing deadline stays blank; the runtime " +
                    "timeoutTimestampMsOrDefault getter is never used to synthesize an export-time deadline. P5 is " +
                    "reported only for complete deadline coverage; sample and coverage columns expose eligibility.",
            ),
            ReplayNote(
                topic = "RQ3 No-pacing source",
                note = "Publication-grade RQ3 No-pacing data must come from an actual Admission-only/NO_PACING run " +
                    "using the same admitted-workload protocol. A Full workbook supplies factual RQ3 data for its own " +
                    "arm and only exact-prefix or trace-conditioned diagnostics for No-pacing.",
            ),
            ReplayNote(
                topic = "RQ3 run boundary",
                note = "RQ3Pacing starts a new experiment run whenever ppSequenceId is less than or equal to the " +
                    "preceding value. This keeps a 30-shot trial intact even when the runtime pacer session changes " +
                    "because the Draft queue drains.",
            ),
            ReplayNote(
                topic = "RQ3 delay mapping",
                note = "the pacing decision persisted on shot i was dequeued when shot i's Draft started, so it is " +
                    "the delay on the incoming transition to shot i. transitionDelayMs is blank on shot 1, and the " +
                    "29 transitions for a 30-shot run are rows 2..30. cumulativeTransitionDelayMs on shot i includes " +
                    "all pacing cost already paid through the incoming transition to shot i. " +
                    "shotToShotWithoutRecordedPacingMs is only the measured interval minus that delay; after the " +
                    "first divergence it is not a factual no-pacing arrival trace.",
            ),
            ReplayNote(
                topic = "RQ3 pacing observation",
                note = "In a current-instrumentation run, a Draft with no consumed gating decision has factual zero " +
                    "delay (NO_GATING_DECISION_ZERO, or SESSION_BOOTSTRAP_ZERO at a session boundary). A row without " +
                    "Draft metrics and a wholly legacy run remain MISSING and make aggregates blank rather than " +
                    "silently zero.",
            ),
            ReplayNote(
                topic = "RQ3 real backlog",
                note = "realBacklogMs is max(draftEndUptimeMs) over unfinished earlier Drafts minus the pacing " +
                    "decision time. realBacklogAtReleaseMs repeats the reconstruction at decision time plus applied " +
                    "delay. Either value is blank when an earlier Draft timeline is incomplete.",
            ),
            ReplayNote(
                topic = "RQ3 queue depth",
                note = "realQueueDepth counts unfinished earlier Drafts whose draftStartUptimeMs is after the snapshot; " +
                    "realOutstandingDraftCount additionally includes a Draft already running. The AtRelease columns " +
                    "apply the same definitions after the pacing delay.",
            ),
            ReplayNote(
                topic = "RQ3 summary",
                note = "RQ3Summary reports P50 and P95 over positive transition delays using Excel PERCENTILE.INC " +
                    "interpolation. A fully observed all-zero run reports 0/0; blank means insufficient observation. " +
                    "Run-level P50/P95 are audit values, not values to average or pool for the final figure: aggregate " +
                    "the transitionDelayMs rows in RQ3Pacing. Admit percentages use recorded decision rows as the " +
                    "denominator, while execution percentages use all analyzed captures.",
            ),
            ReplayNote(
                topic = "RQ3 horizon",
                note = "RQ3Summary uses shots 1..30 and their 29 incoming transitions. RQ3Pacing remains a raw, " +
                    "internally consistent per-shot trace beyond shot 30 when an over-length run is present.",
            ),
            ReplayNote(
                topic = "RQ3 fixed-workload audit",
                note = "For a same-workload policy comparison, match executedWorkloadSequenceKey shot by shot (and " +
                    "verify the experiment manifest). Matching admission codes or aggregate admit rates alone does not " +
                    "prove that both arms executed the same work.",
            ),
            ReplayNote(
                topic = "Wall-base pacing",
                note = "columns for judging a future draft-wall-time-based clock. draftOccupancyUnderpriceMs " +
                    "(draftWall - point sum) is how much the current point clock under-prices a draft; " +
                    "sessionPlannedDraftOverheadMs vs overheadActualMs is the learned overhead's calibration. For a " +
                    "wall-based clock the blockers show up as: inFlightDraftCountAtDecision (drafts whose wall is not " +
                    "observable yet) and freshestWallLagErrorMs (this draft's wall minus the freshest one a wall-EWMA " +
                    "could see) - large during a throttle ramp means an observed-wall clock is stale exactly when it " +
                    "matters. realQueueWaitMs is the pipeline's real time-to-free to score any clock against " +
                    "(compare to beforeBacklogMs + beforeShutterToDecisionMs). draftSequenceReserveCrossSizeContaminationMs " +
                    "(observedMaxDraftMs minus sizeScopedObservedMaxDraftMs) bounds how much a heavier other-size " +
                    "draft can inflate this capture's ceiling - the mixed-size over-pacing channel left after the " +
                    "point prediction is made size-aware; the pacer charges it only while this size is still cold. " +
                    "Both maxima are reconstructed from the Draft timeline, not recorded on the decision, so a " +
                    "capture missing a draft start/end pair is invisible to them.",
            ),
        )

        private fun buildReplayNoteColumns(): List<Column<ReplayNote>> = listOf(
            Column("topic") { it.topic },
            Column("note") { it.note },
        )

        private fun buildCaptureColumns(): List<Column<EnrichedCaptureRow>> = listOf(
            Column("captureIndex") { it.row.captureIndex },
            Column("ppSequenceId") { it.row.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.row.metrics.dsMode) },
            Column("dsExtraInfo") { it.row.metrics.dsExtraInfo },
            Column("isPendingRequest") { it.row.metrics.draftSequenceMetrics?.isPendingRequest },
            Column("resultImageFormat") { it.row.metrics.resultImageFormat },
            Column("resultImageWidth") { it.row.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.row.metrics.resultImageSize.height },
            Column("resultImageFileName") { it.row.metrics.resultImageFileName },
            Column("shotToShotTimeMs") { it.row.metrics.shotToShotTimeMs },
            Column("draftSequenceNodeCount") { it.row.nodeRows.size.takeIf { nodeCount -> nodeCount > 0 } },
            Column("workloadSequenceDurationMs") {
                it.row.nodeRows.sumOf { nodeRow -> nodeRow.node.postExecutionMetrics.durationMs }
                    .takeIf { durationMs -> durationMs > 0L }
            },
            Column("firstNodeOverheatLevel") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.overheatLevel
            },
            Column("firstNodeThermalStatus") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalStatus
            },
            Column("firstNodeThermalHeadroom") {
                it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalHeadroom
            },
            Column("hasWatchdogTimeout") { it.row.metrics.draftSequenceMetrics?.hasWatchdogTimeout },
            Column("isTimeout") { it.row.metrics.draftSequenceMetrics?.isTimeout },
            Column("hasTimeoutOrWatchdogFailure") { it.row.hasTimeoutOrWatchdogFailure },
            Column("bokehAdmitted") { it.row.bokehDecisionRow?.wasAdmitted },
            Column("bokehCompleted") { it.row.bokehDecisionRow?.wasCompleted },
            Column("filterAdmitted") { it.row.filterDecisionRow?.wasAdmitted },
            Column("filterCompleted") { it.row.filterDecisionRow?.wasCompleted },
            Column("filterPreserved") { it.row.isFilterPreserved },
            Column("sequentialPolicyOutcome") { it.row.policyOutcome().label },
            Column("bokehPredictedBudgetOverrun") { it.row.bokehPredictedBudgetOverrun },
            Column("bokehObservedBudgetOverrun") { it.row.bokehObservedBudgetOverrun },
            Column("filterPredictedBudgetOverrun") { it.row.filterPredictedBudgetOverrun },
            Column("filterObservedBudgetOverrun") { it.row.filterObservedBudgetOverrun },
            Column("") { "" },
            // Capture timeline: replay anchors plus the timeout margin the pacing policy is scored against.
            Column("timeoutDeadlineUptimeMs") { it.row.metrics.timeoutTimestampMs },
            Column("draftStartUptimeMs") { it.row.draftStartUptimeMs },
            Column("draftEndUptimeMs") { it.row.draftEndUptimeMs },
            Column("draftSequenceDurationMs") { it.row.draftSequenceDurationMs },
            Column("timeoutMarginMs") { it.row.timeoutMarginMs },
            Column("pacerSessionId") { it.row.metrics.draftSequenceMetrics?.pacerSessionId },
            Column("") { "" },
            Column("sessionId") { it.sessionSummary.sessionId },
            Column("totalShotCount") { "#" + it.sessionSummary.sessionShotCount },
            Column("timeoutShotCount") { it.sessionSummary.sessionTimeoutShotCount?.let { count -> "#" + count } },
            Column("bokehAdmitCount") { it.sessionSummary.sessionBokehAdmitCount },
            Column("bokehTotalCount") { it.sessionSummary.sessionBokehTotalCount },
            Column("bokehAdmitRate") { it.sessionSummary.sessionBokehAdmitRate },
            Column("filterAdmitCount") { it.sessionSummary.sessionFilterAdmitCount },
            Column("filterTotalCount") { it.sessionSummary.sessionFilterTotalCount },
            Column("filterAdmitRate") { it.sessionSummary.sessionFilterAdmitRate },
        )

        private fun buildNodeColumns(): List<Column<NodeSheetRow>> = listOf(
            Column("captureIndex") { it.capture.captureIndex },
            Column("nodeOrder") { it.nodeOrder },
            Column("nodeName") { it.nodeRow.node.nodeName },
            Column("workloadKey") { it.nodeRow.node.workloadKey },
            Column("isLowMemory") { it.nodeRow.node.preExecutionMetrics.memorySnapshot.isLowMemory },
            Column("ramAvailablePercent") { it.nodeRow.node.preExecutionMetrics.memorySnapshot.ramAvailablePercent },
            Column("javaHeapUsedPercent") { it.nodeRow.node.preExecutionMetrics.memorySnapshot.javaHeapUsedPercent },
            Column("nativeHeapAllocatedPercent") { it.nodeRow.node.preExecutionMetrics.memorySnapshot.nativeHeapAllocatedPercent },
            Column("overheatLevel") { it.nodeRow.node.preExecutionMetrics.thermalSnapshot.overheatLevel },
            Column("thermalStatus") { it.nodeRow.node.preExecutionMetrics.thermalSnapshot.thermalStatus },
            Column("thermalHeadroom") { it.nodeRow.node.preExecutionMetrics.thermalSnapshot.thermalHeadroom },
            Column("storageUsedPercent") { it.nodeRow.node.preExecutionMetrics.storageSnapshot.storageUsedPercent },
            Column("cpuTimeMs") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.cpuTimeMs },
            Column("wallTimeMs") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.wallTimeMs },
            Column("runQueueWaitMs") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.runqueueWaitMs },
            Column("cpuUtilizationRatio") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.cpuUtilizationRatio },
            Column("nonvoluntaryCtxSwitches") { it.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.nonvoluntaryCtxSwitches },
            Column("blockingGcCount") { it.nodeRow.node.postExecutionMetrics.gcSnapshot?.blockingGcCount },
            Column("blockingGcTimeMs") { it.nodeRow.node.postExecutionMetrics.gcSnapshot?.blockingGcTimeMs },
            Column("") { "" },
            Column("budgetMs") { it.nodeRow.node.preExecutionMetrics.budgetMs },
            Column("admit") { it.nodeRow.prediction?.admit },
            Column("admissionSkipReason") { it.admissionSkipReason() },
            Column("admissionMarginMs") { sheetRow ->
                sheetRow.nodeRow.prediction?.let { prediction ->
                    sheetRow.nodeRow.node.preExecutionMetrics.budgetMs - prediction.sequencePredictedUpperBoundMs
                }
            },
            Column("nodeStartUptimeMs") { it.nodeRow.node.startUptimeMs },
            Column("durationMs") { it.nodeRow.nodeActualDurationMs },
            Column("watchdogTimeoutMs") { it.nodeRow.node.watchdogTimeoutMs },
            Column("watchdogTimedOut") { it.nodeRow.node.watchdogTimedOut },
            Column("admissionStage") { it.admissionStage() },
            Column("decisionOutcome") { it.decisionOutcomeLabel() },
            Column("decisionObservationStatus") { it.observationStatus() },
            Column("observedActualFeasible") { it.observedActualFeasible() },
            Column("") { "" },
            Column("workloadSequenceKey") { it.nodeRow.prediction?.workloadSequenceKey },
            Column("sequencePredictedDurationMs") { it.nodeRow.prediction?.sequencePredictedDurationMs },
            Column("sequencePredictedUpperBoundMs") { it.nodeRow.prediction?.sequencePredictedUpperBoundMs },
            Column("sequenceActualDurationMs") { it.nodeRow.sequenceActualDurationMs },
            Column("sequencePredictionResidualMs") { it.nodeRow.sequencePredictionResidualMs() },
            Column("sequenceUpperBoundSlackMs") { it.nodeRow.sequenceUpperBoundSlackMs() },
            Column("sequenceUpperBoundMiss") { it.sequenceUpperBoundMiss() },
        )

    }

}
