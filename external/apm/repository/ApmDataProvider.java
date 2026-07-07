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

import androidx.annotation.NonNull;

import com.samsung.android.camera.core2.apm.repository.result.ApmResultData;

/**
 * <div class="camera_en">
 * Provider interface for retrieving APM data results.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * APM 데이터 결과를 제공하는 인터페이스입니다.
 * </div>
 */
public interface ApmDataProvider<T extends ApmResultData> {
    /**
     * <div class="camera_en">
     * Retrieves selected data using a custom selector for flexible data transformation.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 커스텀 selector를 사용하여 필요한 데이터만 선택해서 반환합니다.
     * </div>
     *
     * @param sequenceId The sequence identifier.
     * @param selector The selector to transform repository data.
     * @param <R> The result data type of this selector.
     * @return The selected and transformed data.
     */
    <R> R getSelectedApmResultData(int sequenceId, @NonNull ApmResultDataSelector<T, R> selector);
}
