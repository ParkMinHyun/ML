package com.samsung.android.camera.core2.ml

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

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

    override val name: String = "ewma"

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
                predictorName = name,
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
            predictorName = name,
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
