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
import com.samsung.android.camera.core2.apm.repository.result.ProcessingResultData;
import com.samsung.android.camera.core2.apm.util.SingleThreadDelayedScheduler;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionPredictor;
import com.samsung.android.camera.core2.util.PLog;

import java.util.List;

/**
 * <div class="camera_en">
 * Policy that schedules captureAvailable callbacks using draft-sequence budget trend back-pressure.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * draft sequence budget trend back-pressure에 따라 captureAvailable 콜백을 스케줄링하는 정책입니다.
 * </div>
 */
public class CaptureAvailableApmPolicy extends ApmPolicy {
    private static final String TAG = "CaptureAvailableApmPolicy";
    private static final ApmResultDataSelector<ProcessingResultData, CaptureAvailableData> SELECTOR = resultData ->
            new CaptureAvailableData(
                    resultData.getDraftTimes(),
                    resultData.getCaptureAvailableTime()
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
     * Executes calculating the delay from the draft-sequence budget trend,
     * then schedules the provided {@code runnable}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * draft sequence budget trend로 지연을 구하고, 제공된 {@code runnable}을 스케줄링합니다.
     * </div>
     *
     * @param sequenceId sequenceId
     * @param runnable The task to be executed after the calculated delay.
     * @return {@code false} indicating the policy does not repeat automatically.
     */
    @Override
    protected boolean executeInternal(int sequenceId, @NonNull Runnable runnable) {
        final CaptureAvailableData data = getCaptureAvailableData(sequenceId);
        final CaptureAvailableDelay delay = createCaptureAvailableDelay(data);
        final boolean scheduled = singleThreadDelayedScheduler.schedule(runnable, delay.appliedDelayMs());

        logCaptureAvailableDelay(sequenceId, data, delay, scheduled);
        return scheduled;
    }

    private CaptureAvailableData getCaptureAvailableData(int sequenceId) {
        ApmDataProvider<ProcessingResultData> provider = getApmDataProvider(ProcessingResultData.class);
        if (provider == null) {
            PLog.w(TAG, "executeInternal(id:" + sequenceId + ") - No provider available");
            return CaptureAvailableData.EMPTY;
        }
        return provider.getSelectedApmResultData(sequenceId, SELECTOR);
    }

    private CaptureAvailableDelay createCaptureAvailableDelay(@NonNull CaptureAvailableData data) {
        final long legacyDelayMs = Math.max(0, data.getAverageDraftTime() - data.getCaptureAvailableTime());
        final DraftSequenceExecutionPredictor predictor = DraftSequenceExecutionPredictor.getInstance();
        final boolean budgetTrendObserved = predictor.hasCaptureAvailableBudgetTrend();
        final long budgetPressureDelayMs = budgetTrendObserved ? predictor.predictCaptureAvailableDelayMs() : 0L;
        final long budgetAdvanceMs = budgetTrendObserved ? predictor.predictCaptureAvailableAdvanceMs() : 0L;

        if (!budgetTrendObserved) {
            return CaptureAvailableDelay.useLegacy(
                    legacyDelayMs,
                    budgetPressureDelayMs,
                    budgetAdvanceMs,
                    budgetTrendObserved,
                    "legacy average delay is used until budget trend is observed");
        }

        if (budgetPressureDelayMs > 0L) {
            return CaptureAvailableDelay.useBudgetPressure(legacyDelayMs, budgetPressureDelayMs, budgetAdvanceMs);
        }

        return CaptureAvailableDelay.useBudgetAdvance(legacyDelayMs, budgetAdvanceMs);
    }

    private void logCaptureAvailableDelay(int sequenceId, @NonNull CaptureAvailableData data,
                                          @NonNull CaptureAvailableDelay delay, boolean scheduled) {
        final String message = delay.formatLogMessage(sequenceId, data, scheduled);
        if (delay.warning()) {
            PLog.e(TAG, message);
        } else {
            PLog.d(TAG, message);
        }
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

    private record CaptureAvailableDelay(long legacyDelayMs,
                                         long budgetPressureDelayMs,
                                         long budgetAdvanceMs,
                                         long appliedDelayMs,
                                         boolean budgetTrendObserved,
                                         boolean warning,
                                         String reason) {
            private CaptureAvailableDelay(long legacyDelayMs, long budgetPressureDelayMs, long budgetAdvanceMs,
                                          long appliedDelayMs, boolean budgetTrendObserved, boolean warning,
                                          @NonNull String reason) {
                this.legacyDelayMs = legacyDelayMs;
                this.budgetPressureDelayMs = budgetPressureDelayMs;
                this.budgetAdvanceMs = budgetAdvanceMs;
                this.appliedDelayMs = appliedDelayMs;
                this.budgetTrendObserved = budgetTrendObserved;
                this.warning = warning;
                this.reason = reason;
            }

            static CaptureAvailableDelay useLegacy(long legacyDelayMs, long budgetPressureDelayMs, long budgetAdvanceMs,
                                                   boolean budgetTrendObserved, @NonNull String reason) {
                return new CaptureAvailableDelay(legacyDelayMs, budgetPressureDelayMs, budgetAdvanceMs,
                        legacyDelayMs, budgetTrendObserved, false, reason);
            }

            static CaptureAvailableDelay useBudgetPressure(long legacyDelayMs, long budgetPressureDelayMs,
                                                           long budgetAdvanceMs) {
                final long appliedDelayMs = Math.min(budgetPressureDelayMs, legacyDelayMs);
                if (budgetPressureDelayMs > legacyDelayMs) {
                    return new CaptureAvailableDelay(legacyDelayMs, budgetPressureDelayMs, budgetAdvanceMs,
                            appliedDelayMs, true, true,
                            "legacy average delay caps budget pressure delay by " + (budgetPressureDelayMs - legacyDelayMs) + "ms");
                }
                if (appliedDelayMs < legacyDelayMs) {
                    return new CaptureAvailableDelay(legacyDelayMs, budgetPressureDelayMs, budgetAdvanceMs,
                            appliedDelayMs, true, false,
                            "budget pressure delay is faster by " + (legacyDelayMs - appliedDelayMs) + "ms");
                }
                return useLegacy(legacyDelayMs, budgetPressureDelayMs, budgetAdvanceMs,
                        true, "budget pressure delay is same as legacy average delay");
            }

            static CaptureAvailableDelay useBudgetAdvance(long legacyDelayMs, long budgetAdvanceMs) {
                final long appliedDelayMs = Math.max(0L, legacyDelayMs - budgetAdvanceMs);
                if (appliedDelayMs < legacyDelayMs) {
                    return new CaptureAvailableDelay(legacyDelayMs, 0L, budgetAdvanceMs,
                            appliedDelayMs, true, false,
                            "budget recovery advances legacy delay by " + (legacyDelayMs - appliedDelayMs) + "ms");
                }
                return useLegacy(legacyDelayMs, 0L, budgetAdvanceMs,
                        true, "legacy average delay is retained because budget trend has no usable advance");
            }

            String formatLogMessage(int sequenceId, @NonNull CaptureAvailableData data, boolean scheduled) {
                return "[mhyun2.park] executeInternal(id:" + sequenceId + ") - " + reason
                        + ", budgetTrendObserved=" + budgetTrendObserved
                        + ", budgetPressureDelay=" + budgetPressureDelayMs + "ms"
                        + ", budgetAdvance=" + budgetAdvanceMs + "ms"
                        + ", predictorDelay=" + appliedDelayMs + "ms"
                        + ", legacyDelay=" + legacyDelayMs + "ms"
                        + ", appliedDelay=" + appliedDelayMs + "ms"
                        + ", scheduled=" + scheduled
                        + ", averageDraftTime=" + data.getAverageDraftTime() + "ms"
                        + ", captureAvailableTime=" + data.getCaptureAvailableTime() + "ms";
            }
        }

    private static class CaptureAvailableData {
        private static final CaptureAvailableData EMPTY = new CaptureAvailableData(List.of(), 0L);

        private final long averageDraftTime;
        private final long captureAvailableTime;

        CaptureAvailableData(List<Long> draftTimes, long captureAvailableTime) {
            this.averageDraftTime = (long) draftTimes.stream().mapToLong(Long::longValue).average().orElse(0L);
            this.captureAvailableTime = captureAvailableTime;
        }

        long getAverageDraftTime() {
            return averageDraftTime;
        }

        long getCaptureAvailableTime() {
            return captureAvailableTime;
        }
    }
}
