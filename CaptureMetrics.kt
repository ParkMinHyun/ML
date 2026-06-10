package com.samsung.android.camera.core2.ml

import android.util.Size

data class CaptureMetrics @JvmOverloads constructor(
    val ppSequenceId: Int,
    val dsMode: Int,
    val dsExtraInfo: Int,
    val resultImageFormat: Int,
    val resultImageSize: Size,
    val resultImageFileName: String,
    var draftSequenceMetrics: DraftSequenceMetrics? = null,
)

data class DraftSequenceMetrics(
    val nodeExecutionMetricsList: MutableList<NodeExecutionMetrics> = mutableListOf(),
    val executionPredictionList: MutableList<ExecutionPrediction> = mutableListOf(),
    var savingExecutionMetrics: SavingExecutionMetrics? = null,
    var savingExecutionPrediction: ExecutionPrediction? = null,
    val isTimeout: Boolean? = false,
)

data class NodeExecutionMetrics(
    val nodeId: String,
    val nodeParams: NodeParams = NodeParams.None,
    val inputImageSize: Size,
    val preExecutionMetrics: PreExecutionMetrics,
    val postExecutionMetrics: PostExecutionMetrics,
)

data class SavingExecutionMetrics(
    val isPendingRequest: Boolean,
    val resultImageSize: Size,
    val resultImageFormat: Int,
    val preExecutionMetrics: PreExecutionMetrics,
    val postExecutionMetrics: PostExecutionMetrics,
)

sealed interface NodeParams {
    data object None : NodeParams

    data class DualBokeh(val outputImageSize: Size) : NodeParams

    data class Encoding(val encodingFormat: Int) : NodeParams
}

data class PreExecutionMetrics(
    val budgetMs: Long,
    val memorySnapshot: MemorySnapshot,
    val powerThermalSnapshot: PowerThermalSnapshot,
    val storageSnapshot: StorageSnapshot,
)

data class PostExecutionMetrics(
    var gcSnapshot: GcSnapshot? = null,
    var cpuProcessingSnapshot: CpuProcessingSnapshot? = null,
    var durationMs: Long = 0L,
)

data class ExecutionPrediction(
    val predictedDurationMs: Long,
    val predictedUpperBoundMs: Long,
    val confidence: Float,
    val reason: String,
)