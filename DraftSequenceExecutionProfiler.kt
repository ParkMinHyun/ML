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
import com.samsung.android.camera.watermark.Watermark.WatermarkType
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
    private val captureAvailablePacer: CaptureAvailablePacer = CaptureAvailablePacer.instance,
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictor.instance,
    draftSequenceMetrics: DraftSequenceMetrics = DraftSequenceMetrics(),
) {

    private val modelUpdate = ModelUpdateBuffer()
    private val metricsRecorder = MetricsRecorder(captureMetrics, draftSequenceMetrics, isPendingRequest)
    private val nodeChainLifecycle = DraftNodeChainLifecycle()
    private val sizeBucket = SizeBucket.of(captureMetrics.resultImageSize)

    private var draftSequenceNodeList: List<Node> = emptyList()
    private var pendingCompleteSession: DraftSequenceExecutionSession? = null

    /**
     * Initializes draft-node-chain profiling and observes captureAvailable pacing once before any node executes.
     * Passes the full planned suffix (even before Bokeh's second input arrives); the pacer subtracts any
     * session-demoted workloads itself, since it owns that state. Encoding-only captures pace the RESERVED Encoding
     * sequence so captureAvailable admission and draft-start observations stay balanced.
     */
    fun initializeDraftNodeChain(accessor: DraftNodeChainAccessor) {
        nodeChainLifecycle.setAccessor(accessor)
        draftSequenceNodeList = accessor.configuredNodeList

        val plannedWorkloadKeys = draftSequenceNodeList.mapNotNull { plannedNode ->
            workloadKeyFor(plannedNode, requireReadyToRun = false)
        }
        if (plannedWorkloadKeys.isEmpty()) {
            return
        }

        val gatingPacingDecision = captureAvailablePacer.observeDraftStart(
            WorkloadSequenceKey(plannedWorkloadKeys),
            readBudgetMs(),
        )
        metricsRecorder.onDraftStart(
            draftStartUptimeMs = SystemClock.uptimeMillis(),
            pacerSessionId = captureAvailablePacer.currentSessionId(),
            gatingPacingDecision = gatingPacingDecision,
        )
    }

    /**
     * Profiles one predictable node execution.
     *
     * OPTIONAL workloads are admitted by their remaining suffix UB, hardened by session-sticky demotion: the first
     * in-session rejection of Bokeh, or of the Decoding-Filter-Overlay Watermark chain, forces rejection until the
     * draft pipeline drains ([CaptureAvailablePacer.clear]), so one burst never alternates effects between shots.
     * JPEG-path Decoding is forced when required by Frame Watermark. REQUIRED workloads (DynamicFunction / Frame
     * Watermark) always run but are not reserve-protected; RESERVED workload is the mandatory tail.
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

        val decision = predictor.predictAdmission(workloadSequenceKey, preExecutionMetrics)
        val effectiveAdmit = effectiveAdmit(workloadSequenceKey, decision.executionPrediction.admit)
        val effectiveDecision = decision.copy(
            executionPrediction = decision.executionPrediction.copy(admit = effectiveAdmit),
        )
        modelUpdate.remember(effectiveDecision)
        val prediction = effectiveDecision.executionPrediction
        metricsRecorder.onPrediction(prediction)

        return createExecutionSession(
            workloadKey = workloadKey,
            workloadSequenceKey = workloadSequenceKey,
            preExecutionMetrics = preExecutionMetrics,
            nodeExecutionMetrics = nodeExecutionMetrics,
            prediction = prediction,
        )
    }

    private fun effectiveAdmit(workloadSequenceKey: WorkloadSequenceKey, modelAdmit: Boolean): Boolean {
        val workloadKey = workloadSequenceKey.headWorkloadKey
        if (workloadKey is WorkloadKey.Decoding && workloadSequenceKey.hasFrameWatermark()) {
            return true
        }
        return when (workloadKey) {
            is WorkloadKey.Bokeh -> admitWithSessionDemotion(SessionDemotion.BOKEH, modelAdmit)
            is WorkloadKey.Decoding, is WorkloadKey.Filter ->
                admitWithSessionDemotion(SessionDemotion.YUV_EFFECTS, modelAdmit)
            is WorkloadKey.Watermark -> if (workloadKey.watermarkType == WatermarkType.FRAME) {
                modelAdmit
            } else {
                // Overlay Watermark follows the YUV chain's demotion but its own model rejection does not trigger it.
                modelAdmit && !captureAvailablePacer.isSessionDemoted(SessionDemotion.YUV_EFFECTS)
            }
            is WorkloadKey.DynamicFunction, is WorkloadKey.Encoding -> modelAdmit
        }
    }

    /**
     * Session-sticky OPTIONAL gate: once this group is rejected it stays rejected until the pipeline drains (pacer
     * [clear]), so effects cannot alternate on/off between consecutive captures of one burst. The pacer only holds
     * the demotion state; the sticky decision is made here.
     */
    private fun admitWithSessionDemotion(demotion: SessionDemotion, modelAdmit: Boolean): Boolean {
        if (captureAvailablePacer.isSessionDemoted(demotion)) {
            return false
        }
        if (!modelAdmit) {
            captureAvailablePacer.demoteForSession(demotion)
        }
        return modelAdmit
    }

    private fun readPreExecutionMetrics(): PreExecutionMetrics {
        val deviceState = deviceStateReader.read()
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
                DraftSequenceExecutionSession.forOptionalWorkload(
                    shouldRun = prediction.admit,
                    watchdogTimeoutMs = watchdogDecision.timeoutMs,
                    onTimedOutTask = { worker ->
                        nodeChainLifecycle.waitFor(worker)
                        metricsRecorder.onWatchdogTimedOut(nodeExecutionMetrics)
                    },
                    onComplete = onComplete,
                )
            }
            WorkloadPolicy.REQUIRED -> DraftSequenceExecutionSession.forRequiredWorkload(onComplete)
            WorkloadPolicy.RESERVED -> DraftSequenceExecutionSession.forReservedWorkload(
                onCancel = { pendingCompleteSession = null },
                onComplete = onComplete,
            ).also { session ->
                pendingCompleteSession = session
            }
        }
    }

    /** Completes the RESERVED workload, lets Predictor learn from the capture, and records whether it timed out. */
    fun completeDraftSequenceExecution(): Boolean {
        pendingCompleteSession?.complete()
        pendingCompleteSession = null

        modelUpdate.drainOnce()?.let { (workloadDurations, admissionDecisions) ->
            predictor.learnFromCapture(workloadDurations, admissionDecisions)
        }

        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs
        val isTimeout = timeoutTimestampMs != null && timeoutTimestampMs < SystemClock.uptimeMillis()
        metricsRecorder.onCaptureEnd(
            isTimeout = isTimeout,
            draftEndUptimeMs = SystemClock.uptimeMillis(),
        )
        return isTimeout
    }

    /** Cancels the pending RESERVED workload without discarding collected samples. */
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
            is WatermarkNode -> WorkloadKey.Watermark(sizeBucket, node.watermarkType)
            is SecImageCodecNodeBase -> when {
                node.isDecodingUsage -> WorkloadKey.Decoding(sizeBucket)
                node.isEncodeUsage -> WorkloadKey.Encoding(
                    sizeBucket,
                    captureMetrics.resultImageFormat,
                    isPendingRequest,
                )
                else -> error("SecImageCodecNodeBase is neither decoding nor encoding usage: ${node.javaClass.simpleName}")
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

    fun onDraftStart(
        draftStartUptimeMs: Long,
        pacerSessionId: Int,
        gatingPacingDecision: CaptureAvailablePacingDecision?,
    ) {
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.draftStartUptimeMs = draftStartUptimeMs
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

    fun onCaptureEnd(isTimeout: Boolean, draftEndUptimeMs: Long) {
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.isTimeout = isTimeout
            draftSequenceMetrics.draftEndUptimeMs = draftEndUptimeMs
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
