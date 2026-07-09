package com.samsung.android.camera.core2.ml

import android.util.Size
import com.samsung.android.camera.watermark.Watermark.WatermarkType
import kotlin.math.abs

/**
 * Stable workload bucket shared by the workload EWMA and sequence calibrator.
 *
 * Naming: a *node* is the physical pipeline unit that executes; a *workload* is one node execution's classified
 * work, identified by a [WorkloadKey]; a *sequence* is the planned workload suffix from a decision point through
 * the mandatory tail, identified by a [WorkloadSequenceKey]; the *draft sequence* is one capture's whole
 * node-chain run.
 */
sealed interface WorkloadKey {
    val policy: WorkloadPolicy
    val sizeBucket: SizeBucket

    data class Bokeh(override val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OPTIONAL
    }

    data class DynamicFunction(override val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.REQUIRED
    }

    data class Filter(override val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OPTIONAL
    }

    data class Watermark(
        override val sizeBucket: SizeBucket,
        val watermarkType: WatermarkType,
    ) : WorkloadKey {
        /** FRAME must always stamp (correctness); OVERLAY is a degradable enhancement. */
        override val policy: WorkloadPolicy =
            if (watermarkType == WatermarkType.FRAME) WorkloadPolicy.REQUIRED else WorkloadPolicy.OPTIONAL
    }

    /** JPEG-to-YUV prerequisite for downstream YUV effects; Frame Watermark forces it to run. */
    data class Decoding(override val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OPTIONAL
    }

    /** Mandatory tail from ImageCodec entry through saved draft task completion. */
    data class Encoding(
        override val sizeBucket: SizeBucket,
        val imageFormat: Int,
        val isPendingRequest: Boolean,
    ) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.RESERVED
    }
}

fun WorkloadKey.toReplayString(): String = when (this) {
    is WorkloadKey.Bokeh -> "BOKEH(sizeBucket=$sizeBucket)"
    is WorkloadKey.DynamicFunction -> "DYNAMIC_FUNCTION(sizeBucket=$sizeBucket)"
    is WorkloadKey.Filter -> "FILTER(sizeBucket=$sizeBucket)"
    is WorkloadKey.Watermark -> "WATERMARK(sizeBucket=$sizeBucket,watermarkType=$watermarkType)"
    is WorkloadKey.Decoding -> "DECODING(sizeBucket=$sizeBucket)"
    is WorkloadKey.Encoding -> "ENCODING(sizeBucket=$sizeBucket,imageFormat=$imageFormat,isPendingRequest=$isPendingRequest)"
}

enum class WorkloadPolicy {
    /** Can be skipped by admission control when budget is tight. */
    OPTIONAL,

    /** Always runs, but does not receive protected reserve budget. */
    REQUIRED,

    /** Always runs and is protected by mandatory reserve budget. */
    RESERVED,
}

/** Stable megapixel tiers a frame snaps to - the size axis of the workload taxonomy. */
enum class SizeBucket(val megaPixels: Int) {
    MP12(12),
    MP24(24),
    MP50(50),
    MP108(108),
    MP200(200);

    fun sizeRatio(to: SizeBucket): Double =
        to.megaPixels.toDouble() / megaPixels.toDouble()

    companion object {
        fun of(size: Size): SizeBucket {
            val pixels = size.width.toLong().coerceAtLeast(0L) *
                    size.height.toLong().coerceAtLeast(0L)
            val megaPixels = pixels.toDouble() / 1_000_000.0
            return entries.minByOrNull { abs(megaPixels - it.megaPixels) } ?: MP12
        }
    }
}
