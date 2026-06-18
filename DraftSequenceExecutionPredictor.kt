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
import kotlinx.coroutines.delay
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

    /** Predicts a decision-level (one or more stages + mandatory tail) cost for admission. */
    protected abstract fun predictForDecision(
        stageWorkloadKeys: List<WorkloadKey>,
        tailWorkloadKey: WorkloadKey,
        decisionKey: DecisionKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction

    /** Corrects the decision-level model from the combined observed outcome. */
    protected abstract fun updateForDecision(
        decisionKey: DecisionKey,
        predictedCombinedDurationMs: Long,
        predictedCombinedUpperBoundMs: Long,
        actualAdmissionStagesDurationMs: Long,
        actualEncodingDurationMs: Long,
        actualSavingDurationMs: Long,
    )

    // ---- Node ----

    fun updateNodeExecution(nodeExecutionMetrics: NodeExecutionMetrics, imageFormat: Int) {
        updateForKey(
            workloadKey = WorkloadKey.node(
                nodeExecutionMetrics.nodeId,
                nodeExecutionMetrics.inputImageSize,
                nodeExecutionMetrics.outputImageSize,
                imageFormat,
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

    // ---- Tail ----

    /** Predicts Encoding + Saving tail only. Used by admission and by stage watchdog deadlines. */
    fun predictTailExecution(
        tailResultImageSize: Size,
        tailResultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictForKey(
            workloadKey = WorkloadKey.tail(tailResultImageSize, tailResultImageFormat),
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    private fun updateTailExecution(
        tailResultImageSize: Size,
        tailResultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
        actualEncodingDurationMs: Long,
        actualSavingDurationMs: Long,
    ): Boolean {
        val actualTailDurationMs = actualEncodingDurationMs.coerceAtLeast(0L) +
                actualSavingDurationMs.coerceAtLeast(0L)
        if (actualTailDurationMs <= 0L) {
            return false
        }

        updateForKey(
            workloadKey = WorkloadKey.tail(tailResultImageSize, tailResultImageFormat),
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics().apply {
                durationMs = actualTailDurationMs
            },
        )
        return true
    }

    // ---- Combined (one or more stages + mandatory tail) admission ----

    /**
     * Predicts one or more stages + mandatory tail together for stage admission:
     *     elapsedSoFar + predictedUpperBound(stages + tail) <= totalBudget
     *
     * The primary stage is [stageNodeId]. Optional [additionalAdmissionStages] can be used when an
     * earlier admission decision must include a following admission-gated stage too. For example, a
     * Bokeh decision can include Filter by passing NODE_SEC_FILTER as an additional stage, making the
     * decision cover Bokeh + Filter + Encoding + Saving.
     *
     * The tail means Encoding + Saving; correct it later via [updateCombinedAdmission].
     */
    fun predictCombinedAdmission(
        stageNodeId: NodeId,
        stageInputImageSize: Size,
        stageOutputImageSize: Size,
        tailResultImageSize: Size,
        tailResultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
        additionalAdmissionStages: List<AdmissionStageSpec> = emptyList(),
    ): ExecutionPrediction {
        val stageKeys = buildStageWorkloadKeys(
            stageNodeId = stageNodeId,
            stageInputImageSize = stageInputImageSize,
            stageOutputImageSize = stageOutputImageSize,
            imageFormat = tailResultImageFormat,
            additionalAdmissionStages = additionalAdmissionStages,
        )
        val tailKey = WorkloadKey.tail(tailResultImageSize, tailResultImageFormat)

        return predictForDecision(
            stageWorkloadKeys = stageKeys,
            tailWorkloadKey = tailKey,
            decisionKey = DecisionKey.combined(stageKeys, tailKey),
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    fun updateCombinedAdmission(
        stageNodeId: NodeId,
        stageInputImageSize: Size,
        stageOutputImageSize: Size,
        tailResultImageSize: Size,
        tailResultImageFormat: Int,
        predictedCombinedDurationMs: Long,
        predictedCombinedUpperBoundMs: Long,
        actualStageDurationMs: Long,
        actualEncodingDurationMs: Long,
        actualSavingDurationMs: Long,
        additionalAdmissionStages: List<AdmissionStageSpec> = emptyList(),
    ) {
        val stageKeys = buildStageWorkloadKeys(
            stageNodeId = stageNodeId,
            stageInputImageSize = stageInputImageSize,
            stageOutputImageSize = stageOutputImageSize,
            imageFormat = tailResultImageFormat,
            additionalAdmissionStages = additionalAdmissionStages,
        )
        val tailKey = WorkloadKey.tail(tailResultImageSize, tailResultImageFormat)

        updateForDecision(
            decisionKey = DecisionKey.combined(stageKeys, tailKey),
            predictedCombinedDurationMs = predictedCombinedDurationMs,
            predictedCombinedUpperBoundMs = predictedCombinedUpperBoundMs,
            actualAdmissionStagesDurationMs = actualStageDurationMs,
            actualEncodingDurationMs = actualEncodingDurationMs,
            actualSavingDurationMs = actualSavingDurationMs,
        )
    }

    private fun buildStageWorkloadKeys(
        stageNodeId: NodeId,
        stageInputImageSize: Size,
        stageOutputImageSize: Size,
        imageFormat: Int,
        additionalAdmissionStages: List<AdmissionStageSpec>,
    ): List<WorkloadKey> {
        val stageKeys = mutableListOf(
            WorkloadKey.node(
                nodeId = stageNodeId,
                inputImageSize = stageInputImageSize,
                outputImageSize = stageOutputImageSize,
                imageFormat = imageFormat,
            )
        )

        additionalAdmissionStages.forEach { stage ->
            stageKeys += WorkloadKey.node(
                nodeId = stage.nodeId,
                inputImageSize = stage.inputImageSize,
                outputImageSize = stage.outputImageSize,
                imageFormat = imageFormat,
            )
        }

        return stageKeys
    }

    /**
     * Corrects the combined-admission model from one completed draft sequence: pairs each admission
     * stage (see [WorkloadCategory.isAdmissionStage]) with the mandatory Encoding + Saving tail and
     * feeds its combined residual. For Bokeh, if a Filter admission stage also ran later in the same
     * draft, the Bokeh decision is corrected as Bokeh + Filter + Encoding + Saving.
     *
     * No-op unless the admission stage(s), the encoding node, and saving all actually ran (a skipped
     * stage or a censored fallback leaves their durations at 0). Returns the number of samples fed.
     */
    fun updateCombinedAdmissions(captureMetrics: CaptureMetrics): Int {
        val draftMetrics = captureMetrics.draftSequenceMetrics ?: return 0

        val admissionStages = draftMetrics.nodeExecutionMetricsList.filter {
            WorkloadCategory.ofNode(it.nodeId).isAdmissionStage
        }
        if (admissionStages.isEmpty()) {
            return 0
        }

        val encoding = draftMetrics.nodeExecutionMetricsList.firstOrNull {
            WorkloadCategory.ofNode(it.nodeId) == WorkloadCategory.ENCODING
        } ?: return 0

        val saving = draftMetrics.savingExecutionMetrics ?: return 0

        val encodingDurationMs = encoding.postExecutionMetrics.durationMs
        val savingDurationMs = saving.postExecutionMetrics.durationMs
        if (encodingDurationMs <= 0L || savingDurationMs <= 0L) {
            return 0
        }

        var updatedCount = 0
        if (updateTailExecution(
                tailResultImageSize = captureMetrics.resultImageSize,
                tailResultImageFormat = captureMetrics.resultImageFormat,
                preExecutionMetrics = encoding.preExecutionMetrics,
                actualEncodingDurationMs = encodingDurationMs,
                actualSavingDurationMs = savingDurationMs,
            )
        ) {
            updatedCount++
        }
        val predictions = draftMetrics.nodeExecutionPredictionList

        admissionStages.forEachIndexed { index, stage ->
            val prediction = predictions.getOrNull(index) ?: return@forEachIndexed
            val stageCategory = WorkloadCategory.ofNode(stage.nodeId)

            val additionalStageMetrics = if (stageCategory == WorkloadCategory.BOKEH) {
                admissionStages
                    .drop(index + 1)
                    .filter { WorkloadCategory.ofNode(it.nodeId) == WorkloadCategory.FILTER }
            } else {
                emptyList()
            }

            val admissionStageMetrics = listOf(stage) + additionalStageMetrics
            if (admissionStageMetrics.any { it.postExecutionMetrics.durationMs <= 0L }) {
                return@forEachIndexed
            }

            val admissionStagesDurationMs = admissionStageMetrics.sumOf {
                it.postExecutionMetrics.durationMs
            }

            updateCombinedAdmission(
                stageNodeId = stage.nodeId,
                stageInputImageSize = stage.inputImageSize,
                stageOutputImageSize = stage.outputImageSize,
                tailResultImageSize = captureMetrics.resultImageSize,
                tailResultImageFormat = captureMetrics.resultImageFormat,
                predictedCombinedDurationMs = prediction.predictedDurationMs,
                predictedCombinedUpperBoundMs = prediction.predictedUpperBoundMs,
                actualStageDurationMs = admissionStagesDurationMs,
                actualEncodingDurationMs = encodingDurationMs,
                actualSavingDurationMs = savingDurationMs,
                additionalAdmissionStages = additionalStageMetrics.map {
                    AdmissionStageSpec(
                        nodeId = it.nodeId,
                        inputImageSize = it.inputImageSize,
                        outputImageSize = it.outputImageSize,
                    )
                },
            )
            updatedCount++
        }

        return updatedCount
    }

    fun updateCombinedAdmission(captureMetrics: CaptureMetrics): Boolean {
        return updateCombinedAdmissions(captureMetrics) > 0
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
                    updateNodeExecution(node, captureMetrics.resultImageFormat)
                    updatedCount++
                }
            }

            draftMetrics.savingExecutionMetrics?.let { saving ->
                if (saving.postExecutionMetrics.durationMs > 0L) {
                    updateSavingExecution(saving)
                    updatedCount++
                }
            }

            updatedCount += updateCombinedAdmissions(captureMetrics)
        }
        return updatedCount
    }

    companion object {
        private const val TAG = "DraftSequenceExecutionPredictor"

        /**
         * Process-wide instance. The predictor's learned state lives here, so profilers created
         * per capture keep accumulating across captures instead of cold-starting every time.
         */
        @JvmStatic
        val instance: DraftSequenceExecutionPredictor = EwmaDraftSequenceExecutionPredictor()

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
                    CLog.i(TAG, "[mhyun2.park] warmUp start")
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

/** Extra stage included in a combined admission decision. */
data class AdmissionStageSpec(
    val nodeId: NodeId,
    val inputImageSize: Size,
    val outputImageSize: Size,
)

/**
 * Coarse workload family a node / saving / tail maps to. This is the grouping level the predictors
 * fall back to when an exact [WorkloadKey] cell does not yet have enough samples.
 */
enum class WorkloadCategory {
    BOKEH,
    FILTER,
    ENCODING,
    TAIL,
    SAVING,
    NODE;

    /**
     * Whether this family is gated on a combined admission bound rather than run unconditionally.
     * Single source of truth for both the profiler (whether to predict) and the combined-admission
     * learner (which node is the stage).
     */
    val isAdmissionStage: Boolean
        get() = this == BOKEH || this == FILTER

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
    MP108(108),
    MP200(200);

    companion object {
        fun of(size: Size): SizeBucket {
            val pixels = size.width.toLong().coerceAtLeast(0L) * size.height.toLong().coerceAtLeast(0L)
            val megaPixels = pixels.toDouble() / 1_000_000.0
            return entries.toTypedArray().minByOrNull { abs(megaPixels - it.megaPixels) } ?: MP12
        }
    }
}

/**
 * Stable workload bucket shared by every predictor:
 *   Bokeh : DualBokeh output [SizeBucket]
 *   Filter: input [SizeBucket]
 *   Encoding/Tail/Saving: [SizeBucket] x image format (Saving also splits on the pending flag)
 *
 * Identity is the typed fields of each subtype, so it can be used directly as a map key without any
 * string format to keep in sync.
 */
sealed interface WorkloadKey {
    val category: WorkloadCategory

    data class Bokeh(val sizeBucket: SizeBucket) : WorkloadKey {
        override val category: WorkloadCategory = WorkloadCategory.BOKEH
    }

    data class Filter(val sizeBucket: SizeBucket) : WorkloadKey {
        override val category: WorkloadCategory = WorkloadCategory.FILTER
    }

    data class Node(val nodeId: NodeId, val sizeBucket: SizeBucket) : WorkloadKey {
        override val category: WorkloadCategory = WorkloadCategory.NODE
    }

    data class Encoding(val sizeBucket: SizeBucket, val imageFormat: Int) : WorkloadKey {
        override val category: WorkloadCategory = WorkloadCategory.ENCODING
    }

    data class Saving(val sizeBucket: SizeBucket, val imageFormat: Int, val isPendingRequest: Boolean) : WorkloadKey {
        override val category: WorkloadCategory = WorkloadCategory.SAVING
    }

    data class Tail(
        val sizeBucket: SizeBucket,
        val imageFormat: Int,
    ) : WorkloadKey {
        override val category: WorkloadCategory = WorkloadCategory.TAIL
    }

    companion object {
        fun node(
            nodeId: NodeId,
            inputImageSize: Size,
            outputImageSize: Size,
            imageFormat: Int,
        ): WorkloadKey {
            return when (WorkloadCategory.ofNode(nodeId)) {
                WorkloadCategory.BOKEH -> Bokeh(SizeBucket.of(outputImageSize))
                WorkloadCategory.FILTER -> Filter(SizeBucket.of(inputImageSize))
                WorkloadCategory.ENCODING -> Encoding(
                    sizeBucket = SizeBucket.of(inputImageSize),
                    imageFormat = imageFormat,
                )
                else -> throw IllegalArgumentException("not supported node type($nodeId)")
            }
        }

        fun tail(
            resultImageSize: Size,
            resultImageFormat: Int,
        ): WorkloadKey {
            return Tail(
                sizeBucket = SizeBucket.of(resultImageSize),
                imageFormat = resultImageFormat,
            )
        }

        fun saving(savingExecutionMetrics: SavingExecutionMetrics): WorkloadKey {
            return Saving(
                isPendingRequest = savingExecutionMetrics.isPendingRequest,
                sizeBucket = SizeBucket.of(savingExecutionMetrics.resultImageSize),
                imageFormat = savingExecutionMetrics.resultImageFormat
            )
        }
    }
}

/**
 * Decision-level key for one or more stages + mandatory tail admission. Identity is the typed
 * (stage list, tail) pair, so Bokeh + Tail and Bokeh + Filter + Tail learn separate residuals.
 */
@ConsistentCopyVisibility
data class DecisionKey internal constructor(
    val stageKeys: List<WorkloadKey>,
    val tailKey: WorkloadKey,
) {

    init {
        require(stageKeys.isNotEmpty()) { "DecisionKey requires at least one stage key." }
    }

    companion object {
        fun combined(stageKey: WorkloadKey, tailKey: WorkloadKey): DecisionKey {
            return DecisionKey(listOf(stageKey), tailKey)
        }

        fun combined(stageKeys: List<WorkloadKey>, tailKey: WorkloadKey): DecisionKey {
            return DecisionKey(stageKeys.toList(), tailKey)
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
 * [DraftSequenceExecutionPredictor.instance] predictor, so the learned model persists.
 */
private const val TAG = "DraftSequenceExecutionProfiler"

class DraftSequenceExecutionProfiler @JvmOverloads constructor(
    private val deviceStateReader: DeviceStateReader,
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictor.instance,
) {


    /**
     * Step 1 (node): record this node and return a session whose [DraftSequenceExecutionSession.complete]
     * feeds the observed duration into the per-stage model.
     *
     * Only an admission stage (see [WorkloadCategory.isAdmissionStage]) predicts: its
     * [DraftSequenceExecutionSession.shouldRun] is the COMBINED upper bound - this stage plus any
     * [admissionAdditionalStages] plus the mandatory Encoding + Saving tail (keyed by the capture's
     * final result image size/format) - against the remaining budget, so the gate reflects what
     * actually has to finish in time, not the stage alone. Every other node skips prediction and
     * reports shouldRun == true, so the caller runs it unconditionally and only its cost is learned.
     *
     * @param nodeId node identifier (model bucket key).
     * @param timeoutMs absolute deadline for this node; the remaining budget is derived at read time.
     * @param inputImageSize input image dimensions.
     * @param resultImageSize final capture result image dimensions for this node output and mandatory tail key.
     * @param resultImageFormat final capture result image format for the mandatory tail key.
     * @param draftMetrics draft metrics to append this node's record to.
     * @param admissionAdditionalStages optional following admission stages to include in this decision.
     */
    fun profileNodeExecution(
        nodeId: NodeId,
        timeoutMs: Long,
        inputImageSize: Size,
        resultImageSize: Size,
        resultImageFormat: Int,
        draftMetrics: DraftSequenceMetrics,
        admissionAdditionalStages: List<AdmissionStageSpec> = emptyList(),
    ): DraftSequenceExecutionSession {
        val deviceState = deviceStateReader.read()
        val preExecutionMetrics = PreExecutionMetrics(
            budgetMs = timeoutMs - SystemClock.uptimeMillis(),
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )

        val isAdmissionStage = WorkloadCategory.ofNode(nodeId).isAdmissionStage
        val prediction = if (isAdmissionStage) {
            predictor.predictCombinedAdmission(
                stageNodeId = nodeId,
                stageInputImageSize = inputImageSize,
                stageOutputImageSize = resultImageSize,
                tailResultImageSize = resultImageSize,
                tailResultImageFormat = resultImageFormat,
                preExecutionMetrics = preExecutionMetrics,
                additionalAdmissionStages = admissionAdditionalStages,
            )
        } else {
            null
        }

        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeId = nodeId,
            inputImageSize = inputImageSize,
            outputImageSize = resultImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )

        val tailPrediction = if (isAdmissionStage) {
            predictor.predictTailExecution(
                tailResultImageSize = resultImageSize,
                tailResultImageFormat = resultImageFormat,
                preExecutionMetrics = preExecutionMetrics,
            )
        } else {
            null
        }
        val watchdogDeadlineMs = tailPrediction?.let {
            timeoutMs - it.predictedUpperBoundMs.coerceAtLeast(0L)
        }

        synchronized(draftMetrics) {
            draftMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
            if (prediction != null) {
                draftMetrics.nodeExecutionPredictionList += prediction
            }
        }

        if (prediction != null) {
            CLog.i(TAG, "[mhyun2.park] prediction - $prediction")
        }
        if (watchdogDeadlineMs != null) {
            CLog.i(TAG, "[mhyun2.park] watchdog - nodeId=$nodeId, deadlineMs=$watchdogDeadlineMs, " +
                        "tailUpperBoundMs=${tailPrediction.predictedUpperBoundMs}")
        }

        return DraftSequenceExecutionSession(
            executionPrediction = prediction,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
            onComplete = { predictor.updateNodeExecution(nodeExecutionMetrics, resultImageFormat) },
            watchdogDeadlineMs = watchdogDeadlineMs,
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
    private val watchdogDeadlineMs: Long? = null,
    private val watchdogTimeoutCallback: WatchdogTimeoutCallback? = null,
) {
    interface WatchdogTimeoutCallback {
        fun onTimeout(session: DraftSequenceExecutionSession)
    }

    /** True when there is no admission gate (update-only), or the upper bound fits within the budget. */
    val shouldRun: Boolean = executionPrediction?.admit ?: true

    private val gcTracker = GcTracker()
    private val cpuProcessingTracker = CpuProcessingTracker()
    private val startedAtMs = SystemClock.uptimeMillis()

    private var completed = false
    private var watchdogArmed = false
    private var watchdogTimedOut = false
    private var delayMs = 0L

    fun isWatchdogTimedOut(): Boolean = watchdogTimedOut
    fun getDelayMs(): Long = delayMs

    /**
     * Per-session watchdog resources. They are created only when this session actually arms a
     * watchdog, and are released when complete/abort is called or after the watchdog callback returns.
     */
    private var watchdogJob: kotlinx.coroutines.Job? = null

    init {
        armWatchdogIfNeeded()
    }

    private fun armWatchdogIfNeeded() {
        val deadlineMs = watchdogDeadlineMs ?: return
//        if (!shouldRun || watchdogTimeoutCallback == null) {
//            CLog.w(TAG, "[mhyun2.park] armWatchdogIfNeeded - shouldRun($shouldRun), watchdogTimeoutCallback($watchdogTimeoutCallback)")
//            return
//        }
        delayMs = (deadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)

        synchronized(this) {
            if (completed || watchdogTimedOut) {
                CLog.w(TAG, "[mhyun2.park] armWatchdogIfNeeded - completed($completed), watchdogTimedOut($watchdogTimedOut)")
                return
            }

            watchdogArmed = true
            watchdogJob = CoroutineScope(Dispatchers.Default).launch {
                delay(delayMs)

                val callback = synchronized(this@DraftSequenceExecutionSession) {
                    if (completed || watchdogTimedOut) {
                        null
                    } else {
                        watchdogTimedOut = true
                        watchdogArmed = false
                        watchdogTimeoutCallback
                    }
                }

                try {
                    if (callback != null) {
                        CLog.e(TAG, "[mhyun2.park] watchdog timer - onTimeout")
                        callback.onTimeout(this@DraftSequenceExecutionSession)
                    }
                } finally {
                    releaseWatchdogJob()
                }
            }
            CLog.i(TAG, "[mhyun2.park] armWatchdogIfNeeded - delayMs($delayMs)")
        }
    }

    private fun cancelWatchdogLocked() {
        watchdogJob?.cancel()
        watchdogArmed = false
    }

    private fun releaseWatchdogJob() {
        synchronized(this) {
            cancelWatchdogLocked()
            watchdogJob = null
        }
    }

    /**
     * Step 3: mark this session as cancelled without learning from it. Call this when the caller
     * skips the work or switches to fallback after the watchdog callback.
     */
    fun abort() {
        synchronized(this) {
            check(!completed) { "DraftSequenceExecutionSession.abort() called more than once." }
            completed = true
        }
        releaseWatchdogJob()
    }

    /**
     * Step 3: fill [PostExecutionMetrics] from the elapsed GC / CPU / duration and correct the
     * model. Call this only after the work has actually run, exactly once.
     */
    fun complete() {
        synchronized(this) {
            check(!completed) { "DraftSequenceExecutionSession.complete() called more than once." }
            completed = true

            postExecutionMetrics.durationMs = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
            postExecutionMetrics.gcSnapshot = gcTracker.delta()
            postExecutionMetrics.cpuProcessingSnapshot = cpuProcessingTracker.delta()
        }

        releaseWatchdogJob()
        onComplete()
    }
}
