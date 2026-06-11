package com.samsung.android.camera.core2.ml

import android.util.Size
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * SOTA-inspired online predictor for draft-sequence experiments.
 *
 * The model keeps the production-friendly EWMA baseline, then applies three ideas that commonly
 * appear in recent edge/runtime-prediction work:
 *
 *   1. operator/stage-wise prediction: one model per execution key plus pipeline/workload buckets,
 *   2. context adaptation: residual EWMA buckets for current memory/thermal/storage state,
 *   3. uncertainty-aware admission: rolling under-prediction quantiles as a lightweight conformal
 *      upper-bound calibrator.
 *
 * The plain [DraftSequenceExecutionPredictor] interface only exposes [executionKey] and
 * [PreExecutionMetrics]. For offline replay or research evaluation, use [warmUpFromHistory],
 * [predictNodeExecution], [updateNodeExecution], [predictSavingExecution], and
 * [updateSavingExecution] so the model can also use the top-level [CaptureMetrics] pipeline
 * fields, image sizes, node params, and saving params.
 */
class ContextualEwmaDraftSequenceExecutionPredictor @JvmOverloads constructor(
    private val ewmaAlpha: Double = 0.20,
    private val residualAlpha: Double = 0.18,
    private val calibrationQuantile: Double = 0.90,
    private val minimumErrorMarginMs: Long = 80L,
    private val minimumSamplesForContext: Int = 4,
    private val calibrationWindowSize: Int = 64,
    private val useRecentRuntimeContext: Boolean = true,
) : DraftSequenceExecutionPredictor {

    override val name: String = "draft_sequence_execution_contextual_ewma_conformal"

    private val globalDurationStats = OnlineEwmaStats()
    private val durationStatsByKey: MutableMap<String, OnlineEwmaStats> = mutableMapOf()
    private val durationStatsByWorkload: MutableMap<String, OnlineEwmaStats> = mutableMapOf()
    private val residualStatsByContext: MutableMap<String, OnlineEwmaStats> = mutableMapOf()

    private val globalUnderPredictionErrors = RollingQuantile(calibrationWindowSize)
    private val underPredictionErrorsByKey: MutableMap<String, RollingQuantile> = mutableMapOf()
    private val underPredictionErrorsByContext: MutableMap<String, RollingQuantile> = mutableMapOf()

    private var recentRuntimeContext: RuntimeContextSignature = RuntimeContextSignature.EMPTY

    @Synchronized
    override fun predict(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictInternal(
            executionKey = executionKey,
            pipelineSignature = PipelineSignature.UNKNOWN,
            workloadSignature = WorkloadSignature.keyOnly(executionKey),
            preExecutionMetrics = preExecutionMetrics,
        ).toPrediction()
    }

    @Synchronized
    override fun update(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        updateInternal(
            executionKey = executionKey,
            pipelineSignature = PipelineSignature.UNKNOWN,
            workloadSignature = WorkloadSignature.keyOnly(executionKey),
            preExecutionMetrics = preExecutionMetrics,
            postExecutionMetrics = postExecutionMetrics,
        )
    }

    @Synchronized
    fun predictNodeExecution(
        captureMetrics: CaptureMetrics,
        nodeId: String,
        nodeParams: NodeParams,
        inputImageSize: Size,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictInternal(
            executionKey = nodeId,
            pipelineSignature = PipelineSignature.from(captureMetrics),
            workloadSignature = WorkloadSignature.node(nodeId, nodeParams, inputImageSize),
            preExecutionMetrics = preExecutionMetrics,
        ).toPrediction()
    }

    @Synchronized
    fun predictNodeExecution(
        captureMetrics: CaptureMetrics,
        nodeExecutionMetrics: NodeExecutionMetrics,
    ): ExecutionPrediction {
        return predictNodeExecution(
            captureMetrics = captureMetrics,
            nodeId = nodeExecutionMetrics.nodeId,
            nodeParams = nodeExecutionMetrics.nodeParams,
            inputImageSize = nodeExecutionMetrics.inputImageSize,
            preExecutionMetrics = nodeExecutionMetrics.preExecutionMetrics,
        )
    }

    @Synchronized
    fun predictSavingExecution(
        captureMetrics: CaptureMetrics,
        isPendingRequest: Boolean,
        resultImageSize: Size,
        resultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        return predictInternal(
            executionKey = SAVING_EXECUTION_KEY,
            pipelineSignature = PipelineSignature.from(captureMetrics),
            workloadSignature = WorkloadSignature.saving(
                isPendingRequest = isPendingRequest,
                resultImageSize = resultImageSize,
                resultImageFormat = resultImageFormat,
            ),
            preExecutionMetrics = preExecutionMetrics,
        ).toPrediction()
    }

    @Synchronized
    fun predictSavingExecution(
        captureMetrics: CaptureMetrics,
        savingExecutionMetrics: SavingExecutionMetrics,
    ): ExecutionPrediction {
        return predictSavingExecution(
            captureMetrics = captureMetrics,
            isPendingRequest = savingExecutionMetrics.isPendingRequest,
            resultImageSize = savingExecutionMetrics.resultImageSize,
            resultImageFormat = savingExecutionMetrics.resultImageFormat,
            preExecutionMetrics = savingExecutionMetrics.preExecutionMetrics,
        )
    }

    @Synchronized
    fun updateNodeExecution(
        captureMetrics: CaptureMetrics,
        nodeExecutionMetrics: NodeExecutionMetrics,
    ) {
        updateInternal(
            executionKey = nodeExecutionMetrics.nodeId,
            pipelineSignature = PipelineSignature.from(captureMetrics),
            workloadSignature = WorkloadSignature.node(
                nodeId = nodeExecutionMetrics.nodeId,
                nodeParams = nodeExecutionMetrics.nodeParams,
                inputImageSize = nodeExecutionMetrics.inputImageSize,
            ),
            preExecutionMetrics = nodeExecutionMetrics.preExecutionMetrics,
            postExecutionMetrics = nodeExecutionMetrics.postExecutionMetrics,
        )
    }

    @Synchronized
    fun updateSavingExecution(
        captureMetrics: CaptureMetrics,
        savingExecutionMetrics: SavingExecutionMetrics,
    ) {
        updateInternal(
            executionKey = SAVING_EXECUTION_KEY,
            pipelineSignature = PipelineSignature.from(captureMetrics),
            workloadSignature = WorkloadSignature.saving(savingExecutionMetrics),
            preExecutionMetrics = savingExecutionMetrics.preExecutionMetrics,
            postExecutionMetrics = savingExecutionMetrics.postExecutionMetrics,
        )
    }

    /**
     * Replays complete capture history into the contextual model. Timed-out captures are skipped
     * because their later-stage observations are censored by the timeout path.
     */
    @Synchronized
    fun warmUpFromHistory(history: List<CaptureMetrics>): Int {
        var updatedCount = 0

        history.forEach { captureMetrics ->
            val draftMetrics = captureMetrics.draftSequenceMetrics ?: return@forEach
            if (draftMetrics.isTimeout == true) {
                return@forEach
            }

            draftMetrics.nodeExecutionMetricsList.forEach { node ->
                if (node.postExecutionMetrics.durationMs > 0L) {
                    updateNodeExecution(captureMetrics, node)
                    updatedCount++
                }
            }

            draftMetrics.savingExecutionMetrics?.let { saving ->
                if (saving.postExecutionMetrics.durationMs > 0L) {
                    updateSavingExecution(captureMetrics, saving)
                    updatedCount++
                }
            }
        }

        return updatedCount
    }

    private fun predictInternal(
        executionKey: String,
        pipelineSignature: PipelineSignature,
        workloadSignature: WorkloadSignature,
        preExecutionMetrics: PreExecutionMetrics,
    ): PredictionComponents {
        val predictionContext = PredictionContext(
            executionKey = executionKey,
            pipelineSignature = pipelineSignature,
            workloadSignature = workloadSignature,
            deviceContextSignature = DeviceContextSignature.from(
                preExecutionMetrics = preExecutionMetrics,
                runtimeContextSignature = if (useRecentRuntimeContext) {
                    recentRuntimeContext
                } else {
                    RuntimeContextSignature.EMPTY
                },
            ),
        )

        val keyStats = durationStatsByKey[executionKey]
        val workloadStats = durationStatsByWorkload[predictionContext.workloadKey]
        val baseEstimate = blendedDurationEstimate(
            globalStats = globalDurationStats,
            keyStats = keyStats,
            workloadStats = workloadStats,
        )

        if (baseEstimate == null) {
            val budgetMs = preExecutionMetrics.budgetMs
            return PredictionComponents(
                executionKey = executionKey,
                predictorName = name,
                predictedDurationMs = 0L,
                predictedUpperBoundMs = 0L,
                confidence = confidenceFromCount(0, 0),
                reason = "model=$name key=$executionKey coldStart=true budgetMs=$budgetMs shouldRun=${budgetMs >= 0L}",
                baseDurationMs = 0.0,
                contextResidualMs = 0.0,
                contextKey = predictionContext.contextKey,
                workloadKey = predictionContext.workloadKey,
            )
        }

        val residualStats = residualStatsByContext[predictionContext.contextKey]
            ?.takeIf { it.count >= minimumSamplesForContext }
        val contextResidualMs = residualStats?.ewmaMs ?: 0.0
        val predictedDurationMs = (baseEstimate.durationMs + contextResidualMs)
            .coerceAtLeast(0.0)
            .roundToLong()
        val margin = calibratedMargin(
            executionKey = executionKey,
            contextKey = predictionContext.contextKey,
            fallbackAbsErrorMs = baseEstimate.absErrorMs,
        )
        val predictedUpperBoundMs = predictedDurationMs + margin
        val calibrationCount = calibrationCount(
            executionKey = executionKey,
            contextKey = predictionContext.contextKey,
        )
        val budgetMs = preExecutionMetrics.budgetMs
        val confidence = confidenceFromCount(
            sampleCount = baseEstimate.sampleCount,
            calibrationCount = calibrationCount,
        )
        val reason = buildString {
            append("model=").append(name)
            append(" key=").append(executionKey)
            append(" workload=").append(workloadSignature.value)
            append(" pipeline=").append(pipelineSignature.value)
            append(" device=").append(predictionContext.deviceContextSignature.value)
            append(" samples=").append(baseEstimate.sampleCount)
            append(" calibrationSamples=").append(calibrationCount)
            append(" baseMs=").append(baseEstimate.durationMs.roundToLong())
            append(" contextResidualMs=").append(contextResidualMs.roundToLong())
            append(" marginMs=").append(margin)
            append(" budgetMs=").append(budgetMs)
            append(" slackMs=").append(budgetMs - predictedUpperBoundMs)
            append(" shouldRun=").append(predictedUpperBoundMs <= budgetMs)
        }

        return PredictionComponents(
            executionKey = executionKey,
            predictorName = name,
            predictedDurationMs = predictedDurationMs,
            predictedUpperBoundMs = predictedUpperBoundMs,
            confidence = confidence,
            reason = reason,
            baseDurationMs = baseEstimate.durationMs,
            contextResidualMs = contextResidualMs,
            contextKey = predictionContext.contextKey,
            workloadKey = predictionContext.workloadKey,
        )
    }

    private fun updateInternal(
        executionKey: String,
        pipelineSignature: PipelineSignature,
        workloadSignature: WorkloadSignature,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        val durationMs = postExecutionMetrics.durationMs.coerceAtLeast(0L)
        if (durationMs <= 0L) {
            return
        }

        val predictionBeforeUpdate = predictInternal(
            executionKey = executionKey,
            pipelineSignature = pipelineSignature,
            workloadSignature = workloadSignature,
            preExecutionMetrics = preExecutionMetrics,
        )
        val observedMs = durationMs.toDouble()
        val contextResidualMs = observedMs - predictionBeforeUpdate.baseDurationMs
        val underPredictionMs = (durationMs - predictionBeforeUpdate.predictedDurationMs)
            .coerceAtLeast(0L)

        globalDurationStats.update(observedMs, ewmaAlpha)
        durationStatsByKey.getOrPut(executionKey) { OnlineEwmaStats() }
            .update(observedMs, ewmaAlpha)
        durationStatsByWorkload.getOrPut(predictionBeforeUpdate.workloadKey) { OnlineEwmaStats() }
            .update(observedMs, ewmaAlpha)
        residualStatsByContext.getOrPut(predictionBeforeUpdate.contextKey) { OnlineEwmaStats() }
            .update(contextResidualMs, residualAlpha)

        globalUnderPredictionErrors.add(underPredictionMs)
        underPredictionErrorsByKey.getOrPut(executionKey) { RollingQuantile(calibrationWindowSize) }
            .add(underPredictionMs)
        underPredictionErrorsByContext.getOrPut(predictionBeforeUpdate.contextKey) {
            RollingQuantile(calibrationWindowSize)
        }.add(underPredictionMs)

        recentRuntimeContext = RuntimeContextSignature.from(postExecutionMetrics)
    }

    private fun blendedDurationEstimate(
        globalStats: OnlineEwmaStats,
        keyStats: OnlineEwmaStats?,
        workloadStats: OnlineEwmaStats?,
    ): DurationEstimate? {
        var weightedDuration = 0.0
        var weightedAbsError = 0.0
        var totalWeight = 0.0
        var totalSamples = 0

        fun add(stats: OnlineEwmaStats?, multiplier: Double) {
            if (stats == null || stats.count == 0) {
                return
            }
            val confidenceWeight = stats.count.toDouble() / (stats.count + WARMUP_COUNT).toDouble()
            val weight = confidenceWeight * multiplier
            weightedDuration += stats.ewmaMs * weight
            weightedAbsError += stats.ewmaAbsErrorMs * weight
            totalWeight += weight
            totalSamples += stats.count
        }

        add(globalStats, GLOBAL_WEIGHT)
        add(keyStats, KEY_WEIGHT)
        add(workloadStats, WORKLOAD_WEIGHT)

        if (totalWeight <= 0.0) {
            return null
        }

        return DurationEstimate(
            durationMs = weightedDuration / totalWeight,
            absErrorMs = weightedAbsError / totalWeight,
            sampleCount = totalSamples,
        )
    }

    private fun calibratedMargin(
        executionKey: String,
        contextKey: String,
        fallbackAbsErrorMs: Double,
    ): Long {
        val contextMargin = underPredictionErrorsByContext[contextKey]
            ?.takeIf { it.count >= minimumSamplesForContext }
            ?.quantile(calibrationQuantile)
        val keyMargin = underPredictionErrorsByKey[executionKey]
            ?.takeIf { it.count >= minimumSamplesForContext }
            ?.quantile(calibrationQuantile)
        val globalMargin = globalUnderPredictionErrors
            .takeIf { it.count >= minimumSamplesForContext }
            ?.quantile(calibrationQuantile)
        val ewmaFallbackMargin = (fallbackAbsErrorMs * NORMAL_APPROX_ONE_SIDED_95)
            .roundToLong()

        return listOfNotNull(
            contextMargin,
            keyMargin,
            globalMargin,
            ewmaFallbackMargin,
            minimumErrorMarginMs,
        ).maxOrNull() ?: minimumErrorMarginMs
    }

    private fun calibrationCount(
        executionKey: String,
        contextKey: String,
    ): Int {
        return max(
            max(
                underPredictionErrorsByContext[contextKey]?.count ?: 0,
                underPredictionErrorsByKey[executionKey]?.count ?: 0,
            ),
            globalUnderPredictionErrors.count,
        )
    }

    private fun confidenceFromCount(
        sampleCount: Int,
        calibrationCount: Int,
    ): Float {
        val sampleConfidence = sampleCount.toFloat() / (sampleCount + WARMUP_COUNT).toFloat()
        val calibrationConfidence = calibrationCount.toFloat() /
            (calibrationCount + WARMUP_COUNT).toFloat()
        return (sampleConfidence * calibrationConfidence)
            .coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
    }

    private data class PredictionComponents(
        val executionKey: String,
        val predictorName: String,
        val predictedDurationMs: Long,
        val predictedUpperBoundMs: Long,
        val confidence: Float,
        val reason: String,
        val baseDurationMs: Double,
        val contextResidualMs: Double,
        val contextKey: String,
        val workloadKey: String,
    ) {
        fun toPrediction(): ExecutionPrediction {
            return ExecutionPrediction(
                predictedDurationMs = predictedDurationMs,
                predictedUpperBoundMs = predictedUpperBoundMs,
                confidence = confidence,
                reason = reason,
                predictorName = predictorName,
            )
        }
    }

    private data class DurationEstimate(
        val durationMs: Double,
        val absErrorMs: Double,
        val sampleCount: Int,
    )

    private data class PredictionContext(
        val executionKey: String,
        val pipelineSignature: PipelineSignature,
        val workloadSignature: WorkloadSignature,
        val deviceContextSignature: DeviceContextSignature,
    ) {
        val workloadKey: String =
            "$executionKey|${pipelineSignature.value}|${workloadSignature.value}"
        val contextKey: String =
            "$workloadKey|${deviceContextSignature.value}"
    }

    private data class PipelineSignature(val value: String) {
        companion object {
            val UNKNOWN = PipelineSignature("unknown")

            fun from(captureMetrics: CaptureMetrics): PipelineSignature {
                return PipelineSignature(
                    "pp=${captureMetrics.ppSequenceId}" +
                        ",ds=${captureMetrics.dsMode}" +
                        ",extra=${captureMetrics.dsExtraInfo}" +
                        ",result=${captureMetrics.resultImageSize.bucket()}" +
                        ",format=${captureMetrics.resultImageFormat}",
                )
            }
        }
    }

    private data class WorkloadSignature(val value: String) {
        companion object {
            fun keyOnly(executionKey: String): WorkloadSignature {
                return WorkloadSignature("keyOnly=$executionKey")
            }

            fun node(
                nodeId: String,
                nodeParams: NodeParams,
                inputImageSize: Size,
            ): WorkloadSignature {
                return WorkloadSignature(
                    "node=$nodeId,input=${inputImageSize.bucket()},params=${nodeParams.signature()}",
                )
            }

            fun saving(
                isPendingRequest: Boolean,
                resultImageSize: Size,
                resultImageFormat: Int,
            ): WorkloadSignature {
                return WorkloadSignature(
                    "saving,pending=$isPendingRequest" +
                        ",result=${resultImageSize.bucket()}" +
                        ",format=$resultImageFormat",
                )
            }

            fun saving(savingExecutionMetrics: SavingExecutionMetrics): WorkloadSignature {
                return saving(
                    isPendingRequest = savingExecutionMetrics.isPendingRequest,
                    resultImageSize = savingExecutionMetrics.resultImageSize,
                    resultImageFormat = savingExecutionMetrics.resultImageFormat,
                )
            }
        }
    }

    private data class DeviceContextSignature(val value: String) {
        companion object {
            fun from(
                preExecutionMetrics: PreExecutionMetrics,
                runtimeContextSignature: RuntimeContextSignature,
            ): DeviceContextSignature {
                val memory = preExecutionMetrics.memorySnapshot
                val thermal = preExecutionMetrics.thermalSnapshot
                val storage = preExecutionMetrics.storageSnapshot

                return DeviceContextSignature(
                    "lowMem=${memory.isLowMemory}" +
                        ",ram=${memory.ramAvailablePercent.percentBucket()}" +
                        ",java=${memory.javaHeapUsedPercent.percentBucket()}" +
                        ",native=${memory.nativeHeapAllocatedPercent.percentBucket()}" +
                        ",overheat=${thermal.overheatLevel.coarseBucket(1)}" +
                        ",thermal=${thermal.thermalStatus}" +
                        ",headroom=${thermal.thermalHeadroom.headroomBucket()}" +
                        ",storage=${storage.storageUsedPercent.percentBucket()}" +
                        ",recent=${runtimeContextSignature.value}",
                )
            }
        }
    }

    private data class RuntimeContextSignature(val value: String) {
        companion object {
            val EMPTY = RuntimeContextSignature("none")

            fun from(postExecutionMetrics: PostExecutionMetrics): RuntimeContextSignature {
                val cpu = postExecutionMetrics.cpuProcessingSnapshot
                val gc = postExecutionMetrics.gcSnapshot
                if (cpu == null && gc == null) {
                    return EMPTY
                }

                return RuntimeContextSignature(
                    "cpu=${cpu?.cpuUtilizationRatio?.ratioBucket() ?: UNKNOWN_BUCKET}" +
                        ",runq=${cpu?.runqueueWaitMs?.timeBucket() ?: UNKNOWN_BUCKET}" +
                        ",nvc=${cpu?.nonvoluntaryCtxSwitches?.coarseBucket(4) ?: UNKNOWN_BUCKET}" +
                        ",gcCount=${gc?.blockingGcCount?.coarseBucket(1) ?: UNKNOWN_BUCKET}" +
                        ",gcTime=${gc?.blockingGcTimeMs?.timeBucket() ?: UNKNOWN_BUCKET}",
                )
            }
        }
    }

    private class OnlineEwmaStats {
        var count: Int = 0
            private set
        var ewmaMs: Double = 0.0
            private set
        var ewmaAbsErrorMs: Double = 0.0
            private set

        fun update(
            observedMs: Double,
            alpha: Double,
        ) {
            if (count == 0) {
                ewmaMs = observedMs
                ewmaAbsErrorMs = abs(observedMs) * INITIAL_ABS_ERROR_RATIO
            } else {
                val previous = ewmaMs
                ewmaMs = alpha * observedMs + (1.0 - alpha) * ewmaMs
                ewmaAbsErrorMs = alpha * abs(observedMs - previous) +
                    (1.0 - alpha) * ewmaAbsErrorMs
            }
            count++
        }
    }

    private class RollingQuantile(
        private val maxSize: Int,
    ) {
        private val values = ArrayDeque<Long>()

        val count: Int
            get() = values.size

        fun add(value: Long) {
            values.addLast(value.coerceAtLeast(0L))
            while (values.size > maxSize) {
                values.removeFirst()
            }
        }

        fun quantile(q: Double): Long {
            if (values.isEmpty()) {
                return 0L
            }
            val sortedValues = values.toList().sorted()
            val index = ((sortedValues.size - 1) * q)
                .roundToInt()
                .coerceIn(0, sortedValues.lastIndex)
            return sortedValues[index]
        }
    }

    private companion object {
        private const val SAVING_EXECUTION_KEY = "saving"
        private const val WARMUP_COUNT = 12
        private const val GLOBAL_WEIGHT = 0.25
        private const val KEY_WEIGHT = 1.0
        private const val WORKLOAD_WEIGHT = 1.5
        private const val NORMAL_APPROX_ONE_SIDED_95 = 1.64
        private const val INITIAL_ABS_ERROR_RATIO = 0.25
        private const val MIN_CONFIDENCE = 0.05f
        private const val MAX_CONFIDENCE = 0.95f
        private const val UNKNOWN_BUCKET = -1
    }
}

private fun Size.bucket(): String {
    val pixels = width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)
    val megaPixelsTimes10 = pixels / 100_000L
    val halfMegaPixelBucket = ((megaPixelsTimes10 + 2L) / 5L) * 5L
    return "${halfMegaPixelBucket / 10L}.${halfMegaPixelBucket % 10L}mp"
}

private fun NodeParams.signature(): String {
    return when (this) {
        NodeParams.None -> "none"
        is NodeParams.DualBokeh -> "dualBokeh:${outputImageSize.bucket()}"
        is NodeParams.Encoding -> "encoding:$encodingFormat"
    }
}

private fun Int.percentBucket(): Int {
    if (this < 0) {
        return -1
    }
    return (this / 20) * 20
}

private fun Int.coarseBucket(bucketSize: Int): Int {
    if (this < 0 || bucketSize <= 0) {
        return -1
    }
    return (this / bucketSize) * bucketSize
}

private fun Long.timeBucket(): Int {
    if (this < 0L) {
        return -1
    }
    return when {
        this < 10L -> 0
        this < 50L -> 10
        this < 100L -> 50
        this < 250L -> 100
        this < 500L -> 250
        this < 1_000L -> 500
        else -> 1_000
    }
}

private fun Float.ratioBucket(): Int {
    if (this < 0f) {
        return -1
    }
    return (this.coerceAtMost(1f) * 10f)
        .roundToInt()
        .coerceIn(0, 10)
}

private fun Float.headroomBucket(): Int {
    if (isNaN() || this < 0f) {
        return -1
    }
    return (this * 4f)
        .roundToInt()
        .coerceIn(0, 40)
}
