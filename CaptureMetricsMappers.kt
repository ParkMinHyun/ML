package com.samsung.android.camera.core2.ml

import android.util.Size
import com.samsung.android.camera.core2.node.NodeId
import org.json.JSONObject

private const val NODE_PARAMS_KEY_TYPE = "type"
private const val NODE_PARAMS_KEY_ENCODING_FORMAT = "encodingFormat"
private const val NODE_PARAMS_KEY_OUTPUT_IMAGE_WIDTH = "outputImageWidth"
private const val NODE_PARAMS_KEY_OUTPUT_IMAGE_HEIGHT = "outputImageHeight"
private const val NODE_PARAMS_TYPE_NONE = "none"
private const val NODE_PARAMS_TYPE_ENCODING = "encoding"
private const val NODE_PARAMS_TYPE_DUAL_BOKEH = "dualBokeh"

fun CaptureMetrics.toCaptureEntity(): CaptureMetricsEntity {
    return CaptureMetricsEntity(
        ppSequenceId = ppSequenceId,
        dsMode = dsMode,
        dsExtraInfo = dsExtraInfo,
        resultImageWidth = resultImageSize.width,
        resultImageHeight = resultImageSize.height,
        resultImageFormat = resultImageFormat,
        resultImageFileName = resultImageFileName,
        timeoutTimestampMs = timeoutTimestampMs,
    )
}

fun DraftSequenceMetrics.toEntity(captureMetricsId: Int): DraftSequenceMetricsEntity {
    return DraftSequenceMetricsEntity(
        captureMetricsId = captureMetricsId,
        isTimeout = isTimeout,
    )
}

fun List<NodeExecutionMetrics>.toNodeExecutionMetricsEntities(
    captureMetricsId: Int,
): List<NodeExecutionMetricsEntity> {
    return mapIndexed { index, metrics ->
        metrics.toEntity(
            captureMetricsId = captureMetricsId,
            order = index,
        )
    }
}

fun NodeExecutionMetrics.toEntity(
    captureMetricsId: Int,
    order: Int,
): NodeExecutionMetricsEntity {
    val preExecution = preExecutionMetrics
    val postExecution = postExecutionMetrics
    return NodeExecutionMetricsEntity(
        captureMetricsId = captureMetricsId,
        order = order,
        nodeId = nodeId.name,
        nodeParams = nodeParams.toJson(),
        budgetMs = preExecution.budgetMs,
        inputImageWidth = inputImageSize.width,
        inputImageHeight = inputImageSize.height,
        memorySnapshot = preExecution.memorySnapshot.toEntity(),
        thermalSnapshot = preExecution.thermalSnapshot.toEntity(),
        storageSnapshot = preExecution.storageSnapshot.toEntity(),
        gcSnapshot = postExecution.gcSnapshot?.toEntity(),
        cpuProcessingSnapshot = postExecution.cpuProcessingSnapshot?.toEntity(),
        durationMs = postExecution.durationMs,
    )
}

/** Node predictions: keyed by [target] = NODE and the node's order. */
fun List<ExecutionPrediction>.toNodePredictionEntities(
    captureMetricsId: Int,
): List<ExecutionPredictionEntity> {
    return mapIndexed { index, prediction ->
        prediction.toEntity(
            captureMetricsId = captureMetricsId,
            target = ExecutionPredictionEntity.PredictionTarget.NODE,
            order = index,
        )
    }
}

/** Saving prediction: keyed by [target] = SAVING and [SAVING_ORDER]. */
fun ExecutionPrediction.toSavingPredictionEntity(
    captureMetricsId: Int,
): ExecutionPredictionEntity {
    return toEntity(
        captureMetricsId = captureMetricsId,
        target = ExecutionPredictionEntity.PredictionTarget.SAVING,
        order = ExecutionPredictionEntity.SAVING_ORDER,
    )
}

fun ExecutionPrediction.toEntity(
    captureMetricsId: Int,
    target: ExecutionPredictionEntity.PredictionTarget,
    order: Int,
): ExecutionPredictionEntity {
    return ExecutionPredictionEntity(
        captureMetricsId = captureMetricsId,
        target = target.name,
        order = order,
        predictedDurationMs = predictedDurationMs,
        predictedUpperBoundMs = predictedUpperBoundMs,
        admit = admit,
    )
}

fun GcSnapshot.toEntity(): GcSnapshotEntity {
    return GcSnapshotEntity(
        blockingGcCount = blockingGcCount,
        blockingGcTimeMs = blockingGcTimeMs,
    )
}

fun CpuProcessingSnapshot.toEntity(): CpuProcessingSnapshotEntity {
    return CpuProcessingSnapshotEntity(
        cpuTimeMs = cpuTimeMs,
        wallTimeMs = wallTimeMs,
        cpuUtilizationRatio = cpuUtilizationRatio,
        runqueueWaitMs = runqueueWaitMs,
        nonvoluntaryCtxSwitches = nonvoluntaryCtxSwitches,
    )
}

fun MemorySnapshot.toEntity(): MemorySnapshotEntity {
    return MemorySnapshotEntity(
        isLowMemory = isLowMemory,
        ramAvailablePercent = ramAvailablePercent,
        javaHeapUsedPercent = javaHeapUsedPercent,
        nativeHeapAllocatedPercent = nativeHeapAllocatedPercent,
    )
}

fun ThermalSnapshot.toEntity(): ThermalSnapshotEntity {
    return ThermalSnapshotEntity(
        overheatLevel = overheatLevel,
        thermalStatus = thermalStatus,
        thermalHeadroom = thermalHeadroom,
    )
}

fun StorageSnapshot.toEntity(): StorageSnapshotEntity {
    return StorageSnapshotEntity(
        storageUsedPercent = storageUsedPercent,
    )
}

fun SavingExecutionMetrics.toEntity(
    captureMetricsId: Int,
): SavingExecutionMetricsEntity {
    val preExecution = preExecutionMetrics
    val postExecution = postExecutionMetrics
    return SavingExecutionMetricsEntity(
        captureMetricsId = captureMetricsId,
        budgetMs = preExecution.budgetMs,
        isPendingRequest = isPendingRequest,
        resultImageWidth = resultImageSize.width,
        resultImageHeight = resultImageSize.height,
        resultImageFormat = resultImageFormat,
        memorySnapshot = preExecution.memorySnapshot.toEntity(),
        thermalSnapshot = preExecution.thermalSnapshot.toEntity(),
        storageSnapshot = preExecution.storageSnapshot.toEntity(),
        gcSnapshot = postExecution.gcSnapshot?.toEntity(),
        cpuProcessingSnapshot = postExecution.cpuProcessingSnapshot?.toEntity(),
        durationMs = postExecution.durationMs,
    )
}

fun CaptureMetricsAggregate.toModel(): CaptureMetrics {
    val nodePredictions = executionPredictions
        .filter { it.target == ExecutionPredictionEntity.PredictionTarget.NODE.name }
        .sortedBy { it.order }
    val savingPrediction = executionPredictions
        .filter { it.target == ExecutionPredictionEntity.PredictionTarget.SAVING.name }
        .minByOrNull { it.executionPredictionId }

    return CaptureMetrics(
        ppSequenceId = capture.ppSequenceId,
        dsMode = capture.dsMode,
        dsExtraInfo = capture.dsExtraInfo,
        resultImageSize = Size(capture.resultImageWidth, capture.resultImageHeight),
        resultImageFormat = capture.resultImageFormat,
        resultImageFileName = capture.resultImageFileName,
        timeoutTimestampMs = capture.timeoutTimestampMs,
        draftSequenceMetrics = draftSequenceMetrics?.toModel(
            nodeExecutionMetricsList = nodeExecutionMetrics
                .sortedBy { it.order }
                .map { it.toModel() }
                .toMutableList(),
            nodeExecutionPredictionList = nodePredictions
                .map { it.toModel() }
                .toMutableList(),
            savingExecutionMetrics = savingExecutionMetrics?.toModel(),
            savingExecutionPrediction = savingPrediction?.toModel(),
        ),
    )
}

fun DraftSequenceMetricsEntity.toModel(
    nodeExecutionMetricsList: MutableList<NodeExecutionMetrics>,
    nodeExecutionPredictionList: MutableList<ExecutionPrediction>,
    savingExecutionMetrics: SavingExecutionMetrics?,
    savingExecutionPrediction: ExecutionPrediction? = null,
): DraftSequenceMetrics {
    return DraftSequenceMetrics(
        nodeExecutionMetricsList = nodeExecutionMetricsList,
        nodeExecutionPredictionList = nodeExecutionPredictionList,
        savingExecutionMetrics = savingExecutionMetrics,
        savingExecutionPrediction = savingExecutionPrediction,
        isTimeout = isTimeout,
    )
}

fun SavingExecutionMetricsEntity.toModel(): SavingExecutionMetrics {
    return SavingExecutionMetrics(
        isPendingRequest = isPendingRequest,
        resultImageSize = Size(resultImageWidth, resultImageHeight),
        resultImageFormat = resultImageFormat,
        preExecutionMetrics = PreExecutionMetrics(
            budgetMs = budgetMs,
            memorySnapshot = memorySnapshot.toModel(),
            thermalSnapshot = thermalSnapshot.toModel(),
            storageSnapshot = storageSnapshot.toModel(),
        ),
        postExecutionMetrics = PostExecutionMetrics(
            gcSnapshot = gcSnapshot?.toModel(),
            cpuProcessingSnapshot = cpuProcessingSnapshot?.toModel(),
            durationMs = durationMs,
        ),
    )
}

fun NodeExecutionMetricsEntity.toModel(): NodeExecutionMetrics {
    return NodeExecutionMetrics(
        nodeId = nodeId.toNodeId(),
        nodeParams = nodeParams.toNodeParams(),
        inputImageSize = Size(inputImageWidth, inputImageHeight),
        preExecutionMetrics = toPreExecutionMetrics(),
        postExecutionMetrics = toPostExecutionMetrics(),
    )
}

fun ExecutionPredictionEntity.toModel(): ExecutionPrediction {
    return ExecutionPrediction(
        predictedDurationMs = predictedDurationMs,
        predictedUpperBoundMs = predictedUpperBoundMs,
        admit = admit,
    )
}

private fun NodeExecutionMetricsEntity.toPreExecutionMetrics(): PreExecutionMetrics {
    return PreExecutionMetrics(
        budgetMs = budgetMs,
        memorySnapshot = memorySnapshot.toModel(),
        thermalSnapshot = thermalSnapshot.toModel(),
        storageSnapshot = storageSnapshot.toModel(),
    )
}

private fun NodeExecutionMetricsEntity.toPostExecutionMetrics(): PostExecutionMetrics {
    return PostExecutionMetrics(
        gcSnapshot = gcSnapshot?.toModel(),
        cpuProcessingSnapshot = cpuProcessingSnapshot?.toModel(),
        durationMs = durationMs,
    )
}

fun GcSnapshotEntity.toModel(): GcSnapshot {
    return GcSnapshot(
        blockingGcCount = blockingGcCount,
        blockingGcTimeMs = blockingGcTimeMs,
    )
}

fun CpuProcessingSnapshotEntity.toModel(): CpuProcessingSnapshot {
    return CpuProcessingSnapshot(
        cpuTimeMs = cpuTimeMs,
        wallTimeMs = wallTimeMs,
        cpuUtilizationRatio = cpuUtilizationRatio,
        runqueueWaitMs = runqueueWaitMs,
        nonvoluntaryCtxSwitches = nonvoluntaryCtxSwitches,
    )
}

fun MemorySnapshotEntity.toModel(): MemorySnapshot {
    return MemorySnapshot(
        isLowMemory = isLowMemory,
        ramAvailablePercent = ramAvailablePercent,
        javaHeapUsedPercent = javaHeapUsedPercent,
        nativeHeapAllocatedPercent = nativeHeapAllocatedPercent,
    )
}

fun ThermalSnapshotEntity.toModel(): ThermalSnapshot {
    return ThermalSnapshot(
        overheatLevel = overheatLevel,
        thermalStatus = thermalStatus,
        thermalHeadroom = thermalHeadroom,
    )
}

fun StorageSnapshotEntity.toModel(): StorageSnapshot {
    return StorageSnapshot(
        storageUsedPercent = storageUsedPercent,
    )
}

fun NodeParams.toJson(): String {
    return when (this) {
        NodeParams.None, NodeParams.Watermark, NodeParams.Filter -> JSONObject()
            .put(NODE_PARAMS_KEY_TYPE, NODE_PARAMS_TYPE_NONE)
            .toString()
        is NodeParams.DualBokeh -> JSONObject()
            .put(NODE_PARAMS_KEY_TYPE, NODE_PARAMS_TYPE_DUAL_BOKEH)
            .put(NODE_PARAMS_KEY_OUTPUT_IMAGE_WIDTH, outputImageSize.width)
            .put(NODE_PARAMS_KEY_OUTPUT_IMAGE_HEIGHT, outputImageSize.height)
            .toString()
        is NodeParams.Encoding -> JSONObject()
            .put(NODE_PARAMS_KEY_TYPE, NODE_PARAMS_TYPE_ENCODING)
            .put(NODE_PARAMS_KEY_ENCODING_FORMAT, encodingFormat)
            .toString()
    }
}

fun String.toNodeParams(): NodeParams {
    if (isBlank()) {
        return NodeParams.None
    }
    val json = JSONObject(this)
    return when (json.optString(NODE_PARAMS_KEY_TYPE, NODE_PARAMS_TYPE_NONE)) {
        NODE_PARAMS_TYPE_DUAL_BOKEH -> NodeParams.DualBokeh(
            outputImageSize = Size(
                json.optInt(NODE_PARAMS_KEY_OUTPUT_IMAGE_WIDTH, 0),
                json.optInt(NODE_PARAMS_KEY_OUTPUT_IMAGE_HEIGHT, 0),
            ),
        )
        NODE_PARAMS_TYPE_ENCODING -> NodeParams.Encoding(
            encodingFormat = json.optInt(NODE_PARAMS_KEY_ENCODING_FORMAT, 0),
        )
        else -> NodeParams.None
    }
}

/** Maps a persisted node-id name back to its [NodeId]; unknown names fall back to [NodeId.NODE_DUMMY]. */
fun String.toNodeId(): NodeId {
    return runCatching { NodeId.valueOf(this) }.getOrDefault(NodeId.NODE_DUMMY)
}
