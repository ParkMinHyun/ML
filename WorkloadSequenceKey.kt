package com.samsung.android.camera.core2.ml

internal typealias WorkloadSequenceShape = List<Class<out WorkloadKey>>

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
}

/** Workload-type sequence with the size axis erased - the calibration fallback key for cold sequences. */
internal val WorkloadSequenceKey.shape: WorkloadSequenceShape
    get() = workloadKeys.map { it.javaClass }

/** Decision-time sequence in replay format, e.g. "BOKEH(...)>FILTER(...)>ENCODING(...)". */
fun WorkloadSequenceKey.toReplayString(): String = workloadKeys.joinToString(">") { it.toReplayString() }
