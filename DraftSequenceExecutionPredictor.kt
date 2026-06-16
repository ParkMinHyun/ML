package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.SystemClock
import android.util.Size
import com.samsung.android.camera.core2.node.NodeId
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
 *   - the workload taxonomy (how a node / saving / tail maps to a stable [WorkloadKey]),
 *   - the public entry points the profiler calls (single node, saving, combined admission), and
 *   - history replay ([warmUpFromHistory]).
 *
 * A concrete predictor implements only the four model-specific hooks below; it never sees raw
 * nodeIds, image sizes, or formats - it receives the already-bucketed [WorkloadKey] and the
 * pre/post-execution metrics. To add a new predictor, subclass this and implement the hooks; all
 * routing, keying, and replay are inherited unchanged.
 */
abstract class DraftSequenceExecutionPredictor {

    abstract val name: String

    // ---- Model-specific hooks (the only thing a concrete predictor must implement) ----

    /** Predicts a single bucketed workload's execution cost. */
    protected abstract fun predictForKey(
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction

    /** Corrects the model for a single bucketed workload from its observed outcome. */
    protected abstract fun updateForKey(
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    )

    /** Predicts a decision-level (stage + mandatory tail) cost for admission. */
    protected abstract fun predictForDecision(
        stageWorkloadKey: WorkloadKey,
        tailWorkloadKey: WorkloadKey,
        decisionKey: DecisionKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction

    /** Corrects the decision-level model from the combined observed outcome. */
    protected abstract fun updateForDecision(
        decisionKey: DecisionKey,
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
        return predictForKey(WorkloadKey.generic(executionKey), preExecutionMetrics)
    }

    fun update(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        updateForKey(WorkloadKey.generic(executionKey), preExecutionMetrics, postExecutionMetrics)
    }

    // ---- Node ----

    fun predictNodeExecution(
        nodeId: NodeId,
        nodeParams: NodeParams,
        inputImageSize: Size,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictForKey(
            workloadKey = WorkloadKey.node(nodeId, nodeParams, inputImageSize),
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    fun updateNodeExecution(nodeExecutionMetrics: NodeExecutionMetrics) {
        updateForKey(
            workloadKey = WorkloadKey.node(
                nodeExecutionMetrics.nodeId,
                nodeExecutionMetrics.nodeParams,
                nodeExecutionMetrics.inputImageSize,
            ),
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
            workloadKey = WorkloadKey.saving(isPendingRequest, resultImageSize, resultImageFormat),
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    fun updateSavingExecution(savingExecutionMetrics: SavingExecutionMetrics) {
        updateForKey(
            workloadKey = WorkloadKey.saving(savingExecutionMetrics),
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
        stageNodeId: NodeId,
        stageNodeParams: NodeParams,
        stageInputImageSize: Size,
        tailResultImageSize: Size,
        tailResultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val stageKey = WorkloadKey.node(stageNodeId, stageNodeParams, stageInputImageSize)
        val tailKey = WorkloadKey.tail(tailResultImageSize, tailResultImageFormat)
        return predictForDecision(
            stageWorkloadKey = stageKey,
            tailWorkloadKey = tailKey,
            decisionKey = DecisionKey.combined(stageKey, tailKey),
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    fun updateCombinedAdmission(
        stageNodeId: NodeId,
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
            decisionKey = DecisionKey.combined(stageKey, tailKey),
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
}

/**
 * Coarse workload family a node / saving / tail maps to. This is the grouping level the predictors
 * fall back to when an exact [WorkloadKey] cell does not yet have enough samples.
 */
enum class WorkloadCategory(val tag: String) {
    BOKEH("bokeh"),
    FILTER("filter"),
    ENCODING("encoding"),
    TAIL("tail"),
    SAVING("saving"),
    NODE("node"),
    GENERIC("generic"),
}

/**
 * Stable workload bucket. This is the Mondrian split shared by every predictor:
 *   Bokeh : DualBokeh output image size bucket
 *   Filter: input image size bucket
 *   Encoding/Tail/Saving: size bucket x image format
 *
 * [category] is the coarse family used for hierarchical fallback; [value] is the exact model-cell
 * identity (and its human-readable form). Equality/hashing are by [value], so it can be used
 * directly as a map key.
 */
class WorkloadKey private constructor(
    val category: WorkloadCategory,
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is WorkloadKey && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        private const val UNKNOWN_FORMAT = -1

        fun generic(executionKey: String): WorkloadKey {
            return of(WorkloadCategory.GENERIC, "key=$executionKey")
        }

        fun node(
            nodeId: NodeId,
            nodeParams: NodeParams,
            inputImageSize: Size,
        ): WorkloadKey {
            return when (nodeId) {
                NodeId.NODE_SEC_V1_DUAL_BOKEH,
                NodeId.NODE_SEC_V1_1_DUAL_BOKEH,
                NodeId.NODE_SEC_V2_DUAL_BOKEH ->
                    of(WorkloadCategory.BOKEH, "size=${bokehSize(nodeParams)}")
                NodeId.NODE_SEC_FILTER ->
                    of(WorkloadCategory.FILTER, "size=${sizeBucket(inputImageSize)}")
                NodeId.NODE_SEC_V2_IMAGE_CODEC ->
                    of(
                        WorkloadCategory.ENCODING,
                        "size=${sizeBucket(inputImageSize)}|format=${encodingFormat(nodeParams)}",
                    )
                else ->
                    of(WorkloadCategory.NODE, "node=${nodeId.name}|size=${sizeBucket(inputImageSize)}")
            }
        }

        fun tail(
            resultImageSize: Size,
            resultImageFormat: Int,
        ): WorkloadKey {
            return of(WorkloadCategory.TAIL, "size=${sizeBucket(resultImageSize)}|format=$resultImageFormat")
        }

        fun saving(
            isPendingRequest: Boolean,
            resultImageSize: Size,
            resultImageFormat: Int,
        ): WorkloadKey {
            return of(
                WorkloadCategory.SAVING,
                "pending=$isPendingRequest|size=${sizeBucket(resultImageSize)}|format=$resultImageFormat",
            )
        }

        fun saving(savingExecutionMetrics: SavingExecutionMetrics): WorkloadKey {
            return saving(
                isPendingRequest = savingExecutionMetrics.isPendingRequest,
                resultImageSize = savingExecutionMetrics.resultImageSize,
                resultImageFormat = savingExecutionMetrics.resultImageFormat,
            )
        }

        private fun of(category: WorkloadCategory, descriptor: String): WorkloadKey {
            val value = if (descriptor.isEmpty()) category.tag else "${category.tag}|$descriptor"
            return WorkloadKey(category, value)
        }

        /** Bokeh cost tracks the bokeh OUTPUT size, carried by [NodeParams.DualBokeh]. */
        private fun bokehSize(nodeParams: NodeParams): String {
            val outputImageSize = (nodeParams as? NodeParams.DualBokeh)?.outputImageSize ?: Size(0, 0)
            return sizeBucket(outputImageSize)
        }

        private fun encodingFormat(nodeParams: NodeParams): Int {
            return (nodeParams as? NodeParams.Encoding)?.encodingFormat ?: UNKNOWN_FORMAT
        }

        private fun sizeBucket(size: Size): String {
            val pixels = size.width.toLong().coerceAtLeast(0L) * size.height.toLong().coerceAtLeast(0L)
            val megaPixels = pixels.toDouble() / 1_000_000.0
            val nearest = listOf(12, 24, 50, 200).minByOrNull { abs(megaPixels - it) } ?: 12
            return "${nearest}MP"
        }
    }
}

/**
 * Decision-level key for a stage + mandatory tail admission. Retains the stage [WorkloadKey] so the
 * predictors can fall back to the stage [WorkloadCategory]; equality/hashing are by [value].
 */
class DecisionKey private constructor(
    val stageKey: WorkloadKey,
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is DecisionKey && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        fun combined(stageKey: WorkloadKey, tailKey: WorkloadKey): DecisionKey {
            return DecisionKey(stageKey, "${stageKey.value}|${tailKey.value}")
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
    val predictor: DraftSequenceExecutionPredictor = EwmaDraftSequenceExecutionPredictor(),
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
     * @param nodeId node identifier (model bucket key).
     * @param nodeParams node-specific pre-execution params (e.g. encoding format, bokeh output size).
     * @param timeoutMs absolute deadline for this node; the remaining budget is derived at read time.
     * @param inputImageSize input image dimensions.
     * @param draftMetrics draft metrics to append this node's record to.
     */
    @JvmOverloads
    fun predictNodeExecution(
        captureMetrics: CaptureMetrics,
        nodeId: NodeId,
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
            admit = encodingPrediction.predictedUpperBoundMs + savingPrediction.predictedUpperBoundMs <=
                preExecutionMetrics.budgetMs,
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
    val shouldRun: Boolean = executionPrediction.admit

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
