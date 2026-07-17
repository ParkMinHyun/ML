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
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.apm.ApmDataRepositoryStore;
import com.samsung.android.camera.core2.apm.ApmPolicy;
import com.samsung.android.camera.core2.apm.repository.ApmDataProvider;
import com.samsung.android.camera.core2.apm.repository.ApmResultDataSelector;
import com.samsung.android.camera.core2.apm.repository.result.ProcessingResultData;
import com.samsung.android.camera.core2.apm.util.SingleThreadDelayedScheduler;
import com.samsung.android.camera.core2.ml.CaptureAvailablePacingDecision;
import com.samsung.android.camera.core2.ml.CaptureAvailablePacingDecider;
import com.samsung.android.camera.core2.ml.CaptureAvailablePacingPrediction;
import com.samsung.android.camera.core2.util.PLog;

import java.util.List;

/**
 * <div class="camera_en">
 * Policy that paces captureAvailable callbacks by learned deficits only: the delay is the larger of the observed
 * budget deficit and the admitted-backlog deficit against the capture timeout. An empty draft pipeline is never
 * delayed, so burst responsiveness is preserved until the timeout allowance is genuinely spent; no tuned constant
 * or threshold is involved. The mandatory reserve upper bound only classifies log severity.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 학습된 부족분만으로 captureAvailable 콜백을 pacing합니다. 관측 budget 부족분과 admission backlog 부족분
 * (capture timeout 대비) 중 큰 값을 지연으로 사용합니다. draft 파이프라인이 비어 있으면 지연하지 않아
 * 연속 촬영 반응성이 유지되며, 튜닝 상수나 임계값을 쓰지 않습니다.
 * mandatory reserve 상한은 로그 심각도 분류에만 사용합니다.
 * </div>
 */
public class CaptureAvailableApmPolicy extends ApmPolicy {
    private static final String TAG = "CaptureAvailableApmPolicy";

    /**
     * Reads the whole draft-task times and capture-available latency the {@link ProcessingResultData} repository
     * collected. The max draft time (not a mean) feeds the pacer's observed ceiling; a mean would lag a rising
     * thermal trend and let the queue overrun.
     */
    private static final ApmResultDataSelector<ProcessingResultData, CaptureAvailableData> SELECTOR = resultData ->
            new CaptureAvailableData(
                    resultData.getDraftTimes(),
                    resultData.getCaptureAvailableTime(),
                    resultData.getPacingDecider()
            );

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
        super(apmDataRepositoryStore, List.of(ProcessingResultData.class));
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
     * Hands the observed timings to the published pacing decider, receives the delay decision, then schedules the
     * provided {@code runnable} after the decided delay.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 관측 타이밍을 발행된 pacing decider에 전달해 delay 결정을 받고, 결정된 지연 후 {@code runnable}을 스케줄링합니다.
     * </div>
     *
     * @param sequenceId sequenceId
     * @param runnable The task to be executed after the decided delay.
     * @return {@code false} indicating the policy does not repeat automatically.
     */
    @Override
    protected boolean executeInternal(int sequenceId, @NonNull Runnable runnable) {
        long maxDraftTimeMs = 0L;
        long captureAvailableTimeMs = 0L;
        CaptureAvailablePacingDecider pacingDecider = null;
        final ApmDataProvider<ProcessingResultData> provider = getApmDataProvider(ProcessingResultData.class);
        if (provider != null) {
            final CaptureAvailableData captureAvailableData = provider.getSelectedApmResultData(sequenceId, SELECTOR);
            maxDraftTimeMs = captureAvailableData.getMaxDraftTime();
            captureAvailableTimeMs = captureAvailableData.getCaptureAvailableTime();
            pacingDecider = captureAvailableData.getPacingDecider();
        }

        final CaptureAvailablePacingDecision pacingDecision =
                pacingDecider != null ? pacingDecider.decideDelay(maxDraftTimeMs, captureAvailableTimeMs) : null;

        long appliedDelayMs = 0L;
        boolean warning = false;
        String reason = pacingDecider != null
                ? "captureAvailable pacing waits for the first draft prediction"
                : "no pacing decider published - no pacing";
        String pacingDetails = ", maxDraftTime=" + maxDraftTimeMs + "ms, captureAvailableTime=" + captureAvailableTimeMs + "ms";

        if (pacingDecision != null) {
            final CaptureAvailablePacingPrediction pacingPrediction = pacingDecision.getPrediction();

            appliedDelayMs = pacingDecision.getDelayMs();
            warning = pacingPrediction.getDraftStartBudgetMs() < pacingPrediction.getMandatoryReserveUpperBoundMs();

            if (warning) {
                reason = "mandatory reserve at risk - pace draft sequence by " + appliedDelayMs + "ms";
            } else if (appliedDelayMs > pacingDecision.getLevelDeficitMs()) {
                reason = "pace admitted draft backlog by " + appliedDelayMs + "ms";
            } else if (appliedDelayMs > 0L) {
                reason = "pace draft sequence by " + appliedDelayMs + "ms";
            } else {
                reason = "budget is enough";
            }

            pacingDetails += ", draftStartBudget=" + pacingPrediction.getDraftStartBudgetMs() + "ms"
                    + ", mandatoryReserveUpperBound=" + pacingPrediction.getMandatoryReserveUpperBoundMs() + "ms"
                    + ", draftSequencePredicted=" + pacingPrediction.getDraftSequencePredictedMs() + "ms"
                    + ", draftSequenceCeiling=" + pacingPrediction.getDraftSequenceCeilingMs() + "ms"
                    + ", admittedBacklog=" + pacingDecision.getBacklogMs() + "ms"
                    + ", levelDeficit=" + pacingDecision.getLevelDeficitMs() + "ms"
                    + ", workloadSequenceKey=" + pacingPrediction.getWorkloadSequenceKey();
        }

        final boolean scheduled = singleThreadDelayedScheduler.schedule(runnable, appliedDelayMs);
        final String message = "[mhyun2.park] executeInternal(id:" + sequenceId + ") - " + reason
                + ", pacingPredictionAvailable=" + (pacingDecision != null)
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

    /**
     * <div class="camera_en">
     * Snapshot the pacer reads each callback: the max of the recorded whole draft-task times (measured outside the
     * draft process lock) and the capture-available latency. Max, not mean, so a rising thermal trend is not
     * averaged away.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * pacer가 콜백마다 읽는 스냅샷: 기록된 draft task 전체 시간(draft process lock 밖 측정)의 최댓값과
     * capture-available 지연. 평균이 아니라 max라 상승하는 thermal 추세가 평균으로 희석되지 않습니다.
     * </div>
     */
    private static class CaptureAvailableData {
        private final long maxDraftTime;
        private final long captureAvailableTime;
        private final CaptureAvailablePacingDecider pacingDecider;

        CaptureAvailableData(@NonNull List<Long> draftTimes, long captureAvailableTime,
                             @Nullable CaptureAvailablePacingDecider pacingDecider) {
            this.maxDraftTime = draftTimes.stream().mapToLong(Long::longValue).max().orElse(0L);
            this.captureAvailableTime = captureAvailableTime;
            this.pacingDecider = pacingDecider;
        }

        long getMaxDraftTime() {
            return maxDraftTime;
        }

        long getCaptureAvailableTime() {
            return captureAvailableTime;
        }

        @Nullable
        CaptureAvailablePacingDecider getPacingDecider() {
            return pacingDecider;
        }
    }

}
