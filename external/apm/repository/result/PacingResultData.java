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

import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.ml.CaptureAvailablePacingDecider;

/**
 * <div class="camera_en">
 * The draft pipeline's pacing decider, on its own result type rather than beside the processing measurements.
 * A decider is a live collaborator, not an observation: asking it for a delay records an admission and advances the
 * pipeline's backlog clock. Only a policy that declares this result type can reach it, so a policy reading processing
 * measurements cannot call it by accident and admit the same draft twice.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * draft 파이프라인의 pacing decider를 처리 측정값과 분리한 결과 타입입니다.
 * decider는 관측값이 아니라 살아 있는 협력자입니다. 지연을 물으면 admission이 기록되고 파이프라인의 backlog
 * 시계가 전진합니다. 이 결과 타입을 선언한 정책만 접근할 수 있으므로, 처리 측정값을 읽는 정책이 실수로 호출해
 * 같은 draft를 두 번 admit하는 일이 없습니다.
 * </div>
 */
public abstract class PacingResultData extends ApmResultData {
    /**
     * <div class="camera_en">
     * Returns the pacing decider of the draft pipeline currently producing data.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 현재 데이터를 생산 중인 draft 파이프라인의 pacing decider를 반환합니다.
     * </div>
     *
     * @return The published pacing decider, or null until the pipeline's first draft start publishes one.
     */
    @Nullable
    public abstract CaptureAvailablePacingDecider getPacingDecider();
}
