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
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.apm.data.ApmData;
import com.samsung.android.camera.core2.util.CLog;

/**
 * <div class="camera_en">
 * Manages adaptive performance components, coordinating monitoring and policy enforcement.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * Adaptive Performance 구성 요소를 관리하고, 모니터링 및 정책 적용을 조정합니다.
 * </div>
 */
public class AdaptivePerformanceManager {
    private static final String TAG = "AdaptivePerformanceManager";

    private final ApmDataDispatcher apmDataDispatcher;
    private final ApmPolicyManager apmPolicyManager;
    private final ApmDataRepositoryStore apmDataRepositoryStore;

    private AdaptivePerformanceManager() {
        apmDataRepositoryStore = new ApmDataRepositoryStore();
        apmPolicyManager = new ApmPolicyManager(apmDataRepositoryStore);
        apmDataDispatcher = new ApmDataDispatcher(apmDataRepositoryStore.getAllRepositoryRegisters());
    }

    /**
     * <div class="camera_en">
     * Starts the AdaptivePerformanceManager, initializing policies and monitoring.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * AdaptivePerformanceManager를 시작하고 정책과 모니터링을 초기화합니다.
     * </div>
     */
    public void start() {
        CLog.i(TAG, "start : E");
        apmPolicyManager.initialize();
        apmDataDispatcher.initialize();
        CLog.i(TAG, "start : X");
    }

    /**
     * <div class="camera_en">
     * Updates the APM data by forwarding it to the ApmDataDispatcher.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ApmDataDispatcher 데이터를 전달하여 APM 데이터를 업데이트합니다.
     * </div>
     *
     * @param data The APM data to be updated.
     */
    public <T extends ApmData> void updateData(@NonNull T data) {
        apmDataDispatcher.updateData(data);
    }

    /**
     * <div class="camera_en">
     * Retrieves a specific APM policy instance.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 특정 APM 정책 인스턴스를 반환합니다.
     * </div>
     *
     * @param clazz The class type of the desired policy.
     * @return The policy instance if available, otherwise {@code null}.
     */
    @Nullable
    public <T extends ApmPolicy> T getPolicy(Class<T> clazz) {
        return apmPolicyManager.getPolicy(clazz);
    }

    /**
     * <div class="camera_en">
     * Stops the AdaptivePerformanceManager, releasing resources.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * AdaptivePerformanceManager를 중지하고 리소스를 해제합니다.
     * </div>
     */
    public void stop() {
        CLog.i(TAG, "stop : E");
        apmDataDispatcher.deinitialize();
        apmPolicyManager.deinitialize();
        CLog.i(TAG, "stop : X");
    }

    /**
     * <div class="camera_en">
     * reset apmDataRepositoryStore of the AdaptivePerformanceManager
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * AdaptivePerformanceManager의 apmDataRepositoryStore를 초기화 한다.
     * </div>
     */
    public void resetRepositoryStore() {
        CLog.i(TAG, "resetRepositoryStore : E");
        apmDataRepositoryStore.reset();
        CLog.i(TAG, "resetRepositoryStore : X");
    }


    /**
     * <div class="camera_en">
     * Returns the singleton instance of {@link AdaptivePerformanceManager}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@link AdaptivePerformanceManager} 싱글톤 인스턴스를 반환합니다.
     * </div>
     *
     * @return The singleton {@link AdaptivePerformanceManager} instance.
     */
    public static AdaptivePerformanceManager getInstance() {
        return LazyHolder.INSTANCE;
    }

    /**
     * <div class="camera_en">
     * singleton lazy holder
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * singleton lazy holder
     * </div>
     */
    private static class LazyHolder {
        private static final AdaptivePerformanceManager INSTANCE = new AdaptivePerformanceManager();
    }
}
