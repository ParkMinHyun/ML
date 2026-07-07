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

package com.samsung.android.camera.core2.apm.repository.result;

/**
 * <div class="camera_en">
 * Base class for result objects returned by APM data repositories.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * APM 데이터 저장소가 반환하는 결과 객체의 기본 클래스입니다.
 * </div>
 */
public abstract class ApmResultData {
    /**
     * <div class="camera_en">
     * Releases any resources held by this result instance.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 이 결과 인스턴스가 보유한 리소스를 해제합니다.
     * </div>
     */
    public abstract void release();
}
