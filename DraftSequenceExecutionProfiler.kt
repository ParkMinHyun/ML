package com.samsung.android.camera.core2.ml

import android.os.SystemClock
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

private const val TAG = "DraftSequenceExecutionProfiler"

/**
 * Drives one draft sequence's node lifecycle: classifies each executing node into a [WorkloadKey], asks the
 * Predictor for an admission decision, and feeds the observed outcome back at capture end.
 *
 * State is grouped by destination:
 * - [modelUpdate] buffers what the Predictor learns from (durations + decisions), drained exactly once.
 * - [metricsRecorder] is the sole writer of the [CaptureMetrics] observability store.
 * - [nodeChainLifecycle] owns the draft node chain's deinit timing.
 */
class DraftSequenceExecutionProfiler @JvmOverloads constructor(
    private val captureMetrics: CaptureMetrics,
    private val isPendingRequest: Boolean,
    private val deviceStateReader: DeviceStateReader,
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictor.instance,
    draftSequenceMetrics: DraftSequenceMetrics = DraftSequenceMetrics(),
) {

    private val modelUpdate = ModelUpdateBuffer()
    private val metricsRecorder = MetricsRecorder(captureMetrics, draftSequenceMetrics, isPendingRequest)
    private val nodeChainLifecycle = DraftNodeChainLifecycle()
    private val sizeBucket = SizeBucket.of(captureMetrics.resultImageSize)

    private var draftSequenceNodeList: List<Node> = emptyList()
    private var pendingCompleteSession: DraftSequenceExecutionSession? = null
    private var hasObservedFirstBudget = false

    fun setDraftNodeChainAccessor(accessor: DraftNodeChainAccessor) {
        nodeChainLifecycle.setAccessor(accessor)
        draftSequenceNodeList = accessor.configuredNodeList
    }

    /**
     * Profiles one predictable node execution.
     *
     * ADMIT workloads (Bokeh / Filter) are admitted by their remaining suffix UB.
     * OBSERVE workloads (DynamicFunction / Watermark) always run but stay in prediction;
     * RESERVE workload is the mandatory tail.
     */
    fun profileNodeExecution(node: Node): DraftSequenceExecutionSession? {
        val workloadKey = workloadKeyFor(node, requireReadyToRun = true) ?: return null
        val workloadSequenceKey = WorkloadSequenceKey(plannedWorkloadKeysFrom(node, workloadKey))
        val preExecutionMetrics = readPreExecutionMetrics()
        val nodeExecutionMetrics = metricsRecorder.onNodeExecutionStart(
            nodeName = node.javaClass.simpleName,
            workloadKey = workloadKey,
            preExecutionMetrics = preExecutionMetrics,
        )

        observeFirstBudget(workloadSequenceKey, preExecutionMetrics.budgetMs)
        val decision = predictor.predictAdmission(workloadSequenceKey, preExecutionMetrics).also(modelUpdate::remember)
        val prediction = decision.executionPrediction
        metricsRecorder.onPrediction(prediction)

        return createExecutionSession(
            workloadKey = workloadKey,
            workloadSequenceKey = workloadSequenceKey,
            preExecutionMetrics = preExecutionMetrics,
            nodeExecutionMetrics = nodeExecutionMetrics,
            prediction = prediction,
        )
    }

    private fun readPreExecutionMetrics(): PreExecutionMetrics {
        val nowMs = SystemClock.uptimeMillis()
        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs ?: (nowMs + MakerFeature.CAPTURE_TIMEOUT_MS)
        val deviceState = deviceStateReader.read()
        return PreExecutionMetrics(
            budgetMs = timeoutTimestampMs - nowMs,
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )
    }

    /**
     * Feeds the first leading node's budget to captureAvailable pacing. Leading = the first ADMIT (Bokeh/Filter)
     * or OBSERVE (Watermark/DynamicFunction) node - anything before the RESERVE tail. A RESERVE head means the
     * encoding is already the first node, so there is no pre-encoding budget to pace from and nothing to observe.
     */
    private fun observeFirstBudget(workloadSequenceKey: WorkloadSequenceKey, budgetMs: Long) {
        if (hasObservedFirstBudget || workloadSequenceKey.headWorkloadKey.policy == WorkloadPolicy.RESERVE) {
            return
        }

        predictor.observeCaptureAvailableSlack(workloadSequenceKey, budgetMs)
        hasObservedFirstBudget = true
    }

    private fun createExecutionSession(
        workloadKey: WorkloadKey,
        workloadSequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
        nodeExecutionMetrics: NodeExecutionMetrics,
        prediction: ExecutionPrediction,
    ): DraftSequenceExecutionSession {
        val onComplete: (PostExecutionMetrics) -> Unit = { postExecutionMetrics ->
            metricsRecorder.onWorkloadCompleted(nodeExecutionMetrics, postExecutionMetrics)
            modelUpdate.recordWorkloadDuration(workloadKey, postExecutionMetrics.durationMs)
        }

        return when (workloadKey.policy) {
            WorkloadPolicy.ADMIT -> {
                val watchdogDecision = predictor.predictWatchdogTimeout(workloadSequenceKey, preExecutionMetrics)
                watchdogDecision.decision?.let(modelUpdate::remember)
                metricsRecorder.onWatchdogArmed(nodeExecutionMetrics, watchdogDecision.timeoutMs)
                DraftSequenceExecutionSession.forAdmitWorkload(
                    shouldRun = prediction.admit,
                    watchdogTimeoutMs = watchdogDecision.timeoutMs,
                    onTimedOutTask = { worker ->
                        nodeChainLifecycle.waitFor(worker)
                        metricsRecorder.onWatchdogTimedOut(nodeExecutionMetrics)
                    },
                    onComplete = onComplete,
                )
            }
            WorkloadPolicy.OBSERVE -> DraftSequenceExecutionSession.forObserveWorkload(onComplete)
            WorkloadPolicy.RESERVE -> DraftSequenceExecutionSession.forReserveWorkload(
                onCancel = { pendingCompleteSession = null },
                onComplete = onComplete,
            ).also { session ->
                pendingCompleteSession = session
            }
        }
    }

    /** Completes the RESERVE workload, updates Predictor's UB, and records whether this capture timed out. */
    fun completeDraftSequenceExecution(): Boolean {
        pendingCompleteSession?.complete()
        pendingCompleteSession = null

        modelUpdate.drainOnce()?.let { (workloadDurations, admissionDecisions) ->
            predictor.updateCapture(workloadDurations, admissionDecisions)
        }

        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs
        val isTimeout = timeoutTimestampMs != null && timeoutTimestampMs < SystemClock.uptimeMillis()
        metricsRecorder.onCaptureEnd(isTimeout)
        return isTimeout
    }

    /** Cancels the pending RESERVE workload without discarding collected samples. */
    fun cancelDraftSequenceExecution() {
        pendingCompleteSession?.cancel()
        pendingCompleteSession = null
    }

    private fun plannedWorkloadKeysFrom(node: Node, workloadKey: WorkloadKey): List<WorkloadKey> {
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

/**
 * What one capture teaches the Predictor: actual workload durations plus the admission decisions that produced
 * them, buffered during node execution and drained exactly once at capture end. Cancelling the draft sequence
 * does not clear it - a later complete still drains the samples collected so far.
 */
private class ModelUpdateBuffer {
    private val lock = Any()
    private val workloadDurations = mutableMapOf<WorkloadKey, Long>()
    private val decisions = mutableMapOf<WorkloadSequenceKey, AdmissionDecision>()
    private var drained = false

    fun recordWorkloadDuration(workloadKey: WorkloadKey, durationMs: Long) {
        synchronized(lock) {
            workloadDurations[workloadKey] = durationMs.coerceAtLeast(0L)
        }
    }

    fun remember(decision: AdmissionDecision) {
        synchronized(lock) {
            decisions[decision.workloadSequenceKey] = decision
        }
    }

    /** Returns the buffered durations and decisions on the first call, null afterwards. */
    fun drainOnce(): Pair<Map<WorkloadKey, Long>, List<AdmissionDecision>>? {
        synchronized(lock) {
            if (drained) {
                return null
            }
            drained = true
            return Pair(workloadDurations.toMap(), decisions.values.toList())
        }
    }
}

/**
 * Sole writer of the [CaptureMetrics]/[DraftSequenceMetrics] observability store; nothing model-facing reads
 * from it. Kept in one place so retiring [CaptureMetrics] in favor of logs is a single-class change.
 *
 * Writers arrive from three threads - node execution (process), workload completion (worker), and the watchdog -
 * so every mutation is guarded by the [draftSequenceMetrics] monitor to give the export reader a single
 * happens-before edge over both the lists and the scalar flags.
 */
private class MetricsRecorder(
    captureMetrics: CaptureMetrics,
    private val draftSequenceMetrics: DraftSequenceMetrics,
    isPendingRequest: Boolean,
) {

    init {
        draftSequenceMetrics.isPendingRequest = isPendingRequest
        captureMetrics.draftSequenceMetrics = draftSequenceMetrics
    }

    fun onNodeExecutionStart(
        nodeName: String,
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): NodeExecutionMetrics {
        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeName = nodeName,
            preExecutionMetrics = preExecutionMetrics,
            workloadKey = workloadKey.toReplayString(),
        )
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
        }
        return nodeExecutionMetrics
    }

    fun onPrediction(prediction: ExecutionPrediction) {
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionPredictionList += prediction
        }
    }

    fun onWatchdogArmed(nodeExecutionMetrics: NodeExecutionMetrics, watchdogTimeoutMs: Long) {
        synchronized(draftSequenceMetrics) {
            nodeExecutionMetrics.watchdogTimeoutMs = watchdogTimeoutMs
            nodeExecutionMetrics.watchdogTimedOut = false
        }
    }

    fun onWatchdogTimedOut(nodeExecutionMetrics: NodeExecutionMetrics) {
        synchronized(draftSequenceMetrics) {
            nodeExecutionMetrics.watchdogTimedOut = true
            draftSequenceMetrics.hasWatchdogTimeout = true
        }
    }

    fun onWorkloadCompleted(nodeExecutionMetrics: NodeExecutionMetrics, postExecutionMetrics: PostExecutionMetrics) {
        synchronized(draftSequenceMetrics) {
            nodeExecutionMetrics.postExecutionMetrics = postExecutionMetrics
        }
    }

    fun onCaptureEnd(isTimeout: Boolean) {
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.isTimeout = isTimeout
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
