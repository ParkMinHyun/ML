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

import com.samsung.android.camera.core2.apm.ApmDataRegister;
import com.samsung.android.camera.core2.apm.repository.result.ApmResultData;

/**
 * <div class="camera_en">
 * Abstract base class for APM data repositories.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * APM 데이터 저장소의 추상 기본 클래스입니다.
 * </div>
 */
public abstract class ApmDataRepository<T extends ApmResultData> implements ApmDataRegister, ApmDataProvider<T> {
    /**
     * <div class="camera_en">
     * reset all stored data in the repository.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 저장소에 있는 모든 데이터를 초기화합니다.
     * </div>
     */
    public abstract void reset();
}
