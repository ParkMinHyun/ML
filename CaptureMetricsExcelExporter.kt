package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.Build
import com.samsung.android.camera.core2.container.DynamicShotMode
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.watermark.Watermark.WatermarkType
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
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

    private class EvaluationRun(
        val captures: List<EnrichedCaptureRow>,
    ) {
        val captureRows: List<CaptureRow> = captures.map { enriched -> enriched.row }
        val admissionRows: List<NodeSheetRow> = nodeSheetRows(captures)
            .filter { sheetRow -> sheetRow.nodeRow.isAdmissionWorkload && sheetRow.nodeRow.prediction != null }
        val pacingRows: List<PacingReplay> = captureRows.mapNotNull { capture -> capture.pacingReplay }
        val bokehRows: List<NodeRow> = captureRows.mapNotNull { capture -> capture.bokehDecisionRow }
        val filterRows: List<NodeRow> = captureRows.mapNotNull { capture -> capture.filterDecisionRow }
        val featureEligibleCaptures: List<CaptureRow> = captureRows.filter { capture ->
            capture.bokehDecisionRow != null || capture.filterDecisionRow != null
        }
        val timelineRows: List<EvaluationTimelineRow> = captures.mapIndexed { index, capture ->
            EvaluationTimelineRow(index + 1, capture)
        }
    }

    private class EvaluationTimelineRow(
        val trialCaptureNumber: Int,
        val capture: EnrichedCaptureRow,
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

            val allRawCaptures = metricsList.mapIndexed { index, metrics ->
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
            val rawCaptures = allRawCaptures.takeLast(EVALUATION_SESSION_CAPTURE_COUNT)

            val enrichedNormalCaptures = processCaptures(rawCaptures)
            val replayCaptures = enrichedNormalCaptures.sortedBy { enriched -> enriched.row.captureIndex }
            val evaluationRun = EvaluationRun(replayCaptures)
            val admissionReplayRows = nodeSheetRows(replayCaptures)
                .filter { row -> row.nodeRow.isAdmissionWorkload && row.nodeRow.prediction != null }
            val failureAttributionRows = evaluationRun.timelineRows
                .filter { row -> isFailureOrNearMiss(row.capture.row) }

            writeSheet(
                workbook,
                styles,
                "RunContext",
                listOf(evaluationRun),
                buildRunContextColumns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "RunScorecard",
                listOf(evaluationRun),
                buildRunScorecardColumns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "RQ1Effectiveness",
                listOf(evaluationRun),
                buildRq1Columns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "RQ2Tradeoff",
                listOf(evaluationRun),
                buildRq2Columns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "RQ3Robustness",
                listOf(evaluationRun),
                buildRq3Columns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "RQ3Timeline",
                evaluationRun.timelineRows,
                buildRq3TimelineColumns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "GuardBaseline",
                evaluationRun.timelineRows,
                buildGuardBaselineColumns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "FailureAttribution",
                failureAttributionRows,
                buildFailureAttributionColumns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "EvaluationNotes",
                buildEvaluationNotes(),
                buildReplayNoteColumns(),
                optimizeColumnWidths = true,
            )

            writeSheet(workbook, styles, "AdmissionReplay", admissionReplayRows, buildAdmissionReplayColumns())
            writeSheet(workbook, styles, "PacingReplay", replayCaptures, buildPacingReplayColumns())
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
        optimizeColumnWidths: Boolean = false,
    ) {
        val sheet = workbook.createSheet(sheetName)

        val headerRow = sheet.createRow(0)
        columns.forEachIndexed { index, column ->
            headerRow.createCell(index).apply {
                cellStyle = styles.headerStyle
                setCellValue(column.title)
            }
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
        sheet.createFreezePane(0, 1)
        if (items.isNotEmpty() && columns.isNotEmpty() && columns.all { column -> column.title.isNotBlank() }) {
            sheet.setAutoFilter(CellRangeAddress(0, sheet.lastRowNum, 0, columns.lastIndex))
        }
        if (optimizeColumnWidths) {
            columns.indices.forEach { columnIndex ->
                sheet.autoSizeColumn(columnIndex)
                if (sheet.getColumnWidth(columnIndex) > MAX_EVALUATION_COLUMN_WIDTH) {
                    sheet.setColumnWidth(columnIndex, MAX_EVALUATION_COLUMN_WIDTH)
                }
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
        private val backlogBaseWithoutLatencyMs =
            before.backlogMs + before.sessionPlannedCeilingMs - captureTimeoutMs

        val inferredDraftStartLatencyMs: Long? = if (before.backlogDeficitMs > 0L) {
            (before.backlogDeficitMs - ceil(backlogBaseWithoutLatencyMs).toLong()).coerceAtLeast(0L)
        } else {
            null
        }

        /** Recorded runtime input wins; inference only covers rows persisted before the field existed. */
        private val knownDraftStartLatencyMs: Long? =
            before.maxDraftStartLatencyMs ?: inferredDraftStartLatencyMs
        val draftStartLatencyMinMs: Long = knownDraftStartLatencyMs ?: 0L
        val draftStartLatencyMaxMs: Long = knownDraftStartLatencyMs ?: floor(
            (captureTimeoutMs - before.backlogMs - before.sessionPlannedCeilingMs).coerceAtLeast(0.0),
        ).toLong()
        val draftStartLatencyInference: String = when {
            before.maxDraftStartLatencyMs != null -> PACING_LATENCY_RECORDED
            inferredDraftStartLatencyMs != null -> PACING_LATENCY_EXACT
            else -> PACING_LATENCY_BOUNDED
        }

        val afterLevelDeficitMs: Long = computeCaptureAvailableLevelDeficitMs(
            draftStartBudgetMs = before.draftStartBudgetMs,
            sessionPlannedCeilingMs = before.sessionPlannedCeilingMs,
        )
        val afterBacklogDeficitMinMs: Long = computeCaptureAvailableBacklogDeficitMs(
            backlogMs = before.backlogMs,
            maxDraftStartLatencyMs = draftStartLatencyMinMs,
            sessionPlannedCeilingMs = before.sessionPlannedCeilingMs,
        )
        val afterBacklogDeficitMaxMs: Long = computeCaptureAvailableBacklogDeficitMs(
            backlogMs = before.backlogMs,
            maxDraftStartLatencyMs = draftStartLatencyMaxMs,
            sessionPlannedCeilingMs = before.sessionPlannedCeilingMs,
        )
        val afterBacklogDeficitMs: Long? = afterBacklogDeficitMinMs.takeIf { minimum ->
            minimum == afterBacklogDeficitMaxMs
        }
        val afterAppliedDelayMinMs: Long = maxOf(afterLevelDeficitMs, afterBacklogDeficitMinMs)
        val afterAppliedDelayMaxMs: Long = maxOf(afterLevelDeficitMs, afterBacklogDeficitMaxMs)
        val afterAppliedDelayMs: Long? = afterAppliedDelayMinMs.takeIf { minimum ->
            minimum == afterAppliedDelayMaxMs
        }
        val delayDeltaMs: Long? = afterAppliedDelayMs?.minus(before.appliedDelayMs)
        val pacingChanged: Boolean? = afterAppliedDelayMs?.let { delayMs -> delayMs != before.appliedDelayMs }
        val replayStatus: String = when {
            knownDraftStartLatencyMs != null -> PACING_REPLAY_EXACT
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
        val headerStyle: CellStyle = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply { bold = true })
        }
        private val msStyle: CellStyle = workbook.createCellStyle().apply {
            dataFormat = this@Styles.dataFormat.getFormat("0\" ms\"")
        }
        private val percentStyle: CellStyle = workbook.createCellStyle().apply {
            dataFormat = this@Styles.dataFormat.getFormat("0.0\"%\"")
        }
        private val rateStyle: CellStyle = workbook.createCellStyle().apply {
            dataFormat = this@Styles.dataFormat.getFormat("0.0%")
        }
        private val wrapTextStyle: CellStyle = workbook.createCellStyle().apply {
            wrapText = true
            verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.TOP
        }

        fun styleFor(columnTitle: String, value: Any?): CellStyle? {
            if (columnTitle.equals("note", ignoreCase = true)) {
                return wrapTextStyle
            }
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
        private const val EVALUATION_SESSION_CAPTURE_COUNT = 30
        private const val MAX_SHEET_NAME_LENGTH = 31
        private const val MAX_EVALUATION_COLUMN_WIDTH = 48 * 256
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
        private const val PACING_LATENCY_RECORDED = "Recorded runtime input"
        private const val PACING_LATENCY_EXACT = "Exact from positive backlog deficit"
        private const val PACING_LATENCY_BOUNDED = "Bounded because backlog deficit was zero"
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

        // ---- SEIP evaluation additions: run context, static-guard baseline, failure attribution ----
        // Production static thermal guard from Section 2.4: optional Draft processing is skipped at this overheat
        // level or higher. Used only as an offline counterfactual to contrast with the controller's decisions.
        private const val STATIC_GUARD_OVERHEAT_LEVEL = 4

        // Tail-diagnostic threshold: a capture that finished within this margin of the deadline is a near miss, so
        // failure attribution and tail analysis have signal even in runs with zero hard timeouts. 10% of the timeout.
        private val NEAR_MISS_MARGIN_MS = (MakerFeature.CAPTURE_TIMEOUT_MS * 0.1).toLong()

        // Cold-start window: prediction error over the first vs last few captures shows the online model converging.
        private const val COLD_START_CAPTURE_COUNT = 5

        // PowerManager thermal headroom is normalized so >= 1.0 means at or past the throttling threshold.
        private const val THERMAL_HEADROOM_THROTTLING = 1.0f

        private const val TARGET_CONFIG_M_AND_S = "M+S"
        private const val TARGET_CONFIG_M_ONLY = "M"
        private const val TARGET_CONFIG_S_ONLY = "S-only"
        private const val TARGET_CONFIG_NONE = "none"

        // Controller arm inferred from observed behavior only; a skip implies admission ran, a nonzero delay implies
        // pacing ran. It cannot tell an inactive mechanism from an active one that never triggered - armLabel wins.
        private const val ARM_FULL = "FULL (admission + pacing)"
        private const val ARM_ADMISSION_ONLY = "ADMISSION_ONLY (skip observed, no delay)"
        private const val ARM_PACING_ONLY = "PACING_ONLY (delay observed, no skip)"
        private const val ARM_INACTIVE = "NONE_OR_INACTIVE (no skip and no delay observed)"

        private const val GUARD_CELL_RECOVERED_M_SAFE = "RecoveredM_Safe: guard suppresses, controller ran M, no timeout"
        private const val GUARD_CELL_ADMITTED_M_TIMEOUT = "AdmittedM_Timeout: guard suppresses, controller ran M, timeout"
        private const val GUARD_CELL_AGREE_SUPPRESS = "AgreeSuppress: guard suppresses, controller skipped M"
        private const val GUARD_CELL_AGREE_PERMIT_SAFE = "AgreePermit_Safe: guard permits, controller ran M, no timeout"
        private const val GUARD_CELL_GUARD_BLIND_TIMEOUT = "GuardBlindTimeout: guard permits, controller ran M, timeout"
        private const val GUARD_CELL_TIGHTENED_SAFE = "TightenedBelow4_Safe: guard permits, controller skipped M, no timeout"
        private const val GUARD_CELL_TIGHTENED_TIMEOUT = "Tightened_Timeout: guard permits, controller skipped M, timeout"
        private const val GUARD_CELL_M_NOT_ELIGIBLE = "M_not_eligible: no Bokeh decision in this capture"
        private const val GUARD_CELL_UNKNOWN_LEVEL = "Unknown_level"

        private const val CAUSE_CROSS_SHOT_BACKLOG = "CrossShotBacklogAccumulation"
        private const val CAUSE_SINGLE_CAPTURE_OVERRUN = "SingleCaptureBudgetOverrun"
        private const val CAUSE_PREDICTION_MISS = "PredictionMiss"
        private const val CAUSE_THROTTLE_RAMP = "ThrottleRampSlowdown"
        private const val CAUSE_UNATTRIBUTED = "Unattributed"

        private const val MECHANISM_PACING = "Pacing"
        private const val MECHANISM_ADMISSION = "Admission"
        private const val MECHANISM_PREDICTOR = "Predictor"
        private const val MECHANISM_ENVIRONMENT = "Environment"
        private const val MECHANISM_UNATTRIBUTED = "Unattributed"

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

        private fun buildRq1Columns(): List<Column<EvaluationRun>> = listOf(
            Column("deviceModel") { Build.MODEL },
            Column("captureCount") { it.captureRows.size },
            Column("expectedCaptureCount") { EVALUATION_SESSION_CAPTURE_COUNT },
            Column("sessionComplete") { run ->
                run.captureRows.size == EVALUATION_SESSION_CAPTURE_COUNT
            },
            Column("timeoutCount") { run -> run.captureRows.count { capture -> capture.hasTimeoutFailure } },
            Column("timeoutRate") { run ->
                rate(run.captureRows.count { capture -> capture.hasTimeoutFailure }, run.captureRows.size)
            },
            Column("watchdogFailureCount") { run ->
                run.captureRows.count { capture -> capture.hasWatchdogFailure }
            },
            Column("watchdogFailureRate") { run ->
                rate(run.captureRows.count { capture -> capture.hasWatchdogFailure }, run.captureRows.size)
            },
            Column("timeoutOrWatchdogFailureCount") { run ->
                run.captureRows.count { capture -> capture.hasTimeoutOrWatchdogFailure }
            },
            Column("timeoutOrWatchdogFailureRate") { run ->
                rate(run.captureRows.count { capture -> capture.hasTimeoutOrWatchdogFailure }, run.captureRows.size)
            },
            Column("anyTimeoutOrWatchdogFailure") { run ->
                run.captureRows.any { capture -> capture.hasTimeoutOrWatchdogFailure }
            },
            Column("firstTimeoutCaptureNumber") { run ->
                oneBasedFirstIndex(run.captureRows) { capture -> capture.hasTimeoutFailure }
            },
            Column("firstFailureCaptureNumber") { run ->
                oneBasedFirstIndex(run.captureRows) { capture -> capture.hasTimeoutOrWatchdogFailure }
            },
            Column("timeoutRightCensored") { run ->
                run.captureRows.none { capture -> capture.hasTimeoutFailure }
            },
            Column("timeoutMarginObservedCount") { run ->
                run.captureRows.count { capture -> capture.timeoutMarginMs != null }
            },
            Column("minimumTimeoutMarginMs") { run ->
                run.captureRows.mapNotNull { capture -> capture.timeoutMarginMs }.minOrNull()
            },
            Column("p05TimeoutMarginMs") { run ->
                percentile(run.captureRows.mapNotNull { capture -> capture.timeoutMarginMs?.toDouble() }, 0.05)
            },
            Column("medianTimeoutMarginMs") { run ->
                percentile(run.captureRows.mapNotNull { capture -> capture.timeoutMarginMs?.toDouble() }, 0.50)
            },
            Column("featureEligibleCaptureCount") { it.featureEligibleCaptures.size },
            Column("mBokehDecisionCount") { it.bokehRows.size },
            Column("mBokehAdmitCount") { run -> run.bokehRows.count { row -> row.wasAdmitted == true } },
            Column("mBokehAdmitRate") { run ->
                rate(run.bokehRows.count { row -> row.wasAdmitted == true }, run.bokehRows.size)
            },
            Column("mBokehCompletedCount") { run -> run.bokehRows.count { row -> row.wasCompleted } },
            Column("mBokehCompletionRate") { run ->
                rate(run.bokehRows.count { row -> row.wasCompleted }, run.bokehRows.size)
            },
            Column("mBokehCompletionAmongAdmittedRate") { run ->
                rate(
                    run.bokehRows.count { row -> row.wasCompleted },
                    run.bokehRows.count { row -> row.wasAdmitted == true },
                )
            },
            Column("sFilterDecisionCount") { it.filterRows.size },
            Column("sFilterAdmitCount") { run -> run.filterRows.count { row -> row.wasAdmitted == true } },
            Column("sFilterAdmitRate") { run ->
                rate(run.filterRows.count { row -> row.wasAdmitted == true }, run.filterRows.size)
            },
            Column("sFilterCompletedCount") { run -> run.filterRows.count { row -> row.wasCompleted } },
            Column("sFilterCompletionRate") { run ->
                rate(run.filterRows.count { row -> row.wasCompleted }, run.filterRows.size)
            },
            Column("fullFeatureSuccessCount") { run ->
                run.captureRows.count { capture -> capture.isFullFeatureSuccess }
            },
            Column("fullFeatureSuccessRate") { run ->
                rate(
                    run.captureRows.count { capture -> capture.isFullFeatureSuccess },
                    run.featureEligibleCaptures.size,
                )
            },
            Column("selectiveBokehSkipSuccessCount") { run ->
                run.captureRows.count { capture -> capture.isSelectiveBokehSkipSuccess }
            },
            Column("selectiveBokehSkipSuccessRate") { run ->
                rate(
                    run.captureRows.count { capture -> capture.isSelectiveBokehSkipSuccess },
                    run.featureEligibleCaptures.size,
                )
            },
            Column("tailOnlySafeCount") { run ->
                run.captureRows.count { capture -> capture.policyOutcome() == PolicyOutcome.TAIL_ONLY_SAFE }
            },
            // The benefit Section 2.4 targets: run M where the static guard forbids it (level >= 4) without a timeout,
            // while staying safe where the guard is blind (level < 4). Stratifying M availability by the guard level
            // is what shows the controller is not just uniformly stricter or looser than the fixed threshold.
            Column("mBokehCompletionLevelGE4Rate") { mCompletionRateAtLevel(it, atOrAboveGuard = true) },
            Column("mBokehCompletionLevelLT4Rate") { mCompletionRateAtLevel(it, atOrAboveGuard = false) },
            Column("guardFalsePositiveFixCount") { guardFalsePositiveFixCount(it) },
            Column("guardFalseNegativeExposedCount") { guardFalseNegativeExposedCount(it) },
        )

        private fun buildRq2Columns(): List<Column<EvaluationRun>> = listOf(
            Column("deviceModel") { Build.MODEL },
            Column("captureCount") { it.captureRows.size },
            Column("shotToShotObservedCount") { run ->
                run.captureRows.count { capture -> capture.metrics.shotToShotTimeMs != null }
            },
            Column("totalShotToShotTimeMs") { run ->
                run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs }.sum()
            },
            Column("meanShotToShotTimeMs") { run ->
                mean(run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs?.toDouble() })
            },
            Column("medianShotToShotTimeMs") { run ->
                percentile(
                    run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs?.toDouble() },
                    0.50,
                )
            },
            Column("p95ShotToShotTimeMs") { run ->
                percentile(
                    run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs?.toDouble() },
                    0.95,
                )
            },
            Column("maximumShotToShotTimeMs") { run ->
                run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs }.maxOrNull()
            },
            Column("pacingDecisionCount") { it.pacingRows.size },
            Column("nonzeroPacingDelayCount") { run ->
                run.pacingRows.count { pacing -> pacing.before.appliedDelayMs > 0L }
            },
            Column("nonzeroPacingDelayRate") { run ->
                rate(
                    run.pacingRows.count { pacing -> pacing.before.appliedDelayMs > 0L },
                    run.pacingRows.size,
                )
            },
            Column("totalAppliedPacingDelayMs") { run ->
                run.pacingRows.sumOf { pacing -> pacing.before.appliedDelayMs }
            },
            Column("meanAppliedPacingDelayMs") { run ->
                mean(run.pacingRows.map { pacing -> pacing.before.appliedDelayMs.toDouble() })
            },
            Column("medianAppliedPacingDelayMs") { run ->
                percentile(run.pacingRows.map { pacing -> pacing.before.appliedDelayMs.toDouble() }, 0.50)
            },
            Column("p95AppliedPacingDelayMs") { run ->
                percentile(run.pacingRows.map { pacing -> pacing.before.appliedDelayMs.toDouble() }, 0.95)
            },
            Column("maximumAppliedPacingDelayMs") { run ->
                run.pacingRows.maxOfOrNull { pacing -> pacing.before.appliedDelayMs }
            },
            Column("levelDominantPacingCount") { run ->
                run.pacingRows.count { pacing -> pacing.beforeDominantDeficit == PACING_DOMINANT_LEVEL }
            },
            Column("backlogDominantPacingCount") { run ->
                run.pacingRows.count { pacing -> pacing.beforeDominantDeficit == PACING_DOMINANT_BACKLOG }
            },
            Column("equalDeficitPacingCount") { run ->
                run.pacingRows.count { pacing -> pacing.beforeDominantDeficit == PACING_DOMINANT_EQUAL }
            },
            Column("noDeficitPacingCount") { run ->
                run.pacingRows.count { pacing -> pacing.beforeDominantDeficit == PACING_DOMINANT_NONE }
            },
            Column("admissionDecisionCount") { it.admissionRows.size },
            Column("upperBoundSkipCount") { run ->
                run.admissionRows.count { row -> row.admissionSkipReason() == ADMISSION_SKIP_REASON_UPPER_BOUND }
            },
            Column("sessionDemotionSkipCount") { run ->
                run.admissionRows.count {
                    row -> row.admissionSkipReason() == ADMISSION_SKIP_REASON_SESSION_DEMOTION
                }
            },
            Column("mBokehSkipCount") { run -> run.bokehRows.count { row -> row.wasSkipped } },
            Column("sFilterSkipCount") { run -> run.filterRows.count { row -> row.wasSkipped } },
            // Where the pacing cost went: delay charged because this capture's own ceiling exceeded a throttled
            // budget (level-dominant, throttle recovery) vs delay charged to drain a queued backlog the current
            // capture inherited (backlog-dominant, cross-shot recovery). Section 2.4 argues only the latter can
            // recover budget already lost while waiting for the Draft worker, so separating them is load-bearing.
            Column("throttleRecoveryDelayMs") { appliedDelaySumByDominant(it, PACING_DOMINANT_LEVEL) },
            Column("backlogRecoveryDelayMs") { appliedDelaySumByDominant(it, PACING_DOMINANT_BACKLOG) },
            Column("coDominantDelayMs") { appliedDelaySumByDominant(it, PACING_DOMINANT_EQUAL) },
            Column("effectiveShotsPerSecond") { run ->
                val meanMs = mean(run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs?.toDouble() })
                if (meanMs != null && meanMs > 0.0) 1000.0 / meanMs else null
            },
        )

        private fun buildRq3Columns(): List<Column<EvaluationRun>> = listOf(
            Column("deviceModel") { Build.MODEL },
            Column("captureCount") { it.captureRows.size },
            Column("observedResolutions") { run ->
                run.captureRows.map { capture ->
                    "${capture.metrics.resultImageSize.width}x${capture.metrics.resultImageSize.height}"
                }.distinct().sorted().joinToString("|")
            },
            Column("observedStartingOverheatLevel") { run -> overheatLevels(run).firstOrNull() },
            Column("minimumObservedOverheatLevel") { run -> overheatLevels(run).minOrNull() },
            Column("maximumObservedOverheatLevel") { run -> overheatLevels(run).maxOrNull() },
            Column("finalObservedOverheatLevel") { run -> overheatLevels(run).lastOrNull() },
            Column("overheatLevelChangeCount") { run ->
                overheatLevels(run).zipWithNext().count { (before, after) -> before != after }
            },
            Column("maximumAdjacentOverheatIncrease") { run ->
                overheatLevels(run).zipWithNext()
                    .maxOfOrNull { (before, after) -> after - before }
                    ?.coerceAtLeast(0)
            },
            Column("lowMemoryCaptureCount") { run ->
                firstNodePreExecutionMetrics(run).count { metrics -> metrics.memorySnapshot.isLowMemory }
            },
            Column("lowMemoryCaptureRate") { run ->
                rate(
                    firstNodePreExecutionMetrics(run).count { metrics -> metrics.memorySnapshot.isLowMemory },
                    firstNodePreExecutionMetrics(run).size,
                )
            },
            Column("minimumRamAvailablePercent") { run ->
                firstNodePreExecutionMetrics(run).minOfOrNull { metrics ->
                    metrics.memorySnapshot.ramAvailablePercent
                }
            },
            Column("maximumJavaHeapUsedPercent") { run ->
                firstNodePreExecutionMetrics(run).maxOfOrNull { metrics ->
                    metrics.memorySnapshot.javaHeapUsedPercent
                }
            },
            Column("maximumNativeHeapAllocatedPercent") { run ->
                firstNodePreExecutionMetrics(run).maxOfOrNull { metrics ->
                    metrics.memorySnapshot.nativeHeapAllocatedPercent
                }
            },
            Column("minimumThermalHeadroom") { run ->
                firstNodePreExecutionMetrics(run).minOfOrNull { metrics ->
                    metrics.thermalSnapshot.thermalHeadroom
                }
            },
            Column("admissionDecisionCount") { it.admissionRows.size },
            Column("zeroPointPredictionCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.prediction?.sequencePredictedDurationMs?.let { duration -> duration <= 0.0 } == true
                }
            },
            Column("zeroPointPredictionRate") { run ->
                rate(
                    run.admissionRows.count { row ->
                        row.nodeRow.prediction?.sequencePredictedDurationMs?.let { duration -> duration <= 0.0 } == true
                    },
                    run.admissionRows.size,
                )
            },
            Column("firstPositivePredictionCaptureNumber") { run ->
                oneBasedFirstIndex(run.captures) { capture ->
                    capture.row.nodeRows.any { row ->
                        row.isAdmissionWorkload &&
                            row.prediction?.sequencePredictedDurationMs?.let { duration -> duration > 0.0 } == true
                    }
                }
            },
            Column("fullyObservedAdmissionDecisionCount") { run ->
                run.admissionRows.count { row -> row.isFullyObservedSuffix() }
            },
            Column("sequenceUpperBoundMissCount") { run ->
                run.admissionRows.count { row -> row.sequenceUpperBoundMiss() == true }
            },
            Column("sequenceUpperBoundMissRate") { run ->
                rate(
                    run.admissionRows.count { row -> row.sequenceUpperBoundMiss() == true },
                    run.admissionRows.count { row -> row.sequenceUpperBoundMiss() != null },
                )
            },
            Column("sequenceUpperBoundCoverageRate") { run ->
                val observed = run.admissionRows.count { row -> row.sequenceUpperBoundMiss() != null }
                val misses = run.admissionRows.count { row -> row.sequenceUpperBoundMiss() == true }
                rate(observed - misses, observed)
            },
            Column("minimumSequenceUpperBoundSlackMs") { run ->
                run.admissionRows.mapNotNull { row -> row.nodeRow.sequenceUpperBoundSlackMs() }.minOrNull()
            },
            Column("p05SequenceUpperBoundSlackMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row -> row.nodeRow.sequenceUpperBoundSlackMs() },
                    0.05,
                )
            },
            Column("medianAbsolutePredictionErrorMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row ->
                        row.nodeRow.sequencePredictionResidualMs()?.let(::abs)
                    },
                    0.50,
                )
            },
            Column("p95AbsolutePredictionErrorMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row ->
                        row.nodeRow.sequencePredictionResidualMs()?.let(::abs)
                    },
                    0.95,
                )
            },
            Column("correctAdmitCount") { run ->
                run.admissionRows.count { row -> row.decisionOutcome() == DecisionOutcome.CORRECT_ADMIT }
            },
            Column("unsafeAdmitCount") { run ->
                run.admissionRows.count { row -> row.decisionOutcome() == DecisionOutcome.UNSAFE_ADMIT }
            },
            Column("unsafeAdmitRateAmongObservedAdmits") { run ->
                val correct = run.admissionRows.count {
                    row -> row.decisionOutcome() == DecisionOutcome.CORRECT_ADMIT
                }
                val unsafe = run.admissionRows.count {
                    row -> row.decisionOutcome() == DecisionOutcome.UNSAFE_ADMIT
                }
                rate(unsafe, correct + unsafe)
            },
            Column("skipRequiresOfflineOracleCount") { run ->
                run.admissionRows.count {
                    row -> row.decisionOutcome() == DecisionOutcome.SKIP_REQUIRES_OFFLINE_ORACLE
                }
            },
            Column("pacingCeilingObservedCount") { run -> ceilingErrorsMs(run).size },
            Column("ceilingUndershootCount") { run ->
                ceilingErrorsMs(run).count { errorMs -> errorMs < 0.0 }
            },
            Column("ceilingUndershootRate") { run ->
                rate(
                    ceilingErrorsMs(run).count { errorMs -> errorMs < 0.0 },
                    ceilingErrorsMs(run).size,
                )
            },
            Column("minimumCeilingErrorMs") { run -> ceilingErrorsMs(run).minOrNull() },
            Column("p05CeilingErrorMs") { run -> percentile(ceilingErrorsMs(run), 0.05) },
            Column("p95CeilingErrorMs") { run -> percentile(ceilingErrorsMs(run), 0.95) },
            Column("queuePricingObservedCount") { run -> queuePricingErrorsMs(run).size },
            Column("queueUnderpriceCount") { run ->
                queuePricingErrorsMs(run).count { errorMs -> errorMs > 0.0 }
            },
            Column("queueUnderpriceRate") { run ->
                rate(
                    queuePricingErrorsMs(run).count { errorMs -> errorMs > 0.0 },
                    queuePricingErrorsMs(run).size,
                )
            },
            Column("medianQueuePricingErrorMs") { run ->
                percentile(queuePricingErrorsMs(run), 0.50)
            },
            Column("p95QueuePricingErrorMs") { run ->
                percentile(queuePricingErrorsMs(run), 0.95)
            },
            // Cold-start convergence: the model learns online with no persisted priors, so absolute prediction error
            // over the first few captures vs the last few shows it settling. A positive convergence delta means the
            // later captures predict more accurately than the opening ones.
            Column("coldStartMedianAbsPredictionErrorMs") { run ->
                percentile(absPredictionErrors(run.captures.take(COLD_START_CAPTURE_COUNT)), 0.50)
            },
            Column("steadyStateMedianAbsPredictionErrorMs") { run ->
                percentile(absPredictionErrors(run.captures.takeLast(COLD_START_CAPTURE_COUNT)), 0.50)
            },
            Column("predictionErrorConvergenceDeltaMs") { run ->
                val cold = percentile(absPredictionErrors(run.captures.take(COLD_START_CAPTURE_COUNT)), 0.50)
                val steady = percentile(absPredictionErrors(run.captures.takeLast(COLD_START_CAPTURE_COUNT)), 0.50)
                if (cold != null && steady != null) cold - steady else null
            },
        )

        private fun buildRq3TimelineColumns(): List<Column<EvaluationTimelineRow>> = listOf(
            Column("trialCaptureNumber") { it.trialCaptureNumber },
            Column("captureIndex") { it.capture.row.captureIndex },
            Column("ppSequenceId") { it.capture.row.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.capture.row.metrics.dsMode) },
            Column("resultImageWidth") { it.capture.row.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.capture.row.metrics.resultImageSize.height },
            Column("shotToShotTimeMs") { it.capture.row.metrics.shotToShotTimeMs },
            Column("overheatLevel") {
                it.capture.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.overheatLevel
            },
            Column("thermalStatus") {
                it.capture.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalStatus
            },
            Column("thermalHeadroom") {
                it.capture.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalHeadroom
            },
            Column("isLowMemory") {
                it.capture.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot?.isLowMemory
            },
            Column("ramAvailablePercent") {
                it.capture.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot?.ramAvailablePercent
            },
            Column("javaHeapUsedPercent") {
                it.capture.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot?.javaHeapUsedPercent
            },
            Column("nativeHeapAllocatedPercent") {
                it.capture.row.nodeRows.firstOrNull()
                    ?.node?.preExecutionMetrics?.memorySnapshot?.nativeHeapAllocatedPercent
            },
            Column("isTimeout") { it.capture.row.hasTimeoutFailure },
            Column("hasWatchdogFailure") { it.capture.row.hasWatchdogFailure },
            Column("timeoutMarginMs") { it.capture.row.timeoutMarginMs },
            Column("draftWallMs") { it.capture.row.draftWallMs },
            Column("policyOutcome") { it.capture.row.policyOutcome().label },
            Column("mBokehAdmitted") { it.capture.row.bokehDecisionRow?.wasAdmitted },
            Column("mBokehCompleted") { it.capture.row.bokehDecisionRow?.wasCompleted },
            Column("mBokehPredictedDurationMs") {
                it.capture.row.bokehDecisionRow?.prediction?.sequencePredictedDurationMs
            },
            Column("mBokehPredictedUpperBoundMs") {
                it.capture.row.bokehDecisionRow?.prediction?.sequencePredictedUpperBoundMs
            },
            Column("mBokehActualSuffixDurationMs") {
                it.capture.row.bokehDecisionRow?.sequenceActualDurationMs
            },
            Column("mBokehUpperBoundSlackMs") {
                it.capture.row.bokehDecisionRow?.sequenceUpperBoundSlackMs()
            },
            Column("mBokehUpperBoundMiss") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.bokehDecisionRow)?.sequenceUpperBoundMiss()
            },
            Column("sFilterAdmitted") { it.capture.row.filterDecisionRow?.wasAdmitted },
            Column("sFilterCompleted") { it.capture.row.filterDecisionRow?.wasCompleted },
            Column("sFilterPredictedDurationMs") {
                it.capture.row.filterDecisionRow?.prediction?.sequencePredictedDurationMs
            },
            Column("sFilterPredictedUpperBoundMs") {
                it.capture.row.filterDecisionRow?.prediction?.sequencePredictedUpperBoundMs
            },
            Column("sFilterActualSuffixDurationMs") {
                it.capture.row.filterDecisionRow?.sequenceActualDurationMs
            },
            Column("sFilterUpperBoundSlackMs") {
                it.capture.row.filterDecisionRow?.sequenceUpperBoundSlackMs()
            },
            Column("sFilterUpperBoundMiss") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.filterDecisionRow)?.sequenceUpperBoundMiss()
            },
            Column("pacingDecisionAvailable") { it.capture.row.pacingReplay != null },
            Column("appliedPacingDelayMs") { it.capture.row.pacingReplay?.before?.appliedDelayMs },
            Column("pacingDominantDeficit") { it.capture.row.pacingReplay?.beforeDominantDeficit },
            Column("pacingBacklogMs") { it.capture.row.pacingReplay?.before?.backlogMs },
            Column("pacingQueuedDraftCount") { it.capture.row.pacingReplay?.before?.queuedDraftCount },
            Column("pacingMaxDraftStartLatencyMs") {
                it.capture.row.pacingReplay?.before?.maxDraftStartLatencyMs
            },
            Column("pacingSessionPlannedCeilingMs") {
                it.capture.row.pacingReplay?.before?.sessionPlannedCeilingMs
            },
            Column("pacingCeilingErrorMs") {
                val ceilingMs = it.capture.row.pacingReplay?.before?.sessionPlannedCeilingMs
                val draftWallMs = it.capture.row.draftWallMs
                if (ceilingMs == null || draftWallMs == null) {
                    null
                } else {
                    ceilingMs - draftWallMs
                }
            },
            Column("pacingQueuePricingErrorMs") { row ->
                queuePricingErrorMs(row.capture)
            },
        )

        /**
         * One self-describing row per workbook. Lets several exported workbooks be aggregated into the baseline and
         * ablation comparison tables without parsing file names: every config axis of the Section 2.4 study (device,
         * resolution, size bucket, starting/observed overheat, memory, M vs M+S) plus the inferred controller arm.
         */
        private fun buildRunContextColumns(): List<Column<EvaluationRun>> = listOf(
            Column("deviceModel") { Build.MODEL },
            Column("androidSdkInt") { Build.VERSION.SDK_INT },
            Column("androidRelease") { Build.VERSION.RELEASE },
            Column("captureCount") { it.captureRows.size },
            Column("expectedCaptureCount") { EVALUATION_SESSION_CAPTURE_COUNT },
            Column("sessionComplete") { it.captureRows.size == EVALUATION_SESSION_CAPTURE_COUNT },
            Column("dominantResolution") { dominantResolution(it) },
            Column("observedResolutions") { run ->
                run.captureRows.map { capture ->
                    "${capture.metrics.resultImageSize.width}x${capture.metrics.resultImageSize.height}"
                }.distinct().sorted().joinToString("|")
            },
            Column("sizeBucketInferred") { sizeBucketInferred(it) },
            Column("targetConfigInferred") { targetConfigInferred(it) },
            Column("startingOverheatLevel") { overheatLevels(it).firstOrNull() },
            Column("minOverheatLevel") { overheatLevels(it).minOrNull() },
            Column("maxOverheatLevel") { overheatLevels(it).maxOrNull() },
            Column("finalOverheatLevel") { overheatLevels(it).lastOrNull() },
            Column("overheatRampObserved") { run ->
                val levels = overheatLevels(run)
                val start = levels.firstOrNull()
                val peak = levels.maxOrNull()
                start != null && peak != null && peak > start
            },
            Column("anyLowMemoryObserved") { run ->
                firstNodePreExecutionMetrics(run).any { metrics -> metrics.memorySnapshot.isLowMemory }
            },
            Column("minRamAvailablePercent") { run ->
                firstNodePreExecutionMetrics(run).minOfOrNull { metrics -> metrics.memorySnapshot.ramAvailablePercent }
            },
            Column("admissionActiveObserved") { run -> run.admissionRows.any { row -> row.nodeRow.wasSkipped } },
            Column("pacingDecisionsPresent") { it.pacingRows.isNotEmpty() },
            Column("pacingActiveObserved") { run -> run.pacingRows.any { pacing -> pacing.before.appliedDelayMs > 0L } },
            Column("armSignatureInferred") { armSignatureInferred(it) },
            Column("captureTimeoutMs") { MakerFeature.CAPTURE_TIMEOUT_MS },
            Column("staticGuardOverheatLevel") { STATIC_GUARD_OVERHEAT_LEVEL },
            Column("nearMissMarginMs") { NEAR_MISS_MARGIN_MS },
            // Blank cells for the operator to annotate the authoritative arm/condition after pulling the workbook.
            Column("armLabel") { "" },
            Column("conditionLabel") { "" },
            Column("trialId") { "" },
            Column("notes") { "" },
        )

        /**
         * One headline row per workbook: the same config keys as RunContext plus the safety, feature, cost, and
         * correctness KPIs. Stack these rows across arm/condition runs to assemble the baseline vs proposed and the
         * admission-only / pacing-only / full ablation comparison tables directly.
         */
        private fun buildRunScorecardColumns(): List<Column<EvaluationRun>> = listOf(
            Column("deviceModel") { Build.MODEL },
            Column("sizeBucketInferred") { sizeBucketInferred(it) },
            Column("targetConfigInferred") { targetConfigInferred(it) },
            Column("anyLowMemoryObserved") { run ->
                firstNodePreExecutionMetrics(run).any { metrics -> metrics.memorySnapshot.isLowMemory }
            },
            Column("startingOverheatLevel") { overheatLevels(it).firstOrNull() },
            Column("armSignatureInferred") { armSignatureInferred(it) },
            Column("armLabel") { "" },
            Column("conditionLabel") { "" },
            Column("captureCount") { it.captureRows.size },
            // Safety.
            Column("timeoutCount") { run -> run.captureRows.count { capture -> capture.hasTimeoutFailure } },
            Column("timeoutRate") { run ->
                rate(run.captureRows.count { capture -> capture.hasTimeoutFailure }, run.captureRows.size)
            },
            Column("firstTimeoutCaptureNumber") { run ->
                oneBasedFirstIndex(run.captureRows) { capture -> capture.hasTimeoutFailure }
            },
            Column("timeoutRightCensored") { run -> run.captureRows.none { capture -> capture.hasTimeoutFailure } },
            Column("watchdogFailureCount") { run -> run.captureRows.count { capture -> capture.hasWatchdogFailure } },
            Column("minTimeoutMarginMs") { run ->
                run.captureRows.mapNotNull { capture -> capture.timeoutMarginMs }.minOrNull()
            },
            Column("p05TimeoutMarginMs") { run ->
                percentile(run.captureRows.mapNotNull { capture -> capture.timeoutMarginMs?.toDouble() }, 0.05)
            },
            Column("medianTimeoutMarginMs") { run ->
                percentile(run.captureRows.mapNotNull { capture -> capture.timeoutMarginMs?.toDouble() }, 0.50)
            },
            // Feature availability.
            Column("mBokehCompletionRate") { run ->
                rate(run.bokehRows.count { row -> row.wasCompleted }, run.bokehRows.size)
            },
            Column("mBokehCompletionLevelGE4Rate") { mCompletionRateAtLevel(it, atOrAboveGuard = true) },
            Column("mBokehCompletionLevelLT4Rate") { mCompletionRateAtLevel(it, atOrAboveGuard = false) },
            Column("sFilterCompletionRate") { run ->
                rate(run.filterRows.count { row -> row.wasCompleted }, run.filterRows.size)
            },
            Column("fullFeatureSuccessRate") { run ->
                rate(run.captureRows.count { capture -> capture.isFullFeatureSuccess }, run.featureEligibleCaptures.size)
            },
            Column("guardFalsePositiveFixCount") { guardFalsePositiveFixCount(it) },
            Column("guardFalseNegativeExposedCount") { guardFalseNegativeExposedCount(it) },
            // Cost.
            Column("meanShotToShotTimeMs") { run ->
                mean(run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs?.toDouble() })
            },
            Column("p95ShotToShotTimeMs") { run ->
                percentile(run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs?.toDouble() }, 0.95)
            },
            Column("nonzeroPacingDelayRate") { run ->
                rate(run.pacingRows.count { pacing -> pacing.before.appliedDelayMs > 0L }, run.pacingRows.size)
            },
            Column("meanAppliedPacingDelayMs") { run ->
                mean(run.pacingRows.map { pacing -> pacing.before.appliedDelayMs.toDouble() })
            },
            Column("p95AppliedPacingDelayMs") { run ->
                percentile(run.pacingRows.map { pacing -> pacing.before.appliedDelayMs.toDouble() }, 0.95)
            },
            // Correctness.
            Column("unsafeAdmitCount") { run ->
                run.admissionRows.count { row -> row.decisionOutcome() == DecisionOutcome.UNSAFE_ADMIT }
            },
            Column("sequenceUpperBoundMissCount") { run ->
                run.admissionRows.count { row -> row.sequenceUpperBoundMiss() == true }
            },
            Column("medianAbsolutePredictionErrorMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row -> row.nodeRow.sequencePredictionResidualMs()?.let(::abs) },
                    0.50,
                )
            },
        )

        /**
         * Per-capture offline counterfactual against the production static thermal guard (skip optional Draft at
         * overheat level >= 4). Answers the Section 2.4 claim directly: the controller must recover M where the guard
         * suppresses it and stay safe where the guard is blind. This reads the recorded overheat level only; it is
         * not a separate on-device guard run.
         */
        private fun buildGuardBaselineColumns(): List<Column<EvaluationTimelineRow>> = listOf(
            Column("trialCaptureNumber") { it.trialCaptureNumber },
            Column("captureIndex") { it.capture.row.captureIndex },
            Column("resultImageWidth") { it.capture.row.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.capture.row.metrics.resultImageSize.height },
            Column("overheatLevel") { overheatLevelOf(it.capture.row) },
            Column("guardWouldSuppressOptional") { row ->
                overheatLevelOf(row.capture.row)?.let { level -> level >= STATIC_GUARD_OVERHEAT_LEVEL }
            },
            Column("mBokehEligible") { it.capture.row.bokehDecisionRow != null },
            Column("mBokehAdmitted") { it.capture.row.bokehDecisionRow?.wasAdmitted },
            Column("mBokehCompleted") { it.capture.row.bokehDecisionRow?.wasCompleted },
            Column("sFilterAdmitted") { it.capture.row.filterDecisionRow?.wasAdmitted },
            Column("sFilterCompleted") { it.capture.row.filterDecisionRow?.wasCompleted },
            Column("isTimeout") { it.capture.row.hasTimeoutFailure },
            Column("timeoutMarginMs") { it.capture.row.timeoutMarginMs },
            Column("guardVsControllerCell") { guardBaselineCell(it.capture.row) },
            Column("mRecoveredAtSuppressedLevel") { row ->
                val capture = row.capture.row
                val level = overheatLevelOf(capture)
                level != null && level >= STATIC_GUARD_OVERHEAT_LEVEL &&
                    capture.bokehDecisionRow?.wasCompleted == true && !capture.hasTimeoutFailure
            },
            Column("guardBlindTimeout") { row ->
                val capture = row.capture.row
                val level = overheatLevelOf(capture)
                level != null && level < STATIC_GUARD_OVERHEAT_LEVEL && capture.hasTimeoutFailure
            },
        )

        /**
         * One row per timeout, watchdog failure, or near miss, attributing the binding constraint to the mechanism
         * responsible. This is the ablation-necessity evidence: an admission-only run should retain residual Pacing
         * failures (cross-shot backlog it cannot space out) and a pacing-only run residual Admission failures
         * (single-capture overruns it cannot shrink). Precedence: backlog, then overrun, then upper-bound miss,
         * then throttle.
         */
        private fun buildFailureAttributionColumns(): List<Column<EvaluationTimelineRow>> = listOf(
            Column("trialCaptureNumber") { it.trialCaptureNumber },
            Column("captureIndex") { it.capture.row.captureIndex },
            Column("overheatLevel") { overheatLevelOf(it.capture.row) },
            Column("thermalHeadroom") {
                it.capture.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalHeadroom
            },
            Column("isTimeout") { it.capture.row.hasTimeoutFailure },
            Column("hasWatchdogFailure") { it.capture.row.hasWatchdogFailure },
            Column("isNearMiss") { isNearMiss(it.capture.row) },
            Column("timeoutMarginMs") { it.capture.row.timeoutMarginMs },
            Column("draftWallMs") { it.capture.row.draftWallMs },
            Column("primaryCause") { failurePrimaryCause(it.capture.row) },
            Column("mechanismResponsible") { mechanismResponsible(failurePrimaryCause(it.capture.row)) },
            // Supporting evidence so the attribution is auditable and can be reclassified offline.
            Column("bokehObservedBudgetOverrun") { it.capture.row.bokehObservedBudgetOverrun },
            Column("filterObservedBudgetOverrun") { it.capture.row.filterObservedBudgetOverrun },
            Column("anyUpperBoundMiss") { upperBoundMissed(it.capture.row) },
            Column("pacingDominantDeficit") { it.capture.row.pacingReplay?.beforeDominantDeficit },
            Column("pacingBacklogMs") { it.capture.row.pacingReplay?.before?.backlogMs },
            Column("pacingMaxDraftStartLatencyMs") {
                it.capture.row.pacingReplay?.before?.maxDraftStartLatencyMs
            },
            Column("pacingQueuedDraftCount") { it.capture.row.pacingReplay?.before?.queuedDraftCount },
            Column("appliedPacingDelayMs") { it.capture.row.pacingReplay?.before?.appliedDelayMs },
        )

        private fun overheatLevelOf(capture: CaptureRow): Int? =
            capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.overheatLevel

        /** M (Bokeh) completion rate among captures eligible for it, split at the static-guard overheat threshold. */
        private fun mCompletionRateAtLevel(run: EvaluationRun, atOrAboveGuard: Boolean): Double? {
            val eligible = run.captureRows.filter { capture ->
                val level = overheatLevelOf(capture)
                level != null && capture.bokehDecisionRow != null &&
                    (if (atOrAboveGuard) level >= STATIC_GUARD_OVERHEAT_LEVEL else level < STATIC_GUARD_OVERHEAT_LEVEL)
            }
            return rate(eligible.count { it.bokehDecisionRow?.wasCompleted == true }, eligible.size)
        }

        /** Captures where the guard would suppress M (level >= 4) but the controller ran it safely - the benefit. */
        private fun guardFalsePositiveFixCount(run: EvaluationRun): Int =
            run.captureRows.count { capture ->
                val level = overheatLevelOf(capture)
                level != null && level >= STATIC_GUARD_OVERHEAT_LEVEL &&
                    capture.bokehDecisionRow?.wasCompleted == true && !capture.hasTimeoutFailure
            }

        /** Timeouts below level 4, where the static guard permits optional Draft and so would not have prevented them. */
        private fun guardFalseNegativeExposedCount(run: EvaluationRun): Int =
            run.captureRows.count { capture ->
                val level = overheatLevelOf(capture)
                level != null && level < STATIC_GUARD_OVERHEAT_LEVEL && capture.hasTimeoutFailure
            }

        private fun guardBaselineCell(capture: CaptureRow): String {
            capture.bokehDecisionRow ?: return GUARD_CELL_M_NOT_ELIGIBLE
            val level = overheatLevelOf(capture) ?: return GUARD_CELL_UNKNOWN_LEVEL
            val guardSuppress = level >= STATIC_GUARD_OVERHEAT_LEVEL
            val ranM = capture.bokehDecisionRow?.wasCompleted == true
            val timeout = capture.hasTimeoutFailure
            return when {
                guardSuppress && ranM && !timeout -> GUARD_CELL_RECOVERED_M_SAFE
                guardSuppress && ranM && timeout -> GUARD_CELL_ADMITTED_M_TIMEOUT
                guardSuppress && !ranM -> GUARD_CELL_AGREE_SUPPRESS
                !guardSuppress && ranM && !timeout -> GUARD_CELL_AGREE_PERMIT_SAFE
                !guardSuppress && ranM && timeout -> GUARD_CELL_GUARD_BLIND_TIMEOUT
                !guardSuppress && !ranM && !timeout -> GUARD_CELL_TIGHTENED_SAFE
                else -> GUARD_CELL_TIGHTENED_TIMEOUT
            }
        }

        private fun isNearMiss(capture: CaptureRow): Boolean {
            val marginMs = capture.timeoutMarginMs ?: return false
            return marginMs <= NEAR_MISS_MARGIN_MS
        }

        private fun isFailureOrNearMiss(capture: CaptureRow): Boolean =
            capture.hasTimeoutFailure || capture.hasWatchdogFailure || isNearMiss(capture)

        private fun failurePrimaryCause(capture: CaptureRow): String {
            val pacingReplay = capture.pacingReplay
            val pacing = pacingReplay?.before
            if (pacing != null) {
                val backlogPlusLatencyMs = pacing.backlogMs + (pacing.maxDraftStartLatencyMs ?: 0L)
                if (pacingReplay.beforeDominantDeficit == PACING_DOMINANT_BACKLOG && backlogPlusLatencyMs > 0L) {
                    return CAUSE_CROSS_SHOT_BACKLOG
                }
            }
            if (capture.bokehObservedBudgetOverrun == true || capture.filterObservedBudgetOverrun == true) {
                return CAUSE_SINGLE_CAPTURE_OVERRUN
            }
            if (upperBoundMissed(capture)) {
                return CAUSE_PREDICTION_MISS
            }
            if (throttleObserved(capture)) {
                return CAUSE_THROTTLE_RAMP
            }
            return CAUSE_UNATTRIBUTED
        }

        private fun mechanismResponsible(cause: String): String = when (cause) {
            CAUSE_CROSS_SHOT_BACKLOG -> MECHANISM_PACING
            CAUSE_SINGLE_CAPTURE_OVERRUN -> MECHANISM_ADMISSION
            CAUSE_PREDICTION_MISS -> MECHANISM_PREDICTOR
            CAUSE_THROTTLE_RAMP -> MECHANISM_ENVIRONMENT
            else -> MECHANISM_UNATTRIBUTED
        }

        private fun throttleObserved(capture: CaptureRow): Boolean {
            val thermal = capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot ?: return false
            return thermal.overheatLevel >= STATIC_GUARD_OVERHEAT_LEVEL ||
                thermal.thermalHeadroom >= THERMAL_HEADROOM_THROTTLING
        }

        private fun upperBoundMissed(capture: CaptureRow): Boolean {
            val decisionRows = listOfNotNull(
                capture.bokehDecisionRow,
                capture.filterDecisionRow,
                capture.decodingDecisionRow,
                capture.overlayWatermarkDecisionRow,
            )
            return decisionRows.any { row -> admissionSheetRow(capture, row)?.sequenceUpperBoundMiss() == true }
        }

        private fun appliedDelaySumByDominant(run: EvaluationRun, dominantDeficit: String): Long =
            run.pacingRows.filter { pacing -> pacing.beforeDominantDeficit == dominantDeficit }
                .sumOf { pacing -> pacing.before.appliedDelayMs }

        private fun absPredictionErrors(captures: List<EnrichedCaptureRow>): List<Double> =
            captures.flatMap { capture -> capture.row.nodeRows }
                .filter { row -> row.isAdmissionWorkload && row.prediction != null }
                .mapNotNull { row -> row.sequencePredictionResidualMs()?.let(::abs) }

        private fun dominantResolution(run: EvaluationRun): String? =
            run.captureRows.groupingBy { capture ->
                "${capture.metrics.resultImageSize.width}x${capture.metrics.resultImageSize.height}"
            }.eachCount().maxByOrNull { entry -> entry.value }?.key

        private fun sizeBucketInferred(run: EvaluationRun): String? =
            run.captureRows.groupingBy { capture ->
                sizeBucketNameOf(capture.metrics.resultImageSize.width, capture.metrics.resultImageSize.height)
            }.eachCount().maxByOrNull { entry -> entry.value }?.key

        private fun sizeBucketNameOf(width: Int, height: Int): String {
            val megaPixels =
                (width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)).toDouble() / 1_000_000.0
            return SizeBucket.entries.minByOrNull { bucket -> abs(megaPixels - bucket.megaPixels) }?.name
                ?: SizeBucket.MP12.name
        }

        private fun targetConfigInferred(run: EvaluationRun): String {
            val hasM = run.captureRows.any { capture -> capture.bokehDecisionRow != null }
            val hasS = run.captureRows.any { capture ->
                capture.filterDecisionRow != null || capture.decodingDecisionRow != null ||
                    capture.overlayWatermarkDecisionRow != null
            }
            return when {
                hasM && hasS -> TARGET_CONFIG_M_AND_S
                hasM -> TARGET_CONFIG_M_ONLY
                hasS -> TARGET_CONFIG_S_ONLY
                else -> TARGET_CONFIG_NONE
            }
        }

        private fun armSignatureInferred(run: EvaluationRun): String {
            val admissionActive = run.admissionRows.any { row -> row.nodeRow.wasSkipped }
            val pacingActive = run.pacingRows.any { pacing -> pacing.before.appliedDelayMs > 0L }
            return when {
                admissionActive && pacingActive -> ARM_FULL
                admissionActive -> ARM_ADMISSION_ONLY
                pacingActive -> ARM_PACING_ONLY
                else -> ARM_INACTIVE
            }
        }

        private fun buildEvaluationNotes(): List<ReplayNote> = listOf(
            ReplayNote(
                topic = "Evaluation session",
                note = "Each export evaluates the newest 30 stored captures as one session. sessionComplete is false " +
                    "when fewer than 30 captures are available. The exporter does not attach manually configured " +
                    "environment labels; record the test condition in the exported file name after pulling it.",
            ),
            ReplayNote(
                topic = "RQ1 safety outcome",
                note = "Timeout and watchdog failures are separate outcomes. firstTimeoutCaptureNumber is right " +
                    "censored at captureCount when timeoutRightCensored is true.",
            ),
            ReplayNote(
                topic = "RQ1 feature outcome",
                note = "M is represented by the Bokeh workload and S by the Filter workload in this implementation. " +
                    "Completed means the admitted node produced an observed positive duration; it is an execution " +
                    "proxy, not a perceptual image-quality score.",
            ),
            ReplayNote(
                topic = "RQ2 cost outcome",
                note = "Shot-to-shot and applied pacing delay distributions quantify user-visible pacing cost. " +
                    "Skip counts separate admission-related feature loss from pacing-related waiting.",
            ),
            ReplayNote(
                topic = "RQ3 prediction outcome",
                note = "Upper-bound coverage uses only fully observed suffixes. Online skips have no actual workload " +
                    "duration, so unnecessary-skip claims require shadow execution or a separate offline oracle.",
            ),
            ReplayNote(
                topic = "RQ3 transition outcome",
                note = "RQ3Timeline aligns thermal, memory, prediction, admission, pacing, and deadline outcomes by " +
                    "trial capture number to analyze cold start and throttle ramps.",
            ),
            ReplayNote(
                topic = "Cross-session statistics",
                note = "Aggregate rates, confidence intervals, and time-to-first-timeout survival analysis must use " +
                    "each exported workbook/session as the independent unit, not individual captures.",
            ),
            ReplayNote(
                topic = "RunContext and RunScorecard",
                note = "RunContext self-describes this workbook (device, resolution, size bucket, starting and " +
                    "observed overheat, memory, inferred target config and controller arm) so several workbooks can " +
                    "be aggregated without parsing file names. RunScorecard is one headline row per workbook with the " +
                    "same config keys plus safety, feature, cost, and correctness KPIs; stack RunScorecard rows " +
                    "across arm and condition runs to build the baseline-vs-proposed and the admission-only / " +
                    "pacing-only / full ablation tables. armSignatureInferred is derived from behavior only (a skip " +
                    "implies admission ran, a nonzero delay implies pacing ran) and cannot distinguish an inactive " +
                    "mechanism from an active one that never triggered, so set armLabel and conditionLabel " +
                    "explicitly for the authoritative arm.",
            ),
            ReplayNote(
                topic = "GuardBaseline (static-guard counterfactual)",
                note = "Contrasts the production static thermal guard (skip optional Draft at overheat level >= 4, " +
                    "Section 2.4) with the controller's per-capture decision and the deadline outcome. " +
                    "guardVsControllerCell classifies each capture; mRecoveredAtSuppressedLevel counts the benefit " +
                    "(M ran safely where the guard forbids it) and guardBlindTimeout counts timeouts below level 4 " +
                    "that the guard alone would not have prevented. This is an offline counterfactual over the " +
                    "recorded overheat level, not a separate on-device guard run.",
            ),
            ReplayNote(
                topic = "FailureAttribution (ablation necessity)",
                note = "For every timeout, watchdog failure, or near miss (finished within 10% of the deadline), " +
                    "primaryCause and mechanismResponsible attribute the binding constraint to Pacing (cross-shot " +
                    "backlog), Admission (single-capture budget overrun), Predictor (upper-bound miss), or " +
                    "Environment (throttle). Admission-only runs should retain residual Pacing failures and " +
                    "pacing-only runs residual Admission failures; that separation is the evidence that both " +
                    "mechanisms are necessary. Precedence: backlog, then overrun, then upper-bound miss, then throttle.",
            ),
        )

        private fun rate(count: Int, total: Int): Double? {
            if (total <= 0) {
                return null
            }
            return count.toDouble() / total.toDouble()
        }

        private fun mean(values: List<Double>): Double? {
            if (values.isEmpty()) {
                return null
            }
            return values.average()
        }

        /** Linear interpolation between adjacent sorted values (the common R-7/Excel inclusive percentile form). */
        private fun percentile(values: List<Double>, probability: Double): Double? {
            if (values.isEmpty()) {
                return null
            }
            val sorted = values.sorted()
            val boundedProbability = probability.coerceIn(0.0, 1.0)
            val position = boundedProbability * (sorted.size - 1)
            val lowerIndex = floor(position).toInt()
            val upperIndex = ceil(position).toInt()
            if (lowerIndex == upperIndex) {
                return sorted[lowerIndex]
            }
            val fraction = position - lowerIndex
            return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * fraction
        }

        private fun <T> oneBasedFirstIndex(items: List<T>, predicate: (T) -> Boolean): Int? {
            return items.indexOfFirst(predicate).takeIf { index -> index >= 0 }?.plus(1)
        }

        private fun firstNodePreExecutionMetrics(run: EvaluationRun): List<PreExecutionMetrics> {
            return run.captureRows.mapNotNull { capture ->
                capture.nodeRows.firstOrNull()?.node?.preExecutionMetrics
            }
        }

        private fun overheatLevels(run: EvaluationRun): List<Int> {
            return firstNodePreExecutionMetrics(run).map { metrics -> metrics.thermalSnapshot.overheatLevel }
        }

        private fun admissionSheetRow(capture: CaptureRow, nodeRow: NodeRow?): NodeSheetRow? {
            nodeRow ?: return null
            val index = capture.nodeRows.indexOf(nodeRow)
            if (index < 0) {
                return null
            }
            return NodeSheetRow(capture, index + 1, nodeRow)
        }

        private fun ceilingErrorsMs(run: EvaluationRun): List<Double> {
            return run.captures.mapNotNull { capture ->
                val ceilingMs = capture.row.pacingReplay?.before?.sessionPlannedCeilingMs
                val draftWallMs = capture.row.draftWallMs
                if (ceilingMs == null || draftWallMs == null) {
                    null
                } else {
                    ceilingMs - draftWallMs
                }
            }
        }

        private fun queuePricingErrorsMs(run: EvaluationRun): List<Double> {
            return run.captures.mapNotNull(::queuePricingErrorMs)
        }

        /**
         * Positive means the persisted backlog clock under-priced the real queue wait observed by this capture.
         * New metrics record maxDraftStartLatencyMs directly; legacy rows without it are intentionally excluded.
         */
        private fun queuePricingErrorMs(capture: EnrichedCaptureRow): Double? {
            val pacing = capture.row.pacingReplay?.before ?: return null
            val maxDraftStartLatencyMs = pacing.maxDraftStartLatencyMs ?: return null
            val draftStartMs = capture.row.draftStartUptimeMs ?: return null
            val releaseMs = pacing.decisionUptimeMs + pacing.appliedDelayMs
            val realQueueWaitMs = (draftStartMs - releaseMs).coerceAtLeast(0L)
            val pricedQueueWaitMs = pacing.backlogMs + maxDraftStartLatencyMs
            return realQueueWaitMs.toDouble() - pricedQueueWaitMs.toDouble()
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
            Column("beforeSessionPlannedSequenceKey") { it.row.pacingReplay?.before?.sessionPlannedSequenceKey },
            Column("beforeDraftStartBudgetMs") { it.row.pacingReplay?.before?.draftStartBudgetMs },
            Column("beforeSessionPlannedPredictedMs") {
                it.row.pacingReplay?.before?.sessionPlannedPredictedMs
            },
            Column("beforeSessionPlannedDraftOverheadMs") {
                it.row.pacingReplay?.before?.sessionPlannedDraftOverheadMs
            },
            Column("beforeSessionPlannedCeilingMs") {
                it.row.pacingReplay?.before?.sessionPlannedCeilingMs
            },
            Column("beforeBacklogMs") { it.row.pacingReplay?.before?.backlogMs },
            Column("beforeQueuedDraftCount") { it.row.pacingReplay?.before?.queuedDraftCount },
            Column("beforeQueuedPredictedWorkMs") {
                it.row.pacingReplay?.before?.queuedPredictedWorkMs
            },
            Column("beforeMaxDraftStartLatencyMs") { it.row.pacingReplay?.before?.maxDraftStartLatencyMs },
            Column("beforeMaxDraftSequenceDurationMs") {
                it.row.pacingReplay?.before?.maxDraftSequenceDurationMs
            },
            Column("beforeLevelDeficitMs") { it.row.pacingReplay?.before?.levelDeficitMs },
            Column("beforeBacklogDeficitMs") { it.row.pacingReplay?.before?.backlogDeficitMs },
            Column("beforeDominantDeficit") { it.row.pacingReplay?.beforeDominantDeficit },
            Column("beforeAppliedDelayMs") { it.row.pacingReplay?.before?.appliedDelayMs },
            // Ceiling calibration: recorded per-capture ceiling minus this draft's wall time
            // (positive = ceiling too high = over-pacing pressure, negative = ceiling undershot the draft).
            Column("ceilingErrorMs") {
                val ceilingMs = it.row.pacingReplay?.before?.sessionPlannedCeilingMs
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
                val learnedMs = it.row.pacingReplay?.before?.sessionPlannedDraftOverheadMs
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
                val predMs = it.row.pacingReplay?.before?.sessionPlannedPredictedMs
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
            // delay): the real backlog to compare against the priced beforeBacklogMs + beforeMaxDraftStartLatencyMs.
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
            Column("ceilingCrossSizeContaminationMs") {
                val obsMaxMs = it.row.pacingReplay?.before?.maxDraftSequenceDurationMs
                val sizeScopedMs = it.wallBase.sizeScopedObservedMaxDraftMs
                if (obsMaxMs != null && sizeScopedMs != null) (obsMaxMs - sizeScopedMs).coerceAtLeast(0L) else null
            },
            Column("") { "" },
            Column("captureTimeoutMs") { it.row.pacingReplay?.captureTimeoutMs },
            Column("draftStartLatencyInference") { it.row.pacingReplay?.draftStartLatencyInference },
            Column("inferredDraftStartLatencyMs") { it.row.pacingReplay?.inferredDraftStartLatencyMs },
            Column("draftStartLatencyMinMs") { it.row.pacingReplay?.draftStartLatencyMinMs },
            Column("draftStartLatencyMaxMs") { it.row.pacingReplay?.draftStartLatencyMaxMs },
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
                topic = "Pacing draft-start latency",
                note = "draft-start latency is read from the recorded runtime input (beforeMaxDraftStartLatencyMs) " +
                    "when present. On legacy rows without it, a positive backlog deficit recovers it exactly; a zero " +
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
                topic = "Wall-base pacing",
                note = "columns for judging a future draft-wall-time-based clock. draftOccupancyUnderpriceMs " +
                    "(draftWall - point sum) is how much the current point clock under-prices a draft; " +
                    "sessionPlannedDraftOverheadMs vs overheadActualMs is the learned overhead's calibration. For a " +
                    "wall-based clock the blockers show up as: inFlightDraftCountAtDecision (drafts whose wall is not " +
                    "observable yet) and freshestWallLagErrorMs (this draft's wall minus the freshest one a wall-EWMA " +
                    "could see) - large during a throttle ramp means an observed-wall clock is stale exactly when it " +
                    "matters. realQueueWaitMs is the pipeline's real time-to-free to score any clock against " +
                    "(compare to beforeBacklogMs + beforeMaxDraftStartLatencyMs). ceilingCrossSizeContaminationMs " +
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
            Column("sessionShotCount") { it.sessionSummary.sessionShotCount },
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
