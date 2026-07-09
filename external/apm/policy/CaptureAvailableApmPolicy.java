/*
 * Copyright (C) 2026 Samsung Electronics Co., Ltd. All rights reserved.
 *
 * Mobile eXperience Business,
 * Device eXperience, Samsung Electronics Co., Ltd.
 *
 * This software and its documentation are confidential and proprietary
 * information of Samsung Electronics Co., Ltd.
 * No part of the software and documents may be copied, reproduced, transmitted,
 * translated, or reduced to any electronic medium or machine-readable form
 * without the prior written consent of Samsung Electronics.
 *
 * Samsung Electronics makes no representations with respect to the contents,
 * and assumes no responsibility for any errors that might appear in the software and
 * documents. This publication and the contents hereof are subject
 * to change without notice.
 */

package com.samsung.android.camera.core2.apm.policy;

import androidx.annotation.NonNull;

import com.samsung.android.camera.core2.apm.ApmDataRepositoryStore;
import com.samsung.android.camera.core2.apm.ApmPolicy;
import com.samsung.android.camera.core2.apm.util.SingleThreadDelayedScheduler;
import com.samsung.android.camera.core2.ml.CaptureAvailablePacingPrediction;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionPredictor;
import com.samsung.android.camera.core2.util.PLog;

import java.util.List;

/**
 * <div class="camera_en">
 * Policy that schedules captureAvailable callbacks from draft budget deficits: callbacks are sent immediately when
 * budget is enough, or delayed only within the mandatory reserve / preferred draft-path budget gap.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * draft budget 부족분을 기준으로 captureAvailable 콜백을 스케줄링합니다. budget이 충분하면 즉시 보내고,
 * 부족하면 mandatory reserve / preferred draft-path budget 범위 안에서만 지연합니다.
 * </div>
 */
public class CaptureAvailableApmPolicy extends ApmPolicy {
    private static final String TAG = "CaptureAvailableApmPolicy";

    private SingleThreadDelayedScheduler singleThreadDelayedScheduler;

    /**
     * <div class="camera_en">
     * Creates a new {@code CaptureAvailableApmPolicy} with the given repository store.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 주어진 저장소를 사용하여 {@code CaptureAvailableApmPolicy}를 생성합니다.
     * </div>
     *
     * @param apmDataRepositoryStore Store for accessing APM data repositories.
     */
    public CaptureAvailableApmPolicy(@NonNull ApmDataRepositoryStore apmDataRepositoryStore) {
        super(apmDataRepositoryStore, List.of());
    }

    /**
     * <div class="camera_en">
     * Initializes internal resources, specifically the delayed scheduler.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 내부 리소스를 초기화합니다. 여기서는 지연 스케줄러를 생성합니다.
     * </div>
     */
    @Override
    protected void initializeInternalLocked() {
        singleThreadDelayedScheduler = new SingleThreadDelayedScheduler("CaptureAvailableApmPolicy");
    }

    /**
     * <div class="camera_en">
     * Calculates a budget-deficit captureAvailable delay, then schedules the provided {@code runnable}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * draft budget 부족분을 기준으로 지연을 구하고, 제공된 {@code runnable}을 스케줄링합니다.
     * </div>
     *
     * @param sequenceId sequenceId
     * @param runnable The task to be executed after the calculated delay.
     * @return {@code false} indicating the policy does not repeat automatically.
     */
    @Override
    protected boolean executeInternal(int sequenceId, @NonNull Runnable runnable) {
        final CaptureAvailablePacingPrediction pacingPrediction = DraftSequenceExecutionPredictor.getInstance().captureAvailablePacingPrediction();

        long appliedDelayMs = 0L;
        boolean warning = false;
        String reason = "captureAvailable pacing waits for the first draft prediction";
        String pacingDetails = "";

        if (pacingPrediction != null) {
            final long firstLeadingBudgetMs = pacingPrediction.getFirstLeadingBudgetMs();
            final double mandatoryReserveUpperBoundMs = pacingPrediction.getMandatoryReserveUpperBoundMs();
            final double preferredDraftPathUpperBoundMs = pacingPrediction.getPreferredDraftPathUpperBoundMs();

            final long mandatoryReserveShortageMs = Math.max(0L, (long) Math.ceil(mandatoryReserveUpperBoundMs - firstLeadingBudgetMs));
            final long preferredBudgetShortageMs = Math.max(0L, (long) Math.ceil(preferredDraftPathUpperBoundMs - firstLeadingBudgetMs));
            final long optionalBudgetHeadroomMs = Math.max(0L, (long) Math.floor(firstLeadingBudgetMs - mandatoryReserveUpperBoundMs));

            if (mandatoryReserveShortageMs > 0L) {
                appliedDelayMs = mandatoryReserveShortageMs;
                warning = true;
                reason = "protect mandatory reserve by " + mandatoryReserveShortageMs + "ms";
            } else if (preferredBudgetShortageMs > 0L) {
                appliedDelayMs = Math.min(preferredBudgetShortageMs, optionalBudgetHeadroomMs);
                reason = appliedDelayMs > 0L
                        ? "pace optional draft path by " + appliedDelayMs + "ms"
                        : "optional draft path lacks budget but mandatory reserve has no headroom";
            } else {
                reason = "budget is enough";
            }

            pacingDetails = ", firstLeadingBudget=" + firstLeadingBudgetMs + "ms"
                    + ", mandatoryReserveUpperBound=" + mandatoryReserveUpperBoundMs + "ms"
                    + ", preferredDraftPathUpperBound=" + preferredDraftPathUpperBoundMs + "ms"
                    + ", mandatoryReserveShortage=" + mandatoryReserveShortageMs + "ms"
                    + ", preferredBudgetShortage=" + preferredBudgetShortageMs + "ms"
                    + ", optionalBudgetHeadroom=" + optionalBudgetHeadroomMs + "ms"
                    + ", workloadSequenceKey=" + pacingPrediction.getWorkloadSequenceKey();
        }

        final boolean scheduled = singleThreadDelayedScheduler.schedule(runnable, appliedDelayMs);
        final String message = "[mhyun2.park] executeInternal(id:" + sequenceId + ") - " + reason
                + ", pacingPredictionAvailable=" + (pacingPrediction != null)
                + ", appliedDelay=" + appliedDelayMs + "ms"
                + pacingDetails
                + ", scheduled=" + scheduled;

        if (warning) {
            PLog.e(TAG, message);
        } else {
            PLog.d(TAG, message);
        }
        return scheduled;
    }

    /**
     * <div class="camera_en">
     * Releases internal resources, shutting down the scheduler.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 내부 리소스를 해제하고 스케줄러를 종료합니다.
     * </div>
     */
    @Override
    protected void deinitializeInternalLocked() {
        singleThreadDelayedScheduler.shutdown();
        singleThreadDelayedScheduler = null;
    }

    /**
     * <div class="camera_en">
     * Returns the log tag for this policy.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 이 정책의 로그 태그를 반환합니다.
     * </div>
     *
     * @return The tag string used for logging.
     */
    @Override
    protected String getTag() {
        return TAG;
    }

}
