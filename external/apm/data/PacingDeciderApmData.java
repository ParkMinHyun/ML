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

import androidx.annotation.NonNull;

import com.samsung.android.camera.core2.ml.CaptureAvailablePacingDecider;

/**
 * <div class="camera_en">
 * The pacing decider of the draft pipeline currently producing processing data, published through the same
 * updateData pipeline as the timings it paces with. The producer re-publishes it at every draft start, so a
 * publication dropped before APM start or wiped by a repository reset heals on the next shot; publications of
 * the same decider are idempotent.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 현재 처리 데이터를 생산 중인 draft 파이프라인의 pacing decider. pacing에 쓰는 타이밍 데이터와 같은 updateData
 * 파이프라인으로 발행됩니다. 생산자가 draft start마다 재발행하므로 APM start 이전에 드롭되거나 repository
 * reset으로 지워진 발행은 다음 샷에서 복구되며, 같은 decider의 재발행은 멱등입니다.
 * </div>
 */
public class PacingDeciderApmData implements ApmData {
    private final CaptureAvailablePacingDecider pacingDecider;

    public PacingDeciderApmData(@NonNull CaptureAvailablePacingDecider pacingDecider) {
        this.pacingDecider = pacingDecider;
    }

    @NonNull
    public CaptureAvailablePacingDecider getPacingDecider() {
        return pacingDecider;
    }

    @Override
    public void release() {
        // No resources to release.
    }

    @NonNull
    @Override
    public String toString() {
        return "PacingDeciderApmData{" + pacingDecider.getClass().getSimpleName() + '}';
    }
}
