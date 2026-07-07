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
 * Data class representing the time taken for a capture to become available.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 캡처가 사용 가능해지기까지 걸린 시간을 나타내는 데이터 클래스입니다.
 * </div>
 */
public class MultiPicCaptureTimeApmData implements ApmData {
    private final int sequenceId;
    private final long takePictureTime;
    private final long startTime;
    private final long shutterTime;
    private final long captureAvailableTime;
    private final long endTime;
    private final ResultCode resultCode;

    private MultiPicCaptureTimeApmData(MultiPicCaptureTimeApmDataBuilder builder) {
        this.sequenceId = builder.sequenceId;
        this.takePictureTime = builder.takePictureTime;
        this.startTime = builder.startTime;
        this.shutterTime = builder.shutterTime;
        this.captureAvailableTime = builder.captureAvailableTime;
        this.endTime = builder.endTime;
        this.resultCode = builder.resultCode;
    }

    /**
     * <div class="camera_en">
     * Retrieves the TakePictureTime.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * TakePictureTime을 반환합니다.
     * </div>
     *
     * @return TakePictureTime.
     */
    public long getTakePictureTime() {
        return takePictureTime;
    }

    /**
     * <div class="camera_en">
     * Retrieves the StartTime.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * StartTime을 반환합니다.
     * </div>
     *
     * @return StartTime.
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * <div class="camera_en">
     * Retrieves the ShutterTime.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ShutterTime을 반환합니다.
     * </div>
     *
     * @return ShutterTime
     */
    public long getShutterTime() {
        return shutterTime;
    }

    /**
     * <div class="camera_en">
     * Retrieves CaptureAvailableTime
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * CaptureAvailableTime을 반환합니다.
     * </div>
     *
     * @return CaptureAvailableTime.
     */
    public long getCaptureAvailableTime() {
        return captureAvailableTime;
    }

    /**
     * <div class="camera_en">
     * Retrieves EndTime
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * EndTime을 반환합니다.
     * </div>
     *
     * @return TEndTime.
     */
    public long getEndTime() {
        return endTime;
    }

    /**
     * <div class="camera_en">
     * Retrieves ResultCode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ResultCode을 반환합니다.
     * </div>
     *
     * @return ResultCode.
     */
    public ResultCode getResultCode() {
        return resultCode;
    }

    /**
     * <div class="camera_en">
     * Retrieves the sequence identifier.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 시퀀스 ID를 반환합니다.
     * </div>
     *
     * @return The sequence identifier.
     */
    public int getSequenceId() {
        return sequenceId;
    }

    @Override
    public void release() {
        // No resources to release.
    }

    @NonNull
    @Override
    public String toString() {
        return "MultiPicCaptureTimeApmData{" +
                "sequenceId=" + sequenceId +
                ", ElapsedTime(start-end)=" + ApmData.elapsedString(startTime, endTime) +
                ", ElapsedTime(take-shutter)=" + ApmData.elapsedString(takePictureTime, shutterTime) +
                ", ElapsedTime(take-CaptureAvailable)=" + ApmData.elapsedString(takePictureTime, captureAvailableTime) +
                '}';
    }

    public static class MultiPicCaptureTimeApmDataBuilder {
        private int sequenceId;
        private final long takePictureTime;
        private long startTime;
        private long shutterTime;
        private long captureAvailableTime;
        private long endTime;
        private ResultCode resultCode;

        public MultiPicCaptureTimeApmDataBuilder() {
            takePictureTime = System.currentTimeMillis();
        }


        public synchronized void setStartTime() {
            startTime = System.currentTimeMillis();
        }

        public synchronized void setShutterTime() {
            shutterTime = System.currentTimeMillis();
        }

        public synchronized void setCaptureAvailableTime() {
            captureAvailableTime = System.currentTimeMillis();
        }

        public synchronized void setEndTime(ResultCode resultCode) {
            this.resultCode = resultCode;
            endTime = System.currentTimeMillis();
        }

        public synchronized void setSequenceId(int sequenceId) {
            this.sequenceId = sequenceId;
        }

        public synchronized MultiPicCaptureTimeApmData build() {
            return new MultiPicCaptureTimeApmData(this);
        }
    }

    public enum ResultCode {
        COMPLETED,
        ABORTED
    }

}