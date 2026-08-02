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

package com.samsung.android.camera.core2.apm.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.apm.ApmDataListener;
import com.samsung.android.camera.core2.apm.data.ApmData;
import com.samsung.android.camera.core2.apm.data.PacingDeciderApmData;
import com.samsung.android.camera.core2.apm.repository.result.PacingResultData;
import com.samsung.android.camera.core2.ml.CaptureAvailablePacingDecider;
import com.samsung.android.camera.core2.util.PLog;

import java.util.function.BiConsumer;

/**
 * <div class="camera_en">
 * Holds the draft pipeline's published pacing decider, and nothing else. It is kept out of the processing
 * measurements so that reaching a live collaborator takes declaring {@link PacingResultData}, not reading timings.
 * Cleared by {@link #reset()} like every repository, which is also how a closed pipeline's decider is detached - the
 * draft pipeline re-publishes at every draft start, so the next shot heals it.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * draft 파이프라인이 발행한 pacing decider만 보관합니다. 처리 측정값과 분리해 두어, 살아 있는 협력자에 접근하려면
 * 타이밍을 읽는 것만으로는 안 되고 {@link PacingResultData} 를 선언해야 합니다.
 * 다른 저장소들처럼 {@link #reset()} 에서 비워지며, 그것이 닫힌 파이프라인의 decider를 떼어내는 방식입니다 -
 * draft 파이프라인이 draft start마다 재발행하므로 다음 촬영에서 복구됩니다.
 * </div>
 */
public class PacingDataRepository extends ApmDataRepository<PacingResultData> {
    private static final String TAG = "PacingDataRepository";

    private final Object lock = new Object();
    private CaptureAvailablePacingDecider pacingDecider;

    /**
     * <div class="camera_en">
     * Retrieves selected data using a custom selector for flexible data transformation.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 커스텀 selector를 사용하여 필요한 데이터만 선택해서 반환합니다.
     * </div>
     *
     * @param sequenceId The sequence identifier.
     * @param selector The selector to transform repository data.
     * @param <R> The type of data returned by the selector.
     * @return The selected and transformed data.
     */
    @Override
    public <R> R getSelectedApmResultData(int sequenceId, @NonNull ApmResultDataSelector<PacingResultData, R> selector) {
        synchronized (lock) {
            return selector.select(new PacingResultDataImpl());
        }
    }

    /**
     * <div class="camera_en">
     * Registers the listener for the published pacing decider.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 발행된 pacing decider에 대한 리스너를 등록합니다.
     * </div>
     *
     * @param addListenerFunc Function to add listeners for specific APM data types.
     */
    @Override
    public void registerApmDataListener(@NonNull BiConsumer<Class<? extends ApmData>, ApmDataListener> addListenerFunc) {
        addListenerFunc.accept(PacingDeciderApmData.class, data -> setPacingDecider((PacingDeciderApmData) data));
    }

    private void setPacingDecider(@NonNull PacingDeciderApmData data) {
        synchronized (lock) {
            pacingDecider = data.getPacingDecider();
        }
    }

    /**
     * <div class="camera_en">
     * Clears the stored pacing decider.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 보관 중인 pacing decider를 비웁니다.
     * </div>
     */
    @Override
    public void reset() {
        synchronized (lock) {
            pacingDecider = null;
        }
        PLog.i(TAG, "PacingDataRepository cleared");
    }

    private class PacingResultDataImpl extends PacingResultData {
        @Nullable
        @Override
        public CaptureAvailablePacingDecider getPacingDecider() {
            return pacingDecider;
        }
    }
}
