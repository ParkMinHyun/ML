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
import com.samsung.android.camera.core2.apm.repository.ApmDataProvider;
import com.samsung.android.camera.core2.apm.repository.ApmResultDataSelector;
import com.samsung.android.camera.core2.apm.repository.result.PacingResultData;
import com.samsung.android.camera.core2.apm.repository.result.ProcessingResultData;
import com.samsung.android.camera.core2.apm.util.SingleThreadDelayedScheduler;
import com.samsung.android.camera.core2.ml.CaptureAvailablePacingDecision;
import com.samsung.android.camera.core2.ml.CaptureAvailablePacingDecider;
import com.samsung.android.camera.core2.ml.CaptureAvailablePacingSnapshot;
import com.samsung.android.camera.core2.util.PLog;

import java.util.List;

/**
 * <div class="camera_en">
 * Policy that paces captureAvailable callbacks with half of a two-Draft prospective backlog deficit. The two reserves
 * cover the Draft starting after the decision and the future Draft admitted by the paced callback; Admission owns the
 * remaining half so quality demotion can still absorb pressure. The current level deficit remains diagnostic.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * decision 이후 시작하는 Draft와 pacing callback이 허용할 미래 Draft의 reserve를 합산한 prospective
 * backlog deficit 중 절반만 captureAvailable 지연으로 처리합니다. 나머지 절반은 Admission이 담당하며,
 * 현재 level deficit은 진단값으로만 유지합니다.
 * </div>
 */
public class CaptureAvailableApmPolicy extends ApmPolicy {
    private static final String TAG = "CaptureAvailableApmPolicy";

    /**
     * Reads the whole draft-task times and capture-available latency the {@link ProcessingResultData} repository
     * collected. Both are logged only: the pacer prices its own maxima off the draft walls the pipeline measures,
     * which exclude the draft-task time spent outside the draft process lock that these carry.
     */
    private static final ApmResultDataSelector<ProcessingResultData, CaptureAvailableData> SELECTOR = resultData ->
            new CaptureAvailableData(
                    resultData.getDraftTimes(),
                    resultData.getCaptureAvailableTime()
            );

    /**
     * Reads the decider off its own result type. Asking it for a delay records an admission against the draft
     * pipeline's backlog, so it is deliberately not reachable from the measurement selector above - a policy that only
     * reads timings cannot call it and admit the same draft twice.
     */
    private static final ApmResultDataSelector<PacingResultData, CaptureAvailablePacingDecider> PACING_SELECTOR =
            PacingResultData::getPacingDecider;

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
        super(apmDataRepositoryStore, List.of(ProcessingResultData.class, PacingResultData.class));
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
        final ApmDataProvider<ProcessingResultData> processingProvider = getApmDataProvider(ProcessingResultData.class);
        if (processingProvider != null) {
            final CaptureAvailableData captureAvailableData =
                    processingProvider.getSelectedApmResultData(sequenceId, SELECTOR);
            maxDraftTimeMs = captureAvailableData.getMaxDraftTime();
            captureAvailableTimeMs = captureAvailableData.getCaptureAvailableTime();
        }

        CaptureAvailablePacingDecider pacingDecider = null;
        final ApmDataProvider<PacingResultData> pacingProvider = getApmDataProvider(PacingResultData.class);
        if (pacingProvider != null) {
            pacingDecider = pacingProvider.getSelectedApmResultData(sequenceId, PACING_SELECTOR);
        }

        final CaptureAvailablePacingDecision pacingDecision =
                pacingDecider != null ? pacingDecider.decideDelay() : null;

        long appliedDelayMs = 0L;
        String reason = pacingDecider != null
                ? "captureAvailable pacing waits for the first draft snapshot"
                : "no pacing decider published - no pacing";
        String pacingDetails = ", maxDraftTime=" + maxDraftTimeMs + "ms, captureAvailableTime=" + captureAvailableTimeMs + "ms";

        if (pacingDecision != null) {
            final CaptureAvailablePacingSnapshot pacingSnapshot = pacingDecision.getSnapshot();

            appliedDelayMs = pacingDecision.getDelayMs();

            if (appliedDelayMs > 0L) {
                reason = "pace shared two-draft backlog deficit by " + appliedDelayMs + "ms";
            } else {
                reason = "shared two-draft backlog deficit is empty";
            }

            pacingDetails += ", draftSequenceBudget=" + pacingSnapshot.getDraftSequenceBudgetMs() + "ms"
                    + ", workloadSequencePredictedDuration=" + pacingSnapshot.getWorkloadSequencePredictedDurationMs() + "ms"
                    + ", draftSequenceOverheadDuration=" + pacingSnapshot.getDraftSequenceOverheadDurationMs() + "ms"
                    + ", draftSequenceReservedDuration=" + pacingSnapshot.getDraftSequenceReservedDurationMs() + "ms"
                    + ", admittedBacklog=" + pacingDecision.getBacklogMs() + "ms"
                    + ", draftSequenceKey=" + pacingSnapshot.getDraftSequenceKey();
        }

        final boolean scheduled = singleThreadDelayedScheduler.schedule(runnable, appliedDelayMs);
        final String message = "[mhyun2.park] executeInternal(id:" + sequenceId + ") - " + reason
                + ", pacingSnapshotAvailable=" + (pacingDecision != null)
                + ", appliedDelay=" + appliedDelayMs + "ms"
                + pacingDetails
                + ", scheduled=" + scheduled;

        PLog.d(TAG, message);
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
     * Log snapshot taken each callback: the max of the recorded whole draft-task times (measured outside the draft
     * process lock) and the capture-available latency. Max, not mean, so a rising thermal trend is not averaged away.
     * The pacing decision itself no longer reads either - it prices by the draft walls the pipeline measures.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 콜백마다 찍는 로그 스냅샷: 기록된 draft task 전체 시간(draft process lock 밖 측정)의 최댓값과
     * capture-available 지연. 평균이 아니라 max라 상승하는 thermal 추세가 평균으로 희석되지 않습니다.
     * pacing 결정 자체는 둘 다 읽지 않고, 파이프라인이 측정한 draft wall로 가격을 매깁니다.
     * </div>
     */
    private static class CaptureAvailableData {
        private final long maxDraftTime;
        private final long captureAvailableTime;

        CaptureAvailableData(@NonNull List<Long> draftTimes, long captureAvailableTime) {
            this.maxDraftTime = draftTimes.stream().mapToLong(Long::longValue).max().orElse(0L);
            this.captureAvailableTime = captureAvailableTime;
        }

        long getMaxDraftTime() {
            return maxDraftTime;
        }

        long getCaptureAvailableTime() {
            return captureAvailableTime;
        }
    }

}
