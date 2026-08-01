package com.samsung.android.camera.core2.ml

import android.os.SystemClock
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
    /** Gap from the previous capture's creation; the burst interarrival the pacer's delay actually produced. */
    var shotToShotTimeMs: Long? = null,
) {
    val creationTimestampMs: Long = SystemClock.uptimeMillis()
}

data class DraftSequenceMetrics @JvmOverloads constructor(
    val nodeExecutionMetricsList: MutableList<NodeExecutionMetrics> = mutableListOf(),
    val nodeExecutionPredictionList: MutableList<ExecutionPrediction> = mutableListOf(),
    var isPendingRequest: Boolean? = null,
    var hasWatchdogTimeout: Boolean? = false,
    var isTimeout: Boolean? = false,
    /** Uptime when the draft node chain was initialized; anchors offline interarrival/backlog replay. */
    var draftStartUptimeMs: Long? = null,
    /** Uptime when the draft sequence completed; with the timeout deadline it scores the pacing outcome. */
    var draftEndUptimeMs: Long? = null,
    /**
     * Burst-session identity from [CaptureAvailablePacer]: the uptime that session began at, so it takes a new value
     * each time the drained pipeline clears the pacer and never repeats across pacer instances. Offline grouping
     * treats a change as a session boundary, so only its inequality matters, not its magnitude.
     */
    var pacerSessionId: Long? = null,
    /**
     * Runtime pacing decision consumed at this capture's draft start - the captureAvailable delay that gated this
     * capture. Null when nothing gated it (first capture of a burst, or fresh process).
     */
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
    /** onShutter-to-decision elapsed the backlog deficit charged; null on rows persisted before recording. */
    val shutterElapsedMs: Long?,
    val draftSequenceBudgetMs: Long,
    val draftSequencePredictedDurationMs: Double,
    /** Learned between-node overhead the backlog clock added per queued draft (clock work = predicted + this). */
    val draftSequenceOverheadDurationMs: Double,
    /** Draft-sequence duration both pacing deficits use; see [CaptureAvailablePacingPrediction]. */
    val draftSequencePacingDurationMs: Double,
    val draftSequenceKey: String,
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
