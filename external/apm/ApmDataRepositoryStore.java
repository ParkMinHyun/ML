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

import com.samsung.android.camera.core2.apm.repository.ApmDataProvider;
import com.samsung.android.camera.core2.apm.repository.ApmDataRepository;
import com.samsung.android.camera.core2.apm.repository.ProcessingDataRepository;
import com.samsung.android.camera.core2.apm.repository.result.ApmResultData;
import com.samsung.android.camera.core2.apm.repository.result.ProcessingResultData;
import com.samsung.android.camera.core2.util.PLog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <div class="camera_en">
 * Central store for all APM data repositories.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 모든 APM 데이터 저장소를 관리하는 중앙 저장소입니다.
 * </div>
 */
public class ApmDataRepositoryStore {
    private static final String TAG = "ApmDataRepositoryStore";

    private final Map<Class<? extends ApmResultData>, ApmDataRepository<?>> dataRepositories = new ConcurrentHashMap<>();

    public ApmDataRepositoryStore() {
        dataRepositories.put(ProcessingResultData.class, new ProcessingDataRepository());
    }

    /**
     * <div class="camera_en">
     * Retrieves the {@link ApmDataProvider} for the specified result type.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 지정된 결과 타입에 대한 {@link ApmDataProvider} 를 반환합니다.
     * </div>
     *
     * @param clazz Class object of the desired {@link ApmResultData} type.
     * @return Corresponding {@link ApmDataProvider}, or {@code null} if not found.
     */
    public <T extends ApmResultData> ApmDataProvider<T> getRepositoryDataProvider(@NonNull Class<T> clazz) {
        return (ApmDataProvider<T>) dataRepositories.get(clazz);
    }

    /**
     * <div class="camera_en">
     * Returns a list of all repository registers.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 모든 저장소 레지스터의 리스트를 반환합니다.
     * </div>
     *
     * @return List of {@link ApmDataRegister} instances.
     */
    public List<ApmDataRegister> getAllRepositoryRegisters() {
        return List.copyOf(dataRepositories.values());
    }

    /**
     * <div class="camera_en">
     * reset all data repositories.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 모든 데이터 저장소를 초기화합니다.
     * </div>
     */
    public void reset() {
        dataRepositories.values().forEach(repository -> {
            try {
                repository.reset();
            } catch (Exception e) {
                PLog.w(TAG, "Failed to reset repository(" + repository.getClass().getSimpleName() + "), " + e);
            }
        });
    }
}
