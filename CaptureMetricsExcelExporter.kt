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

class CaptureMetricsExcelExporter(
    private val context: Context,
    private val repository: CaptureMetricsRepository,
) {

    private class EnrichedCaptureRow(
        val row: CaptureRow,
        val sessionSummary: SessionSummary,
    )

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
        val groups = mutableListOf<List<CaptureRow>>()
        var currentGroup = mutableListOf<CaptureRow>()
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

            group.forEach { groupMember ->
                val bokehRows = groupMember.nodeRows.filter { it.isBokehWorkload && it.prediction != null }
                val filterRows = groupMember.nodeRows.filter { it.isFilterWorkload && it.prediction != null }
                bokehAdmitCount += bokehRows.count { it.prediction?.admit == true }
                bokehTotalCount += bokehRows.size
                filterAdmitCount += filterRows.count { it.prediction?.admit == true }
                filterTotalCount += filterRows.size

                val sessionSummary = SessionSummary(
                    sessionId = sessionId,
                    sessionShotCount = group.size,
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

    private fun generateSubSheets(
        workbook: Workbook,
        styles: Styles,
        captures: List<EnrichedCaptureRow>,
        sheetNamePrefix: String,
    ) {
        val nodeRowsByNodeName = buildNodeSheetRows(captures.map { it.row })
            .groupBy { it.nodeRow.node.nodeName }

        nodeRowsByNodeName.toSortedMap().forEach { (nodeName, rows) ->
            val sheetName = uniqueSheetName(workbook, "$sheetNamePrefix$nodeName")
            val nodeColumns = buildNodeColumns()
            writeSheet(workbook, styles, sheetName, rows, nodeColumns)
        }
    }

    private fun buildNodeSheetRows(captures: List<CaptureRow>): List<NodeSheetRow> {
        return captures.flatMap { capture ->
            capture.nodeRows.mapIndexed { index, nodeRow ->
                NodeSheetRow(capture, index + 1, nodeRow)
            }
        }
    }

    private fun buildDecisionQualitySummaries(captures: List<EnrichedCaptureRow>): List<DecisionQualitySummary> {
        return captureGroups(captures.map { it.row }).flatMap { group ->
            listOf(
                DecisionQualitySummary.from(group.name, ADMISSION_STAGE_BOKEH, group.captures),
                DecisionQualitySummary.from(group.name, ADMISSION_STAGE_FILTER, group.captures),
            )
        }
    }

    private fun buildPolicyOutcomeSummaries(captures: List<EnrichedCaptureRow>): List<PolicyOutcomeSummary> {
        return captureGroups(captures.map { it.row }).map { group ->
            PolicyOutcomeSummary.from(group.name, group.captures)
        }
    }

    private fun captureGroups(captures: List<CaptureRow>): List<CaptureGroup> {
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
            metric = "Pacing simulation",
            note = "Slack-policy delay = MAX(0, MIN(legacyDelay, legacyDelay - pacingSlackMs)). " +
                    "Trend-policy delay: EWMA(alpha=0.3) over firstAdmitBudgetMs deltas between consecutive same-session rows; " +
                    "negative trend * 1.43 capped at legacyDelay, positive trend * 0.3 subtracted from legacyDelay. " +
                    "legacyDelay = MAX(0, AVERAGE(last 3 draftNodeDurationMs) - captureAvailableTime); captureAvailableTime is " +
                    "not stored in capture metrics - take it per capture from CaptureAvailableApmPolicy logs. " +
                    "encodingReserveUpperBoundMs is the recorded suffix UB after the first admission decision, so it can " +
                    "include OBSERVE stages that the runtime COMPLETE-only reserve excludes; pacingSlackMs is signed while " +
                    "the runtime watchdog value clamps at 0.",
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

        val filterDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isFilterWorkload && it.prediction != null }

        /** First admission decision of the capture - the node whose budget feeds captureAvailable pacing. */
        val firstAdmissionDecisionRow: NodeRow?
            get() = nodeRows.firstOrNull { it.isAdmissionWorkload && it.prediction != null }

        /** First tail node after the first admission decision - the draft/encoding stage the pacing reserve protects. */
        val encodingReserveRow: NodeRow?
            get() {
                val admissionIndex = nodeRows.indexOfFirst { it.isAdmissionWorkload && it.prediction != null }
                if (admissionIndex < 0) {
                    return null
                }
                return nodeRows.drop(admissionIndex + 1)
                    .firstOrNull { !it.isAdmissionWorkload && it.prediction != null }
            }

        /** Signed pacing slack the slack policy consumes: first ADMIT budget minus the encoding reserve. */
        val pacingSlackMs: Double?
            get() {
                val budgetMs = firstAdmissionDecisionRow?.node?.preExecutionMetrics?.budgetMs ?: return null
                val reserveMs = encodingReserveRow?.prediction?.sequencePredictedUpperBoundMs ?: return null
                return budgetMs - reserveMs
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
    }

    private class SessionSummary(
        val sessionId: Int,
        val sessionShotCount: Int,
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

        val isFilterWorkload: Boolean
            get() = node.workloadKey?.startsWith(ADMIT_FILTER_PREFIX) == true

        val isAdmissionWorkload: Boolean
            get() = isBokehWorkload || isFilterWorkload

        val wasAdmitted: Boolean?
            get() = prediction?.admit

        val wasSkipped: Boolean
            get() = prediction?.admit == false

        val wasCompleted: Boolean
            get() = prediction?.admit == true && nodeActualDurationMs != null

        fun admissionStage(): String? {
            return when {
                isBokehWorkload -> ADMISSION_STAGE_BOKEH
                isFilterWorkload -> ADMISSION_STAGE_FILTER
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

    private class CaptureGroup(
        val name: String,
        val captures: List<CaptureRow>,
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
                val decisionRows = captures.flatMap { capture ->
                    capture.nodeRows.mapIndexed { index, nodeRow ->
                        NodeSheetRow(capture, index + 1, nodeRow)
                    }
                }.filter {
                    it.admissionStage() == decision
                }

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
        private const val ADMIT_FILTER_PREFIX = "FILTER("
        private const val ADMISSION_STAGE_BOKEH = "Bokeh"
        private const val ADMISSION_STAGE_FILTER = "Filter"
        private const val ADMISSION_SKIP_REASON_UPPER_BOUND = "upper bound"
        private const val ADMISSION_SKIP_REASON_BUDGET_RUNWAY = "budget runway"

        private fun rate(numerator: Int, denominator: Int): Double? {
            if (denominator <= 0) {
                return null
            }
            return numerator.toDouble() / denominator.toDouble()
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
            Column("firstAdmitBudgetMs") { it.row.firstAdmissionDecisionRow?.node?.preExecutionMetrics?.budgetMs },
            Column("encodingReserveUpperBoundMs") { it.row.encodingReserveRow?.prediction?.sequencePredictedUpperBoundMs },
            Column("pacingSlackMs") { it.row.pacingSlackMs },
            Column("draftNodeDurationMs") { it.row.encodingReserveRow?.nodeActualDurationMs },
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
            Column("") { "" },
            Column("budgetMs") { it.nodeRow.node.preExecutionMetrics.budgetMs },
            Column("admit") { it.nodeRow.prediction?.admit },
            Column("admissionSkipReason") { it.admissionSkipReason() },
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

        private fun buildMetricNoteColumns(): List<Column<MetricNote>> = listOf(
            Column("metric") { it.metric },
            Column("note") { it.note },
        )
    }

}
