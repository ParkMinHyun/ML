package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.SystemClock
import android.util.Size
import java.util.concurrent.Callable
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.core2.node.NodeId
import com.samsung.android.camera.core2.util.CLog

private const val TAG = "DraftSequenceExecutionProfiler"

/**
 * Drives one draft sequence's node lifecycle, recording both individual stage observations
 * ([WorkloadKey]) and remaining-suffix observations ([WorkloadSequenceKey]). The suffix observation
 * starts at an admission stage (Bokeh / Filter) entry and closes at saving completion; admission then
 * uses the learned suffix bound instead of summing independent stage upper bounds.
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

    /**
     * Suffix observations in progress, opened at admission stages and awaiting saving. Each tracks how
     * many of its later stages still have to report in; saving flushes those that reached zero.
     */
    private val suffixObservations: MutableList<SuffixObservation> = mutableListOf()

    private var plannedAdmissionStages: List<NodeId> = emptyList()
    private var savingExecutionSession: DraftSequenceExecutionSession? = null

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
     *   - Every admission stage is capped at (budget - UB([Encoding, Saving])) via its Future.get timeout
     *
     * In other words, admission is quality-aware, while the per-stage timeout is a hard tail-safety guard.
     * Mandatory stages (e.g. Encoding) skip both: they run to completion. Only ever called for predictable
     * nodes (Bokeh / Filter / Encoding), so the node always resolves to a [WorkloadKey].
     */
    fun profileNodeExecution(nodeId: NodeId, inputImageSize: Size): DraftSequenceExecutionSession {
        val preExecutionMetrics = readPreExecutionMetrics()
        val resultImageSize = captureMetrics.resultImageSize
        val resultImageFormat = captureMetrics.resultImageFormat

        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeId = nodeId,
            inputImageSize = inputImageSize,
            outputImageSize = resultImageSize,
            preExecutionMetrics = preExecutionMetrics,
        )
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
        }

        // Mandatory stage (e.g. Encoding): no admission gate, no discard timeout — it runs to completion.
        // On finish it still counts down every suffix in progress, but opens none of its own.
        if (!WorkloadKey.isAdmissionStageNode(nodeId)) {
            return DraftSequenceExecutionSession(
                onComplete = { postExecutionMetrics ->
                    recordCompletedStage(nodeExecutionMetrics, resultImageFormat, postExecutionMetrics, openedSuffix = null)
                },
            )
        }

        // Admission stage (Bokeh / Filter): gated by the learned suffix bound and bounded by a discard
        // timeout. The suffix is [thisStage, ...followingAdmissionStages, Encoding, Saving].
        val nodeWorkloadKey = WorkloadKey.node(nodeId, inputImageSize, resultImageSize, resultImageFormat)
        val followingIndex = plannedAdmissionStages.indexOf(nodeId)
        val followingAdmissionKeys = if (followingIndex < 0) {
            emptyList()
        } else {
            plannedAdmissionStages.drop(followingIndex + 1).map { followingNodeId ->
                WorkloadKey.node(followingNodeId, inputImageSize, resultImageSize, resultImageFormat)
            }
        }
        val suffixKey = WorkloadSequenceKey(listOf(nodeWorkloadKey) + followingAdmissionKeys + mandatoryWorkloadKeys())

        val prediction = predictor.predictAdmission(suffixKey, preExecutionMetrics)
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionPredictionList += prediction
        }

        // Timer starts now; the suffix joins the in-progress set only once the stage actually completes.
        val suffixObservation = SuffixObservation(suffixKey)
        return DraftSequenceExecutionSession(
            shouldRun = prediction.admit,
            bounded = true,
            processTimeoutMs = processTimeoutMs(preExecutionMetrics),
            onComplete = { postExecutionMetrics ->
                recordCompletedStage(nodeExecutionMetrics, resultImageFormat, postExecutionMetrics, openedSuffix = suffixObservation)
            },
        )
    }

    /**
     * A node stage finished: store its measurement, feed the per-stage model, and count the stage down on
     * every suffix in progress. An admission stage additionally opens [openedSuffix] for its own suffix.
     * A skipped/timed-out stage never reaches here, so it never decrements and leaves its enclosing
     * suffixes incomplete.
     */
    private fun recordCompletedStage(
        nodeExecutionMetrics: NodeExecutionMetrics,
        outputImageFormat: Int,
        postExecutionMetrics: PostExecutionMetrics,
        openedSuffix: SuffixObservation?,
    ) {
        nodeExecutionMetrics.postExecutionMetrics = postExecutionMetrics
        predictor.updateNodeExecution(nodeExecutionMetrics, outputImageFormat)
        synchronized(sequenceLock) {
            suffixObservations.forEach { it.remainingStages-- }
            if (openedSuffix != null) {
                suffixObservations += openedSuffix
            }
        }
    }

    fun profileSavingExecution() {
        draftSequenceMetrics.savingExecutionMetrics = SavingExecutionMetrics(
            isPendingRequest = isPendingRequest,
            resultImageSize = captureMetrics.resultImageSize,
            resultImageFormat = captureMetrics.resultImageFormat,
            preExecutionMetrics = readPreExecutionMetrics(),
        )
        savingExecutionSession = DraftSequenceExecutionSession()
    }

    /**
     * End of the saving stage. Measures the saving stage, feeds the saving and ready suffix models,
     * then records whether the capture overran its timeout.
     */
    fun completeSavingExecution(): Boolean {
        val session = savingExecutionSession
        if (session != null) {
            draftSequenceMetrics.savingExecutionMetrics?.let { savingExecutionMetrics ->
                savingExecutionMetrics.postExecutionMetrics = session.complete()
                predictor.updateSavingExecution(savingExecutionMetrics)
            }

            // Saving is the final stage of every suffix: count it down, then flush. Observations that
            // reached zero saw all their later stages complete and become samples; the rest had a
            // skipped or timed-out stage in their suffix and are dropped.
            val completedObservations = synchronized(sequenceLock) {
                suffixObservations.forEach { it.remainingStages-- }
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
        }
        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs
        val isTimeout = timeoutTimestampMs != null && timeoutTimestampMs < SystemClock.uptimeMillis()
        draftSequenceMetrics.isTimeout = isTimeout
        return isTimeout
    }

    private fun timeoutTimestampMs(nowMs: Long): Long {
        return captureMetrics.timeoutTimestampMs ?: (nowMs + MakerFeature.CAPTURE_TIMEOUT_MS)
    }

    private fun readPreExecutionMetrics(): PreExecutionMetrics {
        val nowMs = SystemClock.uptimeMillis()
        val deviceState = deviceStateReader.read()
        return PreExecutionMetrics(
            budgetMs = timeoutTimestampMs(nowMs) - nowMs,
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )
    }

    private fun mandatoryWorkloadKeys(): List<WorkloadKey> {
        val resultImageSize = captureMetrics.resultImageSize
        val resultImageFormat = captureMetrics.resultImageFormat
        return listOf(
            WorkloadKey.encoding(resultImageSize, resultImageFormat),
            WorkloadKey.saving(resultImageSize, resultImageFormat, isPendingRequest),
        )
    }

    /**
     * Wall-clock budget the current stage may run before the mandatory [Encoding, Saving] tail must
     * start: remaining budget minus UB([Encoding, Saving]). The call site uses this as its Future.get
     * timeout so a slow quality stage can never eat into the tail's reserved time. Filter preservation
     * is handled by the next Filter admission check, not by this timeout.
     */
    private fun processTimeoutMs(preExecutionMetrics: PreExecutionMetrics): Long {
        val tailKey = WorkloadSequenceKey(mandatoryWorkloadKeys())
        val tailUpperBoundMs = predictor.predictAdmission(tailKey, preExecutionMetrics).predictedUpperBoundMs
        return (preExecutionMetrics.budgetMs - tailUpperBoundMs).coerceAtLeast(0L)
    }

    /**
     * One suffix observation: the wall-clock duration of running [sequenceKey] from its first
     * (admission) stage through saving. Only duration feeds the suffix model, so GC / CPU aren't
     * tracked here. [remainingStages] is how many stages after the first must still complete for this to
     * be a valid sample.
     */
    private class SuffixObservation(
        val sequenceKey: WorkloadSequenceKey,
    ) {
        var remainingStages: Int = sequenceKey.workloads.size - 1
        private val startedAtMs = SystemClock.uptimeMillis()

        fun durationMs(): Long = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
    }
}

/**
 * Handle returned by [DraftSequenceExecutionProfiler.profileNodeExecution].
 *
 * Check [shouldRun] before running the stage; if it is false, skip the stage and drop the session
 * without calling [complete]. When the stage ran to a usable result, call [complete] so the predictor
 * learns from it. A [bounded] (quality) stage must be discarded if it exceeds [getProcessTimeoutMs];
 * an unbounded (mandatory) stage runs to completion. Timing enforcement lives at the call site (a
 * `Future.get` timeout).
 */
class DraftSequenceExecutionSession internal constructor(
    /** False when admission rejected the stage: skip it and pass the picture through unchanged. */
    val shouldRun: Boolean = true,
    /** Quality stages are bounded by [getProcessTimeoutMs]; mandatory stages run to completion. */
    val bounded: Boolean = false,
    private val processTimeoutMs: Long = 0L,
    private val onComplete: (PostExecutionMetrics) -> Unit = {},
) {
    // Measured on the creating thread — correct for a stage that runs start-to-finish there (saving).
    // A node offloads its work to an executor thread and re-measures it there via runMeasured.
    private val stopwatch = ExecutionStopwatch()
    private var workerMeasurement: PostExecutionMetrics? = null

    /**
     * Max time the stage may run before the mandatory [Encoding, Saving] tail must start, i.e.
     * (budget - UB([Encoding, Saving])). Intended as the call site's `Future.get` timeout.
     */
    fun getProcessTimeoutMs(): Long = processTimeoutMs

    /**
     * Runs [work] on the calling thread and measures it there. CPU counters are thread-scoped, so a
     * stage that executes on its own worker thread (a node on its executor) must be measured on that
     * thread — call this from inside the worker task, not the thread that created the session.
     */
    fun <T> runMeasured(work: Callable<T>): T {
        val stopwatch = ExecutionStopwatch()
        try {
            return work.call()
        } finally {
            workerMeasurement = stopwatch.stop()
        }
    }

    /** Feeds the stage measurement to [onComplete] and returns it. Call at most once, after the run. */
    fun complete(): PostExecutionMetrics {
        val postExecutionMetrics = workerMeasurement ?: stopwatch.stop()
        onComplete(postExecutionMetrics)
        return postExecutionMetrics
    }
}

/** Measures duration + GC + CPU from construction until [stop]; construct and [stop] on one thread. */
private class ExecutionStopwatch {
    private val startedAtMs = SystemClock.uptimeMillis()
    private val gcTracker = GcTracker()
    private val cpuProcessingTracker = CpuProcessingTracker()

    fun stop(): PostExecutionMetrics = PostExecutionMetrics(
        gcSnapshot = gcTracker.delta(),
        cpuProcessingSnapshot = cpuProcessingTracker.delta(),
        durationMs = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L),
    )
}
