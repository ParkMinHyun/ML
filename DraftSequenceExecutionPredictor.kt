package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import android.util.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

interface DraftSequenceExecutionPredictor {
    val name: String

    fun predict(
        executionKey: String,
        inputImageSize: Size,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction

    fun update(
        executionKey: String,
        inputImageSize: Size,
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
        inputImageSize: Size,
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
        inputImageSize: Size,
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

class DraftSequenceExecutionAdmissionController @JvmOverloads constructor(
    private val predictor: DraftSequenceExecutionPredictor = EwmaDraftSequenceExecutionPredictor(),
) {

    fun predict(
        executionKey: String,
        inputImageSize: Size,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictor.predict(executionKey, inputImageSize, preExecutionMetrics)
    }

    fun updateAfterCompletion(
        executionKey: String,
        inputImageSize: Size,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        predictor.update(executionKey, inputImageSize, preExecutionMetrics, postExecutionMetrics)
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
 *   3. if the node ran, caller calls [DraftSequenceExecutionSession.recordCompletion] to fill
 *      [PostExecutionMetrics] and correct the model.
 *
 * The device-state snapshot used as prediction input is read inside [predict].
 */
class DraftSequenceExecutionPerformanceHelper @JvmOverloads constructor(
    private val deviceStateReader: DeviceStateReader,
    private val admissionController: DraftSequenceExecutionAdmissionController = DraftSequenceExecutionAdmissionController(),
) {

    /**
     * Step 1 (node): predict a node's execution cost from pre-execution state and record it.
     *
     * @param nodeId    stable identifier used as the model bucket key.
     * @param nodeParams node-specific pre-execution params (e.g. encoding format).
     * @param timeoutMs absolute deadline ([System.currentTimeMillis] base, i.e. epoch ms) for
     *   this node; the remaining budget is derived from it at device-state read time.
     * @param inputImageSize input image dimensions (workload feature).
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
        val prediction = admissionController.predict(executionKey, inputImageSize, preExecutionMetrics)

        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeId = nodeId,
            nodeParams = nodeParams,
            inputImageSize = inputImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )
        synchronized(draftMetrics) {
            draftMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
            draftMetrics.executionPredictionList += prediction
        }

        return DraftSequenceExecutionSession(
            executionKey = executionKey,
            inputImageSize = inputImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
            prediction = prediction,
            admissionController = admissionController,
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
        val prediction = admissionController.predict(executionKey, resultImageSize, preExecutionMetrics)

        val savingExecutionMetrics = SavingExecutionMetrics(
            isPendingRequest = isPendingRequest,
            resultImageSize = resultImageSize,
            resultImageFormat = resultImageFormat,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )
        synchronized(draftMetrics) {
            draftMetrics.savingExecutionMetrics = savingExecutionMetrics
            draftMetrics.savingExecutionPrediction = prediction
        }

        return DraftSequenceExecutionSession(
            executionKey = executionKey,
            inputImageSize = resultImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
            prediction = prediction,
            admissionController = admissionController,
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
 * Handle returned by [DraftSequenceExecutionPerformanceHelper.predict] / [predictSaving].
 *
 * GC / CPU / wall-clock baselines are captured at construction time, i.e. right after
 * prediction, so the caller should run the work immediately after deciding [shouldRun].
 * Call [complete] exactly once, only if the work actually ran.
 */
class DraftSequenceExecutionSession internal constructor(
    private val executionKey: String,
    private val inputImageSize: Size,
    private val preExecutionMetrics: PreExecutionMetrics,
    private val postExecutionMetrics: PostExecutionMetrics,
    val prediction: ExecutionPrediction,
    private val admissionController: DraftSequenceExecutionAdmissionController,
) {
    /** True when the predicted upper bound fits within the budget. */
    val shouldRun: Boolean =
        prediction.predictedUpperBoundMs <= preExecutionMetrics.budgetMs

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

        admissionController.updateAfterCompletion(
            executionKey = executionKey,
            inputImageSize = inputImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = postExecutionMetrics,
        )
    }
}
