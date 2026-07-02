package com.samsung.android.camera.core2.ml

import android.util.Size
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
        override val policy: WorkloadPolicy = WorkloadPolicy.ADMIT
    }

    data class DynamicFunction(override val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OBSERVE
    }

    data class Filter(override val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.ADMIT
    }

    data class Watermark(override val sizeBucket: SizeBucket) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.OBSERVE
    }

    /** Mandatory tail from ImageCodec entry through saved draft task completion. */
    data class Encoding(
        override val sizeBucket: SizeBucket,
        val imageFormat: Int,
        val isPendingRequest: Boolean,
    ) : WorkloadKey {
        override val policy: WorkloadPolicy = WorkloadPolicy.COMPLETE
    }
}

enum class WorkloadPolicy {
    ADMIT,
    OBSERVE,
    COMPLETE,
}

/** Key identifying the ordered workload suffix of a decision, e.g. [Bokeh, DynamicFunction, Filter, Watermark, Encoding]. */
data class WorkloadSequenceKey(val workloadKeys: List<WorkloadKey>) {
    val shape: WorkloadSequenceShape = WorkloadSequenceShape(workloadKeys.map { it.javaClass.simpleName })

    init {
        require(workloadKeys.isNotEmpty()) { "WorkloadSequenceKey must contain at least one workload key." }
    }
}

/** Sequence of workload type names only (size and queue axes erased) - the calibration fallback key. */
data class WorkloadSequenceShape(val workloadTypeNames: List<String>)

fun WorkloadKey.toReplayString(): String = when (this) {
    is WorkloadKey.Bokeh -> "BOKEH(sizeBucket=$sizeBucket)"
    is WorkloadKey.DynamicFunction -> "DYNAMIC_FUNCTION(sizeBucket=$sizeBucket)"
    is WorkloadKey.Filter -> "FILTER(sizeBucket=$sizeBucket)"
    is WorkloadKey.Watermark -> "WATERMARK(sizeBucket=$sizeBucket)"
    is WorkloadKey.Encoding -> "ENCODING(sizeBucket=$sizeBucket,imageFormat=$imageFormat,isPendingRequest=$isPendingRequest)"
}

/** Decision-time sequence in replay format, e.g. "BOKEH(...)>FILTER(...)>ENCODING(...)". */
fun WorkloadSequenceKey.toReplayString(): String = workloadKeys.joinToString(">") { it.toReplayString() }

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
