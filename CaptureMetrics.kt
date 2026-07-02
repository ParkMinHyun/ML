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
    var isPendingRequest: Boolean? = null,
    var hasWatchdogTimeout: Boolean? = false,
    var isTimeout: Boolean? = false,
)

data class NodeExecutionMetrics(
    val nodeName: String,
    val workloadKey: String? = null,
    val preExecutionMetrics: PreExecutionMetrics,
    var postExecutionMetrics: PostExecutionMetrics = PostExecutionMetrics(),
    var watchdogTimeoutMs: Long? = null,
    var watchdogTimedOut: Boolean? = null,
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
    val sequencePredictedDurationMs: Long,
    val sequencePredictedUpperBoundMs: Long,
    val admit: Boolean = false,
    /**
     * Decision-time planned workload-key suffix in replay-string format. This is the predictor input for this
     * decision: planned workloads that were later skipped or never profiled appear here and nowhere else, so
     * offline replay must read the sequence from this field instead of reconstructing it from executed-node rows.
     */
    val workloadSequenceKey: String? = null,
    /** "MULTI_FRAME" when the deciding workload is queue-pressure gated (e.g. Bokeh); null otherwise. */
    val queuePressureGroup: String? = null,
)
