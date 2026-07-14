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
            val replayCaptures = enrichedNormalCaptures.sortedBy { enriched -> enriched.row.captureIndex }
            val admissionReplayRows = nodeSheetRows(replayCaptures)
                .filter { row -> row.nodeRow.isAdmissionWorkload && row.nodeRow.prediction != null }

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
        val groups = groupCaptures(captures)
        groups.forEach(::simulateAdmissionAfter)

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

    /** Replays the current admission code over recorded predictions while preserving sticky demotion order. */
    private fun simulateAdmissionAfter(captures: List<CaptureRow>) {
        val replayPacer = CaptureAvailablePacer(DraftSequenceExecutionPredictor())

        for (capture in captures) {
            for (row in capture.nodeRows) {
                val prediction = row.prediction ?: continue
                val workloadKey = row.replayWorkloadKey() ?: continue

                val modelAdmit = shouldAdmitOptionalWorkload(
                    sequencePredictedMs = prediction.sequencePredictedDurationMs,
                    sequenceUpperBoundMs = prediction.sequencePredictedUpperBoundMs,
                    budgetMs = row.node.preExecutionMetrics.budgetMs,
                )
                val hasFrameWatermark = prediction.workloadSequenceKey?.contains(WATERMARK_TYPE_FRAME) == true
                val demotion = when (workloadKey) {
                    is WorkloadKey.Bokeh -> SessionDemotion.BOKEH
                    is WorkloadKey.Decoding, is WorkloadKey.Filter, is WorkloadKey.Watermark ->
                        SessionDemotion.YUV_EFFECTS
                    is WorkloadKey.DynamicFunction, is WorkloadKey.Encoding -> null
                }

                row.afterModelAdmit = modelAdmit
                row.afterSessionDemotedBeforeDecision = demotion?.let(replayPacer::isSessionDemoted)
                row.afterAdmit = applySessionAdmissionPolicy(
                    captureAvailablePacer = replayPacer,
                    workloadKey = workloadKey,
                    hasFrameWatermark = hasFrameWatermark,
                    modelAdmit = modelAdmit,
                )
            }
        }
    }

    /** Groups captures into timeout-delimited sessions. */
    private fun groupCaptures(captures: List<CaptureRow>): List<List<CaptureRow>> {
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
        private val backlogBaseWithoutSojournMs =
            before.backlogMs + before.preferredDraftPathUpperBoundMs - captureTimeoutMs

        val inferredObservedSojournMs: Long? = if (before.backlogDeficitMs > 0L) {
            (before.backlogDeficitMs - ceil(backlogBaseWithoutSojournMs).toLong()).coerceAtLeast(0L)
        } else {
            null
        }
        val observedSojournMinMs: Long = inferredObservedSojournMs ?: 0L
        val observedSojournMaxMs: Long = inferredObservedSojournMs ?: floor(
            (captureTimeoutMs - before.backlogMs - before.preferredDraftPathUpperBoundMs).coerceAtLeast(0.0),
        ).toLong()
        val observedSojournInference: String = if (inferredObservedSojournMs != null) {
            PACING_SOJOURN_EXACT
        } else {
            PACING_SOJOURN_BOUNDED
        }

        val afterLevelDeficitMs: Long = captureAvailableLevelDeficitMs(
            draftStartBudgetMs = before.draftStartBudgetMs,
            preferredUpperBoundMs = before.preferredDraftPathUpperBoundMs,
        )
        val afterBacklogDeficitMinMs: Long = captureAvailableBacklogDeficitMs(
            backlogMs = before.backlogMs,
            observedSojournMs = observedSojournMinMs,
            preferredUpperBoundMs = before.preferredDraftPathUpperBoundMs,
        )
        val afterBacklogDeficitMaxMs: Long = captureAvailableBacklogDeficitMs(
            backlogMs = before.backlogMs,
            observedSojournMs = observedSojournMaxMs,
            preferredUpperBoundMs = before.preferredDraftPathUpperBoundMs,
        )
        val afterBacklogDeficitMs: Long? = afterBacklogDeficitMinMs.takeIf { minimum ->
            minimum == afterBacklogDeficitMaxMs
        }
        val afterAppliedDelayMinMs: Long = captureAvailableDelayMs(
            afterLevelDeficitMs,
            afterBacklogDeficitMinMs,
        )
        val afterAppliedDelayMaxMs: Long = captureAvailableDelayMs(
            afterLevelDeficitMs,
            afterBacklogDeficitMaxMs,
        )
        val afterAppliedDelayMs: Long? = afterAppliedDelayMinMs.takeIf { minimum ->
            minimum == afterAppliedDelayMaxMs
        }
        val delayDeltaMs: Long? = afterAppliedDelayMs?.minus(before.appliedDelayMs)
        val pacingChanged: Boolean? = afterAppliedDelayMs?.let { delayMs -> delayMs != before.appliedDelayMs }
        val replayStatus: String = when {
            inferredObservedSojournMs != null -> PACING_REPLAY_EXACT
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
        private const val ADMISSION_SESSION_BOUNDARY_SOURCE = "timeout-delimited proxy"
        private const val PACING_SOJOURN_EXACT = "Exact from positive backlog deficit"
        private const val PACING_SOJOURN_BOUNDED = "Bounded because backlog deficit was zero"
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

        fun replayWorkloadKey(): WorkloadKey? {
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
            Column("afterSessionBoundarySource") { ADMISSION_SESSION_BOUNDARY_SOURCE },
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
            Column("timeoutDeadlineUptimeMs") { it.row.metrics.timeoutTimestampMs },
            Column("firstNodeStartUptimeMs") { it.row.firstNodeStartUptimeMs },
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
            Column("beforeWorkloadSequenceKey") { it.row.pacingReplay?.before?.workloadSequenceKey },
            Column("beforeDraftStartBudgetMs") { it.row.pacingReplay?.before?.draftStartBudgetMs },
            Column("beforeMandatoryReserveUpperBoundMs") {
                it.row.pacingReplay?.before?.mandatoryReserveUpperBoundMs
            },
            Column("beforePreferredPathPredictedMs") {
                it.row.pacingReplay?.before?.preferredDraftPathPredictedMs
            },
            Column("beforePreferredPathUpperBoundMs") {
                it.row.pacingReplay?.before?.preferredDraftPathUpperBoundMs
            },
            Column("beforeBacklogMs") { it.row.pacingReplay?.before?.backlogMs },
            Column("beforeQueuedDraftCount") { it.row.pacingReplay?.before?.queuedDraftCount },
            Column("beforeQueuedReservedWorkMs") {
                it.row.pacingReplay?.before?.queuedPredictedWorkMs
            },
            Column("beforeLevelDeficitMs") { it.row.pacingReplay?.before?.levelDeficitMs },
            Column("beforeBacklogDeficitMs") { it.row.pacingReplay?.before?.backlogDeficitMs },
            Column("beforeDominantDeficit") { it.row.pacingReplay?.beforeDominantDeficit },
            Column("beforeAppliedDelayMs") { it.row.pacingReplay?.before?.appliedDelayMs },
            Column("") { "" },
            Column("captureTimeoutMs") { it.row.pacingReplay?.captureTimeoutMs },
            Column("observedSojournInference") { it.row.pacingReplay?.observedSojournInference },
            Column("inferredObservedSojournMs") { it.row.pacingReplay?.inferredObservedSojournMs },
            Column("observedSojournMinMs") { it.row.pacingReplay?.observedSojournMinMs },
            Column("observedSojournMaxMs") { it.row.pacingReplay?.observedSojournMaxMs },
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
                note = "sticky demotion is replayed over timeout-delimited proxy sessions because the runtime pacer " +
                    "session id was removed.",
            ),
            ReplayNote(
                topic = "Pacing sojourn",
                note = "when the recorded backlog deficit is positive, observed sojourn is recovered exactly. A zero " +
                    "deficit only provides a min/max range, so after delay is reported as a range unless both bounds " +
                    "produce the same result.",
            ),
            ReplayNote(
                topic = "Pacing prediction scope",
                note = "after pacing reuses the recorded preferred-path prediction, reserve, and backlog. Changes to " +
                    "reserve prediction or backlog reconstruction require a sequential replay with additional raw " +
                    "runtime observations.",
            ),
            ReplayNote(
                topic = "Counterfactual outcomes",
                note = "a changed decision whose workload was not observed online requires offline replay or shadow " +
                    "execution. afterOutcomeStatus and afterObservationStatus identify those rows.",
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
