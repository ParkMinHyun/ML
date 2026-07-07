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

/**
 * <div class="camera_en">
 * Data class representing the processing time of a draft task.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * Draft 작업의 처리 시간을 나타내는 데이터 클래스입니다.
 * </div>
 */
public class DraftTimeApmData implements ApmData {
    private final long startTime;
    private final long endTime;

    private DraftTimeApmData(DraftTimeApmDataBuilder builder) {
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
    }

    @Override
    public void release() {
        // No resources to release.
    }

    /**
     * <div class="camera_en">
     * Calculates the elapsed processing time.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 경과 처리 시간을 계산합니다.
     * </div>
     *
     * @return Elapsed time in milliseconds.
     */
    public long getElapsedTime() {
        return endTime - startTime;
    }

    @NonNull
    @Override
    public String toString() {
        return "DraftTaskProcessingTime{" +
                "getElapsedTime=" + ApmData.elapsedString(getElapsedTime()) +
                '}';
    }

    public static class DraftTimeApmDataBuilder {
        private long startTime;
        private long endTime;

        public void setStartTime() {
            startTime = System.currentTimeMillis();
        }

        public void setEndTime() {
            endTime = System.currentTimeMillis();
        }

        public DraftTimeApmData build() {
            return new DraftTimeApmData(this);
        }
    }
}