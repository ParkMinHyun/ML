package com.samsung.android.camera.core2.ml

import android.content.Context
import android.os.SystemClock
import android.util.Size
import com.samsung.android.camera.core2.util.CLog
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface DraftSequenceExecutionPredictor {
    val name: String

    fun predict(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction

    fun update(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    )
}

/**
 * Baseline predictor: one EWMA of raw observed durations per [executionKey] — no workload
 * bucketing and no device-state slowdown multipliers. A key's first execution is always
 * admitted (zero-cost prediction) and the model learns from its observed duration.
 */
class EwmaDraftSequenceExecutionPredictor @JvmOverloads constructor(
    private val ewmaAlpha: Double = 0.20,
    private val upperBoundErrorScale: Double = 1.64,
    private val minimumErrorMarginMs: Long = 80L,
) : DraftSequenceExecutionPredictor {

    override val name: String = "draft_sequence_execution_ewma"

    private val statsByKey: MutableMap<String, EwmaStats> = mutableMapOf()

    @Synchronized
    override fun predict(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val budgetMs = preExecutionMetrics.budgetMs
        val stats = statsByKey[executionKey]

        if (stats == null || stats.count == 0) {
            return ExecutionPrediction(
                predictedDurationMs = 0L,
                predictedUpperBoundMs = 0L,
                confidence = confidenceFromCount(0),
                reason = "key=$executionKey count=0 budgetMs=$budgetMs shouldRun=${budgetMs >= 0L} (cold start)",
            )
        }

        val predictedMs = stats.ewmaMs
        val errorMarginMs = max(
            minimumErrorMarginMs.toDouble(),
            stats.ewmaAbsErrorMs * upperBoundErrorScale,
        )
        val predictedDurationMs = predictedMs.roundToLong()
        val predictedUpperBoundMs = (predictedMs + errorMarginMs).roundToLong()
        val reason = buildString {
            append("key=").append(executionKey)
            append(" count=").append(stats.count)
            append(" budgetMs=").append(budgetMs)
            append(" slackMs=").append(budgetMs - predictedUpperBoundMs)
            append(" shouldRun=").append(predictedUpperBoundMs <= budgetMs)
        }

        return ExecutionPrediction(
            predictedDurationMs = predictedDurationMs,
            predictedUpperBoundMs = predictedUpperBoundMs,
            confidence = confidenceFromCount(stats.count),
            reason = reason,
        )
    }

    @Synchronized
    override fun update(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        val durationMs = postExecutionMetrics.durationMs.coerceAtLeast(0L)
        if (durationMs <= 0L) {
            return
        }

        statsByKey.getOrPut(executionKey) { EwmaStats() }
            .update(durationMs.toDouble(), ewmaAlpha)
    }

    private fun confidenceFromCount(count: Int): Float {
        return (count.toFloat() / (count + WARMUP_COUNT).toFloat())
            .coerceIn(0.05f, 0.90f)
    }

    private companion object {
        private const val WARMUP_COUNT = 12
    }
}

private class EwmaStats {
    var count: Int = 0
        private set
    var ewmaMs: Double = 0.0
        private set
    var ewmaAbsErrorMs: Double = 120.0
        private set

    fun update(observedMs: Double, alpha: Double) {
        if (count == 0) {
            ewmaMs = observedMs
            ewmaAbsErrorMs = observedMs * 0.25
        } else {
            val previous = ewmaMs
            ewmaMs = alpha * observedMs + (1.0 - alpha) * ewmaMs
            ewmaAbsErrorMs = alpha * abs(observedMs - previous) + (1.0 - alpha) * ewmaAbsErrorMs
        }
        count++
    }
}

/** Owns a [DraftSequenceExecutionPredictor] and routes predictions / completion updates to it. */
class DraftSequenceExecutionPredictionManager @JvmOverloads constructor(
    private val predictor: DraftSequenceExecutionPredictor = EwmaDraftSequenceExecutionPredictor(),
) {

    fun predict(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictor.predict(executionKey, preExecutionMetrics)
    }

    fun updateAfterCompletion(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        predictor.update(executionKey, preExecutionMetrics, postExecutionMetrics)
    }

    /** Replays persisted capture history into the predictor. Returns the sample count fed. */
    fun warmUpFromHistory(history: List<CaptureMetrics>): Int {
        var updatedCount = 0

        history.forEach { captureMetrics ->
            val draftMetrics = captureMetrics.draftSequenceMetrics ?: return@forEach
            if (draftMetrics.isTimeout == true) {
                return@forEach
            }

            draftMetrics.nodeExecutionMetricsList.forEach { node ->
                if (node.postExecutionMetrics.durationMs > 0L) {
                    updateAfterCompletion(
                        executionKey = node.nodeId,
                        preExecutionMetrics = node.preExecutionMetrics,
                        postExecutionMetrics = node.postExecutionMetrics,
                    )
                    updatedCount++
                }
            }

            draftMetrics.savingExecutionMetrics?.let { saving ->
                if (saving.postExecutionMetrics.durationMs > 0L) {
                    updateAfterCompletion(
                        executionKey = SAVING_EXECUTION_KEY,
                        preExecutionMetrics = saving.preExecutionMetrics,
                        postExecutionMetrics = saving.postExecutionMetrics,
                    )
                    updatedCount++
                }
            }
        }

        return updatedCount
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

/** Saving has no nodeId; all saving executions share a single model key. */
private const val SAVING_EXECUTION_KEY = "saving"

/**
 * Splits a single node's lifecycle into the two steps the caller drives:
 *
 *   1. [predict] - reads device state, builds [PreExecutionMetrics], predicts, and records
 *      the node onto [DraftSequenceMetrics]. Returns a [DraftSequenceExecutionSession].
 *   2. caller inspects [DraftSequenceExecutionSession.shouldRun] and either runs the node or falls back.
 *   3. if the node ran, caller calls [DraftSequenceExecutionSession.complete] to fill
 *      [PostExecutionMetrics] and correct the model.
 *
 * The device-state snapshot used as prediction input is read inside [predict].
 *
 * This profiler itself is cheap and may be created per capture; the default
 * [predictionManager] is [DraftSequenceExecutionPredictionManager.instance], so the learned
 * model persists across captures.
 */
class DraftSequenceExecutionProfiler @JvmOverloads constructor(
    private val deviceStateReader: DeviceStateReader,
    private val predictionManager: DraftSequenceExecutionPredictionManager = DraftSequenceExecutionPredictionManager.instance,
) {

    /**
     * Step 1 (node): predict a node's execution cost from pre-execution state and record it.
     *
     * @param nodeId    stable identifier used as the model bucket key.
     * @param nodeParams node-specific pre-execution params (e.g. encoding format).
     * @param timeoutMs absolute deadline ([System.currentTimeMillis] base, i.e. epoch ms) for
     *   this node; the remaining budget is derived from it at device-state read time.
     * @param inputImageSize input image dimensions (recorded on the node metrics).
     * @param draftMetrics draft metrics to append this node's record to.
     */
    @JvmOverloads
    fun predictNodeExecution(
        nodeId: String,
        nodeParams: NodeParams = NodeParams.None,
        timeoutMs: Long,
        inputImageSize: Size,
        draftMetrics: DraftSequenceMetrics,
    ): DraftSequenceExecutionSession {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val executionKey = nodeId
        val nodeExecutionPrediction = predictionManager.predict(executionKey, preExecutionMetrics)

        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeId = nodeId,
            nodeParams = nodeParams,
            inputImageSize = inputImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )
        synchronized(draftMetrics) {
            draftMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
            draftMetrics.nodeExecutionPredictionList += nodeExecutionPrediction
        }

        return DraftSequenceExecutionSession(
            executionKey = executionKey,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
            executionPrediction = nodeExecutionPrediction,
            predictionManager = predictionManager,
        )
    }

    /**
     * Step 1 (saving): predict the saving step's cost. Saving has no nodeId / nodeParams; all
     * saving executions share the [SAVING_EXECUTION_KEY] model key. The resulting
     * [SavingExecutionMetrics] is attached to [draftMetrics].
     *
     * @param timeoutMs absolute deadline ([System.currentTimeMillis] base, i.e. epoch ms) for
     *   the saving step; the remaining budget is derived from it at device-state read time.
     * @param resultImageSize result image dimensions (recorded on the saving metrics).
     * @param resultImageFormat result image format (recorded on the saving metrics).
     * @param isPendingRequest whether the saving ran as a pending request.
     * @param draftMetrics draft metrics to attach the saving record to.
     */
    fun predictSavingExecution(
        timeoutMs: Long,
        isPendingRequest: Boolean,
        resultImageSize: Size,
        resultImageFormat: Int,
        draftMetrics: DraftSequenceMetrics,
    ): DraftSequenceExecutionSession {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val executionKey = SAVING_EXECUTION_KEY
        val savingExecutionPrediction = predictionManager.predict(executionKey, preExecutionMetrics)

        val savingExecutionMetrics = SavingExecutionMetrics(
            isPendingRequest = isPendingRequest,
            resultImageSize = resultImageSize,
            resultImageFormat = resultImageFormat,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )
        synchronized(draftMetrics) {
            draftMetrics.savingExecutionMetrics = savingExecutionMetrics
            draftMetrics.savingExecutionPrediction = savingExecutionPrediction
        }

        return DraftSequenceExecutionSession(
            executionKey = executionKey,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
            executionPrediction = savingExecutionPrediction,
            predictionManager = predictionManager,
        )
    }

    /**
     * Predicts the cost of the fallback path — the mandatory encoding node plus saving — under
     * the current device state. Unlike the other predict functions nothing is recorded onto the
     * draft metrics and no session is returned: this is a read-only estimate the caller takes
     * before running each draft node, e.g. to derive a watchdog timeout
     * (remaining budget - fallback cost) so that when the watchdog fires, the in-flight node is
     * abandoned and encoding + saving still fit.
     *
     * The combined upper bound is conservative: it assumes both steps hit their own upper bound.
     * Confidence is the lower of the two.
     *
     * @param encodingNodeId node id of the encoding node (model key).
     * @param timeoutMs absolute deadline ([System.currentTimeMillis] base, i.e. epoch ms).
     */
    fun predictFallbackExecution(
        encodingNodeId: String,
        timeoutMs: Long,
    ): ExecutionPrediction {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val encodingPrediction = predictionManager.predict(encodingNodeId, preExecutionMetrics)
        val savingPrediction = predictionManager.predict(SAVING_EXECUTION_KEY, preExecutionMetrics)

        return ExecutionPrediction(
            predictedDurationMs = encodingPrediction.predictedDurationMs + savingPrediction.predictedDurationMs,
            predictedUpperBoundMs = encodingPrediction.predictedUpperBoundMs + savingPrediction.predictedUpperBoundMs,
            confidence = minOf(encodingPrediction.confidence, savingPrediction.confidence),
            reason = buildString {
                append("fallback=encoding+saving")
                append(" encoding{").append(encodingPrediction.reason).append('}')
                append(" saving{").append(savingPrediction.reason).append('}')
            },
        )
    }

    private fun readPreExecutionMetrics(timeoutMs: Long): PreExecutionMetrics {
        val deviceState = deviceStateReader.read()
        return PreExecutionMetrics(
            budgetMs = timeoutMs - System.currentTimeMillis(),
            memorySnapshot = deviceState.memorySnapshot,
            powerThermalSnapshot = deviceState.powerThermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )
    }
}

/**
 * Handle returned by [DraftSequenceExecutionProfiler.predictNodeExecution] /
 * [DraftSequenceExecutionProfiler.predictSavingExecution].
 *
 * GC / CPU / wall-clock baselines are captured at construction time, i.e. right after
 * prediction, so the caller should run the work immediately after deciding [shouldRun].
 * Call [complete] exactly once, only if the work actually ran.
 */
class DraftSequenceExecutionSession internal constructor(
    private val executionKey: String,
    private val preExecutionMetrics: PreExecutionMetrics,
    private val postExecutionMetrics: PostExecutionMetrics,
    val executionPrediction: ExecutionPrediction,
    private val predictionManager: DraftSequenceExecutionPredictionManager,
) {
    /** True when the predicted upper bound fits within the budget. */
    val shouldRun: Boolean = executionPrediction.predictedUpperBoundMs <= preExecutionMetrics.budgetMs

    private val gcTracker = GcTracker()
    private val cpuProcessingTracker = CpuProcessingTracker()
    private val startedAtMs = SystemClock.uptimeMillis()

    /**
     * Step 3: fill [PostExecutionMetrics] from the elapsed GC / CPU / duration and correct
     * the model. Call this only after the work has actually run.
     */
    fun complete() {
        postExecutionMetrics.durationMs = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
        postExecutionMetrics.gcSnapshot = gcTracker.delta()
        postExecutionMetrics.cpuProcessingSnapshot = cpuProcessingTracker.delta()

        predictionManager.updateAfterCompletion(
            executionKey = executionKey,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = postExecutionMetrics,
        )
    }
}
