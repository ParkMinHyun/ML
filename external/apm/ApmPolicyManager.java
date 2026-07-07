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

import com.samsung.android.camera.core2.apm.policy.CaptureAvailableApmPolicy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <div class="camera_en">
 * Manages and provides APM policies.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * APM 정책을 관리하고 제공합니다.
 * </div>
 */
public class ApmPolicyManager {
    private static final String TAG = "ApmPolicyManager";
    private final Map<Class<? extends ApmPolicy>, ApmPolicy> policies = new ConcurrentHashMap<>();

    /**
     * <div class="camera_en">
     * Constructs a ApmPolicyManager with the provided data repository store.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 제공된 데이터 저장소를 사용하여 ApmPolicyManager 생성합니다.
     * </div>
     *
     * @param apmDataRepositoryStore Store for APM data repositories.
     */
    public ApmPolicyManager(@NonNull ApmDataRepositoryStore apmDataRepositoryStore) {
        policies.put(CaptureAvailableApmPolicy.class, new CaptureAvailableApmPolicy(apmDataRepositoryStore));
    }

    /**
     * <div class="camera_en">
     * Initializes all registered APM policies.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 등록된 모든 APM 정책을 초기화합니다.
     * </div>
     */
    public void initialize() {
        policies.values().forEach(ApmPolicy::initialize);
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
     * @return The policy instance if present, otherwise {@code null}.
     */
    @Nullable
    public <T extends ApmPolicy> T getPolicy(@NonNull Class<T> clazz) {
        return (T) policies.get(clazz);
    }

    /**
     * <div class="camera_en">
     * Releases all APM policies, performing cleanup.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 모든 APM 정책을 해제하고 정리합니다.
     * </div>
     */
    public void deinitialize() {
        policies.values().forEach(ApmPolicy::deinitialize);
    }
}
