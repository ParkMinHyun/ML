package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.SystemClock
import android.util.Size
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.core2.node.NodeId
import com.samsung.android.camera.core2.util.CLog

private const val TAG = "DraftSequenceExecutionProfiler"

/**
 * Drives one draft sequence's node lifecycle, recording both individual stage observations
 * ([WorkloadKey]) and remaining-suffix observations ([WorkloadSequenceKey]). The suffix observation
 * starts at Bokeh / Filter / Encoding entry and closes at saving completion; admission then uses the
 * learned suffix bound instead of summing independent stage upper bounds.
 */
class DraftSequenceExecutionProfiler @JvmOverloads constructor(
    private val context: Context,
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
    private val completedWorkloadKeys: MutableList<WorkloadKey> = mutableListOf()
    private val openSequenceObservations: MutableList<PendingSequenceObservation> = mutableListOf()

    private var plannedAdmissionStages: List<NodeId> = emptyList()
    private lateinit var savingExecutionSession: DraftSequenceExecutionSession

    /** Ordered quality stages for this draft, excluding mandatory Encoding + Saving. */
    fun setDraftPlan(admissionStages: List<NodeId>) {
        plannedAdmissionStages = admissionStages
            .filter { WorkloadKey.isAdmissionStageNode(it) }
            .toList()
    }

    /**
     * Profiles one node execution.
     *
     * For portrait mode with Filter enabled:
     *   - Bokeh entry prediction uses UB([Bokeh, Filter, Encoding, Saving])
     *   - Filter entry prediction uses UB([Filter, Encoding, Saving])
     *   - Every stage is capped at (budget - UB([Encoding, Saving])) via its Future.get timeout
     *
     * In other words, admission is quality-aware, while the per-stage timeout is a hard tail-safety guard.
     */
    fun profileNodeExecution(nodeId: NodeId, inputImageSize: Size): DraftSequenceExecutionSession {
        val nowMs = SystemClock.uptimeMillis()
        val timeoutTimestampMs = timeoutTimestampMs(nowMs)
        val preExecutionMetrics = readPreExecutionMetrics(timeoutTimestampMs - nowMs)

        val resultImageSize = captureMetrics.resultImageSize
        val resultImageFormat = captureMetrics.resultImageFormat
        val nodeWorkloadKey = WorkloadKey.nodeOrNull(
            nodeId = nodeId,
            inputImageSize = inputImageSize,
            outputImageSize = resultImageSize,
            outputImageFormat = resultImageFormat,
        )

        val admissionSequenceKey = if (WorkloadKey.isAdmissionStageNode(nodeId) && nodeWorkloadKey != null) {
            remainingSequenceKeyStartingAtNode(
                nodeId = nodeId,
                nodeWorkloadKey = nodeWorkloadKey,
                inputImageSize = inputImageSize,
                resultImageSize = resultImageSize,
                resultImageFormat = resultImageFormat,
            )
        } else {
            null
        }

        val prediction = admissionSequenceKey?.let { sequenceKey ->
            predictor.predictAdmission(sequenceKey, preExecutionMetrics)
        }

        val processTimeoutMs = processTimeoutMs(resultImageSize, resultImageFormat, preExecutionMetrics)

        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeId = nodeId,
            inputImageSize = inputImageSize,
            outputImageSize = resultImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )

        val pendingSequenceObservation = nodeWorkloadKey?.let {
            sequenceObservationStartingAtNode(
                nodeId = nodeId,
                nodeWorkloadKey = it,
                inputImageSize = inputImageSize,
                resultImageSize = resultImageSize,
                resultImageFormat = resultImageFormat,
            )
        }

        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
            if (prediction != null) {
                draftSequenceMetrics.nodeExecutionPredictionList += prediction
            }
        }

        if (prediction != null) {
            CLog.i(TAG, "prediction - nodeId=$nodeId, sequence=$admissionSequenceKey, prediction=$prediction")
        }
        CLog.i(TAG, "process timeout - nodeId=$nodeId, timeoutMs=$processTimeoutMs")

        return DraftSequenceExecutionSession(
            executionPrediction = prediction,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
            processTimeoutMs = processTimeoutMs,
            onComplete = { session ->
                if (nodeWorkloadKey != null) {
                    predictor.updateNodeExecution(nodeExecutionMetrics, resultImageFormat)
                    markWorkloadCompleted(nodeWorkloadKey)
                }

                // complete() runs only when the stage produced a usable result, so this is a valid
                // suffix sample. Skipped or timed-out stages call abort() and never reach here.
                if (session.shouldRun) {
                    pendingSequenceObservation?.let { addOpenSequenceObservation(it) }
                }
            },
        )
    }

    fun profileSavingExecution() {
        val nowMs = SystemClock.uptimeMillis()
        val timeoutTimestampMs = timeoutTimestampMs(nowMs)
        val preExecutionMetrics = readPreExecutionMetrics(timeoutTimestampMs - nowMs)

        val savingExecutionMetrics = SavingExecutionMetrics(
            isPendingRequest = isPendingRequest,
            resultImageSize = captureMetrics.resultImageSize,
            resultImageFormat = captureMetrics.resultImageFormat,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )
        val savingWorkloadKey = WorkloadKey.saving(
            captureMetrics.resultImageSize,
            captureMetrics.resultImageFormat,
            isPendingRequest,
        )

        draftSequenceMetrics.savingExecutionMetrics = savingExecutionMetrics
        savingExecutionSession = DraftSequenceExecutionSession(
            executionPrediction = null,
            postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
            onComplete = {
                predictor.updateSavingExecution(savingExecutionMetrics)
                markWorkloadCompleted(savingWorkloadKey)
                completeOpenSequenceObservations()
            },
        )
    }

    /**
     * End of the saving stage. Fills saving metrics, updates saving and ready suffix observations,
     * then records whether the capture overran its timeout.
     */
    fun completeSavingExecution(): Boolean {
        if (this::savingExecutionSession.isInitialized) {
            savingExecutionSession.complete()
        }
        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs
        val isTimeout = timeoutTimestampMs != null && timeoutTimestampMs < SystemClock.uptimeMillis()
        draftSequenceMetrics.isTimeout = isTimeout
        return isTimeout
    }

    private fun timeoutTimestampMs(nowMs: Long): Long {
        return captureMetrics.timeoutTimestampMs ?: (nowMs + MakerFeature.CAPTURE_TIMEOUT_MS)
    }

    private fun readPreExecutionMetrics(budgetMs: Long): PreExecutionMetrics {
        val deviceState = deviceStateReader.read()
        return PreExecutionMetrics(
            budgetMs = budgetMs,
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )
    }

    private fun mandatoryWorkloadKeys(resultImageSize: Size, resultImageFormat: Int): List<WorkloadKey> {
        return listOf(
            WorkloadKey.encoding(resultImageSize, resultImageFormat),
            WorkloadKey.saving(resultImageSize, resultImageFormat, isPendingRequest),
        )
    }

    private fun remainingSequenceKeyStartingAtNode(
        nodeId: NodeId,
        nodeWorkloadKey: WorkloadKey,
        inputImageSize: Size,
        resultImageSize: Size,
        resultImageFormat: Int,
    ): WorkloadSequenceKey {
        val savingKey = WorkloadKey.saving(resultImageSize, resultImageFormat, isPendingRequest)
        val mandatoryKeys = mandatoryWorkloadKeys(resultImageSize, resultImageFormat)
        val workloadKeys = when {
            WorkloadKey.isAdmissionStageNode(nodeId) -> {
                listOf(nodeWorkloadKey) + followingAdmissionWorkloadKeys(
                    currentNodeId = nodeId,
                    inputImageSize = inputImageSize,
                    resultImageSize = resultImageSize,
                    resultImageFormat = resultImageFormat,
                ) + mandatoryKeys
            }
            WorkloadKey.isEncodingNode(nodeId) -> listOf(nodeWorkloadKey, savingKey)
            else -> listOf(nodeWorkloadKey)
        }
        return WorkloadSequenceKey(workloadKeys)
    }

    private fun sequenceObservationStartingAtNode(
        nodeId: NodeId,
        nodeWorkloadKey: WorkloadKey,
        inputImageSize: Size,
        resultImageSize: Size,
        resultImageFormat: Int,
    ): PendingSequenceObservation? {
        if (!WorkloadKey.isAdmissionStageNode(nodeId) && !WorkloadKey.isEncodingNode(nodeId)) {
            return null
        }
        return PendingSequenceObservation(
            sequenceKey = remainingSequenceKeyStartingAtNode(
                nodeId = nodeId,
                nodeWorkloadKey = nodeWorkloadKey,
                inputImageSize = inputImageSize,
                resultImageSize = resultImageSize,
                resultImageFormat = resultImageFormat,
            ),
        )
    }

    /**
     * Wall-clock budget the current stage may run before the mandatory [Encoding, Saving] tail must
     * start: remaining budget minus UB([Encoding, Saving]). The call site uses this as its Future.get
     * timeout so a slow quality stage can never eat into the tail's reserved time. Filter preservation
     * is handled by the next Filter admission check, not by this timeout.
     */
    private fun processTimeoutMs(
        resultImageSize: Size,
        resultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
    ): Long {
        val tailKey = WorkloadSequenceKey(mandatoryWorkloadKeys(resultImageSize, resultImageFormat))
        val tailUpperBoundMs = predictor.predictAdmission(tailKey, preExecutionMetrics).predictedUpperBoundMs
        return (preExecutionMetrics.budgetMs - tailUpperBoundMs).coerceAtLeast(0L)
    }

    /** Workload keys for the planned admission stages that run after [currentNodeId]. */
    private fun followingAdmissionWorkloadKeys(
        currentNodeId: NodeId,
        inputImageSize: Size,
        resultImageSize: Size,
        resultImageFormat: Int,
    ): List<WorkloadKey> {
        val index = plannedAdmissionStages.indexOf(currentNodeId)
        if (index < 0) {
            return emptyList()
        }
        return plannedAdmissionStages.drop(index + 1).mapNotNull { nodeId ->
            WorkloadKey.nodeOrNull(nodeId, inputImageSize, resultImageSize, resultImageFormat)
        }
    }

    private fun markWorkloadCompleted(workloadKey: WorkloadKey) {
        synchronized(sequenceLock) {
            completedWorkloadKeys += workloadKey
        }
    }

    private fun addOpenSequenceObservation(observation: PendingSequenceObservation) {
        synchronized(sequenceLock) {
            openSequenceObservations += observation
        }
    }

    private fun completeOpenSequenceObservations() {
        val observationsToUpdate = synchronized(sequenceLock) {
            val completedSnapshot = completedWorkloadKeys.toList()
            val readyObservations = openSequenceObservations.filter { observation ->
                observation.sequenceKey.isFullyObservedBy(completedSnapshot)
            }
            val droppedCount = openSequenceObservations.size - readyObservations.size
            openSequenceObservations.clear()
            if (droppedCount > 0) {
                CLog.i(TAG, "drop incomplete sequence observations - count=$droppedCount")
            }
            readyObservations
        }

        observationsToUpdate.forEach { observation ->
            predictor.updateWorkloadSequence(
                sequenceKey = observation.sequenceKey,
                postExecutionMetrics = observation.complete(),
            )
        }
    }

    private fun WorkloadSequenceKey.isFullyObservedBy(completedWorkloads: List<WorkloadKey>): Boolean {
        val remaining = workloads.toMutableList()
        completedWorkloads.forEach { completedWorkload ->
            remaining.remove(completedWorkload)
        }
        return remaining.isEmpty()
    }

    private class PendingSequenceObservation(
        val sequenceKey: WorkloadSequenceKey,
    ) {
        private val startedAtMs: Long = SystemClock.uptimeMillis()
        private val gcTracker = GcTracker()
        private val cpuProcessingTracker = CpuProcessingTracker()

        fun complete(): PostExecutionMetrics {
            return PostExecutionMetrics().also { postExecutionMetrics ->
                postExecutionMetrics.durationMs = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
                postExecutionMetrics.gcSnapshot = gcTracker.delta()
                postExecutionMetrics.cpuProcessingSnapshot = cpuProcessingTracker.delta()
            }
        }
    }
}

/**
 * Handle returned by [DraftSequenceExecutionProfiler.profileNodeExecution].
 *
 * Exactly one of [complete] / [abort] must be called. Call [complete] when the stage ran to a usable
 * result; call [abort] when it was skipped by admission, or it overran [getProcessTimeoutMs] and the
 * result was discarded. Timing enforcement lives at the call site (a `Future.get` timeout).
 */
class DraftSequenceExecutionSession internal constructor(
    val executionPrediction: ExecutionPrediction?,
    private val postExecutionMetrics: PostExecutionMetrics,
    private val processTimeoutMs: Long = 0L,
    private val onComplete: (DraftSequenceExecutionSession) -> Unit,
) {
    /** True when there is no admission gate, or the predicted suffix upper bound fits the budget. */
    val shouldRun: Boolean = executionPrediction?.admit ?: true

    private val startedAtMs = SystemClock.uptimeMillis()
    private val gcTracker = GcTracker()
    private val cpuProcessingTracker = CpuProcessingTracker()
    private var completed = false

    /**
     * Max time the stage may run before the mandatory [Encoding, Saving] tail must start, i.e.
     * (budget - UB([Encoding, Saving])). Intended as the call site's `Future.get` timeout.
     */
    fun getProcessTimeoutMs(): Long = processTimeoutMs

    /** Marks this session as skipped/cancelled without learning from it. */
    fun abort() {
        markCompleted()
    }

    /** Fills [PostExecutionMetrics] and updates the predictor exactly once. */
    fun complete() {
        markCompleted()
        postExecutionMetrics.durationMs = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
        postExecutionMetrics.gcSnapshot = gcTracker.delta()
        postExecutionMetrics.cpuProcessingSnapshot = cpuProcessingTracker.delta()
        onComplete(this)
    }

    private fun markCompleted() {
        synchronized(this) {
            check(!completed) { "DraftSequenceExecutionSession already completed." }
            completed = true
        }
    }
}
