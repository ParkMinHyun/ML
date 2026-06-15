package com.samsung.android.camera.core2.ml

import android.util.Size
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * EWMA + online conformal residual calibration predictor for draft-sequence admission.
 *
 * Design intent
 * -------------
 * - Keep EWMA as the point predictor because recent execution history was the most stable signal
 *   in the current Bokeh measurements.
 * - Use online positive residual quantiles as the upper-bound calibration layer. The margin is not
 *   a hand-tuned fixed ms value: it is read from recent under-prediction errors.
 * - Use workload identity as the stable Mondrian split, with a hierarchical fallback so a cold
 *   (key x size x format) cell borrows from (key x size), then key-only, then a global pool:
 *      Bokeh : result/input image size bucket (12MP / 24MP when present)
 *      Filter: image size bucket (12MP / 24MP / 50MP when present)
 *      Tail  : result image size bucket x image format (Encoding + Saving)
 * - Thermal / memory / runtime pressure do not create hard buckets. They only select how high a
 *   residual quantile to read from the same workload calibration window.
 * - For stage admission, use decision-level combined residuals:
 *      score = actual(stage + mandatoryTail) - predicted(stage + mandatoryTail)
 *   This calibrates the exact quantity used by the controller.
 *
 * Pressure-aware quantile selection: cold-gated, not always-on
 * ------------------------------------------------------------
 * Raising the residual quantile under pressure only helps while the calibration window has not
 * yet seen pressure residuals. Once the sliding window has absorbed them, it already widens the
 * margin on its own, and an additional pressure surcharge becomes pure over-conservatism. On the
 * 8U memory-pressure logs this was measured directly through the admission counterfactual:
 *
 *   pressure-aware always-on : over-skip 2.8% (5.8% inside the pressure region), Bokeh kept 85.0%
 *   pressure-aware cold-gated : over-skip 0.4% (1.2% in pressure region),       Bokeh kept 87.6%
 *
 * Both defended the identical set of savable timeouts, so the always-on surcharge cost quality
 * without buying safety. (Note this trade-off is invisible to a pure coverage metric: by UB-miss
 * rate, always-on looks *better* - 3.2% vs 5.7% Bokeh+Tail - because the higher bound is harder
 * to exceed. The miss reductions are mostly on captures with ample slack, where the decision is
 * unaffected, which is why savable-timeout defense is unchanged. Coverage-optimal and
 * admission-optimal disagree here; the admission counterfactual is the one that matches the
 * controller's actual job.) [enablePressureAwareQuantile] therefore gates the surcharge to the
 * cold window: when a cell has at least [coldGateSampleCount] residual samples, pressure no longer
 * forces the quantile up. The runtime-pressure tracker is retained for the cold phase and for
 * devices whose pre-execution signals (e.g. RAM) actually move under pressure.
 *
 * Notes on coverage wording
 * -------------------------
 * This class should be described as online residual calibration, not as an unconditional hard
 * guarantee. Standard conformal coverage assumes exchangeability; mobile thermal drift and burst
 * progression violate it. Sliding windows and cold-gated pressure-aware quantile selection make
 * the calibrator adaptive, but the guarantee should be evaluated empirically on device logs.
 */
class ConformalEwmaDraftSequenceExecutionPredictor @JvmOverloads constructor(
    private val ewmaAlpha: Double = 0.20,
    private val calibrationWindowSize: Int = 96,
    private val minimumSamplesForCalibration: Int = 8,
    private val minimumErrorMarginMs: Long = 60L,
    /**
     * Cold-phase warmup bridge. While a window holds at least [warmupMinSamples] but fewer than
     * [minimumSamplesForCalibration] residuals, the margin uses a conservative parametric bound
     * (mean + [warmupStdDevFactor] * std) instead of an untrustworthy small-sample quantile. This
     * shortens the first-session interval where the margin is only the flat [minimumErrorMarginMs]
     * floor, without injecting any foreign data: it is built from the device's own early residuals.
     * Set [warmupMinSamples] >= [minimumSamplesForCalibration] to disable the parametric bridge.
     */
    private val warmupMinSamples: Int = 2,
    private val warmupStdDevFactor: Double = 2.0,
    private val normalQuantile: Double = 0.85,
    private val lowQuantile: Double = 0.90,
    private val highQuantile: Double = 0.95,
    private val criticalQuantile: Double = 0.99,
    private val enablePressureAwareQuantile: Boolean = true,
    /**
     * Once a cell's calibration window holds at least this many samples, pressure no longer
     * raises the quantile (the window itself has absorbed the drift). Set to 0 to restore the
     * always-on behavior; set very large to disable cold-gating.
     */
    private val coldGateSampleCount: Int = 24,
) : DraftSequenceExecutionPredictor {

    override val name: String = "draft_sequence_execution_conformal_ewma"

    private val durationStatsByWorkload: MutableMap<String, OnlineEwmaStats> = mutableMapOf()
    private val positiveResidualsByWorkload: MutableMap<String, RollingQuantile> = mutableMapOf()
    private val combinedPositiveResidualsByDecision: MutableMap<String, RollingQuantile> = mutableMapOf()

    private val globalPositiveResiduals = RollingQuantile(calibrationWindowSize)
    private val globalCombinedPositiveResiduals = RollingQuantile(calibrationWindowSize)

    private val runtimePressureTracker = RuntimePressureTracker(calibrationWindowSize)

    private var lastOverheatLevel: Int? = null
    private var lastThermalStatus: Int? = null
    private var lastPressureLevel: PressureLevel = PressureLevel.NORMAL

    @Synchronized
    override fun predict(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val workloadKey = WorkloadKey.keyOnly(executionKey)
        return predictSingleInternal(
            executionKey = executionKey,
            workloadKey = workloadKey,
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    @Synchronized
    override fun update(
        executionKey: String,
        preExecutionMetrics: PreExecutionMetrics,
        postExecutionMetrics: PostExecutionMetrics,
    ) {
        val workloadKey = WorkloadKey.keyOnly(executionKey)
        val predictionBeforeUpdate = predictSingleInternal(
            executionKey = executionKey,
            workloadKey = workloadKey,
            preExecutionMetrics = preExecutionMetrics,
        )
        updateSingleInternal(
            workloadKey = workloadKey.value,
            predictedDurationMs = predictionBeforeUpdate.predictedDurationMs,
            observedDurationMs = postExecutionMetrics.durationMs,
        )
        runtimePressureTracker.update(postExecutionMetrics)
    }

    @Synchronized
    fun predictNodeExecution(
        captureMetrics: CaptureMetrics,
        nodeId: String,
        nodeParams: NodeParams,
        inputImageSize: Size,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val workloadKey = WorkloadKey.node(
            nodeId = nodeId,
            nodeParams = nodeParams,
            inputImageSize = inputImageSize,
        )
        return predictSingleInternal(
            executionKey = nodeId,
            workloadKey = workloadKey,
            preExecutionMetrics = preExecutionMetrics,
        )
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
    fun updateNodeExecution(
        captureMetrics: CaptureMetrics,
        nodeExecutionMetrics: NodeExecutionMetrics,
    ) {
        val predictionBeforeUpdate = predictNodeExecution(captureMetrics, nodeExecutionMetrics)
        val workloadKey = WorkloadKey.node(
            nodeId = nodeExecutionMetrics.nodeId,
            nodeParams = nodeExecutionMetrics.nodeParams,
            inputImageSize = nodeExecutionMetrics.inputImageSize,
        )
        updateSingleInternal(
            workloadKey = workloadKey.value,
            predictedDurationMs = predictionBeforeUpdate.predictedDurationMs,
            observedDurationMs = nodeExecutionMetrics.postExecutionMetrics.durationMs,
        )
        runtimePressureTracker.update(nodeExecutionMetrics.postExecutionMetrics)
    }

    @Synchronized
    fun predictSavingExecution(
        captureMetrics: CaptureMetrics,
        isPendingRequest: Boolean,
        resultImageSize: Size,
        resultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val workloadKey = WorkloadKey.saving(
            isPendingRequest = isPendingRequest,
            resultImageSize = resultImageSize,
            resultImageFormat = resultImageFormat,
        )
        return predictSingleInternal(
            executionKey = SAVING_EXECUTION_KEY,
            workloadKey = workloadKey,
            preExecutionMetrics = preExecutionMetrics,
        )
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
    fun updateSavingExecution(
        captureMetrics: CaptureMetrics,
        savingExecutionMetrics: SavingExecutionMetrics,
    ) {
        val predictionBeforeUpdate = predictSavingExecution(captureMetrics, savingExecutionMetrics)
        val workloadKey = WorkloadKey.saving(savingExecutionMetrics)
        updateSingleInternal(
            workloadKey = workloadKey.value,
            predictedDurationMs = predictionBeforeUpdate.predictedDurationMs,
            observedDurationMs = savingExecutionMetrics.postExecutionMetrics.durationMs,
        )
        runtimePressureTracker.update(savingExecutionMetrics.postExecutionMetrics)
    }

    /**
     * Predicts a stage + mandatory tail together using a decision-level conformal residual.
     * Use this for stage admission:
     *     elapsedSoFar + predictedUpperBound(stage + tail) <= totalBudget
     *
     * The tail here means Encoding + Saving. Encoding node duration and Saving duration should be
     * updated later through [updateCombinedAdmission] or [updateAfterCapture].
     */
    @Synchronized
    fun predictCombinedAdmission(
        captureMetrics: CaptureMetrics,
        stageNodeId: String,
        stageNodeParams: NodeParams,
        stageInputImageSize: Size,
        tailResultImageSize: Size,
        tailResultImageFormat: Int,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val stageKey = WorkloadKey.node(
            nodeId = stageNodeId,
            nodeParams = stageNodeParams,
            inputImageSize = stageInputImageSize,
        )
        val tailKey = WorkloadKey.tail(
            resultImageSize = tailResultImageSize,
            resultImageFormat = tailResultImageFormat,
        )
        return predictCombinedInternal(
            stageExecutionKey = stageNodeId,
            stageWorkloadKey = stageKey,
            tailWorkloadKey = tailKey,
            preExecutionMetrics = preExecutionMetrics,
        )
    }

    /**
     * Updates the exact combined residual used by [predictCombinedAdmission]. Call this after the
     * current capture's stage, encoding, and saving durations are known.
     */
    @Synchronized
    fun updateCombinedAdmission(
        stageNodeId: String,
        stageNodeParams: NodeParams,
        stageInputImageSize: Size,
        tailResultImageSize: Size,
        tailResultImageFormat: Int,
        predictedCombinedDurationMs: Long,
        actualStageDurationMs: Long,
        actualEncodingDurationMs: Long,
        actualSavingDurationMs: Long,
    ) {
        val stageKey = WorkloadKey.node(
            nodeId = stageNodeId,
            nodeParams = stageNodeParams,
            inputImageSize = stageInputImageSize,
        )
        val tailKey = WorkloadKey.tail(
            resultImageSize = tailResultImageSize,
            resultImageFormat = tailResultImageFormat,
        )
        val decisionKey = DecisionKey.combined(stageKey, tailKey)
        val actualCombinedMs = actualStageDurationMs.coerceAtLeast(0L) +
            actualEncodingDurationMs.coerceAtLeast(0L) +
            actualSavingDurationMs.coerceAtLeast(0L)
        val positiveResidualMs = (actualCombinedMs - predictedCombinedDurationMs)
            .coerceAtLeast(0L)
        combinedPositiveResidualsByDecision.getOrPut(decisionKey.value) {
            RollingQuantile(calibrationWindowSize)
        }.add(positiveResidualMs)
        globalCombinedPositiveResiduals.add(positiveResidualMs)
    }

    /**
     * Replays complete capture history. Timed-out captures are skipped because later-stage samples
     * may be censored by the fallback path.
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

    private fun predictSingleInternal(
        executionKey: String,
        workloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val pressureLevel = pressureLevel(preExecutionMetrics)
        val quantile = quantileForPressure(pressureLevel, calibrationCount(workloadKey.value))
        val stats = durationStatsByWorkload[workloadKey.value]
        val predictedMs = stats?.ewmaMs ?: 0.0
        val marginMs = calibratedSingleMargin(workloadKey.value, quantile)
        val upperBoundMs = if (stats == null || stats.count == 0) {
            0L
        } else {
            (predictedMs + marginMs).roundToLong()
        }
        val predictedDurationMs = predictedMs.roundToLong()
        val budgetMs = preExecutionMetrics.budgetMs
        val reason = buildString {
            append("model=").append(name)
            append(" type=single")
            append(" key=").append(executionKey)
            append(" workload=").append(workloadKey.value)
            append(" samples=").append(stats?.count ?: 0)
            append(" q=").append(quantile)
            append(" pressure=").append(pressureLevel.label)
            append(" marginMs=").append(marginMs.roundToLong())
            append(" budgetMs=").append(budgetMs)
            append(" slackMs=").append(budgetMs - upperBoundMs)
            append(" shouldRun=").append(upperBoundMs <= budgetMs)
        }
        rememberThermal(preExecutionMetrics)
        return ExecutionPrediction(
            predictedDurationMs = predictedDurationMs,
            predictedUpperBoundMs = upperBoundMs,
            confidence = confidenceFromCount(
                sampleCount = stats?.count ?: 0,
                calibrationCount = calibrationCount(workloadKey.value),
            ),
            reason = reason,
            predictorName = name,
        )
    }

    private fun predictCombinedInternal(
        stageExecutionKey: String,
        stageWorkloadKey: WorkloadKey,
        tailWorkloadKey: WorkloadKey,
        preExecutionMetrics: PreExecutionMetrics,
    ): ExecutionPrediction {
        val pressureLevel = pressureLevel(preExecutionMetrics)
        val decisionKey = DecisionKey.combined(stageWorkloadKey, tailWorkloadKey)
        val quantile = quantileForPressure(pressureLevel, combinedCalibrationCount(decisionKey.value))
        val stageStats = durationStatsByWorkload[stageWorkloadKey.value]
        val tailStats = durationStatsByWorkload[tailWorkloadKey.value]
        val stagePredMs = stageStats?.ewmaMs ?: 0.0
        val tailPredMs = tailStats?.ewmaMs ?: 0.0
        val predictedCombinedMs = stagePredMs + tailPredMs
        val hasAnySample = (stageStats?.count ?: 0) > 0 || (tailStats?.count ?: 0) > 0
        val marginMs = calibratedCombinedMargin(decisionKey.value, quantile)
        val upperBoundMs = if (!hasAnySample) {
            0L
        } else {
            (predictedCombinedMs + marginMs).roundToLong()
        }
        val budgetMs = preExecutionMetrics.budgetMs
        val reason = buildString {
            append("model=").append(name)
            append(" type=combined")
            append(" stage=").append(stageExecutionKey)
            append(" decision=").append(decisionKey.value)
            append(" stageSamples=").append(stageStats?.count ?: 0)
            append(" tailSamples=").append(tailStats?.count ?: 0)
            append(" q=").append(quantile)
            append(" pressure=").append(pressureLevel.label)
            append(" marginMs=").append(marginMs.roundToLong())
            append(" budgetMs=").append(budgetMs)
            append(" slackMs=").append(budgetMs - upperBoundMs)
            append(" shouldRun=").append(upperBoundMs <= budgetMs)
        }
        rememberThermal(preExecutionMetrics)
        return ExecutionPrediction(
            predictedDurationMs = predictedCombinedMs.roundToLong(),
            predictedUpperBoundMs = upperBoundMs,
            confidence = confidenceFromCount(
                sampleCount = (stageStats?.count ?: 0) + (tailStats?.count ?: 0),
                calibrationCount = combinedCalibrationCount(decisionKey.value),
            ),
            reason = reason,
            predictorName = name,
        )
    }

    private fun updateSingleInternal(
        workloadKey: String,
        predictedDurationMs: Long,
        observedDurationMs: Long,
    ) {
        val observedMs = observedDurationMs.coerceAtLeast(0L)
        if (observedMs <= 0L) {
            return
        }
        durationStatsByWorkload.getOrPut(workloadKey) { OnlineEwmaStats() }
            .update(observedMs.toDouble(), ewmaAlpha)
        val positiveResidualMs = (observedMs - predictedDurationMs).coerceAtLeast(0L)
        positiveResidualsByWorkload.getOrPut(workloadKey) {
            RollingQuantile(calibrationWindowSize)
        }.add(positiveResidualMs)
        globalPositiveResiduals.add(positiveResidualMs)
    }

    /**
     * Best margin a single window can supply for this quantile: the empirical quantile once the
     * window is warm (>= minimumSamplesForCalibration), otherwise a conservative parametric bound
     * during the cold warmup band (>= warmupMinSamples), otherwise null (too few samples to say
     * anything - the caller falls back to a coarser pool or the floor).
     */
    private fun windowMargin(window: RollingQuantile, quantile: Double): Double? {
        return when {
            window.count >= minimumSamplesForCalibration -> window.quantile(quantile)
            window.count >= warmupMinSamples -> window.parametricUpperBound(warmupStdDevFactor)
            else -> null
        }
    }

    private fun calibratedSingleMargin(workloadKey: String, quantile: Double): Double {
        // Hierarchical fallback: exact cell -> coarser key prefix -> global pool. Each tier first
        // tries the empirical quantile, then the parametric warmup bound while still cold.
        // On a single-cell dataset every prefix resolves to the same window, so the prefix tier is a
        // no-op there (verified); it only engages once multiple size/format cells coexist.
        val candidates = mutableListOf<Double>()
        val exact = positiveResidualsByWorkload[workloadKey]?.let { windowMargin(it, quantile) }
        if (exact != null) {
            candidates.add(exact)
        } else {
            // Coarser tier: any window whose key shares this key's executionKey prefix
            // (e.g. "bokeh|size=24MP" falls back to the pooled "bokeh|" residuals).
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
        candidates.add(minimumErrorMarginMs.toDouble())
        return candidates.max()
    }

    private fun calibratedCombinedMargin(decisionKey: String, quantile: Double): Double {
        // Same hierarchical idea for the decision-level window: exact decision cell -> decisions
        // sharing the stage+tail execution-key prefix -> global combined pool, each with the same
        // quantile-then-parametric-warmup logic.
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
        candidates.add(minimumErrorMarginMs.toDouble())
        return candidates.max()
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
        val sampleConfidence = sampleCount.toFloat() / (sampleCount + WARMUP_COUNT).toFloat()
        val calibrationConfidence = calibrationCount.toFloat() /
            (calibrationCount + WARMUP_COUNT).toFloat()
        return (sampleConfidence * calibrationConfidence)
            .coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
    }

    private fun quantileForPressure(
        pressureLevel: PressureLevel,
        calibrationCount: Int
    ): Double {

        if (!enablePressureAwareQuantile) {
            return highQuantile
        }

        return if (calibrationCount >= coldGateSampleCount) {
            highQuantile
        } else {
            when (pressureLevel) {
                PressureLevel.NORMAL -> normalQuantile
                PressureLevel.LOW -> lowQuantile
                PressureLevel.HIGH -> highQuantile
                PressureLevel.CRITICAL -> highQuantile
            }
        }.coerceIn(0.5, 0.999)
    }

    private fun pressureLevel(preExecutionMetrics: PreExecutionMetrics): PressureLevel {
        val thermal = preExecutionMetrics.thermalSnapshot
        val overheatLevel = thermal.overheatLevel
        val thermalStatus = thermal.thermalStatus
        // overheatLevel leads thermalStatus during an upward thermal transient (measured: oh moves
        // before ts in ~29 captures vs ts-only in ~17). It is therefore used only as an escalation
        // early-warning, not as a primary axis. On device logs thermalStatus alone reproduced the
        // identical UB-miss/over-skip (the two correlate r=0.93), so the level boundaries are driven
        // by thermalStatus; overheatLevel can only push the level one notch higher when it has run
        // ahead of thermalStatus, never lower.
        val overheatEscalates = lastThermalStatus?.let { prevStatus ->
            lastOverheatLevel?.let { prevOverheat -> overheatLevel > prevOverheat } == true &&
                thermalStatus <= prevStatus
        } ?: false
        val runtimePressure = runtimePressureTracker.currentLevel()

        // Primary axis: thermalStatus. Validated jumps: ts2 is the dominant 4x residual jump; ts3-4
        // stay elevated (share HIGH) without re-jumping. runtimePressure and an overheat escalation
        // can each raise the level by one step so an upward transient is not under-protected before
        // thermalStatus catches up.
        return when {
            thermalStatus >= 4 || runtimePressure == PressureLevel.CRITICAL ->
                PressureLevel.CRITICAL
            thermalStatus >= 2 || runtimePressure == PressureLevel.HIGH || (overheatEscalates && thermalStatus >= 1) ->
                PressureLevel.HIGH
            thermalStatus >= 1 || runtimePressure == PressureLevel.LOW || overheatEscalates ->
                PressureLevel.LOW
            else -> PressureLevel.NORMAL
        }
    }

    private fun rememberThermal(preExecutionMetrics: PreExecutionMetrics) {
        val thermal = preExecutionMetrics.thermalSnapshot
        lastOverheatLevel = thermal.overheatLevel
        lastThermalStatus = thermal.thermalStatus
    }

    /**
     * Pressure levels ordered by the size of the positive residual they predict, driven primarily
     * by thermalStatus - a standard OS integer signal that is stable across device tiers, so one
     * threshold set covers the whole fleet with zero per-model configuration. On device logs (Bokeh
     * node, EWMA(0.20) residuals) the strongest single jump is at thermalStatus 2 (p90 residual
     * 30ms -> 113ms, ~4x); thermalStatus 3-4 stay elevated (~100ms) without re-jumping, so they
     * share the HIGH level. overheatLevel is used only as an escalation early-warning (it can lead
     * thermalStatus during an upward transient) - thermalStatus alone reproduced identical
     * UB-miss/over-skip (the two correlate r=0.93). A continuous thermalHeadroom split (~0.97) was
     * also evaluated and dropped: it is tier-dependent and the online residual window already
     * absorbs that signal per tier. Levels are sorted so the implied quantile margin increases
     * monotonically.
     */
    private enum class PressureLevel(val label: String) {
        NORMAL("normal"),
        LOW("low"),
        HIGH("high"),
        CRITICAL("critical"),
    }

    private data class WorkloadKey(val value: String) {
        companion object {
            fun keyOnly(executionKey: String): WorkloadKey {
                return WorkloadKey("keyOnly=$executionKey")
            }

            fun node(
                nodeId: String,
                nodeParams: NodeParams,
                inputImageSize: Size,
            ): WorkloadKey {
                return when (nodeId) {
                    NODE_ID_BOKEH -> WorkloadKey("bokeh|size=${bokehSize(nodeParams, inputImageSize)}")
                    NODE_ID_FILTER -> WorkloadKey("filter|size=${sizeBucket(inputImageSize)}")
                    NODE_ID_ENCODING -> WorkloadKey("encoding|size=${sizeBucket(inputImageSize)}|format=${encodingFormat(nodeParams)}")
                    else -> WorkloadKey("node=$nodeId|size=${sizeBucket(inputImageSize)}")
                }
            }

            fun tail(
                resultImageSize: Size,
                resultImageFormat: Int,
            ): WorkloadKey {
                return WorkloadKey("tail|size=${sizeBucket(resultImageSize)}|format=$resultImageFormat")
            }

            fun saving(
                isPendingRequest: Boolean,
                resultImageSize: Size,
                resultImageFormat: Int,
            ): WorkloadKey {
                return WorkloadKey(
                    "saving|pending=$isPendingRequest|size=${sizeBucket(resultImageSize)}|format=$resultImageFormat",
                )
            }

            fun saving(savingExecutionMetrics: SavingExecutionMetrics): WorkloadKey {
                return saving(
                    isPendingRequest = savingExecutionMetrics.isPendingRequest,
                    resultImageSize = savingExecutionMetrics.resultImageSize,
                    resultImageFormat = savingExecutionMetrics.resultImageFormat,
                )
            }

            private fun bokehSize(nodeParams: NodeParams, inputImageSize: Size): String {
                return when (nodeParams) {
                    is NodeParams.DualBokeh -> sizeBucket(nodeParams.outputImageSize)
                    else -> sizeBucket(inputImageSize)
                }
            }

            private fun encodingFormat(nodeParams: NodeParams): Int {
                return when (nodeParams) {
                    is NodeParams.Encoding -> nodeParams.encodingFormat
                    else -> UNKNOWN_FORMAT
                }
            }
        }
    }

    private data class DecisionKey(val value: String) {
        companion object {
            fun combined(stageKey: WorkloadKey, tailKey: WorkloadKey): DecisionKey {
                return DecisionKey("${stageKey.value}|${tailKey.value}")
            }
        }
    }

    private class OnlineEwmaStats {
        var count: Int = 0
            private set
        var ewmaMs: Double = 0.0
            private set
        private var varianceEwma: Double = 0.0

        fun update(observedMs: Double, alpha: Double) {
            if (count == 0) {
                ewmaMs = observedMs
            } else {
                val error = abs(observedMs - ewmaMs)
                varianceEwma = 0.9 * varianceEwma + 0.1 * error
                val normalized = (varianceEwma / (ewmaMs + 1e-6)).coerceIn(0.0, 2.0)
                val adaptiveAlpha = (alpha * (1.0 + normalized)).coerceIn(0.05, 0.5)
                ewmaMs = adaptiveAlpha * observedMs + (1.0 - adaptiveAlpha) * ewmaMs
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

        /**
         * Conservative parametric upper bound = mean + k * sample standard deviation, for the cold
         * phase where there are too few residuals for a trustworthy empirical quantile but the mean
         * and spread are already stable (n >= 2). Used as a warmup bridge before the empirical
         * quantile takes over at minimumSamplesForCalibration. A normal tail factor (k ~ 2) is
         * deliberately conservative here: it over-covers slightly on the right-skewed residuals,
         * which is the safe direction while the window is still filling.
         */
        fun parametricUpperBound(k: Double): Double {
            if (values.size < 2) {
                return 0.0
            }
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size.toDouble()
            return mean + k * sqrt(variance)
        }

        fun percentileRank(value: Double): Double {
            if (values.isEmpty()) {
                return 0.0
            }
            val leCount = values.count { it <= value }
            return leCount.toDouble() / values.size.toDouble()
        }
    }

    /**
     * Tracks recent runtime CPU pressure from a single signal: the run-queue wait of the previous
     * shot, expressed as a percentile rank within a rolling window. nonvoluntaryCtxSwitches was
     * evaluated and dropped - it correlates r=0.82 with run-queue wait and carries nearly identical
     * information (removing it changed tail-admission UB-miss by 0.08%p), so it is redundant. This
     * is a post-hoc proxy: the value is from the previous capture, used only to nudge the quantile
     * up while the calibration window is still cold.
     */
    private class RuntimePressureTracker(windowSize: Int) {
        private val runQueueWaitMs = RollingQuantile(windowSize)
        private var lastRunQueueWaitMs: Double? = null

        fun update(postExecutionMetrics: PostExecutionMetrics) {
            val cpu = postExecutionMetrics.cpuProcessingSnapshot ?: return
            val runQueue = cpu.runqueueWaitMs.coerceAtLeast(0L).toDouble()
            lastRunQueueWaitMs = runQueue
            runQueueWaitMs.add(runQueue)
        }

        fun currentLevel(): PressureLevel {
            val runQueue = lastRunQueueWaitMs ?: return PressureLevel.NORMAL
            val runQueueRank = runQueueWaitMs.percentileRank(runQueue)
            return when {
                runQueueRank >= CRITICAL_PERCENTILE -> PressureLevel.CRITICAL
                runQueueRank >= HIGH_PERCENTILE -> PressureLevel.HIGH
                else -> PressureLevel.NORMAL
            }
        }
    }

    private companion object {
        private const val SAVING_EXECUTION_KEY = "saving"
        private const val NODE_ID_BOKEH = "NODE_SEC_V2_DUAL_BOKEH"
        private const val NODE_ID_FILTER = "NODE_SEC_FILTER"
        private const val NODE_ID_ENCODING = "NODE_SEC_V2_IMAGE_CODEC"
        private const val UNKNOWN_FORMAT = -1
        private const val WARMUP_COUNT = 12
        private const val MIN_CONFIDENCE = 0.05f
        private const val MAX_CONFIDENCE = 0.95f
        private const val HIGH_PERCENTILE = 0.85
        private const val CRITICAL_PERCENTILE = 0.95

        private fun sizeBucket(size: Size): String {
            val pixels = size.width.toLong().coerceAtLeast(0L) * size.height.toLong().coerceAtLeast(0L)
            val megaPixels = pixels.toDouble() / 1_000_000.0
            val nearest = listOf(12, 24, 50, 200).minByOrNull { abs(megaPixels - it) } ?: 12
            return "${nearest}MP"
        }
    }
}
