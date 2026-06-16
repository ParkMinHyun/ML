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
 *   - the public entry points the profiler calls (combined admission + per-stage learning), and
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

    // ---- Node ----

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
     * The tail means Encoding + Saving; correct it later via [updateCombinedAdmission].
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
     * Corrects the combined-admission model from one completed draft sequence: pairs the admission
     * stage (see [WorkloadCategory.isAdmissionStage]) with the mandatory Encoding + Saving tail and
     * feeds their combined residual. No-op unless the stage, the encoding node, and saving all
     * actually ran (a skipped stage or a censored fallback leaves their durations at 0). Returns true
     * when a sample was fed.
     */
    fun updateCombinedAdmission(captureMetrics: CaptureMetrics): Boolean {
        val draftMetrics = captureMetrics.draftSequenceMetrics ?: return false
        val stage = draftMetrics.nodeExecutionMetricsList.firstOrNull {
            WorkloadCategory.ofNode(it.nodeId).isAdmissionStage
        } ?: return false
        val encoding = draftMetrics.nodeExecutionMetricsList.firstOrNull {
            WorkloadCategory.ofNode(it.nodeId) == WorkloadCategory.ENCODING
        } ?: return false
        val saving = draftMetrics.savingExecutionMetrics ?: return false
        // Only the admission stage records a prediction, so this is the combined bound it was issued.
        val prediction = draftMetrics.nodeExecutionPredictionList.firstOrNull() ?: return false

        val stageDurationMs = stage.postExecutionMetrics.durationMs
        val encodingDurationMs = encoding.postExecutionMetrics.durationMs
        val savingDurationMs = saving.postExecutionMetrics.durationMs
        if (stageDurationMs <= 0L || encodingDurationMs <= 0L || savingDurationMs <= 0L) {
            return false
        }

        updateCombinedAdmission(
            stageNodeId = stage.nodeId,
            stageNodeParams = stage.nodeParams,
            stageInputImageSize = stage.inputImageSize,
            // Tail keyed by the capture result, exactly as predictCombinedAdmission issued it.
            tailResultImageSize = captureMetrics.resultImageSize,
            tailResultImageFormat = captureMetrics.resultImageFormat,
            predictedCombinedDurationMs = prediction.predictedDurationMs,
            predictedCombinedUpperBoundMs = prediction.predictedUpperBoundMs,
            actualStageDurationMs = stageDurationMs,
            actualEncodingDurationMs = encodingDurationMs,
            actualSavingDurationMs = savingDurationMs,
        )
        return true
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

            if (updateCombinedAdmission(captureMetrics)) {
                updatedCount++
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
    NODE("node");

    /**
     * Whether this family is gated on the combined (stage + Encoding + Saving) admission bound rather
     * than run unconditionally. Single source of truth for both the profiler (whether to predict) and
     * the combined-admission learner (which node is the stage). Extend here to gate more stages, e.g.
     * `this == BOKEH || this == FILTER`.
     */
    val isAdmissionStage: Boolean
        get() = this == BOKEH

    companion object {
        /** Single source of truth for which [WorkloadCategory] a node id belongs to. */
        fun ofNode(nodeId: NodeId): WorkloadCategory {
            return when (nodeId) {
                NodeId.NODE_SEC_V1_DUAL_BOKEH,
                NodeId.NODE_SEC_V1_1_DUAL_BOKEH,
                NodeId.NODE_SEC_V2_DUAL_BOKEH -> BOKEH
                NodeId.NODE_SEC_FILTER -> FILTER
                NodeId.NODE_SEC_V2_IMAGE_CODEC -> ENCODING
                else -> NODE
            }
        }
    }
}

/** Stable megapixel tiers a frame snaps to - the size axis of the workload taxonomy. */
enum class SizeBucket(val megaPixels: Int) {
    MP12(12),
    MP24(24),
    MP50(50),
    MP200(200);

    companion object {
        fun of(size: Size): SizeBucket {
            val pixels = size.width.toLong().coerceAtLeast(0L) * size.height.toLong().coerceAtLeast(0L)
            val megaPixels = pixels.toDouble() / 1_000_000.0
            return values().minByOrNull { abs(megaPixels - it.megaPixels) } ?: MP12
        }
    }
}

/**
 * Stable workload bucket - the Mondrian split shared by every predictor:
 *   Bokeh : DualBokeh output [SizeBucket]
 *   Filter: input [SizeBucket]
 *   Encoding/Tail/Saving: [SizeBucket] x image format (Saving also splits on the pending flag)
 *
 * Identity is the typed fields (it is a data class), so it can be used directly as a map key without
 * any string format to keep in sync; [toString] renders a compact form for logs only.
 */
data class WorkloadKey internal constructor(
    val category: WorkloadCategory,
    val sizeBucket: SizeBucket? = null,
    val imageFormat: Int? = null,
    val isPendingRequest: Boolean? = null,
    val nodeName: String? = null,
) {
    override fun toString(): String = buildString {
        append(category.tag)
        sizeBucket?.let { append("|").append(it.megaPixels).append("MP") }
        imageFormat?.let { append("|fmt=").append(it) }
        isPendingRequest?.let { append("|pending=").append(it) }
        nodeName?.let { append("|").append(it) }
    }

    companion object {
        private const val UNKNOWN_FORMAT = -1

        fun node(
            nodeId: NodeId,
            nodeParams: NodeParams,
            inputImageSize: Size,
        ): WorkloadKey {
            return when (WorkloadCategory.ofNode(nodeId)) {
                WorkloadCategory.BOKEH ->
                    WorkloadKey(WorkloadCategory.BOKEH, sizeBucket = SizeBucket.of(bokehOutputSize(nodeParams)))
                WorkloadCategory.FILTER ->
                    WorkloadKey(WorkloadCategory.FILTER, sizeBucket = SizeBucket.of(inputImageSize))
                WorkloadCategory.ENCODING ->
                    WorkloadKey(
                        WorkloadCategory.ENCODING,
                        sizeBucket = SizeBucket.of(inputImageSize),
                        imageFormat = encodingFormat(nodeParams),
                    )
                else ->
                    WorkloadKey(
                        WorkloadCategory.NODE,
                        sizeBucket = SizeBucket.of(inputImageSize),
                        nodeName = nodeId.name,
                    )
            }
        }

        fun tail(
            resultImageSize: Size,
            resultImageFormat: Int,
        ): WorkloadKey {
            return WorkloadKey(
                WorkloadCategory.TAIL,
                sizeBucket = SizeBucket.of(resultImageSize),
                imageFormat = resultImageFormat,
            )
        }

        fun saving(
            isPendingRequest: Boolean,
            resultImageSize: Size,
            resultImageFormat: Int,
        ): WorkloadKey {
            return WorkloadKey(
                WorkloadCategory.SAVING,
                sizeBucket = SizeBucket.of(resultImageSize),
                imageFormat = resultImageFormat,
                isPendingRequest = isPendingRequest,
            )
        }

        fun saving(savingExecutionMetrics: SavingExecutionMetrics): WorkloadKey {
            return saving(
                isPendingRequest = savingExecutionMetrics.isPendingRequest,
                resultImageSize = savingExecutionMetrics.resultImageSize,
                resultImageFormat = savingExecutionMetrics.resultImageFormat,
            )
        }

        /** Bokeh cost tracks the bokeh OUTPUT size, carried by [NodeParams.DualBokeh]. */
        private fun bokehOutputSize(nodeParams: NodeParams): Size {
            return (nodeParams as? NodeParams.DualBokeh)?.outputImageSize ?: Size(0, 0)
        }

        private fun encodingFormat(nodeParams: NodeParams): Int {
            return (nodeParams as? NodeParams.Encoding)?.encodingFormat ?: UNKNOWN_FORMAT
        }
    }
}

/**
 * Decision-level key for a stage + mandatory tail admission. Identity is the typed (stage, tail)
 * pair; [stageKey] is retained so predictors can fall back to the stage [WorkloadCategory].
 */
data class DecisionKey internal constructor(
    val stageKey: WorkloadKey,
    val tailKey: WorkloadKey,
) {
    override fun toString(): String = "$stageKey|$tailKey"

    companion object {
        fun combined(stageKey: WorkloadKey, tailKey: WorkloadKey): DecisionKey {
            return DecisionKey(stageKey, tailKey)
        }
    }
}

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
 * Drives one node's lifecycle in two steps:
 *
 *   1. [profileNodeExecution] - reads device state, builds [PreExecutionMetrics], predicts (admission
 *      stages only), records the metrics (+ prediction) onto [DraftSequenceMetrics], and returns a
 *      [DraftSequenceExecutionSession].
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
     * Step 1 (node): record this node and return a session whose [DraftSequenceExecutionSession.complete]
     * feeds the observed duration into the per-stage model.
     *
     * Only an admission stage (see [WorkloadCategory.isAdmissionStage]) predicts: its
     * [DraftSequenceExecutionSession.shouldRun] is the COMBINED upper bound - this stage plus the
     * mandatory Encoding + Saving tail (keyed by the capture's final result image size/format) -
     * against the remaining budget, so the gate reflects what actually has to finish in time, not the
     * stage alone. Every other node skips prediction and reports shouldRun == true, so the caller runs
     * it unconditionally and only its cost is learned.
     *
     * @param nodeId node identifier (model bucket key).
     * @param nodeParams node-specific pre-execution params (e.g. encoding format, bokeh output size).
     * @param timeoutMs absolute deadline for this node; the remaining budget is derived at read time.
     * @param inputImageSize input image dimensions.
     * @param draftMetrics draft metrics to append this node's record to.
     */
    @JvmOverloads
    fun profileNodeExecution(
        captureMetrics: CaptureMetrics,
        nodeId: NodeId,
        nodeParams: NodeParams = NodeParams.None,
        timeoutMs: Long,
        inputImageSize: Size,
        draftMetrics: DraftSequenceMetrics = captureMetrics.ensureDraftSequenceMetrics(),
    ): DraftSequenceExecutionSession {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val prediction = if (WorkloadCategory.ofNode(nodeId).isAdmissionStage) {
            predictor.predictCombinedAdmission(
                stageNodeId = nodeId,
                stageNodeParams = nodeParams,
                stageInputImageSize = inputImageSize,
                tailResultImageSize = captureMetrics.resultImageSize,
                tailResultImageFormat = captureMetrics.resultImageFormat,
                preExecutionMetrics = preExecutionMetrics,
            )
        } else {
            null
        }

        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeId = nodeId,
            nodeParams = nodeParams,
            inputImageSize = inputImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )
        synchronized(draftMetrics) {
            draftMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
            if (prediction != null) {
                draftMetrics.nodeExecutionPredictionList += prediction
            }
        }

        return DraftSequenceExecutionSession(
            executionPrediction = prediction,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
            onComplete = { predictor.updateNodeExecution(nodeExecutionMetrics) },
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
 * Handle returned by [DraftSequenceExecutionProfiler.profileNodeExecution].
 *
 * GC / CPU / wall-clock baselines are captured at construction time, i.e. right after prediction, so
 * the caller should run the work immediately after deciding [shouldRun]. Call [complete] exactly
 * once, only if the work actually ran.
 */
class DraftSequenceExecutionSession internal constructor(
    val executionPrediction: ExecutionPrediction?,
    private val postExecutionMetrics: PostExecutionMetrics,
    private val onComplete: () -> Unit,
) {
    /** True when there is no admission gate (update-only), or the upper bound fits within the budget. */
    val shouldRun: Boolean = executionPrediction?.admit ?: true

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
