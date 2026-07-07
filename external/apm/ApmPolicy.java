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

import com.samsung.android.camera.core2.apm.repository.ApmDataProvider;
import com.samsung.android.camera.core2.apm.repository.result.ApmResultData;
import com.samsung.android.camera.core2.util.CLog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * <div class="camera_en">
 * Defines the lifecycle and execution of an APM policy.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * APM 정책의 라이프사이클 및 실행을 정의합니다.
 * </div>
 */
public abstract class ApmPolicy {
    private final Map<Class<? extends ApmResultData>, ApmDataProvider<? extends ApmResultData>> dataProviders = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock executeLock = new ReentrantReadWriteLock();
    private boolean isDeinitialized = true;

    /**
     * <div class="camera_en">
     * Constructs an APM policy with the given data repository store.
     * Register the RepositoryDataProvider that provides the necessary data for the policy.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 주어진 데이터 저장소를 사용하여 APM 정책을 생성합니다.
     * 해당 policy에서 필요한 data를 제공해주는 RepositoryDataProvider 등록한다.
     * </div>
     *
     * @param apmDataRepositoryStore Store for APM data repositories.
     * @param apmResultDataList list of ApmResultData class used in this policy.
     */
    protected ApmPolicy(@NonNull ApmDataRepositoryStore apmDataRepositoryStore,
                        @NonNull List<Class<? extends ApmResultData>> apmResultDataList) {
        for (Class<? extends ApmResultData> apmResultData : apmResultDataList) {
            dataProviders.put(apmResultData, Objects.requireNonNull(apmDataRepositoryStore.getRepositoryDataProvider(apmResultData)));
        }
    }

    /**
     * <div class="camera_en">
     * Initializes the policy, preparing it for execution.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 정책을 초기화하고 실행 준비를 합니다.
     * </div>
     */
    final void initialize() {
        executeLock.writeLock().lock();
        try {
            if (!isDeinitialized) return;

            CLog.d(getTag(), "initialize policy");
            initializeInternalLocked();
            isDeinitialized = false;
        } finally {
            executeLock.writeLock().unlock();
        }
    }

    /**
     * <div class="camera_en">
     * Executes the given runnable within the policy's execution lock.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 정책의 실행 락 내에서 주어진 Runnable을 실행합니다.
     * </div>
     *
     * @param runnable The task to execute.
     * @return {@code true} if execution succeeded, {@code false} otherwise.
     */
    public final boolean execute(int sequenceId, @NonNull Runnable runnable) {
        executeLock.readLock().lock();
        try {
            if (isDeinitialized) {
                CLog.w(getTag(), "Policy not initialized for execute");
                return false;
            }
            return executeInternal(sequenceId, runnable);
        } finally {
            executeLock.readLock().unlock();
        }
    }

    /**
     * <div class="camera_en">
     * Releases the policy, performing any necessary cleanup.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 정책을 해제하고 필요한 정리를 수행합니다.
     * </div>
     */
    final void deinitialize() {
        executeLock.writeLock().lock();
        try {
            if (isDeinitialized) return;

            CLog.d(getTag(), "deinitialize policy");
            deinitializeInternalLocked();
            isDeinitialized = true;
        } finally {
            executeLock.writeLock().unlock();
        }
    }

    @Nullable
    protected <T extends ApmResultData> ApmDataProvider<T> getApmDataProvider(Class<T> clazz) {
        return (ApmDataProvider<T>) dataProviders.get(clazz);
    }

    protected abstract void initializeInternalLocked();

    protected abstract boolean executeInternal(int sequenceId, @NonNull Runnable runnable);

    protected abstract void deinitializeInternalLocked();

    protected abstract String getTag();
}
