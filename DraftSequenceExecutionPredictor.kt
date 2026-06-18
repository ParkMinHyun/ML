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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Base for every draft-sequence execution predictor.
 *
 * This class owns what is common to *all* predictors and independent of how any one models cost:
 *   - the workload taxonomy (how a node / saving maps to a stable [WorkloadKey]),
 *   - admission as a sum of independent per-workload upper bounds ([predictAdmission]), and
 *   - history replay ([warmUpFromHistory]).
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
                savingExecutionMetrics.resultImageSize,
                savingExecutionMetrics.resultImageFormat,
                savingExecutionMetrics.isPendingRequest,
            ),
            postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
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

        fun saving(resultImageSize: Size, imageFormat: Int, isPendingRequest: Boolean): WorkloadKey {
            return Saving(SizeBucket.of(resultImageSize), imageFormat, isPendingRequest)
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
 * Construct one per draft sequence with the plan (the admission stages that will run, in order, plus
 * whether the save is a pending request); the default [predictor] is the process-wide
 * [DraftSequenceExecutionPredictor.instance] predictor, so the learned model persists.
 */
private const val TAG = "DraftSequenceExecutionProfiler"

class DraftSequenceExecutionProfiler @JvmOverloads constructor(
    private val deviceStateReader: DeviceStateReader,
    private val plannedAdmissionStages: List<NodeId>,
    private val savingIsPendingRequest: Boolean,
    private val predictor: DraftSequenceExecutionPredictor = DraftSequenceExecutionPredictor.instance,
) {

    /**
     * Step 1 (node): record this node and return a session whose [DraftSequenceExecutionSession.complete]
     * feeds the observed duration into the per-stage model.
     *
     * Only an admission stage (see [WorkloadKey.isAdmissionStageNode]) predicts: its
     * [DraftSequenceExecutionSession.shouldRun] is the sum of upper bounds - this stage, the admission
     * stages still ahead of it (from the constructor's plan), and the mandatory Encoding + Saving - against
     * the remaining budget, so the gate reflects what actually has to finish in time, not the stage
     * alone. Every other node skips prediction and reports shouldRun == true, so the caller runs it
     * unconditionally and only its cost is learned.
     *
     * @param nodeId node identifier (model bucket key).
     * @param timeoutMs absolute deadline for this node; the remaining budget is derived at read time.
     * @param inputImageSize input image dimensions.
     * @param resultImageSize final capture result image dimensions for this node output and Encoding/Saving keys.
     * @param resultImageFormat final capture result image format for the Encoding/Saving keys.
     * @param draftMetrics draft metrics to append this node's record to.
     */
    fun profileNodeExecution(
        nodeId: NodeId,
        timeoutMs: Long,
        inputImageSize: Size,
        resultImageSize: Size,
        resultImageFormat: Int,
        draftMetrics: DraftSequenceMetrics,
    ): DraftSequenceExecutionSession {
        val deviceState = deviceStateReader.read()
        val preExecutionMetrics = PreExecutionMetrics(
            budgetMs = timeoutMs - SystemClock.uptimeMillis(),
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )

        val mandatoryKeys = listOf(
            WorkloadKey.encoding(resultImageSize, resultImageFormat),
            WorkloadKey.saving(resultImageSize, resultImageFormat, savingIsPendingRequest),
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
            val mandatoryUpperBoundMs =
                predictor.predictAdmission(mandatoryKeys, preExecutionMetrics).predictedUpperBoundMs
            timeoutMs - mandatoryUpperBoundMs.coerceAtLeast(0L)
        } else {
            null
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
            CLog.i(TAG, "[mhyun2.park] watchdog - nodeId=$nodeId, deadlineMs=$watchdogDeadlineMs")
        }

        return DraftSequenceExecutionSession(
            executionPrediction = prediction,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
            onComplete = { predictor.updateNodeExecution(nodeExecutionMetrics, resultImageFormat) },
            watchdogDeadlineMs = watchdogDeadlineMs,
        )
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
    private var watchdogTimedOut = false

    /** Time from start until the mandatory tail must begin; 0 when no watchdog is set. */
    private val delayMs: Long =
        watchdogDeadlineMs?.let { (it - startedAtMs).coerceAtLeast(0L) } ?: 0L

    /**
     * Single-shot watchdog: at the deadline, if the work has not finished, flag the timeout and
     * notify [watchdogTimeoutCallback]; [complete]/[abort] cancel it. Currently armed but inert - the
     * profiler passes no callback (the Node.java wiring is commented out). When re-enabled, also gate
     * arming on shouldRun and a non-null callback.
     */
    private val watchdogJob: Job? = watchdogDeadlineMs?.let {
        CoroutineScope(Dispatchers.Default).launch {
            delay(delayMs)
            val timedOut = synchronized(this@DraftSequenceExecutionSession) {
                if (completed) false else { watchdogTimedOut = true; true }
            }
            if (timedOut) {
                CLog.e(TAG, "[mhyun2.park] watchdog timer - onTimeout")
                watchdogTimeoutCallback?.onTimeout(this@DraftSequenceExecutionSession)
            }
        }
    }

    fun isWatchdogTimedOut(): Boolean = watchdogTimedOut
    fun getDelayMs(): Long = delayMs

    /**
     * Step 3: mark this session as cancelled without learning from it. Call this when the caller
     * skips the work or switches to fallback after the watchdog callback.
     */
    fun abort() {
        markCompleted()
    }

    /**
     * Step 3: fill [PostExecutionMetrics] from the elapsed GC / CPU / duration and correct the
     * model. Call this only after the work has actually run, exactly once.
     */
    fun complete() {
        markCompleted()
        postExecutionMetrics.durationMs = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
        postExecutionMetrics.gcSnapshot = gcTracker.delta()
        postExecutionMetrics.cpuProcessingSnapshot = cpuProcessingTracker.delta()
        onComplete()
    }

    /** Marks the session done exactly once and stops the watchdog. */
    private fun markCompleted() {
        synchronized(this) {
            check(!completed) { "DraftSequenceExecutionSession already completed." }
            completed = true
        }
        watchdogJob?.cancel()
    }
}
