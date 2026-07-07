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

/**
 * <div class="camera_en">
 * Listener for receiving APM data events.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * APM 데이터 이벤트를 수신하는 리스너.
 * </div>
 */
public interface ApmDataListener {
    /**
     * <div class="camera_en">
     * Called when APM data is received.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * APM 데이터가 수신될 때 호출됩니다.
     * </div>
     *
     * @param apmData The received APM data.
     */
    void onDataReceived(@NonNull ApmData apmData);
}
