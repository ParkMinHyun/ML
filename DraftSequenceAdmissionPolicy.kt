package com.samsung.android.camera.core2.ml

import com.samsung.android.camera.watermark.Watermark.WatermarkType

/**
 * Session-sticky admission rules for draft workloads: the product-rule layer between the Predictor's model admits
 * and what actually runs. The first in-session rejection of [AdmissionGroup.PORTRAIT] (Bokeh), or of the
 * [AdmissionGroup.DECORATION] chain (Decoding-Filter-Overlay Watermark), forces rejection until [reset], so one
 * burst never alternates effects between shots. JPEG-path Decoding is forced when required by Frame Watermark;
 * FRAME watermarks are never demoted.
 *
 * Shared by runtime admission (Profiler), pacing shape projection (Pacer), and session-ordered offline replay.
 * The draft task queue owns the burst-session boundary and resets this when it drains.
 */
class DraftSequenceAdmissionPolicy {

    /** Effect groups this burst has decided on, and whether a skip has since taken one out. */
    private val effectGroups = mutableMapOf<AdmissionGroup, EffectGroupState>()

    /** Atomically applies a model decision, hardening it with the sticky group-demotion state. */
    @Synchronized
    fun admit(workloadKey: WorkloadKey, hasFrameWatermark: Boolean, modelAdmit: Boolean): Boolean {
        if (workloadKey is WorkloadKey.Decoding && hasFrameWatermark) {
            return true
        }
        if (isDemotedWorkload(workloadKey, hasFrameWatermark)) {
            return false
        }

        AdmissionGroup.of(workloadKey)?.let { effectGroup ->
            if (modelAdmit) {
                // A group is entered by the first workload of it this burst decides on, so a burst without Bokeh
                // never carries PORTRAIT. Never overwrite a demotion: the REQUIRED members of a demoted group still
                // run, and they must not carry the group back in.
                effectGroups.putIfAbsent(effectGroup, EffectGroupState.RUNNING)
            } else {
                effectGroups[effectGroup] = EffectGroupState.DEMOTED
            }
        }
        return modelAdmit
    }

    /** What this burst will actually run: [workloadSequenceKey] minus the workloads whose group is demoted. */
    @Synchronized
    fun resolveDraftSequenceKey(workloadSequenceKey: WorkloadSequenceKey): WorkloadSequenceKey {
        if (!effectGroups.containsValue(EffectGroupState.DEMOTED)) {
            return workloadSequenceKey
        }

        val hasFrameWatermark = workloadSequenceKey.hasFrameWatermark()
        val draftWorkloadKeys = workloadSequenceKey.workloadKeys.filterNot { workloadKey ->
            isDemotedWorkload(workloadKey, hasFrameWatermark)
        }
        return if (draftWorkloadKeys.isEmpty()) {
            workloadSequenceKey
        } else {
            WorkloadSequenceKey(draftWorkloadKeys)
        }
    }

    /** Returns whether the group is already demoted in this burst session. */
    @Synchronized
    fun isDemoted(group: AdmissionGroup): Boolean = effectGroups[group] == EffectGroupState.DEMOTED

    /**
     * Whether the sticky demotions take this workload out of the burst. Frame Watermark and the Decoding it forces
     * are the chain's REQUIRED members and stay in regardless of their group.
     */
    private fun isDemotedWorkload(workloadKey: WorkloadKey, hasFrameWatermark: Boolean): Boolean {
        val requiredByFrameWatermark = when (workloadKey) {
            is WorkloadKey.Decoding -> hasFrameWatermark
            is WorkloadKey.Watermark -> workloadKey.watermarkType == WatermarkType.FRAME
            is WorkloadKey.Bokeh, is WorkloadKey.Filter,
            is WorkloadKey.DynamicFunction, is WorkloadKey.Encoding -> false
        }
        if (requiredByFrameWatermark) {
            return false
        }
        val group = AdmissionGroup.of(workloadKey) ?: return false
        return effectGroups[group] == EffectGroupState.DEMOTED
    }

    /** Forgets this burst's effect groups when the session ends; the next burst enters its own. */
    @Synchronized
    fun reset() {
        effectGroups.clear()
    }

    /** Whether an effect group this burst entered still runs, or a skip has taken it out until [reset]. */
    private enum class EffectGroupState { RUNNING, DEMOTED }
}

/** Workload groups admitted or demoted as one unit; a demotion sticks until [DraftSequenceAdmissionPolicy.reset]. */
enum class AdmissionGroup {
    /** Depth-driven rendering of the image itself. */
    PORTRAIT,

    /** Looks laid over the finished image: Filter and Overlay Watermark, plus the Decoding they need. */
    DECORATION;

    companion object {
        /**
         * Admission group the workload belongs to. Membership only - admission exceptions (FRAME watermark,
         * Decoding forced by Frame Watermark) are [DraftSequenceAdmissionPolicy] rules, not group boundaries.
         */
        fun of(workloadKey: WorkloadKey): AdmissionGroup? {
            return when (workloadKey) {
                is WorkloadKey.Bokeh -> PORTRAIT
                is WorkloadKey.Decoding, is WorkloadKey.Filter, is WorkloadKey.Watermark -> DECORATION
                is WorkloadKey.DynamicFunction, is WorkloadKey.Encoding -> null
            }
        }
    }
}
