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

    private data class EvaluationReadinessRow(
        val researchQuestion: String,
        val metric: String,
        val status: String,
        val evidenceSource: String,
        val limitation: String,
        val requiredAction: String,
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
                "RQ1EndToEnd",
                listOf(evaluationRun),
                buildRq1EndToEndColumns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "RQ2Admission",
                listOf(evaluationRun),
                buildRq2AdmissionQualityColumns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "RQ3Pacing",
                listOf(evaluationRun),
                buildRq3PacingEffectColumns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "SessionTimeline",
                evaluationRun.timelineRows,
                buildSessionTimelineColumns(),
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
            writeSheet(
                workbook,
                styles,
                "DataReadiness",
                buildEvaluationReadinessRows(evaluationRun),
                buildEvaluationReadinessColumns(),
                optimizeColumnWidths = true,
            )
            writeSheet(
                workbook,
                styles,
                "MetricDefinitions",
                buildMetricDefinitions(),
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
        sheet.setDisplayGridlines(false)

        val headerRow = sheet.createRow(0)
        headerRow.heightInPoints = 32f
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
                val minimumWidth = 12 * 256
                if (sheet.getColumnWidth(columnIndex) < minimumWidth) {
                    sheet.setColumnWidth(columnIndex, minimumWidth)
                }
                if (sheet.getColumnWidth(columnIndex) > MAX_EVALUATION_COLUMN_WIDTH) {
                    sheet.setColumnWidth(columnIndex, MAX_EVALUATION_COLUMN_WIDTH)
                }
            }
        } else {
            columns.forEachIndexed { columnIndex, column ->
                val widthInCharacters = if (column.title.isBlank()) {
                    3
                } else {
                    (column.title.length + 2).coerceIn(12, 48)
                }
                sheet.setColumnWidth(columnIndex, widthInCharacters * 256)
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
            val suffixText = "_$suffix"
            val candidate = base.take(MAX_SHEET_NAME_LENGTH - suffixText.length) + suffixText
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

        /** Shutter acceptance inferred from the hard deadline written as acceptance + capture-timeout. */
        val acceptedUptimeMs: Long?
            get() = metrics.timeoutTimestampMs?.minus(MakerFeature.CAPTURE_TIMEOUT_MS)

        /** Acceptance-to-Draft-completion latency used by the paper's deadline-slack definition. */
        val completionLatencyMs: Long?
            get() {
                val acceptedMs = acceptedUptimeMs ?: return null
                val completedMs = draftEndUptimeMs ?: return null
                return (completedMs - acceptedMs).takeIf { latencyMs -> latencyMs >= 0L }
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
            val configuredFeatures = listOfNotNull(bokehDecision, filterDecision)

            return when {
                configuredFeatures.isNotEmpty() && configuredFeatures.all { row -> row.wasCompleted } ->
                    PolicyOutcome.FULL_FEATURE_SUCCESS
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

        /**
         * The instant at which [PreExecutionMetrics.budgetMs] was read. With a persisted deadline this is exact:
         * budget = deadline - now. Older rows without a deadline fall back to the immediately following node-start
         * timestamp and are explicitly labelled as such by [admissionGroundTruthSource].
         */
        fun decisionUptimeMs(): Long? {
            val deadlineMs = capture.metrics.timeoutTimestampMs
            if (deadlineMs != null) {
                return deadlineMs - nodeRow.node.preExecutionMetrics.budgetMs
            }
            return nodeRow.node.startUptimeMs
        }

        /**
         * Factual remaining wall cost of the configuration that actually ran, including predictor/admission time,
         * inter-node gaps, scheduling, and the mandatory tail. Skipped decisions remain unobserved until a future
         * audit persists an explicit per-decision forced-execution flag.
         */
        fun observedRemainingWallMs(): Long? {
            nodeRow.prediction ?: return null
            if (nodeRow.wasAdmitted != true) {
                return null
            }
            if (nodeRow.nodeActualDurationMs == null) {
                return null
            }
            val decisionMs = decisionUptimeMs() ?: return null
            val completedMs = capture.draftEndUptimeMs ?: return null
            return (completedMs - decisionMs).takeIf { durationMs -> durationMs >= 0L }
        }

        fun admissionGroundTruthSource(): String {
            nodeRow.prediction ?: return ADMISSION_GROUND_TRUTH_UNOBSERVED
            if (nodeRow.wasAdmitted != true) {
                return ADMISSION_GROUND_TRUTH_UNOBSERVED
            }
            if (observedRemainingWallMs() == null) {
                return ADMISSION_GROUND_TRUTH_UNOBSERVED
            }
            return if (capture.metrics.timeoutTimestampMs != null) {
                ADMISSION_GROUND_TRUTH_FACTUAL_WALL
            } else {
                ADMISSION_GROUND_TRUTH_NODE_START_FALLBACK
            }
        }

        fun observedActualFeasible(): Boolean? {
            val actualRemainingMs = observedRemainingWallMs() ?: return null
            return actualRemainingMs <= nodeRow.node.preExecutionMetrics.budgetMs
        }

        fun exactWallUpperBoundSlackMs(): Double? {
            if (nodeRow.isControllableOptionalDecision != true || !plannedSuffixFullyExecuted()) {
                return null
            }
            if (admissionGroundTruthSource() != ADMISSION_GROUND_TRUTH_FACTUAL_WALL) {
                return null
            }
            val prediction = nodeRow.prediction ?: return null
            val actualRemainingMs = observedRemainingWallMs() ?: return null
            return prediction.sequencePredictedUpperBoundMs - actualRemainingMs
        }

        fun exactWallUpperBoundMiss(): Boolean? =
            exactWallUpperBoundSlackMs()?.let { slackMs -> slackMs < 0.0 }

        fun sequenceUpperBoundMiss(): Boolean? {
            if (nodeRow.isControllableOptionalDecision != true ||
                nodeRow.wasAdmitted != true ||
                !plannedSuffixFullyExecuted()
            ) {
                return null
            }
            val prediction = nodeRow.prediction ?: return null
            val actualDurationMs = nodeRow.sequenceActualDurationMs ?: return null
            return actualDurationMs > prediction.sequencePredictedUpperBoundMs
        }

        fun fullyObservedSequenceUpperBoundSlackMs(): Double? {
            if (nodeRow.isControllableOptionalDecision != true ||
                nodeRow.wasAdmitted != true ||
                !plannedSuffixFullyExecuted()
            ) {
                return null
            }
            return nodeRow.sequenceUpperBoundSlackMs()
        }

        fun admittedSuffixWatchdogTimedOut(): Boolean {
            if (nodeRow.wasAdmitted != true) {
                return false
            }
            return capture.nodeRows.drop(nodeOrder - 1)
                .any { suffixRow -> suffixRow.node.watchdogTimedOut == true }
        }

        fun decisionOutcome(): DecisionOutcome? {
            val prediction = nodeRow.prediction ?: return null
            if (!nodeRow.isAdmissionWorkload) {
                return null
            }
            if (!prediction.admit) {
                return DecisionOutcome.SKIP_REQUIRES_OFFLINE_ORACLE
            }
            if (admittedSuffixWatchdogTimedOut()) {
                return DecisionOutcome.UNSAFE_ADMIT
            }
            val actualFeasible = observedActualFeasible()
                ?: return DecisionOutcome.ADMIT_OUTCOME_NOT_FULLY_OBSERVED
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

        /**
         * Whether the decision-time planned suffix is exactly the suffix that was later profiled. Planned workloads
         * that were skipped before profiling exist only in workloadSequenceKey, so comparing recorded rows alone
         * would silently score a smaller realized configuration against the larger plan's upper bound.
         */
        fun plannedSuffixKeyMatch(): Boolean? {
            val serializedPlan = nodeRow.prediction?.workloadSequenceKey ?: return null
            val plannedKeys = serializedPlan.split('>')
            if (plannedKeys.any { key -> key.isBlank() }) {
                return null
            }
            val suffixRows = capture.nodeRows.drop(nodeOrder - 1)
            if (suffixRows.isEmpty() || suffixRows.any { row -> row.node.workloadKey == null }) {
                return null
            }
            val observedKeys = suffixRows.mapNotNull { row -> row.node.workloadKey }
            return observedKeys == plannedKeys
        }

        fun plannedSuffixFullyExecuted(): Boolean =
            plannedSuffixKeyMatch() == true && isFullyObservedSuffix()
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

        val isControllableOptionalDecision: Boolean?
            get() {
                if (!isAdmissionWorkload) {
                    return false
                }
                if (!isDecodingWorkload) {
                    return true
                }
                val workloadSequenceKey = prediction?.workloadSequenceKey ?: return null
                return !workloadSequenceKey.contains(WATERMARK_TYPE_FRAME)
            }

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
        CORRECT_ADMIT("Admit, Capture Deadline Met"),
        UNSAFE_ADMIT("Admit, Capture/Suffix Failure"),
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
            setFont(
                workbook.createFont().apply {
                    bold = true
                    color = org.apache.poi.ss.usermodel.IndexedColors.WHITE.index
                },
            )
            fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.DARK_BLUE.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
            verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.CENTER
            wrapText = true
            borderBottom = org.apache.poi.ss.usermodel.BorderStyle.THIN
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
        private val integerStyle: CellStyle = workbook.createCellStyle().apply {
            dataFormat = this@Styles.dataFormat.getFormat("#,##0")
        }
        private val plainIntegerStyle: CellStyle = workbook.createCellStyle().apply {
            dataFormat = this@Styles.dataFormat.getFormat("0")
        }
        private val wrapTextStyle: CellStyle = workbook.createCellStyle().apply {
            wrapText = true
            verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.TOP
        }

        fun styleFor(columnTitle: String, value: Any?): CellStyle? {
            if (columnTitle.equals("note", ignoreCase = true) ||
                columnTitle.equals("limitation", ignoreCase = true) ||
                columnTitle.equals("requiredAction", ignoreCase = true) ||
                columnTitle.equals("evidenceSource", ignoreCase = true)
            ) {
                return wrapTextStyle
            }
            if (value !is Number) {
                return null
            }
            return when {
                columnTitle.endsWith("Ms", ignoreCase = true) -> msStyle
                columnTitle.endsWith("Percent", ignoreCase = true) -> percentStyle
                columnTitle.endsWith("Rate", ignoreCase = true) -> rateStyle
                columnTitle.endsWith("Id", ignoreCase = true) ||
                    columnTitle.endsWith("Index", ignoreCase = true) ||
                    columnTitle.endsWith("Number", ignoreCase = true) ||
                    columnTitle.endsWith("Width", ignoreCase = true) ||
                    columnTitle.endsWith("Height", ignoreCase = true) ||
                    columnTitle.endsWith("Level", ignoreCase = true) -> plainIntegerStyle
                value is Byte || value is Short || value is Int || value is Long -> integerStyle
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
        private const val ADMISSION_GROUND_TRUTH_FACTUAL_WALL = "FACTUAL_SELECTED_CONFIG_WALL"
        private const val ADMISSION_GROUND_TRUTH_NODE_START_FALLBACK =
            "FACTUAL_SELECTED_CONFIG_NODE_START_FALLBACK"
        private const val ADMISSION_GROUND_TRUTH_UNOBSERVED = "UNOBSERVED"
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
        private const val PACING_ORACLE_NONE = "NONE_UNTIL_VALIDATED_CLOSED_LOOP_TRACE_REPLAY"

        private const val OPTIONAL_OUTCOME_M_AND_S = "M+S"
        private const val OPTIONAL_OUTCOME_M_ONLY = "M-only"
        private const val OPTIONAL_OUTCOME_S_ONLY = "S-only"
        private const val OPTIONAL_OUTCOME_MANDATORY_ONLY = "mandatory-only"

        private const val REQUIREMENT_SAFE_WITH_RECORDED_WORK = "SafeWithRecordedWork"
        private const val REQUIREMENT_ADMISSION = "AdmissionRequiredDiagnostic"
        private const val REQUIREMENT_PACING = "PacingRequiredDiagnostic"
        private const val REQUIREMENT_BOTH = "BothRequiredDiagnostic"

        private const val STATE_SAMPLING_DRAFT_START_SHARED = "Draft-start snapshot shared across node rows"
        private const val EVALUATION_SCHEMA_VERSION = 2

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

        private const val AUDIT_BASIS_FACTUAL_ADMIT = "Factual admitted remaining wall"
        private const val AUDIT_BASIS_PREVIOUS_EXACT_SEQUENCE = "Previous exact-sequence observed suffix"
        private const val AUDIT_BASIS_PREVIOUS_WORKLOAD = "Previous same-workload own-deadline proxy"
        private const val AUDIT_BASIS_FUTURE_EXACT_SEQUENCE_ONLY = "Future exact-sequence sensitivity only"
        private const val AUDIT_BASIS_FUTURE_WORKLOAD_ONLY = "Future same-workload sensitivity only"
        private const val AUDIT_BASIS_NO_COMPARABLE_OBSERVATION = "No comparable observation"

        private const val AUDIT_VERDICT_OBSERVED_FEASIBLE_ADMIT = "Admitted, Capture Deadline Met"
        private const val AUDIT_VERDICT_OBSERVED_INFEASIBLE_ADMIT = "Admitted, Capture Deadline Missed"
        private const val AUDIT_VERDICT_OBSERVED_UNSAFE_ADMIT_FEASIBILITY_UNKNOWN =
            "Admitted, Failure Observed, Deadline Feasibility Unknown"
        private const val AUDIT_VERDICT_OBSERVED_ADMIT_INCOMPLETE = "Observed Admit, Incomplete Suffix"
        private const val AUDIT_VERDICT_LIKELY_UNNECESSARY_SKIP = "History Proxy: Likely Non-binding Skip"
        private const val AUDIT_VERDICT_LIKELY_CORRECT_SKIP = "History Proxy: Likely Binding Skip"
        private const val AUDIT_VERDICT_UNCERTAIN_TRANSITION =
            "History Proxy: Uncertain, Previous/Next Disagree"
        private const val AUDIT_VERDICT_UNIDENTIFIABLE_SKIP = "History Proxy: Unidentifiable Skip"

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

        // Positive-trigger activity inferred from observed behavior only; a skip is evidence of admission activity and
        // a nonzero delay of pacing activity. Absence cannot distinguish a disabled mechanism from one that never fired.
        private const val ACTIVITY_SKIP_AND_DELAY = "SKIP_AND_DELAY_OBSERVED"
        private const val ACTIVITY_SKIP_ONLY = "SKIP_OBSERVED_NO_DELAY"
        private const val ACTIVITY_DELAY_ONLY = "DELAY_OBSERVED_NO_SKIP"
        private const val ACTIVITY_NO_TRIGGER = "NO_SKIP_OR_DELAY_OBSERVED"

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

        private fun <T> mergeColumns(vararg groups: List<Column<T>>): List<Column<T>> {
            val seenTitles = mutableSetOf<String>()
            return groups.flatMap { columns -> columns }
                .filter { column -> column.title.isBlank() || seenTitles.add(column.title) }
        }

        /** Intervals wholly inside this burst. The first row may still contain the previous burst's gap. */
        private fun inSessionShotToShotTimesMs(run: EvaluationRun): List<Long> =
            run.captureRows.drop(1)
                .mapNotNull { capture -> capture.metrics.shotToShotTimeMs }
                .filter { intervalMs -> intervalMs >= 0L }

        private fun captureAcceptanceSpanMs(run: EvaluationRun): Long? {
            if (run.captureRows.size < 2) {
                return null
            }
            val firstAcceptedMs = run.captureRows.first().acceptedUptimeMs ?: return null
            val lastAcceptedMs = run.captureRows.last().acceptedUptimeMs ?: return null
            return (lastAcceptedMs - firstAcceptedMs).takeIf { durationMs -> durationMs >= 0L }
        }

        private fun processingMakespanMs(run: EvaluationRun): Long? {
            val firstAcceptedMs = run.captureRows.firstOrNull()?.acceptedUptimeMs ?: return null
            val completedTimesMs = run.captureRows.mapNotNull { capture -> capture.draftEndUptimeMs }
            if (completedTimesMs.size != run.captureRows.size) {
                return null
            }
            val lastCompletedMs = completedTimesMs.maxOrNull() ?: return null
            return (lastCompletedMs - firstAcceptedMs).takeIf { durationMs -> durationMs >= 0L }
        }

        private fun acceptanceRateWithinObservedSpanPerMinute(run: EvaluationRun): Double? {
            if (run.captureRows.size < 2 ||
                run.captureRows.any { capture -> capture.acceptedUptimeMs == null }
            ) {
                return null
            }
            val captureSpanMs = captureAcceptanceSpanMs(run)
                ?.takeIf { durationMs -> durationMs > 0L }
                ?: return null
            return (run.captureRows.size - 1) * 60_000.0 / captureSpanMs
        }

        private fun optionalWorkOutcome(capture: CaptureRow): String? {
            val mConfigured = capture.bokehDecisionRow != null
            val sConfigured = capture.filterDecisionRow != null
            if (!mConfigured && !sConfigured) {
                return null
            }
            val mCompleted = capture.bokehDecisionRow?.wasCompleted == true
            val sCompleted = capture.filterDecisionRow?.wasCompleted == true
            return when {
                mCompleted && sCompleted -> OPTIONAL_OUTCOME_M_AND_S
                mCompleted -> OPTIONAL_OUTCOME_M_ONLY
                sCompleted -> OPTIONAL_OUTCOME_S_ONLY
                else -> OPTIONAL_OUTCOME_MANDATORY_ONLY
            }
        }

        private fun optionalWorkOutcomeCount(run: EvaluationRun, outcome: String): Int =
            run.captureRows.count { capture -> optionalWorkOutcome(capture) == outcome }

        private fun optionalWorkOutcomeRate(run: EvaluationRun, outcome: String): Double? {
            val observed = run.captureRows.mapNotNull(::optionalWorkOutcome)
            return rate(observed.count { value -> value == outcome }, observed.size)
        }

        private fun timeoutFreeAtCaptureCount(run: EvaluationRun, targetCaptureCount: Int): Boolean? {
            if (!run.isWholeBurstSession) {
                return null
            }
            val evaluated = run.captureRows.take(targetCaptureCount)
            if (evaluated.any { capture -> capture.hasTimeoutFailure }) {
                return false
            }
            return true.takeIf { run.captureRows.size >= targetCaptureCount }
        }

        private fun failureFreeAtCaptureCount(run: EvaluationRun, targetCaptureCount: Int): Boolean? {
            if (!run.isWholeBurstSession) {
                return null
            }
            val evaluated = run.captureRows.take(targetCaptureCount)
            if (evaluated.any { capture -> capture.hasTimeoutOrWatchdogFailure }) {
                return false
            }
            return true.takeIf { run.captureRows.size >= targetCaptureCount }
        }

        private fun controllerRequirementDiagnostic(capture: CaptureRow): String {
            val admissionRequired = listOf(
                capture.bokehObservedBudgetOverrun,
                capture.filterObservedBudgetOverrun,
            ).any { overrun -> overrun == true }
            val pacingRequired = capture.pacingReplay?.before?.backlogDeficitMs?.let { deficitMs ->
                deficitMs > 0L
            } == true
            return when {
                admissionRequired && pacingRequired -> REQUIREMENT_BOTH
                admissionRequired -> REQUIREMENT_ADMISSION
                pacingRequired -> REQUIREMENT_PACING
                else -> REQUIREMENT_SAFE_WITH_RECORDED_WORK
            }
        }

        private fun buildRq1EndToEndColumns(): List<Column<EvaluationRun>> = mergeColumns(
            buildRunContextColumns(),
            listOf(
                Column("timeoutFreeAt30Captures") { timeoutFreeAtCaptureCount(it, 30) },
                Column("failureFreeAt30Captures") { failureFreeAtCaptureCount(it, 30) },
            ),
            buildRq1Columns(),
            listOf(
                Column("mAndSCompletedCount") { optionalWorkOutcomeCount(it, OPTIONAL_OUTCOME_M_AND_S) },
                Column("mAndSCompletedRate") { optionalWorkOutcomeRate(it, OPTIONAL_OUTCOME_M_AND_S) },
                Column("mOnlyCompletedCount") { optionalWorkOutcomeCount(it, OPTIONAL_OUTCOME_M_ONLY) },
                Column("mOnlyCompletedRate") { optionalWorkOutcomeRate(it, OPTIONAL_OUTCOME_M_ONLY) },
                Column("sOnlyCompletedCount") { optionalWorkOutcomeCount(it, OPTIONAL_OUTCOME_S_ONLY) },
                Column("sOnlyCompletedRate") { optionalWorkOutcomeRate(it, OPTIONAL_OUTCOME_S_ONLY) },
                Column("mandatoryOnlyCount") { optionalWorkOutcomeCount(it, OPTIONAL_OUTCOME_MANDATORY_ONLY) },
                Column("mandatoryOnlyRate") { optionalWorkOutcomeRate(it, OPTIONAL_OUTCOME_MANDATORY_ONLY) },
                Column("inSessionShotToShotObservedCount") { inSessionShotToShotTimesMs(it).size },
                Column("captureAcceptanceSpanMs") { captureAcceptanceSpanMs(it) },
                Column("processingMakespanMs") { processingMakespanMs(it) },
                Column("medianShotToShotTimeMs") { run ->
                    percentile(
                        inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() },
                        0.50,
                    )
                },
                Column("p95ShotToShotTimeMs") { run ->
                    percentile(
                        inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() },
                        0.95,
                    )
                },
                Column("p99ShotToShotTimeMs") { run ->
                    percentile(
                        inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() },
                        0.99,
                    )
                },
                Column("acceptanceRateWithinObservedSpanPerMinute") {
                    acceptanceRateWithinObservedSpanPerMinute(it)
                },
                Column("totalRequestedPacingDelayMs") { run ->
                    run.pacingRows.sumOf { pacing -> pacing.before.appliedDelayMs }
                },
                Column("p95RequestedPacingDelayMs") { run ->
                    percentile(run.pacingRows.map { pacing -> pacing.before.appliedDelayMs.toDouble() }, 0.95)
                },
            ),
            buildEnvironmentDiagnosticColumns(),
        )

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
            Column("offlineGuardRecoveryCandidateCount") { offlineGuardRecoveryCandidateCount(it) },
            Column("offlineGuardBlindTimeoutCandidateCount") { offlineGuardBlindTimeoutCandidateCount(it) },
        )

        private fun buildRq2AdmissionQualityColumns(): List<Column<EvaluationRun>> = mergeColumns(
            buildRunContextColumns(),
            buildAdmissionCalibrationColumns(),
        )

        private fun buildPacingCostColumns(): List<Column<EvaluationRun>> = listOf(
            Column("deviceModel") { Build.MODEL },
            Column("captureCount") { it.captureRows.size },
            Column("shotToShotObservedCount") { run ->
                inSessionShotToShotTimesMs(run).size
            },
            Column("totalShotToShotTimeMs") { run ->
                inSessionShotToShotTimesMs(run).sum()
            },
            Column("meanShotToShotTimeMs") { run ->
                mean(inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() })
            },
            Column("medianShotToShotTimeMs") { run ->
                percentile(
                    inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() },
                    0.50,
                )
            },
            Column("p95ShotToShotTimeMs") { run ->
                percentile(
                    inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() },
                    0.95,
                )
            },
            Column("maximumShotToShotTimeMs") { run ->
                inSessionShotToShotTimesMs(run).maxOrNull()
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
                val meanMs = mean(inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() })
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

        private fun buildRq3PacingEffectColumns(): List<Column<EvaluationRun>> = mergeColumns(
            buildRunContextColumns(),
            buildRunScorecardColumns(),
            listOf(
                Column("acceptedTimestampObservedCount") { run ->
                    run.captureRows.count { capture -> capture.acceptedUptimeMs != null }
                },
                Column("meanQueuedDraftCount") { run ->
                    mean(run.pacingRows.map { pacing -> pacing.before.queuedDraftCount.toDouble() })
                },
                Column("maximumQueuedDraftCount") { run ->
                    run.pacingRows.maxOfOrNull { pacing -> pacing.before.queuedDraftCount }
                },
                Column("safeWithRecordedWorkDiagnosticCount") { run ->
                    run.captureRows.count { capture ->
                        controllerRequirementDiagnostic(capture) == REQUIREMENT_SAFE_WITH_RECORDED_WORK
                    }
                },
                Column("admissionRequiredDiagnosticCount") { run ->
                    run.captureRows.count { capture ->
                        controllerRequirementDiagnostic(capture) == REQUIREMENT_ADMISSION
                    }
                },
                Column("pacingRequiredDiagnosticCount") { run ->
                    run.captureRows.count { capture ->
                        controllerRequirementDiagnostic(capture) == REQUIREMENT_PACING
                    }
                },
                Column("bothRequiredDiagnosticCount") { run ->
                    run.captureRows.count { capture ->
                        controllerRequirementDiagnostic(capture) == REQUIREMENT_BOTH
                    }
                },
            ),
            buildPacingCostColumns(),
            buildPacingDiagnosticColumns(),
        )

        private fun buildEnvironmentDiagnosticColumns(): List<Column<EvaluationRun>> = listOf(
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
        )

        private fun buildAdmissionCalibrationColumns(): List<Column<EvaluationRun>> = listOf(
            Column("admissionDecisionCount") { it.admissionRows.size },
            Column("controllableOptionalDecisionCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.isControllableOptionalDecision == true }
            },
            Column("controllabilityUnknownDecisionCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.isControllableOptionalDecision == null }
            },
            Column("admittedDecisionCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.wasAdmitted == true }
            },
            Column("skippedDecisionCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.wasSkipped }
            },
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
            // Primary RQ2 outcome: on controllable admits whose planned suffix fully ran, did the upper bound cover
            // the exact decision-to-Draft-end wall cost?
            Column("exactWallUpperBoundEligibleControllableAdmittedDecisionCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true &&
                        row.nodeRow.wasAdmitted == true &&
                        row.capture.metrics.timeoutTimestampMs != null
                }
            },
            Column("exactWallUpperBoundObservationCount") { run ->
                run.admissionRows.count { row -> row.exactWallUpperBoundSlackMs() != null }
            },
            Column("exactWallUpperBoundIncompleteCount") { run ->
                val eligible = run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true &&
                        row.nodeRow.wasAdmitted == true &&
                        row.capture.metrics.timeoutTimestampMs != null
                }
                val observed = run.admissionRows.count { row -> row.exactWallUpperBoundSlackMs() != null }
                (eligible - observed).coerceAtLeast(0)
            },
            Column("plannedSuffixKeyMismatchEligibleDecisionCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true &&
                        row.nodeRow.wasAdmitted == true &&
                        row.capture.metrics.timeoutTimestampMs != null &&
                        row.plannedSuffixKeyMatch() == false
                }
            },
            Column("plannedSuffixKeyUnknownEligibleDecisionCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true &&
                        row.nodeRow.wasAdmitted == true &&
                        row.capture.metrics.timeoutTimestampMs != null &&
                        row.plannedSuffixKeyMatch() == null
                }
            },
            Column("exactWallUpperBoundMissCount") { run ->
                run.admissionRows.count { row -> row.exactWallUpperBoundMiss() == true }
            },
            Column("observedExactWallUpperBoundMissRate") { run ->
                rate(
                    run.admissionRows.count { row -> row.exactWallUpperBoundMiss() == true },
                    run.admissionRows.count { row -> row.exactWallUpperBoundMiss() != null },
                )
            },
            Column("observedExactWallUpperBoundCoverageRate") { run ->
                val observed = run.admissionRows.count { row -> row.exactWallUpperBoundMiss() != null }
                val misses = run.admissionRows.count { row -> row.exactWallUpperBoundMiss() == true }
                rate(observed - misses, observed)
            },
            Column("worstCaseExactWallUpperBoundMissRate") { run ->
                val eligible = run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true &&
                        row.nodeRow.wasAdmitted == true &&
                        row.capture.metrics.timeoutTimestampMs != null
                }
                val observed = run.admissionRows.count { row -> row.exactWallUpperBoundMiss() != null }
                val misses = run.admissionRows.count { row -> row.exactWallUpperBoundMiss() == true }
                rate(misses + (eligible - observed).coerceAtLeast(0), eligible)
            },
            Column("minimumExactWallUpperBoundSlackMs") { run ->
                run.admissionRows.mapNotNull { row -> row.exactWallUpperBoundSlackMs() }.minOrNull()
            },
            Column("p05ExactWallUpperBoundSlackMs") { run ->
                percentile(run.admissionRows.mapNotNull { row -> row.exactWallUpperBoundSlackMs() }, 0.05)
            },
            Column("medianExactWallUpperBoundExcessMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row ->
                        row.exactWallUpperBoundSlackMs()?.takeIf { slackMs -> slackMs >= 0.0 }
                    },
                    0.50,
                )
            },
            Column("medianExactWallUpperBoundMissMagnitudeMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row ->
                        row.exactWallUpperBoundSlackMs()
                            ?.takeIf { slackMs -> slackMs < 0.0 }
                            ?.let { slackMs -> -slackMs }
                    },
                    0.50,
                )
            },
            Column("p95ExactWallUpperBoundMissMagnitudeMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row ->
                        row.exactWallUpperBoundSlackMs()
                            ?.takeIf { slackMs -> slackMs < 0.0 }
                            ?.let { slackMs -> -slackMs }
                    },
                    0.95,
                )
            },
            // Secondary predictor calibration: node-duration suffix only, on fully observed controllable admits.
            Column("fullyObservedControllableAdmittedDecisionCount") { run ->
                run.admissionRows.count { row -> row.fullyObservedSequenceUpperBoundSlackMs() != null }
            },
            Column("incompleteControllableAdmittedDecisionCount") { run ->
                val admitted = run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true && row.nodeRow.wasAdmitted == true
                }
                val fullyObserved =
                    run.admissionRows.count { row -> row.fullyObservedSequenceUpperBoundSlackMs() != null }
                (admitted - fullyObserved).coerceAtLeast(0)
            },
            Column("fullyObservedControllableAdmittedDecisionRate") { run ->
                rate(
                    run.admissionRows.count { row -> row.fullyObservedSequenceUpperBoundSlackMs() != null },
                    run.admissionRows.count { row ->
                        row.nodeRow.isControllableOptionalDecision == true && row.nodeRow.wasAdmitted == true
                    },
                )
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
                run.admissionRows.mapNotNull { row -> row.fullyObservedSequenceUpperBoundSlackMs() }.minOrNull()
            },
            Column("p05SequenceUpperBoundSlackMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row -> row.fullyObservedSequenceUpperBoundSlackMs() },
                    0.05,
                )
            },
            Column("medianAbsolutePredictionErrorMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row ->
                        if (row.fullyObservedSequenceUpperBoundSlackMs() == null) {
                            null
                        } else {
                            row.nodeRow.sequencePredictionResidualMs()?.let(::abs)
                        }
                    },
                    0.50,
                )
            },
            Column("p95AbsolutePredictionErrorMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row ->
                        if (row.fullyObservedSequenceUpperBoundSlackMs() == null) {
                            null
                        } else {
                            row.nodeRow.sequencePredictionResidualMs()?.let(::abs)
                        }
                    },
                    0.95,
                )
            },
            // Secondary failure phenotypes. Suffix watchdog counts decisions, not distinct watchdog events.
            Column("controllableAdmittedNodeWatchdogTimeoutCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true &&
                        row.nodeRow.wasAdmitted == true &&
                        row.nodeRow.node.watchdogTimedOut == true
                }
            },
            Column("controllableAdmittedNodeWatchdogTimeoutRate") { run ->
                rate(
                    run.admissionRows.count { row ->
                        row.nodeRow.isControllableOptionalDecision == true &&
                            row.nodeRow.wasAdmitted == true &&
                            row.nodeRow.node.watchdogTimedOut == true
                    },
                    run.admissionRows.count { row ->
                        row.nodeRow.isControllableOptionalDecision == true && row.nodeRow.wasAdmitted == true
                    },
                )
            },
            Column("controllableAdmittedDecisionWithSuffixWatchdogTimeoutCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true && row.admittedSuffixWatchdogTimedOut()
                }
            },
            Column("controllableAdmittedDecisionWithSuffixWatchdogTimeoutRate") { run ->
                rate(
                    run.admissionRows.count { row ->
                        row.nodeRow.isControllableOptionalDecision == true && row.admittedSuffixWatchdogTimedOut()
                    },
                    run.admissionRows.count { row ->
                        row.nodeRow.isControllableOptionalDecision == true && row.nodeRow.wasAdmitted == true
                    },
                )
            },
            Column("captureDeadlineMissSharedControllableAdmitDecisionCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true &&
                        row.admissionGroundTruthSource() == ADMISSION_GROUND_TRUTH_FACTUAL_WALL &&
                        row.observedActualFeasible() == false
                }
            },
            Column("captureDeadlineMissSharedControllableAdmitDecisionRate") { run ->
                val observed = run.admissionRows.filter { row ->
                    row.nodeRow.isControllableOptionalDecision == true &&
                        row.admissionGroundTruthSource() == ADMISSION_GROUND_TRUTH_FACTUAL_WALL
                }
                rate(observed.count { row -> row.observedActualFeasible() == false }, observed.size)
            },
            // Exploratory matched-history sensitivity only; it is not factual skip ground truth.
            Column("skipRequiresExplicitAuditDecisionCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.wasSkipped }
            },
            Column("historyProxySkippedCaptureCount") { run ->
                run.captureRows.count { capture ->
                    capture.nodeRows.any { row -> row.isAdmissionWorkload && row.wasSkipped }
                }
            },
            Column("historyProxyPricedSkipCaptureCount") { run ->
                directionalSkipCounterfactualSlacksMs(run, previous = true).size
            },
            Column("historyProxyCoverageRate") { run ->
                val skippedCaptureCount = run.captureRows.count { capture ->
                    capture.nodeRows.any { row -> row.isAdmissionWorkload && row.wasSkipped }
                }
                rate(directionalSkipCounterfactualSlacksMs(run, previous = true).size, skippedCaptureCount)
            },
            Column("historyProxyOwnDeadlineNonBindingSkipCount") { run ->
                directionalSkipCounterfactualSlacksMs(run, previous = true)
                    .count { slackMs -> slackMs >= 0.0 }
            },
            Column("historyProxyOwnDeadlineNonBindingSkipRate") { run ->
                val slacksMs = directionalSkipCounterfactualSlacksMs(run, previous = true)
                rate(slacksMs.count { slackMs -> slackMs >= 0.0 }, slacksMs.size)
            },
            Column("medianHistoryProxySlackMs") { run ->
                percentile(directionalSkipCounterfactualSlacksMs(run, previous = true), 0.50)
            },
            Column("historyProxySensitivityComparedCount") { run ->
                run.captureRows.count { capture -> directionalSkipEvidenceAgreement(run, capture) != null }
            },
            Column("historyProxySensitivityAgreementRate") { run ->
                val compared = run.captureRows.mapNotNull { capture ->
                    directionalSkipEvidenceAgreement(run, capture)
                }
                rate(compared.count { agrees -> agrees }, compared.size)
            },
            Column("coldStartMedianAbsPredictionErrorMs") { run ->
                percentile(absPredictionErrors(run.captures.take(COLD_START_CAPTURE_COUNT)), 0.50)
            },
            Column("steadyStateMedianAbsPredictionErrorMs") { run ->
                percentile(absPredictionErrors(run.captures.takeLast(COLD_START_CAPTURE_COUNT)), 0.50)
            },
            Column("predictionErrorConvergenceDeltaMs") { run ->
                val cold = percentile(absPredictionErrors(run.captures.take(COLD_START_CAPTURE_COUNT)), 0.50)
                val steady = percentile(absPredictionErrors(run.captures.takeLast(COLD_START_CAPTURE_COUNT)), 0.50)
                if (cold != null && steady != null) {
                    cold - steady
                } else {
                    null
                }
            },
        )

        private fun buildPacingDiagnosticColumns(): List<Column<EvaluationRun>> = listOf(
            // These are structural diagnostics, not per-decision causal or minimum-delay verdicts.
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
        )

        private fun buildSessionTimelineColumns(): List<Column<EvaluationTimelineRow>> = listOf(
            Column("trialCaptureNumber") { it.trialCaptureNumber },
            Column("captureIndex") { it.capture.row.captureIndex },
            Column("ppSequenceId") { it.capture.row.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.capture.row.metrics.dsMode) },
            Column("resultImageWidth") { it.capture.row.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.capture.row.metrics.resultImageSize.height },
            Column("shotToShotTimeMs") { it.capture.row.metrics.shotToShotTimeMs },
            Column("acceptedUptimeMs") { it.capture.row.acceptedUptimeMs },
            Column("draftStartUptimeMs") { it.capture.row.draftStartUptimeMs },
            Column("draftEndUptimeMs") { it.capture.row.draftEndUptimeMs },
            Column("completionLatencyMs") { it.capture.row.completionLatencyMs },
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
            Column("optionalWorkOutcome") { optionalWorkOutcome(it.capture.row) },
            Column("controllerRequirementDiagnostic") {
                controllerRequirementDiagnostic(it.capture.row)
            },
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
                admissionSheetRow(it.capture.row, it.capture.row.bokehDecisionRow)
                    ?.fullyObservedSequenceUpperBoundSlackMs()
            },
            Column("mBokehUpperBoundMiss") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.bokehDecisionRow)?.sequenceUpperBoundMiss()
            },
            Column("mBokehObservedRemainingWallMs") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.bokehDecisionRow)?.observedRemainingWallMs()
            },
            Column("mBokehObservedFeasible") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.bokehDecisionRow)?.observedActualFeasible()
            },
            Column("mBokehGroundTruthSource") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.bokehDecisionRow)
                    ?.admissionGroundTruthSource()
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
                admissionSheetRow(it.capture.row, it.capture.row.filterDecisionRow)
                    ?.fullyObservedSequenceUpperBoundSlackMs()
            },
            Column("sFilterUpperBoundMiss") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.filterDecisionRow)?.sequenceUpperBoundMiss()
            },
            Column("sFilterObservedRemainingWallMs") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.filterDecisionRow)?.observedRemainingWallMs()
            },
            Column("sFilterObservedFeasible") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.filterDecisionRow)?.observedActualFeasible()
            },
            Column("sFilterGroundTruthSource") { row ->
                admissionSheetRow(row.capture.row, row.capture.row.filterDecisionRow)
                    ?.admissionGroundTruthSource()
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
            Column("observedActivitySignature") { observedActivitySignature(it.run) },
            Column("trialCaptureNumber") { it.trialCaptureNumber },
            Column("captureIndex") { it.sheetRow.capture.captureIndex },
            Column("ppSequenceId") { it.sheetRow.capture.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.sheetRow.capture.metrics.dsMode) },
            Column("resultImageWidth") { it.sheetRow.capture.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.sheetRow.capture.metrics.resultImageSize.height },
            Column("nodeOrder") { it.sheetRow.nodeOrder },
            Column("admissionStage") { it.sheetRow.admissionStage() },
            Column("controllableOptionalDecision") {
                it.sheetRow.nodeRow.isControllableOptionalDecision
            },
            Column("workloadKey") { it.sheetRow.nodeRow.node.workloadKey },
            Column("workloadSequenceKey") { it.sheetRow.nodeRow.prediction?.workloadSequenceKey },
            Column("budgetMs") { it.sheetRow.nodeRow.node.preExecutionMetrics.budgetMs },
            Column("decisionUptimeMs") { it.sheetRow.decisionUptimeMs() },
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
            Column("admittedNodeWatchdogTimedOut") {
                it.sheetRow.nodeRow.wasAdmitted == true &&
                    it.sheetRow.nodeRow.node.watchdogTimedOut == true
            },
            Column("admittedSuffixWatchdogTimedOut") {
                it.sheetRow.admittedSuffixWatchdogTimedOut()
            },
            Column("timeoutMarginMs") { it.sheetRow.capture.timeoutMarginMs },
            Column("nodeActualDurationMs") { it.sheetRow.nodeRow.nodeActualDurationMs },
            Column("sequenceActualDurationMs") { it.sheetRow.nodeRow.sequenceActualDurationMs },
            Column("suffixFullyObserved") { it.sheetRow.isFullyObservedSuffix() },
            Column("plannedSuffixKeyMatch") { it.sheetRow.plannedSuffixKeyMatch() },
            Column("plannedSuffixFullyExecuted") { it.sheetRow.plannedSuffixFullyExecuted() },
            Column("sequenceUpperBoundSlackMs") {
                it.sheetRow.fullyObservedSequenceUpperBoundSlackMs()
            },
            Column("sequenceUpperBoundMiss") { it.sheetRow.sequenceUpperBoundMiss() },
            Column("observedRemainingWallMs") { it.sheetRow.observedRemainingWallMs() },
            Column("exactWallUpperBoundSlackMs") { it.sheetRow.exactWallUpperBoundSlackMs() },
            Column("exactWallUpperBoundMiss") { it.sheetRow.exactWallUpperBoundMiss() },
            Column("admissionGroundTruthSource") { it.sheetRow.admissionGroundTruthSource() },
            Column("captureDeadlineFeasibleShared") { it.sheetRow.observedActualFeasible() },
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
            Column("observedActivitySignature") { observedActivitySignature(it.run) },
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
        private fun prefixWindowRows(
            captures: List<EnrichedCaptureRow>,
        ): List<PrefixWindowRow> =
            (PREFIX_CAPTURE_COUNTS + captures.size).distinct().sorted()
                .filter { count -> count in 1..captures.size }
                .map { count -> PrefixWindowRow(count, EvaluationRun(captures.take(count))) }

        private fun buildBurstPrefixColumns(): List<Column<PrefixWindowRow>> = listOf(
            Column("prefixCaptureCount") { it.prefixCaptureCount },
            Column("wholeBurstSessionEvaluated") { it.run.isWholeBurstSession },
            Column("confirmatoryPrefixEligible") { it.run.isWholeBurstSession },
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
                mean(inSessionShotToShotTimesMs(row.run).map { intervalMs -> intervalMs.toDouble() })
            },
            Column("p95ShotToShotTimeMs") { row ->
                percentile(
                    inSessionShotToShotTimesMs(row.run).map { intervalMs -> intervalMs.toDouble() },
                    0.95,
                )
            },
        )

        /**
         * One observed-context row per workbook. Controlled policy and condition labels come from the filename/test
         * log; this sheet records what can be inferred from the capture itself.
         */
        private fun buildRunContextColumns(): List<Column<EvaluationRun>> = listOf(
            Column("evaluationSchemaVersion") { EVALUATION_SCHEMA_VERSION },
            Column("deviceManufacturer") { Build.MANUFACTURER },
            Column("deviceModel") { Build.MODEL },
            Column("deviceProduct") { Build.PRODUCT },
            Column("softwareBuildId") { Build.ID },
            Column("softwareBuildFingerprint") { Build.FINGERPRINT },
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
            Column("admissionDecisionCount") { it.admissionRows.size },
            Column("admissionSkipCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.wasSkipped }
            },
            Column("controllableOptionalDecisionCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.isControllableOptionalDecision == true }
            },
            Column("controllabilityUnknownDecisionCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.isControllableOptionalDecision == null }
            },
            Column("controllableOptionalAdmitCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true && row.nodeRow.wasAdmitted == true
                }
            },
            Column("controllableOptionalSkipCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true && row.nodeRow.wasSkipped
                }
            },
            Column("pacingDecisionCount") { it.pacingRows.size },
            Column("nonzeroPacingDelayCount") { run ->
                run.pacingRows.count { pacing -> pacing.before.appliedDelayMs > 0L }
            },
            Column("observedActivitySignature") { observedActivitySignature(it) },
            Column("captureTimeoutMs") { MakerFeature.CAPTURE_TIMEOUT_MS },
            Column("staticGuardOverheatLevel") { STATIC_GUARD_OVERHEAT_LEVEL },
            Column("nearMissMarginMs") { NEAR_MISS_MARGIN_MS },
        )

        /**
         * One headline row per workbook. Join it with the supplied filename/test log when comparing policy arms.
         */
        private fun buildRunScorecardColumns(): List<Column<EvaluationRun>> = listOf(
            Column("evaluationSchemaVersion") { EVALUATION_SCHEMA_VERSION },
            Column("deviceManufacturer") { Build.MANUFACTURER },
            Column("deviceModel") { Build.MODEL },
            Column("deviceProduct") { Build.PRODUCT },
            Column("softwareBuildId") { Build.ID },
            Column("sizeBucketInferred") { sizeBucketInferred(it) },
            Column("targetConfigInferred") { targetConfigInferred(it) },
            Column("anyLowMemoryObserved") { run ->
                firstNodePreExecutionMetrics(run).any { metrics -> metrics.memorySnapshot.isLowMemory }
            },
            Column("startingOverheatLevel") { overheatLevels(it).firstOrNull() },
            Column("observedActivitySignature") { observedActivitySignature(it) },
            Column("captureCount") { it.captureRows.size },
            Column("admissionDecisionCount") { it.admissionRows.size },
            Column("admissionSkipCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.wasSkipped }
            },
            Column("controllableOptionalDecisionCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.isControllableOptionalDecision == true }
            },
            Column("controllabilityUnknownDecisionCount") { run ->
                run.admissionRows.count { row -> row.nodeRow.isControllableOptionalDecision == null }
            },
            Column("controllableOptionalAdmitCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true && row.nodeRow.wasAdmitted == true
                }
            },
            Column("controllableOptionalSkipCount") { run ->
                run.admissionRows.count { row ->
                    row.nodeRow.isControllableOptionalDecision == true && row.nodeRow.wasSkipped
                }
            },
            // Safety.
            Column("timeoutCount") { run -> run.captureRows.count { capture -> capture.hasTimeoutFailure } },
            Column("timeoutRate") { run ->
                rate(run.captureRows.count { capture -> capture.hasTimeoutFailure }, run.captureRows.size)
            },
            Column("firstTimeoutCaptureNumber") { run ->
                oneBasedFirstIndex(run.captureRows) { capture -> capture.hasTimeoutFailure }
            },
            Column("timeoutRightCensored") { run -> run.captureRows.none { capture -> capture.hasTimeoutFailure } },
            Column("timeoutFreeAt30Captures") { timeoutFreeAtCaptureCount(it, 30) },
            Column("failureFreeAt30Captures") { failureFreeAtCaptureCount(it, 30) },
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
            Column("featureEligibleCaptureCount") { it.featureEligibleCaptures.size },
            Column("mBokehDecisionCount") { it.bokehRows.size },
            Column("mBokehCompletedCount") { run -> run.bokehRows.count { row -> row.wasCompleted } },
            Column("mBokehCompletionRate") { run ->
                rate(run.bokehRows.count { row -> row.wasCompleted }, run.bokehRows.size)
            },
            Column("mBokehCompletionLevelGE4Rate") { mCompletionRateAtLevel(it, atOrAboveGuard = true) },
            Column("mBokehCompletionLevelLT4Rate") { mCompletionRateAtLevel(it, atOrAboveGuard = false) },
            Column("sFilterDecisionCount") { it.filterRows.size },
            Column("sFilterCompletedCount") { run -> run.filterRows.count { row -> row.wasCompleted } },
            Column("sFilterCompletionRate") { run ->
                rate(run.filterRows.count { row -> row.wasCompleted }, run.filterRows.size)
            },
            Column("fullFeatureSuccessCount") { run ->
                run.captureRows.count { capture -> capture.isFullFeatureSuccess }
            },
            Column("fullFeatureSuccessRate") { run ->
                rate(run.captureRows.count { capture -> capture.isFullFeatureSuccess }, run.featureEligibleCaptures.size)
            },
            Column("mAndSCompletedCount") { optionalWorkOutcomeCount(it, OPTIONAL_OUTCOME_M_AND_S) },
            Column("mAndSCompletedRate") { optionalWorkOutcomeRate(it, OPTIONAL_OUTCOME_M_AND_S) },
            Column("mOnlyCompletedCount") { optionalWorkOutcomeCount(it, OPTIONAL_OUTCOME_M_ONLY) },
            Column("mOnlyCompletedRate") { optionalWorkOutcomeRate(it, OPTIONAL_OUTCOME_M_ONLY) },
            Column("sOnlyCompletedCount") { optionalWorkOutcomeCount(it, OPTIONAL_OUTCOME_S_ONLY) },
            Column("sOnlyCompletedRate") { optionalWorkOutcomeRate(it, OPTIONAL_OUTCOME_S_ONLY) },
            Column("mandatoryOnlyCount") { optionalWorkOutcomeCount(it, OPTIONAL_OUTCOME_MANDATORY_ONLY) },
            Column("mandatoryOnlyRate") { optionalWorkOutcomeRate(it, OPTIONAL_OUTCOME_MANDATORY_ONLY) },
            Column("offlineGuardRecoveryCandidateCount") { offlineGuardRecoveryCandidateCount(it) },
            Column("offlineGuardBlindTimeoutCandidateCount") { offlineGuardBlindTimeoutCandidateCount(it) },
            // Cost.
            Column("meanShotToShotTimeMs") { run ->
                mean(inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() })
            },
            Column("p95ShotToShotTimeMs") { run ->
                percentile(
                    inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() },
                    0.95,
                )
            },
            Column("p99ShotToShotTimeMs") { run ->
                percentile(
                    inSessionShotToShotTimesMs(run).map { intervalMs -> intervalMs.toDouble() },
                    0.99,
                )
            },
            Column("captureAcceptanceSpanMs") { captureAcceptanceSpanMs(it) },
            Column("processingMakespanMs") { processingMakespanMs(it) },
            Column("acceptanceRateWithinObservedSpanPerMinute") {
                acceptanceRateWithinObservedSpanPerMinute(it)
            },
            Column("pacingDecisionCount") { it.pacingRows.size },
            Column("nonzeroPacingDelayCount") { run ->
                run.pacingRows.count { pacing -> pacing.before.appliedDelayMs > 0L }
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
            // RQ2 calibration headline; detailed denominators and sensitivity metrics live in RQ2Admission.
            Column("exactWallUpperBoundObservationCount") { run ->
                run.admissionRows.count { row -> row.exactWallUpperBoundSlackMs() != null }
            },
            Column("exactWallUpperBoundMissCount") { run ->
                run.admissionRows.count { row -> row.exactWallUpperBoundMiss() == true }
            },
            Column("observedExactWallUpperBoundMissRate") { run ->
                rate(
                    run.admissionRows.count { row -> row.exactWallUpperBoundMiss() == true },
                    run.admissionRows.count { row -> row.exactWallUpperBoundMiss() != null },
                )
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
            Column("medianAbsolutePredictionErrorMs") { run ->
                percentile(
                    run.admissionRows.mapNotNull { row ->
                        if (row.fullyObservedSequenceUpperBoundSlackMs() == null) {
                            null
                        } else {
                            row.nodeRow.sequencePredictionResidualMs()?.let(::abs)
                        }
                    },
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
            Column("offlineGuardRecoveryCandidate") { row ->
                val capture = row.capture.row
                val level = overheatLevelOf(capture)
                level != null && level >= STATIC_GUARD_OVERHEAT_LEVEL &&
                    capture.bokehDecisionRow?.wasCompleted == true && !capture.hasTimeoutFailure
            },
            Column("offlineGuardBlindTimeoutCandidate") { row ->
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

        /** Offline candidates where a level-4 guard suppresses M but the recorded controller ran it safely. */
        private fun offlineGuardRecoveryCandidateCount(run: EvaluationRun): Int =
            run.captureRows.count { capture ->
                val level = overheatLevelOf(capture)
                level != null && level >= STATIC_GUARD_OVERHEAT_LEVEL &&
                    capture.bokehDecisionRow?.wasCompleted == true && !capture.hasTimeoutFailure
            }

        /** Offline candidates where a timeout occurred below level 4, outside a pure level-4 guard's trigger. */
        private fun offlineGuardBlindTimeoutCandidateCount(run: EvaluationRun): Int =
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
            nodeSheetRows(captures)
                .filter { row ->
                    row.nodeRow.isAdmissionWorkload &&
                        row.fullyObservedSequenceUpperBoundSlackMs() != null
                }
                .mapNotNull { row -> row.nodeRow.sequencePredictionResidualMs()?.let(::abs) }

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

        private fun observedActivitySignature(run: EvaluationRun): String {
            val admissionActive = run.admissionRows.any { row -> row.nodeRow.wasSkipped }
            val pacingActive = run.pacingRows.any { pacing -> pacing.before.appliedDelayMs > 0L }
            return when {
                admissionActive && pacingActive -> ACTIVITY_SKIP_AND_DELAY
                admissionActive -> ACTIVITY_SKIP_ONLY
                pacingActive -> ACTIVITY_DELAY_ONLY
                else -> ACTIVITY_NO_TRIGGER
            }
        }

        private fun buildEvaluationReadinessRows(run: EvaluationRun): List<EvaluationReadinessRow> {
            val captureCount = run.captureRows.size
            val deadlineSlackObservedCount =
                run.captureRows.count { capture -> capture.timeoutMarginMs != null }
            val featureEligibleCount = run.featureEligibleCaptures.size
            val shotToShotObservedCount = inSessionShotToShotTimesMs(run).size
            val exactWallEligibleCount = run.admissionRows.count { row ->
                row.nodeRow.isControllableOptionalDecision == true &&
                    row.nodeRow.wasAdmitted == true &&
                    row.capture.metrics.timeoutTimestampMs != null
            }
            val exactWallObservedCount =
                run.admissionRows.count { row -> row.exactWallUpperBoundSlackMs() != null }
            val fullyObservedSequenceCount =
                run.admissionRows.count { row -> row.fullyObservedSequenceUpperBoundSlackMs() != null }
            val pacingCalibrationObservedCount =
                draftSequencePacingErrorsMs(run).size + queuePricingErrorsMs(run).size
            return listOf(
                EvaluationReadinessRow(
                    researchQuestion = "RQ1",
                    metric = "Capture timeout, survival index, and deadline slack",
                    status = when {
                        captureCount == 0 -> "NO_CAPTURE_ROWS"
                        deadlineSlackObservedCount == captureCount -> "AVAILABLE"
                        deadlineSlackObservedCount > 0 -> "PARTIALLY_AVAILABLE"
                        else -> "TIMEOUT_ONLY_NO_DEADLINE_SLACK"
                    },
                    evidenceSource = "Persisted shutter deadline and Draft completion " +
                        "($deadlineSlackObservedCount/$captureCount rows with deadline slack)",
                    limitation = "One workbook contains one newest burst session; missing deadlines cannot yield slack.",
                    requiredAction = "Aggregate independent workbook sessions for rates, confidence intervals, and survival.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ1",
                    metric = "M/S utility",
                    status = if (featureEligibleCount > 0) {
                        "AVAILABLE_AS_EXECUTION_PROXY"
                    } else {
                        "NO_ELIGIBLE_ROWS"
                    },
                    evidenceSource = "Bokeh and Filter admit/completion observations " +
                        "($featureEligibleCount eligible captures)",
                    limitation = "Execution is not a perceptual image-quality measurement.",
                    requiredAction = "Add a separate image-quality study if the paper claims visual equivalence.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ1/RQ3",
                    metric = "Shot-to-shot responsiveness and throughput",
                    status = if (shotToShotObservedCount > 0) {
                        "AVAILABLE"
                    } else {
                        "NO_WITHIN_SESSION_INTERVALS"
                    },
                    evidenceSource = "Within-session shot-to-shot intervals; first row excluded " +
                        "($shotToShotObservedCount observed intervals)",
                    limitation = "Requested pacing delay is not identical to total UI shutter-blocked time.",
                    requiredAction = "Use a fixed-duration run and UI instrumentation for blocked-time claims.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ1/RQ3",
                    metric = "Assigned policy arm and one-sided compliance check",
                    status = "EXTERNAL_MANIFEST_REQUIRED",
                    evidenceSource = "Filename/manifest assignment plus observedActivitySignature and trigger counts",
                    limitation = "Observed behavior cannot prove that an enabled mechanism was configured when it never " +
                        "fired. Legacy Decoding rows without workloadSequenceKey have unknown controllability.",
                    requiredAction = "Use the manifest as assignment; reject only forbidden positive triggers, never " +
                        "require exact equality with observedActivitySignature. Exclude unknown-controllability rows " +
                        "from optional-admit/skip contradiction checks.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ1",
                    metric = "Cross-device and thermal/memory robustness",
                    status = if (firstNodePreExecutionMetrics(run).isNotEmpty()) {
                        "AVAILABLE_AT_DRAFT_START"
                    } else {
                        "NO_STATE_ROWS"
                    },
                    evidenceSource = STATE_SAMPLING_DRAFT_START_SHARED,
                    limitation = "A workbook is one session and node rows reuse the Draft-start snapshot; sessions that " +
                        "inherit the same thermal trajectory are not independent.",
                    requiredAction = "Block by device, resolution, memory protocol, and starting thermal level; preserve " +
                        "the whole session and counterbalance arm order with cooldown.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ2",
                    metric = "Exact-wall one-sided upper-bound calibration",
                    status = when {
                        exactWallEligibleCount == 0 -> "NO_ELIGIBLE_ADMITTED_ROWS"
                        exactWallObservedCount == exactWallEligibleCount -> "AVAILABLE"
                        exactWallObservedCount > 0 -> "PARTIALLY_AVAILABLE"
                        else -> "NO_COMPLETE_WALL_ROWS"
                    },
                    evidenceSource = "Predicted sequence upper bound versus decision-to-Draft-end remaining wall " +
                        "($exactWallObservedCount/$exactWallEligibleCount eligible controllable admitted decisions observed)",
                    limitation = "Only a fully executed planned suffix is observed for the primary comparison. Missing " +
                        "or later-skipped suffixes can be informative and are not missing at random.",
                    requiredAction = "Report observed-case miss rate with incomplete count and the exported worst-case " +
                        "sensitivity rate; cluster decisions within capture/session.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ2",
                    metric = "Fully observed node-suffix predictor calibration",
                    status = if (fullyObservedSequenceCount > 0) "AVAILABLE_SECONDARY" else "NO_COMPLETE_SUFFIX_ROWS",
                    evidenceSource = "Predicted upper bound versus summed node durations " +
                        "($fullyObservedSequenceCount plan-matched, fully observed controllable admits)",
                    limitation = "Node-duration sums exclude inter-node gaps and scheduling, and skipped decisions are unobserved.",
                    requiredAction = "Use as predictor calibration only; do not substitute it for exact-wall gate safety.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ2",
                    metric = "Unnecessary skip ground truth",
                    status = "UNAVAILABLE_WITHOUT_EXPLICIT_FORCED_FLAG",
                    evidenceSource = "Exploratory matched-history proxy only",
                    limitation = "A normal skipped stage has no factual execution cost; filename-level forced labels " +
                        "cannot identify which decision was actually forced.",
                    requiredAction = "If a forced audit is later required, persist decisionAdmit, actuallyExecuted, and " +
                        "a node-level forcedExecution flag. Keep history matching as sensitivity only.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ3",
                    metric = "Assigned-policy pacing effect",
                    status = "CROSS_WORKBOOK_CONTRAST_REQUIRED",
                    evidenceSource = "Session outcomes from Always-on, Admission-only, Pacing-only, and Full files",
                    limitation = "Full minus Admission-only is the total effect of enabling pacing with admission on; " +
                        "pacing may change later admission decisions.",
                    requiredAction = "Randomize arms within condition blocks for ITT. Otherwise label the result a " +
                        "controlled assigned-policy contrast. Report Full-Admission-only, Pacing-only-Always-on, and " +
                        "their interaction.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ3",
                    metric = "Pacing mechanism diagnostics",
                    status = if (pacingCalibrationObservedCount > 0) {
                        "AVAILABLE"
                    } else {
                        "NO_CALIBRATION_ROWS"
                    },
                    evidenceSource = "Recorded delay, backlog, queue wait, Draft wall, and timeout outcome " +
                        "($pacingCalibrationObservedCount calibration observations)",
                    limitation = "Excessive/insufficient verdicts are structural diagnostics, not an optimal-delay oracle.",
                    requiredAction = "Use these diagnostics to debug the controller, not to claim d* optimality.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ3",
                    metric = "Fixed-duration exposure completion",
                    status = "EXTERNAL_PROTOCOL_LOG_REQUIRED",
                    evidenceSource = "No fixed-duration protocol timer in persisted capture metrics",
                    limitation = "Acceptance span is not the protocol timer and cannot prove the requested exposure ran.",
                    requiredAction = "Supply the intended/actual test duration with the workbook.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ3",
                    metric = "Minimum-delay oracle and per-decision under/overpace correctness",
                    status = "OUT_OF_SCOPE_WITH_CURRENT_DATA",
                    evidenceSource = PACING_ORACLE_NONE,
                    limitation = "Changing a delay changes later arrivals, admission, queue, and thermal state.",
                    requiredAction = "Attribute deadline and throughput differences only through assigned-arm contrasts; " +
                        "add validated closed-loop replay only if minimum-delay optimality becomes a separate claim.",
                ),
                EvaluationReadinessRow(
                    researchQuestion = "RQ1",
                    metric = "Controller overhead, preview frames, and UI regression",
                    status = "UNAVAILABLE",
                    evidenceSource = "Node CPU/GC metrics do not isolate controller overhead",
                    limitation = "The current store has no decision latency, controller allocation, or frame timeline.",
                    requiredAction = "Measure with Perfetto/frame metrics and a controller-off paired run.",
                ),
            )
        }

        private fun buildEvaluationReadinessColumns(): List<Column<EvaluationReadinessRow>> = listOf(
            Column("researchQuestion") { it.researchQuestion },
            Column("metric") { it.metric },
            Column("status") { it.status },
            Column("evidenceSource") { it.evidenceSource },
            Column("limitation") { it.limitation },
            Column("requiredAction") { it.requiredAction },
        )

        private fun buildMetricDefinitions(): List<ReplayNote> = listOf(
            ReplayNote(
                topic = "evaluationSchemaVersion",
                note = "Schema 2 is the first three-RQ export layout. Do not pool it with older workbooks by column " +
                    "position; join by explicit column name and keep older schema rows in a separately mapped cohort.",
            ),
            ReplayNote(
                topic = "acceptedUptimeMs",
                note = "timeoutDeadlineUptimeMs - current captureTimeoutMs. Valid for newly exported runs whose " +
                    "runtime timeout matches the exporter constant; do not use it to compare historical DB rows " +
                    "recorded under a different timeout.",
            ),
            ReplayNote(
                topic = "completionLatencyMs",
                note = "draftEndUptimeMs - acceptedUptimeMs",
            ),
            ReplayNote(
                topic = "timeoutMarginMs",
                note = "timeoutDeadlineUptimeMs - draftEndUptimeMs; positive means deadline slack.",
            ),
            ReplayNote(
                topic = "admissionDecisionUptimeMs",
                note = "timeoutDeadlineUptimeMs - decisionBudgetMs; nodeStartUptimeMs is a labelled legacy fallback.",
            ),
            ReplayNote(
                topic = "observedRemainingWallMs",
                note = "draftEndUptimeMs - admissionDecisionUptimeMs. Includes decision overhead, inter-node gaps, " +
                    "scheduling, and the mandatory tail. Exported only for recorded admitted decisions.",
            ),
            ReplayNote(
                topic = "plannedSuffixKeyMatch",
                note = "Exact equality between the decision-time workloadSequenceKey list and the later profiled " +
                    "workload-key suffix. False exposes a later skip/configuration change; blank means the comparison " +
                    "cannot be reconstructed. Primary RQ2 coverage additionally requires every matched node to finish.",
            ),
            ReplayNote(
                topic = "exactWallUpperBoundSlackMs",
                note = "sequencePredictedUpperBoundMs - observedRemainingWallMs, only when the persisted deadline " +
                    "reconstructs the exact decision instant and every node in the admitted controllable decision's " +
                    "planned suffix completed. Negative means the one-sided bound under-covered the realized wall cost.",
            ),
            ReplayNote(
                topic = "sequenceUpperBoundSlackMs",
                note = "sequencePredictedUpperBoundMs - summed recorded suffix node durations. Exported only for a " +
                    "fully observed controllable admitted suffix; it excludes inter-node gaps and scheduling and is " +
                    "secondary predictor calibration rather than end-to-end gate safety.",
            ),
            ReplayNote(
                topic = "controllableAdmittedDecisionWithSuffixWatchdogTimeoutCount",
                note = "Counts controllable admitted decisions whose recorded suffix contains any watchdog timeout. " +
                    "It is not a distinct watchdog-event count: one downstream timeout can be included in several " +
                    "earlier admitted decisions.",
            ),
            ReplayNote(
                topic = "captureAcceptanceSpanMs",
                note = "last capture acceptedUptimeMs - first capture acceptedUptimeMs in session order. Blank when " +
                    "either endpoint is missing, so an interior subset is never presented as the whole-burst span.",
            ),
            ReplayNote(
                topic = "processingMakespanMs",
                note = "latest Draft completion - first capture acceptance, but only when every capture in the " +
                    "session has a completion timestamp. Otherwise blank to avoid understating burst completion time.",
            ),
            ReplayNote(
                topic = "acceptanceRateWithinObservedSpanPerMinute",
                note = "(captureCount - 1) * 60000 / captureAcceptanceSpanMs only when every capture has an " +
                    "acceptance timestamp. Blank with incomplete timestamp coverage. This is the observed-span rate, " +
                    "not a fixed-duration protocol rate.",
            ),
            ReplayNote(
                topic = "optionalWorkOutcome",
                note = "M+S, M-only, S-only, or mandatory-only from observed Bokeh(M) and Filter(S) completion.",
            ),
            ReplayNote(
                topic = "observedActivitySignature",
                note = "Behavior-only summary from positive triggers: at least one admission skip and/or nonzero " +
                    "pacing delay. The filename/manifest remains the assigned arm. A forbidden positive trigger is a " +
                    "contradiction, but a configured mechanism that never fires is inconclusive.",
            ),
            ReplayNote(
                topic = "controllableOptionalDecision",
                note = "True for admission-controlled optional work and false for mandatory Frame-Watermark Decoding. " +
                    "Legacy Decoding rows without workloadSequenceKey are blank/unknown and are excluded from " +
                    "controllableOptionalAdmitCount and controllableOptionalSkipCount.",
            ),
            ReplayNote(
                topic = "controllerRequirementDiagnostic",
                note = "Controller-formula diagnostic from recorded budget overruns and pacing backlog deficit. It is " +
                    "not an oracle classification of which mechanism was truly required and must not be used as " +
                    "ground truth for admission or pacing correctness.",
            ),
        )

        private fun buildEvaluationNotes(): List<ReplayNote> = listOf(
            ReplayNote(
                topic = "Evaluation session",
                note = "Each export evaluates the newest burst session: captures are split wherever ppSequenceId " +
                    "restarts at 0 or a capture has no shot-to-shot time, and the last group is the evaluated run. " +
                    "wholeBurstSessionEvaluated is false when retention dropped the head of that burst, so its " +
                    "opening shots are missing. Controlled policy and condition labels come from the supplied " +
                    "workbook filename/test log.",
            ),
            ReplayNote(
                topic = "RQ1 safety outcome",
                note = "Timeout and watchdog failures are separate outcomes. firstTimeoutCaptureNumber is right " +
                    "censored at captureCount during analysis when timeoutRightCensored is true.",
            ),
            ReplayNote(
                topic = "RQ1 feature outcome",
                note = "M is represented by the Bokeh workload and S by the Filter workload in this implementation. " +
                    "Completed means the admitted node produced an observed positive duration; it is an execution " +
                    "proxy, not a perceptual image-quality score.",
            ),
            ReplayNote(
                topic = "RQ1 responsiveness outcome",
                note = "Shot-to-shot distributions exclude the first row because it may contain the gap from the " +
                    "previous burst. Capture span, processing makespan, throughput, and requested pacing delay must " +
                    "be read beside safety and M/S retention; requested delay is not a direct UI blocked-time trace.",
            ),
            ReplayNote(
                topic = "RQ2 one-sided upper-bound calibration",
                note = "The primary metric compares sequencePredictedUpperBoundMs with exact decision-to-Draft-end " +
                    "remaining wall cost for controllable admitted decisions whose persisted deadline reconstructs the " +
                    "decision instant and whose planned suffix fully executed. This tests the safety bound independently " +
                    "of whether the later capture deadline was missed. Report observed-case coverage, incomplete " +
                    "eligible decisions, and the worst-case rate that treats incomplete or changed suffixes as misses. " +
                    "The sequence-sum metrics use the same fully observed controllable population and are secondary " +
                    "predictor calibration because they omit gaps and scheduling.",
            ),
            ReplayNote(
                topic = "RQ2 skip sensitivity proxy",
                note = "previousSkipCounterfactualSlackMs prices every workload a capture skipped from the most " +
                    "recent earlier capture that actually ran that workload key, then asks whether the capture " +
                    "would still have met its own deadline. It can mix earlier thermal/content conditions and cannot " +
                    "recreate gaps, watchdogs, future queue, or thermal effects. Skips with no earlier observation are " +
                    "excluded, so always report historyProxyCoverageRate. Treat every historyProxy result as " +
                    "exploratory sensitivity, never factual unnecessary-skip ground truth.",
            ),
            ReplayNote(
                topic = "AdmissionDecisionAudit",
                note = "Admitted rows expose factual remaining wall and upper-bound coverage. Every skipped row " +
                    "requires an explicit audit for factual scoring; duration presence is never interpreted as proof " +
                    "of forced execution. The exploratory retrospective evidence uses the most recent earlier " +
                    "execution of the same workload; an earlier fully observed row with the exact workloadSequenceKey " +
                    "is preferred because its actual " +
                    "suffix can be compared directly with the skipped decision's budget. The closest later " +
                    "observation is sensitivity evidence only: disagreement marks a transition as uncertain. The " +
                    "recent-three median exposes one-sample GC/contention sensitivity. Evidence may come from the " +
                    "same capture when a workload runs twice in one sequence; that is the closest match available. " +
                    "Under sticky demotion a skipped workload never runs again in the burst, so the later-" +
                    "observation columns and the uncertain-transition verdict are usually empty by construction - " +
                    "they fill in only for a skip the controller recovered from. Confidence is therefore graded on " +
                    "the earlier evidence: exact-sequence and comparable context is High for the local proxy only. " +
                    "contextComparable is only " +
                    "a coarse screen of thermal and memory state (overheat delta <= 1, thermal-headroom delta <= " +
                    "0.25, same low-memory state, RAM delta <= 10 percentage points); it deliberately ignores queue " +
                    "depth, because the comparison is against the skipped decision's own budget and that budget " +
                    "already shrinks with the queue. The raw deltas remain authoritative. A likely verdict is a " +
                    "local decision audit, not the closed-loop outcome of changing the burst.",
            ),
            ReplayNote(
                topic = "RQ3 assigned-policy effect",
                note = "The causal unit is the assigned session arm, not whether an individual delay happened. " +
                    "Full minus Admission-only estimates the total effect of enabling pacing with admission on, " +
                    "including pacing-mediated changes to later admission. Pacing-only minus Always-on estimates the " +
                    "effect with admission off; their difference is the admission-by-pacing interaction. Call these " +
                    "ITT contrasts only when arms were assigned before the session and randomized within condition " +
                    "blocks. Blocking or counterbalancing without random assignment supports a controlled " +
                    "assigned-policy contrast, not a causal ITT claim. Within-arm paced-versus-unpaced rows remain " +
                    "selected diagnostics.",
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
                    "there even when the 30-capture row shows heavy skipping. Retention-truncated sessions remain " +
                    "diagnostic only: confirmatoryPrefixEligible is false, and 30-capture success fields are blank.",
            ),
            ReplayNote(
                topic = "SessionTimeline robustness support",
                note = "SessionTimeline aligns thermal, memory, prediction, admission, pacing, and deadline outcomes " +
                    "by trial capture number. It supports RQ1 cold-start and throttle-ramp analysis; it is not an " +
                    "independent fourth research question.",
            ),
            ReplayNote(
                topic = "Cross-session statistics",
                note = "Aggregate rates, confidence intervals, and time-to-first-timeout survival analysis must use " +
                    "each exported workbook/session as the independent unit, not individual captures. For zero " +
                    "timeouts, report exposure and a session-level upper bound (the rough 95% rule-of-three is 3/n). " +
                    "A pooled bound applies only to the prespecified device/condition mixture; per-device or per-level " +
                    "claims require stratum-specific exposure.",
            ),
            ReplayNote(
                topic = "Required policy arms",
                note = "The balanced 2x2 core is Always-on (admission off/pacing off), Admission-only (on/off), " +
                    "Pacing-only (off/on), and Full (on/on). Run actual Static-L4 as the primary production baseline; " +
                    "Mandatory-only is a targeted diagnostic rather than a full-factorial arm. GuardBaseline cannot " +
                    "replace a Static-L4 run because policy changes alter later queue and thermal trajectories.",
            ),
            ReplayNote(
                topic = "Condition matrix",
                note = "Within each device, cross 12MP/24MP with normal/memory pressure so resolution and memory " +
                    "effects are not confounded. Treat starting thermal level as a blocked stratum, preserve the full " +
                    "thermal trajectory in one session, and report per-device results before any pooled model. The " +
                    "number of independent devices limits generalization across devices.",
            ),
            ReplayNote(
                topic = "Required arrival protocols",
                note = "Use both fixed-count bursts (for 30-capture success, first-timeout survival, M/S retention, " +
                    "and completion time) and fixed-duration saturation (for sustained throughput). Keep one " +
                    "workbook per independent session, randomize policy order within a condition, and do not split a " +
                    "single burst when its overheat level changes. Supply fixed-duration protocol timing with the file.",
            ),
            ReplayNote(
                topic = "Exposure allocation",
                note = "Use a balanced or power-based confirmatory core for arm differences, especially Full versus " +
                    "Static-L4, then add a Full-only reliability extension for an absolute zero-failure bound. A large " +
                    "Full sample cannot compensate for a tiny comparator sample when estimating superiority. Mark the " +
                    "extension as a separate analysis cohort; do not automatically pool it into the balanced 2x2 " +
                    "contrast if it was collected at a different time or condition mix.",
            ),
            ReplayNote(
                topic = "External manifest join contract",
                note = "Join exactly one manifest row to each workbook by its immutable full filename. Record trialId, " +
                    "randomizationBlockId, sessionOrder, assignedPolicyArm, assignedAdmission, assignedPacing, " +
                    "conditionId, targetStartingThermalLevel, memoryProtocol, resolutionTarget, arrivalProtocol, " +
                    "plannedCaptureCount, plannedDurationMs, actualProtocolDurationMs, analysisCohort, " +
                    "deviceInstanceId, softwareBuildId, and whether assignment was recorded before the session. " +
                    "Reject duplicate filenames and assignment/count contradictions before analysis.",
            ),
            ReplayNote(
                topic = "RunContext and RunScorecard",
                note = "RunContext records observed device/build, resolution, size bucket, thermal/memory state, " +
                    "inferred target config, trigger counts, and observed activity. RunScorecard is one headline row " +
                    "per workbook with safety, feature, cost, and calibration numerators/denominators. Join these rows " +
                    "with the filename/manifest assignment. observedActivitySignature is behavior-only: forbidden " +
                    "positive triggers are contradictions, while an enabled mechanism that never triggered is " +
                    "inconclusive.",
            ),
            ReplayNote(
                topic = "Manifest arm compliance rules",
                note = "Always-on contradicts any controllable optional skip or nonzero pacing delay; Admission-only " +
                    "contradicts a nonzero delay; Pacing-only contradicts a controllable optional skip. Full permits " +
                    "every observed trigger and cannot be verified by absence. Mandatory-only contradicts any " +
                    "controllable optional admit or nonzero delay. Static-L4 contradicts nonzero adaptive pacing and " +
                    "must be checked separately against the level-4 rule. Apply optional admit/skip checks only where " +
                    "controllableOptionalDecision is true; unknown rows are inconclusive. A missing allowed trigger " +
                    "is always inconclusive, not a mismatch.",
            ),
            ReplayNote(
                topic = "Static-L4 compliance",
                note = "Check each known-controllable decision against the same draft-start overheat snapshot used by " +
                    "the runtime baseline. A pure per-decision L4 policy contradicts admit at level >= 4 or skip below " +
                    "4. If the actual baseline intentionally keeps a sticky demotion after reaching L4, declare that " +
                    "variant in the manifest and do not treat a later below-4 skip as a contradiction.",
            ),
            ReplayNote(
                topic = "GuardBaseline (static-guard counterfactual)",
                note = "Contrasts the production static thermal guard (skip optional Draft at overheat level >= 4, " +
                    "Section 2.4) with the controller's per-capture decision and the deadline outcome. " +
                    "guardVsControllerCell classifies each capture; offlineGuardRecoveryCandidate marks where M ran " +
                    "safely despite the threshold, and offlineGuardBlindTimeoutCandidate marks a timeout below level " +
                    "4. These are candidates from the recorded trajectory, not outcomes of a separate on-device " +
                    "Static-L4 run.",
            ),
            ReplayNote(
                topic = "FailureAttribution (ablation necessity)",
                note = "For every timeout, watchdog failure, or near miss (finished within 10% of the deadline), " +
                    "primaryCause and mechanismResponsible attribute the binding constraint to Pacing (cross-shot " +
                    "backlog), Admission (single-capture budget overrun), Predictor (upper-bound miss), or " +
                    "Environment (throttle). Admission-only runs should retain residual Pacing failures and " +
                    "pacing-only runs residual Admission failures. This sheet is diagnostic attribution, not a " +
                    "replacement for actual policy-arm runs. Precedence: backlog, then overrun, then upper-bound " +
                    "miss, then throttle.",
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
            Column("beforeAdmittedSuffixWatchdogTimedOut") { it.admittedSuffixWatchdogTimedOut() },
            Column("beforeNodeDurationMs") { it.nodeRow.nodeActualDurationMs },
            Column("beforeSequenceActualDurationMs") { it.nodeRow.sequenceActualDurationMs },
            Column("beforeSuffixFullyObserved") { it.isFullyObservedSuffix() },
            Column("beforePlannedSuffixKeyMatch") { it.plannedSuffixKeyMatch() },
            Column("beforePlannedSuffixFullyExecuted") { it.plannedSuffixFullyExecuted() },
            Column("beforeDecisionUptimeMs") { it.decisionUptimeMs() },
            Column("beforeObservedRemainingWallMs") { it.observedRemainingWallMs() },
            Column("beforeExactWallUpperBoundSlackMs") { it.exactWallUpperBoundSlackMs() },
            Column("beforeExactWallUpperBoundMiss") { it.exactWallUpperBoundMiss() },
            Column("beforeAdmissionGroundTruthSource") { it.admissionGroundTruthSource() },
            Column("beforeCaptureDeadlineFeasibleShared") { it.observedActualFeasible() },
            Column("beforeDecisionOutcome") { it.decisionOutcomeLabel() },
            Column("beforeDecisionObservationStatus") { it.observationStatus() },
            Column("beforeSequencePredictionResidualMs") { it.nodeRow.sequencePredictionResidualMs() },
            Column("beforeSequenceUpperBoundSlackMs") { it.fullyObservedSequenceUpperBoundSlackMs() },
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
            Column("acceptedUptimeMs") { it.row.acceptedUptimeMs },
            Column("firstNodeStartUptimeMs") { it.row.firstNodeStartUptimeMs },
            Column("draftStartUptimeMs") { it.row.draftStartUptimeMs },
            Column("draftEndUptimeMs") { it.row.draftEndUptimeMs },
            Column("draftWallMs") { it.row.draftWallMs },
            Column("completionLatencyMs") { it.row.completionLatencyMs },
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
            Column("controllerRequirementDiagnostic") { controllerRequirementDiagnostic(it.row) },
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
            Column("acceptedUptimeMs") { it.row.acceptedUptimeMs },
            Column("draftStartUptimeMs") { it.row.draftStartUptimeMs },
            Column("draftEndUptimeMs") { it.row.draftEndUptimeMs },
            Column("draftWallMs") { it.row.draftWallMs },
            Column("completionLatencyMs") { it.row.completionLatencyMs },
            Column("timeoutMarginMs") { it.row.timeoutMarginMs },
            Column("pacerSessionId") { it.row.metrics.draftSequenceMetrics?.pacerSessionId },
            Column("optionalWorkOutcome") { optionalWorkOutcome(it.row) },
            Column("controllerRequirementDiagnostic") { controllerRequirementDiagnostic(it.row) },
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
            Column("controllableOptionalDecision") { it.nodeRow.isControllableOptionalDecision },
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
            Column("admittedSuffixWatchdogTimedOut") { it.admittedSuffixWatchdogTimedOut() },
            Column("admissionStage") { it.admissionStage() },
            Column("decisionOutcome") { it.decisionOutcomeLabel() },
            Column("decisionObservationStatus") { it.observationStatus() },
            Column("decisionUptimeMs") { it.decisionUptimeMs() },
            Column("observedRemainingWallMs") { it.observedRemainingWallMs() },
            Column("exactWallUpperBoundSlackMs") { it.exactWallUpperBoundSlackMs() },
            Column("exactWallUpperBoundMiss") { it.exactWallUpperBoundMiss() },
            Column("admissionGroundTruthSource") { it.admissionGroundTruthSource() },
            Column("captureDeadlineFeasibleShared") { it.observedActualFeasible() },
            Column("") { "" },
            Column("workloadSequenceKey") { it.nodeRow.prediction?.workloadSequenceKey },
            Column("sequencePredictedDurationMs") { it.nodeRow.prediction?.sequencePredictedDurationMs },
            Column("sequencePredictedUpperBoundMs") { it.nodeRow.prediction?.sequencePredictedUpperBoundMs },
            Column("sequenceActualDurationMs") { it.nodeRow.sequenceActualDurationMs },
            Column("suffixFullyObserved") { it.isFullyObservedSuffix() },
            Column("plannedSuffixKeyMatch") { it.plannedSuffixKeyMatch() },
            Column("plannedSuffixFullyExecuted") { it.plannedSuffixFullyExecuted() },
            Column("sequencePredictionResidualMs") { it.nodeRow.sequencePredictionResidualMs() },
            Column("sequenceUpperBoundSlackMs") { it.fullyObservedSequenceUpperBoundSlackMs() },
            Column("sequenceUpperBoundMiss") { it.sequenceUpperBoundMiss() },
        )

    }

}
