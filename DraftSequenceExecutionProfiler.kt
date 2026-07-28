package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.container.CodecConfiguration
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
    private val isPendingRequest: Boolean,
    private val captureMetrics: CaptureMetrics,
    private val deviceStateReader: DeviceStateReader,
    private val captureAvailablePacer: CaptureAvailablePacer,
    private val predictor: DraftSequenceExecutionPredictor,
    private val admissionPolicy: DraftSequenceAdmissionPolicy,
    draftSequenceMetrics: DraftSequenceMetrics = DraftSequenceMetrics(),
) {

    private val modelUpdate = ModelUpdateBuffer()
    private val metricsRecorder = MetricsRecorder(captureMetrics, draftSequenceMetrics, isPendingRequest)
    private val nodeChainLifecycle = DraftNodeChainLifecycle()
    private val sizeBucket = SizeBucket.of(captureMetrics.resultImageSize)

    private var draftSequenceNodeList: List<Node> = emptyList()
    private var pendingCompleteSession: DraftSequenceExecutionSession? = null
    private var draftDeviceStateSnapshot: DeviceStateSnapshot? = null
    private var draftStartUptimeMs = 0L

    /**
     * Initializes draft-node-chain profiling and observes captureAvailable pacing once before any node executes.
     * Passes the full planned suffix (even before Bokeh's second input arrives); the pacer re-projects it onto the
     * session's demoted shape via the [DraftSequenceAdmissionPolicy]. Encoding-only captures pace the RESERVED
     * Encoding sequence, and captures with no predictable workloads (JPEG passthrough) pass a null key so their
     * admission record is still consumed - captureAvailable admission and draft-start observations stay balanced.
     */
    fun initializeDraftNodeChain(accessor: DraftNodeChainAccessor) {
        nodeChainLifecycle.setAccessor(accessor)
        draftSequenceNodeList = accessor.configuredNodeList
        draftStartUptimeMs = SystemClock.uptimeMillis()

        val plannedWorkloadKeys = draftSequenceNodeList.mapNotNull { plannedNode ->
            classifyWorkloadKey(plannedNode, requireReadyToRun = false)
        }
        val gatingPacingDecision = captureAvailablePacer.startDraftSequence(
            plannedWorkloadKeys.takeIf { it.isNotEmpty() }?.let(::WorkloadSequenceKey),
            readBudgetMs(),
        )
        metricsRecorder.onDraftStart(
            gatingPacingDecision = gatingPacingDecision,
            pacerSessionId = captureAvailablePacer.readCurrentSessionId(),
        )

        // Memory, thermal, and storage are observability inputs; only the timeout budget must stay fresh per node.
        // Sampling them once keeps DeviceStateReader's blocking dispatcher work off every node transition.
        draftDeviceStateSnapshot = deviceStateReader.read()
    }

    /**
     * Profiles one predictable node execution.
     *
     * OPTIONAL workloads are admitted by their remaining suffix UB, hardened by session-sticky demotion: the first
     * in-session rejection of Bokeh, or of the Decoding-Filter-Overlay Watermark chain, forces rejection until the
     * draft pipeline drains ([DraftSequenceAdmissionPolicy.clear]), so one burst never alternates effects between
     * shots.
     * JPEG-path Decoding is forced when required by Frame Watermark. REQUIRED workloads (DynamicFunction / Frame
     * Watermark) always run but are not reserve-protected; RESERVED workload is the mandatory tail.
     */
    fun profileNodeExecution(node: Node): DraftSequenceExecutionSession? {
        val workloadKey = classifyWorkloadKey(node, requireReadyToRun = true) ?: return null
        val workloadSequenceKey = WorkloadSequenceKey(collectPlannedWorkloadKeysFrom(node, workloadKey))
        val preExecutionMetrics = readPreExecutionMetrics()
        val nodeExecutionMetrics = metricsRecorder.onNodeExecutionStart(
            nodeName = node.javaClass.simpleName,
            workloadKey = workloadKey,
            preExecutionMetrics = preExecutionMetrics,
        )

        val modelDecision = predictor.decideAdmission(workloadSequenceKey, preExecutionMetrics)
        val decision = modelDecision.copy(
            executionPrediction = modelDecision.executionPrediction.copy(
                admit = admissionPolicy.admit(
                    workloadKey = workloadKey,
                    hasFrameWatermark = workloadSequenceKey.hasFrameWatermark(),
                    modelAdmit = modelDecision.executionPrediction.admit,
                ),
            ),
        )
        modelUpdate.remember(decision)
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
        val deviceState = draftDeviceStateSnapshot ?: deviceStateReader.read().also { snapshot ->
            draftDeviceStateSnapshot = snapshot
        }
        return PreExecutionMetrics(
            budgetMs = readBudgetMs(),
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )
    }

    private fun readBudgetMs(): Long {
        val nowMs = SystemClock.uptimeMillis()
        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs ?: (nowMs + MakerFeature.CAPTURE_TIMEOUT_MS)
        return timeoutTimestampMs - nowMs
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
            WorkloadPolicy.OPTIONAL -> {
                val watchdogDecision = predictor.predictWatchdogTimeout(workloadSequenceKey, preExecutionMetrics)
                watchdogDecision.decision?.let(modelUpdate::remember)
                metricsRecorder.onWatchdogArmed(nodeExecutionMetrics, watchdogDecision.timeoutMs)
                DraftSequenceExecutionSession.createForOptionalWorkload(
                    shouldRun = prediction.admit,
                    watchdogTimeoutMs = watchdogDecision.timeoutMs,
                    onTimedOutTask = { worker ->
                        nodeChainLifecycle.waitFor(worker)
                        metricsRecorder.onWatchdogTimedOut(nodeExecutionMetrics)
                    },
                    onComplete = onComplete,
                )
            }
            WorkloadPolicy.REQUIRED -> DraftSequenceExecutionSession.createForRequiredWorkload(onComplete)
            WorkloadPolicy.RESERVED -> DraftSequenceExecutionSession.createForReservedWorkload(
                onCancel = { pendingCompleteSession = null },
                onComplete = onComplete,
            ).also { session ->
                pendingCompleteSession = session
            }
        }
    }

    /** Completes RESERVED work, updates the learned duration/pacing models, and records whether it timed out. */
    fun completeDraftSequenceExecution(): Boolean {
        pendingCompleteSession?.complete()
        pendingCompleteSession = null

        val draftWallMs = if (draftStartUptimeMs > 0L) {
            (SystemClock.uptimeMillis() - draftStartUptimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        modelUpdate.drainOnce()?.let { (workloadDurations, admissionDecisions) ->
            predictor.learnFromCapture(workloadDurations, admissionDecisions, draftWallMs)
        }
        // Keep the session's size-scoped duration estimate aligned to this completed draft.
        captureAvailablePacer.observeDraftMeasured(sizeBucket, draftWallMs)

        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs
        val isTimeout = timeoutTimestampMs != null && timeoutTimestampMs < SystemClock.uptimeMillis()
        metricsRecorder.onCaptureEnd(isTimeout)
        return isTimeout
    }

    /** Cancels the pending RESERVED workload without discarding collected samples. */
    fun cancelDraftSequenceExecution() {
        pendingCompleteSession?.cancel()
        pendingCompleteSession = null
    }

    private fun collectPlannedWorkloadKeysFrom(node: Node, workloadKey: WorkloadKey): List<WorkloadKey> {
        val index = draftSequenceNodeList.indexOfFirst { plannedNode -> plannedNode === node }
        return if (index >= 0) {
            draftSequenceNodeList.drop(index).mapNotNull { plannedNode ->
                classifyWorkloadKey(plannedNode, requireReadyToRun = false)
            }
        } else {
            listOf(workloadKey)
        }
    }

    private fun classifyWorkloadKey(node: Node, requireReadyToRun: Boolean): WorkloadKey? {
        return when (node) {
            is SecDualBokehNodeBase -> WorkloadKey.Bokeh(sizeBucket)
                .takeIf { !requireReadyToRun || node.isMaxInputCount() }
            is SecFilterNode -> WorkloadKey.Filter(sizeBucket)
            is DynamicFunctionNode -> WorkloadKey.DynamicFunction(sizeBucket)
            is WatermarkNode -> WorkloadKey.Watermark(sizeBucket, node.watermarkType)
            is SecImageCodecNodeBase -> {
                when (node.codecUsage) {
                    CodecConfiguration.DECODE -> WorkloadKey.Decoding(sizeBucket)
                    CodecConfiguration.ENCODE -> WorkloadKey.Encoding(
                        sizeBucket,
                        captureMetrics.resultImageFormat,
                        isPendingRequest,
                    )
                    else -> error("not supported codecUsage(${node.codecUsage})")
                }
            }
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

    fun onDraftStart(gatingPacingDecision: CaptureAvailablePacingDecision?, pacerSessionId: Long) {
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.draftStartUptimeMs = SystemClock.uptimeMillis()
            draftSequenceMetrics.pacerSessionId = pacerSessionId
            if (gatingPacingDecision != null) {
                draftSequenceMetrics.captureAvailablePacing = gatingPacingDecision.toCaptureAvailablePacingMetrics()
            }
        }
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
            startUptimeMs = SystemClock.uptimeMillis(),
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
            draftSequenceMetrics.draftEndUptimeMs = SystemClock.uptimeMillis()
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
