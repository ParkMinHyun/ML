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
    var isTimeout: Boolean? = false,
)

data class NodeExecutionMetrics(
    val nodeName: String,
    val preExecutionMetrics: PreExecutionMetrics,
    var postExecutionMetrics: PostExecutionMetrics = PostExecutionMetrics(),
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
    val nodePredictedDurationMs: Long,
    val nodePredictedUpperBoundMs: Long,
    val sequencePredictedDurationMs: Long,
    val sequencePredictedUpperBoundMs: Long,
    val admit: Boolean = false,
)
