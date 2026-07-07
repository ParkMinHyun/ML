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

import java.util.List;

/**
 * <div class="camera_en">
 * Abstract class for processing-related APM information.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 처리 관련 APM 정보를 나타내는 추상 클래스입니다.
 * </div>
 */
public abstract class ProcessingResultData extends ApmResultData {
    /**
     * <div class="camera_en">
     * Returns the most recent draft processing times (up to 3).
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 최근 draft 처리 시간들을 반환합니다 (최대 3개).
     * </div>
     *
     * @return List of draft times in milliseconds.
     */
    public abstract List<Long> getDraftTimes();

    /**
     * <div class="camera_en">
     * Returns the capture available time for the sequence.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 시퀀스에 대한 capture available 시간을 반환합니다.
     * </div>
     *
     * @return Capture available time in milliseconds.
     */
    public abstract long getCaptureAvailableTime();
}
