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

package com.samsung.android.camera.core2.apm;

import androidx.annotation.NonNull;

import com.samsung.android.camera.core2.apm.data.ApmData;
import com.samsung.android.camera.core2.apm.util.ConsumerQueue;
import com.samsung.android.camera.core2.util.PLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <div class="camera_en">
 * Updates ApmData and delivers it to registered listeners.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * ApmData 를 update 받아 등록된 리스너에게 전달합니다.
 * </div>
 */
public class ApmDataDispatcher {
    private static final String TAG = "ApmDataDispatcher";

    private final Map<Class<? extends ApmData>, List<ApmDataListener>> apmDataListenersMap = new ConcurrentHashMap<>();
    private volatile ConsumerQueue<ApmData> apmDataQueue;

    /**
     * <div class="camera_en">
     * Constructs a ApmDataDispatcher with the given APM data registers.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 주어진 APM 데이터 레지스터를 사용하여 ApmDataDispatcher 생성합니다.
     * </div>
     *
     * @param apmDataRegisters List of {@link ApmDataRegister} to register listeners.
     */
    public ApmDataDispatcher(@NonNull List<ApmDataRegister> apmDataRegisters) {
        for (ApmDataRegister apmDataRegister : apmDataRegisters) {
            apmDataRegister.registerApmDataListener(this::addListener);
        }
    }

    /**
     * <div class="camera_en">
     * Starts the internal ConsumerQueue for processing APM data.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * APM 데이터를 처리하기 위한 내부 ConsumerQueue를 시작합니다.
     * </div>
     */
    public synchronized void initialize() {
        if (apmDataQueue != null) {
            return;
        }
        apmDataQueue = new ConsumerQueue<>("ApmData", this::notifyApmData);
    }

    /**
     * <div class="camera_en">
     * Updates the given APM data by enqueuing it for processing.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 주어진 APM 데이터를 처리 큐에 넣어 업데이트합니다.
     * </div>
     *
     * @param data The APM data to update.
     */
    public <T extends ApmData> void updateData(@NonNull T data) {
        ConsumerQueue<ApmData> queue = apmDataQueue;
        if (queue == null || !queue.isRunning()) {
            return;
        }
        queue.update(data);
    }

    void notifyApmData(@NonNull ApmData apmData) {
        Class<?> eventClass = apmData.getClass();
        List<ApmDataListener> listeners = apmDataListenersMap.get(eventClass);

        if (listeners != null) {
            for (ApmDataListener listener : listeners) {
                try {
                    listener.onDataReceived(apmData);
                } catch (Exception e) {
                    PLog.e(TAG, "notifyApmData is failed - task rejected for " + e);
                }
            }
        }
    }

    private <T extends ApmData> void addListener(@NonNull Class<T> apmData, ApmDataListener listener) {
        apmDataListenersMap.computeIfAbsent(apmData, k -> new ArrayList<>()).add(listener);
    }

    /**
     * <div class="camera_en">
     * Stops the ConsumerQueue and releases associated resources.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ConsumerQueue를 중지하고 관련 리소스를 해제합니다.
     * </div>
     */
    public synchronized void deinitialize() {
        if (apmDataQueue == null) {
            return;
        }

        apmDataQueue.shutdownSafely();
        apmDataQueue = null;
    }
}
