package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import android.util.Size

/**
 * Runs a reference predictor and the contextual EWMA predictor side by side for comparison.
 *
 * Callers use this profiler instead of [DraftSequenceExecutionProfiler]. It records one
 * [NodeExecutionMetrics] / [SavingExecutionMetrics] row for the real work, and records one
 * [ExecutionPrediction] row per predictor so DB and Excel exports can compare them directly.
 *
 * [decisionPredictorName] controls the returned [ComparedDraftSequenceExecutionSession.shouldRun].
 * The other predictor is observation-only but is still updated after completion.
 */
class ComparingDraftSequenceExecutionProfiler @JvmOverloads constructor(
    private val deviceStateReader: DeviceStateReader,
    private val referencePredictor: DraftSequenceExecutionPredictor = EwmaDraftSequenceExecutionPredictor(),
    private val contextualPredictor: ContextualEwmaDraftSequenceExecutionPredictor =
        ContextualEwmaDraftSequenceExecutionPredictor(),
    private val decisionPredictorName: String = referencePredictor.name,
) {

    @JvmOverloads
    fun predictNodeExecution(
        captureMetrics: CaptureMetrics,
        nodeId: String,
        nodeParams: NodeParams = NodeParams.None,
        timeoutMs: Long,
        inputImageSize: Size,
        draftMetrics: DraftSequenceMetrics = captureMetrics.ensureDraftSequenceMetrics(),
    ): ComparedDraftSequenceExecutionSession {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val nodeExecutionMetrics = NodeExecutionMetrics(
            nodeId = nodeId,
            nodeParams = nodeParams,
            inputImageSize = inputImageSize,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )

        val order = synchronized(draftMetrics) {
            val nextOrder = draftMetrics.nodeExecutionMetricsList.size
            draftMetrics.nodeExecutionMetricsList += nodeExecutionMetrics
            nextOrder
        }

        val predictions = listOf(
            referencePredictor
                .predict(nodeId, preExecutionMetrics)
                .forComparison(referencePredictor.name, order),
            contextualPredictor
                .predictNodeExecution(
                    captureMetrics = captureMetrics,
                    nodeId = nodeId,
                    nodeParams = nodeParams,
                    inputImageSize = inputImageSize,
                    preExecutionMetrics = preExecutionMetrics,
                )
                .forComparison(contextualPredictor.name, order),
        )

        synchronized(draftMetrics) {
            draftMetrics.nodeExecutionPredictionList += predictions
        }

        return ComparedDraftSequenceExecutionSession(
            predictions = predictions,
            decisionPredictorName = decisionPredictorName,
            budgetMs = preExecutionMetrics.budgetMs,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
            completionCallbacks = listOf(
                {
                    referencePredictor.update(
                        executionKey = nodeId,
                        preExecutionMetrics = preExecutionMetrics,
                        postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
                    )
                },
                {
                    contextualPredictor.updateNodeExecution(
                        captureMetrics = captureMetrics,
                        nodeExecutionMetrics = nodeExecutionMetrics,
                    )
                },
            ),
        )
    }

    @JvmOverloads
    fun predictSavingExecution(
        captureMetrics: CaptureMetrics,
        timeoutMs: Long,
        isPendingRequest: Boolean,
        resultImageSize: Size,
        resultImageFormat: Int,
        draftMetrics: DraftSequenceMetrics = captureMetrics.ensureDraftSequenceMetrics(),
    ): ComparedDraftSequenceExecutionSession {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val savingExecutionMetrics = SavingExecutionMetrics(
            isPendingRequest = isPendingRequest,
            resultImageSize = resultImageSize,
            resultImageFormat = resultImageFormat,
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = PostExecutionMetrics(),
        )

        val predictions = listOf(
            referencePredictor
                .predict(SAVING_EXECUTION_KEY, preExecutionMetrics)
                .forComparison(
                    predictorName = referencePredictor.name,
                    executionOrder = ExecutionPredictionEntity.SAVING_ORDER,
                ),
            contextualPredictor
                .predictSavingExecution(
                    captureMetrics = captureMetrics,
                    savingExecutionMetrics = savingExecutionMetrics,
                )
                .forComparison(
                    predictorName = contextualPredictor.name,
                    executionOrder = ExecutionPredictionEntity.SAVING_ORDER,
                ),
        )

        synchronized(draftMetrics) {
            draftMetrics.savingExecutionMetrics = savingExecutionMetrics
            draftMetrics.savingExecutionPredictionList.clear()
            draftMetrics.savingExecutionPredictionList += predictions
        }

        return ComparedDraftSequenceExecutionSession(
            predictions = predictions,
            decisionPredictorName = decisionPredictorName,
            budgetMs = preExecutionMetrics.budgetMs,
            postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
            completionCallbacks = listOf(
                {
                    referencePredictor.update(
                        executionKey = SAVING_EXECUTION_KEY,
                        preExecutionMetrics = preExecutionMetrics,
                        postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
                    )
                },
                {
                    contextualPredictor.updateSavingExecution(
                        captureMetrics = captureMetrics,
                        savingExecutionMetrics = savingExecutionMetrics,
                    )
                },
            ),
        )
    }

    fun predictFallbackExecution(
        encodingNodeId: String,
        timeoutMs: Long,
    ): ComparedExecutionPrediction {
        val preExecutionMetrics = readPreExecutionMetrics(timeoutMs)
        val referencePrediction = combineFallbackPrediction(
            predictorName = referencePredictor.name,
            encodingPrediction = referencePredictor.predict(encodingNodeId, preExecutionMetrics),
            savingPrediction = referencePredictor.predict(SAVING_EXECUTION_KEY, preExecutionMetrics),
        )
        val contextualPrediction = combineFallbackPrediction(
            predictorName = contextualPredictor.name,
            encodingPrediction = contextualPredictor.predict(encodingNodeId, preExecutionMetrics),
            savingPrediction = contextualPredictor.predict(SAVING_EXECUTION_KEY, preExecutionMetrics),
        )
        val predictions = listOf(referencePrediction, contextualPrediction)

        return ComparedExecutionPrediction(
            predictions = predictions,
            decisionPrediction = predictions.decisionPrediction(decisionPredictorName),
        )
    }

    /**
     * Replays historical completed captures into both predictors. This should be called once
     * before a replay experiment when the experiment wants a warmed model.
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
                    referencePredictor.update(
                        executionKey = node.nodeId,
                        preExecutionMetrics = node.preExecutionMetrics,
                        postExecutionMetrics = node.postExecutionMetrics,
                    )
                    contextualPredictor.updateNodeExecution(captureMetrics, node)
                    updatedCount++
                }
            }

            draftMetrics.savingExecutionMetrics?.let { saving ->
                if (saving.postExecutionMetrics.durationMs > 0L) {
                    referencePredictor.update(
                        executionKey = SAVING_EXECUTION_KEY,
                        preExecutionMetrics = saving.preExecutionMetrics,
                        postExecutionMetrics = saving.postExecutionMetrics,
                    )
                    contextualPredictor.updateSavingExecution(captureMetrics, saving)
                    updatedCount++
                }
            }
        }

        return updatedCount
    }

    private fun readPreExecutionMetrics(timeoutMs: Long): PreExecutionMetrics {
        val deviceState = deviceStateReader.read()
        return PreExecutionMetrics(
            budgetMs = timeoutMs - System.currentTimeMillis(),
            memorySnapshot = deviceState.memorySnapshot,
            thermalSnapshot = deviceState.thermalSnapshot,
            storageSnapshot = deviceState.storageSnapshot,
        )
    }

    private fun combineFallbackPrediction(
        predictorName: String,
        encodingPrediction: ExecutionPrediction,
        savingPrediction: ExecutionPrediction,
    ): ExecutionPrediction {
        return ExecutionPrediction(
            predictedDurationMs = encodingPrediction.predictedDurationMs +
                savingPrediction.predictedDurationMs,
            predictedUpperBoundMs = encodingPrediction.predictedUpperBoundMs +
                savingPrediction.predictedUpperBoundMs,
            confidence = minOf(encodingPrediction.confidence, savingPrediction.confidence),
            reason = buildString {
                append("fallback=encoding+saving")
                append(" encoding{").append(encodingPrediction.reason).append('}')
                append(" saving{").append(savingPrediction.reason).append('}')
            },
            predictorName = predictorName,
        )
    }

    private companion object {
        private const val SAVING_EXECUTION_KEY = "saving"
    }
}

class ComparedDraftSequenceExecutionSession internal constructor(
    val predictions: List<ExecutionPrediction>,
    decisionPredictorName: String,
    private val budgetMs: Long,
    private val postExecutionMetrics: PostExecutionMetrics,
    private val completionCallbacks: List<() -> Unit>,
) {
    val decisionPrediction: ExecutionPrediction = predictions.decisionPrediction(decisionPredictorName)

    /** True when the selected decision predictor's upper bound fits within the budget. */
    val shouldRun: Boolean = decisionPrediction.predictedUpperBoundMs <= budgetMs

    private val gcTracker = GcTracker()
    private val cpuProcessingTracker = CpuProcessingTracker()
    private val startedAtMs = SystemClock.uptimeMillis()
    private var completed = false

    @Synchronized
    fun complete() {
        check(!completed) { "ComparedDraftSequenceExecutionSession.complete() called more than once." }
        completed = true

        postExecutionMetrics.durationMs = (SystemClock.uptimeMillis() - startedAtMs)
            .coerceAtLeast(0L)
        postExecutionMetrics.gcSnapshot = gcTracker.delta()
        postExecutionMetrics.cpuProcessingSnapshot = cpuProcessingTracker.delta()

        completionCallbacks.forEach { it.invoke() }
    }
}

data class ComparedExecutionPrediction(
    val predictions: List<ExecutionPrediction>,
    val decisionPrediction: ExecutionPrediction,
)

private fun CaptureMetrics.ensureDraftSequenceMetrics(): DraftSequenceMetrics {
    return draftSequenceMetrics ?: DraftSequenceMetrics().also {
        draftSequenceMetrics = it
    }
}

private fun ExecutionPrediction.forComparison(
    predictorName: String,
    executionOrder: Int,
): ExecutionPrediction {
    return copy(
        predictorName = predictorName,
        executionOrder = executionOrder,
    )
}

private fun List<ExecutionPrediction>.decisionPrediction(
    decisionPredictorName: String,
): ExecutionPrediction {
    return firstOrNull { it.predictorName == decisionPredictorName } ?: first()
}
