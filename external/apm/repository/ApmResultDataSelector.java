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
 * Functional interface for selecting and transforming APM data from a repository.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 저장소에서 APM 데이터를 선택하고 변환하기 위한 함수형 인터페이스입니다.
 * </div>
 *
 * @param <R> The ApmResultData type that the view corresponds to.
 * @param <T> The type of data returned by the selector.
 */
@FunctionalInterface
public interface ApmResultDataSelector<R extends ApmResultData, T> {
    /**
     * <div class="camera_en">
     * Selects and transforms data from the provided apmResultData.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 제공된 apmResultData에서 필요한 데이터를 선택하고 변환합니다.
     * </div>
     *
     * @param apmResultData apmResultData.
     * @return The selected and transformed data of type T.
     */
    T select(@NonNull R apmResultData);
}
