package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.Build
import com.samsung.android.camera.core2.container.DynamicShotMode
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.watermark.Watermark.WatermarkType
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
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
         * ceiling uses beforeObservedMaxDraftMs (max over all sizes); the gap between the two is the cross-size
         * contamination a heavy other-size draft (e.g. MP24) adds to this size's (e.g. MP12) reserve.
         */
        val sizeScopedObservedMaxDraftMs: Long?,
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

    /** Per-shot RQ3 row. The delay recorded on shot i gates the transition to shot i+1. */
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
        val delayAppliesBeforeShotIndex: Int?,
        val appliedDelayMs: Long,
        val transitionDelayMs: Long?,
        val pacedTransition: Boolean?,
        val cumulativeTransitionDelayMs: Long,
        val releaseUptimeMs: Long?,
        val beforeDelayState: Rq3QueueState,
        val atReleaseState: Rq3QueueState,
    )

    /** One row per ppSequenceId-reset-delimited experiment run for direct RQ3 table aggregation. */
    private class Rq3RunSummary(
        val deviceModel: String,
        val runId: Int,
        val shotCount: Int,
        val startingOverheatLevel: Int?,
        val sizeBucket: String?,
        val isLowMemory: Boolean?,
        val transitionCount: Int,
        val recordedPacingDecisionCount: Int,
        val pacingDecisionCoveragePercent: Double?,
        val pacedTransitionCount: Int,
        val pacedPercent: Double?,
        val totalDelayMs: Long,
        val positiveDelayP50Ms: Double?,
        val positiveDelayP95Ms: Double?,
        val realTraceCoveragePercent: Double?,
        val maxRealBacklogMs: Long?,
        val maxRealBacklogAtReleaseMs: Long?,
        val maxRealQueueDepth: Int?,
        val maxRealQueueDepthAtRelease: Int?,
    )

    private class Rq3Export(
        val captures: List<Rq3CaptureRow>,
        val summaries: List<Rq3RunSummary>,
    )

    /** First classified node's size bucket (MP12/MP24/...) - the draft's working resolution. */
    private fun draftSizeBucketOf(cap: CaptureRow): String? =
        cap.nodeRows.firstOrNull()?.node?.workloadKey
            ?.let { key -> Regex("sizeBucket=([A-Za-z0-9]+)").find(key)?.groupValues?.get(1) }

    /** Per capture: in-flight count, freshest completed wall, and same-size observed max as of its pacing decision. */
    private fun computeWallBaseDiagnostics(group: List<CaptureRow>, member: CaptureRow): WallBaseDiagnostics {
        val decisionMs = member.pacingReplay?.before?.decisionUptimeMs ?: member.draftStartUptimeMs
            ?: return WallBaseDiagnostics(null, null, null)
        val memberSize = draftSizeBucketOf(member)
        // The pacer's observed max resets when the pipeline drains (a new pacer session), so scope this to the same
        // pacer session to stay comparable to beforeObservedMaxDraftMs. In-flight/freshest are physical pipeline
        // facts, so they stay session-agnostic.
        val memberPacerSession = member.metrics.draftSequenceMetrics?.pacerSessionId
        var inFlight = 0
        var freshestEndMs = Long.MIN_VALUE
        var freshestWallMs: Long? = null
        var sizeScopedMaxMs: Long? = null
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
                freshestWallMs = other.draftWallMs
            }
            val sameSize = memberSize != null && draftSizeBucketOf(other) == memberSize
            val samePacerSession = other.metrics.draftSequenceMetrics?.pacerSessionId == memberPacerSession
            if (endMs <= decisionMs && sameSize && samePacerSession) {
                val wallMs = other.draftWallMs
                if (wallMs != null && (sizeScopedMaxMs == null || wallMs > sizeScopedMaxMs)) {
                    sizeScopedMaxMs = wallMs
                }
            }
        }
        return WallBaseDiagnostics(inFlight, freshestWallMs, sizeScopedMaxMs)
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
            val runRows = mutableListOf<Rq3CaptureRow>()

            run.forEachIndexed { shotOffset, capture ->
                val shotIndex = shotOffset + 1
                val pacing = capture.metrics.draftSequenceMetrics?.captureAvailablePacing
                val appliedDelayMs = pacing?.appliedDelayMs ?: 0L
                val delayAppliesBeforeShotIndex = (shotIndex + 1).takeIf { nextShot -> nextShot <= run.size }
                val transitionDelayMs = appliedDelayMs.takeIf { delayAppliesBeforeShotIndex != null }
                if (transitionDelayMs != null) {
                    cumulativeTransitionDelayMs += transitionDelayMs
                }

                val decisionUptimeMs = pacing?.decisionUptimeMs
                val releaseUptimeMs = decisionUptimeMs?.plus(appliedDelayMs)
                val earlierCaptures = run.take(shotOffset)
                val firstShotWithoutDecision = shotOffset == 0 && decisionUptimeMs == null
                val beforeDelayState = if (firstShotWithoutDecision) {
                    Rq3QueueState(true, 0L, 0, 0)
                } else {
                    computeRq3QueueState(earlierCaptures, decisionUptimeMs)
                }
                val atReleaseState = if (firstShotWithoutDecision) {
                    Rq3QueueState(true, 0L, 0, 0)
                } else {
                    computeRq3QueueState(earlierCaptures, releaseUptimeMs)
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
                        delayAppliesBeforeShotIndex = delayAppliesBeforeShotIndex,
                        appliedDelayMs = appliedDelayMs,
                        transitionDelayMs = transitionDelayMs,
                        pacedTransition = transitionDelayMs?.let { delayMs -> delayMs > 0L },
                        cumulativeTransitionDelayMs = cumulativeTransitionDelayMs,
                        releaseUptimeMs = releaseUptimeMs,
                        beforeDelayState = beforeDelayState,
                        atReleaseState = atReleaseState,
                    )
                )
            }

            val transitionRows = runRows.filter { row -> row.transitionDelayMs != null }
            val positiveDelays = transitionRows.mapNotNull { row ->
                row.transitionDelayMs?.takeIf { delayMs -> delayMs > 0L }
            }
            val pacedTransitionCount = positiveDelays.size
            val recordedPacingDecisionCount = transitionRows.count { row -> row.pacingDecisionRecorded }
            val realTraceRows = runRows.count { row -> row.beforeDelayState.realBacklogMs != null }
            val beforeDelayTraceComplete = runRows.all { row -> row.beforeDelayState.traceComplete }
            val atReleaseTraceComplete = runRows.all { row -> row.atReleaseState.traceComplete }
            summaries.add(
                Rq3RunSummary(
                    deviceModel = Build.MODEL,
                    runId = runId,
                    shotCount = run.size,
                    startingOverheatLevel = startingOverheatLevel,
                    sizeBucket = sizeBucket,
                    isLowMemory = isLowMemory,
                    transitionCount = transitionRows.size,
                    recordedPacingDecisionCount = recordedPacingDecisionCount,
                    pacingDecisionCoveragePercent = percent(recordedPacingDecisionCount, transitionRows.size),
                    pacedTransitionCount = pacedTransitionCount,
                    pacedPercent = percent(pacedTransitionCount, transitionRows.size),
                    totalDelayMs = transitionRows.sumOf { row -> row.transitionDelayMs ?: 0L },
                    positiveDelayP50Ms = inclusivePercentile(positiveDelays, 0.50),
                    positiveDelayP95Ms = inclusivePercentile(positiveDelays, 0.95),
                    realTraceCoveragePercent = percent(realTraceRows, runRows.size),
                    maxRealBacklogMs = if (beforeDelayTraceComplete) {
                        runRows.mapNotNull { row -> row.beforeDelayState.realBacklogMs }.maxOrNull()
                    } else {
                        null
                    },
                    maxRealBacklogAtReleaseMs = if (atReleaseTraceComplete) {
                        runRows.mapNotNull { row -> row.atReleaseState.realBacklogMs }.maxOrNull()
                    } else {
                        null
                    },
                    maxRealQueueDepth = if (beforeDelayTraceComplete) {
                        runRows.mapNotNull { row -> row.beforeDelayState.realQueueDepth }.maxOrNull()
                    } else {
                        null
                    },
                    maxRealQueueDepthAtRelease = if (atReleaseTraceComplete) {
                        runRows.mapNotNull { row -> row.atReleaseState.realQueueDepth }.maxOrNull()
                    } else {
                        null
                    },
                )
            )
            captureRows.addAll(runRows)
        }

        return Rq3Export(captureRows, summaries)
    }

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

    /** Excel PERCENTILE.INC-compatible interpolation over positive applied delays. */
    private fun inclusivePercentile(values: List<Long>, quantile: Double): Double? {
        if (values.isEmpty()) {
            return null
        }

        val sorted = values.sorted()
        val rank = (sorted.size - 1) * quantile.coerceIn(0.0, 1.0)
        val lowerIndex = floor(rank).toInt()
        val upperIndex = ceil(rank).toInt()
        if (lowerIndex == upperIndex) {
            return sorted[lowerIndex].toDouble()
        }

        val fraction = rank - lowerIndex
        return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * fraction
    }

    suspend fun export(): File {
        val outputDir = context.getExternalFilesDir(DIR_NAME)
            ?: throw IllegalStateException("External files dir is unavailable")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val outputFile = File(outputDir, FILE_NAME)

        val metricsList = repository.getAll()

        XSSFWorkbook().use { workbook ->
            val styles = Styles(workbook)

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

            writeSheet(workbook, styles, "AdmissionReplay", admissionReplayRows, buildAdmissionReplayColumns())
            writeSheet(workbook, styles, "PacingReplay", replayCaptures, buildPacingReplayColumns())
            writeSheet(workbook, styles, "RQ3Pacing", rq3Export.captures, buildRq3PacingColumns())
            writeSheet(workbook, styles, "RQ3Summary", rq3Export.summaries, buildRq3SummaryColumns())
            writeSheet(workbook, styles, "ReplayNotes", buildReplayNotes(), buildReplayNoteColumns())

            // Write main sheet
            writeSheet(workbook, styles, "Capture", enrichedNormalCaptures, buildCaptureColumns())

            // Write sub-sheets for each category
            generateSubSheets(workbook, styles, enrichedNormalCaptures, "")

            FileOutputStream(outputFile).use { workbook.write(it) }
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
                    sequencePredictedMs = prediction.sequencePredictedDurationMs,
                    sequenceUpperBoundMs = prediction.sequencePredictedUpperBoundMs,
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
        workbook: Workbook,
        styles: Styles,
        captures: List<EnrichedCaptureRow>,
        sheetNamePrefix: String,
    ) {
        val nodeRowsByNodeName = nodeSheetRows(captures)
            .groupBy { it.nodeRow.node.nodeName }

        nodeRowsByNodeName.toSortedMap().forEach { (nodeName, rows) ->
            val sheetName = uniqueSheetName(workbook, "$sheetNamePrefix$nodeName")
            val nodeColumns = buildNodeColumns()
            writeSheet(workbook, styles, sheetName, rows, nodeColumns)
        }
    }

    private fun <T> writeSheet(
        workbook: Workbook,
        styles: Styles,
        sheetName: String,
        items: List<T>,
        columns: List<Column<T>>,
    ) {
        val sheet = workbook.createSheet(sheetName)

        val headerRow = sheet.createRow(0)
        columns.forEachIndexed { index, column ->
            headerRow.createCell(index).setCellValue(column.title)
        }

        items.forEachIndexed { rowIndex, item ->
            val row = sheet.createRow(sheet.lastRowNum + 1)
            columns.forEachIndexed { colIndex, column ->
                val cell = row.createCell(colIndex)
                val value = column.extractor(item)
                styles.styleFor(column.title, value)?.let { cell.cellStyle = it }
                setCellValue(cell, value)
            }
        }
    }

    private fun setCellValue(cell: Cell, value: Any?) {
        when (value) {
            is Number -> cell.setCellValue(value.toDouble())
            is Boolean -> cell.setCellValue(value)
            null -> cell.setCellValue("")
            else -> cell.setCellValue(value.toString())
        }
    }

    /** Excel sheet names must be <=31 chars, unique, and exclude : \ / ? * [ ]. */
    private fun uniqueSheetName(workbook: Workbook, rawNodeName: String): String {
        val base = rawNodeName.replace(Regex("[:\\\\/?*\\[\\]]"), "_")
            .take(MAX_SHEET_NAME_LENGTH)

        if (workbook.getSheet(base) == null) {
            return base
        }

        var suffix = 2
        while (true) {
            val candidate = base.take(MAX_SHEET_NAME_LENGTH - 2) + "_" + suffix
            if (workbook.getSheet(candidate) == null) {
                return candidate
            }
            suffix++
        }
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
        val draftWallMs: Long?
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

        val draftSequenceDurationMs: Long?
            get() = nodeRows.sumOf { row -> row.node.postExecutionMetrics.durationMs }
                .takeIf { durationMs -> durationMs > 0L }

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
            get() = filterDecisionRow?.wasAdmitted == true

        val isBokehExecuted: Boolean
            get() = bokehDecisionRow?.wasAdmitted == true

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
        private val backlogBaseWithoutShutterElapsedMs =
            before.backlogMs + before.draftSequencePacingDurationMs - captureTimeoutMs

        val inferredShutterElapsedMs: Long? = if (before.backlogDeficitMs > 0L) {
            (before.backlogDeficitMs - ceil(backlogBaseWithoutShutterElapsedMs).toLong()).coerceAtLeast(0L)
        } else {
            null
        }

        /** Recorded runtime input wins; inference only covers rows persisted before the field existed. */
        private val knownShutterElapsedMs: Long? =
            before.shutterElapsedMs ?: inferredShutterElapsedMs
        val shutterElapsedMinMs: Long = knownShutterElapsedMs ?: 0L
        val shutterElapsedMaxMs: Long = knownShutterElapsedMs ?: floor(
            (captureTimeoutMs - before.backlogMs - before.draftSequencePacingDurationMs).coerceAtLeast(0.0),
        ).toLong()
        val shutterElapsedInference: String = when {
            before.shutterElapsedMs != null -> PACING_SHUTTER_ELAPSED_RECORDED
            inferredShutterElapsedMs != null -> PACING_SHUTTER_ELAPSED_EXACT
            else -> PACING_SHUTTER_ELAPSED_BOUNDED
        }

        val afterLevelDeficitMs: Long = computeCaptureAvailableLevelDeficitMs(
            draftSequenceBudgetMs = before.draftSequenceBudgetMs,
            draftSequencePacingDurationMs = before.draftSequencePacingDurationMs,
        )
        val afterBacklogDeficitMinMs: Long = computeCaptureAvailableBacklogDeficitMs(
            backlogMs = before.backlogMs,
            shutterElapsedMs = shutterElapsedMinMs,
            draftSequencePacingDurationMs = before.draftSequencePacingDurationMs,
        )
        val afterBacklogDeficitMaxMs: Long = computeCaptureAvailableBacklogDeficitMs(
            backlogMs = before.backlogMs,
            shutterElapsedMs = shutterElapsedMaxMs,
            draftSequencePacingDurationMs = before.draftSequencePacingDurationMs,
        )
        val afterBacklogDeficitMs: Long? = afterBacklogDeficitMinMs.takeIf { minimum ->
            minimum == afterBacklogDeficitMaxMs
        }
        val afterAppliedDelayMinMs: Long = maxOf(
            afterLevelDeficitMs,
            afterBacklogDeficitMinMs,
        )
        val afterAppliedDelayMaxMs: Long = maxOf(
            afterLevelDeficitMs,
            afterBacklogDeficitMaxMs,
        )
        val afterAppliedDelayMs: Long? = afterAppliedDelayMinMs.takeIf { minimum ->
            minimum == afterAppliedDelayMaxMs
        }
        val delayDeltaMs: Long? = afterAppliedDelayMs?.minus(before.appliedDelayMs)
        val pacingChanged: Boolean? = afterAppliedDelayMs?.let { delayMs -> delayMs != before.appliedDelayMs }
        val replayStatus: String = when {
            knownShutterElapsedMs != null -> PACING_REPLAY_EXACT
            afterAppliedDelayMs != null -> PACING_REPLAY_BOUNDED_DETERMINISTIC
            else -> PACING_REPLAY_BOUNDED
        }

        val beforeDominantDeficit: String = dominantDeficit(before.levelDeficitMs, before.backlogDeficitMs)
        val afterDominantDeficit: String? = afterBacklogDeficitMs?.let { backlogDeficitMs ->
            dominantDeficit(afterLevelDeficitMs, backlogDeficitMs)
        }
        val afterOutcomeStatus: String = when (pacingChanged) {
            false -> PACING_OUTCOME_RECORDED_REUSABLE
            true -> PACING_OUTCOME_REQUIRES_REPLAY
            null -> PACING_OUTCOME_BOUNDED
        }

        private fun dominantDeficit(levelDeficitMs: Long, backlogDeficitMs: Long): String {
            return when {
                levelDeficitMs <= 0L && backlogDeficitMs <= 0L -> PACING_DOMINANT_NONE
                levelDeficitMs == backlogDeficitMs -> PACING_DOMINANT_EQUAL
                levelDeficitMs > backlogDeficitMs -> PACING_DOMINANT_LEVEL
                else -> PACING_DOMINANT_BACKLOG
            }
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

        val wasCompleted: Boolean
            get() = prediction?.admit == true && nodeActualDurationMs != null

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

    /** Cell number formats keyed by column-title suffix. */
    private class Styles(workbook: Workbook) {
        private val dataFormat = workbook.createDataFormat()
        private val msStyle: CellStyle = workbook.createCellStyle().apply {
            dataFormat = this@Styles.dataFormat.getFormat("0\" ms\"")
        }
        private val percentStyle: CellStyle = workbook.createCellStyle().apply {
            dataFormat = this@Styles.dataFormat.getFormat("0.0\"%\"")
        }
        private val rateStyle: CellStyle = workbook.createCellStyle().apply {
            dataFormat = this@Styles.dataFormat.getFormat("0.0%")
        }

        fun styleFor(columnTitle: String, value: Any?): CellStyle? {
            if (value !is Number) {
                return null
            }
            return when {
                columnTitle.endsWith("Ms", ignoreCase = true) -> msStyle
                columnTitle.endsWith("Percent", ignoreCase = true) -> percentStyle
                columnTitle.endsWith("Rate", ignoreCase = true) -> rateStyle
                else -> null
            }
        }
    }

    private companion object {
        private const val DIR_NAME = "metrics"
        private val FILE_NAME = "${Build.MODEL}_metrics.xlsx"
        private const val MAX_SHEET_NAME_LENGTH = 31
        private const val RQ3_TARGET_SHOT_COUNT = 30
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
        private const val PACING_SHUTTER_ELAPSED_RECORDED = "Recorded runtime input"
        private const val PACING_SHUTTER_ELAPSED_EXACT = "Exact from positive backlog deficit"
        private const val PACING_SHUTTER_ELAPSED_BOUNDED = "Bounded because backlog deficit was zero"
        private const val PACING_REPLAY_EXACT = "Exact"
        private const val PACING_REPLAY_BOUNDED_DETERMINISTIC = "Bounded input, deterministic delay"
        private const val PACING_REPLAY_BOUNDED = "Bounded delay"
        private const val PACING_DOMINANT_NONE = "None"
        private const val PACING_DOMINANT_EQUAL = "Level = Backlog"
        private const val PACING_DOMINANT_LEVEL = "Level"
        private const val PACING_DOMINANT_BACKLOG = "Backlog"
        private const val PACING_OUTCOME_RECORDED_REUSABLE = "Recorded outcome reusable"
        private const val PACING_OUTCOME_REQUIRES_REPLAY = "Changed delay requires offline replay"
        private const val PACING_OUTCOME_BOUNDED = "Delay range requires offline replay"

        private fun nodeSheetRows(captures: List<EnrichedCaptureRow>): List<NodeSheetRow> {
            return captures.flatMap { capture ->
                capture.row.nodeRows.mapIndexed { index, nodeRow ->
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

        private fun buildRq3PacingColumns(): List<Column<Rq3CaptureRow>> = listOf(
            Column("deviceModel") { it.deviceModel },
            Column("runId") { it.runId },
            Column("runShotIndex") { it.runShotIndex },
            Column("runShotCount") { it.runShotCount },
            Column("captureIndex") { it.capture.captureIndex },
            Column("ppSequenceId") { it.capture.metrics.ppSequenceId },
            Column("shotToShotTimeMs") { it.capture.metrics.shotToShotTimeMs },
            Column("startingOverheatLevel") { it.startingOverheatLevel },
            Column("sizeBucket") { it.sizeBucket },
            Column("isLowMemory") { it.isLowMemory },
            Column("captureTimedOut") { it.capture.hasTimeoutFailure },
            Column("captureWatchdogFailed") { it.capture.hasWatchdogFailure },
            Column("") { "" },
            // Delay on shot i gates the transition to shot i+1; the final shot therefore has no transition delay.
            Column("pacingDecisionRecorded") { it.pacingDecisionRecorded },
            Column("delayAppliesBeforeShotIndex") { it.delayAppliesBeforeShotIndex },
            Column("appliedDelayMs") { it.appliedDelayMs },
            Column("transitionDelayMs") { it.transitionDelayMs },
            Column("pacedTransition") { it.pacedTransition },
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
            Column("draftStartUptimeMs") { it.capture.draftStartUptimeMs },
            Column("draftEndUptimeMs") { it.capture.draftEndUptimeMs },
            Column("draftWallMs") { it.capture.draftWallMs },
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
            Column("isComplete30ShotRun") { it.shotCount == RQ3_TARGET_SHOT_COUNT },
            Column("startingOverheatLevel") { it.startingOverheatLevel },
            Column("sizeBucket") { it.sizeBucket },
            Column("isLowMemory") { it.isLowMemory },
            Column("") { "" },
            Column("transitionCount") { it.transitionCount },
            Column("recordedPacingDecisionCount") { it.recordedPacingDecisionCount },
            Column("pacingDecisionCoveragePercent") { it.pacingDecisionCoveragePercent },
            Column("pacedTransitionCount") { it.pacedTransitionCount },
            Column("pacedPercent") { it.pacedPercent },
            Column("totalDelayMs") { it.totalDelayMs },
            Column("positiveDelayP50Ms") { it.positiveDelayP50Ms },
            Column("positiveDelayP95Ms") { it.positiveDelayP95Ms },
            Column("") { "" },
            Column("realTraceCoveragePercent") { it.realTraceCoveragePercent },
            Column("maxRealBacklogMs") { it.maxRealBacklogMs },
            Column("maxRealQueueDepth") { it.maxRealQueueDepth },
            Column("maxRealBacklogAtReleaseMs") { it.maxRealBacklogAtReleaseMs },
            Column("maxRealQueueDepthAtRelease") { it.maxRealQueueDepthAtRelease },
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
            Column("draftWallMs") { it.row.draftWallMs },
            // Deadline minus draft end - the pacing outcome each counterfactual delay is scored against.
            Column("timeoutMarginMs") { it.row.timeoutMarginMs },
            Column("beforeDraftSequenceDurationMs") { it.row.draftSequenceDurationMs },
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
            Column("beforeMaxDraftSequenceDurationMs") {
                it.row.pacingReplay?.before?.maxDraftSequenceDurationMs
            },
            Column("beforeDraftSequencePredictedDurationMs") {
                it.row.pacingReplay?.before?.draftSequencePredictedDurationMs
            },
            Column("beforeDraftSequenceOverheadDurationMs") {
                it.row.pacingReplay?.before?.draftSequenceOverheadDurationMs
            },
            Column("beforeDraftSequencePacingDurationMs") {
                it.row.pacingReplay?.before?.draftSequencePacingDurationMs
            },
            Column("beforeBacklogMs") { it.row.pacingReplay?.before?.backlogMs },
            Column("beforeQueuedDraftCount") { it.row.pacingReplay?.before?.queuedDraftCount },
            Column("beforeQueuedPredictedWorkMs") {
                it.row.pacingReplay?.before?.queuedPredictedWorkMs
            },
            Column("beforeShutterElapsedMs") { it.row.pacingReplay?.before?.shutterElapsedMs },
            Column("beforeMaxDraftSequenceDurationMs") { it.row.pacingReplay?.before?.maxDraftSequenceDurationMs },
            Column("beforeLevelDeficitMs") { it.row.pacingReplay?.before?.levelDeficitMs },
            Column("beforeBacklogDeficitMs") { it.row.pacingReplay?.before?.backlogDeficitMs },
            Column("beforeDominantDeficit") { it.row.pacingReplay?.beforeDominantDeficit },
            Column("beforeAppliedDelayMs") { it.row.pacingReplay?.before?.appliedDelayMs },
            // Ceiling calibration: recorded per-capture ceiling minus this draft's wall time
            // (positive = ceiling too high = over-pacing pressure, negative = ceiling undershot the draft).
            Column("draftSequencePacingErrorMs") {
                val ceilingMs = it.row.pacingReplay?.before?.draftSequencePacingDurationMs
                val draftWallMs = it.row.draftWallMs
                if (ceilingMs != null && draftWallMs != null) ceilingMs - draftWallMs else null
            },
            // This draft's real between-node overhead (wall minus node processing) - what the clock's learned
            // overhead term is calibrated against. Compare to beforeSessionPlannedDraftOverheadMs.
            Column("overheadActualMs") {
                val draftWallMs = it.row.draftWallMs
                val nodeMs = it.row.draftSequenceDurationMs
                if (draftWallMs != null && nodeMs != null) draftWallMs - nodeMs else null
            },
            // Learned overhead the clock added minus what this draft actually needed (positive = learned ran high).
            Column("overheadLearnedMinusActualMs") {
                val learnedMs = it.row.pacingReplay?.before?.draftSequenceOverheadDurationMs
                val draftWallMs = it.row.draftWallMs
                val nodeMs = it.row.draftSequenceDurationMs
                if (learnedMs != null && draftWallMs != null && nodeMs != null) {
                    learnedMs - (draftWallMs - nodeMs)
                } else {
                    null
                }
            },
            // How much the node point sum ALONE under-priced this draft's real pipeline occupancy (wall minus point
            // sum) - the shortfall the overhead term exists to close. Positive = a point-only clock runs fast here,
            // which is the backlog under-pricing that compounds with queue depth into a timeout.
            Column("draftOccupancyUnderpriceMs") {
                val predMs = it.row.pacingReplay?.before?.draftSequencePredictedDurationMs
                val draftWallMs = it.row.draftWallMs
                if (predMs != null && draftWallMs != null) draftWallMs - predMs else null
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
                val actualMs = it.row.draftWallMs
                val freshestMs = it.wallBase.freshestCompletedDraftWallMs
                if (actualMs != null && freshestMs != null) actualMs - freshestMs else null
            },
            // Ground-truth pipeline wait this capture actually hit (draft start minus its release = decision + applied
            // delay): the real backlog to compare against the priced beforeBacklogMs +
            // beforeShutterElapsedMs.
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
            // Session max draft wall of THIS capture's own draft size, vs the pacer's size-agnostic observed max.
            Column("sizeScopedObservedMaxDraftMs") { it.wallBase.sizeScopedObservedMaxDraftMs },
            // How much the size-agnostic observed max inflates this size's ceiling (cross-size contamination): a heavy
            // MP24 draft raising an MP12 capture's reserve. Clamped at 0 (when obsMax is below this size's own max, or
            // freshly reset to 0, the ceiling is not cross-size inflated).
            Column("draftSequencePacingCrossSizeContaminationMs") {
                val obsMaxMs = it.row.pacingReplay?.before?.maxDraftSequenceDurationMs
                val sizeScopedMs = it.wallBase.sizeScopedObservedMaxDraftMs
                if (obsMaxMs != null && sizeScopedMs != null) (obsMaxMs - sizeScopedMs).coerceAtLeast(0L) else null
            },
            Column("") { "" },
            Column("captureTimeoutMs") { it.row.pacingReplay?.captureTimeoutMs },
            Column("shutterElapsedInference") { it.row.pacingReplay?.shutterElapsedInference },
            Column("inferredShutterElapsedMs") { it.row.pacingReplay?.inferredShutterElapsedMs },
            Column("shutterElapsedMinMs") { it.row.pacingReplay?.shutterElapsedMinMs },
            Column("shutterElapsedMaxMs") { it.row.pacingReplay?.shutterElapsedMaxMs },
            Column("afterReplayStatus") { it.row.pacingReplay?.replayStatus },
            Column("") { "" },
            Column("afterLevelDeficitMs") { it.row.pacingReplay?.afterLevelDeficitMs },
            Column("afterBacklogDeficitMs") { it.row.pacingReplay?.afterBacklogDeficitMs },
            Column("afterBacklogDeficitMinMs") { it.row.pacingReplay?.afterBacklogDeficitMinMs },
            Column("afterBacklogDeficitMaxMs") { it.row.pacingReplay?.afterBacklogDeficitMaxMs },
            Column("afterDominantDeficit") { it.row.pacingReplay?.afterDominantDeficit },
            Column("afterAppliedDelayMs") { it.row.pacingReplay?.afterAppliedDelayMs },
            Column("afterAppliedDelayMinMs") { it.row.pacingReplay?.afterAppliedDelayMinMs },
            Column("afterAppliedDelayMaxMs") { it.row.pacingReplay?.afterAppliedDelayMaxMs },
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
                note = "the backlog deficit's pre-draft term is this capture's own onShutter-to-decision elapsed " +
                    "(the timeout deadline is stamped at onShutter), read from the recorded runtime input " +
                    "(beforeShutterElapsedMs) when present. " +
                    "On legacy rows without it, a positive backlog deficit recovers it exactly; a zero " +
                    "deficit only provides a min/max range, so after delay is reported as a range unless both bounds " +
                    "produce the same result.",
            ),
            ReplayNote(
                topic = "Pacing prediction scope",
                note = "after pacing reuses the recorded draft-sequence prediction, ceiling, and backlog. Changes to " +
                    "how the ceiling is derived or to backlog reconstruction require a sequential replay with " +
                    "additional raw runtime observations.",
            ),
            ReplayNote(
                topic = "Counterfactual outcomes",
                note = "a changed decision whose workload was not observed online requires offline replay or shadow " +
                    "execution. afterOutcomeStatus and afterObservationStatus identify those rows.",
            ),
            ReplayNote(
                topic = "RQ3 run boundary",
                note = "RQ3Pacing starts a new experiment run whenever ppSequenceId is less than or equal to the " +
                    "preceding value. This keeps a 30-shot trial intact even when the runtime pacer session changes " +
                    "because the Draft queue drains.",
            ),
            ReplayNote(
                topic = "RQ3 delay mapping",
                note = "the delay recorded on shot i gates the transition to shot i+1. transitionDelayMs is therefore " +
                    "blank on the final shot, and RQ3Summary excludes that final appliedDelayMs from pacedPercent, " +
                    "totalDelayMs, and positive-delay percentiles.",
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
                    "interpolation. deviceModel is the runtime Build.MODEL value.",
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
                    "(compare to beforeBacklogMs + beforeShutterElapsedMs). ceilingCrossSizeContaminationMs " +
                    "(beforeObservedMaxDraftMs minus sizeScopedObservedMaxDraftMs) is how much a heavier other-size " +
                    "draft inflates this capture's ceiling - the mixed-size over-pacing channel left after the point " +
                    "prediction is made size-aware.",
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
            Column("draftSequenceDurationMs") {
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
            Column("draftWallMs") { it.row.draftWallMs },
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
