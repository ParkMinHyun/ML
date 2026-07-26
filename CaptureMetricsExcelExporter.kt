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

        /**
         * False when the stored captures start mid-burst - the retention limit dropped the head of this session, so
         * its opening shots (the coldest predictions and the emptiest backlog) are missing from every RQ statistic.
         */
        val isWholeBurstSession: Boolean = captureRows.firstOrNull()?.isBurstSessionStart == true
        val admissionRows: List<NodeSheetRow> = nodeSheetRows(captures)
            .filter { sheetRow -> sheetRow.nodeRow.isAdmissionWorkload && sheetRow.nodeRow.prediction != null }
        val pacingRows: List<PacingReplay> = captureRows.mapNotNull { capture -> capture.pacingReplay }
        val bokehRows: List<NodeRow> = captureRows.mapNotNull { capture -> capture.bokehDecisionRow }
        val filterRows: List<NodeRow> = captureRows.mapNotNull { capture -> capture.filterDecisionRow }
        val featureEligibleCaptures: List<CaptureRow> = captureRows.filter { capture ->
            capture.bokehDecisionRow != null || capture.filterDecisionRow != null
        }
        val timelineRows: List<EvaluationTimelineRow> = captures.mapIndexed { index, capture ->
            EvaluationTimelineRow(index + 1, capture, this)
        }

        // Both audits pair every decision with its matching observations, so they are quadratic in decisions and
        // every aggregate column reads the same rows. Built once per run, and only for the runs that ask: the
        // BurstPrefix windows are runs too, and they never touch the audits.
        val admissionAuditRows: List<AdmissionDecisionAuditRow> by lazy {
            buildAdmissionDecisionAuditRows(this)
        }
        val pacingAuditRows: List<PacingDecisionAuditRow> by lazy { buildPacingDecisionAuditRows(this) }
    }

    private class EvaluationTimelineRow(
        val trialCaptureNumber: Int,
        val capture: EnrichedCaptureRow,
        /** The session this capture sits in - skip counterfactuals price a skip from its siblings' observations. */
        val run: EvaluationRun,
    )

    /**
     * One observed execution of the same workload as an admission decision. Previous observations are the primary
     * evidence because they were available before the decision; next observations are sensitivity evidence only.
     */
    private class AdmissionAuditObservation(
        val trialCaptureNumber: Int,
        val sheetRow: NodeSheetRow,
    )

    /** Per-admission-decision factual outcome plus previous/next matched observations for retrospective auditing. */
    private class AdmissionDecisionAuditRow(
        val trialCaptureNumber: Int,
        val run: EvaluationRun,
        val sheetRow: NodeSheetRow,
        val previousWorkloadObservation: AdmissionAuditObservation?,
        val nextWorkloadObservation: AdmissionAuditObservation?,
        val recentPreviousWorkloadObservations: List<AdmissionAuditObservation>,
        val previousSequenceObservation: AdmissionAuditObservation?,
        val nextSequenceObservation: AdmissionAuditObservation?,
    )

    /** Per-pacing-decision calibration, its gated capture outcome, and the following capture as sensitivity data. */
    private class PacingDecisionAuditRow(
        val trialCaptureNumber: Int,
        val capture: EnrichedCaptureRow,
        val nextCapture: EnrichedCaptureRow?,
        val run: EvaluationRun,
    )

    /**
     * The same run scored over only its first [prefixCaptureCount] captures. A burst degrades as it goes - the
     * first skip and the first delay land somewhere in the middle - so a whole-session average hides what the user
     * actually experiences in a short burst. Five shots is the usability-representative window; ten and the full
     * session show where the controller starts trading features and shutter time away.
     */
    private class PrefixWindowRow(
        val prefixCaptureCount: Int,
        val run: EvaluationRun,
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
         * draft-sequence estimate uses beforeObservedMaxDraftMs (max over all sizes); the gap between the two is the
         * cross-size contamination a heavy other-size draft (e.g. MP24) adds to this size's (e.g. MP12) estimate.
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
            // The evaluation unit is the newest burst session, not a fixed capture count: bursts run around 30
            // shots but vary, so a fixed window straddles boundaries and mixes two thermal states into one row.
            val rawCaptures = groupCaptures(allRawCaptures).lastOrNull().orEmpty()

            val enrichedNormalCaptures = processCaptures(rawCaptures)
            val replayCaptures = enrichedNormalCaptures.sortedBy { enriched -> enriched.row.captureIndex }
            val evaluationRun = EvaluationRun(replayCaptures)
            val admissionReplayRows = nodeSheetRows(replayCaptures)
                .filter { row -> row.nodeRow.isAdmissionWorkload && row.nodeRow.prediction != null }
            val failureAttributionRows = evaluationRun.timelineRows
                .filter { row -> isFailureOrNearMiss(row.capture.row) }
            val prefixWindowRows = prefixWindowRows(replayCaptures)
            val admissionDecisionAuditRows = evaluationRun.admissionAuditRows
            val pacingDecisionAuditRows = evaluationRun.pacingAuditRows

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
                "BurstPrefix",
                prefixWindowRows,
                buildBurstPrefixColumns(),
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
            writeSheet(
                workbook,
                styles,
                "AdmissionDecisionAudit",
                admissionDecisionAuditRows,
                buildAdmissionDecisionAuditColumns(),
                // Read by filtering on a verdict, so every column carries a title and the autofilter survives.
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "PacingDecisionAudit",
                pacingDecisionAuditRows,
                buildPacingDecisionAuditColumns(),
                optimizeColumnWidths = true,
            )
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
        val groups = groupCaptures(captures)
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
     * Groups captures into burst sessions, split at every [CaptureRow.isBurstSessionStart]. The runtime pacer
     * session id is deliberately not the boundary: it only increments when the draft pipeline drains, which
     * back-to-back bursts never do, so it groups a whole test run into one session.
     */
    private fun groupCaptures(captures: List<CaptureRow>): List<List<CaptureRow>> {
        val groups = mutableListOf<List<CaptureRow>>()
        var currentGroup = mutableListOf<CaptureRow>()

        for (capture in captures) {
            if (capture.isBurstSessionStart && currentGroup.isNotEmpty()) {
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

        /**
         * First shot of a burst: the capture pipeline restarts ppSequenceId at 0, and the first shot of a burst has
         * no preceding shot to measure shot-to-shot time from, so either mark opens a new session.
         */
        val isBurstSessionStart: Boolean
            get() = metrics.ppSequenceId == 0 || metrics.shotToShotTimeMs == null

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
            before.backlogMs + before.draftSequencePacingDurationMs - captureTimeoutMs

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
            (captureTimeoutMs - before.backlogMs - before.draftSequencePacingDurationMs).coerceAtLeast(0.0),
        ).toLong()
        val draftStartLatencyInference: String = when {
            before.maxDraftStartLatencyMs != null -> PACING_LATENCY_RECORDED
            inferredDraftStartLatencyMs != null -> PACING_LATENCY_EXACT
            else -> PACING_LATENCY_BOUNDED
        }

        val afterLevelDeficitMs: Long = computeCaptureAvailableLevelDeficitMs(
            draftSequenceStartBudgetMs = before.draftSequenceStartBudgetMs,
            draftSequencePacingDurationMs = before.draftSequencePacingDurationMs,
        )
        val afterBacklogDeficitMinMs: Long = computeCaptureAvailableBacklogDeficitMs(
            backlogMs = before.backlogMs,
            maxDraftStartLatencyMs = draftStartLatencyMinMs,
            draftSequencePacingDurationMs = before.draftSequencePacingDurationMs,
        )
        val afterBacklogDeficitMaxMs: Long = computeCaptureAvailableBacklogDeficitMs(
            backlogMs = before.backlogMs,
            maxDraftStartLatencyMs = draftStartLatencyMaxMs,
            draftSequencePacingDurationMs = before.draftSequencePacingDurationMs,
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

        // Retrospective admission audit: the immediate previous same-workload observation is primary. Two more
        // previous observations expose its sensitivity to a single GC/contention outlier without replacing it.
        private const val RECENT_WORKLOAD_OBSERVATION_COUNT = 3
        private const val AUDIT_MAX_OVERHEAT_LEVEL_DELTA = 1
        private const val AUDIT_MAX_THERMAL_HEADROOM_DELTA = 0.25f
        private const val AUDIT_MAX_RAM_AVAILABLE_PERCENT_DELTA = 10

        private const val AUDIT_BASIS_FACTUAL_ADMIT = "Factual admitted suffix"
        private const val AUDIT_BASIS_PREVIOUS_EXACT_SEQUENCE = "Previous exact-sequence observed suffix"
        private const val AUDIT_BASIS_PREVIOUS_WORKLOAD = "Previous same-workload own-deadline proxy"
        private const val AUDIT_BASIS_FUTURE_EXACT_SEQUENCE_ONLY = "Future exact-sequence sensitivity only"
        private const val AUDIT_BASIS_FUTURE_WORKLOAD_ONLY = "Future same-workload sensitivity only"
        private const val AUDIT_BASIS_NO_COMPARABLE_OBSERVATION = "No comparable observation"

        private const val AUDIT_VERDICT_OBSERVED_FEASIBLE_ADMIT = "Observed Feasible Admit"
        private const val AUDIT_VERDICT_OBSERVED_INFEASIBLE_ADMIT = "Observed Infeasible Admit"
        private const val AUDIT_VERDICT_OBSERVED_UNSAFE_ADMIT_FEASIBILITY_UNKNOWN =
            "Observed Unsafe Admit, Feasibility Unknown"
        private const val AUDIT_VERDICT_OBSERVED_ADMIT_INCOMPLETE = "Observed Admit, Incomplete Suffix"
        private const val AUDIT_VERDICT_LIKELY_UNNECESSARY_SKIP = "Likely Unnecessary Skip"
        private const val AUDIT_VERDICT_LIKELY_CORRECT_SKIP = "Likely Correct Skip"
        private const val AUDIT_VERDICT_UNCERTAIN_TRANSITION = "Uncertain: Previous/Next Disagree"
        private const val AUDIT_VERDICT_UNIDENTIFIABLE_SKIP = "Unidentifiable Skip"

        private const val AUDIT_CONFIDENCE_HIGH = "High"
        private const val AUDIT_CONFIDENCE_MEDIUM = "Medium"
        private const val AUDIT_CONFIDENCE_LOW = "Low"
        private const val AUDIT_CONFIDENCE_NONE = "None"

        private const val PACING_AUDIT_BASIS_EMPTY_PIPELINE = "Observed delay with empty pipeline"
        private const val PACING_AUDIT_BASIS_NO_CAPTURE_OUTCOME = "No gated-capture outcome"
        private const val PACING_AUDIT_BASIS_CALIBRATION_AND_CAPTURE_OUTCOME =
            "Queue/draft-sequence estimate calibration plus gated-capture outcome"
        private const val PACING_AUDIT_BASIS_CAPTURE_OUTCOME_ONLY = "Gated-capture outcome only"
        private const val PACING_AUDIT_VERDICT_LIKELY_EXCESSIVE = "Likely Excessive Delay"
        private const val PACING_AUDIT_VERDICT_LIKELY_INSUFFICIENT = "Likely Insufficient Delay"
        private const val PACING_AUDIT_VERDICT_UNCERTAIN_CAPTURE_AT_RISK =
            "Uncertain: Gated Capture At Risk"
        private const val PACING_AUDIT_VERDICT_OUTCOME_SUPPORTED_OPTIMALITY_UNKNOWN =
            "Outcome Supported, Delay Optimality Unknown"
        private const val PACING_AUDIT_VERDICT_NO_DELAY_OUTCOME_SUPPORTED = "No-delay Outcome Supported"
        private const val PACING_AUDIT_VERDICT_UNIDENTIFIABLE = "Unidentifiable Pacing Decision"

        // Burst prefixes scored separately in BurstPrefix. Five shots is the usability-representative window most
        // users ever reach; ten and thirty show how far into a burst the controller holds before it trades away.
        private val PREFIX_CAPTURE_COUNTS = listOf(5, 10, 30)

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

        /** Decision-time order: earlier capture first, then earlier node inside the same capture. */
        private fun decisionOrderCompare(
            left: AdmissionAuditObservation,
            right: AdmissionAuditObservation,
        ): Int = compareValuesBy(
            left,
            right,
            { observation -> observation.trialCaptureNumber },
            { observation -> observation.sheetRow.nodeOrder },
        )

        private fun buildAdmissionDecisionAuditRows(run: EvaluationRun): List<AdmissionDecisionAuditRow> {
            val observations = run.captureRows.flatMapIndexed { captureIndex, capture ->
                capture.nodeRows.mapIndexed { nodeIndex, nodeRow ->
                    AdmissionAuditObservation(
                        trialCaptureNumber = captureIndex + 1,
                        sheetRow = NodeSheetRow(capture, nodeIndex + 1, nodeRow),
                    )
                }
            }
            return observations.filter { observation ->
                observation.sheetRow.nodeRow.isAdmissionWorkload &&
                    observation.sheetRow.nodeRow.prediction != null
            }.map { current ->
                val workloadKey = current.sheetRow.nodeRow.node.workloadKey
                val workloadObservations = observations.filter { candidate ->
                    candidate.sheetRow.nodeRow !== current.sheetRow.nodeRow &&
                        candidate.sheetRow.nodeRow.node.workloadKey == workloadKey &&
                        candidate.sheetRow.nodeRow.nodeActualDurationMs != null
                }
                // Ordered by capture, then by node order inside it: a workload that runs twice in one sequence
                // (Decoding) leaves the best-matched evidence there is - same capture, same thermal and memory
                // state, same queue - and dropping the whole capture would throw it away.
                val previousWorkloadObservations = workloadObservations
                    .filter { candidate -> decisionOrderCompare(candidate, current) < 0 }
                    .sortedWith(Comparator { left, right -> decisionOrderCompare(right, left) })
                val nextWorkloadObservations = workloadObservations
                    .filter { candidate -> decisionOrderCompare(candidate, current) > 0 }
                    .sortedWith(Comparator { left, right -> decisionOrderCompare(left, right) })

                val workloadSequenceKey = current.sheetRow.nodeRow.prediction?.workloadSequenceKey
                val exactSequenceObservations = workloadObservations.filter { candidate ->
                    workloadSequenceKey != null &&
                        candidate.sheetRow.nodeRow.prediction?.workloadSequenceKey == workloadSequenceKey &&
                        candidate.sheetRow.isFullyObservedSuffix()
                }
                AdmissionDecisionAuditRow(
                    trialCaptureNumber = current.trialCaptureNumber,
                    run = run,
                    sheetRow = current.sheetRow,
                    previousWorkloadObservation = previousWorkloadObservations.firstOrNull(),
                    nextWorkloadObservation = nextWorkloadObservations.firstOrNull(),
                    recentPreviousWorkloadObservations =
                        previousWorkloadObservations.take(RECENT_WORKLOAD_OBSERVATION_COUNT),
                    previousSequenceObservation = exactSequenceObservations
                        .filter { candidate -> candidate.trialCaptureNumber < current.trialCaptureNumber }
                        .maxByOrNull { candidate -> candidate.trialCaptureNumber },
                    nextSequenceObservation = exactSequenceObservations
                        .filter { candidate -> candidate.trialCaptureNumber > current.trialCaptureNumber }
                        .minByOrNull { candidate -> candidate.trialCaptureNumber },
                )
            }
        }

        private fun buildPacingDecisionAuditRows(run: EvaluationRun): List<PacingDecisionAuditRow> =
            run.captures.mapIndexedNotNull { index, capture ->
                if (capture.row.pacingReplay == null) {
                    null
                } else {
                    PacingDecisionAuditRow(
                        trialCaptureNumber = index + 1,
                        capture = capture,
                        nextCapture = run.captures.getOrNull(index + 1),
                        run = run,
                    )
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
            Column("wholeBurstSessionEvaluated") { it.isWholeBurstSession },
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
            Column("firstWatchdogFailureCaptureNumber") { run ->
                oneBasedFirstIndex(run.captureRows) { capture -> capture.hasWatchdogFailure }
            },
            // Where the run changes regime: every average after the first skip describes a degraded controller, so
            // a whole-session rate is only readable next to the capture number the degradation started at.
            Column("firstMBokehSkipCaptureNumber") { run ->
                oneBasedFirstIndex(run.captureRows) { capture -> capture.bokehDecisionRow?.wasSkipped == true }
            },
            Column("firstSFilterSkipCaptureNumber") { run ->
                oneBasedFirstIndex(run.captureRows) { capture -> capture.filterDecisionRow?.wasSkipped == true }
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
            Column("firstNonzeroPacingDelayCaptureNumber") { run ->
                oneBasedFirstIndex(run.captureRows) { capture ->
                    (capture.pacingReplay?.before?.appliedDelayMs ?: 0L) > 0L
                }
            },
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
            // Where the pacing cost went: delay charged because this capture's own duration estimate exceeded a
            // throttled budget (level-dominant, throttle recovery) vs delay charged to drain a queued backlog the
            // current capture inherited (backlog-dominant, cross-shot recovery). Section 2.4 argues only the latter
            // can recover budget already lost while waiting for the Draft worker, so separating them is load-bearing.
            Column("throttleRecoveryDelayMs") { appliedDelaySumByDominant(it, PACING_DOMINANT_LEVEL) },
            Column("backlogRecoveryDelayMs") { appliedDelaySumByDominant(it, PACING_DOMINANT_BACKLOG) },
            Column("coDominantDelayMs") { appliedDelaySumByDominant(it, PACING_DOMINANT_EQUAL) },
            Column("effectiveShotsPerSecond") { run ->
                val meanMs = mean(run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs?.toDouble() })
                if (meanMs != null && meanMs > 0.0) 1000.0 / meanMs else null
            },
            // What the paced captures realized, as cost context: margins in the near-miss band mean the shutter time
            // did not buy much headroom, margins far above the unpaced population mean it bought more than the
            // deadline needed. Whether each individual delay was the right size is scored in RQ3 with the other
            // decision verdicts; these columns stay here because they price the trade, not the decision.
            Column("emptyPipelineDelayCount") { emptyPipelineDelayCount(it) },
            Column("p05PacedCaptureMarginMs") { run -> percentile(pacedCaptureMarginsMs(run, paced = true), 0.05) },
            Column("medianPacedCaptureMarginMs") { run -> percentile(pacedCaptureMarginsMs(run, paced = true), 0.50) },
            Column("medianUnpacedCaptureMarginMs") { run -> percentile(pacedCaptureMarginsMs(run, paced = false), 0.50) },
            // A delay the queue absorbed cost this capture no margin - only the unabsorbed part can be excessive
            // for its own deadline. Splitting them keeps a zero excess count from reading as "no evidence looked at".
            Column("queueAbsorbedDelayCount") { run ->
                run.captures.count { capture ->
                    val appliedDelayMs = capture.row.pacingReplay?.before?.appliedDelayMs ?: 0L
                    appliedDelayMs > 0L && (realQueueWaitMs(capture) ?: 0L) > 0L
                }
            },
            Column("totalDelayOnOwnCriticalPathMs") { run ->
                run.captures.sumOf { capture -> delayOnOwnCriticalPathMs(capture) ?: 0L }
            },
            Column("ownDeadlineExcessDelayCount") { run ->
                run.captures.count { capture -> (ownDeadlineExcessDelayMs(capture) ?: 0L) > 0L }
            },
            Column("totalOwnDeadlineExcessDelayMs") { run ->
                run.captures.sumOf { capture -> ownDeadlineExcessDelayMs(capture) ?: 0L }
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
            // Feasibility is kept separate from capture safety: a timeout caused by inherited backlog must not by
            // itself turn a budget-feasible admission into evidence that the admission gate was wrong.
            Column("observedFeasibleAdmitDecisionCount") { run ->
                run.admissionRows.count { row -> row.observedActualFeasible() == true }
            },
            Column("observedInfeasibleAdmitDecisionCount") { run ->
                run.admissionRows.count { row -> row.observedActualFeasible() == false }
            },
            Column("observedAdmitFeasibilityRate") { run ->
                val observed = run.admissionRows.mapNotNull { row -> row.observedActualFeasible() }
                rate(observed.count { feasible -> feasible }, observed.size)
            },
            Column("skipRequiresOfflineOracleCount") { run ->
                run.admissionRows.count {
                    row -> row.decisionOutcome() == DecisionOutcome.SKIP_REQUIRES_OFFLINE_ORACLE
                }
            },
            // The skip half of decision quality. unsafeAdmitCount already scores admits against the observed
            // outcome; these price each skip from captures that ran the same workload, so an over-strict controller
            // is visible instead of hiding behind "skip requires offline oracle".
            //
            // Past-only is the decision-time-faithful primary audit. Future-only never supplies the primary verdict;
            // it only shows whether a nearby later observation would reverse the retrospective conclusion.
            Column("previousPricedSkipCaptureCount") { run ->
                directionalSkipCounterfactualSlacksMs(run, previous = true).size
            },
            Column("previousOwnDeadlineUnnecessarySkipCount") { run ->
                directionalSkipCounterfactualSlacksMs(run, previous = true)
                    .count { slackMs -> slackMs >= 0.0 }
            },
            Column("previousOwnDeadlineUnnecessarySkipRate") { run ->
                val slacksMs = directionalSkipCounterfactualSlacksMs(run, previous = true)
                rate(slacksMs.count { slackMs -> slackMs >= 0.0 }, slacksMs.size)
            },
            Column("medianPreviousSkipCounterfactualSlackMs") { run ->
                percentile(directionalSkipCounterfactualSlacksMs(run, previous = true), 0.50)
            },
            Column("nextSensitivityPricedSkipCaptureCount") { run ->
                directionalSkipCounterfactualSlacksMs(run, previous = false).size
            },
            Column("previousNextSkipEvidenceComparedCount") { run ->
                run.captureRows.count { capture -> directionalSkipEvidenceAgreement(run, capture) != null }
            },
            Column("previousNextSkipEvidenceAgreementRate") { run ->
                val compared = run.captureRows.mapNotNull { capture ->
                    directionalSkipEvidenceAgreement(run, capture)
                }
                rate(compared.count { agrees -> agrees }, compared.size)
            },
            Column("likelyUnnecessarySkipDecisionCount") { run ->
                run.admissionAuditRows.count { row ->
                    admissionAuditVerdict(row) == AUDIT_VERDICT_LIKELY_UNNECESSARY_SKIP
                }
            },
            Column("likelyCorrectSkipDecisionCount") { run ->
                run.admissionAuditRows.count { row ->
                    admissionAuditVerdict(row) == AUDIT_VERDICT_LIKELY_CORRECT_SKIP
                }
            },
            Column("uncertainTransitionSkipDecisionCount") { run ->
                run.admissionAuditRows.count { row ->
                    admissionAuditVerdict(row) == AUDIT_VERDICT_UNCERTAIN_TRANSITION
                }
            },
            Column("unidentifiableSkipDecisionCount") { run ->
                run.admissionAuditRows.count { row ->
                    admissionAuditVerdict(row) == AUDIT_VERDICT_UNIDENTIFIABLE_SKIP
                }
            },
            // The third decision family. A delay is graded like an admit or a skip - was this one decision right,
            // given what was observable when it was made - so it belongs beside them rather than with the shutter
            // time it cost, which RQ2 prices. Excessive fires on structural evidence (a delay with nothing queued
            // and nothing in flight); insufficient on a gated capture at risk whose queue or draft estimate was
            // under-priced. Everything else stays unidentifiable on purpose: optimality needs a replay.
            Column("likelyExcessivePacingDecisionCount") { run ->
                run.pacingAuditRows.count { row ->
                    pacingAuditVerdict(row) == PACING_AUDIT_VERDICT_LIKELY_EXCESSIVE
                }
            },
            Column("likelyInsufficientPacingDecisionCount") { run ->
                run.pacingAuditRows.count { row ->
                    pacingAuditVerdict(row) == PACING_AUDIT_VERDICT_LIKELY_INSUFFICIENT
                }
            },
            Column("unidentifiablePacingDecisionCount") { run ->
                run.pacingAuditRows.count { row ->
                    pacingAuditVerdict(row) == PACING_AUDIT_VERDICT_UNIDENTIFIABLE
                }
            },
            Column("draftSequencePacingDurationObservedCount") { run ->
                draftSequencePacingErrorsMs(run).size
            },
            Column("draftSequencePacingUndershootCount") { run ->
                draftSequencePacingErrorsMs(run).count { errorMs -> errorMs < 0.0 }
            },
            Column("draftSequencePacingUndershootRate") { run ->
                rate(
                    draftSequencePacingErrorsMs(run).count { errorMs -> errorMs < 0.0 },
                    draftSequencePacingErrorsMs(run).size,
                )
            },
            Column("minimumDraftSequencePacingErrorMs") { run ->
                draftSequencePacingErrorsMs(run).minOrNull()
            },
            Column("p05DraftSequencePacingErrorMs") { run ->
                percentile(draftSequencePacingErrorsMs(run), 0.05)
            },
            Column("p95DraftSequencePacingErrorMs") { run ->
                percentile(draftSequencePacingErrorsMs(run), 0.95)
            },
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
            Column("draftSequencePacingDurationMs") {
                it.capture.row.pacingReplay?.before?.draftSequencePacingDurationMs
            },
            Column("draftSequencePacingErrorMs") {
                val pacingDurationMs = it.capture.row.pacingReplay?.before?.draftSequencePacingDurationMs
                val draftWallMs = it.capture.row.draftWallMs
                if (pacingDurationMs == null || draftWallMs == null) {
                    null
                } else {
                    pacingDurationMs - draftWallMs
                }
            },
            Column("pacingQueuePricingErrorMs") { row ->
                queuePricingErrorMs(row.capture)
            },
            Column("previousSkipCounterfactualSlackMs") { row ->
                directionalSkipCounterfactualSlackMs(row.run, row.capture.row, previous = true)
            },
            Column("nextSensitivitySkipCounterfactualSlackMs") { row ->
                directionalSkipCounterfactualSlackMs(row.run, row.capture.row, previous = false)
            },
            Column("previousNextSkipEvidenceAgree") { row ->
                directionalSkipEvidenceAgreement(row.run, row.capture.row)
            },
        )

        private fun buildAdmissionDecisionAuditColumns(): List<Column<AdmissionDecisionAuditRow>> = listOf(
            Column("deviceModel") { Build.MODEL },
            Column("sizeBucketInferred") { sizeBucketInferred(it.run) },
            Column("targetConfigInferred") { targetConfigInferred(it.run) },
            Column("armSignatureInferred") { armSignatureInferred(it.run) },
            Column("armLabel") { "" },
            Column("conditionLabel") { "" },
            Column("trialCaptureNumber") { it.trialCaptureNumber },
            Column("captureIndex") { it.sheetRow.capture.captureIndex },
            Column("ppSequenceId") { it.sheetRow.capture.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.sheetRow.capture.metrics.dsMode) },
            Column("resultImageWidth") { it.sheetRow.capture.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.sheetRow.capture.metrics.resultImageSize.height },
            Column("nodeOrder") { it.sheetRow.nodeOrder },
            Column("admissionStage") { it.sheetRow.admissionStage() },
            Column("workloadKey") { it.sheetRow.nodeRow.node.workloadKey },
            Column("workloadSequenceKey") { it.sheetRow.nodeRow.prediction?.workloadSequenceKey },
            Column("budgetMs") { it.sheetRow.nodeRow.node.preExecutionMetrics.budgetMs },
            Column("sequencePredictedDurationMs") {
                it.sheetRow.nodeRow.prediction?.sequencePredictedDurationMs
            },
            Column("sequencePredictedUpperBoundMs") {
                it.sheetRow.nodeRow.prediction?.sequencePredictedUpperBoundMs
            },
            Column("admissionMarginMs") { row ->
                row.sheetRow.nodeRow.prediction?.let { prediction ->
                    row.sheetRow.nodeRow.node.preExecutionMetrics.budgetMs -
                        prediction.sequencePredictedUpperBoundMs
                }
            },
            Column("effectiveAdmit") { it.sheetRow.nodeRow.prediction?.admit },
            Column("skipReason") { it.sheetRow.admissionSkipReason() },
            Column("sessionDemotionApplied") { row ->
                row.sheetRow.inferredBeforeModelAdmit() == true &&
                    row.sheetRow.nodeRow.prediction?.admit == false
            },
            Column("captureTimedOut") { it.sheetRow.capture.hasTimeoutFailure },
            Column("captureWatchdogFailed") { it.sheetRow.capture.hasWatchdogFailure },
            Column("timeoutMarginMs") { it.sheetRow.capture.timeoutMarginMs },
            Column("nodeActualDurationMs") { it.sheetRow.nodeRow.nodeActualDurationMs },
            Column("sequenceActualDurationMs") { it.sheetRow.nodeRow.sequenceActualDurationMs },
            Column("suffixFullyObserved") { it.sheetRow.isFullyObservedSuffix() },
            Column("observedActualFeasible") { it.sheetRow.observedActualFeasible() },
            Column("recordedDecisionOutcome") { it.sheetRow.decisionOutcomeLabel() },
            Column("runQueueWaitMs") {
                it.sheetRow.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.runqueueWaitMs
            },
            Column("cpuUtilizationRatio") {
                it.sheetRow.nodeRow.node.postExecutionMetrics.cpuProcessingSnapshot?.cpuUtilizationRatio
            },
            Column("blockingGcCount") {
                it.sheetRow.nodeRow.node.postExecutionMetrics.gcSnapshot?.blockingGcCount
            },
            Column("blockingGcTimeMs") {
                it.sheetRow.nodeRow.node.postExecutionMetrics.gcSnapshot?.blockingGcTimeMs
            },
            Column("overheatLevel") {
                it.sheetRow.nodeRow.node.preExecutionMetrics.thermalSnapshot.overheatLevel
            },
            Column("thermalStatus") {
                it.sheetRow.nodeRow.node.preExecutionMetrics.thermalSnapshot.thermalStatus
            },
            Column("thermalHeadroom") {
                it.sheetRow.nodeRow.node.preExecutionMetrics.thermalSnapshot.thermalHeadroom
            },
            Column("isLowMemory") {
                it.sheetRow.nodeRow.node.preExecutionMetrics.memorySnapshot.isLowMemory
            },
            Column("ramAvailablePercent") {
                it.sheetRow.nodeRow.node.preExecutionMetrics.memorySnapshot.ramAvailablePercent
            },
            Column("javaHeapUsedPercent") {
                it.sheetRow.nodeRow.node.preExecutionMetrics.memorySnapshot.javaHeapUsedPercent
            },
            Column("nativeHeapAllocatedPercent") {
                it.sheetRow.nodeRow.node.preExecutionMetrics.memorySnapshot.nativeHeapAllocatedPercent
            },
            Column("appliedPacingDelayMs") { row ->
                admissionAuditEnrichedCapture(row, null)?.row?.pacingReplay?.before?.appliedDelayMs
            },
            Column("pacingDominantDeficit") { row ->
                admissionAuditEnrichedCapture(row, null)?.row?.pacingReplay?.beforeDominantDeficit
            },
            Column("pacingBacklogMs") { row ->
                admissionAuditEnrichedCapture(row, null)?.row?.pacingReplay?.before?.backlogMs
            },
            Column("pacingQueuedDraftCount") { row ->
                admissionAuditEnrichedCapture(row, null)?.row?.pacingReplay?.before?.queuedDraftCount
            },
            Column("pacingInFlightDraftCountAtDecision") { row ->
                admissionAuditEnrichedCapture(row, null)?.wallBase?.inFlightDraftCountAtDecision
            },
            Column("previousWorkloadTrialCaptureNumber") {
                it.previousWorkloadObservation?.trialCaptureNumber
            },
            Column("previousWorkloadCaptureIndex") {
                it.previousWorkloadObservation?.sheetRow?.capture?.captureIndex
            },
            Column("previousWorkloadDistanceCaptures") { row ->
                row.previousWorkloadObservation?.let { observation ->
                    row.trialCaptureNumber - observation.trialCaptureNumber
                }
            },
            Column("previousWorkloadNodeDurationMs") {
                it.previousWorkloadObservation?.sheetRow?.nodeRow?.nodeActualDurationMs
            },
            Column("previousWorkloadSequenceKey") {
                it.previousWorkloadObservation?.sheetRow?.nodeRow?.prediction?.workloadSequenceKey
            },
            Column("previousWorkloadExactSequenceMatch") { row ->
                val currentKey = row.sheetRow.nodeRow.prediction?.workloadSequenceKey
                val observedKey =
                    row.previousWorkloadObservation?.sheetRow?.nodeRow?.prediction?.workloadSequenceKey
                if (currentKey == null || observedKey == null) {
                    null
                } else {
                    currentKey == observedKey
                }
            },
            Column("previousWorkloadOwnDeadlineSlackMs") { row ->
                ownDeadlineSlackMs(row, row.previousWorkloadObservation)
            },
            Column("previousWorkloadOverheatLevel") { row ->
                observationPreExecutionMetrics(row.previousWorkloadObservation)
                    ?.thermalSnapshot?.overheatLevel
            },
            Column("previousWorkloadOverheatLevelDelta") { row ->
                overheatLevelDelta(row, row.previousWorkloadObservation)
            },
            Column("previousWorkloadThermalHeadroom") { row ->
                observationPreExecutionMetrics(row.previousWorkloadObservation)
                    ?.thermalSnapshot?.thermalHeadroom
            },
            Column("previousWorkloadThermalHeadroomDelta") { row ->
                thermalHeadroomDelta(row, row.previousWorkloadObservation)
            },
            Column("previousWorkloadIsLowMemory") { row ->
                observationPreExecutionMetrics(row.previousWorkloadObservation)
                    ?.memorySnapshot?.isLowMemory
            },
            Column("previousWorkloadRamAvailablePercent") { row ->
                observationPreExecutionMetrics(row.previousWorkloadObservation)
                    ?.memorySnapshot?.ramAvailablePercent
            },
            Column("previousWorkloadRamAvailablePercentDelta") { row ->
                ramAvailablePercentDelta(row, row.previousWorkloadObservation)
            },
            Column("previousWorkloadPacingBacklogMs") { row ->
                admissionAuditEnrichedCapture(row, row.previousWorkloadObservation)
                    ?.row?.pacingReplay?.before?.backlogMs
            },
            Column("previousWorkloadPacingBacklogDeltaMs") { row ->
                pacingBacklogDeltaMs(row, row.previousWorkloadObservation)
            },
            Column("previousWorkloadQueuedDraftCount") { row ->
                admissionAuditEnrichedCapture(row, row.previousWorkloadObservation)
                    ?.row?.pacingReplay?.before?.queuedDraftCount
            },
            Column("previousWorkloadContextComparable") { row ->
                contextComparable(row, row.previousWorkloadObservation)
            },
            Column("previousWorkloadRunQueueWaitMs") {
                it.previousWorkloadObservation?.sheetRow?.nodeRow?.node?.postExecutionMetrics
                    ?.cpuProcessingSnapshot?.runqueueWaitMs
            },
            Column("previousWorkloadBlockingGcCount") {
                it.previousWorkloadObservation?.sheetRow?.nodeRow?.node?.postExecutionMetrics
                    ?.gcSnapshot?.blockingGcCount
            },
            Column("previousWorkloadBlockingGcTimeMs") {
                it.previousWorkloadObservation?.sheetRow?.nodeRow?.node?.postExecutionMetrics
                    ?.gcSnapshot?.blockingGcTimeMs
            },
            Column("recentPreviousWorkloadObservationCount") {
                it.recentPreviousWorkloadObservations.size
            },
            Column("recentPreviousWorkloadMedianDurationMs") {
                recentPreviousWorkloadMedianDurationMs(it)
            },
            Column("recentPreviousWorkloadMedianOwnDeadlineSlackMs") { row ->
                val marginMs = row.sheetRow.capture.timeoutMarginMs
                val medianDurationMs = recentPreviousWorkloadMedianDurationMs(row)
                if (marginMs == null || medianDurationMs == null) {
                    null
                } else {
                    marginMs.toDouble() - medianDurationMs
                }
            },
            Column("nextWorkloadTrialCaptureNumber") {
                it.nextWorkloadObservation?.trialCaptureNumber
            },
            Column("nextWorkloadCaptureIndex") {
                it.nextWorkloadObservation?.sheetRow?.capture?.captureIndex
            },
            Column("nextWorkloadDistanceCaptures") { row ->
                row.nextWorkloadObservation?.let { observation ->
                    observation.trialCaptureNumber - row.trialCaptureNumber
                }
            },
            Column("nextWorkloadNodeDurationMs") {
                it.nextWorkloadObservation?.sheetRow?.nodeRow?.nodeActualDurationMs
            },
            Column("nextWorkloadSequenceKey") {
                it.nextWorkloadObservation?.sheetRow?.nodeRow?.prediction?.workloadSequenceKey
            },
            Column("nextWorkloadExactSequenceMatch") { row ->
                val currentKey = row.sheetRow.nodeRow.prediction?.workloadSequenceKey
                val observedKey =
                    row.nextWorkloadObservation?.sheetRow?.nodeRow?.prediction?.workloadSequenceKey
                if (currentKey == null || observedKey == null) {
                    null
                } else {
                    currentKey == observedKey
                }
            },
            Column("nextWorkloadOwnDeadlineSlackMs") { row ->
                ownDeadlineSlackMs(row, row.nextWorkloadObservation)
            },
            Column("nextWorkloadOverheatLevel") { row ->
                observationPreExecutionMetrics(row.nextWorkloadObservation)
                    ?.thermalSnapshot?.overheatLevel
            },
            Column("nextWorkloadOverheatLevelDelta") { row ->
                overheatLevelDelta(row, row.nextWorkloadObservation)
            },
            Column("nextWorkloadThermalHeadroom") { row ->
                observationPreExecutionMetrics(row.nextWorkloadObservation)
                    ?.thermalSnapshot?.thermalHeadroom
            },
            Column("nextWorkloadThermalHeadroomDelta") { row ->
                thermalHeadroomDelta(row, row.nextWorkloadObservation)
            },
            Column("nextWorkloadIsLowMemory") { row ->
                observationPreExecutionMetrics(row.nextWorkloadObservation)
                    ?.memorySnapshot?.isLowMemory
            },
            Column("nextWorkloadRamAvailablePercent") { row ->
                observationPreExecutionMetrics(row.nextWorkloadObservation)
                    ?.memorySnapshot?.ramAvailablePercent
            },
            Column("nextWorkloadRamAvailablePercentDelta") { row ->
                ramAvailablePercentDelta(row, row.nextWorkloadObservation)
            },
            Column("nextWorkloadPacingBacklogMs") { row ->
                admissionAuditEnrichedCapture(row, row.nextWorkloadObservation)
                    ?.row?.pacingReplay?.before?.backlogMs
            },
            Column("nextWorkloadPacingBacklogDeltaMs") { row ->
                pacingBacklogDeltaMs(row, row.nextWorkloadObservation)
            },
            Column("nextWorkloadQueuedDraftCount") { row ->
                admissionAuditEnrichedCapture(row, row.nextWorkloadObservation)
                    ?.row?.pacingReplay?.before?.queuedDraftCount
            },
            Column("nextWorkloadContextComparable") { row ->
                contextComparable(row, row.nextWorkloadObservation)
            },
            Column("nextWorkloadRunQueueWaitMs") {
                it.nextWorkloadObservation?.sheetRow?.nodeRow?.node?.postExecutionMetrics
                    ?.cpuProcessingSnapshot?.runqueueWaitMs
            },
            Column("nextWorkloadBlockingGcCount") {
                it.nextWorkloadObservation?.sheetRow?.nodeRow?.node?.postExecutionMetrics
                    ?.gcSnapshot?.blockingGcCount
            },
            Column("nextWorkloadBlockingGcTimeMs") {
                it.nextWorkloadObservation?.sheetRow?.nodeRow?.node?.postExecutionMetrics
                    ?.gcSnapshot?.blockingGcTimeMs
            },
            Column("previousExactSequenceTrialCaptureNumber") {
                it.previousSequenceObservation?.trialCaptureNumber
            },
            Column("previousExactSequenceDistanceCaptures") { row ->
                row.previousSequenceObservation?.let { observation ->
                    row.trialCaptureNumber - observation.trialCaptureNumber
                }
            },
            Column("previousExactSequenceActualDurationMs") {
                it.previousSequenceObservation?.sheetRow?.nodeRow?.sequenceActualDurationMs
            },
            Column("previousExactSequenceBudgetSlackMs") { row ->
                sequenceBudgetSlackMs(row, row.previousSequenceObservation)
            },
            Column("previousExactSequenceContextComparable") { row ->
                contextComparable(row, row.previousSequenceObservation)
            },
            Column("nextExactSequenceTrialCaptureNumber") {
                it.nextSequenceObservation?.trialCaptureNumber
            },
            Column("nextExactSequenceDistanceCaptures") { row ->
                row.nextSequenceObservation?.let { observation ->
                    observation.trialCaptureNumber - row.trialCaptureNumber
                }
            },
            Column("nextExactSequenceActualDurationMs") {
                it.nextSequenceObservation?.sheetRow?.nodeRow?.sequenceActualDurationMs
            },
            Column("nextExactSequenceBudgetSlackMs") { row ->
                sequenceBudgetSlackMs(row, row.nextSequenceObservation)
            },
            Column("nextExactSequenceContextComparable") { row ->
                contextComparable(row, row.nextSequenceObservation)
            },
            Column("previousAllSkippedWorkloadsOwnDeadlineSlackMs") { row ->
                directionalSkipCounterfactualSlackMs(row.run, row.sheetRow.capture, previous = true)
            },
            Column("nextAllSkippedWorkloadsOwnDeadlineSlackMs") { row ->
                directionalSkipCounterfactualSlackMs(row.run, row.sheetRow.capture, previous = false)
            },
            Column("previousNextAllSkippedEvidenceAgree") { row ->
                directionalSkipEvidenceAgreement(row.run, row.sheetRow.capture)
            },
            Column("auditEvidenceBasis") { admissionAuditEvidenceBasis(it) },
            Column("previousNextEvidenceAgree") { admissionAuditEvidenceAgreement(it) },
            Column("auditVerdict") { admissionAuditVerdict(it) },
            Column("auditConfidence") { admissionAuditConfidence(it) },
        )

        private fun buildPacingDecisionAuditColumns(): List<Column<PacingDecisionAuditRow>> = listOf(
            Column("deviceModel") { Build.MODEL },
            Column("sizeBucketInferred") { sizeBucketInferred(it.run) },
            Column("targetConfigInferred") { targetConfigInferred(it.run) },
            Column("armSignatureInferred") { armSignatureInferred(it.run) },
            Column("armLabel") { "" },
            Column("conditionLabel") { "" },
            Column("trialCaptureNumber") { it.trialCaptureNumber },
            Column("captureIndex") { it.capture.row.captureIndex },
            Column("ppSequenceId") { it.capture.row.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.capture.row.metrics.dsMode) },
            Column("resultImageWidth") { it.capture.row.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.capture.row.metrics.resultImageSize.height },
            Column("overheatLevel") { overheatLevelOf(it.capture.row) },
            Column("thermalHeadroom") {
                it.capture.row.nodeRows.firstOrNull()
                    ?.node?.preExecutionMetrics?.thermalSnapshot?.thermalHeadroom
            },
            Column("isLowMemory") {
                it.capture.row.nodeRows.firstOrNull()
                    ?.node?.preExecutionMetrics?.memorySnapshot?.isLowMemory
            },
            Column("decisionUptimeMs") { it.capture.row.pacingReplay?.before?.decisionUptimeMs },
            Column("appliedDelayMs") { it.capture.row.pacingReplay?.before?.appliedDelayMs },
            Column("dominantDeficit") { it.capture.row.pacingReplay?.beforeDominantDeficit },
            Column("levelDeficitMs") { it.capture.row.pacingReplay?.before?.levelDeficitMs },
            Column("backlogDeficitMs") { it.capture.row.pacingReplay?.before?.backlogDeficitMs },
            Column("backlogMs") { it.capture.row.pacingReplay?.before?.backlogMs },
            Column("queuedDraftCount") { it.capture.row.pacingReplay?.before?.queuedDraftCount },
            Column("inFlightDraftCountAtDecision") {
                it.capture.wallBase.inFlightDraftCountAtDecision
            },
            Column("maxDraftStartLatencyMs") {
                it.capture.row.pacingReplay?.before?.maxDraftStartLatencyMs
            },
            Column("pricedQueueWaitMs") { row -> pricedQueueWaitMs(row.capture) },
            Column("realQueueWaitMs") { row -> realQueueWaitMs(row.capture) },
            Column("queuePricingErrorMs") { row -> queuePricingErrorMs(row.capture) },
            Column("draftSequencePacingDurationMs") {
                it.capture.row.pacingReplay?.before?.draftSequencePacingDurationMs
            },
            Column("draftWallMs") { it.capture.row.draftWallMs },
            Column("draftSequencePacingErrorMs") { row ->
                draftSequencePacingErrorMs(row.capture)
            },
            Column("emptyPipelineDelay") { row -> isEmptyPipelineDelay(row.capture) },
            // Where the delay landed. Absorbed delay never touched this capture's own deadline, so a paced capture
            // finishing thin is not evidence the delay hurt it - and excess can only be claimed on the unabsorbed part.
            Column("delayAbsorbedByQueue") { row ->
                val appliedDelayMs = row.capture.row.pacingReplay?.before?.appliedDelayMs
                val queueWaitMs = realQueueWaitMs(row.capture)
                if (appliedDelayMs == null || queueWaitMs == null) null else appliedDelayMs > 0L && queueWaitMs > 0L
            },
            Column("delayOnOwnCriticalPathMs") { row -> delayOnOwnCriticalPathMs(row.capture) },
            Column("ownDeadlineExcessDelayMs") { row -> ownDeadlineExcessDelayMs(row.capture) },
            // The queued decision is consumed by this draft start, so this row - not the following one - is the
            // factual outcome of the delay. The next capture remains transition sensitivity evidence only.
            Column("captureShotToShotTimeMs") { it.capture.row.metrics.shotToShotTimeMs },
            Column("captureTimedOut") { it.capture.row.hasTimeoutFailure },
            Column("captureWatchdogFailed") { it.capture.row.hasWatchdogFailure },
            Column("captureTimeoutMarginMs") { it.capture.row.timeoutMarginMs },
            Column("captureNearMiss") { row -> isNearMiss(row.capture.row) },
            Column("medianPacedCaptureMarginMs") { row ->
                percentile(pacedCaptureMarginsMs(row.run, paced = true), 0.50)
            },
            Column("medianUnpacedCaptureMarginMs") { row ->
                percentile(pacedCaptureMarginsMs(row.run, paced = false), 0.50)
            },
            Column("marginVsUnpacedMedianMs") { row ->
                val timeoutMarginMs = row.capture.row.timeoutMarginMs
                val noDelayMedianMs = percentile(pacedCaptureMarginsMs(row.run, paced = false), 0.50)
                if (timeoutMarginMs == null || noDelayMedianMs == null) {
                    null
                } else {
                    timeoutMarginMs.toDouble() - noDelayMedianMs
                }
            },
            Column("nextCaptureIndex") { it.nextCapture?.row?.captureIndex },
            Column("nextCaptureShotToShotTimeMs") { it.nextCapture?.row?.metrics?.shotToShotTimeMs },
            Column("nextCaptureTimedOut") { it.nextCapture?.row?.hasTimeoutFailure },
            Column("nextCaptureWatchdogFailed") { it.nextCapture?.row?.hasWatchdogFailure },
            Column("nextCaptureTimeoutMarginMs") { it.nextCapture?.row?.timeoutMarginMs },
            Column("nextCaptureNearMiss") { row ->
                row.nextCapture?.row?.let(::isNearMiss)
            },
            Column("nextCaptureOverheatLevel") { row ->
                row.nextCapture?.row?.let(::overheatLevelOf)
            },
            Column("nextCaptureThermalHeadroom") {
                it.nextCapture?.row?.nodeRows?.firstOrNull()
                    ?.node?.preExecutionMetrics?.thermalSnapshot?.thermalHeadroom
            },
            Column("auditEvidenceBasis") { pacingAuditEvidenceBasis(it) },
            Column("auditVerdict") { pacingAuditVerdict(it) },
            Column("auditConfidence") { pacingAuditConfidence(it) },
        )

        /** Prefix windows plus the whole burst, deduplicated and clipped to what was actually captured. */
        private fun prefixWindowRows(captures: List<EnrichedCaptureRow>): List<PrefixWindowRow> =
            (PREFIX_CAPTURE_COUNTS + captures.size).distinct().sorted()
                .filter { count -> count in 1..captures.size }
                .map { count -> PrefixWindowRow(count, EvaluationRun(captures.take(count))) }

        private fun buildBurstPrefixColumns(): List<Column<PrefixWindowRow>> = listOf(
            Column("prefixCaptureCount") { it.prefixCaptureCount },
            Column("startingOverheatLevel") { overheatLevels(it.run).firstOrNull() },
            Column("endingOverheatLevel") { overheatLevels(it.run).lastOrNull() },
            Column("maximumOverheatLevel") { overheatLevels(it.run).maxOrNull() },
            Column("timeoutCount") { row -> row.run.captureRows.count { capture -> capture.hasTimeoutFailure } },
            Column("watchdogFailureCount") { row ->
                row.run.captureRows.count { capture -> capture.hasWatchdogFailure }
            },
            Column("minimumTimeoutMarginMs") { row ->
                row.run.captureRows.mapNotNull { capture -> capture.timeoutMarginMs }.minOrNull()
            },
            Column("medianTimeoutMarginMs") { row ->
                percentile(row.run.captureRows.mapNotNull { capture -> capture.timeoutMarginMs?.toDouble() }, 0.50)
            },
            Column("mBokehAdmitRate") { row ->
                rate(row.run.bokehRows.count { node -> node.wasAdmitted == true }, row.run.bokehRows.size)
            },
            Column("mBokehCompletionRate") { row ->
                rate(row.run.bokehRows.count { node -> node.wasCompleted }, row.run.bokehRows.size)
            },
            Column("mBokehSkipCount") { row -> row.run.bokehRows.count { node -> node.wasSkipped } },
            Column("sFilterCompletionRate") { row ->
                rate(row.run.filterRows.count { node -> node.wasCompleted }, row.run.filterRows.size)
            },
            Column("sFilterSkipCount") { row -> row.run.filterRows.count { node -> node.wasSkipped } },
            Column("fullFeatureSuccessRate") { row ->
                rate(
                    row.run.captureRows.count { capture -> capture.isFullFeatureSuccess },
                    row.run.featureEligibleCaptures.size,
                )
            },
            Column("nonzeroPacingDelayRate") { row ->
                rate(
                    row.run.pacingRows.count { pacing -> pacing.before.appliedDelayMs > 0L },
                    row.run.pacingRows.size,
                )
            },
            Column("totalAppliedPacingDelayMs") { row ->
                row.run.pacingRows.sumOf { pacing -> pacing.before.appliedDelayMs }
            },
            Column("meanAppliedPacingDelayMs") { row ->
                mean(row.run.pacingRows.map { pacing -> pacing.before.appliedDelayMs.toDouble() })
            },
            Column("maximumAppliedPacingDelayMs") { row ->
                row.run.pacingRows.maxOfOrNull { pacing -> pacing.before.appliedDelayMs }
            },
            Column("meanShotToShotTimeMs") { row ->
                mean(row.run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs?.toDouble() })
            },
            Column("p95ShotToShotTimeMs") { row ->
                percentile(
                    row.run.captureRows.mapNotNull { capture -> capture.metrics.shotToShotTimeMs?.toDouble() },
                    0.95,
                )
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
            Column("wholeBurstSessionEvaluated") { it.isWholeBurstSession },
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
            Column("observedInfeasibleAdmitDecisionCount") { run ->
                run.admissionRows.count { row -> row.observedActualFeasible() == false }
            },
            Column("likelyUnnecessarySkipDecisionCount") { run ->
                run.admissionAuditRows.count { row ->
                    admissionAuditVerdict(row) == AUDIT_VERDICT_LIKELY_UNNECESSARY_SKIP
                }
            },
            Column("likelyExcessivePacingDecisionCount") { run ->
                run.pacingAuditRows.count { row ->
                    pacingAuditVerdict(row) == PACING_AUDIT_VERDICT_LIKELY_EXCESSIVE
                }
            },
            Column("likelyInsufficientPacingDecisionCount") { run ->
                run.pacingAuditRows.count { row ->
                    pacingAuditVerdict(row) == PACING_AUDIT_VERDICT_LIKELY_INSUFFICIENT
                }
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

        private fun observationPreExecutionMetrics(
            observation: AdmissionAuditObservation?,
        ): PreExecutionMetrics? = observation?.sheetRow?.nodeRow?.node?.preExecutionMetrics

        private fun admissionAuditEnrichedCapture(
            row: AdmissionDecisionAuditRow,
            observation: AdmissionAuditObservation?,
        ): EnrichedCaptureRow? {
            val trialCaptureNumber = observation?.trialCaptureNumber ?: row.trialCaptureNumber
            return row.run.captures.getOrNull(trialCaptureNumber - 1)
        }

        private fun ownDeadlineSlackMs(
            row: AdmissionDecisionAuditRow,
            observation: AdmissionAuditObservation?,
        ): Long? {
            val timeoutMarginMs = row.sheetRow.capture.timeoutMarginMs ?: return null
            val observedDurationMs = observation?.sheetRow?.nodeRow?.nodeActualDurationMs ?: return null
            return timeoutMarginMs - observedDurationMs
        }

        private fun sequenceBudgetSlackMs(
            row: AdmissionDecisionAuditRow,
            observation: AdmissionAuditObservation?,
        ): Long? {
            val sequenceActualDurationMs =
                observation?.sheetRow?.nodeRow?.sequenceActualDurationMs ?: return null
            return row.sheetRow.nodeRow.node.preExecutionMetrics.budgetMs - sequenceActualDurationMs
        }

        private fun recentPreviousWorkloadMedianDurationMs(row: AdmissionDecisionAuditRow): Double? =
            percentile(
                row.recentPreviousWorkloadObservations.mapNotNull { observation ->
                    observation.sheetRow.nodeRow.nodeActualDurationMs?.toDouble()
                },
                0.50,
            )

        private fun overheatLevelDelta(
            row: AdmissionDecisionAuditRow,
            observation: AdmissionAuditObservation?,
        ): Int? {
            val observedLevel =
                observationPreExecutionMetrics(observation)?.thermalSnapshot?.overheatLevel ?: return null
            val currentLevel = row.sheetRow.nodeRow.node.preExecutionMetrics.thermalSnapshot.overheatLevel
            return currentLevel - observedLevel
        }

        private fun thermalHeadroomDelta(
            row: AdmissionDecisionAuditRow,
            observation: AdmissionAuditObservation?,
        ): Float? {
            val observedHeadroom =
                observationPreExecutionMetrics(observation)?.thermalSnapshot?.thermalHeadroom ?: return null
            val currentHeadroom =
                row.sheetRow.nodeRow.node.preExecutionMetrics.thermalSnapshot.thermalHeadroom
            return currentHeadroom - observedHeadroom
        }

        private fun ramAvailablePercentDelta(
            row: AdmissionDecisionAuditRow,
            observation: AdmissionAuditObservation?,
        ): Int? {
            val observedRam =
                observationPreExecutionMetrics(observation)?.memorySnapshot?.ramAvailablePercent ?: return null
            val currentRam = row.sheetRow.nodeRow.node.preExecutionMetrics.memorySnapshot.ramAvailablePercent
            return currentRam - observedRam
        }

        private fun pacingBacklogDeltaMs(
            row: AdmissionDecisionAuditRow,
            observation: AdmissionAuditObservation?,
        ): Long? {
            observation ?: return null
            val currentBacklogMs =
                admissionAuditEnrichedCapture(row, null)?.row?.pacingReplay?.before?.backlogMs ?: return null
            val observedBacklogMs =
                admissionAuditEnrichedCapture(row, observation)?.row?.pacingReplay?.before?.backlogMs ?: return null
            return currentBacklogMs - observedBacklogMs
        }

        /**
         * Deliberately coarse context screen, not a causal match score. Raw deltas are exported beside it so an
         * external multi-session analysis can apply condition-specific matching rather than trusting this flag.
         */
        private fun contextComparable(
            row: AdmissionDecisionAuditRow,
            observation: AdmissionAuditObservation?,
        ): Boolean? {
            val observed = observationPreExecutionMetrics(observation) ?: return null
            val current = row.sheetRow.nodeRow.node.preExecutionMetrics
            return abs(current.thermalSnapshot.overheatLevel - observed.thermalSnapshot.overheatLevel) <=
                AUDIT_MAX_OVERHEAT_LEVEL_DELTA &&
                abs(current.thermalSnapshot.thermalHeadroom - observed.thermalSnapshot.thermalHeadroom) <=
                AUDIT_MAX_THERMAL_HEADROOM_DELTA &&
                current.memorySnapshot.isLowMemory == observed.memorySnapshot.isLowMemory &&
                abs(current.memorySnapshot.ramAvailablePercent - observed.memorySnapshot.ramAvailablePercent) <=
                AUDIT_MAX_RAM_AVAILABLE_PERCENT_DELTA
        }

        private fun admissionAuditEvidenceBasis(row: AdmissionDecisionAuditRow): String {
            if (row.sheetRow.nodeRow.wasAdmitted == true) {
                return AUDIT_BASIS_FACTUAL_ADMIT
            }
            return when {
                row.previousSequenceObservation != null -> AUDIT_BASIS_PREVIOUS_EXACT_SEQUENCE
                row.previousWorkloadObservation != null -> AUDIT_BASIS_PREVIOUS_WORKLOAD
                row.nextSequenceObservation != null -> AUDIT_BASIS_FUTURE_EXACT_SEQUENCE_ONLY
                row.nextWorkloadObservation != null -> AUDIT_BASIS_FUTURE_WORKLOAD_ONLY
                else -> AUDIT_BASIS_NO_COMPARABLE_OBSERVATION
            }
        }

        private fun admissionAuditPrimarySlackMs(row: AdmissionDecisionAuditRow): Double? {
            sequenceBudgetSlackMs(row, row.previousSequenceObservation)?.let { return it.toDouble() }
            return ownDeadlineSlackMs(row, row.previousWorkloadObservation)?.toDouble()
        }

        private fun admissionAuditSensitivitySlackMs(row: AdmissionDecisionAuditRow): Double? {
            if (row.previousSequenceObservation != null) {
                return sequenceBudgetSlackMs(row, row.nextSequenceObservation)?.toDouble()
            }
            return ownDeadlineSlackMs(row, row.nextWorkloadObservation)?.toDouble()
        }

        private fun admissionAuditEvidenceAgreement(row: AdmissionDecisionAuditRow): Boolean? {
            if (row.sheetRow.nodeRow.wasSkipped.not()) {
                return null
            }
            val primarySlackMs = admissionAuditPrimarySlackMs(row) ?: return null
            val sensitivitySlackMs = admissionAuditSensitivitySlackMs(row) ?: return null
            return (primarySlackMs >= 0.0) == (sensitivitySlackMs >= 0.0)
        }

        private fun admissionAuditVerdict(row: AdmissionDecisionAuditRow): String {
            if (row.sheetRow.nodeRow.wasAdmitted == true) {
                return when (row.sheetRow.observedActualFeasible()) {
                    true -> AUDIT_VERDICT_OBSERVED_FEASIBLE_ADMIT
                    false -> AUDIT_VERDICT_OBSERVED_INFEASIBLE_ADMIT
                    null -> if (row.sheetRow.capture.hasTimeoutOrWatchdogFailure) {
                        AUDIT_VERDICT_OBSERVED_UNSAFE_ADMIT_FEASIBILITY_UNKNOWN
                    } else {
                        AUDIT_VERDICT_OBSERVED_ADMIT_INCOMPLETE
                    }
                }
            }

            val primarySlackMs = admissionAuditPrimarySlackMs(row)
                ?: return AUDIT_VERDICT_UNIDENTIFIABLE_SKIP
            if (admissionAuditEvidenceAgreement(row) == false) {
                return AUDIT_VERDICT_UNCERTAIN_TRANSITION
            }
            return if (primarySlackMs >= 0.0) {
                AUDIT_VERDICT_LIKELY_UNNECESSARY_SKIP
            } else {
                AUDIT_VERDICT_LIKELY_CORRECT_SKIP
            }
        }

        private fun admissionAuditConfidence(row: AdmissionDecisionAuditRow): String {
            if (row.sheetRow.nodeRow.wasAdmitted == true) {
                return if (row.sheetRow.observedActualFeasible() != null) {
                    AUDIT_CONFIDENCE_HIGH
                } else {
                    AUDIT_CONFIDENCE_LOW
                }
            }
            if (admissionAuditPrimarySlackMs(row) == null ||
                admissionAuditEvidenceAgreement(row) == false
            ) {
                return AUDIT_CONFIDENCE_NONE
            }

            val exactSequenceEvidence = row.previousSequenceObservation != null
            val previousObservation = if (exactSequenceEvidence) {
                row.previousSequenceObservation
            } else {
                row.previousWorkloadObservation
            }
            val nextObservation = if (exactSequenceEvidence) {
                row.nextSequenceObservation
            } else {
                row.nextWorkloadObservation
            }
            val previousComparable = contextComparable(row, previousObservation) == true
            val nextComparable = contextComparable(row, nextObservation) == true
            val evidenceAgrees = admissionAuditEvidenceAgreement(row)
            // Grading is on the past evidence alone. Sticky demotion means a workload that gets skipped never runs
            // again in the same burst, so a later observation is structurally absent for exactly the decisions this
            // audit exists to score - requiring one would pin every skip verdict at Medium. A later observation that
            // does exist (an upper-bound skip the controller recovered from) still contributes: agreement lifts the
            // grade, and disagreement is already routed to the uncertain verdict before this point.
            return when {
                exactSequenceEvidence && previousComparable -> AUDIT_CONFIDENCE_HIGH
                exactSequenceEvidence -> AUDIT_CONFIDENCE_MEDIUM
                previousComparable && (nextComparable || evidenceAgrees == true) -> AUDIT_CONFIDENCE_MEDIUM
                else -> AUDIT_CONFIDENCE_LOW
            }
        }

        private fun pricedQueueWaitMs(capture: EnrichedCaptureRow): Long? {
            val pacing = capture.row.pacingReplay?.before ?: return null
            val maxDraftStartLatencyMs = pacing.maxDraftStartLatencyMs ?: return null
            return pacing.backlogMs + maxDraftStartLatencyMs
        }

        private fun realQueueWaitMs(capture: EnrichedCaptureRow): Long? {
            val pacing = capture.row.pacingReplay?.before ?: return null
            val draftStartMs = capture.row.draftStartUptimeMs ?: return null
            val releaseMs = pacing.decisionUptimeMs + pacing.appliedDelayMs
            return (draftStartMs - releaseMs).coerceAtLeast(0L)
        }

        private fun draftSequencePacingErrorMs(capture: EnrichedCaptureRow): Double? {
            val pacingDurationMs =
                capture.row.pacingReplay?.before?.draftSequencePacingDurationMs ?: return null
            val draftWallMs = capture.row.draftWallMs ?: return null
            return pacingDurationMs - draftWallMs
        }

        private fun isEmptyPipelineDelay(capture: EnrichedCaptureRow): Boolean? {
            val pacing = capture.row.pacingReplay?.before ?: return null
            val inFlightCount = capture.wallBase.inFlightDraftCountAtDecision ?: return null
            return pacing.appliedDelayMs > 0L && pacing.queuedDraftCount == 0 && inFlightCount == 0
        }

        private fun pacingAuditEvidenceBasis(row: PacingDecisionAuditRow): String {
            if (isEmptyPipelineDelay(row.capture) == true) {
                return PACING_AUDIT_BASIS_EMPTY_PIPELINE
            }
            val capture = row.capture.row
            val hasCaptureOutcome =
                capture.timeoutMarginMs != null || capture.hasTimeoutOrWatchdogFailure
            if (!hasCaptureOutcome) {
                return PACING_AUDIT_BASIS_NO_CAPTURE_OUTCOME
            }
            val hasCalibrationEvidence =
                queuePricingErrorMs(row.capture) != null ||
                    draftSequencePacingErrorMs(row.capture) != null
            return if (hasCalibrationEvidence) {
                PACING_AUDIT_BASIS_CALIBRATION_AND_CAPTURE_OUTCOME
            } else {
                PACING_AUDIT_BASIS_CAPTURE_OUTCOME_ONLY
            }
        }

        private fun pacingAuditVerdict(row: PacingDecisionAuditRow): String {
            val pacing = row.capture.row.pacingReplay?.before ?: return PACING_AUDIT_VERDICT_UNIDENTIFIABLE
            if (isEmptyPipelineDelay(row.capture) == true) {
                return PACING_AUDIT_VERDICT_LIKELY_EXCESSIVE
            }
            val capture = row.capture.row
            val hasCaptureOutcome =
                capture.timeoutMarginMs != null || capture.hasTimeoutOrWatchdogFailure
            if (!hasCaptureOutcome) {
                return PACING_AUDIT_VERDICT_UNIDENTIFIABLE
            }
            val captureAtRisk = capture.hasTimeoutOrWatchdogFailure || isNearMiss(capture)
            val queueUnderpriced = queuePricingErrorMs(row.capture)?.let { errorMs -> errorMs > 0.0 } == true
            val draftSequencePacingUndershot =
                draftSequencePacingErrorMs(row.capture)?.let { errorMs -> errorMs < 0.0 } == true
            if (captureAtRisk && (queueUnderpriced || draftSequencePacingUndershot)) {
                return PACING_AUDIT_VERDICT_LIKELY_INSUFFICIENT
            }
            if (captureAtRisk) {
                return PACING_AUDIT_VERDICT_UNCERTAIN_CAPTURE_AT_RISK
            }
            return if (pacing.appliedDelayMs > 0L) {
                PACING_AUDIT_VERDICT_OUTCOME_SUPPORTED_OPTIMALITY_UNKNOWN
            } else {
                PACING_AUDIT_VERDICT_NO_DELAY_OUTCOME_SUPPORTED
            }
        }

        private fun pacingAuditConfidence(row: PacingDecisionAuditRow): String {
            return when (pacingAuditVerdict(row)) {
                PACING_AUDIT_VERDICT_LIKELY_EXCESSIVE -> AUDIT_CONFIDENCE_HIGH
                PACING_AUDIT_VERDICT_LIKELY_INSUFFICIENT -> AUDIT_CONFIDENCE_MEDIUM
                PACING_AUDIT_VERDICT_OUTCOME_SUPPORTED_OPTIMALITY_UNKNOWN,
                PACING_AUDIT_VERDICT_NO_DELAY_OUTCOME_SUPPORTED,
                PACING_AUDIT_VERDICT_UNCERTAIN_CAPTURE_AT_RISK -> AUDIT_CONFIDENCE_LOW
                else -> AUDIT_CONFIDENCE_NONE
            }
        }

        private fun directionalObservedDurationMs(
            run: EvaluationRun,
            capture: CaptureRow,
            workloadKey: String,
            previous: Boolean,
        ): Long? {
            val currentIndex = run.captureRows.indexOf(capture)
            if (currentIndex < 0) {
                return null
            }
            val indices = if (previous) {
                (currentIndex - 1 downTo 0)
            } else {
                (currentIndex + 1 until run.captureRows.size)
            }
            for (index in indices) {
                val observedMs = run.captureRows[index].nodeRows.firstOrNull { row ->
                    row.node.workloadKey == workloadKey && row.nodeActualDurationMs != null
                }?.nodeActualDurationMs
                if (observedMs != null) {
                    return observedMs
                }
            }
            return null
        }

        /**
         * Own-deadline slack after restoring every workload this capture skipped. Previous-only is the primary
         * retrospective estimate; next-only is sensitivity evidence for thermal/memory transition bias.
         */
        private fun directionalSkipCounterfactualSlackMs(
            run: EvaluationRun,
            capture: CaptureRow,
            previous: Boolean,
        ): Long? {
            val marginMs = capture.timeoutMarginMs ?: return null
            val skippedRows = capture.nodeRows.filter { row -> row.isAdmissionWorkload && row.wasSkipped }
            if (skippedRows.isEmpty()) {
                return null
            }
            var restoredMs = 0L
            for (row in skippedRows) {
                val workloadKey = row.node.workloadKey ?: return null
                restoredMs += directionalObservedDurationMs(run, capture, workloadKey, previous) ?: return null
            }
            return marginMs - restoredMs
        }

        private fun directionalSkipEvidenceAgreement(
            run: EvaluationRun,
            capture: CaptureRow,
        ): Boolean? {
            val previousSlackMs =
                directionalSkipCounterfactualSlackMs(run, capture, previous = true) ?: return null
            val nextSlackMs =
                directionalSkipCounterfactualSlackMs(run, capture, previous = false) ?: return null
            return (previousSlackMs >= 0L) == (nextSlackMs >= 0L)
        }

        private fun directionalSkipCounterfactualSlacksMs(
            run: EvaluationRun,
            previous: Boolean,
        ): List<Double> = run.captureRows.mapNotNull { capture ->
            directionalSkipCounterfactualSlackMs(run, capture, previous)?.toDouble()
        }

        /**
         * Deadline margin realized by captures whose own draft start consumed a pacing decision that did ([paced])
         * or did not carry delay. The decision was queued at captureAvailable and persisted when this draft start
         * consumed it, so the outcome is on the same row - but the two groups are selected, not assigned: a delay
         * is charged exactly when the queue is deep, which is also when margin is thin. Read the split as a
         * description of the two populations, never as the delay's effect; that needs a replay.
         */
        private fun pacedCaptureMarginsMs(run: EvaluationRun, paced: Boolean): List<Double> =
            run.captureRows.mapNotNull { capture ->
                val delayMs = capture.pacingReplay?.before?.appliedDelayMs
                if (delayMs == null || (delayMs > 0L) != paced) {
                    null
                } else {
                    capture.timeoutMarginMs?.toDouble()
                }
            }

        /**
         * How much of the applied delay actually pushed this capture's own draft start later. A delay is absorbed
         * whenever the pipeline was still busy at release ([realQueueWaitMs] > 0): the draft would have queued
         * anyway, so the delay cost this capture no deadline margin and its whole effect was throttling the next
         * shutter. Only the unabsorbed part sits on this capture's critical path.
         */
        private fun delayOnOwnCriticalPathMs(capture: EnrichedCaptureRow): Long? {
            val appliedDelayMs = capture.row.pacingReplay?.before?.appliedDelayMs ?: return null
            val queueWaitMs = realQueueWaitMs(capture) ?: return null
            return (appliedDelayMs - queueWaitMs).coerceAtLeast(0L)
        }

        /**
         * Delay that could have been dropped without putting this capture past its own deadline - the pacing
         * counterpart of the skip oracle's own-deadline slack, and threshold-free. Zero has two very different
         * causes that the neighbouring columns separate: the delay never reached this capture's critical path
         * (absorbed by the queue), or it did and the capture needed every millisecond of the margin it kept.
         */
        private fun ownDeadlineExcessDelayMs(capture: EnrichedCaptureRow): Long? {
            val criticalPathDelayMs = delayOnOwnCriticalPathMs(capture) ?: return null
            val marginMs = capture.row.timeoutMarginMs ?: return null
            return minOf(criticalPathDelayMs, marginMs.coerceAtLeast(0L))
        }

        /** Delay charged with nothing queued and nothing in flight - no backlog to drain, so it was pure shutter lag. */
        private fun emptyPipelineDelayCount(run: EvaluationRun): Int =
            run.captures.count { enriched -> isEmptyPipelineDelay(enriched) == true }

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
                note = "Each export evaluates the newest burst session: captures are split wherever ppSequenceId " +
                    "restarts at 0 or a capture has no shot-to-shot time, and the last group is the evaluated run. " +
                    "wholeBurstSessionEvaluated is false when retention dropped the head of that burst, so its " +
                    "opening shots are missing. The exporter does not attach manually configured environment " +
                    "labels; record the test condition in the exported file name after pulling it.",
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
                topic = "RQ3 skip oracle",
                note = "previousSkipCounterfactualSlackMs prices every workload a capture skipped from the most " +
                    "recent earlier capture that actually ran that workload key, then asks whether the capture " +
                    "would still have met its own deadline: positive slack means the skip bought that capture " +
                    "nothing. Earlier observations only, so the estimate uses nothing the controller could not have " +
                    "had. It restores the work into that one capture only, so it cannot see the queue the " +
                    "restored work would have left for later shots - that cross-shot cost is what pacing absorbs, " +
                    "so read previousOwnDeadlineUnnecessarySkipRate together with the RQ2 delay columns before " +
                    "calling a skip wrong. Skips with no earlier observation stay unpriced and are excluded from " +
                    "the rate. The next-observation columns are sensitivity only and are usually blank: sticky " +
                    "demotion means a skipped workload does not run again in the same burst.",
            ),
            ReplayNote(
                topic = "AdmissionDecisionAudit",
                note = "Admitted rows are scored factually from the fully observed suffix. For skipped rows, the " +
                    "primary retrospective evidence is the most recent earlier execution of the same workload; an " +
                    "earlier fully observed row with the exact workloadSequenceKey is preferred because its actual " +
                    "suffix can be compared directly with the skipped decision's budget. The closest later " +
                    "observation is sensitivity evidence only: disagreement marks a transition as uncertain. The " +
                    "recent-three median exposes one-sample GC/contention sensitivity. Evidence may come from the " +
                    "same capture when a workload runs twice in one sequence; that is the closest match available. " +
                    "Under sticky demotion a skipped workload never runs again in the burst, so the later-" +
                    "observation columns and the uncertain-transition verdict are usually empty by construction - " +
                    "they fill in only for a skip the controller recovered from. Confidence is therefore graded on " +
                    "the earlier evidence: exact-sequence and comparable context is High. contextComparable is only " +
                    "a coarse screen of thermal and memory state (overheat delta <= 1, thermal-headroom delta <= " +
                    "0.25, same low-memory state, RAM delta <= 10 percentage points); it deliberately ignores queue " +
                    "depth, because the comparison is against the skipped decision's own budget and that budget " +
                    "already shrinks with the queue. The raw deltas remain authoritative. A likely verdict is a " +
                    "local decision audit, not the closed-loop outcome of changing the burst.",
            ),
            ReplayNote(
                topic = "RQ2 delay adequacy",
                note = "RQ2 prices what pacing cost; whether each delay was the right call is graded in RQ3 with " +
                    "the admit and skip verdicts. No column proves a delay was the right size either way: changing " +
                    "one delay changes every later arrival, which needs a closed-loop replay. A persisted pacing " +
                    "decision is consumed by the same capture row's draft start, so the observable factual outcome " +
                    "is that row's timeout margin. p05PacedCaptureMarginMs in the near-miss band means paced " +
                    "captures did not get enough headroom; medianPacedCaptureMarginMs far above " +
                    "medianUnpacedCaptureMarginMs means shutter time bought headroom the deadline did not need - " +
                    "but the two groups are selected, not assigned, since a delay is charged exactly when the queue " +
                    "is deep. emptyPipelineDelayCount must stay 0 - a delay with nothing queued and nothing in " +
                    "flight has no backlog to drain.",
            ),
            ReplayNote(
                topic = "PacingDecisionAudit",
                note = "A nonzero delay with no queued or in-flight draft is strong evidence of excess. " +
                    "delayAbsorbedByQueue separates the far more common case: the pipeline was still busy at " +
                    "release, so the delay never reached this capture's own critical path and cost it no margin - " +
                    "its whole effect was throttling the next shutter. Only delayOnOwnCriticalPathMs can be " +
                    "excessive for this capture, and ownDeadlineExcessDelayMs is how much of it could have been " +
                    "dropped without missing the deadline. A gated capture's " +
                    "near miss/failure together with queue under-pricing or duration-estimate undershoot is evidence " +
                    "that the delay was likely insufficient. A safe gated capture supports the observed outcome but " +
                    "never proves the delay was optimal, so those rows stay low-confidence. The following capture is " +
                    "reported only as transition sensitivity; the sheet does not simulate the changed arrival " +
                    "trajectory.",
            ),
            ReplayNote(
                topic = "Burst prefix windows",
                note = "BurstPrefix scores the same session over its first 5, 10, and 30 captures and then the whole " +
                    "burst. A session average is a mixture: the first skip and the first delay land mid-burst " +
                    "(firstMBokehSkipCaptureNumber, firstNonzeroPacingDelayCaptureNumber), and everything after " +
                    "them is a different regime. The 5-capture row is the window most users actually reach, so a " +
                    "controller that keeps M and zero delay for five shots at overheat level 5 reads as a success " +
                    "there even when the 30-capture row shows heavy skipping.",
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

        private fun draftSequencePacingErrorsMs(run: EvaluationRun): List<Double> {
            return run.captures.mapNotNull { capture ->
                val pacingDurationMs = capture.row.pacingReplay?.before?.draftSequencePacingDurationMs
                val draftWallMs = capture.row.draftWallMs
                if (pacingDurationMs == null || draftWallMs == null) {
                    null
                } else {
                    pacingDurationMs - draftWallMs
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
            val observedQueueWaitMs = realQueueWaitMs(capture) ?: return null
            val modeledQueueWaitMs = pricedQueueWaitMs(capture) ?: return null
            return observedQueueWaitMs.toDouble() - modeledQueueWaitMs.toDouble()
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
            Column("beforeDraftSequenceStartBudgetMs") { it.row.pacingReplay?.before?.draftSequenceStartBudgetMs },
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
            Column("beforeMaxDraftStartLatencyMs") { it.row.pacingReplay?.before?.maxDraftStartLatencyMs },
            Column("beforeMaxDraftSequenceDurationMs") {
                it.row.pacingReplay?.before?.maxDraftSequenceDurationMs
            },
            Column("beforeLevelDeficitMs") { it.row.pacingReplay?.before?.levelDeficitMs },
            Column("beforeBacklogDeficitMs") { it.row.pacingReplay?.before?.backlogDeficitMs },
            Column("beforeDominantDeficit") { it.row.pacingReplay?.beforeDominantDeficit },
            Column("beforeAppliedDelayMs") { it.row.pacingReplay?.before?.appliedDelayMs },
            // Estimate calibration: recorded per-capture duration estimate minus this draft's wall time
            // (positive = estimate too high = over-pacing pressure, negative = estimate undershot the draft).
            Column("draftSequencePacingErrorMs") {
                val pacingDurationMs = it.row.pacingReplay?.before?.draftSequencePacingDurationMs
                val draftWallMs = it.row.draftWallMs
                if (pacingDurationMs != null && draftWallMs != null) pacingDurationMs - draftWallMs else null
            },
            // This draft's real between-node overhead (wall minus node processing) - what the clock's learned
            // overhead term is calibrated against. Compare to beforeDraftSequenceOverheadDurationMs.
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
            // How much the size-agnostic observed max inflates this size's estimate (cross-size contamination): a heavy
            // MP24 draft raising an MP12 capture's reserve. Clamped at 0 (when obsMax is below this size's own max, or
            // freshly reset to 0, the estimate is not cross-size inflated).
            Column("draftSequencePacingCrossSizeContaminationMs") {
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
                note = "sticky demotion is replayed over burst sessions, split where ppSequenceId restarts at 0 or a " +
                    "capture has no shot-to-shot time. pacerSessionId is reported per capture but is not the " +
                    "boundary: it only increments when the draft pipeline drains.",
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
                note = "after pacing reuses the recorded draft-sequence prediction, duration estimate, and backlog. " +
                    "Changes to how the estimate is derived or to backlog reconstruction require a sequential replay " +
                    "with additional raw runtime observations.",
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
                    "draftSequenceOverheadDurationMs vs overheadActualMs is the learned overhead's calibration. For a " +
                    "wall-based clock the blockers show up as: inFlightDraftCountAtDecision (drafts whose wall is not " +
                    "observable yet) and freshestWallLagErrorMs (this draft's wall minus the freshest one a wall-EWMA " +
                    "could see) - large during a throttle ramp means an observed-wall clock is stale exactly when it " +
                    "matters. realQueueWaitMs is the pipeline's real time-to-free to score any clock against " +
                    "(compare to beforeBacklogMs + beforeMaxDraftStartLatencyMs). " +
                    "draftSequencePacingCrossSizeContaminationMs " +
                    "(beforeObservedMaxDraftMs minus sizeScopedObservedMaxDraftMs) is how much a heavier other-size " +
                    "draft inflates this capture's duration estimate - the mixed-size over-pacing channel left after " +
                    "the point prediction is made size-aware.",
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
