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
    /** Uptime when the draft node chain was initialized; anchors offline interarrival/backlog replay. */
    var draftStartUptimeMs: Long? = null,
    /** Uptime when the draft sequence completed. */
    var draftEndUptimeMs: Long? = null,
    /** Burst-session ordinal from [CaptureAvailablePacer]; increments each time the drained pipeline clears it. */
    var pacerSessionId: Int? = null,
    /** Runtime captureAvailable pacing decision observed for this capture, if one was made. */
    var captureAvailablePacing: CaptureAvailablePacingMetrics? = null,
)

data class NodeExecutionMetrics(
    val nodeName: String,
    val workloadKey: String? = null,
    val preExecutionMetrics: PreExecutionMetrics,
    var postExecutionMetrics: PostExecutionMetrics = PostExecutionMetrics(),
    var watchdogTimeoutMs: Long? = null,
    var watchdogTimedOut: Boolean? = null,
    /** Uptime when this node's execution was profiled to start; gives replay the real per-node timeline. */
    var startUptimeMs: Long? = null,
)

/**
 * Runtime captureAvailable pacing decision persisted for offline replay: the applied callback delay plus every
 * backlog-clock input that produced it, so skip/pacing counterfactuals can re-run the policy on real state
 * instead of exporter-side proxies.
 */
data class CaptureAvailablePacingMetrics(
    val decisionUptimeMs: Long,
    val appliedDelayMs: Long,
    val levelDeficitMs: Long,
    val backlogDeficitMs: Long,
    val backlogMs: Long,
    val queuedDraftCount: Int,
    val queuedPredictedWorkMs: Double,
    val draftStartBudgetMs: Long,
    val mandatoryReserveUpperBoundMs: Double,
    val preferredDraftPathPredictedMs: Double,
    val preferredDraftPathUpperBoundMs: Double,
    val workloadSequenceKey: String,
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
    val sequencePredictedDurationMs: Double,
    val sequencePredictedUpperBoundMs: Double,
    val admit: Boolean = false,
    /**
     * Decision-time planned workload-key suffix in replay-string format. This is the predictor input for this
     * decision: planned workloads that were later skipped or never profiled appear here and nowhere else, so
     * offline replay must read the sequence from this field instead of reconstructing it from executed-node rows.
     */
    val workloadSequenceKey: String? = null,
)
