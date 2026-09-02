package com.samsung.android.camera.core2.ml

import com.samsung.android.camera.watermark.Watermark.WatermarkType

/**
 * Stable workload bucket shared by the workload duration trend and sequence calibrator.
 *
 * Naming: a *node* is the physical pipeline unit that executes; a *workload* is one node execution's classified
 * work, identified by a [WorkloadKey]; a *sequence* is the planned workload suffix from a decision point through
 * the mandatory tail, identified by a [WorkloadSequenceKey]; the *draft sequence* is one capture's whole
 * node-chain run.
 *
 * Deliberately resolution-independent. A size axis split every learned duration per frame size, so the heaviest
 * work the pipeline had actually measured priced nothing outside its own size: a burst at a size seen for the
 * first time started from scratch, and a lighter size kept its own cheap history while the heavy one throttled
 * the same CPU. One pooled history per workload prices every capture by everything the pipeline has measured,
 * which is the conservative direction and the only one a shared thermal condition is meaningful across.
 */
sealed interface WorkloadKey {
    val policy: WorkloadPolicy

    data object Bokeh : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OPTIONAL
    }

    data object DynamicFunction : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.REQUIRED
    }

    data object Filter : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OPTIONAL
    }

    data class Watermark(val watermarkType: WatermarkType) : WorkloadKey {
        /** FRAME must always stamp (correctness); OVERLAY is a degradable enhancement. */
        override val policy: WorkloadPolicy =
            if (watermarkType == WatermarkType.FRAME) WorkloadPolicy.REQUIRED else WorkloadPolicy.OPTIONAL
    }

    /** JPEG-to-YUV prerequisite for downstream YUV effects; Frame Watermark forces it to run. */
    data object Decoding : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OPTIONAL
    }

    /** Mandatory tail from ImageCodec entry through saved draft task completion. */
    data class Encoding(
        val imageFormat: Int,
        val isPendingRequest: Boolean,
    ) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.RESERVED
    }
}

fun WorkloadKey.toReplayString(): String = when (this) {
    is WorkloadKey.Bokeh -> "BOKEH()"
    is WorkloadKey.DynamicFunction -> "DYNAMIC_FUNCTION()"
    is WorkloadKey.Filter -> "FILTER()"
    is WorkloadKey.Watermark -> "WATERMARK(watermarkType=$watermarkType)"
    is WorkloadKey.Decoding -> "DECODING()"
    is WorkloadKey.Encoding -> "ENCODING(imageFormat=$imageFormat,isPendingRequest=$isPendingRequest)"
}

enum class WorkloadPolicy {
    /** Can be skipped by admission control when budget is tight. */
    OPTIONAL,

    /** Always runs, but does not receive protected reserve budget. */
    REQUIRED,

    /** Always runs and is protected by mandatory reserve budget. */
    RESERVED,
}
