/*
 * Copyright (C) 2017 Samsung Electronics Co., Ltd. All rights reserved.
 *
 * IT & Mobile Communications,
 * Mobile Communications Business, Samsung Electronics Co., Ltd.
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

package com.samsung.android.camera.core2.node.dualBokeh;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.CamCapability;
import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.local.vendorkey.CaptureMetadata;
import com.samsung.android.camera.core2.node.MultiFrameNodeBase;
import com.samsung.android.camera.core2.node.NodeId;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo.CameraUsage;

/**
 * <div class="camera_en">
 * DualBokehNodeBase Class.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * DualBokehNodeBase Class.
 * </div>
 */
public abstract class DualBokehNodeBase extends MultiFrameNodeBase {

    /**
     * <div class="camera_en">
     * DualBokehNodeBase class's constructor.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * DualBokehNodeBase class 의 생성자.
     * </div>
     *
     * @param nodeId        Node's Identifier.
     * @param nodeTag       Node's Tag
     * @param hasNativeNode if Node has NativeNode, set true.
     * @param callback      MultiFrameNodeCallback
     */
    protected DualBokehNodeBase(@NonNull NodeId nodeId, @NonNull String nodeTag, boolean hasNativeNode, @Nullable MultiFrameNodeCallback callback) {
        super(nodeId, nodeTag, hasNativeNode, callback);
    }

    /**
     * <div class="camera_en">
     * Set Device Orientation.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Device Orientation 을 설정한다.
     * </div>
     *
     * @param orientation orientation on device.
     */
    public abstract void setDeviceOrientation(int orientation);

    /**
     * <div class="camera_en">
     * Picture Set SkinSoftenLevel.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Picture SkinSoftenLevel 을 설정한다.
     * </div>
     *
     * @param level Picture SkinSoftenLevel.
     */
    public abstract void setSkinSoftenLevel(int level);

    /**
     * <div class="camera_en">
     * Set FaceColorLevel.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * FaceColorLevel 을 설정한다.
     * </div>
     *
     * @param level FaceColorLevel.
     */
    public abstract void setFaceColorLevel(int level);

    /**
     * <div class="camera_en">
     * Set BokehBlurLevel.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * BokehBlurLevel 을 설정한다.
     * </div>
     *
     * @param level BokehBlurLevel.
     */
    public abstract void setBokehBlurLevel(int level);

    /**
     * <div class="camera_en">
     * Set BokehRelightLevel.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * BokehRelightLevel 을 설정한다.
     * </div>
     *
     * @param level BokehRelightLevel.
     */
    public abstract void setBokehRelightLevel(int level);

    /**
     * <div class="camera_en">
     * Set bokeh effect type.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * bokeh effect type 를 설정한다.
     * </div>
     *
     * @param type bokeh effect type.
     */
    public abstract void setBokehEffectType(int type);

    /**
     * <div class="camera_en">
     * Set bokeh effect level.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * bokeh effect level 를 설정한다.
     * </div>
     *
     * @param level bokeh effect level.
     */
    public abstract void setBokehEffectLevel(int level);

    /**
     * <div class="camera_en">
     * Set bokeh State.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * bokeh state 를 설정한다.
     * </div>
     *
     * @param state bokeh state.
     */
    public abstract void setBokehState(int state);

    /**
     * <div class="camera_en">
     * Set flip mode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * flip mode 를 설정한다.
     * </div>
     *
     * @param flipMode flip mode.
     */
    public abstract void setFlipMode(int flipMode);

    /**
     * <div class="camera_en">
     * Set dualbokeh camera id.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * dualbokeh camera id 을 설정한다.
     * </div>
     *
     * @param cameraId dualbokeh camera id.
     */
    public abstract void setCameraId(int cameraId);

    /**
     * <div class="camera_en">
     * Set dual Calibration.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * dual Calibration을 설정한다.
     * </div>
     *
     */
    public abstract void setDualCalibration();

    /**
     * <div class="camera_en">
     * Set shot mode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * shot mode를 설정한다.
     * </div>
     *
     * @param captureMetadata captureMetadata.
     */
    public abstract void setShotMode(CaptureMetadata captureMetadata);

    /**
     * <div class="camera_en">
     * Set meta information to main input picture
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * main 입력 이미지에 필요한 meta 정보를 설정한다.
     * </div>
     *
     * @param picture picture.
     * @param bundle bundle.
     * @param streamType streamType.
     * @param captureMetadata captureMetadata.
     */
    protected abstract void setMainPictureYuv(@NonNull ImageBuffer picture, @NonNull ExtraBundle bundle, @Nullable Integer streamType, @NonNull CaptureMetadata captureMetadata);

    /**
     * <div class="camera_en">
     * Set meta information to sub input picture
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * sub 입력 이미지에 필요한 meta 정보를 설정한다.
     * </div>
     *
     * @param picture picture.
     * @param captureMetadata captureMetadata.
     */
    protected abstract void setSubPictureYuv(@NonNull ImageBuffer picture, @NonNull CaptureMetadata captureMetadata);

    @Override
    protected void setSupportedCamType(int dsMode) {
        mSupportedCamType.clear();
        mSupportedCamType.add(CameraUsage.MAIN_CAM);
        mSupportedCamType.add(CameraUsage.SUB_CAM);
    }

    public record DualBokehInitParam(@NonNull CamCapability camCapability, @NonNull Context context, int solutionType) {
        public static final int SOLUTION_TYPE_NORMAL = 0;
        public static final int SOLUTION_TYPE_LITE = 1;

        public DualBokehInitParam(@NonNull CamCapability camCapability, @NonNull Context context) {
            this(camCapability, context, SOLUTION_TYPE_NORMAL);
        }

        @NonNull
        @Override
        public String toString() {
            return "DualBokehInitParam{" +
                    "cameraId=" + camCapability.getCameraId() +
                    ", logicalMultiCameraMainPhysicalId=" + camCapability.getSamsungLogicalMultiCameraMainPhysicalId() +
                    ", solutionType=" + ((solutionType == SOLUTION_TYPE_LITE) ? "lite" : "normal") +
                    '}';
        }
    }
}
