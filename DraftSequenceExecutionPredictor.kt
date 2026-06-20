package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.SystemClock
import android.util.Size
import com.samsung.android.camera.core2.maker.MakerFeature
import com.samsung.android.camera.core2.node.NodeId
import com.samsung.android.camera.core2.util.CLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Objects
import kotlin.math.abs

/**
 * Base for every draft-sequence execution predictor.
 *
 * This class owns what is common to *all* predictors and independent of how any one models cost:
 *   - the workload taxonomy (how a node / saving maps to a stable [WorkloadKey]),
 *   - admission as a sum of independent per-workload upper bounds ([predictAdmission]), and
 *
 * A concrete predictor implements only the two model-specific hooks below; it never sees raw
 * nodeIds, image sizes, or formats - it receives the already-bucketed [WorkloadKey] and the
 * pre/post-execution metrics. To add a new predictor, subclass this and implement the hooks; all
 * routing, keying, and replay are inherited unchanged.
 */
abstract class DraftSequenceExecutionPredictor {

    // ---- Model-specific hooks (the only thing a concrete predictor must implement) ----

    /** Predicts one workload's point estimate and upper bound. */
    protected abstract fun predictWorkload(
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction

    /** Records one workload's observed cost. Pure observation: no prediction, no budget. */
    protected abstract fun updateWorkload(
        workloadKey: WorkloadKey,
        postExecutionMetrics: PostExecutionMetrics,
    )

    // ---- Admission: sum of independent per-workload upper bounds ----

    /**
     * Predicts a stage's admission as the sum of each listed workload's independent upper bound:
     *     admit  <=>  Σ upperBound(workload) <= budget
     *
     * The caller lists every workload that still has to finish in the draft sequence - the running
     * stage, the admission stages after it, and the mandatory Encoding + Saving - so the gate
     * reflects the whole remaining cost. A workload with no samples contributes 0, so admission stays
     * lenient until the model has learned.
     */
    fun predictAdmission(
        workloadKeys: List<WorkloadKey>,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        var sumDurationMs = 0L
        var sumUpperBoundMs = 0L
        workloadKeys.forEach { workloadKey ->
            val prediction = predictWorkload(workloadKey, preExecutionMetrics)
            sumDurationMs += prediction.predictedDurationMs
            sumUpperBoundMs += prediction.predictedUpperBoundMs
        }
        return ExecutionPrediction(
            admit = sumUpperBoundMs <= preExecutionMetrics.budgetMs,
            predictedDurationMs = sumDurationMs,
            predictedUpperBoundMs = sumUpperBoundMs,
        )
    }

    // ---- Per-workload learning ----

    fun updateNodeExecution(nodeExecutionMetrics: NodeExecutionMetrics, imageFormat: Int) {
        updateWorkload(
            workloadKey = WorkloadKey.node(
                nodeExecutionMetrics.nodeId,
                nodeExecutionMetrics.inputImageSize,
                nodeExecutionMetrics.outputImageSize,
                imageFormat,
            ),
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
        )
    }

    fun updateSavingExecution(savingExecutionMetrics: SavingExecutionMetrics) {
        updateWorkload(
            workloadKey = WorkloadKey.saving(
                savingExecutionMetrics.isPendingRequest,
                savingExecutionMetrics.resultImageSize,
                savingExecutionMetrics.resultImageFormat),
            postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
        )
    }

    companion object {

        /**
         * Process-wide instance. The predictor's learned state lives here, so profilers created
         * per capture keep accumulating across captures instead of cold-starting every time.
         */
        @JvmStatic
        val instance: DraftSequenceExecutionPredictor = EwmaDraftSequenceExecutionPredictor()
    }
}

/**
 * Stable megapixel tiers a frame snaps to - the size axis of the workload taxonomy.
 */
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
 *   Encoding/Saving: [SizeBucket] x image format (Saving also splits on the pending flag)
 *
 * Identity is the typed fields of each subtype, so it can be used directly as a map key without any
 * string format to keep in sync.
 */
sealed interface WorkloadKey {

    data class Bokeh(val sizeBucket: SizeBucket) : WorkloadKey

    data class Filter(val sizeBucket: SizeBucket) : WorkloadKey

    data class Encoding(val sizeBucket: SizeBucket, val imageFormat: Int) : WorkloadKey

    data class Saving(val sizeBucket: SizeBucket, val imageFormat: Int, val isPendingRequest: Boolean) : WorkloadKey

    companion object {
        /** Bokeh and Filter are gated on an admission bound; Encoding and Saving are mandatory. */
        fun isAdmissionStageNode(nodeId: NodeId): Boolean {
            return when (nodeId) {
                NodeId.NODE_SEC_V1_DUAL_BOKEH,
                NodeId.NODE_SEC_V1_1_DUAL_BOKEH,
                NodeId.NODE_SEC_V2_DUAL_BOKEH,
                NodeId.NODE_SEC_FILTER -> true
                else -> false
            }
        }

        fun node(
            nodeId: NodeId,
            inputImageSize: Size,
            outputImageSize: Size,
            imageFormat: Int,
        ): WorkloadKey {
            return when (nodeId) {
                NodeId.NODE_SEC_V1_DUAL_BOKEH,
                NodeId.NODE_SEC_V1_1_DUAL_BOKEH,
                NodeId.NODE_SEC_V2_DUAL_BOKEH -> Bokeh(SizeBucket.of(outputImageSize))
                NodeId.NODE_SEC_FILTER -> Filter(SizeBucket.of(inputImageSize))
                NodeId.NODE_SEC_V2_IMAGE_CODEC -> Encoding(SizeBucket.of(inputImageSize), imageFormat)
                else -> throw IllegalArgumentException("not supported node type($nodeId)")
            }
        }

        fun encoding(resultImageSize: Size, imageFormat: Int): WorkloadKey {
            return Encoding(SizeBucket.of(resultImageSize), imageFormat)
        }

        fun saving(isPendingRequest: Boolean, resultImageSize: Size, resultImageFormat: Int): WorkloadKey {
            return Saving(
                isPendingRequest = isPendingRequest,
                sizeBucket = SizeBucket.of(resultImageSize),
                imageFormat = resultImageFormat,
            )
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
 * Create one per draft sequence and call [setDraftPlan] at the start so each node's admission
 * decision can sum the upper bounds of the workloads still ahead of it; the default [predictor] is
 * the process-wide [DraftSequenceExecutionPredictor.instance] predictor, so the learned model persists.
 */
private const val TAG = "DraftSequenceExecutionProfiler"

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

    private var plannedAdmissionStages: List<NodeId> = emptyList()
    private lateinit var savingExecutionSession: DraftSequenceExecutionSession

    /**
     * Draft-start configuration, set once before the first [profileNodeExecution]:
     *   - [admissionStages]: the ordered admission-gated stages (e.g. Bokeh, Filter) that will run;
     *     the mandatory Encoding + Saving are implicit and must not be listed.
     *
     * Lets each [profileNodeExecution] sum the upper bounds of every workload still ahead of the
     * running node, instead of the caller threading them in per node. NodeIds the predictor does not
     * yet model as admission stages (e.g. a future Watermark) are stored but stay ignored until
     * [WorkloadKey.isAdmissionStageNode] covers them.
     */
    fun setDraftPlan(admissionStages: List<NodeId>) {
        plannedAdmissionStages = admissionStages.toList()
    }

    /**
     * Step 1 (node): record this node and return a session whose [DraftSequenceExecutionSession.complete]
     * feeds the observed duration into the per-stage model.
     *
     * Only an admission stage (see [WorkloadKey.isAdmissionStageNode]) predicts: its
     * [DraftSequenceExecutionSession.shouldRun] is the sum of upper bounds - this stage, the admission
     * stages still ahead of it (from [setDraftPlan]), and the mandatory Encoding + Saving - against
     * the remaining budget, so the gate reflects what actually has to finish in time, not the stage
     * alone. Every other node skips prediction and reports shouldRun == true, so the caller runs it
     * unconditionally and only its cost is learned.
     *
     * @param nodeId node identifier (model bucket key).
     * @param inputImageSize input image dimensions.
     */
    fun profileNodeExecution(nodeId: NodeId, inputImageSize: Size): DraftSequenceExecutionSession {
        val deviceState = deviceStateReader.read()
        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs ?: (SystemClock.uptimeMillis() + MakerFeature.CAPTURE_TIMEOUT_MS)
        val preExecutionMetrics = PreExecutionMetrics(
            budgetMs = timeoutTimestampMs - SystemClock.uptimeMillis(),
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )

        val resultImageSize = captureMetrics.resultImageSize
        val resultImageFormat = captureMetrics.resultImageFormat
        val mandatoryKeys = listOf(
            WorkloadKey.encoding(resultImageSize, resultImageFormat),
            WorkloadKey.saving(isPendingRequest, resultImageSize, resultImageFormat),
        )

        val isAdmissionStage = WorkloadKey.isAdmissionStageNode(nodeId)
        val prediction = if (isAdmissionStage) {
            val stageKeys = (listOf(nodeId) + followingAdmissionStages(nodeId)).map {
                WorkloadKey.node(it, inputImageSize, resultImageSize, resultImageFormat)
            }
            predictor.predictAdmission(stageKeys + mandatoryKeys, preExecutionMetrics)
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

        // Watchdog: the latest moment the mandatory Encoding + Saving can still start and meet budget.
        val watchdogDeadlineMs = if (isAdmissionStage) {
            val mandatoryUpperBoundMs = predictor.predictAdmission(mandatoryKeys, preExecutionMetrics).predictedUpperBoundMs
            timeoutTimestampMs - mandatoryUpperBoundMs.coerceAtLeast(0L)
        } else {
            null
        }

        val draftSequenceMetrics = captureMetrics.draftSequenceMetrics!!
        synchronized(draftSequenceMetrics) {
            draftSequenceMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
            if (prediction != null) {
                draftSequenceMetrics.nodeExecutionPredictionList += prediction
            }
        }

        if (prediction != null) {
            CLog.i(TAG, "[mhyun2.park] prediction - $prediction")
        }
        if (watchdogDeadlineMs != null) {
            CLog.i(TAG, "[mhyun2.park] watchdog - nodeId=$nodeId, deadlineMs=$watchdogDeadlineMs")
        }

        return DraftSequenceExecutionSession(
            executionPrediction = prediction,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
            onComplete = { predictor.updateNodeExecution(nodeExecutionMetrics, resultImageFormat) },
            watchdogDeadlineMs = watchdogDeadlineMs,
        )
    }

    fun profileSavingExecution() {
        val deviceStateSnapshot = deviceStateReader.read()

        val savingStartTimestampMs = SystemClock.uptimeMillis()
        val timeoutTimestampMs = captureMetrics.timeoutTimestampMs ?: (savingStartTimestampMs + MakerFeature.CAPTURE_TIMEOUT_MS)
        val budgetMs: Long = timeoutTimestampMs - savingStartTimestampMs

        val postExecutionMetrics = PostExecutionMetrics()
        val savingExecutionMetrics = SavingExecutionMetrics(
            isPendingRequest,
            captureMetrics.resultImageSize,
            captureMetrics.resultImageFormat,
            PreExecutionMetrics(
                budgetMs,
                deviceStateSnapshot.memorySnapshot,
                deviceStateSnapshot.thermalSnapshot,
                deviceStateSnapshot.storageSnapshot
            ),
            postExecutionMetrics
        )
        draftSequenceMetrics.savingExecutionMetrics = savingExecutionMetrics
        savingExecutionSession = DraftSequenceExecutionSession(
            executionPrediction = null,
            postExecutionMetrics = postExecutionMetrics,
            onComplete = { predictor.updateSavingExecution(savingExecutionMetrics) },
        )
    }

    /**
     * End of the saving stage. Fills the saving [PostExecutionMetrics] and feeds the observed cost to
     * the predictor (only when saving was profiled), then records whether the capture overran its
     * timeout onto [DraftSequenceMetrics.isTimeout] and returns it.
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

    /**
     * The admission stages planned to run after [nodeId]. Empty when [nodeId] is not in the planned
     * sequence; unmodeled NodeIds are dropped so only keyable admission stages remain.
     */
    private fun followingAdmissionStages(nodeId: NodeId): List<NodeId> {
        val index = plannedAdmissionStages.indexOf(nodeId)
        if (index < 0) {
            return emptyList()
        }
        return plannedAdmissionStages.drop(index + 1)
            .filter { WorkloadKey.isAdmissionStageNode(it) }
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
