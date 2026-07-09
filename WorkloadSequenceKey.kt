package com.samsung.android.camera.core2.ml

import com.samsung.android.camera.watermark.Watermark.WatermarkType

/** Key identifying the ordered workload suffix of a decision, e.g. [Bokeh, DynamicFunction, Filter, Watermark, Encoding]. */
data class WorkloadSequenceKey(val workloadKeys: List<WorkloadKey>) {

    init {
        require(workloadKeys.isNotEmpty()) { "WorkloadSequenceKey must contain at least one workload key." }
    }

    /**
     * Head of the sequence - the workload this decision is for, since a sequence is the planned suffix
     * from its decision point.
     */
    val headWorkloadKey: WorkloadKey
        get() = workloadKeys.first()

    fun hasFrameWatermark(): Boolean {
        return workloadKeys.any { workloadKey ->
            workloadKey is WorkloadKey.Watermark && workloadKey.watermarkType == WatermarkType.FRAME
        }
    }
}

/** Decision-time sequence in replay format, e.g. "BOKEH(...)>FILTER(...)>ENCODING(...)". */
fun WorkloadSequenceKey.toReplayString(): String = workloadKeys.joinToString(">") { it.toReplayString() }
