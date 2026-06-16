package com.samsung.android.camera.core2.ml

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Baseline EWMA predictor with residual upper-bound correction.
 *
 * This intentionally keeps only the basic study behavior:
 *   - point estimate: per-workload EWMA duration,
 *   - upper bound: fixed rolling quantile of the same cell's recent positive residuals,
 *   - no minimum residual sample gate, margin floor, adaptive conformal quantile, drift detector,
 *     pressure logic, or parametric bridge.
 */
class EwmaDraftSequenceExecutionPredictor @JvmOverloads constructor(
    private val ewmaAlpha: Double = 0.20,
    private val residualWindowSize: Int = 96,
    private val residualQuantile: Double = 0.95,
) : DraftSequenceExecutionPredictor() {

    override val name: String = "draft_sequence_execution_ewma"

    private val durationStatsByWorkload: MutableMap<WorkloadKey, EwmaStats> = mutableMapOf()
    private val positiveResidualsByWorkload: MutableMap<WorkloadKey, RollingQuantile> = mutableMapOf()
    private val combinedPositiveResidualsByDecision: MutableMap<DecisionKey, RollingQuantile> = mutableMapOf()

    @Synchronized
    override fun predictForKey(
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val stats = durationStatsByWorkload[workloadKey]
        val predictedMs = stats?.ewmaMs ?: 0.0
        val predictedDurationMs = predictedMs.roundToLong()
        val marginMs = residualMargin(workloadKey)
        val upperBoundMs = if (stats == null || stats.count == 0) {
            0L
        } else {
            (predictedMs + marginMs).roundToLong()
        }
        val budgetMs = preExecutionMetrics.budgetMs
        val admit = upperBoundMs <= budgetMs
        val reason = buildString {
            append("model=").append(name)
            append(" type=single")
            append(" workload=").append(workloadKey.value)
            append(" samples=").append(stats?.count ?: 0)
            append(" residualSamples=").append(residualCount(workloadKey))
            append(" q=").append(residualQuantile)
            append(" marginMs=").append(marginMs.roundToLong())
            append(" budgetMs=").append(budgetMs)
            append(" slackMs=").append(budgetMs - upperBoundMs)
            append(" shouldRun=").append(admit)
        }

        return ExecutionPrediction(
            predictedDurationMs = predictedDurationMs,
            predictedUpperBoundMs = upperBoundMs,
            confidence = confidenceFromCount(
                sampleCount = stats?.count ?: 0,
                residualCount = residualCount(workloadKey),
            ),
            reason = reason,
            predictorName = name,
            admit = admit,
        )
    }

    @Synchronized
    override fun updateForKey(
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        val observedMs = postExecutionMetrics.durationMs.coerceAtLeast(0L)
        if (observedMs <= 0L) {
            return
        }

        val issued = predictForKey(workloadKey, preExecutionMetrics)
        durationStatsByWorkload.getOrPut(workloadKey) { EwmaStats() }
            .update(observedMs.toDouble(), ewmaAlpha)

        val positiveResidualMs = (observedMs - issued.predictedDurationMs).coerceAtLeast(0L)
        positiveResidualsByWorkload.getOrPut(workloadKey) {
            RollingQuantile(residualWindowSize)
        }.add(positiveResidualMs)
    }

    @Synchronized
    override fun predictForDecision(
        stageWorkloadKey: WorkloadKey,
        tailWorkloadKey: WorkloadKey,
        decisionKey: DecisionKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val stageStats = durationStatsByWorkload[stageWorkloadKey]
        val tailStats = durationStatsByWorkload[tailWorkloadKey]
        val stagePredMs = stageStats?.ewmaMs ?: 0.0
        val tailPredMs = tailStats?.ewmaMs ?: 0.0
        val predictedCombinedMs = stagePredMs + tailPredMs
        val hasAnySample = (stageStats?.count ?: 0) > 0 || (tailStats?.count ?: 0) > 0
        val marginMs = combinedResidualMargin(decisionKey)
        val upperBoundMs = if (!hasAnySample) {
            0L
        } else {
            (predictedCombinedMs + marginMs).roundToLong()
        }
        val budgetMs = preExecutionMetrics.budgetMs
        val admit = upperBoundMs <= budgetMs
        val reason = buildString {
            append("model=").append(name)
            append(" type=combined")
            append(" decision=").append(decisionKey.value)
            append(" stageSamples=").append(stageStats?.count ?: 0)
            append(" tailSamples=").append(tailStats?.count ?: 0)
            append(" residualSamples=").append(combinedResidualCount(decisionKey))
            append(" q=").append(residualQuantile)
            append(" marginMs=").append(marginMs.roundToLong())
            append(" budgetMs=").append(budgetMs)
            append(" slackMs=").append(budgetMs - upperBoundMs)
            append(" shouldRun=").append(admit)
        }

        return ExecutionPrediction(
            predictedDurationMs = predictedCombinedMs.roundToLong(),
            predictedUpperBoundMs = upperBoundMs,
            confidence = confidenceFromCount(
                sampleCount = (stageStats?.count ?: 0) + (tailStats?.count ?: 0),
                residualCount = combinedResidualCount(decisionKey),
            ),
            reason = reason,
            predictorName = name,
            admit = admit,
        )
    }

    @Synchronized
    override fun updateForDecision(
        decisionKey: DecisionKey,
        predictedCombinedDurationMs: Long,
        predictedCombinedUpperBoundMs: Long,
        actualStageDurationMs: Long,
        actualEncodingDurationMs: Long,
        actualSavingDurationMs: Long,
    ) {
        val actualCombinedMs = actualStageDurationMs.coerceAtLeast(0L) +
            actualEncodingDurationMs.coerceAtLeast(0L) +
            actualSavingDurationMs.coerceAtLeast(0L)
        val positiveResidualMs = (actualCombinedMs - predictedCombinedDurationMs).coerceAtLeast(0L)
        combinedPositiveResidualsByDecision.getOrPut(decisionKey) {
            RollingQuantile(residualWindowSize)
        }.add(positiveResidualMs)
    }

    private fun residualMargin(workloadKey: WorkloadKey): Double {
        return positiveResidualsByWorkload[workloadKey]
            ?.quantile(residualQuantile)
            ?: 0.0
    }

    private fun combinedResidualMargin(decisionKey: DecisionKey): Double {
        return combinedPositiveResidualsByDecision[decisionKey]
            ?.quantile(residualQuantile)
            ?: 0.0
    }

    private fun residualCount(workloadKey: WorkloadKey): Int {
        return positiveResidualsByWorkload[workloadKey]?.count ?: 0
    }

    private fun combinedResidualCount(decisionKey: DecisionKey): Int {
        return combinedPositiveResidualsByDecision[decisionKey]?.count ?: 0
    }

    private fun confidenceFromCount(sampleCount: Int, residualCount: Int): Float {
        val sampleConfidence = sampleCount.toFloat() / (sampleCount + WARMUP_COUNT).toFloat()
        val residualConfidence = residualCount.toFloat() / (residualCount + WARMUP_COUNT).toFloat()
        return (sampleConfidence * residualConfidence).coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
    }

    private class EwmaStats {
        var count: Int = 0
            private set
        var ewmaMs: Double = 0.0
            private set

        fun update(observedMs: Double, alpha: Double) {
            ewmaMs = if (count == 0) {
                observedMs
            } else {
                alpha * observedMs + (1.0 - alpha) * ewmaMs
            }
            count++
        }
    }

    private class RollingQuantile(private val maxSize: Int) {
        private val values = ArrayDeque<Double>()

        val count: Int
            get() = values.size

        fun add(value: Long) {
            add(value.toDouble())
        }

        fun add(value: Double) {
            values.addLast(value.coerceAtLeast(0.0))
            while (values.size > maxSize) {
                values.removeFirst()
            }
        }

        fun quantile(q: Double): Double {
            if (values.isEmpty()) {
                return 0.0
            }
            val sortedValues = values.toList().sorted()
            val rank = ceil(q.coerceIn(0.0, 1.0) * sortedValues.size.toDouble()).toInt() - 1
            val index = rank.coerceIn(0, sortedValues.lastIndex)
            return sortedValues[index]
        }
    }

    private companion object {
        private const val WARMUP_COUNT = 12
        private const val MIN_CONFIDENCE = 0.05f
        private const val MAX_CONFIDENCE = 0.90f
    }
}
