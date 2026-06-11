package com.samsung.android.camera.core2.ml

import android.util.Size

data class CaptureMetrics @JvmOverloads constructor(
    val ppSequenceId: Int,
    val dsMode: Int,
    val dsExtraInfo: Int,
    val resultImageSize: Size,
    val resultImageFormat: Int,
    val resultImageFileName: String,
    var timeoutTimestampMs: Long? = null,
    var draftSequenceMetrics: DraftSequenceMetrics? = null,
)

data class DraftSequenceMetrics @JvmOverloads constructor(
    val nodeExecutionMetricsList: MutableList<NodeExecutionMetrics> = mutableListOf(),
    val nodeExecutionPredictionList: MutableList<ExecutionPrediction> = mutableListOf(),
    var savingExecutionMetrics: SavingExecutionMetrics? = null,
    val savingExecutionPredictionList: MutableList<ExecutionPrediction> = mutableListOf(),
    var isTimeout: Boolean? = false,
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
    val thermalSnapshot: ThermalSnapshot,
    val storageSnapshot: StorageSnapshot,
)

data class PostExecutionMetrics(
    var gcSnapshot: GcSnapshot? = null,
    var cpuProcessingSnapshot: CpuProcessingSnapshot? = null,
    var durationMs: Long = 0L,
)

data class ExecutionPrediction @JvmOverloads constructor(
    val predictedDurationMs: Long,
    val predictedUpperBoundMs: Long,
    val confidence: Float,
    val reason: String,
    val predictorName: String = PREDICTOR_UNKNOWN,
    val executionOrder: Int? = null,
) {
    companion object {
        const val PREDICTOR_UNKNOWN = "unknown"
    }
}
