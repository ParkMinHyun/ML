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

import java.util.function.BiConsumer;

/**
 * <div class="camera_en">
 * Interface for registering APM data listeners.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * APM 데이터 리스너를 등록하기 위한 인터페이스입니다.
 * </div>
 */
public interface ApmDataRegister {

    /**
     * <div class="camera_en">
     * Registers a listener for a specific type of {@link ApmData}.
     * The provided {@code addListenerFunc} receives a class of {@link ApmData}
     * and a corresponding {@link ApmDataListener} to be stored by the implementer.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 특정 {@link ApmData} 타입에 대한 리스너를 등록합니다.
     * 전달된 {@code addListenerFunc} 은 {@link ApmData} 클래스와 해당 {@link ApmDataListener}
     * 를 받아 구현체가 보관하도록 합니다.
     * </div>
     *
     * @param addListenerFunc Function that accepts a {@link Class} of {@link ApmData}
     *                        and an {@link ApmDataListener} to be registered.
     */
    void registerApmDataListener(@NonNull BiConsumer<Class<? extends ApmData>, ApmDataListener> addListenerFunc);
}
