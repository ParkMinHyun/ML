package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.SystemClock
import android.util.Size
import com.samsung.android.camera.core2.util.CLog
import java.util.function.Consumer
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Base for every draft-sequence execution predictor.
 *
 * This class owns the functionality that is common to *all* predictors and independent of how any
 * one of them models execution cost:
 *   - the workload taxonomy (how a node / saving / tail maps to a stable bucket key),
 *   - the public entry points the profiler calls (single node, saving, combined admission), and
 *   - history replay ([warmUpFromHistory]).
 *
 * A concrete predictor implements only the four model-specific hooks below; it never sees raw
 * nodeIds, image sizes, or formats - it receives the already-bucketed string key and the
 * pre/post-execution metrics. To add a new predictor, subclass this and implement the hooks; all
 * routing, keying, and replay are inherited unchanged.
 */
abstract class DraftSequenceExecutionPredictor {

    abstract val name: String

    // ---- Model-specific hooks (the only thing a concrete predictor must implement) ----

    /** Predicts a single bucketed workload's execution cost. */
    protected abstract fun predictForKey(
        executionKey: String,
        workloadKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction

    /** Corrects the model for a single bucketed workload from its observed outcome. */
    protected abstract fun updateForKey(
        executionKey: String,
        workloadKey: String,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    )

    /** Predicts a decision-level (stage + mandatory tail) cost for admission. */
    protected abstract fun predictForDecision(
        stageExecutionKey: String,
        stageWorkloadKey: String,
        tailWorkloadKey: String,
        decisionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction

    /** Corrects the decision-level model from the combined observed outcome. */
    protected abstract fun updateForDecision(
        decisionKey: String,
        predictedCombinedDurationMs: Long,
        predictedCombinedUpperBoundMs: Long,
        actualStageDurationMs: Long,
        actualEncodingDurationMs: Long,
        actualSavingDurationMs: Long,
    )

    // ---- Generic single-key entry (no workload bucketing) ----

    fun predict(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictForKey(executionKey, WorkloadKey.keyOnly(executionKey).value, preExecutionMetrics)
    }

    fun update(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        updateForKey(executionKey, WorkloadKey.keyOnly(executionKey).value, preExecutionMetrics, postExecutionMetrics)
    }

    // ---- Node ----

    fun predictNodeExecution(
        nodeId: String,
        nodeParams: NodeParams,
        inputImageSize: Size,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictForKey(
            executionKey = nodeId,
            workloadKey = WorkloadKey.node(nodeId, nodeParams, inputImageSize).value,
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    fun updateNodeExecution(nodeExecutionMetrics: NodeExecutionMetrics) {
        updateForKey(
            executionKey = nodeExecutionMetrics.nodeId,
            workloadKey = WorkloadKey.node(
                nodeExecutionMetrics.nodeId,
                nodeExecutionMetrics.nodeParams,
                nodeExecutionMetrics.inputImageSize,
            ).value,
            preExecutionMetrics = nodeExecutionMetrics.preExecutionMetrics,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
        )
    }

    // ---- Saving ----

    fun predictSavingExecution(
        isPendingRequest: Boolean,
        resultImageSize: Size,
        resultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictForKey(
            executionKey = SAVING_EXECUTION_KEY,
            workloadKey = WorkloadKey.saving(isPendingRequest, resultImageSize, resultImageFormat).value,
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    fun updateSavingExecution(savingExecutionMetrics: SavingExecutionMetrics) {
        updateForKey(
            executionKey = SAVING_EXECUTION_KEY,
            workloadKey = WorkloadKey.saving(savingExecutionMetrics).value,
            preExecutionMetrics = savingExecutionMetrics.preExecutionMetrics,
            postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
        )
    }

    // ---- Combined (stage + mandatory tail) admission ----

    /**
     * Predicts a stage + mandatory tail together for stage admission:
     *     elapsedSoFar + predictedUpperBound(stage + tail) <= totalBudget
     * The tail means Encoding + Saving; update it later via [updateCombinedAdmission].
     */
    fun predictCombinedAdmission(
        stageNodeId: String,
        stageNodeParams: NodeParams,
        stageInputImageSize: Size,
        tailResultImageSize: Size,
        tailResultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val stageKey = WorkloadKey.node(stageNodeId, stageNodeParams, stageInputImageSize)
        val tailKey = WorkloadKey.tail(tailResultImageSize, tailResultImageFormat)
        return predictForDecision(
            stageExecutionKey = stageNodeId,
            stageWorkloadKey = stageKey.value,
            tailWorkloadKey = tailKey.value,
            decisionKey = DecisionKey.combined(stageKey, tailKey).value,
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    fun updateCombinedAdmission(
        stageNodeId: String,
        stageNodeParams: NodeParams,
        stageInputImageSize: Size,
        tailResultImageSize: Size,
        tailResultImageFormat: Int,
        predictedCombinedDurationMs: Long,
        predictedCombinedUpperBoundMs: Long,
        actualStageDurationMs: Long,
        actualEncodingDurationMs: Long,
        actualSavingDurationMs: Long,
    ) {
        val stageKey = WorkloadKey.node(stageNodeId, stageNodeParams, stageInputImageSize)
        val tailKey = WorkloadKey.tail(tailResultImageSize, tailResultImageFormat)
        updateForDecision(
            decisionKey = DecisionKey.combined(stageKey, tailKey).value,
            predictedCombinedDurationMs = predictedCombinedDurationMs,
            predictedCombinedUpperBoundMs = predictedCombinedUpperBoundMs,
            actualStageDurationMs = actualStageDurationMs,
            actualEncodingDurationMs = actualEncodingDurationMs,
            actualSavingDurationMs = actualSavingDurationMs,
        )
    }

    /**
     * Replays complete capture history. Timed-out captures are skipped because later-stage samples
     * may be censored by the fallback path. Returns the number of samples fed.
     */
    fun warmUpFromHistory(history: List<CaptureMetrics>): Int {
        var updatedCount = 0
        history.forEach { captureMetrics ->
            val draftMetrics = captureMetrics.draftSequenceMetrics ?: return@forEach
            if (draftMetrics.isTimeout == true) {
                return@forEach
            }

            draftMetrics.nodeExecutionMetricsList.forEach { node ->
                if (node.postExecutionMetrics.durationMs > 0L) {
                    updateNodeExecution(node)
                    updatedCount++
                }
            }

            draftMetrics.savingExecutionMetrics?.let { saving ->
                if (saving.postExecutionMetrics.durationMs > 0L) {
                    updateSavingExecution(saving)
                    updatedCount++
                }
            }
        }
        return updatedCount
    }

    /**
     * Stable workload bucket key. This is the Mondrian split shared by every predictor:
     *   Bokeh : output/input image size bucket
     *   Filter: input image size bucket
     *   Encoding/Tail/Saving: size bucket x image format
     */
    private data class WorkloadKey(val value: String) {
        companion object {
            fun keyOnly(executionKey: String): WorkloadKey {
                return WorkloadKey("keyOnly=$executionKey")
            }

            fun node(
                nodeId: String,
                nodeParams: NodeParams,
                inputImageSize: Size,
            ): WorkloadKey {
                return when (nodeId) {
                    NODE_ID_BOKEH -> WorkloadKey("bokeh|size=${bokehSize(nodeParams, inputImageSize)}")
                    NODE_ID_FILTER -> WorkloadKey("filter|size=${sizeBucket(inputImageSize)}")
                    NODE_ID_ENCODING -> WorkloadKey("encoding|size=${sizeBucket(inputImageSize)}|format=${encodingFormat(nodeParams)}")
                    else -> WorkloadKey("node=$nodeId|size=${sizeBucket(inputImageSize)}")
                }
            }

            fun tail(
                resultImageSize: Size,
                resultImageFormat: Int,
            ): WorkloadKey {
                return WorkloadKey("tail|size=${sizeBucket(resultImageSize)}|format=$resultImageFormat")
            }

            fun saving(
                isPendingRequest: Boolean,
                resultImageSize: Size,
                resultImageFormat: Int,
            ): WorkloadKey {
                return WorkloadKey(
                    "saving|pending=$isPendingRequest|size=${sizeBucket(resultImageSize)}|format=$resultImageFormat",
                )
            }

            fun saving(savingExecutionMetrics: SavingExecutionMetrics): WorkloadKey {
                return saving(
                    isPendingRequest = savingExecutionMetrics.isPendingRequest,
                    resultImageSize = savingExecutionMetrics.resultImageSize,
                    resultImageFormat = savingExecutionMetrics.resultImageFormat,
                )
            }

            private fun bokehSize(nodeParams: NodeParams, inputImageSize: Size): String {
                return when (nodeParams) {
                    is NodeParams.DualBokeh -> sizeBucket(nodeParams.outputImageSize)
                    else -> sizeBucket(inputImageSize)
                }
            }

            private fun encodingFormat(nodeParams: NodeParams): Int {
                return when (nodeParams) {
                    is NodeParams.Encoding -> nodeParams.encodingFormat
                    else -> UNKNOWN_FORMAT
                }
            }
        }
    }

    private data class DecisionKey(val value: String) {
        companion object {
            fun combined(stageKey: WorkloadKey, tailKey: WorkloadKey): DecisionKey {
                return DecisionKey("${stageKey.value}|${tailKey.value}")
            }
        }
    }

    private companion object {
        private const val NODE_ID_BOKEH = "NODE_SEC_V2_DUAL_BOKEH"
        private const val NODE_ID_FILTER = "NODE_SEC_FILTER"
        private const val NODE_ID_ENCODING = "NODE_SEC_V2_IMAGE_CODEC"
        private const val UNKNOWN_FORMAT = -1

        private fun sizeBucket(size: Size): String {
            val pixels = size.width.toLong().coerceAtLeast(0L) * size.height.toLong().coerceAtLeast(0L)
            val megaPixels = pixels.toDouble() / 1_000_000.0
            val nearest = listOf(12, 24, 50, 200).minByOrNull { abs(megaPixels - it) } ?: 12
            return "${nearest}MP"
        }
    }
}

/** Saving has no nodeId; all saving executions share a single model key. */
private const val SAVING_EXECUTION_KEY = "saving"

/**
 * Owns the process-wide [DraftSequenceExecutionPredictor] instance whose learned state must persist
 * across captures, plus the one-shot warm-up from the metrics database.
 */
class DraftSequenceExecutionPredictionManager @JvmOverloads constructor(
    val predictor: DraftSequenceExecutionPredictor = ConformalEwmaDraftSequenceExecutionPredictor(),
) {

    /** Replays persisted capture history into the predictor. Returns the sample count fed. */
    fun warmUpFromHistory(history: List<CaptureMetrics>): Int {
        return predictor.warmUpFromHistory(history)
    }

    companion object {
        private const val TAG = "DraftSequenceExecutionPredictionManager"

        /**
         * Process-wide instance. The predictor's learned state lives here, so profilers created
         * per capture keep accumulating across captures instead of cold-starting every time.
         */
        @JvmStatic
        val instance: DraftSequenceExecutionPredictionManager = DraftSequenceExecutionPredictionManager()

        private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var warmUpStarted: Boolean = false

        /**
         * Feeds [instance] with the capture history stored in the metrics database, restoring
         * the learned state lost on process death. Call once at process start; subsequent calls
         * are no-ops. Retries are allowed after a failure.
         */
        @JvmStatic
        @JvmOverloads
        @Synchronized
        fun warmUp(context: Context, callback: Consumer<Int>? = null) {
            if (warmUpStarted) {
                return
            }
            warmUpStarted = true

            val appContext = context.applicationContext
            warmUpScope.launch {
                try {
                    val history = CaptureMetricsRepository
                        .getInstance(appContext)
                        .getAll()

                    val updatedCount = instance.warmUpFromHistory(history)

                    CLog.i(TAG, "[mhyun2.park] warmUp completed. updatedCount=$updatedCount")
                    callback?.accept(updatedCount)
                } catch (t: Throwable) {
                    warmUpStarted = false
                    CLog.e(TAG, "[mhyun2.park] warmUp failed", t)
                }
            }
        }
    }
}

/**
 * Splits a single node / saving lifecycle into the two steps the caller drives:
 *
 *   1. [predictNodeExecution] / [predictSavingExecution] - reads device state, builds
 *      [PreExecutionMetrics], predicts (via the workload-bucketed predictor API), records the
 *      metrics + prediction onto [DraftSequenceMetrics], and returns a [DraftSequenceExecutionSession].
 *   2. caller inspects [DraftSequenceExecutionSession.shouldRun] and runs the work or falls back.
 *   3. if the work ran, caller calls [DraftSequenceExecutionSession.complete] exactly once to fill
 *      [PostExecutionMetrics] and correct the model.
 *
 * This profiler is cheap and may be created per capture; the default [predictor] is the process-wide
 * [DraftSequenceExecutionPredictionManager.instance] predictor, so the learned model persists.
 */
class DraftSequenceExecutionProfiler @JvmOverloads constructor(
    private val deviceStateReader: DeviceStateReader,
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictionManager.instance.predictor,
) {

    /**
     * Step 1 (node): predict a node's execution cost from pre-execution state and record it.
     *
     * @param nodeId stable node identifier (model bucket key).
     * @param nodeParams node-specific pre-execution params (e.g. encoding format, bokeh output size).
     * @param timeoutMs absolute deadline for this node; the remaining budget is derived at read time.
     * @param inputImageSize input image dimensions.
     * @param draftMetrics draft metrics to append this node's record to.
     */
    @JvmOverloads
    fun predictNodeExecution(
        captureMetrics: CaptureMetrics,
        nodeId: String,
        nodeParams: NodeParams = NodeParams.None,
        timeoutMs: Long,
        inputImageSize: Size,
        draftMetrics: DraftSequenceMetrics = captureMetrics.ensureDraftSequenceMetrics(),
    ): DraftSequenceExecutionSession {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val prediction = predictor.predictNodeExecution(nodeId, nodeParams, inputImageSize, preExecutionMetrics)

        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeId = nodeId,
            nodeParams = nodeParams,
            inputImageSize = inputImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )
        synchronized(draftMetrics) {
            draftMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
            draftMetrics.nodeExecutionPredictionList += prediction
        }

        return DraftSequenceExecutionSession(
            executionPrediction = prediction,
            budgetMs = preExecutionMetrics.budgetMs,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
            onComplete = { predictor.updateNodeExecution(nodeExecutionMetrics) },
        )
    }

    /**
     * Step 1 (saving): predict the saving step's cost. Saving has no nodeId; it is bucketed by
     * pending flag x result size x format. The [SavingExecutionMetrics] is attached to [draftMetrics].
     */
    @JvmOverloads
    fun predictSavingExecution(
        captureMetrics: CaptureMetrics,
        timeoutMs: Long,
        isPendingRequest: Boolean,
        resultImageSize: Size,
        resultImageFormat: Int,
        draftMetrics: DraftSequenceMetrics = captureMetrics.ensureDraftSequenceMetrics(),
    ): DraftSequenceExecutionSession {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val prediction = predictor.predictSavingExecution(
            isPendingRequest = isPendingRequest,
            resultImageSize = resultImageSize,
            resultImageFormat = resultImageFormat,
            preExecutionMetrics = preExecutionMetrics,
        )

        val savingExecutionMetrics = SavingExecutionMetrics(
            isPendingRequest = isPendingRequest,
            resultImageSize = resultImageSize,
            resultImageFormat = resultImageFormat,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )
        synchronized(draftMetrics) {
            draftMetrics.savingExecutionMetrics = savingExecutionMetrics
            draftMetrics.savingExecutionPredictionList.clear()
            draftMetrics.savingExecutionPredictionList += prediction
        }

        return DraftSequenceExecutionSession(
            executionPrediction = prediction,
            budgetMs = preExecutionMetrics.budgetMs,
            postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
            onComplete = { predictor.updateSavingExecution(savingExecutionMetrics) },
        )
    }

    /**
     * Predicts the cost of the fallback path - the mandatory encoding node plus saving - under the
     * current device state. Read-only: nothing is recorded and no session is returned. Used to
     * derive a watchdog timeout (remaining budget - fallback cost). The combined upper bound is
     * conservative (assumes both steps hit their own upper bound); confidence is the lower of the two.
     */
    fun predictFallbackExecution(
        encodingNodeId: String,
        timeoutMs: Long,
    ): ExecutionPrediction {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val encodingPrediction = predictor.predict(encodingNodeId, preExecutionMetrics)
        val savingPrediction = predictor.predict(SAVING_EXECUTION_KEY, preExecutionMetrics)

        return ExecutionPrediction(
            predictedDurationMs = encodingPrediction.predictedDurationMs + savingPrediction.predictedDurationMs,
            predictedUpperBoundMs = encodingPrediction.predictedUpperBoundMs + savingPrediction.predictedUpperBoundMs,
            confidence = minOf(encodingPrediction.confidence, savingPrediction.confidence),
            reason = buildString {
                append("fallback=encoding+saving")
                append(" encoding{").append(encodingPrediction.reason).append('}')
                append(" saving{").append(savingPrediction.reason).append('}')
            },
            predictorName = encodingPrediction.predictorName,
        )
    }

    private fun readPreExecutionMetrics(timeoutMs: Long): PreExecutionMetrics {
        val deviceState = deviceStateReader.read()
        return PreExecutionMetrics(
            budgetMs = timeoutMs - SystemClock.uptimeMillis(),
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )
    }
}

/**
 * Handle returned by [DraftSequenceExecutionProfiler.predictNodeExecution] /
 * [DraftSequenceExecutionProfiler.predictSavingExecution].
 *
 * GC / CPU / wall-clock baselines are captured at construction time, i.e. right after prediction, so
 * the caller should run the work immediately after deciding [shouldRun]. Call [complete] exactly
 * once, only if the work actually ran.
 */
class DraftSequenceExecutionSession internal constructor(
    val executionPrediction: ExecutionPrediction,
    private val budgetMs: Long,
    private val postExecutionMetrics: PostExecutionMetrics,
    private val onComplete: () -> Unit,
) {
    /** True when the predicted upper bound fits within the budget. */
    val shouldRun: Boolean = executionPrediction.predictedUpperBoundMs <= budgetMs

    private val gcTracker = GcTracker()
    private val cpuProcessingTracker = CpuProcessingTracker()
    private val startedAtMs = SystemClock.uptimeMillis()
    private var completed = false

    /**
     * Step 3: fill [PostExecutionMetrics] from the elapsed GC / CPU / duration and correct the
     * model. Call this only after the work has actually run, exactly once.
     */
    @Synchronized
    fun complete() {
        check(!completed) { "DraftSequenceExecutionSession.complete() called more than once." }
        completed = true

        postExecutionMetrics.durationMs = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
        postExecutionMetrics.gcSnapshot = gcTracker.delta()
        postExecutionMetrics.cpuProcessingSnapshot = cpuProcessingTracker.delta()

        onComplete()
    }
}

private fun CaptureMetrics.ensureDraftSequenceMetrics(): DraftSequenceMetrics {
    return draftSequenceMetrics ?: DraftSequenceMetrics().also {
        draftSequenceMetrics = it
    }
}
