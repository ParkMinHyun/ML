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

            // Each capture gets a 1-based index used as the join key across all sheets.
            val captures = metricsList.mapIndexed { index, metrics -> CaptureRow(index + 1, metrics) }
            val nodeRows = captures.flatMap { capture ->
                capture.metrics.draftSequenceMetrics?.let { draftMetrics ->
                    draftMetrics.nodeExecutionMetricsList.mapIndexed { order, node ->
                        NodeRow(
                            captureIndex = capture.captureIndex,
                            order = order,
                            node = node,
                            prediction = draftMetrics.executionPredictionList.getOrNull(order),
                        )
                    }
                }
                    .orEmpty()
            }

            val savingRows = captures.mapNotNull { capture ->
                capture.metrics.draftSequenceMetrics?.savingExecutionMetrics?.let { saving ->
                    SavingRow(
                        captureIndex = capture.captureIndex,
                        saving = saving,
                        prediction = capture.metrics.draftSequenceMetrics?.savingExecutionPrediction,
                    )
                }
            }

            writeSheet(workbook, styles, "Capture", captures, CAPTURE_COLUMNS)

            nodeRows
                .groupBy { it.node.nodeId }
                .toSortedMap()
                .forEach { (nodeId, rows) ->
                    val sheetName = uniqueSheetName(workbook, nodeId)
                    writeSheet(workbook, styles, sheetName, rows, NODE_COLUMNS)
                }

            if (savingRows.isNotEmpty()) {
                writeSheet(workbook, styles, "Saving", savingRows, SAVING_COLUMNS)
            }

            FileOutputStream(outputFile).use { workbook.write(it) }
        }

        return outputFile
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
            val row = sheet.createRow(rowIndex + 1)
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
    private fun uniqueSheetName(workbook: Workbook, rawNodeId: String): String {
        val base = rawNodeId.substringAfterLast('.')
            .replace(Regex("[:\\\\/?*\\[\\]]"), "_")
            .take(MAX_SHEET_NAME_LENGTH)
            .ifBlank { "node" }

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
    )

    private class NodeRow(
        val captureIndex: Int,
        val order: Int,
        val node: NodeExecutionMetrics,
        val prediction: ExecutionPrediction?,
    )

    private class SavingRow(
        val captureIndex: Int,
        val saving: SavingExecutionMetrics,
        val prediction: ExecutionPrediction?,
    )

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

        private val CAPTURE_COLUMNS: List<Column<CaptureRow>> = listOf(
            Column("captureIndex") { it.captureIndex },
            Column("ppSequenceId") { it.metrics.ppSequenceId },
            Column("dsMode") { DynamicShotMode.getDsModeName(it.metrics.dsMode) },
            Column("dsExtraInfo") { it.metrics.dsExtraInfo },
            Column("resultImageFormat") { it.metrics.resultImageFormat },
            Column("resultImageWidth") { it.metrics.resultImageSize.width },
            Column("resultImageHeight") { it.metrics.resultImageSize.height },
            Column("resultImageFileName") { it.metrics.resultImageFileName },
            Column("isTimeout") { it.metrics.draftSequenceMetrics?.isTimeout },
            Column("nodeCount") { it.metrics.draftSequenceMetrics?.nodeExecutionMetricsList?.size ?: 0 },
        )

        private val NODE_COLUMNS: List<Column<NodeRow>> = listOf(
            Column("captureIndex") { it.captureIndex },
            Column("order") { it.order },
            Column("nodeId") { it.node.nodeId },
            Column("nodeParamsType") { it.node.nodeParams.typeName() },
            Column("encodingFormat") { (it.node.nodeParams as? NodeParams.Encoding)?.encodingFormat },
            Column("outputImageWidth") { (it.node.nodeParams as? NodeParams.DualBokeh)?.outputImageSize?.width },
            Column("outputImageHeight") { (it.node.nodeParams as? NodeParams.DualBokeh)?.outputImageSize?.height },
            Column("inputImageWidth") { it.node.inputImageSize.width },
            Column("inputImageHeight") { it.node.inputImageSize.height },

            // pre-execution metrics
            Column("budgetMs") { it.node.preExecutionMetrics.budgetMs },

            // pre-execution memory snapshot
            Column("isLowMemory") { it.node.preExecutionMetrics.memorySnapshot.isLowMemory },
            Column("ramAvailablePercent") { it.node.preExecutionMetrics.memorySnapshot.ramAvailablePercent },
            Column("javaHeapUsedPercent") { it.node.preExecutionMetrics.memorySnapshot.javaHeapUsedPercent },
            Column("nativeHeapAllocatedPercent") { it.node.preExecutionMetrics.memorySnapshot.nativeHeapAllocatedPercent },

            // pre-execution power / thermal snapshot
            Column("isPowerSaveMode") { it.node.preExecutionMetrics.powerThermalSnapshot.isPowerSaveMode },
            Column("isCharging") { it.node.preExecutionMetrics.powerThermalSnapshot.isCharging },
            Column("overheatLevel") { it.node.preExecutionMetrics.powerThermalSnapshot.overheatLevel },
            Column("thermalStatus") { it.node.preExecutionMetrics.powerThermalSnapshot.thermalStatus },
            Column("thermalHeadroom") { it.node.preExecutionMetrics.powerThermalSnapshot.thermalHeadroom },

            // pre-execution storage snapshot
            Column("storageUsedPercent") { it.node.preExecutionMetrics.storageSnapshot.storageUsedPercent },

            // prediction
            Column("predictedDurationMs") { it.prediction?.predictedDurationMs },
            Column("predictedUpperBoundMs") { it.prediction?.predictedUpperBoundMs },
            Column("confidence") { it.prediction?.confidence },
            Column("reason") { it.prediction?.reason },

            // post-execution metrics
            Column("blockingGcCount") { it.node.postExecutionMetrics.gcSnapshot?.blockingGcCount },
            Column("blockingGcTimeMs") { it.node.postExecutionMetrics.gcSnapshot?.blockingGcTimeMs },
            Column("cpuTimeMs") { it.node.postExecutionMetrics.cpuProcessingSnapshot?.cpuTimeMs },
            Column("cpuWallTimeMs") { it.node.postExecutionMetrics.cpuProcessingSnapshot?.wallTimeMs },
            Column("cpuUtilizationRatio") { it.node.postExecutionMetrics.cpuProcessingSnapshot?.cpuUtilizationRatio },
            Column("runqueueWaitMs") { it.node.postExecutionMetrics.cpuProcessingSnapshot?.runqueueWaitMs },
            Column("nonvoluntaryCtxSwitches") { it.node.postExecutionMetrics.cpuProcessingSnapshot?.nonvoluntaryCtxSwitches },
            Column("durationMs") { it.node.postExecutionMetrics.durationMs },
        )

        private val SAVING_COLUMNS: List<Column<SavingRow>> = listOf(
            Column("captureIndex") { it.captureIndex },

            Column("isPendingRequest") { it.saving.isPendingRequest },
            Column("resultImageWidth") { it.saving.resultImageSize.width },
            Column("resultImageHeight") { it.saving.resultImageSize.height },
            Column("resultImageFormat") { it.saving.resultImageFormat },

            // pre-execution metrics
            Column("budgetMs") { it.saving.preExecutionMetrics.budgetMs },

            // pre-execution memory snapshot
            Column("isLowMemory") { it.saving.preExecutionMetrics.memorySnapshot.isLowMemory },
            Column("ramAvailablePercent") { it.saving.preExecutionMetrics.memorySnapshot.ramAvailablePercent },
            Column("javaHeapUsedPercent") { it.saving.preExecutionMetrics.memorySnapshot.javaHeapUsedPercent },
            Column("nativeHeapAllocatedPercent") { it.saving.preExecutionMetrics.memorySnapshot.nativeHeapAllocatedPercent },

            // pre-execution power / thermal snapshot
            Column("isPowerSaveMode") { it.saving.preExecutionMetrics.powerThermalSnapshot.isPowerSaveMode },
            Column("isCharging") { it.saving.preExecutionMetrics.powerThermalSnapshot.isCharging },
            Column("overheatLevel") { it.saving.preExecutionMetrics.powerThermalSnapshot.overheatLevel },
            Column("thermalStatus") { it.saving.preExecutionMetrics.powerThermalSnapshot.thermalStatus },
            Column("thermalHeadroom") { it.saving.preExecutionMetrics.powerThermalSnapshot.thermalHeadroom },

            // pre-execution storage snapshot
            Column("storageUsedPercent") { it.saving.preExecutionMetrics.storageSnapshot.storageUsedPercent },

            // prediction
            Column("predictedDurationMs") { it.prediction?.predictedDurationMs },
            Column("predictedUpperBoundMs") { it.prediction?.predictedUpperBoundMs },
            Column("confidence") { it.prediction?.confidence },
            Column("reason") { it.prediction?.reason },

            // post-execution metrics
            Column("blockingGcCount") { it.saving.postExecutionMetrics.gcSnapshot?.blockingGcCount },
            Column("blockingGcTimeMs") { it.saving.postExecutionMetrics.gcSnapshot?.blockingGcTimeMs },
            Column("cpuTimeMs") { it.saving.postExecutionMetrics.cpuProcessingSnapshot?.cpuTimeMs },
            Column("cpuWallTimeMs") { it.saving.postExecutionMetrics.cpuProcessingSnapshot?.wallTimeMs },
            Column("cpuUtilizationRatio") { it.saving.postExecutionMetrics.cpuProcessingSnapshot?.cpuUtilizationRatio },
            Column("runqueueWaitMs") { it.saving.postExecutionMetrics.cpuProcessingSnapshot?.runqueueWaitMs },
            Column("nonvoluntaryCtxSwitches") { it.saving.postExecutionMetrics.cpuProcessingSnapshot?.nonvoluntaryCtxSwitches },
            Column("durationMs") { it.saving.postExecutionMetrics.durationMs },
        )

        private fun NodeParams.typeName(): String {
            return when (this) {
                NodeParams.None -> "none"
                is NodeParams.Encoding -> "encoding"
                is NodeParams.DualBokeh -> "dualBokeh"
            }
        }
    }
}
