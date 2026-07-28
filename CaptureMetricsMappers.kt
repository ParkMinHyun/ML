package com.samsung.android.camera.core2.ml

import android.util.Size

/**
 * Runtime pacing decision into the [CaptureMetrics] observability model - the one conversion here that reads a live
 * type rather than a stored one, so the pacer's runtime records never reach the store unprojected.
 */
fun CaptureAvailablePacingDecision.toCaptureAvailablePacingMetrics(): CaptureAvailablePacingMetrics {
    return CaptureAvailablePacingMetrics(
        decisionUptimeMs = decisionUptimeMs,
        appliedDelayMs = delayMs,
        levelDeficitMs = levelDeficitMs,
        backlogDeficitMs = backlogDeficitMs,
        backlogMs = backlogMs,
        queuedDraftCount = queuedDraftCount,
        queuedPredictedWorkMs = queuedPredictedWorkMs,
        shutterElapsedMs = shutterElapsedMs,
        maxDraftSequenceDurationMs = maxDraftSequenceDurationMs,
        draftSequenceBudgetMs = prediction.draftSequenceBudgetMs,
        draftSequencePredictedDurationMs = prediction.draftSequencePredictedDurationMs,
        draftSequenceOverheadDurationMs = prediction.draftSequenceOverheadDurationMs,
        draftSequencePacingDurationMs = prediction.draftSequencePacingDurationMs,
        draftSequenceKey = prediction.draftSequenceKey,
    )
}

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
        shotToShotTimeMs = shotToShotTimeMs,
    )
}

fun DraftSequenceMetrics.toEntity(captureMetricsId: Int): DraftSequenceMetricsEntity {
    return DraftSequenceMetricsEntity(
        captureMetricsId = captureMetricsId,
        isPendingRequest = isPendingRequest,
        hasWatchdogTimeout = hasWatchdogTimeout,
        isTimeout = isTimeout,
        draftStartUptimeMs = draftStartUptimeMs,
        draftEndUptimeMs = draftEndUptimeMs,
        pacerSessionId = pacerSessionId,
        pacingSnapshot = captureAvailablePacing?.toEntity(),
    )
}

fun CaptureAvailablePacingMetrics.toEntity(): CaptureAvailablePacingMetricsEntity {
    return CaptureAvailablePacingMetricsEntity(
        decisionUptimeMs = decisionUptimeMs,
        appliedDelayMs = appliedDelayMs,
        levelDeficitMs = levelDeficitMs,
        backlogDeficitMs = backlogDeficitMs,
        backlogMs = backlogMs,
        queuedDraftCount = queuedDraftCount,
        queuedPredictedWorkMs = queuedPredictedWorkMs,
        shutterElapsedMs = shutterElapsedMs,
        maxDraftSequenceDurationMs = maxDraftSequenceDurationMs,
        draftSequenceBudgetMs = draftSequenceBudgetMs,
        draftSequencePredictedDurationMs = draftSequencePredictedDurationMs,
        draftSequenceOverheadDurationMs = draftSequenceOverheadDurationMs,
        draftSequencePacingDurationMs = draftSequencePacingDurationMs,
        draftSequenceKey = draftSequenceKey,
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
        nodeName = nodeName,
        workloadKey = workloadKey,
        budgetMs = preExecution.budgetMs,
        memorySnapshot = preExecution.memorySnapshot.toEntity(),
        thermalSnapshot = preExecution.thermalSnapshot.toEntity(),
        storageSnapshot = preExecution.storageSnapshot.toEntity(),
        gcSnapshot = postExecution.gcSnapshot?.toEntity(),
        cpuProcessingSnapshot = postExecution.cpuProcessingSnapshot?.toEntity(),
        durationMs = postExecution.durationMs,
        watchdogTimeoutMs = watchdogTimeoutMs,
        watchdogTimedOut = watchdogTimedOut,
        startUptimeMs = startUptimeMs,
    )
}

fun List<ExecutionPrediction>.toNodePredictionEntities(
    captureMetricsId: Int,
): List<ExecutionPredictionEntity> {
    return mapIndexed { index, prediction ->
        prediction.toEntity(
            captureMetricsId = captureMetricsId,
            order = index,
        )
    }
}

fun ExecutionPrediction.toEntity(
    captureMetricsId: Int,
    order: Int,
): ExecutionPredictionEntity {
    return ExecutionPredictionEntity(
        captureMetricsId = captureMetricsId,
        order = order,
        workloadSequenceKey = workloadSequenceKey,
        sequencePredictedDurationMs = sequencePredictedDurationMs,
        sequencePredictedUpperBoundMs = sequencePredictedUpperBoundMs,
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

fun CaptureMetricsAggregate.toModel(): CaptureMetrics {
    val nodePredictions = executionPredictions
        .sortedBy { it.order }
    return CaptureMetrics(
        ppSequenceId = capture.ppSequenceId,
        dsMode = capture.dsMode,
        dsExtraInfo = capture.dsExtraInfo,
        resultImageSize = Size(capture.resultImageWidth, capture.resultImageHeight),
        resultImageFormat = capture.resultImageFormat,
        resultImageFileName = capture.resultImageFileName,
        timeoutTimestampMs = capture.timeoutTimestampMs,
        shotToShotTimeMs = capture.shotToShotTimeMs,
        draftSequenceMetrics = draftSequenceMetrics?.toModel(
            nodeExecutionMetricsList = nodeExecutionMetrics
                .sortedBy { it.order }
                .map { it.toModel() }
                .toMutableList(),
            nodeExecutionPredictionList = nodePredictions
                .map { it.toModel() }
                .toMutableList(),
        ),
    )
}

fun DraftSequenceMetricsEntity.toModel(
    nodeExecutionMetricsList: MutableList<NodeExecutionMetrics>,
    nodeExecutionPredictionList: MutableList<ExecutionPrediction>,
): DraftSequenceMetrics {
    return DraftSequenceMetrics(
        nodeExecutionMetricsList = nodeExecutionMetricsList,
        nodeExecutionPredictionList = nodeExecutionPredictionList,
        isPendingRequest = isPendingRequest,
        hasWatchdogTimeout = hasWatchdogTimeout,
        isTimeout = isTimeout,
        draftStartUptimeMs = draftStartUptimeMs,
        draftEndUptimeMs = draftEndUptimeMs,
        pacerSessionId = pacerSessionId,
        captureAvailablePacing = pacingSnapshot?.toModel(),
    )
}

fun CaptureAvailablePacingMetricsEntity.toModel(): CaptureAvailablePacingMetrics {
    return CaptureAvailablePacingMetrics(
        decisionUptimeMs = decisionUptimeMs,
        appliedDelayMs = appliedDelayMs,
        levelDeficitMs = levelDeficitMs,
        backlogDeficitMs = backlogDeficitMs,
        backlogMs = backlogMs,
        queuedDraftCount = queuedDraftCount,
        queuedPredictedWorkMs = queuedPredictedWorkMs,
        shutterElapsedMs = shutterElapsedMs,
        maxDraftSequenceDurationMs = maxDraftSequenceDurationMs,
        draftSequenceBudgetMs = draftSequenceBudgetMs,
        draftSequencePredictedDurationMs = draftSequencePredictedDurationMs,
        draftSequenceOverheadDurationMs = draftSequenceOverheadDurationMs,
        draftSequencePacingDurationMs = draftSequencePacingDurationMs,
        draftSequenceKey = draftSequenceKey,
    )
}

fun NodeExecutionMetricsEntity.toModel(): NodeExecutionMetrics {
    return NodeExecutionMetrics(
        nodeName = nodeName,
        preExecutionMetrics = toPreExecutionMetrics(),
        postExecutionMetrics = toPostExecutionMetrics(),
        workloadKey = workloadKey,
        watchdogTimeoutMs = watchdogTimeoutMs,
        watchdogTimedOut = watchdogTimedOut,
        startUptimeMs = startUptimeMs,
    )
}

fun ExecutionPredictionEntity.toModel(): ExecutionPrediction {
    return ExecutionPrediction(
        admit = admit,
        sequencePredictedDurationMs = sequencePredictedDurationMs,
        sequencePredictedUpperBoundMs = sequencePredictedUpperBoundMs,
        workloadSequenceKey = workloadSequenceKey,
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
