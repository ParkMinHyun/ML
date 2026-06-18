package com.samsung.android.camera.core2.ml

import android.util.Size
import com.samsung.android.camera.core2.node.NodeId
import org.json.JSONObject

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
        budgetMs = preExecution.budgetMs,
        inputImageWidth = inputImageSize.width,
        inputImageHeight = inputImageSize.height,
        outputImageWidth = outputImageSize.width,
        outputImageHeight = outputImageSize.height,
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
        inputImageSize = Size(inputImageWidth, inputImageHeight),
        outputImageSize = Size(outputImageWidth, outputImageHeight),
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

/** Maps a persisted node-id name back to its [NodeId]; unknown names fall back to [NodeId.NODE_DUMMY]. */
fun String.toNodeId(): NodeId {
    return runCatching { NodeId.valueOf(this) }.getOrDefault(NodeId.NODE_DUMMY)
}
