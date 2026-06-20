package com.samsung.android.camera.core2.ml

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Baseline EWMA predictor with residual upper-bound correction.
 *
 * Per workload: the point estimate is the EWMA duration; the upper bound adds a fixed rolling
 * quantile of that cell's recent positive residuals. Admission sums these per-workload upper bounds
 * in the base class - this predictor only ever sees one workload at a time.
 */
class EwmaDraftSequenceExecutionPredictor @JvmOverloads constructor(
    private val ewmaAlpha: Double = 0.20,
    private val residualWindowSize: Int = 96,
    private val residualQuantile: Double = 0.95,
) : DraftSequenceExecutionPredictor() {

    private val durationStatsByWorkload: MutableMap<WorkloadKey, EwmaStats> = mutableMapOf()
    private val positiveResidualsByWorkload: MutableMap<WorkloadKey, RollingQuantile> = mutableMapOf()

    @Synchronized
    override fun predictWorkload(
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val stats = durationStatsByWorkload[workloadKey]

        val predictedMs = stats?.ewmaMs ?: 0.0
        val marginMs = residualMargin(workloadKey)
        val upperBoundMs = if (stats == null || stats.count == 0) {
            0L
        } else {
            (predictedMs + marginMs).roundToLong()
        }

        return ExecutionPrediction(
            predictedDurationMs = predictedMs.roundToLong(),
            predictedUpperBoundMs = upperBoundMs,
            admit = upperBoundMs <= preExecutionMetrics.budgetMs,
        )
    }

    @Synchronized
    override fun updateWorkload(
        workloadKey: WorkloadKey,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        val observedMs = postExecutionMetrics.durationMs.coerceAtLeast(0L)
        if (observedMs <= 0L) {
            return
        }

        // Residual is measured against the point estimate from before this observation.
        val stats = durationStatsByWorkload.getOrPut(workloadKey) { EwmaStats() }
        val predictedDurationMs = stats.ewmaMs.roundToLong()
        stats.update(observedMs.toDouble(), ewmaAlpha)

        if (predictedDurationMs > 0) {
            val positiveResidualMs = (observedMs - predictedDurationMs).coerceAtLeast(0L)
            positiveResidualsByWorkload.getOrPut(workloadKey) { RollingQuantile(residualWindowSize) }.add(positiveResidualMs)
        }
    }

    private fun residualMargin(workloadKey: WorkloadKey): Double {
        return positiveResidualsByWorkload[workloadKey]
            ?.quantile(residualQuantile)
            ?: 0.0
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
}
