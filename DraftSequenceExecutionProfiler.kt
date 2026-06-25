package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import android.util.Size
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.core2.node.DynamicFunctionNode
import com.samsung.android.camera.core2.node.Node
import com.samsung.android.camera.core2.node.dualBokeh.samsung.SecDualBokehNodeBase
import com.samsung.android.camera.core2.node.filter.SecFilterNode
import com.samsung.android.camera.core2.node.imageCodec.samsung.SecImageCodecNodeBase
import com.samsung.android.camera.core2.node.watermark.WatermarkNode
import com.samsung.android.camera.core2.processor.nodeController.DraftNodeChainAccessor
import com.samsung.android.camera.core2.util.CLog
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

private const val TAG = "DraftSequenceExecutionProfiler"

/**
 * Drives one draft sequence's node lifecycle. Workload durations are collected during node execution,
 * then SeqPAW-UB receives decision-time prediction snapshots and actual workload durations at capture end.
 */
class DraftSequenceExecutionProfiler @JvmOverloads constructor(
    private val deviceStateReader: DeviceStateReader,
    private val captureMetrics: CaptureMetrics,
    private val isPendingRequest: Boolean,
    private val draftSequenceMetrics: DraftSequenceMetrics = DraftSequenceMetrics(),
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictor.instance,
) {

    init {
        captureMetrics.draftSequenceMetrics = draftSequenceMetrics
    }

    private val sequenceLock = Any()
    private val nodeChainLifecycle = DraftNodeChainLifecycle()
    private val workloadDurations: MutableMap<WorkloadKey, Long> = mutableMapOf()
    private val sequenceSnapshots: MutableMap<WorkloadSequenceKey, SequencePredictionSnapshot> = mutableMapOf()

    private var draftSequenceNodeList: List<Node> = emptyList()
    private var pendingCompleteSession: DraftSequenceExecutionSession? = null
    private var isCaptureUpdated = false

    fun setDraftNodeChainAccessor(accessor: DraftNodeChainAccessor) {
        nodeChainLifecycle.setAccessor(accessor)
        draftSequenceNodeList = accessor.configuredNodeList
    }

    /**
     * Profiles one predictable node execution.
     *
     * ADMIT workloads (Bokeh / Filter) are admitted by their remaining suffix UB.
     * OBSERVE workloads (DynamicFunction / Watermark) always run but stay in prediction;
     * COMPLETE workload is the mandatory tail.
     */
    fun profileNodeExecution(node: Node): DraftSequenceExecutionSession? {
        val workloadKey = workloadKeyFor(node, requireReadyToRun = true) ?: return null
        val nowMs = SystemClock.uptimeMillis()
        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs ?: (nowMs + MakerFeature.CAPTURE_TIMEOUT_MS)
        val deviceState = deviceStateReader.read()
        val preExecutionMetrics = PreExecutionMetrics(
            budgetMs = timeoutTimestampMs - nowMs,
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )
        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeName = node.javaClass.simpleName,
            preExecutionMetrics = preExecutionMetrics,
        )
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
        }

        return createExecutionSession(
            node = node,
            workloadKey = workloadKey,
            preExecutionMetrics = preExecutionMetrics,
            nodeExecutionMetrics = nodeExecutionMetrics,
        )
    }

    private fun createExecutionSession(
        node: Node,
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
        nodeExecutionMetrics: NodeExecutionMetrics,
    ): DraftSequenceExecutionSession {
        val sequenceKey = WorkloadSequenceKey(workloadsFrom(node, workloadKey))
        val decision = predictor.predictAdmission(sequenceKey, preExecutionMetrics)
        rememberDecision(decision)

        val prediction = when (workloadKey.policy) {
            WorkloadPolicy.ADMIT -> decision.executionPrediction
            WorkloadPolicy.OBSERVE, WorkloadPolicy.COMPLETE -> decision.executionPrediction.copy(admit = true)
        }
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionPredictionList += prediction
        }

        val onComplete: (PostExecutionMetrics) -> Unit = { postExecutionMetrics ->
            recordCompletedWorkload(
                workloadKey,
                nodeExecutionMetrics,
                postExecutionMetrics,
            )
        }

        return when (workloadKey.policy) {
            WorkloadPolicy.ADMIT -> DraftSequenceExecutionSession(
                shouldRun = prediction.admit,
                processTimeoutMs = processTimeoutMs(node, workloadKey, preExecutionMetrics),
                onTimedOutTask = nodeChainLifecycle::waitFor,
                onComplete = onComplete,
            )
            WorkloadPolicy.OBSERVE -> DraftSequenceExecutionSession(
                onComplete = onComplete,
            )
            WorkloadPolicy.COMPLETE -> DraftSequenceExecutionSession(
                completeOnReturn = false,
                onCancel = { pendingCompleteSession = null },
                onComplete = onComplete,
            ).also { session ->
                pendingCompleteSession = session
            }
        }
    }

    private fun recordCompletedWorkload(
        workloadKey: WorkloadKey,
        nodeExecutionMetrics: NodeExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        nodeExecutionMetrics.postExecutionMetrics = postExecutionMetrics
        synchronized(sequenceLock) {
            workloadDurations[workloadKey] = postExecutionMetrics.durationMs.coerceAtLeast(0L)
        }
    }

    /** Completes the COMPLETE workload, updates SeqPAW-UB, and records whether this capture timed out. */
    fun completeDraftSequenceExecution(): Boolean {
        pendingCompleteSession?.complete()
        pendingCompleteSession = null

        val captureUpdate = synchronized(sequenceLock) {
            if (isCaptureUpdated) {
                null
            } else {
                isCaptureUpdated = true
                Pair(workloadDurations.toMap(), sequenceSnapshots.values.toList())
            }
        }
        captureUpdate?.let { (completedWorkloadDurations, completedSnapshots) ->
            predictor.updateCapture(completedWorkloadDurations, completedSnapshots)
        }

        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs
        val isTimeout = timeoutTimestampMs != null && timeoutTimestampMs < SystemClock.uptimeMillis()
        draftSequenceMetrics.isTimeout = isTimeout
        return isTimeout
    }

    /** Cancels the pending COMPLETE workload without discarding collected samples. */
    fun cancelDraftSequenceExecution() {
        pendingCompleteSession?.cancel()
        pendingCompleteSession = null
    }

    private fun processTimeoutMs(
        node: Node,
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): Long {
        // ADMIT watchdogs reserve only the mandatory COMPLETE workload.
        // OBSERVE workloads are measured but not protected here.
        val reserveWorkloads = workloadsFrom(node, workloadKey)
            .drop(1)
            .filter { plannedWorkloadKey -> plannedWorkloadKey.policy == WorkloadPolicy.COMPLETE }
        if (reserveWorkloads.isEmpty()) {
            return preExecutionMetrics.budgetMs.coerceAtLeast(0L)
        }

        val timeoutDecision = predictor.predictWatchdogTimeout(WorkloadSequenceKey(reserveWorkloads), preExecutionMetrics)
        rememberDecision(timeoutDecision.decision)
        return timeoutDecision.timeoutMs
    }

    private fun rememberDecision(decision: SeqPawDecision) {
        synchronized(sequenceLock) {
            sequenceSnapshots[decision.nodeSnapshot.sequenceKey] = decision.nodeSnapshot
            sequenceSnapshots[decision.sequenceSnapshot.sequenceKey] = decision.sequenceSnapshot
        }
    }

    private fun workloadsFrom(node: Node, workloadKey: WorkloadKey): List<WorkloadKey> {
        val index = draftSequenceNodeList.indexOfFirst { plannedNode -> plannedNode === node }
        return if (index >= 0) {
            draftSequenceNodeList.drop(index).mapNotNull { plannedNode ->
                workloadKeyFor(plannedNode, requireReadyToRun = false)
            }
        } else {
            listOf(workloadKey)
        }
    }

    private fun workloadKeyFor(node: Node, requireReadyToRun: Boolean): WorkloadKey? {
        val sizeBucket = SizeBucket.of(captureMetrics.resultImageSize)
        return when (node) {
            is SecDualBokehNodeBase -> WorkloadKey.Bokeh(sizeBucket)
                .takeIf { !requireReadyToRun || node.isMaxInputCount() }
            is SecFilterNode -> WorkloadKey.Filter(sizeBucket)
            is DynamicFunctionNode -> WorkloadKey.DynamicFunction(sizeBucket)
            is WatermarkNode -> WorkloadKey.Watermark(sizeBucket)
            is SecImageCodecNodeBase -> WorkloadKey.Encoding(
                sizeBucket,
                captureMetrics.resultImageFormat,
                isPendingRequest,
            ).takeIf { node.isEncodeUsage }
            else -> null
        }
    }

    fun deinitializeDraftNodeChain() {
        nodeChainLifecycle.deinitializeWhenIdle()
    }
}

/** Stable workload bucket shared by the workload EWMA and sequence calibrator. */
sealed interface WorkloadKey {
    val policy: WorkloadPolicy

    data class Bokeh(val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.ADMIT
    }

    data class DynamicFunction(val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OBSERVE
    }

    data class Filter(val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.ADMIT
    }

    data class Watermark(val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OBSERVE
    }

    /** Mandatory tail from ImageCodec entry through saved draft task completion. */
    data class Encoding(
        val sizeBucket: SizeBucket,
        val imageFormat: Int,
        val isPendingRequest: Boolean,
    ) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.COMPLETE
    }
}

/** Ordered key for a decision suffix, e.g. [Bokeh, DynamicFunction, Filter, Watermark, Encoding]. */
data class WorkloadSequenceKey(val workloads: List<WorkloadKey>) {

    init {
        require(workloads.isNotEmpty()) { "WorkloadSequenceKey must contain at least one workload." }
    }

    override fun toString(): String = workloads.joinToString(prefix = "[", postfix = "]")
}


enum class WorkloadPolicy {
    ADMIT,
    OBSERVE,
    COMPLETE,
}

/** Stable megapixel tiers a frame snaps to - the size axis of the workload taxonomy. */
enum class SizeBucket(val megaPixels: Int) {
    MP12(12),
    MP24(24),
    MP50(50),
    MP108(108),
    MP200(200);

    companion object {
        fun of(size: Size): SizeBucket {
            val pixels = size.width.toLong().coerceAtLeast(0L) *
                    size.height.toLong().coerceAtLeast(0L)
            val megaPixels = pixels.toDouble() / 1_000_000.0
            return entries.minByOrNull { abs(megaPixels - it.megaPixels) } ?: MP12
        }
    }
}

/**
 * Owns the draft node chain's deinit timing. Normally [deinitializeWhenIdle] deinitializes at once,
 * but a workload that timed out leaves its worker running on the chain; [waitFor] records that worker so
 * deinit is deferred until it actually finishes. Deinit runs exactly once.
 *
 * Single-use, one instance per draft sequence. [waitFor] (process thread, during node execution)
 * always runs before [deinitializeWhenIdle] (process thread, at task end), so recording the worker is
 * enough - there is no request/worker ordering to reconcile.
 */
private class DraftNodeChainLifecycle {
    private val lock = Any()
    private var accessor: DraftNodeChainAccessor? = null
    private var pendingWorker: CompletableFuture<*>? = null
    private var deinitialized = false

    fun setAccessor(accessor: DraftNodeChainAccessor) {
        synchronized(lock) {
            this.accessor = accessor
        }
    }

    /** A timed-out workload's worker is still running on the chain; defer deinit until it finishes. */
    fun waitFor(worker: CompletableFuture<*>) {
        synchronized(lock) {
            pendingWorker = worker
        }
    }

    /** Deinitializes the chain exactly once, after the pending timed-out worker (if any) finishes. */
    fun deinitializeWhenIdle() {
        val (accessor, worker) = synchronized(lock) {
            if (deinitialized) {
                return
            }
            deinitialized = true
            Pair(accessor, pendingWorker)
        }
        if (accessor == null) {
            return
        }

        val deinit = {
            try {
                accessor.deinitializeNodeChain()
            } catch (t: Throwable) {
                CLog.e(TAG, "deinitializeDraftNodeChain error", t)
            }
        }
        if (worker == null || worker.isDone) {
            deinit()
        } else {
            worker.whenComplete { _, _ -> deinit() }
        }
    }
}