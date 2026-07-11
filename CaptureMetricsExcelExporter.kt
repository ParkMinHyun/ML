package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.Build
import com.samsung.android.camera.core2.container.DynamicShotMode
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
    ) {
        val queueAwareCaptureAvailableElapsedProxyMs: Long
            get() = QUEUE_AWARE_CAPTURE_AVAILABLE_ELAPSED_PROXY_MS

        val queueAwareQueuedDraftCountProxy: Int
            get() = (sessionSummary.sessionCaptureIndex - 1).coerceAtLeast(0)

        val queueAwarePacing: PacingSimulation?
            get() = row.queueAwarePacing(
                captureAvailableElapsedMs = queueAwareCaptureAvailableElapsedProxyMs,
                queuedDraftCount = queueAwareQueuedDraftCountProxy,
            )

        val queueAwarePacingInputSource: String
            get() = "proxy: captureAvailableElapsed=0ms, queuedDraftCount=sessionCaptureIndex-1"
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

            writeSheet(workbook, styles, "DecisionQuality", buildDecisionQualitySummaries(enrichedNormalCaptures), buildDecisionQualityColumns())
            writeSheet(workbook, styles, "PolicyOutcome", buildPolicyOutcomeSummaries(enrichedNormalCaptures), buildPolicyOutcomeColumns())
            writeSheet(workbook, styles, "ReviewMetrics", buildReviewMetricSummaries(enrichedNormalCaptures), buildReviewMetricColumns())
            writeSheet(workbook, styles, "SimulationScenarios", buildSimulationScenarioSummaries(enrichedNormalCaptures), buildSimulationScenarioColumns())
            writeSheet(workbook, styles, "PacingSimulation", buildPacingSimulationSummaries(enrichedNormalCaptures), buildPacingSimulationColumns())
            writeSheet(workbook, styles, "MetricNotes", buildMetricNotes(), buildMetricNoteColumns())

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

        val sortedGroups = groups.sortedBy { group ->
            group.firstOrNull()?.nodeRows?.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.overheatLevel ?: Int.MAX_VALUE
        }

        val enriched = mutableListOf<EnrichedCaptureRow>()
        sortedGroups.forEachIndexed { sessionId, group ->
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
                enriched.add(EnrichedCaptureRow(groupMember, sessionSummary))
            }
        }
        return enriched
    }

    /**
     * Groups captures into burst sessions. Prefers the runtime pacer session id (increments each time the drained
     * pipeline clears the pacer); rows recorded before that field existed fall back to the legacy
     * timeout-delimited grouping.
     */
    private fun groupCaptures(captures: List<CaptureRow>): List<List<CaptureRow>> {
        val groups = mutableListOf<List<CaptureRow>>()
        var currentGroup = mutableListOf<CaptureRow>()

        if (captures.isNotEmpty() && captures.all { it.metrics.draftSequenceMetrics?.pacerSessionId != null }) {
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
        val nodeRowsByNodeName = nodeSheetRows(captures.map { it.row })
            .groupBy { it.nodeRow.node.nodeName }

        nodeRowsByNodeName.toSortedMap().forEach { (nodeName, rows) ->
            val sheetName = uniqueSheetName(workbook, "$sheetNamePrefix$nodeName")
            val nodeColumns = buildNodeColumns()
            writeSheet(workbook, styles, sheetName, rows, nodeColumns)
        }
    }

    private fun buildDecisionQualitySummaries(captures: List<EnrichedCaptureRow>): List<DecisionQualitySummary> {
        return captureGroups(captures.map { it.row }).flatMap { group ->
            listOf(
                DecisionQualitySummary.from(group.name, ADMISSION_STAGE_BOKEH, group.captures),
                DecisionQualitySummary.from(group.name, ADMISSION_STAGE_DECODING, group.captures),
                DecisionQualitySummary.from(group.name, ADMISSION_STAGE_FILTER, group.captures),
                DecisionQualitySummary.from(group.name, ADMISSION_STAGE_OVERLAY_WATERMARK, group.captures),
            )
        }
    }

    private fun buildPolicyOutcomeSummaries(captures: List<EnrichedCaptureRow>): List<PolicyOutcomeSummary> {
        return captureGroups(captures.map { it.row }).map { group ->
            PolicyOutcomeSummary.from(group.name, group.captures)
        }
    }

    private fun buildReviewMetricSummaries(captures: List<EnrichedCaptureRow>): List<ReviewMetricSummary> {
        return captureGroups(captures.map { it.row }).flatMap { group ->
            ReviewMetricSummary.from(group.name, group.captures)
        }
    }

    private fun buildSimulationScenarioSummaries(captures: List<EnrichedCaptureRow>): List<SimulationScenarioSummary> {
        return captureGroups(captures.map { it.row }).flatMap { group ->
            SimulationScenarioSummary.from(group.name, group.captures)
        }
    }

    private fun buildPacingSimulationSummaries(captures: List<EnrichedCaptureRow>): List<PacingSimulationSummary> {
        return enrichedCaptureGroups(captures).flatMap { group ->
            PacingSimulationSummary.from(group.name, group.captures)
        }
    }

    private fun captureGroups(captures: List<CaptureRow>): List<CaptureGroup<CaptureRow>> {
        if (captures.isEmpty()) {
            return listOf(CaptureGroup("All", captures))
        }

        val groups = mutableListOf(CaptureGroup("All", captures))
        captures.groupBy { it.firstNodeLowMemoryLabel() }
            .toSortedMap()
            .forEach { (name, groupCaptures) ->
                groups += CaptureGroup(name, groupCaptures)
            }
        return groups
    }

    private fun enrichedCaptureGroups(captures: List<EnrichedCaptureRow>): List<CaptureGroup<EnrichedCaptureRow>> {
        if (captures.isEmpty()) {
            return listOf(CaptureGroup("All", captures))
        }

        val groups = mutableListOf(CaptureGroup("All", captures))
        captures.groupBy { it.row.firstNodeLowMemoryLabel() }
            .toSortedMap()
            .forEach { (name, groupCaptures) ->
                groups += CaptureGroup(name, groupCaptures)
            }
        return groups
    }

    private fun buildMetricNotes(): List<MetricNote> = listOf(
        MetricNote(
            metric = "DecisionQuality admit metrics",
            note = "Admit success, unsafe admit, and UB miss are evaluated only when the admitted suffix is observed online. A timeout or watchdog after admit is counted as unsafe.",
        ),
        MetricNote(
            metric = "Bokeh admit correctness",
            note = "Bokeh suffix correctness requires the full Bokeh-to-tail suffix. If Bokeh is admitted but a later optional stage is skipped, online logs do not contain that counterfactual stage time.",
        ),
        MetricNote(
            metric = "Skip correctness",
            note = "Correct Skip, Unnecessary Skip, overall decision accuracy, and balanced decision accuracy require full-execution offline replay or shadow execution.",
        ),
        MetricNote(
            metric = "PolicyOutcome",
            note = "Sequential outcomes are mutually exclusive per capture and are the right place to discuss Filter preservation and observed Filter loss after Bokeh admit.",
        ),
        MetricNote(
            metric = "ReviewMetrics",
            note = "Professor/SEIP-facing one-page metrics: timeout/watchdog rate, Filter preservation, Bokeh execution, full-feature success, selective Bokeh skip, and decision-quality metrics.",
        ),
        MetricNote(
            metric = "SimulationScenarios",
            note = "budgetOverrunRisk mixes axes on purpose: the Current Model row is the observed timeout/watchdog rate, " +
                    "while Always Run / Always Skip Bokeh rows are predicted-UB > budget proxies from recorded predictions " +
                    "(offlineOracleRequired=true). Read the column by direction, not absolute value; actual counterfactual " +
                    "timeout/watchdog and unnecessary-skip counts require offline replay or shadow execution.",
        ),
        MetricNote(
            metric = "Pacing simulation",
            note = "Runtime pacing delay = MAX(level deficit, admitted-backlog deficit vs CAPTURE_TIMEOUT); the backlog " +
                    "term needs runtime admission times that CaptureMetrics does not persist, so this sheet reproduces the " +
                    "level term only: optionalAdmissionDeficitMs = CEIL(MAX(0, pacingTargetUpperBoundMs - MAX(0, draftStartBudgetMs))). " +
                    "mandatoryReserveDeficitMs and optionalHeadroomMs remain as diagnostics; " +
                    "encodingReserveUpperBoundMs only classifies log severity (mandatory reserve at risk when budget < reserve). " +
                    "pacingTargetUpperBoundMs is the draft-start workload suffix UB, matching the runtime CaptureAvailablePacer observation. " +
                    "simulatedBudgetAfterPacingMs assumes 1ms callback delay contributes 1ms budget runway in the counterfactual replay. " +
                    "The policy has no prior callback-delay input, threshold, or device-tuned constant.",
        ),
        MetricNote(
            metric = "Queue-aware pacing simulation",
            note = "Queue-aware pacing uses availableBudgetMs = draftStartBudgetMs - queuedDraftWorkMs; " +
                    "queuedDraftWorkMs = MAX(0, CEIL(predictedDraftDurationMs - captureAvailableElapsedProxyMs)) * queuedDraftCountProxy. " +
                    "CaptureMetrics currently does not persist runtime captureAvailable elapsed time or runtime draft queue depth, " +
                    "so this exporter uses captureAvailableElapsedProxyMs=0 and queuedDraftCountProxy=sessionCaptureIndex-1 as a conservative burst proxy. " +
                    "Add runtime fields later to replace these proxy columns without changing the derived columns.",
        ),
        MetricNote(
            metric = "Offline replay columns",
            note = "Capture sheet persists the runtime state offline replay needs: pacerSessionId (burst boundary; " +
                    "pacer clear() on pipeline drain increments it), draftStartUptimeMs/draftEndUptimeMs/timeoutDeadlineUptimeMs " +
                    "(interarrival and deadline reconstruction), nodeStartUptimeMs per node row (real per-node timeline), and " +
                    "runtimePacing* (the applied captureAvailable delay with every backlog-clock input that produced it: " +
                    "level/backlog deficits, backlog, queued count/work, budget, preferred-path prediction/UB). " +
                    "Skip and pacing counterfactuals can re-run the policy from these fields instead of the proxy columns; " +
                    "runtimePacing* is null when the capture had no pacing decision (first capture of a fresh process).",
        ),
    )

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

            if (item is EnrichedCaptureRow && item.row.metrics.draftSequenceMetrics?.isTimeout == true && rowIndex < items.lastIndex) {
                val nextItem = items.getOrNull(rowIndex + 1) as? EnrichedCaptureRow
                if (nextItem != null && item.sessionSummary.sessionId != nextItem.sessionSummary.sessionId) {
                    sheet.createRow(sheet.lastRowNum + 1)
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

    private class CaptureRow(
        val captureIndex: Int,
        val metrics: CaptureMetrics,
        val nodeRows: List<NodeRow>,
    ) {
        val bokehDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isBokehWorkload && it.prediction != null }

        val decodingDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isDecodingWorkload && it.prediction != null }

        val filterDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isFilterWorkload && it.prediction != null }

        val overlayWatermarkDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isOverlayWatermarkWorkload && it.prediction != null }

        /**
         * Draft-start node of the capture - the node whose budget the runtime CaptureAvailablePacer records at
         * draft start. This is the first predicted node in the configured sequence; encoding-only captures use the
         * RESERVED Encoding row, mirroring the runtime.
         */
        val draftStartRow: NodeRow?
            get() = nodeRows.firstOrNull { it.prediction != null }

        /**
         * Encoding reserve node - the RESERVED tail, i.e. the last predicted node. Its suffix UB is exactly the
         * runtime's reserve over the RESERVED tail (UB[Encoding]), so encodingReserveUpperBoundMs no longer over-counts the
         * intervening REQUIRED stages the old "first non-admission node" proxy included.
         */
        val encodingReserveRow: NodeRow?
            get() = nodeRows.lastOrNull { it.prediction != null }

        /** Signed headroom above the mandatory reserve: draft-start budget minus the encoding reserve UB. */
        val pacingSlackMs: Double?
            get() {
                val budgetMs = draftStartRow?.node?.preExecutionMetrics?.budgetMs ?: return null
                val reserveMs = mandatoryReserveUpperBoundMs ?: return null
                return budgetMs - reserveMs
            }

        val mandatoryReserveUpperBoundMs: Double?
            get() = encodingReserveRow?.prediction?.sequencePredictedUpperBoundMs

        val pacingTargetUpperBoundMs: Double?
            get() = draftStartRow?.prediction?.sequencePredictedUpperBoundMs

        val budgetDeficitPacing: PacingSimulation?
            get() {
                val startRow = draftStartRow ?: return null
                val budgetMs = startRow.node.preExecutionMetrics.budgetMs
                val reserveUpperBoundMs = mandatoryReserveUpperBoundMs ?: return null
                val targetUpperBoundMs = pacingTargetUpperBoundMs ?: return null
                val clampedBudgetMs = budgetMs.coerceAtLeast(0L)
                val mandatoryDeficitMs = positiveCeilMs(reserveUpperBoundMs - clampedBudgetMs)
                val optionalDeficitMs = positiveCeilMs(targetUpperBoundMs - clampedBudgetMs)
                val optionalHeadroomMs = positiveFloorMs(clampedBudgetMs - reserveUpperBoundMs)
                // Mirrors CaptureAvailableApmPolicy monotone pacing: delay = full preferred-path deficit.
                // mandatoryDeficitMs / optionalHeadroomMs stay as diagnostic columns only.
                val appliedDelayMs = optionalDeficitMs
                val simulatedBudgetAfterPacingMs = budgetMs + appliedDelayMs

                return PacingSimulation(
                    targetStage = pacingTargetStage(startRow),
                    budgetMs = budgetMs,
                    mandatoryReserveUpperBoundMs = reserveUpperBoundMs,
                    targetUpperBoundMs = targetUpperBoundMs,
                    mandatoryDeficitMs = mandatoryDeficitMs,
                    optionalDeficitMs = optionalDeficitMs,
                    optionalHeadroomMs = optionalHeadroomMs,
                    appliedDelayMs = appliedDelayMs,
                    simulatedBudgetAfterPacingMs = simulatedBudgetAfterPacingMs,
                    mandatorySafeBeforePacing = reserveUpperBoundMs <= budgetMs,
                    mandatorySafeAfterPacing = reserveUpperBoundMs <= simulatedBudgetAfterPacingMs,
                    targetAdmitBeforePacing = targetUpperBoundMs <= budgetMs,
                    targetAdmitAfterPacing = targetUpperBoundMs <= simulatedBudgetAfterPacingMs,
                )
            }

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

        fun queueAwarePacing(captureAvailableElapsedMs: Long, queuedDraftCount: Int): PacingSimulation? {
            val startRow = draftStartRow ?: return null
            val timeoutBudgetMs = startRow.node.preExecutionMetrics.budgetMs
            val requiredReserveMs = mandatoryReserveUpperBoundMs ?: return null
            val predictedDraftDurationMs = startRow.prediction?.sequencePredictedDurationMs ?: return null
            val preferredDraftPathBudgetMs = pacingTargetUpperBoundMs ?: return null
            val captureAvailableElapsedProxyMs = captureAvailableElapsedMs.coerceAtLeast(0L)
            val queuedDraftCountProxy = queuedDraftCount.coerceAtLeast(0)

            val unservedDraftWorkMs = positiveCeilMs(predictedDraftDurationMs - captureAvailableElapsedProxyMs)
            val queuedDraftWorkMs = unservedDraftWorkMs * queuedDraftCountProxy.toLong()
            val availableBudgetMs = (timeoutBudgetMs - queuedDraftWorkMs).coerceAtLeast(0L)
            val mandatoryReserveShortageMs = positiveCeilMs(requiredReserveMs - availableBudgetMs)
            val preferredBudgetShortageMs = positiveCeilMs(preferredDraftPathBudgetMs - availableBudgetMs)
            val optionalBudgetHeadroomMs = positiveFloorMs(availableBudgetMs - requiredReserveMs)
            // Mirrors CaptureAvailableApmPolicy monotone pacing: delay = full preferred-path deficit.
            val appliedDelayMs = preferredBudgetShortageMs
            val simulatedBudgetAfterPacingMs = availableBudgetMs + appliedDelayMs

            return PacingSimulation(
                targetStage = pacingTargetStage(startRow),
                budgetMs = timeoutBudgetMs,
                mandatoryReserveUpperBoundMs = requiredReserveMs,
                targetUpperBoundMs = preferredDraftPathBudgetMs,
                mandatoryDeficitMs = mandatoryReserveShortageMs,
                optionalDeficitMs = preferredBudgetShortageMs,
                optionalHeadroomMs = optionalBudgetHeadroomMs,
                appliedDelayMs = appliedDelayMs,
                simulatedBudgetAfterPacingMs = simulatedBudgetAfterPacingMs,
                mandatorySafeBeforePacing = requiredReserveMs <= availableBudgetMs,
                mandatorySafeAfterPacing = requiredReserveMs <= simulatedBudgetAfterPacingMs,
                targetAdmitBeforePacing = preferredDraftPathBudgetMs <= availableBudgetMs,
                targetAdmitAfterPacing = preferredDraftPathBudgetMs <= simulatedBudgetAfterPacingMs,
                method = QUEUE_AWARE_PACING_METHOD,
                note = QUEUE_AWARE_PACING_NOTE,
                captureAvailableElapsedMs = captureAvailableElapsedProxyMs,
                queuedDraftCount = queuedDraftCountProxy,
                predictedDraftDurationMs = predictedDraftDurationMs,
                unservedDraftWorkMs = unservedDraftWorkMs,
                queuedDraftWorkMs = queuedDraftWorkMs,
                availableBudgetMs = availableBudgetMs,
            )
        }

        fun firstNodeLowMemoryLabel(): String {
            val isLowMemory = nodeRows.firstOrNull()?.node?.preExecutionMetrics?.memorySnapshot?.isLowMemory
                ?: return "Memory Not Recorded"
            return "LowMemory=$isLowMemory"
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

    private class NodeSheetRow(
        val capture: CaptureRow,
        val nodeOrder: Int,
        val nodeRow: NodeRow,
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
                ADMISSION_SKIP_REASON_BUDGET_RUNWAY
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

        private fun isFullyObservedSuffix(): Boolean {
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

    private class CaptureGroup<T>(
        val name: String,
        val captures: List<T>,
    )

    private class PacingSimulation(
        val targetStage: String,
        val budgetMs: Long,
        val mandatoryReserveUpperBoundMs: Double,
        val targetUpperBoundMs: Double,
        val mandatoryDeficitMs: Long,
        val optionalDeficitMs: Long,
        val optionalHeadroomMs: Long,
        val appliedDelayMs: Long,
        val simulatedBudgetAfterPacingMs: Long,
        val mandatorySafeBeforePacing: Boolean,
        val mandatorySafeAfterPacing: Boolean,
        val targetAdmitBeforePacing: Boolean,
        val targetAdmitAfterPacing: Boolean,
        val method: String = BUDGET_DEFICIT_PACING_METHOD,
        val note: String = BUDGET_DEFICIT_PACING_NOTE,
        val captureAvailableElapsedMs: Long? = null,
        val queuedDraftCount: Int? = null,
        val predictedDraftDurationMs: Double? = null,
        val unservedDraftWorkMs: Long? = null,
        val queuedDraftWorkMs: Long? = null,
        val availableBudgetMs: Long? = null,
    )

    private class DecisionQualitySummary(
        val group: String,
        val decision: String,
        val decisionCount: Int,
        val admitDecisionCount: Int,
        val skipDecisionCount: Int,
        val observedAdmitDecisionCount: Int,
        val correctAdmitCount: Int,
        val unsafeAdmitCount: Int,
        val admitOutcomeNotFullyObservedCount: Int,
        val admitSuccessRate: Double?,
        val unsafeAdmitRate: Double?,
        val ubEvaluatedCount: Int,
        val ubMissCount: Int,
        val ubMissRate: Double?,
        val skipCorrectnessStatus: String,
    ) {
        companion object {
            fun from(group: String, decision: String, captures: List<CaptureRow>): DecisionQualitySummary {
                val decisionRows = decisionRows(captures, decision)

                val correctAdmitCount = decisionRows.count {
                    it.decisionOutcome() == DecisionOutcome.CORRECT_ADMIT
                }
                val unsafeAdmitCount = decisionRows.count {
                    it.decisionOutcome() == DecisionOutcome.UNSAFE_ADMIT
                }
                val observedAdmitDecisionCount = correctAdmitCount + unsafeAdmitCount
                val admitOutcomeNotFullyObservedCount = decisionRows.count {
                    it.decisionOutcome() == DecisionOutcome.ADMIT_OUTCOME_NOT_FULLY_OBSERVED
                }
                val ubEvaluatedCount = decisionRows.count {
                    it.sequenceUpperBoundMiss() != null
                }
                val ubMissCount = decisionRows.count {
                    it.sequenceUpperBoundMiss() == true
                }

                return DecisionQualitySummary(
                    group = group,
                    decision = decision,
                    decisionCount = decisionRows.size,
                    admitDecisionCount = decisionRows.count { it.nodeRow.prediction?.admit == true },
                    skipDecisionCount = decisionRows.count { it.nodeRow.prediction?.admit == false },
                    observedAdmitDecisionCount = observedAdmitDecisionCount,
                    correctAdmitCount = correctAdmitCount,
                    unsafeAdmitCount = unsafeAdmitCount,
                    admitOutcomeNotFullyObservedCount = admitOutcomeNotFullyObservedCount,
                    admitSuccessRate = rate(correctAdmitCount, observedAdmitDecisionCount),
                    unsafeAdmitRate = rate(unsafeAdmitCount, observedAdmitDecisionCount),
                    ubEvaluatedCount = ubEvaluatedCount,
                    ubMissCount = ubMissCount,
                    ubMissRate = rate(ubMissCount, ubEvaluatedCount),
                    skipCorrectnessStatus = "Requires offline oracle",
                )
            }
        }
    }

    private class PolicyOutcomeSummary(
        val group: String,
        val captureCount: Int,
        val timeoutCount: Int,
        val timeoutRate: Double?,
        val watchdogTriggerCount: Int,
        val watchdogTriggerRate: Double?,
        val filterPreservedCount: Int,
        val filterPreservationRate: Double?,
        val bokehExecutedCount: Int,
        val bokehExecutionRate: Double?,
        val bothSkippedCount: Int,
        val bothSkippedRate: Double?,
        val fullFeatureSuccessCount: Int,
        val fullFeatureSuccessRate: Double?,
        val selectiveBokehSkipSuccessCount: Int,
        val selectiveBokehSkipSuccessRate: Double?,
        val observedFilterLossAfterBokehAdmitCount: Int,
        val observedFilterLossAfterBokehAdmitRate: Double?,
        val tailOnlySafeCount: Int,
        val tailOnlySafeRate: Double?,
        val timeoutFailureCount: Int,
        val timeoutFailureRate: Double?,
        val watchdogFailureCount: Int,
        val watchdogFailureRate: Double?,
        val otherCount: Int,
        val otherRate: Double?,
    ) {
        companion object {
            fun from(group: String, captures: List<CaptureRow>): PolicyOutcomeSummary {
                val total = captures.size
                val timeoutCount = captures.count { it.metrics.draftSequenceMetrics?.isTimeout == true }
                val watchdogTriggerCount = captures.count {
                    it.metrics.draftSequenceMetrics?.hasWatchdogTimeout == true ||
                            it.nodeRows.any { nodeRow -> nodeRow.node.watchdogTimedOut == true }
                }
                val filterPreservedCount = captures.count { it.isFilterPreserved }
                val bokehExecutedCount = captures.count {
                    it.bokehDecisionRow?.wasAdmitted == true
                }
                val bothSkippedCount = captures.count {
                    it.bokehDecisionRow?.wasSkipped == true && it.filterDecisionRow?.wasSkipped == true
                }
                val outcomeCounts = captures.groupingBy { it.policyOutcome() }.eachCount()
                val fullFeatureSuccessCount = outcomeCounts[PolicyOutcome.FULL_FEATURE_SUCCESS].orZero()
                val selectiveBokehSkipSuccessCount = outcomeCounts[PolicyOutcome.SELECTIVE_BOKEH_SKIP_SUCCESS].orZero()
                val observedFilterLossAfterBokehAdmitCount =
                    outcomeCounts[PolicyOutcome.OBSERVED_FILTER_LOSS_AFTER_BOKEH_ADMIT].orZero()
                val tailOnlySafeCount = outcomeCounts[PolicyOutcome.TAIL_ONLY_SAFE].orZero()
                val timeoutFailureCount = outcomeCounts[PolicyOutcome.TIMEOUT_FAILURE].orZero()
                val watchdogFailureCount = outcomeCounts[PolicyOutcome.WATCHDOG_FAILURE].orZero()
                val otherCount = outcomeCounts[PolicyOutcome.OTHER].orZero()

                return PolicyOutcomeSummary(
                    group = group,
                    captureCount = total,
                    timeoutCount = timeoutCount,
                    timeoutRate = rate(timeoutCount, total),
                    watchdogTriggerCount = watchdogTriggerCount,
                    watchdogTriggerRate = rate(watchdogTriggerCount, total),
                    filterPreservedCount = filterPreservedCount,
                    filterPreservationRate = rate(filterPreservedCount, total),
                    bokehExecutedCount = bokehExecutedCount,
                    bokehExecutionRate = rate(bokehExecutedCount, total),
                    bothSkippedCount = bothSkippedCount,
                    bothSkippedRate = rate(bothSkippedCount, total),
                    fullFeatureSuccessCount = fullFeatureSuccessCount,
                    fullFeatureSuccessRate = rate(fullFeatureSuccessCount, total),
                    selectiveBokehSkipSuccessCount = selectiveBokehSkipSuccessCount,
                    selectiveBokehSkipSuccessRate = rate(selectiveBokehSkipSuccessCount, total),
                    observedFilterLossAfterBokehAdmitCount = observedFilterLossAfterBokehAdmitCount,
                    observedFilterLossAfterBokehAdmitRate = rate(observedFilterLossAfterBokehAdmitCount, total),
                    tailOnlySafeCount = tailOnlySafeCount,
                    tailOnlySafeRate = rate(tailOnlySafeCount, total),
                    timeoutFailureCount = timeoutFailureCount,
                    timeoutFailureRate = rate(timeoutFailureCount, total),
                    watchdogFailureCount = watchdogFailureCount,
                    watchdogFailureRate = rate(watchdogFailureCount, total),
                    otherCount = otherCount,
                    otherRate = rate(otherCount, total),
                )
            }

            private fun Int?.orZero(): Int = this ?: 0
        }
    }

    private class ReviewMetricSummary(
        val group: String,
        val category: String,
        val metric: String,
        val numerator: Int?,
        val denominator: Int?,
        val rate: Double?,
        val evidenceLevel: String,
        val note: String,
    ) {
        companion object {
            fun from(group: String, captures: List<CaptureRow>): List<ReviewMetricSummary> {
                val total = captures.size
                val bokehSkipCount = captures.count { it.bokehDecisionRow?.wasSkipped == true }
                val bokehDecisionRows = decisionRows(captures, ADMISSION_STAGE_BOKEH)
                val filterDecisionRows = decisionRows(captures, ADMISSION_STAGE_FILTER)

                return listOf(
                    reviewMetric(
                        group = group,
                        category = "Product outcome",
                        metric = "timeout/watchdog 발생률",
                        numerator = captures.count { it.hasTimeoutOrWatchdogFailure },
                        denominator = total,
                        evidenceLevel = "Observed online",
                        note = "Timeout 또는 watchdog 중 하나라도 발생한 capture 비율.",
                    ),
                    reviewMetric(
                        group = group,
                        category = "Product outcome",
                        metric = "Filter 보존율",
                        numerator = captures.count { it.isFilterPreserved },
                        denominator = total,
                        evidenceLevel = "Observed online",
                        note = "Filter admission이 보존된 capture 비율.",
                    ),
                    reviewMetric(
                        group = group,
                        category = "Product outcome",
                        metric = "Bokeh 실행률",
                        numerator = captures.count { it.isBokehExecuted },
                        denominator = total,
                        evidenceLevel = "Observed online",
                        note = "Bokeh admission이 실행된 capture 비율.",
                    ),
                    reviewMetric(
                        group = group,
                        category = "Product outcome",
                        metric = "full feature 성공률",
                        numerator = captures.count { it.isFullFeatureSuccess },
                        denominator = total,
                        evidenceLevel = "Observed online",
                        note = "Bokeh와 Filter가 모두 완료된 capture 비율.",
                    ),
                    reviewMetric(
                        group = group,
                        category = "Selective degrade",
                        metric = "Bokeh skip + Filter 보존",
                        numerator = captures.count { it.isSelectiveBokehSkipSuccess },
                        denominator = total,
                        evidenceLevel = "Observed online",
                        note = "Bokeh만 선택적으로 skip하고 Filter는 완료된 capture.",
                    ),
                    reviewMetric(
                        group = group,
                        category = "Selective degrade",
                        metric = "둘 다 skip",
                        numerator = captures.count { it.isBothSkipped },
                        denominator = total,
                        evidenceLevel = "Observed online",
                        note = "Bokeh와 Filter가 모두 skip된 capture.",
                    ),
                    reviewMetric(
                        group = group,
                        category = "Bokeh skip validation",
                        metric = "Bokeh skip",
                        numerator = bokehSkipCount,
                        denominator = total,
                        evidenceLevel = "Observed decision",
                        note = "현재 모델이 Bokeh를 skip한 건수.",
                    ),
                    reviewMetric(
                        group = group,
                        category = "Bokeh skip validation",
                        metric = "Bokeh skip 중 budget 초과 예상",
                        numerator = captures.count {
                            it.bokehDecisionRow?.wasSkipped == true && it.bokehPredictedBudgetOverrun == true
                        },
                        denominator = bokehSkipCount,
                        evidenceLevel = "Predicted upper bound",
                        note = "Bokeh skip row에서 predicted upper bound가 budget보다 큰 건수.",
                    ),
                    ReviewMetricSummary(
                        group = group,
                        category = "Bokeh skip validation",
                        metric = "불필요한 Bokeh skip",
                        numerator = null,
                        denominator = bokehSkipCount,
                        rate = null,
                        evidenceLevel = "Offline oracle required",
                        note = "Bokeh를 실행했어도 budget 안에 들어왔을 skip 건수. offline replay/shadow 실행으로만 확정 가능.",
                    ),
                    reviewMetric(
                        group = group,
                        category = "Bokeh skip validation",
                        metric = "Bokeh skip 후 Filter 보존",
                        numerator = captures.count {
                            it.bokehDecisionRow?.wasSkipped == true && it.filterDecisionRow?.wasCompleted == true
                        },
                        denominator = bokehSkipCount,
                        evidenceLevel = "Observed outcome",
                        note = "Bokeh skip capture 중 Filter가 완료된 건수. '덕분에' 보존됐는지는 baseline/offline 비교 필요.",
                    ),
                    admitMetric(
                        group = group,
                        decision = ADMISSION_STAGE_BOKEH,
                        metric = "Bokeh admit success",
                        decisionRows = bokehDecisionRows,
                    ),
                    ubMetric(
                        group = group,
                        decision = ADMISSION_STAGE_BOKEH,
                        metric = "Bokeh UB miss",
                        decisionRows = bokehDecisionRows,
                    ),
                    admitMetric(
                        group = group,
                        decision = ADMISSION_STAGE_FILTER,
                        metric = "Filter admit success",
                        decisionRows = filterDecisionRows,
                    ),
                    ubMetric(
                        group = group,
                        decision = ADMISSION_STAGE_FILTER,
                        metric = "Filter UB miss",
                        decisionRows = filterDecisionRows,
                    ),
                )
            }

            private fun admitMetric(
                group: String,
                decision: String,
                metric: String,
                decisionRows: List<NodeSheetRow>,
            ): ReviewMetricSummary {
                val correctAdmitCount = decisionRows.count {
                    it.decisionOutcome() == DecisionOutcome.CORRECT_ADMIT
                }
                val unsafeAdmitCount = decisionRows.count {
                    it.decisionOutcome() == DecisionOutcome.UNSAFE_ADMIT
                }
                val observedAdmitCount = correctAdmitCount + unsafeAdmitCount
                return reviewMetric(
                    group = group,
                    category = "Decision quality",
                    metric = metric,
                    numerator = correctAdmitCount,
                    denominator = observedAdmitCount,
                    evidenceLevel = "Observed admitted suffix",
                    note = "$decision admit 결정이 실제 budget 안에 들어온 비율.",
                )
            }

            private fun ubMetric(
                group: String,
                decision: String,
                metric: String,
                decisionRows: List<NodeSheetRow>,
            ): ReviewMetricSummary {
                val evaluatedCount = decisionRows.count { it.sequenceUpperBoundMiss() != null }
                val missCount = decisionRows.count { it.sequenceUpperBoundMiss() == true }
                return reviewMetric(
                    group = group,
                    category = "Decision quality",
                    metric = metric,
                    numerator = missCount,
                    denominator = evaluatedCount,
                    evidenceLevel = "Observed admitted suffix",
                    note = "$decision predicted upper bound보다 실제 suffix 시간이 길었던 비율.",
                )
            }

            private fun reviewMetric(
                group: String,
                category: String,
                metric: String,
                numerator: Int,
                denominator: Int,
                evidenceLevel: String,
                note: String,
            ): ReviewMetricSummary {
                return ReviewMetricSummary(
                    group = group,
                    category = category,
                    metric = metric,
                    numerator = numerator,
                    denominator = denominator,
                    rate = rate(numerator, denominator),
                    evidenceLevel = evidenceLevel,
                    note = note,
                )
            }
        }
    }

    private class SimulationScenarioSummary(
        val group: String,
        val scenario: String,
        val method: String,
        val captureCount: Int,
        val budgetOverrunRiskCount: Int?,
        val budgetOverrunRiskEvaluatedCount: Int?,
        val budgetOverrunRiskRate: Double?,
        val filterPreservedCount: Int?,
        val filterPreservationRate: Double?,
        val bokehExecutedCount: Int?,
        val bokehExecutionRate: Double?,
        val fullFeatureSuccessCount: Int?,
        val fullFeatureSuccessRate: Double?,
        val offlineOracleRequired: Boolean,
        val note: String,
    ) {
        companion object {
            fun from(group: String, captures: List<CaptureRow>): List<SimulationScenarioSummary> {
                val total = captures.size
                val alwaysRunRiskKnownCount = captures.count { it.alwaysRunBudgetRiskByUpperBound != null }
                val alwaysRunRiskCount = captures.count { it.alwaysRunBudgetRiskByUpperBound == true }
                val alwaysSkipRiskKnownCount = captures.count { it.filterPredictedBudgetOverrun != null }
                val alwaysSkipRiskCount = captures.count { it.filterPredictedBudgetOverrun == true }

                return listOf(
                    SimulationScenarioSummary(
                        group = group,
                        scenario = "Current Model",
                        method = "Observed online",
                        captureCount = total,
                        budgetOverrunRiskCount = captures.count { it.hasTimeoutOrWatchdogFailure },
                        budgetOverrunRiskEvaluatedCount = total,
                        budgetOverrunRiskRate = rate(captures.count { it.hasTimeoutOrWatchdogFailure }, total),
                        filterPreservedCount = captures.count { it.isFilterPreserved },
                        filterPreservationRate = rate(captures.count { it.isFilterPreserved }, total),
                        bokehExecutedCount = captures.count { it.isBokehExecuted },
                        bokehExecutionRate = rate(captures.count { it.isBokehExecuted }, total),
                        fullFeatureSuccessCount = captures.count { it.isFullFeatureSuccess },
                        fullFeatureSuccessRate = rate(captures.count { it.isFullFeatureSuccess }, total),
                        offlineOracleRequired = false,
                        note = "현재 정책의 실제 online 결과. 이 행의 budgetOverrunRisk는 실측 timeout/watchdog 발생률이고, " +
                                "Always Run/Skip 행은 predicted-UB proxy라 축이 다름 - 절대값이 아니라 방향(현재 ~0 vs baseline↑)으로 비교할 것.",
                    ),
                    SimulationScenarioSummary(
                        group = group,
                        scenario = "Always Run Bokeh + Filter",
                        method = "Predicted UB simulation",
                        captureCount = total,
                        budgetOverrunRiskCount = alwaysRunRiskCount,
                        budgetOverrunRiskEvaluatedCount = alwaysRunRiskKnownCount,
                        budgetOverrunRiskRate = rate(alwaysRunRiskCount, alwaysRunRiskKnownCount),
                        filterPreservedCount = captures.count { it.filterDecisionRow != null },
                        filterPreservationRate = rate(captures.count { it.filterDecisionRow != null }, total),
                        bokehExecutedCount = captures.count { it.bokehDecisionRow != null },
                        bokehExecutionRate = rate(captures.count { it.bokehDecisionRow != null }, total),
                        fullFeatureSuccessCount = captures.count { it.alwaysRunBudgetRiskByUpperBound == false },
                        fullFeatureSuccessRate = rate(captures.count { it.alwaysRunBudgetRiskByUpperBound == false }, total),
                        offlineOracleRequired = true,
                        note = "Bokeh/Filter를 항상 실행한다고 가정하고 recorded predicted upper bound > budget을 risk로 세는 proxy. 실제 timeout/watchdog는 offline replay 필요.",
                    ),
                    SimulationScenarioSummary(
                        group = group,
                        scenario = "Always Skip Bokeh",
                        method = "Filter-decision proxy",
                        captureCount = total,
                        budgetOverrunRiskCount = alwaysSkipRiskCount,
                        budgetOverrunRiskEvaluatedCount = alwaysSkipRiskKnownCount,
                        budgetOverrunRiskRate = rate(alwaysSkipRiskCount, alwaysSkipRiskKnownCount),
                        filterPreservedCount = captures.count { it.filterDecisionRow?.wasAdmitted == true },
                        filterPreservationRate = rate(captures.count { it.filterDecisionRow?.wasAdmitted == true }, total),
                        bokehExecutedCount = 0,
                        bokehExecutionRate = rate(0, total),
                        fullFeatureSuccessCount = 0,
                        fullFeatureSuccessRate = rate(0, total),
                        offlineOracleRequired = true,
                        note = "Bokeh를 항상 포기하고 Filter decision만 본 proxy. Bokeh skip으로 생기는 추가 runway까지 반영하려면 offline replay/shadow 실행 필요.",
                    ),
                )
            }
        }
    }

    private class PacingSimulationSummary(
        val group: String,
        val targetStage: String,
        val method: String,
        val captureCount: Int,
        val evaluatedCount: Int,
        val targetAdmitBeforeCount: Int,
        val targetAdmitBeforeRate: Double?,
        val targetAdmitAfterCount: Int,
        val targetAdmitAfterRate: Double?,
        val targetAdmitGainCount: Int,
        val mandatoryRiskBeforeCount: Int,
        val mandatoryRiskBeforeRate: Double?,
        val mandatoryRiskAfterCount: Int,
        val mandatoryRiskAfterRate: Double?,
        val zeroDelayCount: Int,
        val zeroDelayRate: Double?,
        val totalAppliedDelayMs: Long,
        val averageAppliedDelayMs: Double?,
        val maxAppliedDelayMs: Long?,
        val note: String,
    ) {
        companion object {
            fun from(group: String, captures: List<EnrichedCaptureRow>): List<PacingSimulationSummary> {
                val simulations = captures.flatMap { capture ->
                    listOfNotNull(capture.row.budgetDeficitPacing, capture.queueAwarePacing)
                }
                val stages = listOf(
                    PACING_TARGET_ALL,
                    ADMISSION_STAGE_BOKEH,
                    ADMISSION_STAGE_DECODING,
                    ADMISSION_STAGE_FILTER,
                    ADMISSION_STAGE_OVERLAY_WATERMARK,
                )

                return simulations.groupBy { it.method }
                    .toSortedMap()
                    .flatMap { (_, methodSimulations) ->
                        stages.mapNotNull { stage ->
                            val stageSimulations = if (stage == PACING_TARGET_ALL) {
                                methodSimulations
                            } else {
                                methodSimulations.filter { it.targetStage == stage }
                            }
                            if (stage != PACING_TARGET_ALL && stageSimulations.isEmpty()) {
                                return@mapNotNull null
                            }
                            fromSimulations(group, stage, captures.size, stageSimulations)
                        }
                    }
            }

            private fun fromSimulations(
                group: String,
                targetStage: String,
                captureCount: Int,
                simulations: List<PacingSimulation>,
            ): PacingSimulationSummary {
                val evaluatedCount = simulations.size
                val targetAdmitBeforeCount = simulations.count { it.targetAdmitBeforePacing }
                val targetAdmitAfterCount = simulations.count { it.targetAdmitAfterPacing }
                val mandatoryRiskBeforeCount = simulations.count { !it.mandatorySafeBeforePacing }
                val mandatoryRiskAfterCount = simulations.count { !it.mandatorySafeAfterPacing }
                val zeroDelayCount = simulations.count { it.appliedDelayMs == 0L }
                val totalAppliedDelayMs = simulations.sumOf { it.appliedDelayMs }

                return PacingSimulationSummary(
                    group = group,
                    targetStage = targetStage,
                    method = simulations.firstOrNull()?.method.orEmpty(),
                    captureCount = captureCount,
                    evaluatedCount = evaluatedCount,
                    targetAdmitBeforeCount = targetAdmitBeforeCount,
                    targetAdmitBeforeRate = rate(targetAdmitBeforeCount, evaluatedCount),
                    targetAdmitAfterCount = targetAdmitAfterCount,
                    targetAdmitAfterRate = rate(targetAdmitAfterCount, evaluatedCount),
                    targetAdmitGainCount = simulations.count {
                        !it.targetAdmitBeforePacing && it.targetAdmitAfterPacing
                    },
                    mandatoryRiskBeforeCount = mandatoryRiskBeforeCount,
                    mandatoryRiskBeforeRate = rate(mandatoryRiskBeforeCount, evaluatedCount),
                    mandatoryRiskAfterCount = mandatoryRiskAfterCount,
                    mandatoryRiskAfterRate = rate(mandatoryRiskAfterCount, evaluatedCount),
                    zeroDelayCount = zeroDelayCount,
                    zeroDelayRate = rate(zeroDelayCount, evaluatedCount),
                    totalAppliedDelayMs = totalAppliedDelayMs,
                    averageAppliedDelayMs = if (evaluatedCount > 0) {
                        totalAppliedDelayMs.toDouble() / evaluatedCount.toDouble()
                    } else {
                        null
                    },
                    maxAppliedDelayMs = simulations.maxOfOrNull { it.appliedDelayMs },
                    note = simulations.firstOrNull()?.note.orEmpty(),
                )
            }
        }
    }

    private class MetricNote(
        val metric: String,
        val note: String,
    )

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
        private const val ADMIT_BOKEH_PREFIX = "BOKEH("
        private const val ADMIT_DECODING_PREFIX = "DECODING("
        private const val ADMIT_FILTER_PREFIX = "FILTER("
        private const val ADMIT_WATERMARK_PREFIX = "WATERMARK("
        private const val WATERMARK_TYPE_OVERLAY = "watermarkType=OVERLAY"
        private const val ADMISSION_STAGE_BOKEH = "Bokeh"
        private const val ADMISSION_STAGE_DECODING = "Decoding"
        private const val ADMISSION_STAGE_FILTER = "Filter"
        private const val ADMISSION_STAGE_OVERLAY_WATERMARK = "OverlayWatermark"
        private const val PACING_TARGET_ALL = "All"
        private const val BUDGET_DEFICIT_PACING_METHOD = "Budget-deficit predicted UB proxy"
        private const val QUEUE_AWARE_PACING_METHOD = "Queue-aware burst proxy"
        private const val BUDGET_DEFICIT_PACING_NOTE = "Counterfactual assumes applied delay converts to equal budget runway; validate with runtime logs."
        private const val QUEUE_AWARE_PACING_NOTE = "Uses CaptureMetrics proxy inputs: captureAvailableElapsed=0ms and queuedDraftCount=sessionCaptureIndex-1. Replace proxy columns with runtime fields when persisted."
        private const val QUEUE_AWARE_CAPTURE_AVAILABLE_ELAPSED_PROXY_MS = 0L
        private const val ADMISSION_SKIP_REASON_UPPER_BOUND = "upper bound"
        private const val ADMISSION_SKIP_REASON_BUDGET_RUNWAY = "budget runway"

        private fun rate(numerator: Int, denominator: Int): Double? {
            if (denominator <= 0) {
                return null
            }
            return numerator.toDouble() / denominator.toDouble()
        }

        private fun positiveCeilMs(valueMs: Double): Long {
            if (valueMs <= 0.0 || valueMs.isNaN()) {
                return 0L
            }
            return ceil(valueMs).toLong().coerceAtLeast(0L)
        }

        private fun positiveFloorMs(valueMs: Double): Long {
            if (valueMs <= 0.0 || valueMs.isNaN()) {
                return 0L
            }
            return floor(valueMs).toLong().coerceAtLeast(0L)
        }

        private fun pacingTargetStage(leadingRow: NodeRow): String {
            leadingRow.admissionStage()?.let { return it }
            val workloadSequenceKey = leadingRow.prediction?.workloadSequenceKey.orEmpty()
            return when {
                workloadSequenceKey.contains(ADMIT_BOKEH_PREFIX) -> ADMISSION_STAGE_BOKEH
                workloadSequenceKey.contains(ADMIT_DECODING_PREFIX) -> ADMISSION_STAGE_DECODING
                workloadSequenceKey.contains(ADMIT_FILTER_PREFIX) -> ADMISSION_STAGE_FILTER
                workloadSequenceKey.contains(ADMIT_WATERMARK_PREFIX) &&
                        workloadSequenceKey.contains(WATERMARK_TYPE_OVERLAY) -> ADMISSION_STAGE_OVERLAY_WATERMARK
                else -> "ObserveOnly"
            }
        }

        private fun nodeSheetRows(captures: List<CaptureRow>): List<NodeSheetRow> {
            return captures.flatMap { capture ->
                capture.nodeRows.mapIndexed { index, nodeRow ->
                    NodeSheetRow(capture, index + 1, nodeRow)
                }
            }
        }

        private fun decisionRows(captures: List<CaptureRow>, decision: String): List<NodeSheetRow> {
            return nodeSheetRows(captures).filter { it.admissionStage() == decision }
        }

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
            Column("draftStartBudgetMs") { it.row.draftStartRow?.node?.preExecutionMetrics?.budgetMs },
            Column("encodingReserveUpperBoundMs") { it.row.mandatoryReserveUpperBoundMs },
            Column("pacingTargetUpperBoundMs") { it.row.pacingTargetUpperBoundMs },
            Column("pacingSlackMs") { it.row.pacingSlackMs },
            Column("mandatoryReserveDeficitMs") { it.row.budgetDeficitPacing?.mandatoryDeficitMs },
            Column("optionalAdmissionDeficitMs") { it.row.budgetDeficitPacing?.optionalDeficitMs },
            Column("optionalHeadroomMs") { it.row.budgetDeficitPacing?.optionalHeadroomMs },
            Column("budgetDeficitPacingDelayMs") { it.row.budgetDeficitPacing?.appliedDelayMs },
            Column("simulatedBudgetAfterPacingMs") { it.row.budgetDeficitPacing?.simulatedBudgetAfterPacingMs },
            Column("targetAdmitBeforePacing") { it.row.budgetDeficitPacing?.targetAdmitBeforePacing },
            Column("targetAdmitAfterPacing") { it.row.budgetDeficitPacing?.targetAdmitAfterPacing },
            Column("mandatorySafeBeforePacing") { it.row.budgetDeficitPacing?.mandatorySafeBeforePacing },
            Column("mandatorySafeAfterPacing") { it.row.budgetDeficitPacing?.mandatorySafeAfterPacing },
            Column("queueAwarePacingInputSource") { it.queueAwarePacingInputSource },
            Column("queueAwareCaptureAvailableElapsedProxyMs") { it.queueAwareCaptureAvailableElapsedProxyMs },
            Column("queueAwareQueuedDraftCountProxy") { it.queueAwareQueuedDraftCountProxy },
            Column("queueAwarePredictedDraftDurationMs") { it.queueAwarePacing?.predictedDraftDurationMs },
            Column("queueAwareUnservedDraftWorkMs") { it.queueAwarePacing?.unservedDraftWorkMs },
            Column("queueAwareQueuedDraftWorkMs") { it.queueAwarePacing?.queuedDraftWorkMs },
            Column("queueAwareAvailableBudgetMs") { it.queueAwarePacing?.availableBudgetMs },
            Column("queueAwareMandatoryReserveShortageMs") { it.queueAwarePacing?.mandatoryDeficitMs },
            Column("queueAwarePreferredBudgetShortageMs") { it.queueAwarePacing?.optionalDeficitMs },
            Column("queueAwareOptionalBudgetHeadroomMs") { it.queueAwarePacing?.optionalHeadroomMs },
            Column("queueAwarePacingDelayMs") { it.queueAwarePacing?.appliedDelayMs },
            Column("queueAwareSimulatedBudgetAfterPacingMs") { it.queueAwarePacing?.simulatedBudgetAfterPacingMs },
            Column("queueAwareTargetAdmitBeforePacing") { it.queueAwarePacing?.targetAdmitBeforePacing },
            Column("queueAwareTargetAdmitAfterPacing") { it.queueAwarePacing?.targetAdmitAfterPacing },
            Column("queueAwareMandatorySafeBeforePacing") { it.queueAwarePacing?.mandatorySafeBeforePacing },
            Column("queueAwareMandatorySafeAfterPacing") { it.queueAwarePacing?.mandatorySafeAfterPacing },
            Column("draftNodeDurationMs") { it.row.encodingReserveRow?.nodeActualDurationMs },
            Column("") { "" },
            Column("pacerSessionId") { it.row.metrics.draftSequenceMetrics?.pacerSessionId },
            Column("draftStartUptimeMs") { it.row.metrics.draftSequenceMetrics?.draftStartUptimeMs },
            Column("draftEndUptimeMs") { it.row.metrics.draftSequenceMetrics?.draftEndUptimeMs },
            Column("timeoutDeadlineUptimeMs") { it.row.metrics.timeoutTimestampMs },
            Column("runtimePacingDecisionUptimeMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.decisionUptimeMs },
            Column("runtimePacingAppliedDelayMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.appliedDelayMs },
            Column("runtimePacingLevelDeficitMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.levelDeficitMs },
            Column("runtimePacingBacklogDeficitMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.backlogDeficitMs },
            Column("runtimePacingBacklogMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.backlogMs },
            Column("runtimePacingQueuedDraftCount") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.queuedDraftCount },
            Column("runtimePacingQueuedPredictedWorkMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.queuedPredictedWorkMs },
            Column("runtimePacingDraftStartBudgetMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.draftStartBudgetMs },
            Column("runtimePacingMandatoryReserveUpperBoundMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.mandatoryReserveUpperBoundMs },
            Column("runtimePacingPreferredPathPredictedMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.preferredDraftPathPredictedMs },
            Column("runtimePacingPreferredPathUpperBoundMs") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.preferredDraftPathUpperBoundMs },
            Column("runtimePacingWorkloadSequenceKey") { it.row.metrics.draftSequenceMetrics?.captureAvailablePacing?.workloadSequenceKey },
            Column("") { "" },
            Column("sessionId") { it.sessionSummary.sessionId },
            Column("sessionCaptureIndex") { it.sessionSummary.sessionCaptureIndex },
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
            Column("") { "" },
            Column("budgetMs") { it.nodeRow.node.preExecutionMetrics.budgetMs },
            Column("admit") { it.nodeRow.prediction?.admit },
            Column("admissionSkipReason") { it.admissionSkipReason() },
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

        private fun buildDecisionQualityColumns(): List<Column<DecisionQualitySummary>> = listOf(
            Column("group") { it.group },
            Column("decision") { it.decision },
            Column("decisionCount") { it.decisionCount },
            Column("admitDecisionCount") { it.admitDecisionCount },
            Column("skipDecisionCount") { it.skipDecisionCount },
            Column("observedAdmitDecisionCount") { it.observedAdmitDecisionCount },
            Column("correctAdmitCount") { it.correctAdmitCount },
            Column("unsafeAdmitCount") { it.unsafeAdmitCount },
            Column("admitOutcomeNotFullyObservedCount") { it.admitOutcomeNotFullyObservedCount },
            Column("admitSuccessRate") { it.admitSuccessRate },
            Column("unsafeAdmitRate") { it.unsafeAdmitRate },
            Column("ubEvaluatedCount") { it.ubEvaluatedCount },
            Column("ubMissCount") { it.ubMissCount },
            Column("ubMissRate") { it.ubMissRate },
            Column("skipCorrectnessStatus") { it.skipCorrectnessStatus },
        )

        private fun buildPolicyOutcomeColumns(): List<Column<PolicyOutcomeSummary>> = listOf(
            Column("group") { it.group },
            Column("captureCount") { it.captureCount },
            Column("timeoutCount") { it.timeoutCount },
            Column("timeoutRate") { it.timeoutRate },
            Column("watchdogTriggerCount") { it.watchdogTriggerCount },
            Column("watchdogTriggerRate") { it.watchdogTriggerRate },
            Column("filterPreservedCount") { it.filterPreservedCount },
            Column("filterPreservationRate") { it.filterPreservationRate },
            Column("bokehExecutedCount") { it.bokehExecutedCount },
            Column("bokehExecutionRate") { it.bokehExecutionRate },
            Column("bothSkippedCount") { it.bothSkippedCount },
            Column("bothSkippedRate") { it.bothSkippedRate },
            Column("fullFeatureSuccessCount") { it.fullFeatureSuccessCount },
            Column("fullFeatureSuccessRate") { it.fullFeatureSuccessRate },
            Column("selectiveBokehSkipSuccessCount") { it.selectiveBokehSkipSuccessCount },
            Column("selectiveBokehSkipSuccessRate") { it.selectiveBokehSkipSuccessRate },
            Column("observedFilterLossAfterBokehAdmitCount") { it.observedFilterLossAfterBokehAdmitCount },
            Column("observedFilterLossAfterBokehAdmitRate") { it.observedFilterLossAfterBokehAdmitRate },
            Column("tailOnlySafeCount") { it.tailOnlySafeCount },
            Column("tailOnlySafeRate") { it.tailOnlySafeRate },
            Column("timeoutFailureCount") { it.timeoutFailureCount },
            Column("timeoutFailureRate") { it.timeoutFailureRate },
            Column("watchdogFailureCount") { it.watchdogFailureCount },
            Column("watchdogFailureRate") { it.watchdogFailureRate },
            Column("otherCount") { it.otherCount },
            Column("otherRate") { it.otherRate },
        )

        private fun buildReviewMetricColumns(): List<Column<ReviewMetricSummary>> = listOf(
            Column("group") { it.group },
            Column("category") { it.category },
            Column("metric") { it.metric },
            Column("numerator") { it.numerator },
            Column("denominator") { it.denominator },
            Column("rate") { it.rate },
            Column("evidenceLevel") { it.evidenceLevel },
            Column("note") { it.note },
        )

        private fun buildSimulationScenarioColumns(): List<Column<SimulationScenarioSummary>> = listOf(
            Column("group") { it.group },
            Column("scenario") { it.scenario },
            Column("method") { it.method },
            Column("captureCount") { it.captureCount },
            Column("budgetOverrunRiskCount") { it.budgetOverrunRiskCount },
            Column("budgetOverrunRiskEvaluatedCount") { it.budgetOverrunRiskEvaluatedCount },
            Column("budgetOverrunRiskRate") { it.budgetOverrunRiskRate },
            Column("filterPreservedCount") { it.filterPreservedCount },
            Column("filterPreservationRate") { it.filterPreservationRate },
            Column("bokehExecutedCount") { it.bokehExecutedCount },
            Column("bokehExecutionRate") { it.bokehExecutionRate },
            Column("fullFeatureSuccessCount") { it.fullFeatureSuccessCount },
            Column("fullFeatureSuccessRate") { it.fullFeatureSuccessRate },
            Column("offlineOracleRequired") { it.offlineOracleRequired },
            Column("note") { it.note },
        )

        private fun buildPacingSimulationColumns(): List<Column<PacingSimulationSummary>> = listOf(
            Column("group") { it.group },
            Column("targetStage") { it.targetStage },
            Column("method") { it.method },
            Column("captureCount") { it.captureCount },
            Column("evaluatedCount") { it.evaluatedCount },
            Column("targetAdmitBeforeCount") { it.targetAdmitBeforeCount },
            Column("targetAdmitBeforeRate") { it.targetAdmitBeforeRate },
            Column("targetAdmitAfterCount") { it.targetAdmitAfterCount },
            Column("targetAdmitAfterRate") { it.targetAdmitAfterRate },
            Column("targetAdmitGainCount") { it.targetAdmitGainCount },
            Column("mandatoryRiskBeforeCount") { it.mandatoryRiskBeforeCount },
            Column("mandatoryRiskBeforeRate") { it.mandatoryRiskBeforeRate },
            Column("mandatoryRiskAfterCount") { it.mandatoryRiskAfterCount },
            Column("mandatoryRiskAfterRate") { it.mandatoryRiskAfterRate },
            Column("zeroDelayCount") { it.zeroDelayCount },
            Column("zeroDelayRate") { it.zeroDelayRate },
            Column("totalAppliedDelayMs") { it.totalAppliedDelayMs },
            Column("averageAppliedDelayMs") { it.averageAppliedDelayMs },
            Column("maxAppliedDelayMs") { it.maxAppliedDelayMs },
            Column("note") { it.note },
        )

        private fun buildMetricNoteColumns(): List<Column<MetricNote>> = listOf(
            Column("metric") { it.metric },
            Column("note") { it.note },
        )
    }

}
