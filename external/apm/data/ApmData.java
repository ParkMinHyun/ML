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

package com.samsung.android.camera.core2.apm.data;

/**
 * <div class="camera_en">
 * Represents a unit of data used by the Adaptive Performance Management (APM) system.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * Adaptive Performance Management(APM) 시스템에서 사용하는 데이터 단위입니다.
 * </div>
 */
public interface ApmData {

    /**
     * <div class="camera_en">
     * Returns the elapsed time between two timestamps as a display string (e.g. "123ms").
     * A time field that was never set stays 0, so if either {@code from} or {@code to} is
     * not positive this returns "N/A" instead of a misleading negative value
     * (e.g. on an ABORTED capture).
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 두 타임스탬프 사이의 경과 시간을 표시용 문자열(예: "123ms")로 반환합니다.
     * 설정된 적 없는 시간 필드는 0으로 남으므로, {@code from} 또는 {@code to} 가 양수가
     * 아니면 잘못된 음수 값 대신 "N/A" 를 반환합니다(예: ABORTED 된 캡처).
     * </div>
     */
    static String elapsedString(long from, long to) {
        return (from <= 0 || to <= 0) ? "N/A" : (to - from) + "ms";
    }

    /**
     * <div class="camera_en">
     * Returns an already-measured elapsed time as a display string (e.g. "123ms").
     * A time field that was never set stays 0, so if {@code elapsedTime} is not positive
     * this returns "N/A" instead of a misleading value.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 이미 측정된 경과 시간을 표시용 문자열(예: "123ms")로 반환합니다.
     * 설정된 적 없는 시간 필드는 0으로 남으므로, {@code elapsedTime} 이 양수가 아니면
     * 잘못된 값 대신 "N/A" 를 반환합니다.
     * </div>
     */
    static String elapsedString(long elapsedTime) {
        return (elapsedTime <= 0) ? "N/A" : (elapsedTime) + "ms";
    }

    /**
     * <div class="camera_en">
     * Releases any resources held by this data instance.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 이 데이터 인스턴스가 보유한 리소스를 해제합니다.
     * </div>
     */
    void release();
}