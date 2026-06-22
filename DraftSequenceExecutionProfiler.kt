package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import android.util.Size
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.core2.node.NodeId
import com.samsung.android.camera.core2.processor.nodeController.DraftNodeChainAccessor
import com.samsung.android.camera.core2.util.CLog
import java.util.concurrent.CompletableFuture

private const val TAG = "DraftSequenceExecutionProfiler"

/**
 * Drives one draft sequence's node lifecycle, recording both individual stage observations
 * ([WorkloadKey]) and remaining-suffix observations ([WorkloadSequenceKey]). The suffix observation
 * starts at an admission stage (Bokeh / Filter) entry and closes when finalize execution completes;
 * admission then uses the learned suffix bound instead of summing independent stage upper bounds.
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

    /**
     * Suffix observations in progress, opened at admission stages and awaiting finalization. Each
     * tracks how many of its later stages still have to report before it can become a sample.
     */
    private val suffixObservations: MutableList<SuffixObservation> = mutableListOf()

    private var plannedAdmissionStages: List<NodeId> = emptyList()
    private var finalizeExecutionSession: DraftSequenceExecutionSession? = null

    fun setDraftNodeChainAccessor(accessor: DraftNodeChainAccessor) {
        nodeChainLifecycle.reset(accessor)
        updateDraftPlan(accessor.configuredNodeIdList)
    }

    fun deinitializeDraftNodeChain() {
        nodeChainLifecycle.deinitializeWhenIdle()
    }

    private fun updateDraftPlan(nodeIds: List<NodeId>) {
        plannedAdmissionStages = nodeIds.filter(WorkloadKey::isAdmissionStageNode)
    }

    /**
     * Profiles one node execution.
     *
     * For portrait mode with Filter enabled:
     *   - Bokeh entry prediction uses UB([Bokeh, Filter, FinalizeExecution])
     *   - Filter entry prediction uses UB([Filter, FinalizeExecution])
     *   - Every admission stage is capped at (budget - UB([FinalizeExecution])) via its Future.get timeout
     *
     * Admission stages complete at node return. FinalizeExecution starts at ImageCodec and completes
     * when SavingDraftImageTaskManager observes task finish, so it covers encode through saved draft.
     */
    fun profileNodeExecution(nodeId: NodeId): DraftSequenceExecutionSession {
        val nowMs = SystemClock.uptimeMillis()
        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs ?: (nowMs + MakerFeature.CAPTURE_TIMEOUT_MS)
        val deviceState = deviceStateReader.read()
        val preExecutionMetrics = PreExecutionMetrics(
            budgetMs = timeoutTimestampMs - nowMs,
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )
        val resultImageFormat = captureMetrics.resultImageFormat
        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeId = nodeId,
            preExecutionMetrics = preExecutionMetrics,
        )
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
        }

        return if (WorkloadKey.isFinalizeExecutionNode(nodeId)) {
            createFinalizeExecutionSession(resultImageFormat, preExecutionMetrics, nodeExecutionMetrics)
        } else {
            createAdmissionExecutionSession(nodeId, resultImageFormat, preExecutionMetrics, nodeExecutionMetrics)
        }
    }

    private fun createFinalizeExecutionSession(
        resultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
        nodeExecutionMetrics: NodeExecutionMetrics,
    ): DraftSequenceExecutionSession {
        val prediction = predictor
            .predictAdmission(WorkloadSequenceKey(mandatoryWorkloadKeys()), preExecutionMetrics)
            .copy(admit = true)

        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionPredictionList += prediction
        }

        return DraftSequenceExecutionSession.deferred(
            onCancel = { finalizeExecutionSession = null },
            onComplete = { postExecutionMetrics ->
                recordCompletedStage(
                    resultImageFormat,
                    nodeExecutionMetrics,
                    postExecutionMetrics,
                    openedSuffix = null
                )
            },
        ).also { session ->
            finalizeExecutionSession = session
        }
    }

    private fun createAdmissionExecutionSession(
        nodeId: NodeId,
        resultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
        nodeExecutionMetrics: NodeExecutionMetrics,
    ): DraftSequenceExecutionSession {
        val resultImageSize = captureMetrics.resultImageSize
        val suffixKey = admissionSuffixKey(nodeId, resultImageSize, resultImageFormat)
        val prediction = predictor.predictAdmission(suffixKey, preExecutionMetrics)
        val timeoutMs = processTimeoutMs(preExecutionMetrics)
        val suffixObservation = SuffixObservation(suffixKey)

        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionPredictionList += prediction
        }

        return DraftSequenceExecutionSession.bounded(
            shouldRun = prediction.admit,
            processTimeoutMs = timeoutMs,
            onTimedOutTask = nodeChainLifecycle::waitFor,
            onComplete = { postExecutionMetrics ->
                recordCompletedStage(
                    resultImageFormat,
                    nodeExecutionMetrics,
                    postExecutionMetrics,
                    openedSuffix = suffixObservation
                )
            },
        )
    }

    /**
     * A stage finished: store its measurement, feed the per-stage model, and count the stage down on
     * every suffix in progress. An admission stage additionally opens [openedSuffix] for its own suffix.
     * A skipped/timed-out stage never reaches here, so it never decrements and leaves its enclosing
     * suffixes incomplete.
     */
    private fun recordCompletedStage(
        resultImageFormat: Int,
        nodeExecutionMetrics: NodeExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
        openedSuffix: SuffixObservation?,
    ) {
        nodeExecutionMetrics.postExecutionMetrics = postExecutionMetrics
        predictor.updateNodeExecution(nodeExecutionMetrics, captureMetrics.resultImageSize, resultImageFormat, isPendingRequest)
        synchronized(sequenceLock) {
            suffixObservations.forEach { it.remainingStages-- }
            if (openedSuffix != null) {
                suffixObservations += openedSuffix
            }
        }
    }

    /**
     * End of the draft task. Completes FinalizeExecution, feeds ready suffix models, and records
     * whether the capture overran its timeout.
     */
    fun completeDraftSequenceExecution(): Boolean {
        finalizeExecutionSession?.complete()
        finalizeExecutionSession = null

        val completedObservations = synchronized(sequenceLock) {
            val (completed, dropped) = suffixObservations.partition { it.remainingStages == 0 }
            suffixObservations.clear()
            if (dropped.isNotEmpty()) {
                CLog.i(TAG, "drop incomplete suffix observations - count=${dropped.size}")
            }
            completed
        }
        completedObservations.forEach { observation ->
            predictor.updateWorkloadSequence(observation.sequenceKey, observation.durationMs())
        }

        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs
        val isTimeout = timeoutTimestampMs != null && timeoutTimestampMs < SystemClock.uptimeMillis()
        draftSequenceMetrics.isTimeout = isTimeout
        return isTimeout
    }

    /** Drops any open FinalizeExecution/suffix sample without feeding the predictor. */
    fun cancelDraftSequenceExecution() {
        finalizeExecutionSession?.cancel()
        finalizeExecutionSession = null
        synchronized(sequenceLock) {
            suffixObservations.clear()
        }
    }

    private fun admissionSuffixKey(nodeId: NodeId, resultImageSize: Size, resultImageFormat: Int): WorkloadSequenceKey {
        val followingIndex = plannedAdmissionStages.indexOf(nodeId)
        val followingNodeIds = if (followingIndex < 0) {
            emptyList()
        } else {
            plannedAdmissionStages.drop(followingIndex + 1)
        }
        val workloads = listOf(workloadKey(nodeId, resultImageSize, resultImageFormat)) +
            followingNodeIds.map { workloadKey(it, resultImageSize, resultImageFormat) } +
            mandatoryWorkloadKeys()
        return WorkloadSequenceKey(workloads)
    }

    private fun workloadKey(nodeId: NodeId, resultImageSize: Size, resultImageFormat: Int): WorkloadKey {
        return WorkloadKey.node(nodeId, resultImageSize, resultImageFormat, isPendingRequest)
    }

    private fun mandatoryWorkloadKeys(): List<WorkloadKey> {
        return listOf(
            WorkloadKey.finalizeExecution(captureMetrics.resultImageSize, captureMetrics.resultImageFormat, isPendingRequest),
        )
    }

    /**
     * Wall-clock budget the current quality stage may run before FinalizeExecution must start. The call
     * site uses this as its Future.get timeout so a slow quality stage cannot eat into the mandatory tail.
     */
    private fun processTimeoutMs(preExecutionMetrics: PreExecutionMetrics): Long {
        val tailKey = WorkloadSequenceKey(mandatoryWorkloadKeys())
        val tailUpperBoundMs = predictor.predictAdmission(tailKey, preExecutionMetrics).predictedUpperBoundMs
        return (preExecutionMetrics.budgetMs - tailUpperBoundMs).coerceAtLeast(0L)
    }

    /**
     * One suffix observation: the wall-clock duration from the first admission stage through draft task
     * completion. Only duration feeds the suffix model. [remainingStages] is how many stages after the
     * first must still complete for this to be a valid sample.
     */
    private class SuffixObservation(
        val sequenceKey: WorkloadSequenceKey,
    ) {
        var remainingStages: Int = sequenceKey.workloads.size - 1
        private val startedAtMs = SystemClock.uptimeMillis()

        fun durationMs(): Long = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
    }
}

private class DraftNodeChainLifecycle {
    private enum class State { ACTIVE, DEINITIALIZE_REQUESTED, DEINITIALIZED }

    private val lock = Any()
    private var accessor: DraftNodeChainAccessor? = null
    private var pendingTask: CompletableFuture<Void>? = null
    private var state = State.ACTIVE

    fun reset(accessor: DraftNodeChainAccessor) {
        synchronized(lock) {
            this.accessor = accessor
            pendingTask = null
            state = State.ACTIVE
        }
    }

    fun waitFor(taskFinished: CompletableFuture<Void>) {
        val shouldDeinitialize = synchronized(lock) {
            if (state == State.DEINITIALIZED) {
                return
            }
            pendingTask = taskFinished
            state == State.DEINITIALIZE_REQUESTED
        }
        if (shouldDeinitialize) {
            deinitializeAfter(taskFinished)
        }
    }

    fun deinitializeWhenIdle() {
        val taskFinished = synchronized(lock) {
            when (state) {
                State.ACTIVE -> {
                    state = State.DEINITIALIZE_REQUESTED
                    pendingTask
                }
                State.DEINITIALIZE_REQUESTED, State.DEINITIALIZED -> return
            }
        }
        deinitializeAfter(taskFinished)
    }

    private fun deinitializeAfter(taskFinished: CompletableFuture<Void>?) {
        if (taskFinished?.isDone == false) {
            taskFinished.whenComplete { _, _ -> deinitializeNow() }
        } else {
            deinitializeNow()
        }
    }

    private fun deinitializeNow() {
        val accessor = synchronized(lock) {
            if (state == State.DEINITIALIZED) {
                return
            }
            state = State.DEINITIALIZED
            accessor
        } ?: return

        try {
            accessor.deinitializeNodeChain()
        } catch (t: Throwable) {
            CLog.e(TAG, "deinitializeDraftNodeChain error", t)
        }
    }
}

