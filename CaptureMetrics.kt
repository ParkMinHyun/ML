package com.samsung.android.camera.core2.ml

import android.util.Size
import com.samsung.android.camera.core2.node.NodeId

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
    var savingExecutionPrediction: ExecutionPrediction? = null,
    var isTimeout: Boolean? = false,
)

data class NodeExecutionMetrics(
    val nodeId: NodeId,
    val inputImageSize: Size,
    val outputImageSize: Size,
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
    val admit: Boolean = false,
    val predictedDurationMs: Long,
    val predictedUpperBoundMs: Long,
)
