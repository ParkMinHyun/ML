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

import com.samsung.android.camera.core2.apm.ApmDataListener;
import com.samsung.android.camera.core2.apm.data.ApmData;
import com.samsung.android.camera.core2.apm.data.DraftTimeApmData;
import com.samsung.android.camera.core2.apm.data.MultiPicCaptureTimeApmData;
import com.samsung.android.camera.core2.apm.repository.result.ProcessingResultData;
import com.samsung.android.camera.core2.util.PLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * <div class="camera_en">
 * Repository implementation for processing-related APM data.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 처리 관련 APM 데이터를 관리하는 저장소 구현체입니다.
 * </div>
 */
public class ProcessingDataRepository extends ApmDataRepository<ProcessingResultData> {
    private static final String TAG = "ProcessingDataRepository";
    private static final int MAX_DRAFT_TIME_SIZE = 3;
    private static final int MAX_CAN_TIME_SIZE = 10;

    private final Object lock = new Object();
    private final Deque<Long> draftTimeDeque = new ArrayDeque<>(MAX_DRAFT_TIME_SIZE);
    private final LinkedHashMap</*sequenceId*/Integer, /*time*/Long> canTimeMap = new LinkedHashMap<>();

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
    public <R> R getSelectedApmResultData(int sequenceId, @NonNull ApmResultDataSelector<ProcessingResultData, R> selector) {
        synchronized (lock) {
            ProcessingResultData apmResultData = new ProcessingResultDataImpl(sequenceId);
            return selector.select(apmResultData);
        }
    }

    /**
     * <div class="camera_en">
     * Registers listeners for draft and capture available time data.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Draft 및 캡처 가능 시간 데이터에 대한 리스너를 등록합니다.
     * </div>
     *
     * @param addListenerFunc Function to add listeners for specific APM data types.
     */
    @Override
    public void registerApmDataListener(@NonNull BiConsumer<Class<? extends ApmData>, ApmDataListener> addListenerFunc) {
        addListenerFunc.accept(DraftTimeApmData.class, data -> addDraftTaskProcessingTime((DraftTimeApmData) data));
        addListenerFunc.accept(MultiPicCaptureTimeApmData.class, data -> addCaptureAvailableTime((MultiPicCaptureTimeApmData) data));
    }

    private void addDraftTaskProcessingTime(@NonNull DraftTimeApmData time) {
        synchronized (lock) {
            if (draftTimeDeque.size() == MAX_DRAFT_TIME_SIZE) {
                draftTimeDeque.removeFirst();
            }

            draftTimeDeque.addLast(time.getElapsedTime());
        }
        PLog.i(TAG, "addDraftTaskProcessingTime: " + time);
    }

    private void addCaptureAvailableTime(@NonNull MultiPicCaptureTimeApmData time) {
        final long endTime = time.getEndTime() - time.getTakePictureTime();
        final long captureAvailableTime = time.getCaptureAvailableTime() - time.getTakePictureTime();
        final long elapsedTime = Math.max(endTime, captureAvailableTime);

        if (elapsedTime <= 0) {
            return;
        }

        synchronized (lock) {
            if (canTimeMap.size() == MAX_CAN_TIME_SIZE) {
                canTimeMap.remove(canTimeMap.firstEntry().getKey());
            }
            canTimeMap.put(time.getSequenceId(), elapsedTime);
        }
        PLog.i(TAG, "addCaptureAvailableTime: " + time);
    }

    /**
     * <div class="camera_en">
     * Clears all stored processing data.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 저장된 모든 처리 데이터를 삭제합니다.
     * </div>
     */
    @Override
    public void reset() {
        synchronized (lock) {
            draftTimeDeque.clear();
            canTimeMap.clear();
        }
        PLog.i(TAG, "ProcessingDataRepository cleared");
    }

    private class ProcessingResultDataImpl extends ProcessingResultData {
        private final int sequenceId;

        ProcessingResultDataImpl(int sequenceId) {
            this.sequenceId = sequenceId;
        }

        @Override
        public List<Long> getDraftTimes() {
            return new ArrayList<>(draftTimeDeque);
        }

        @Override
        public long getCaptureAvailableTime() {
            return Optional.ofNullable(canTimeMap.get(sequenceId))
                    .orElseGet(() -> Optional.ofNullable(canTimeMap.lastEntry()).map(Map.Entry::getValue).orElse(0L));
        }

        @Override
        public void release() {
        }
    }
}
