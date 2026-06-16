package com.samsung.android.camera.core2.ml

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * EWMA + online conformal residual calibration predictor for draft-sequence admission.
 *
 * This class implements only the model-specific hooks of [DraftSequenceExecutionPredictor]; all
 * workload bucketing, routing, and history replay are inherited from the base. It receives an
 * already-bucketed workload key and turns (key, pre/post metrics) into a calibrated prediction.
 *
 * Constant-free by construction
 * -----------------------------
 * Model and capture characteristics vary too widely across the fleet for hand-set, dimensioned
 * (ms) or regime-specific (per-thermal-level) constants to transfer. Everything is derived from
 * exactly TWO device-invariant, dimensionless probabilities:
 *
 *   1. [targetBreachRate] - how often the issued upper bound may be exceeded (a timeout). Read off
 *      the cost ratio (timeout ~50x as costly as an over-skip -> ~0.02). Drives every quantile and
 *      sample-count threshold:
 *        calibrationWindowSize        = ceil(2 / targetBreachRate)
 *        minimumSamplesForCalibration = ceil(1 / targetBreachRate)
 *        cold parametric bridge       = Student-t at (1 - targetBreachRate)
 *        ACI step gamma               = targetBreachRate (clamped to 0.005..0.05)
 *   2. [driftFalseAlarmRate] - the change detector's tolerated false-alarm rate. Sets the detector
 *      threshold (= -ln(driftFalseAlarmRate)); the adaptation gain itself is parameter-free.
 *
 * Point predictor: adaptive running mean. The center is a running mean with gain alpha = 1 / nEff.
 * nEff grows while stationary (noise is smoothed) and is reset by a two-sided CUSUM change detector
 * on a persistent shift (the mean re-locks fast). A thermal escalation only lowers the detector's
 * threshold (earlier reaction); it never enters the bound. The two-sided detector also pulls the
 * center down fast when execution gets faster - the negative-residual handling, done structurally.
 *
 * Upper bound: target-coverage, self-selecting quantile (Adaptive Conformal Inference, Gibbs &
 * Candes 2021). The margin is the empirical positive-residual quantile, but the quantile is driven
 * online to hit [targetBreachRate]; a breach widens it, a non-breach (incl. every over-prediction)
 * relaxes it. The residual window stays one-sided (the correct, tight design for an upper bound).
 *
 * This is online residual calibration, not an unconditional coverage guarantee: mobile thermal
 * drift violates exchangeability, so the guarantee must be evaluated empirically on device logs.
 */
class ConformalEwmaDraftSequenceExecutionPredictor @JvmOverloads constructor(
    /** Knob #1: tolerated upper-bound breach (timeout) rate. Drives every quantile/threshold. */
    private val targetBreachRate: Double = 0.02,
    /** Knob #2: change-detector false-alarm rate. Drives center adaptation speed. */
    private val driftFalseAlarmRate: Double = 0.01,
) : DraftSequenceExecutionPredictor() {

    override val name: String = "draft_sequence_execution_conformal_ewma"

    // --- Everything below is derived from the two knobs above (no independent constants). ---

    /** ~2 expected tail exceedances kept in the calibration window. */
    private val calibrationWindowSize: Int =
        ceil(2.0 / targetBreachRate).toInt().coerceAtLeast(PARAMETRIC_MIN_SAMPLES)

    /** Need >= 1 expected exceedance before an empirical quantile is meaningful. */
    private val minimumSamplesForCalibration: Int =
        ceil(1.0 / targetBreachRate).toInt().coerceAtLeast(PARAMETRIC_MIN_SAMPLES)

    /** Coverage target the parametric bridge aims at while a cell is still cold. */
    private val coverageTarget: Double = (1.0 - targetBreachRate).coerceIn(0.5, 0.999)

    /** ACI step size, tied to the target (Gibbs & Candes use 0.005..0.05); not tuned separately. */
    private val quantileAdaptationSpeed: Double = targetBreachRate.coerceIn(0.005, 0.05)

    /** CUSUM/SPRT log-evidence threshold from the false-alarm rate. */
    private val driftThreshold: Double = -ln(driftFalseAlarmRate.coerceIn(1e-6, 0.5))

    private val durationStatsByWorkload: MutableMap<String, AdaptiveRunningMean> = mutableMapOf()
    private val positiveResidualsByWorkload: MutableMap<String, RollingQuantile> = mutableMapOf()
    private val combinedPositiveResidualsByDecision: MutableMap<String, RollingQuantile> = mutableMapOf()

    private val globalPositiveResiduals = RollingQuantile(calibrationWindowSize)
    private val globalCombinedPositiveResiduals = RollingQuantile(calibrationWindowSize)

    // Warm-phase coverage controllers (Adaptive Conformal Inference). One per decision quantity:
    // node/single duration vs. combined stage+tail admission. Breaches are sparse, so these are
    // kept coarse (one each) for signal density rather than split per workload cell.
    private val singleQuantileController = AdaptiveQuantileController(
        targetBreachRate = targetBreachRate,
        gamma = quantileAdaptationSpeed,
        initialQuantile = coverageTarget,
    )
    private val combinedQuantileController = AdaptiveQuantileController(
        targetBreachRate = targetBreachRate,
        gamma = quantileAdaptationSpeed,
        initialQuantile = coverageTarget,
    )

    @Synchronized
    override fun predictForKey(
        executionKey: String,
        workloadKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val quantile = singleQuantileController.quantile
        val stats = durationStatsByWorkload[workloadKey]
        val predictedMs = stats?.centerMs ?: 0.0
        val marginMs = calibratedSingleMargin(workloadKey, quantile)
        val upperBoundMs = if (stats == null || stats.count == 0 || marginMs == null) {
            // No center yet, or too few residuals to form any margin -> never skip (always admit).
            0L
        } else {
            (predictedMs + marginMs).roundToLong()
        }
        val predictedDurationMs = predictedMs.roundToLong()
        val budgetMs = preExecutionMetrics.budgetMs
        val addmit = upperBoundMs <= budgetMs
        val reason = buildString {
            append("model=").append(name)
            append(" type=single")
            append(" key=").append(executionKey)
            append(" workload=").append(workloadKey)
            append(" samples=").append(stats?.count ?: 0)
            append(" q=").append(quantile)
            append(" marginMs=").append(marginMs?.roundToLong() ?: 0L)
            append(" budgetMs=").append(budgetMs)
            append(" slackMs=").append(budgetMs - upperBoundMs)
            append(" shouldRun=").append(addmit)
        }
        return ExecutionPrediction(
            predictedDurationMs = predictedDurationMs,
            predictedUpperBoundMs = upperBoundMs,
            confidence = confidenceFromCount(
                sampleCount = stats?.count ?: 0,
                calibrationCount = calibrationCount(workloadKey),
            ),
            reason = reason,
            predictorName = name,
            addmit = addmit,
        )
    }

    @Synchronized
    override fun updateForKey(
        executionKey: String,
        workloadKey: String,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        val observedMs = postExecutionMetrics.durationMs.coerceAtLeast(0L)
        if (observedMs <= 0L) {
            return
        }
        // Issued prediction reflects pre-update state, matching exactly what predict() handed out.
        val issued = predictForKey(executionKey, workloadKey, preExecutionMetrics)

        // Center: adaptive running mean with CUSUM-driven forgetting + thermal-escalation prior.
        durationStatsByWorkload.getOrPut(workloadKey) {
            AdaptiveRunningMean(maxEffectiveSamples = calibrationWindowSize, driftThreshold = driftThreshold)
        }.update(observedMs.toDouble(), thermalSeverity(preExecutionMetrics))

        // Upper-margin window stays ONE-SIDED: residual measured against the issued center.
        val positiveResidualMs = (observedMs - issued.predictedDurationMs).coerceAtLeast(0L)
        positiveResidualsByWorkload.getOrPut(workloadKey) {
            RollingQuantile(calibrationWindowSize)
        }.add(positiveResidualMs)
        globalPositiveResiduals.add(positiveResidualMs)

        // Drive the single-path coverage controller toward targetBreachRate.
        singleQuantileController.observe(breach = observedMs > issued.predictedUpperBoundMs)
    }

    @Synchronized
    override fun predictForDecision(
        stageExecutionKey: String,
        stageWorkloadKey: String,
        tailWorkloadKey: String,
        decisionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val quantile = combinedQuantileController.quantile
        val stageStats = durationStatsByWorkload[stageWorkloadKey]
        val tailStats = durationStatsByWorkload[tailWorkloadKey]
        val stagePredMs = stageStats?.centerMs ?: 0.0
        val tailPredMs = tailStats?.centerMs ?: 0.0
        val predictedCombinedMs = stagePredMs + tailPredMs
        val hasAnySample = (stageStats?.count ?: 0) > 0 || (tailStats?.count ?: 0) > 0
        val marginMs = calibratedCombinedMargin(decisionKey, quantile)
        val upperBoundMs = if (!hasAnySample || marginMs == null) {
            0L
        } else {
            (predictedCombinedMs + marginMs).roundToLong()
        }
        val budgetMs = preExecutionMetrics.budgetMs
        val addmit = upperBoundMs <= budgetMs
        val reason = buildString {
            append("model=").append(name)
            append(" type=combined")
            append(" stage=").append(stageExecutionKey)
            append(" decision=").append(decisionKey)
            append(" stageSamples=").append(stageStats?.count ?: 0)
            append(" tailSamples=").append(tailStats?.count ?: 0)
            append(" q=").append(quantile)
            append(" marginMs=").append(marginMs?.roundToLong() ?: 0L)
            append(" budgetMs=").append(budgetMs)
            append(" slackMs=").append(budgetMs - upperBoundMs)
            append(" shouldRun=").append(addmit)
        }
        return ExecutionPrediction(
            predictedDurationMs = predictedCombinedMs.roundToLong(),
            predictedUpperBoundMs = upperBoundMs,
            confidence = confidenceFromCount(
                sampleCount = (stageStats?.count ?: 0) + (tailStats?.count ?: 0),
                calibrationCount = combinedCalibrationCount(decisionKey),
            ),
            reason = reason,
            predictorName = name,
            addmit = addmit,
        )
    }

    @Synchronized
    override fun updateForDecision(
        decisionKey: String,
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
            RollingQuantile(calibrationWindowSize)
        }.add(positiveResidualMs)
        globalCombinedPositiveResiduals.add(positiveResidualMs)
        // A "no breach" here (which includes every over-prediction / negative residual) relaxes the
        // quantile - the formal mechanism by which negative residuals lower the bound.
        combinedQuantileController.observe(breach = actualCombinedMs > predictedCombinedUpperBoundMs)
    }

    /**
     * Best margin a single window can supply for this quantile: the empirical quantile once the
     * window is warm (>= minimumSamplesForCalibration), otherwise a conservative Student-t bound
     * during the cold band (>= PARAMETRIC_MIN_SAMPLES), otherwise null (too few samples to say
     * anything - the caller falls back to a coarser pool, the global pool, or "always admit").
     */
    private fun windowMargin(window: RollingQuantile, quantile: Double): Double? {
        return when {
            window.count >= minimumSamplesForCalibration -> window.quantile(quantile)
            window.count >= PARAMETRIC_MIN_SAMPLES -> window.studentTUpperBound(coverageTarget)
            else -> null
        }
    }

    private fun calibratedSingleMargin(workloadKey: String, quantile: Double): Double? {
        // Hierarchical fallback: exact cell -> coarser key prefix -> global pool. Each tier first
        // tries the empirical quantile, then the Student-t warmup bound while still cold. Returns
        // null only when no tier has even PARAMETRIC_MIN_SAMPLES residuals.
        val candidates = mutableListOf<Double>()
        val exact = positiveResidualsByWorkload[workloadKey]?.let { windowMargin(it, quantile) }
        if (exact != null) {
            candidates.add(exact)
        } else {
            val prefix = workloadKey.substringBefore('|')
            val coarser = positiveResidualsByWorkload.entries
                .filter { it.key.substringBefore('|') == prefix }
                .mapNotNull { windowMargin(it.value, quantile) }
                .maxOrNull()
            if (coarser != null) {
                candidates.add(coarser)
            }
        }
        windowMargin(globalPositiveResiduals, quantile)?.let { candidates.add(it) }
        return candidates.maxOrNull()
    }

    private fun calibratedCombinedMargin(decisionKey: String, quantile: Double): Double? {
        val candidates = mutableListOf<Double>()
        val exact = combinedPositiveResidualsByDecision[decisionKey]?.let { windowMargin(it, quantile) }
        if (exact != null) {
            candidates.add(exact)
        } else {
            val stagePrefix = decisionKey.substringBefore('|')
            val coarser = combinedPositiveResidualsByDecision.entries
                .filter { it.key.substringBefore('|') == stagePrefix }
                .mapNotNull { windowMargin(it.value, quantile) }
                .maxOrNull()
            if (coarser != null) {
                candidates.add(coarser)
            }
        }
        windowMargin(globalCombinedPositiveResiduals, quantile)?.let { candidates.add(it) }
        return candidates.maxOrNull()
    }

    private fun calibrationCount(workloadKey: String): Int {
        return max(
            positiveResidualsByWorkload[workloadKey]?.count ?: 0,
            globalPositiveResiduals.count,
        )
    }

    private fun combinedCalibrationCount(decisionKey: String): Int {
        return max(
            combinedPositiveResidualsByDecision[decisionKey]?.count ?: 0,
            globalCombinedPositiveResiduals.count,
        )
    }

    private fun confidenceFromCount(sampleCount: Int, calibrationCount: Int): Float {
        // Warm scale = minimumSamplesForCalibration (itself derived from targetBreachRate). Reporting
        // only; never enters the admission decision.
        val warm = minimumSamplesForCalibration.toFloat()
        val sampleConfidence = sampleCount.toFloat() / (sampleCount + warm)
        val calibrationConfidence = calibrationCount.toFloat() / (calibrationCount + warm)
        return (sampleConfidence * calibrationConfidence)
            .coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
    }

    /**
     * A single monotone thermal-severity scalar in [0,1], used only as the change detector's
     * escalation prior - it selects an adaptation *speed* within a cell, never splits a cell, never
     * enters the bound. Both signals are normalized by their platform spec range and fused by
     * worst-of (max), so there are no fusion weights.
     */
    private fun thermalSeverity(preExecutionMetrics: PreExecutionMetrics): Double {
        val thermal = preExecutionMetrics.thermalSnapshot
        val statusTerm = thermal.thermalStatus.coerceIn(0, THERMAL_STATUS_MAX).toDouble() /
            THERMAL_STATUS_MAX
        val overheatTerm = thermal.overheatLevel.coerceIn(0, OVERHEAT_LEVEL_MAX).toDouble() /
            OVERHEAT_LEVEL_MAX
        return max(statusTerm, overheatTerm).coerceIn(0.0, 1.0)
    }

    /**
     * Point estimator: parameter-free adaptive running mean. Gain alpha = 1 / nEff; nEff grows while
     * stationary and resets on a CUSUM-detected persistent shift, so it is slow on noise and fast on
     * regime change with NO tuned constants. The detector is two-sided, so a downward shift resets
     * the mean just as fast as an upward one.
     */
    private class AdaptiveRunningMean(
        private val maxEffectiveSamples: Int,
        driftThreshold: Double,
    ) {
        var count: Int = 0
            private set

        /** What predict() should issue. */
        var centerMs: Double = 0.0
            private set

        private var varEwma: Double = 0.0
        private var effectiveSamples: Int = 0
        private var lastSeverity: Double = 0.0
        private val detector = DriftDetector(driftThreshold)

        fun update(observedMs: Double, severity: Double) {
            if (count == 0) {
                centerMs = observedMs
                effectiveSamples = 1
                lastSeverity = severity
                count = 1
                return
            }

            val innovation = observedMs - centerMs

            var drift = false
            if (count >= SCALE_WARMUP) {
                val scale = sqrt(varEwma).coerceAtLeast(1e-6)
                val z = innovation / scale
                val severityBoost = (severity - lastSeverity).coerceIn(0.0, 1.0)
                drift = detector.observe(z, severityBoost)
            }
            if (drift) {
                effectiveSamples = 1
            }
            effectiveSamples = (effectiveSamples + 1).coerceAtMost(maxEffectiveSamples)

            val alpha = 1.0 / effectiveSamples
            centerMs += alpha * innovation
            varEwma = (1.0 - alpha) * (varEwma + alpha * innovation * innovation)

            lastSeverity = severity
            count++
        }
    }

    /**
     * Two-sided CUSUM (Page's test) on standardized innovations. The decision threshold is the only
     * parameter (derived from the false-alarm rate). The allowance k = 0.5 is the canonical CUSUM
     * design value for a 1-sigma shift. A thermal-escalation boost in [0,1] lowers the effective
     * threshold so an upward thermal transient is detected sooner.
     */
    private class DriftDetector(private val threshold: Double) {
        private var cumulativeUp: Double = 0.0
        private var cumulativeDown: Double = 0.0

        fun observe(z: Double, severityBoost: Double): Boolean {
            cumulativeUp = (cumulativeUp + z - CUSUM_ALLOWANCE).coerceAtLeast(0.0)
            cumulativeDown = (cumulativeDown - z - CUSUM_ALLOWANCE).coerceAtLeast(0.0)
            val effectiveThreshold = threshold * (1.0 - severityBoost.coerceIn(0.0, 1.0))
            val fired = cumulativeUp > effectiveThreshold || cumulativeDown > effectiveThreshold
            if (fired) {
                cumulativeUp = 0.0
                cumulativeDown = 0.0
            }
            return fired
        }
    }

    /**
     * Target-coverage quantile controller (Adaptive Conformal Inference, Gibbs & Candes 2021).
     *     q <- clamp( q + gamma * (breach_t - targetBreachRate) )
     * A breach raises q (widen); a non-breach - incl. every over-prediction - relaxes q.
     */
    private class AdaptiveQuantileController(
        private val targetBreachRate: Double,
        private val gamma: Double,
        initialQuantile: Double,
    ) {
        var quantile: Double = initialQuantile.coerceIn(Q_MIN, Q_MAX)
            private set

        fun observe(breach: Boolean) {
            val err = if (breach) 1.0 else 0.0
            quantile = (quantile + gamma * (err - targetBreachRate)).coerceIn(Q_MIN, Q_MAX)
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

        /**
         * Conservative Student-t upper bound = mean + t_{n-1}(p) * sample std, for the cold phase.
         * The t-factor widens automatically for small n (vs a fixed normal factor), the correct,
         * derived, safe direction while the window fills. p is derived from the target breach rate.
         */
        fun studentTUpperBound(p: Double): Double {
            val n = values.size
            if (n < PARAMETRIC_MIN_SAMPLES) {
                return 0.0
            }
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / (n - 1).toDouble()
            val standardDeviation = sqrt(variance)
            return mean + studentTQuantile(p, n - 1) * standardDeviation
        }
    }

    private companion object {
        // Platform spec ranges (NOT tuned): Android PowerManager thermal status spans 0..6
        // (NONE..SHUTDOWN); Samsung overheatLevel spans 0..6. Used only to normalize the two
        // thermal signals to [0,1] before taking their worst-case for the escalation delta.
        private const val THERMAL_STATUS_MAX = 6
        private const val OVERHEAT_LEVEL_MAX = 6

        // Structural numeric-method floors (NOT tuned):
        //  - need a few points before standardizing innovations for the change detector.
        private const val SCALE_WARMUP = 4
        //  - the Cornish-Fisher t-quantile is trustworthy for df >= 5, i.e. n >= 6.
        private const val PARAMETRIC_MIN_SAMPLES = 6
        //  - canonical CUSUM allowance k = delta/2 for detecting a 1-sigma shift.
        private const val CUSUM_ALLOWANCE = 0.5
        //  - treat the t-distribution as normal once df is large.
        private const val LARGE_DF = 200

        // Quantile clamps + confidence reporting bounds (numeric guards; never tuned per device).
        private const val Q_MIN = 0.50
        private const val Q_MAX = 0.999
        private const val MIN_CONFIDENCE = 0.05f
        private const val MAX_CONFIDENCE = 0.95f

        /**
         * Inverse standard-normal CDF (Acklam's rational approximation; abs error < 1.15e-9). The
         * coefficients are fixed properties of the approximation - a numerical method, not tuning.
         */
        private fun inverseNormalCdf(p: Double): Double {
            val pp = p.coerceIn(1e-12, 1.0 - 1e-12)
            val a = doubleArrayOf(
                -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00,
            )
            val b = doubleArrayOf(
                -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01,
            )
            val c = doubleArrayOf(
                -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00,
            )
            val d = doubleArrayOf(
                7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00,
            )
            val pLow = 0.02425
            val pHigh = 1.0 - pLow
            return when {
                pp < pLow -> {
                    val q = sqrt(-2.0 * ln(pp))
                    (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                        ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
                }
                pp <= pHigh -> {
                    val q = pp - 0.5
                    val r = q * q
                    (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                        (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
                }
                else -> {
                    val q = sqrt(-2.0 * ln(1.0 - pp))
                    -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                        ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
                }
            }
        }

        /**
         * Student-t quantile via the Cornish-Fisher expansion around the normal quantile. Accurate
         * for df >= 5 (the band where it is used); collapses to the normal quantile for large df.
         */
        private fun studentTQuantile(p: Double, df: Int): Double {
            val z = inverseNormalCdf(p)
            if (df >= LARGE_DF || df <= 0) {
                return z
            }
            val nu = df.toDouble()
            val z2 = z * z
            val z3 = z2 * z
            val z5 = z3 * z2
            val z7 = z5 * z2
            val g1 = (z3 + z) / 4.0
            val g2 = (5.0 * z5 + 16.0 * z3 + 3.0 * z) / 96.0
            val g3 = (3.0 * z7 + 19.0 * z5 + 17.0 * z3 - 15.0 * z) / 384.0
            return z + g1 / nu + g2 / (nu * nu) + g3 / (nu * nu * nu)
        }
    }
}
