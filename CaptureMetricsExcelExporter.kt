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

    private class EnrichedCaptureRow(val row: CaptureRow, val timeoutCount: Int?, val groupId: Int)

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

                val nodeRows = draftMetrics?.nodeExecutionMetricsList.orEmpty().mapIndexed { order, node ->
                    NodeRow(node, draftMetrics?.nodeExecutionPredictionList?.getOrNull(order))
                }

                val savingRow = draftMetrics?.savingExecutionMetrics?.let { savingMetrics ->
                    SavingRow(savingMetrics, draftMetrics.savingExecutionPrediction)
                }

                CaptureRow(
                    captureIndex = index + 1,
                    metrics = metrics,
                    nodeRows = nodeRows,
                    savingRow = savingRow
                )
            }

            val enrichedNormalCaptures = processCaptures(rawCaptures)

            // Write main sheets
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
        for (group in sortedGroups) {
            val timeoutIndexValue = if (group.last().metrics.draftSequenceMetrics?.isTimeout == true) group.size else null
            val groupId = group.first().captureIndex
            group.forEach { groupMember ->
                enriched.add(EnrichedCaptureRow(groupMember, timeoutIndexValue, groupId))
            }
        }
        return enriched
    }

    private fun generateSubSheets(
        workbook: Workbook,
        styles: Styles,
        captures: List<EnrichedCaptureRow>,
        sheetNamePrefix: String
    ) {
        val nodeRowsByNodeId = captures
            .flatMap { enrichedCapture ->
                enrichedCapture.row.nodeRows.map { nodeRow ->
                    Triple(enrichedCapture.row.captureIndex, nodeRow.node.nodeId.name, nodeRow)
                }
            }
            .groupBy { it.second } // group by nodeId

        nodeRowsByNodeId.toSortedMap().forEach { (nodeId, rows) ->
            val sheetName = uniqueSheetName(workbook, "$sheetNamePrefix$nodeId")
            val nodeColumns = buildNodeColumns()
            writeSheet(workbook, styles, sheetName, rows, nodeColumns)
        }

        if (captures.any { it.row.savingRow != null }) {
            val savingColumns = buildSavingColumns()
            val flattenedSavingRows = captures.mapNotNull { enrichedCapture ->
                enrichedCapture.row.savingRow?.let { Pair(enrichedCapture.row.captureIndex, it) }
            }
            writeSheet(workbook, styles, "${sheetNamePrefix}Saving", flattenedSavingRows, savingColumns)
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
                if (nextItem != null && item.groupId != nextItem.groupId) {
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
    private fun uniqueSheetName(workbook: Workbook, rawNodeId: String): String {
        val base = rawNodeId.replace(Regex("[:\\\\/?*\\[\\]]"), "_")
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
        val savingRow: SavingRow?,
    )

    private class NodeRow(
        val node: NodeExecutionMetrics,
        val prediction: ExecutionPrediction?,
    ) {
        private val actualDurationMs: Long?
            get() = node.postExecutionMetrics.durationMs.takeIf { it > 0L }

        fun predictionErrorMs(): Long? {
            val prediction = prediction ?: return null
            val durationMs = actualDurationMs ?: return null
            return durationMs - prediction.predictedDurationMs
        }
    }

    private class SavingRow(
        val saving: SavingExecutionMetrics,
        val prediction: ExecutionPrediction?,
    ) {
        private val actualDurationMs: Long?
            get() = saving.postExecutionMetrics.durationMs.takeIf { it > 0L }

        fun predictionErrorMs(): Long? {
            val durationMs = actualDurationMs ?: return null
            val prediction = prediction ?: return null
            return durationMs - prediction.predictedDurationMs
        }
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

        fun styleFor(columnTitle: String, value: Any?): CellStyle? {
            if (value !is Number) {
                return null
            }
            return when {
                columnTitle.endsWith("Ms", ignoreCase = true) -> msStyle
                columnTitle.endsWith("Percent", ignoreCase = true) -> percentStyle
                else -> null
            }
        }
    }

    private companion object {
        private const val DIR_NAME = "metrics"
        private val FILE_NAME = "${Build.MODEL}_metrics.xlsx"
        private const val MAX_SHEET_NAME_LENGTH = 31

        private fun buildCaptureColumns(): List<Column<EnrichedCaptureRow>> = listOf(
            Column("captureIndex") { it.row.captureIndex },
            Column("ppSequenceId") { it.row.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.row.metrics.dsMode) },
            Column("dsExtraInfo") { it.row.metrics.dsExtraInfo },
            Column("resultImageFormat") { it.row.metrics.resultImageFormat },
            Column("resultImageWidth") { it.row.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.row.metrics.resultImageSize.height },
            Column("resultImageFileName") { it.row.metrics.resultImageFileName },
            Column("isTimeout") { it.row.metrics.draftSequenceMetrics?.isTimeout },
            Column("overheatLevel") { it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.overheatLevel },
            Column("thermalStatus") { it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalStatus },
            Column("thermalHeadroom") { it.row.nodeRows.firstOrNull()?.node?.preExecutionMetrics?.thermalSnapshot?.thermalHeadroom },
            Column("totalDurationMs") {
                val row = it.row
                val nodesDuration = row.nodeRows.sumOf { nodeRow -> nodeRow.node.postExecutionMetrics.durationMs }
                val savingDuration = row.savingRow?.saving?.postExecutionMetrics?.durationMs ?: 0L
                nodesDuration + savingDuration
            },
            Column("timeoutCount") { it.timeoutCount?.let { idx -> "#$idx" } }
        )

        private fun buildNodeColumns(): List<Column<Triple<Int, String, NodeRow>>> {
            val columns = mutableListOf<Column<Triple<Int, String, NodeRow>>>(
                Column("captureIndex") { it.first },
                Column("nodeId") { it.second },
                Column("outputImageWidth") { it.third.node.outputImageSize.width },
                Column("outputImageHeight") { it.third.node.outputImageSize.height },
                Column("inputImageWidth") { it.third.node.inputImageSize.width },
                Column("inputImageHeight") { it.third.node.inputImageSize.height },
                Column("budgetMs") { it.third.node.preExecutionMetrics.budgetMs },
                Column("isLowMemory") { it.third.node.preExecutionMetrics.memorySnapshot.isLowMemory },
                Column("ramAvailablePercent") { it.third.node.preExecutionMetrics.memorySnapshot.ramAvailablePercent },
                Column("javaHeapUsedPercent") { it.third.node.preExecutionMetrics.memorySnapshot.javaHeapUsedPercent },
                Column("nativeHeapAllocatedPercent") { it.third.node.preExecutionMetrics.memorySnapshot.nativeHeapAllocatedPercent },
                Column("overheatLevel") { it.third.node.preExecutionMetrics.thermalSnapshot.overheatLevel },
                Column("thermalStatus") { it.third.node.preExecutionMetrics.thermalSnapshot.thermalStatus },
                Column("thermalHeadroom") { it.third.node.preExecutionMetrics.thermalSnapshot.thermalHeadroom },
                Column("storageUsedPercent") { it.third.node.preExecutionMetrics.storageSnapshot.storageUsedPercent },
                Column("cpuTimeMs") { it.third.node.postExecutionMetrics.cpuProcessingSnapshot?.cpuTimeMs },
                Column("wallTimeMs") { it.third.node.postExecutionMetrics.cpuProcessingSnapshot?.wallTimeMs },
                Column("runQueueWaitMs") { it.third.node.postExecutionMetrics.cpuProcessingSnapshot?.runqueueWaitMs },
                Column("admit") { it.third.prediction?.admit },
                Column("predictedDurationMs") { it.third.prediction?.predictedDurationMs },
                Column("predictedUpperBoundMs") { it.third.prediction?.predictedUpperBoundMs },
                Column("durationMs") { it.third.node.postExecutionMetrics.durationMs },
                Column("predictionErrorMs") { it.third.predictionErrorMs() },
            )
            // ... (rest of the function is the same)
            return columns
        }

        private fun buildSavingColumns(): List<Column<Pair<Int, SavingRow>>> {
            val columns = mutableListOf<Column<Pair<Int, SavingRow>>>(
                Column("captureIndex") { it.first },
                Column("isPendingRequest") { it.second.saving.isPendingRequest },
                Column("resultImageWidth") { it.second.saving.resultImageSize.width },
                Column("resultImageHeight") { it.second.saving.resultImageSize.height },
                Column("resultImageFormat") { it.second.saving.resultImageFormat },
                Column("budgetMs") { it.second.saving.preExecutionMetrics.budgetMs },
                Column("isLowMemory") { it.second.saving.preExecutionMetrics.memorySnapshot.isLowMemory },
                Column("ramAvailablePercent") { it.second.saving.preExecutionMetrics.memorySnapshot.ramAvailablePercent },
                Column("javaHeapUsedPercent") { it.second.saving.preExecutionMetrics.memorySnapshot.javaHeapUsedPercent },
                Column("nativeHeapAllocatedPercent") { it.second.saving.preExecutionMetrics.memorySnapshot.nativeHeapAllocatedPercent },
                Column("overheatLevel") { it.second.saving.preExecutionMetrics.thermalSnapshot.overheatLevel },
                Column("thermalStatus") { it.second.saving.preExecutionMetrics.thermalSnapshot.thermalStatus },
                Column("thermalHeadroom") { it.second.saving.preExecutionMetrics.thermalSnapshot.thermalHeadroom },
                Column("storageUsedPercent") { it.second.saving.preExecutionMetrics.storageSnapshot.storageUsedPercent },
                Column("cpuTimeMs") { it.second.saving.postExecutionMetrics.cpuProcessingSnapshot?.cpuTimeMs },
                Column("wallTimeMs") { it.second.saving.postExecutionMetrics.cpuProcessingSnapshot?.wallTimeMs },
                Column("runQueueWaitMs") { it.second.saving.postExecutionMetrics.cpuProcessingSnapshot?.runqueueWaitMs },
                Column("durationMs") { it.second.saving.postExecutionMetrics.durationMs },
                Column("predictionErrorMs") { it.second.predictionErrorMs() },
            )
            // ... (rest of the function is the same)
            return columns
        }
    }
}
