package com.samsung.android.camera.core2.ml

import android.util.Size
import com.samsung.android.camera.core2.node.NodeId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Thermal-bucketed EWMA cost model for draft-sequence admission.
 *
 * Every learned cost is keyed by a [WorkloadSequenceKey]. Each entry keeps a global EWMA plus a per
 * thermalStatus EWMA bucket, sharing one positive-residual quantile for the upper-bound margin. A single
 * stage is just a length-1 sequence, so one map serves both the per-stage fallback and the per-suffix bound.
 *
 * Prediction prefers the current thermalStatus bucket, falls back to the global EWMA, then to the
 * cold-start policy below: [predictAdmission] uses the learned suffix bound once that exact suffix has
 * enough samples, otherwise it blends toward the conservative per-stage upper-bound sum. Suffix keys are
 * plain ordered lists, so adding a stage needs only a new [WorkloadKey] mapping - no new combination class.
 */
class DraftSequenceExecutionPredictor {

    private val models = mutableMapOf<WorkloadSequenceKey, ThermalBucketedEwmaModel>()

    /**
     * Admission against a dynamically-built remaining suffix, e.g. Bokeh entry ->
     * [Bokeh, Filter, FinalizeExecution], Filter entry -> [Filter, FinalizeExecution]. Admits when the
     * predicted suffix upper bound fits the remaining budget. Predictions use the current thermalStatus.
     */
    @Synchronized
    fun predictAdmission(
        sequenceKey: WorkloadSequenceKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val thermalStatus = preExecutionMetrics.thermalSnapshot.thermalStatus
        val fallback = sequenceKey.workloads.fold(WorkloadPrediction.ZERO) { acc, key ->
            acc + predictWorkload(key, thermalStatus)
        }
        val prediction = predictWorkloadSequence(sequenceKey, thermalStatus, fallback)
        return ExecutionPrediction(
            admit = prediction.predictedUpperBoundMs <= preExecutionMetrics.budgetMs,
            predictedDurationMs = prediction.predictedDurationMs,
            predictedUpperBoundMs = prediction.predictedUpperBoundMs,
        )
    }

    @Synchronized
    fun updateNodeExecution(
        nodeExecutionMetrics: NodeExecutionMetrics,
        resultImageSize: Size,
        resultImageFormat: Int,
        isPendingRequest: Boolean,
    ) {
        val workloadKey = WorkloadKey.node(
            nodeExecutionMetrics.nodeId,
            resultImageSize,
            resultImageFormat,
            isPendingRequest,
        )
        updateModel(
            WorkloadSequenceKey(workloadKey),
            nodeExecutionMetrics.postExecutionMetrics.durationMs,
            nodeExecutionMetrics.preExecutionMetrics.thermalSnapshot.thermalStatus,
        )
    }

    /** Records the observed duration from the start of a suffix to draft task completion. */
    @Synchronized
    fun updateWorkloadSequence(sequenceKey: WorkloadSequenceKey, observedDurationMs: Long, thermalStatus: Int) {
        updateModel(sequenceKey, observedDurationMs, thermalStatus)
    }

    /** Unseen stage predicts zero: an optimistic cold start that admission tightens as it learns. */
    private fun predictWorkload(workloadKey: WorkloadKey, thermalStatus: Int): WorkloadPrediction {
        return models[WorkloadSequenceKey(workloadKey)]?.prediction(thermalStatus) ?: WorkloadPrediction.ZERO
    }

    private fun predictWorkloadSequence(
        sequenceKey: WorkloadSequenceKey,
        thermalStatus: Int,
        fallback: WorkloadPrediction,
    ): WorkloadPrediction {
        val model = models[sequenceKey] ?: return fallback
        val direct = model.prediction(thermalStatus)
        if (model.count >= DIRECT_PREDICTION_MIN_SAMPLES) {
            return direct
        }
        // Suffix cold start: blend from the conservative per-stage sum toward the learned suffix bound
        // as samples accumulate. Avoids both the too-lenient zero and the over-conservative stage sum.
        return blend(fallback, direct, model.count.toDouble() / DIRECT_PREDICTION_MIN_SAMPLES)
    }

    private fun updateModel(sequenceKey: WorkloadSequenceKey, observedDurationMs: Long, thermalStatus: Int) {
        models.getOrPut(sequenceKey) { ThermalBucketedEwmaModel() }
            .update(observedDurationMs.coerceAtLeast(0L), thermalStatus)
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

    /**
     * Point estimate for one workload (or suffix): a global EWMA plus per-thermalStatus EWMA buckets,
     * sharing one rolling positive-residual quantile that supplies the upper-bound margin.
     *
     * The global EWMA adapts with a fixed alpha; each bucket adapts with an asymmetric alpha - faster
     * ([BUCKET_ALPHA_UP]) when the stage overran its estimate or thermalStatus rose, slower
     * ([BUCKET_ALPHA_DOWN]) otherwise - so cooling decays the estimate gently while heating reacts quickly.
     */
    private class ThermalBucketedEwmaModel {
        var count: Int = 0
            private set
        private var globalMs: Double = 0.0
        private val bucketMs = mutableMapOf<Int, Double>()
        private val positiveResiduals = ArrayDeque<Double>()
        private var previousThermalStatus = 0

        /** Baseline point estimate (ms) for [thermalStatus]: its bucket if seen, else the global EWMA. */
        private fun pointMs(thermalStatus: Int): Double = bucketMs[thermalStatus] ?: globalMs

        fun prediction(thermalStatus: Int): WorkloadPrediction {
            val pointMs = pointMs(thermalStatus)
            val durationMs = pointMs.roundToNonNegativeLong()
            val upperBoundMs = (pointMs + residualQuantile())
                .roundToNonNegativeLong()
                .coerceAtLeast(durationMs)
            return WorkloadPrediction(durationMs, upperBoundMs)
        }

        fun update(observedMs: Long, thermalStatus: Int) {
            if (observedMs <= 0L) {
                return
            }
            val observed = observedMs.toDouble()
            val predictedMs = pointMs(thermalStatus).roundToNonNegativeLong()

            updateBucket(observed, thermalStatus)
            globalMs = if (count == 0) observed else GLOBAL_ALPHA * observed + (1.0 - GLOBAL_ALPHA) * globalMs
            count++
            previousThermalStatus = thermalStatus

            if (predictedMs > 0L) {
                positiveResiduals.addLast((observedMs - predictedMs).coerceAtLeast(0L).toDouble())
                while (positiveResiduals.size > RESIDUAL_WINDOW_SIZE) {
                    positiveResiduals.removeFirst()
                }
            }
        }

        /** Asymmetric-alpha EWMA for the current bucket, seeded from the global EWMA when first seen. */
        private fun updateBucket(observed: Double, thermalStatus: Int) {
            val predicted = bucketMs[thermalStatus] ?: globalMs
            val alpha = when {
                count == 0 -> 1.0
                observed > predicted -> BUCKET_ALPHA_UP
                thermalStatus > previousThermalStatus -> BUCKET_ALPHA_UP
                else -> BUCKET_ALPHA_DOWN
            }
            bucketMs[thermalStatus] = alpha * observed + (1.0 - alpha) * predicted
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

        private const val GLOBAL_ALPHA = 0.25
        private const val BUCKET_ALPHA_UP = 0.30
        private const val BUCKET_ALPHA_DOWN = 0.20
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
 * them into plan suffixes automatically. No new group type is required.
 */
sealed interface WorkloadKey {

    data class Bokeh(val sizeBucket: SizeBucket) : WorkloadKey

    data class Filter(val sizeBucket: SizeBucket) : WorkloadKey

    data class Watermark(val sizeBucket: SizeBucket) : WorkloadKey

    /** Mandatory tail from ImageCodec entry through saved draft task completion. */
    data class FinalizeExecution(
        val sizeBucket: SizeBucket,
        val imageFormat: Int,
        val isPendingRequest: Boolean,
    ) : WorkloadKey

    companion object {
        /** Bokeh and Filter are quality/admission stages; FinalizeExecution is the mandatory tail. */
        fun isAdmissionStageNode(nodeId: NodeId): Boolean {
            return when (nodeId) {
                NodeId.NODE_SEC_V1_DUAL_BOKEH,
                NodeId.NODE_SEC_V1_1_DUAL_BOKEH,
                NodeId.NODE_SEC_V2_DUAL_BOKEH,
                NodeId.NODE_SEC_FILTER -> true
                else -> false
            }
        }

        fun isFinalizeExecutionNode(nodeId: NodeId): Boolean {
            return nodeId == NodeId.NODE_SEC_V2_IMAGE_CODEC
        }

        /** Only predictable nodes (Bokeh / Filter / ImageCodec finalize) have a workload key. */
        fun node(
            nodeId: NodeId,
            resultImageSize: Size,
            resultImageFormat: Int,
            isPendingRequest: Boolean,
        ): WorkloadKey {
            return when (nodeId) {
                NodeId.NODE_SEC_V1_DUAL_BOKEH,
                NodeId.NODE_SEC_V1_1_DUAL_BOKEH,
                NodeId.NODE_SEC_V2_DUAL_BOKEH -> Bokeh(SizeBucket.of(resultImageSize))
                NodeId.NODE_SEC_FILTER -> Filter(SizeBucket.of(resultImageSize))
                NodeId.NODE_WATERMARK -> Watermark(SizeBucket.of(resultImageSize))
                NodeId.NODE_SEC_V2_IMAGE_CODEC -> finalizeExecution(resultImageSize, resultImageFormat, isPendingRequest)
                else -> error("WorkloadKey.node: unsupported nodeId $nodeId")
            }
        }

        fun finalizeExecution(resultImageSize: Size, resultImageFormat: Int, isPendingRequest: Boolean): WorkloadKey {
            return FinalizeExecution(SizeBucket.of(resultImageSize), resultImageFormat, isPendingRequest)
        }
    }
}

/**
 * Ordered key for the remaining suffix of a draft sequence, e.g. [Bokeh, Filter, FinalizeExecution].
 * The model only stores suffixes actually predicted/observed, so key growth is linear in executed plans.
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
