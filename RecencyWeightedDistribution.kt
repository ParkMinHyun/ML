package com.samsung.android.camera.core2.ml

/**
 * Empirical distribution of one learned quantity's recent observations, weighted toward the recent ones: each
 * capture ages the existing samples once and then appends that capture's observations, so a burst's later shots
 * outweigh its first without any trend carrying its own smoothing factor. Bounded, because ageing prunes the
 * samples that can no longer move a read.
 *
 * Every learned trend keeps one of these and reads the statistic its role calls for - [quantile] at the median for
 * a robust centre, [quantile] at an adaptive high fraction for a safety bound, [mean] for a right-skewed quantity
 * that must not be under-estimated.
 *
 * Scores are kept in ascending order on insertion, so a quantile read walks the samples once and never filters,
 * maps, or sorts them. Mean reads are order-independent and share the same ordered storage.
 */
internal class RecencyWeightedDistribution {

    private val samples = mutableListOf<WeightedSample>()

    fun isEmpty(): Boolean = samples.isEmpty()

    fun add(score: Double) {
        val searchResult = samples.binarySearchBy(score) { it.score }
        val insertionIndex = if (searchResult >= 0) searchResult else -searchResult - 1
        samples.add(insertionIndex, WeightedSample(score))
    }

    /** Ages the whole history by one capture; call exactly once per capture, before that capture's [add] calls. */
    fun decay() {
        for (sample in samples) {
            sample.weight *= WEIGHT_DECAY
        }
        // ponytail: drop the negligible tail so this long-lived history stays bounded. At decay 0.9 a sample older
        // than ~130 captures weighs < 1e-6 and can no longer move any quantile or mean.
        samples.removeAll { it.weight < WEIGHT_PRUNE_THRESHOLD }
    }

    /** Score at [fraction] of the total weight; 0.0 while empty. Samples are already score-ordered. */
    fun quantile(fraction: Double): Double {
        if (samples.isEmpty()) {
            return 0.0
        }

        val targetWeight = samples.sumOf { it.weight } * fraction
        var cumulativeWeight = 0.0
        for (sample in samples) {
            cumulativeWeight += sample.weight
            if (cumulativeWeight >= targetWeight) {
                return sample.score
            }
        }
        return samples.last().score
    }

    /** Robust centre: the score with half the weight on either side. Prefer over [mean] for a spike-prone quantity. */
    fun median(): Double = quantile(0.5)

    fun mean(): Double {
        if (samples.isEmpty()) {
            return 0.0
        }

        var weightSum = 0.0
        var weightedScoreSum = 0.0
        for (sample in samples) {
            weightSum += sample.weight
            weightedScoreSum += sample.score * sample.weight
        }
        return weightedScoreSum / weightSum
    }

    /** Kish effective sample size of the decayed weights - how many captures the history effectively still holds. */
    fun effectiveSampleSize(): Double {
        var weightSum = 0.0
        var squaredWeightSum = 0.0
        for (sample in samples) {
            weightSum += sample.weight
            squaredWeightSum += sample.weight * sample.weight
        }
        if (weightSum <= 0.0 || squaredWeightSum <= 0.0) {
            return 0.0
        }
        return (weightSum * weightSum) / squaredWeightSum
    }

    /** Mutated in place by [decay]: ageing the whole history must not allocate once per sample per capture. */
    private class WeightedSample(val score: Double) {
        var weight: Double = 1.0
    }

    private companion object {
        /**
         * The one recency control behind every learned trend, so none of them carries its own smoothing factor.
         * It also sets the residual safety quantile through [effectiveSampleSize]: ESS = (1 + d) / (1 - d) ≈ 19 at
         * 0.9, so the adaptive quantile 1 - 1 / (ESS + 1) lands on ≈ p95 - the standard safety bound, self-calibrating
         * to each device. Lowering it thins that tail (0.8 → p90); raising it only adds delay (0.98 → p99).
         */
        const val WEIGHT_DECAY = 0.90
        const val WEIGHT_PRUNE_THRESHOLD = 1e-6
    }
}
