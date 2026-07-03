package com.samsung.android.camera.core2.ml

/** Key identifying the ordered workload suffix of a decision, e.g. [Bokeh, DynamicFunction, Filter, Watermark, Encoding]. */
data class WorkloadSequenceKey(val workloadKeys: List<WorkloadKey>) {

    init {
        require(workloadKeys.isNotEmpty()) { "WorkloadSequenceKey must contain at least one workload key." }
    }

    /** Whether the deciding head workload is budget-trend gated (e.g. Bokeh). */
    val isBudgetTrendGated: Boolean
        get() = workloadKeys.first().isBudgetTrendGated
}

/** Decision-time sequence in replay format, e.g. "BOKEH(...)>FILTER(...)>ENCODING(...)". */
fun WorkloadSequenceKey.toReplayString(): String = workloadKeys.joinToString(">") { it.toReplayString() }
