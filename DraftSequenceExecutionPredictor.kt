package com.samsung.android.camera.core2.ml

import android.util.Size
import com.samsung.android.camera.core2.node.NodeId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * EWMA cost model for draft-sequence admission.
 *
 * Every learned cost is keyed by a [WorkloadSequenceKey] mapping to an EWMA mean plus a positive-residual
 * quantile (point estimate + upper bound). A single stage is just a length-1 sequence, so one map serves
 * both the per-stage fallback and the per-suffix bound.
 *
 * [predictAdmission] uses the learned suffix bound once that exact suffix has enough samples, otherwise
 * it blends toward the conservative per-stage upper-bound sum. Suffix keys are plain ordered lists, so
 * adding a stage needs only a new [WorkloadKey] mapping - no new hand-written combination class.
 */
class DraftSequenceExecutionPredictor {

    private val models = mutableMapOf<WorkloadSequenceKey, EwmaModel>()

    /**
     * Admission against a dynamically-built remaining suffix, e.g. Bokeh entry ->
     * [Bokeh, Filter, Encoding, Saving], Filter entry -> [Filter, Encoding, Saving]. Admits when the
     * predicted suffix upper bound fits the remaining budget.
     */
    @Synchronized
    fun predictAdmission(
        sequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val fallback = sequenceKey.workloads.fold(WorkloadPrediction.ZERO) { acc, key ->
            acc + predictWorkload(key)
        }
        val prediction = predictWorkloadSequence(sequenceKey, fallback)
        return ExecutionPrediction(
            admit = prediction.predictedUpperBoundMs <= preExecutionMetrics.budgetMs,
            predictedDurationMs = prediction.predictedDurationMs,
            predictedUpperBoundMs = prediction.predictedUpperBoundMs,
        )
    }

    @Synchronized
    fun updateNodeExecution(nodeExecutionMetrics: NodeExecutionMetrics, outputImageFormat: Int) {
        val workloadKey = WorkloadKey.node(
            nodeExecutionMetrics.nodeId,
            nodeExecutionMetrics.inputImageSize,
            nodeExecutionMetrics.outputImageSize,
            outputImageFormat,
        ) ?: return
        updateModel(WorkloadSequenceKey(workloadKey), nodeExecutionMetrics.postExecutionMetrics)
    }

    @Synchronized
    fun updateSavingExecution(savingExecutionMetrics: SavingExecutionMetrics) {
        updateModel(
            WorkloadSequenceKey(
                WorkloadKey.saving(
                    savingExecutionMetrics.resultImageSize,
                    savingExecutionMetrics.resultImageFormat,
                    savingExecutionMetrics.isPendingRequest,
                ),
            ),
            savingExecutionMetrics.postExecutionMetrics,
        )
    }

    /** Records the observed duration from the start of a suffix to final saving completion. */
    @Synchronized
    fun updateWorkloadSequence(sequenceKey: WorkloadSequenceKey, postExecutionMetrics: PostExecutionMetrics) {
        updateModel(sequenceKey, postExecutionMetrics)
    }

    /** Unseen stage predicts zero: an optimistic cold start that admission tightens as it learns. */
    private fun predictWorkload(workloadKey: WorkloadKey): WorkloadPrediction {
        return models[WorkloadSequenceKey(workloadKey)]?.prediction() ?: WorkloadPrediction.ZERO
    }

    private fun predictWorkloadSequence(
        sequenceKey: WorkloadSequenceKey,
        fallback: WorkloadPrediction,
    ): WorkloadPrediction {
        val model = models[sequenceKey] ?: return fallback
        val direct = model.prediction()
        if (model.count >= DIRECT_PREDICTION_MIN_SAMPLES) {
            return direct
        }
        // Suffix cold start: blend from the conservative per-stage sum toward the learned suffix bound
        // as samples accumulate. Avoids both the too-lenient zero and the over-conservative stage sum.
        return blend(fallback, direct, model.count.toDouble() / DIRECT_PREDICTION_MIN_SAMPLES)
    }

    private fun updateModel(sequenceKey: WorkloadSequenceKey, postExecutionMetrics: PostExecutionMetrics) {
        models.getOrPut(sequenceKey) { EwmaModel() }
            .update(postExecutionMetrics.durationMs.coerceAtLeast(0L))
    }

    private fun blend(low: WorkloadPrediction, high: WorkloadPrediction, highWeight: Double): WorkloadPrediction {
        val w = highWeight.coerceIn(0.0, 1.0)
        val durationMs = ((1.0 - w) * low.predictedDurationMs + w * high.predictedDurationMs).roundToNonNegativeLong()
        val upperBoundMs = ((1.0 - w) * low.predictedUpperBoundMs + w * high.predictedUpperBoundMs)
            .roundToNonNegativeLong()
            .coerceAtLeast(durationMs)
        return WorkloadPrediction(durationMs, upperBoundMs)
    }

    private data class WorkloadPrediction(
        val predictedDurationMs: Long,
        val predictedUpperBoundMs: Long,
    ) {
        operator fun plus(other: WorkloadPrediction) = WorkloadPrediction(
            predictedDurationMs + other.predictedDurationMs,
            predictedUpperBoundMs + other.predictedUpperBoundMs,
        )

        companion object {
            val ZERO = WorkloadPrediction(0L, 0L)
        }
    }

    /** EWMA mean plus a rolling positive-residual quantile, for one workload or one suffix. */
    private class EwmaModel {
        var count: Int = 0
            private set
        private var ewmaMs: Double = 0.0
        private val positiveResiduals = ArrayDeque<Double>()

        fun update(observedMs: Long) {
            if (observedMs <= 0L) {
                return
            }
            val predictedMs = ewmaMs.roundToNonNegativeLong()
            ewmaMs = if (count == 0) observedMs.toDouble() else EWMA_ALPHA * observedMs + (1.0 - EWMA_ALPHA) * ewmaMs
            count++
            if (predictedMs > 0L) {
                positiveResiduals.addLast((observedMs - predictedMs).coerceAtLeast(0L).toDouble())
                while (positiveResiduals.size > RESIDUAL_WINDOW_SIZE) {
                    positiveResiduals.removeFirst()
                }
            }
        }

        fun prediction(): WorkloadPrediction {
            val durationMs = ewmaMs.roundToNonNegativeLong()
            val upperBoundMs = (ewmaMs + residualQuantile())
                .roundToNonNegativeLong()
                .coerceAtLeast(durationMs)
            return WorkloadPrediction(durationMs, upperBoundMs)
        }

        private fun residualQuantile(): Double {
            if (positiveResiduals.isEmpty()) {
                return 0.0
            }
            val sorted = positiveResiduals.sorted()
            val rank = ceil(RESIDUAL_QUANTILE * sorted.size).toInt() - 1
            return sorted[rank.coerceIn(0, sorted.lastIndex)]
        }
    }

    companion object {
        /** Process-wide learned model shared by profilers created across captures. */
        @JvmStatic
        val instance = DraftSequenceExecutionPredictor()

        private const val EWMA_ALPHA = 0.20
        private const val RESIDUAL_WINDOW_SIZE = 96
        private const val RESIDUAL_QUANTILE = 0.95
        private const val DIRECT_PREDICTION_MIN_SAMPLES = 4
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
            val pixels = size.width.toLong().coerceAtLeast(0L) *
                size.height.toLong().coerceAtLeast(0L)
            val megaPixels = pixels.toDouble() / 1_000_000.0
            return entries.minByOrNull { abs(megaPixels - it.megaPixels) } ?: MP12
        }
    }
}

/**
 * Stable workload bucket shared by every predictor.
 *
 * New admission-capable stages should be added here once, then [WorkloadSequenceKey] will compose
 * them into plan suffixes automatically. No new `BokehFilterTail`-style group type is required.
 */
sealed interface WorkloadKey {

    data class Bokeh(val sizeBucket: SizeBucket) : WorkloadKey

    data class Filter(val sizeBucket: SizeBucket) : WorkloadKey

    data class Encoding(val sizeBucket: SizeBucket, val imageFormat: Int) : WorkloadKey

    data class Saving(
        val sizeBucket: SizeBucket,
        val imageFormat: Int,
        val isPendingRequest: Boolean,
    ) : WorkloadKey

    companion object {
        /** Bokeh and Filter are quality/admission stages; Encoding and Saving are mandatory tail. */
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
            outputImageFormat: Int,
        ): WorkloadKey? {
            return when (nodeId) {
                NodeId.NODE_SEC_V1_DUAL_BOKEH,
                NodeId.NODE_SEC_V1_1_DUAL_BOKEH,
                NodeId.NODE_SEC_V2_DUAL_BOKEH -> Bokeh(SizeBucket.of(outputImageSize))
                NodeId.NODE_SEC_FILTER -> Filter(SizeBucket.of(inputImageSize))
                NodeId.NODE_SEC_V2_IMAGE_CODEC -> Encoding(SizeBucket.of(outputImageSize), outputImageFormat)
                else -> null
            }
        }

        fun encoding(resultImageSize: Size, resultImageFormat: Int): WorkloadKey {
            return Encoding(SizeBucket.of(resultImageSize), resultImageFormat)
        }

        fun saving(resultImageSize: Size, resultImageFormat: Int, isPendingRequest: Boolean): WorkloadKey {
            return Saving(SizeBucket.of(resultImageSize), resultImageFormat, isPendingRequest)
        }
    }
}

/**
 * Ordered key for the remaining suffix of a draft sequence, e.g. [Bokeh, Filter, Encoding, Saving].
 * The model only stores suffixes actually predicted/observed, so key growth is linear in executed plans.
 *
 * ponytail: no defensive copy - every call site passes a freshly built list. Copy in the constructor
 * if a caller ever mutates a list after using it as a key.
 */
data class WorkloadSequenceKey(val workloads: List<WorkloadKey>) {
    constructor(workloadKey: WorkloadKey) : this(listOf(workloadKey))

    init {
        require(workloads.isNotEmpty()) { "WorkloadSequenceKey must contain at least one workload." }
    }

    override fun toString(): String = workloads.joinToString(prefix = "[", postfix = "]")
}

private fun Double.roundToNonNegativeLong(): Long = when {
    isNaN() || this <= 0.0 -> 0L
    this >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
    else -> roundToLong().coerceAtLeast(0L)
}
