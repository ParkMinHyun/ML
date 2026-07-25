package com.samsung.android.camera.core2.ml

/**
 * Empirical distribution of one learned quantity's recent observations, weighted toward the recent ones: each
 * capture ages the existing samples once and then appends that capture's observations, so a burst's later shots
 * outweigh its first without any trend carrying its own smoothing factor. Bounded, because ageing prunes the
 * samples that can no longer move a read.
 *
 * Every learned trend keeps one of these and reads the statistic its role calls for - [median] for a robust centre,
 * [mean] for a right-skewed quantity that must not be under-estimated, [expectedMaximum] for a safety bound. Each is a
 * named statistic rather than a tunable fraction, so no caller has a percentile to pick.
 *
 * Scores are kept in ascending order on insertion, so a read walks the samples once and never filters, maps, or
 * sorts them. Mean reads are order-independent and share the same ordered storage.
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
        // than ~130 captures weighs < 1e-6 and can no longer move any read.
        samples.removeAll { it.weight < WEIGHT_PRUNE_THRESHOLD }
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

    /**
     * The largest score n effective samples are expected to produce, 0.0 while empty: with n of them the maximum
     * sits at quantile n / (n + 1). So a thin history stays near its centre instead of extrapolating a tail it has
     * never observed, and reaches further out only as it earns the samples to justify it (n = 19 -> ~p95). That
     * makes it the safety bound a caller wants, self-calibrating, with no fixed percentile to tune per device.
     */
    fun expectedMaximum(): Double {
        // Kish effective sample size: how many captures the decayed history effectively still counts as.
        var weightSum = 0.0
        var squaredWeightSum = 0.0
        for (sample in samples) {
            weightSum += sample.weight
            squaredWeightSum += sample.weight * sample.weight
        }
        if (weightSum <= 0.0 || squaredWeightSum <= 0.0) {
            return 0.0
        }
        val effectiveSampleCount = (weightSum * weightSum) / squaredWeightSum
        return quantile(1.0 - 1.0 / (effectiveSampleCount + 1.0))
    }

    /** Score at [fraction] of the total weight; 0.0 while empty. Samples are already score-ordered. */
    private fun quantile(fraction: Double): Double {
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

    /** Mutated in place by [decay]: ageing the whole history must not allocate once per sample per capture. */
    private class WeightedSample(val score: Double) {
        var weight: Double = 1.0
    }

    private companion object {
        /**
         * The one recency control behind every learned trend, so none of them carries its own smoothing factor.
         * It also sets how far [expectedMaximum] may reach: the effective sample count it derives is (1 + d)/(1 - d),
         * so 0.9 counts ~19 captures and bounds at ~p95 - the standard safety quantile, self-calibrating to each
         * device. Lowering it thins that tail (0.8 -> p90); raising it only adds delay (0.98 -> p99).
         */
        const val WEIGHT_DECAY = 0.90
        const val WEIGHT_PRUNE_THRESHOLD = 1e-6
    }
}
