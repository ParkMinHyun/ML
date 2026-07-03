/*
 * Copyright (C) 2024 Samsung Electronics Co., Ltd. All rights reserved.
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

package com.samsung.android.camera.core2.node.dualBokeh.samsung.v2;

import static com.samsung.android.camera.core2.PublicMetadata.SCALER_RAW_SENSOR_INFO_INDEX_CROP_MODE;

import android.graphics.Rect;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.MeteringRectangle;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.ExtraCaptureInfo;
import com.samsung.android.camera.core2.container.LightMapInfo;
import com.samsung.android.camera.core2.local.vendorkey.CaptureMetadata;
import com.samsung.android.camera.core2.local.vendorkey.SemCameraMetadata;
import com.samsung.android.camera.core2.local.vendorkey.SemCaptureResult;
import com.samsung.android.camera.core2.local.vendorkey.metadata.CaptureMetadataKey;
import com.samsung.android.camera.core2.local.vendorkey.metadata.RequiredCaptureMetadata;
import com.samsung.android.camera.core2.node.NativeNode;
import com.samsung.android.camera.core2.node.NodeErrors;
import com.samsung.android.camera.core2.node.NodeFeature;
import com.samsung.android.camera.core2.node.NodeFeatureUtil;
import com.samsung.android.camera.core2.node.NodeId;
import com.samsung.android.camera.core2.node.NodeTagComposer;
import com.samsung.android.camera.core2.node.dualBokeh.samsung.SecDualBokehNodeBase;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.CalculationUtils;
import com.samsung.android.camera.core2.util.DirectBuffer;
import com.samsung.android.camera.core2.util.DynamicShotUtils;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.ImageUtils;
import com.samsung.android.camera.core2.util.StrideInfo;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

@RequiredCaptureMetadata(keys = {
        CaptureMetadataKey.CONTROL_CAPTURE_SUB_PHYSICAL_ID,
        CaptureMetadataKey.SAMSUNG_SENSOR_PIXEL_MODE,
        CaptureMetadataKey.SCALER_RAW_SENSOR_INFO,
        CaptureMetadataKey.SENSOR_PIXEL_MODE
})

public class SecDualBokehNode extends SecDualBokehNodeBase {
    private static final String SEC_DUAL_BOKEH_V2_TAG = "V2/SecDualBokehNode";

    private static final int DEFAULT_MAIN_COUNT = 1;

    private static final NativeNode.Command<Void> NATIVE_COMMAND_SET_CAPTURE_PHYSICAL_PAIR = new NativeNode.Command<>(138, Integer.class, Integer.class) {};
    private static final NativeNode.Command<Void> NATIVE_COMMAND_SET_CALIBRATION_W_UW_DATA = new NativeNode.Command<>(139, DirectBuffer.class) {};
    private static final NativeNode.Command<Void> NATIVE_COMMAND_SET_CALIBRATION_T_W_DATA = new NativeNode.Command<>(140, DirectBuffer.class) {};
    private static final NativeNode.Command<Void> NATIVE_COMMAND_SET_CALIBRATION_T2_T1_DATA = new NativeNode.Command<>(141, DirectBuffer.class) {};
    private static final NativeNode.Command<Void> NATIVE_COMMAND_SET_OIS_HALL_INFO_VERSION = new NativeNode.Command<>(142, Integer.class) {};
    private static final NativeNode.Command<Void> NATIVE_COMMAND_SET_DRAFT_PREPROCESSED_DATA_PATH = new NativeNode.Command<>(143, String.class) {};
    private static final NativeNode.Command<Void> NATIVE_COMMAND_SET_LIGHT_MAP_INFO = new NativeNode.Command<>(144, DirectBuffer.class, LightMapInfo.class) {};
    private static final NativeNode.Command<Void> NATIVE_COMMAND_SET_CROP_MODE = new NativeNode.Command<>(145, Integer.class) {};

    private static final NativeNode.Command<Void> STATIC_NATIVE_COMMAND_SET_ENTRY = new NativeNode.Command<>(2000, Integer.class) {};

    private byte[] mDualCalibrationW_UW;
    private byte[] mDualCalibrationT_W;
    private byte[] mDualCalibrationT2_T1;

    public static void setEntryMode(@NonNull EntryMode entryMode) {
        final NodeFeature.NodeFeatureVersion dualBokehNodeFeatureVersion = Objects.requireNonNull(NodeFeatureUtil.getNodeFeatureVersion(SecDualBokehNode.class));
        if (NodeId.NODE_SEC_V2_DUAL_BOKEH != dualBokehNodeFeatureVersion.getTargetNodeId()) {
            return;
        }

        NativeNode.staticNativeCall(NodeId.NODE_SEC_V2_DUAL_BOKEH.getId(), STATIC_NATIVE_COMMAND_SET_ENTRY, entryMode.ordinal());
    }

    public SecDualBokehNode(@NonNull DualBokehInitParam dualBokehInitParam, @NonNull MultiFrameNodeCallback callback) {
        this(SEC_DUAL_BOKEH_V2_TAG, dualBokehInitParam, callback);
    }

    public SecDualBokehNode(@NonNull NodeTagComposer nodeTag,
                            @NonNull DualBokehInitParam dualBokehInitParam,
                            @NonNull MultiFrameNodeCallback callback) {
        this(nodeTag.compose(SEC_DUAL_BOKEH_V2_TAG).toString(), dualBokehInitParam, callback);
    }

    private SecDualBokehNode(@NonNull String nodeTag,
                             @NonNull DualBokehInitParam dualBokehInitParam,
                             @NonNull MultiFrameNodeCallback callback) {
        super(NodeId.NODE_SEC_V2_DUAL_BOKEH, nodeTag, dualBokehInitParam, callback);

        if (mCamCapability.getCapabilityLogicalMultiCamera()) {
            mDualCalibrationW_UW = mCamCapability.getSamsungLogicalMultiCameraDualCalibration_W_UW();
            mDualCalibrationT_W = mCamCapability.getSamsungLogicalMultiCameraDualCalibration_T_W();
            mDualCalibrationT2_T1 = mCamCapability.getSamsungLogicalMultiCameraDualCalibration_T2_T1();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void reconfigure(@NonNull Object initParam) {
        super.reconfigure(initParam);
        if (mCamCapability.getCapabilityLogicalMultiCamera()) {
            if (null == mDualCalibrationW_UW) {
                mDualCalibrationW_UW = mCamCapability.getSamsungLogicalMultiCameraDualCalibration_W_UW();
                setDualCalibrationNativeCall(mDualCalibrationW_UW, NATIVE_COMMAND_SET_CALIBRATION_W_UW_DATA, "DualCalibrationW_UW");
            }

            if (null == mDualCalibrationT_W) {
                mDualCalibrationT_W = mCamCapability.getSamsungLogicalMultiCameraDualCalibration_T_W();
                setDualCalibrationNativeCall(mDualCalibrationT_W, NATIVE_COMMAND_SET_CALIBRATION_T_W_DATA, "DualCalibrationT_W");
            }

            if (null == mDualCalibrationT2_T1) {
                mDualCalibrationT2_T1 = mCamCapability.getSamsungLogicalMultiCameraDualCalibration_T2_T1();
                setDualCalibrationNativeCall(mDualCalibrationT2_T1, NATIVE_COMMAND_SET_CALIBRATION_T2_T1_DATA, "DualCalibrationT2_T1");
            }
        }
    }

    private void setOisHallInfoVersion() {
        final int oisHallInfoVersion = mCamCapability.getSamsungStatisticsOisHallInfoVersion();
        nativeCall(NATIVE_COMMAND_SET_OIS_HALL_INFO_VERSION, oisHallInfoVersion);
    }

    public void setMainPictureYuv(@NonNull ImageBuffer picture, @NonNull ExtraBundle bundle, @Nullable Integer streamType, @NonNull CaptureMetadata captureMetadata) {
        mMainFrameCount++;
        if (mMainFrameCount == FIRST_CAPTURE_COUNT) {
            final ImageInfo pictureImageInfo = picture.getImageInfo();
            final Size pictureSize = Objects.requireNonNull(pictureImageInfo.getSize());

            final Rect sensorInfoActiveArraySize = Objects.requireNonNull(mCamCapability.getSensorInfoActiveArraySize(streamType));

            setOverHeatLevel(captureMetadata, bundle);
            setMemoryInfo();
            setPreviewInfo(captureMetadata);
            setAfMode(captureMetadata);
            setMainImageBufferAndSize(picture);
            setFaceInfo(captureMetadata, sensorInfoActiveArraySize, pictureSize);
            setFocusInfo(pictureSize, sensorInfoActiveArraySize, captureMetadata);
            setNightResultInfo(bundle);
            setPetDetectionInfo(captureMetadata, sensorInfoActiveArraySize, pictureSize);

            setDeviceState(captureMetadata);
            setOisHallInfoVersion();
            setOisHallInfo(captureMetadata);
            setSpecialSceneAeInfo(captureMetadata);
            setCropRegionInfo(captureMetadata);
            setHdrCropRegion(bundle);

            setRefMainYuvImage(bundle);
            setCapturePhysicalId(captureMetadata);
            setLightMapInfo(bundle);
        }
    }

    @Override
    protected void prepareFirstYuvProcessPicture(@NonNull ImageInfo imageInfo, @NonNull ExtraBundle bundle) {
        super.prepareFirstYuvProcessPicture(imageInfo, bundle);
        setDraftPreprocessedDataPath(bundle);
    }

    @Override
    public ImageBuffer processPictureYuv(@NonNull ImageBuffer picture, @NonNull ExtraBundle bundle) {
        CLog.i(getNodeTag(), "processPictureYuv E");

        final ImageInfo pictureImageInfo = picture.getImageInfo();
        final CaptureMetadata captureMetadata = Objects.requireNonNull(pictureImageInfo.getCaptureMetadata());

        final Integer streamType = getStreamType(captureMetadata);
        final boolean isMainPhysicalId = pictureImageInfo.getImageComesFrom() == ImageInfo.CameraUsage.MAIN_CAM;
        CLog.i(getNodeTag(), "processPictureYuv: [%s] Current Input Count = %d/%d",
                isMainPhysicalId ? "Main" : "Sub", getCurrentInputCount(), getMaxInputCount());

        setCropMode(captureMetadata);
        setExtraCaptureInfo(isMainPhysicalId, pictureImageInfo);

        if (isMainPhysicalId) {
            setMainPictureYuv(picture, bundle, streamType, captureMetadata);
        } else {
            setSubPictureYuv(picture, captureMetadata);
        }
        setBokehExtraInfo(picture, bundle, streamType, isMainPhysicalId, captureMetadata);

        final int res = setBufferInfo(picture, isMainPhysicalId);
        if (NodeErrors.NO_ERROR != res) {
            handleErrorCallback(res, bundle);
            return null;
        }

        ImageBuffer resultImg = null;
        if (isMaxInputCount()) {
            resultImg = makeDualBokeh(bundle);
        }

        CLog.i(getNodeTag(), "processPictureYuv X");
        return resultImg;
    }

    protected ImageBuffer makeDualBokeh(@NonNull ExtraBundle bundle) {
        // Bokeh result must be packed by bokeh solution if necessary
        final Size resultSize = Optional.ofNullable(bundle.get(ExtraBundle.INFO_RESULT_CAPTURE_SIZE))
                .orElse(mMainPictureSize);
        CLog.i(getNodeTag(), "makeDualBokeh E: resultSize" + resultSize);
        if (null == mBokehResultBuffer || mBokehResultBuffer.capacity() != ImageUtils.getNV21BufferSize(resultSize)) {
            Optional.ofNullable(mBokehResultBuffer).ifPresent(DirectBuffer::release);
            mBokehResultBuffer = DirectBuffer.allocate(ImageUtils.getNV21BufferSize(resultSize));
        }
        mBokehResultBuffer.rewind();
        mMainImageBuffer.rewind();

        if (mSubCnt > 1) {
            nativeCall(NATIVE_COMMAND_SET_SUB_IMAGE_BUFFER, mSubImageBuffer, mSubPictureSize, mSubPictureImageInfo.getStrideInfo());
        }
        final int res = nativeCall(NATIVE_COMMAND_MAKE_DUAL_BOKEH, mBokehResultBuffer, mMainImageBuffer, resultSize);
        if (NodeErrors.ABORT == res) {
            CLog.i(getNodeTag(), "makeDualBokeh X: aborted");
            mBokehDebugInfo = null;
            handleErrorCallback(NodeErrors.ABORT, bundle);
            return null;
        }

        final boolean isBokehSuccess = (NodeErrors.NO_ERROR == res);
        setProcessedOption(bundle, isBokehSuccess);
        saveSourceImageToSefParam(bundle, isBokehSuccess);

        mMainPictureImageInfo.setExtraDebugInfoApp4(mBokehDebugInfo);
        mBokehDebugInfo = null;
        mMainPictureImageInfo.setSize(resultSize);
        mMainPictureImageInfo.setStrideInfo(new StrideInfo(resultSize));
        mBokehResultBuffer.rewind();
        final ImageBuffer image = ImageBuffer.allocate(mBokehResultBuffer.capacity(), mMainPictureImageInfo);
        image.put(mBokehResultBuffer);
        image.rewind();
        mBokehResultBuffer.rewind();
        mNodeCallback.onCompleted();

        CLog.i(getNodeTag(), "makeDualBokeh X: Capture bokeh effect was %s", (isBokehSuccess) ? "applied." : "not applied.");
        return image;
    }

    @NonNull
    @Override
    protected ImageBuffer getSkippedImage(@NonNull ImageBuffer picture, @NonNull ExtraBundle bundle) {
        final Size resultSize = picture.getImageInfo().getSize();
        CLog.i(getNodeTag(), "[mhyun2.park] getSkippedImage E: resultSize" + resultSize);

        if (null == mBokehResultBuffer || mBokehResultBuffer.capacity() != ImageUtils.getNV21BufferSize(resultSize)) {
            Optional.ofNullable(mBokehResultBuffer).ifPresent(DirectBuffer::release);
            mBokehResultBuffer = DirectBuffer.allocate(ImageUtils.getNV21BufferSize(resultSize));
        }

        mBokehResultBuffer.rewind();
        final ImageBuffer image = ImageBuffer.allocate(mBokehResultBuffer.capacity(), Objects.requireNonNullElseGet(mMainPictureImageInfo, picture::getImageInfo));
        image.put(mBokehResultBuffer);
        image.rewind();
        mBokehResultBuffer.rewind();

        CLog.i(getNodeTag(), "[mhyun2.park] getSkippedImage X");
        return image;
    }

    /**
     * <div class="camera_en">
     * setFaceInfo. coordinate transformation is performed in the solution depending on whether it is flipped or not.
     * Samsung Crop Region is based on current sensor (for Dual Bokeh Solution request and to know the sensor crop region compared with dual calibration data).
     * The FD coordinate system is based on the Google crop region, which causes a shift in coordinates. Therefore, we calculate using the Google crop region as the reference base
     * this change is only be applied to dual bokeh v2.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Face 정보를 설정한다. flip 여부에 따른 좌표 변환은 솔루션에서 한다.
     * Samsung Crop Region 은 현재 센서 기준 (Dual Bokeh Solution 요청 및 Dual Calibration 데이터와 비교한 센서 Crop Region 알기 위함).
     * Face 영역의 좌표계는 Google crop region을 기준으로 하기 때문에 좌표가 이동하게 된다. 따라서, Google crop region을 기준으로 계산한다.
     * 이 변경은 dual bokeh v2에만 적용된다.
     * </div>
     *
     * @param pictureSize               pictureSize.
     * @param sensorInfoActiveArraySize sensorInfoActiveArraySize.
     * @param captureMetadata           captureMetadata.
     */
    @Override
    protected void setFaceInfo(@NonNull CaptureMetadata captureMetadata, @NonNull Rect sensorInfoActiveArraySize, @NonNull Size pictureSize) {
        final Face[] faces = SemCaptureResult.get(captureMetadata, CaptureResult.STATISTICS_FACES);
        if (null == faces) {
            CLog.w(getNodeTag(), "setFaceInfo: failed because faces is null");
            return;
        }
        final Rect cropRegion = SemCaptureResult.get(captureMetadata, CaptureResult.SCALER_CROP_REGION);
        if (null == cropRegion) {
            CLog.w(getNodeTag(), "setFaceInfo: failed because cropRegion is null");
            return;
        }
        CLog.i(getNodeTag(), "setFaceInfo: face num = " + faces.length);

        final int length = faces.length;
        final Rect[] faceInfo = new Rect[length];
        final int[] scores = new int[length];
        for (int i = 0; i < length; i++) {
            faceInfo[i] = new Rect(faces[i].getBounds());
            scores[i] = faces[i].getScore();
            CalculationUtils.convertRectActiveArrayBaseToImageBase(faceInfo[i], pictureSize, sensorInfoActiveArraySize, cropRegion);
        }
        nativeCall(NATIVE_COMMAND_SET_FACE_INFO, faceInfo, scores);
    }

    /**
     * <div class="camera_en">
     * setFocusInfo. coordinate transformation is performed in the solution depending on whether it is flipped or not.
     * Samsung Crop Region is based on current sensor (for Dual Bokeh Solution request and to know the sensor crop region compared with dual calibration data).
     * The AF focus ROI coordinate system is based on the Google crop region, which causes a shift in coordinates. Therefore, we calculate using the Google crop region as the reference base
     * this change is only be applied to dual bokeh v2.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Focus 정보를 설정한다. flip 여부에 따른 좌표 변환은 솔루션에서 한다.
     * Samsung Crop Region 은 현재 센서 기준 (Dual Bokeh Solution 요청 및 Dual Calibration 데이터와 비교한 센서 Crop Region 알기 위함).
     * AF 영역의 좌표계는 Google crop region을 기준으로 하기 때문에 좌표가 이동하게 된다. 따라서, Google crop region을 기준으로 계산한다.
     * 이 변경은 dual bokeh v2에만 적용된다.
     * </div>
     *
     * @param pictureSize               pictureSize.
     * @param sensorInfoActiveArraySize sensorInfoActiveArraySize.
     * @param captureMetadata           captureMetadata.
     */
    @Override
    protected void setFocusInfo(@NonNull Size pictureSize, @NonNull Rect sensorInfoActiveArraySize, @NonNull CaptureMetadata captureMetadata) {
        final Rect cropRegion = SemCaptureResult.get(captureMetadata, CaptureResult.SCALER_CROP_REGION);
        if (null == cropRegion) {
            CLog.w(getNodeTag(), "setFocusInfo: failed because cropRegion is null");
            return;
        }
        CLog.i(getNodeTag(), "setFocusInfo");

        final Rect focusInfo = new Rect();
        final MeteringRectangle[] focusRect = SemCaptureResult.get(captureMetadata, CaptureResult.CONTROL_AF_REGIONS);
        if (null != focusRect) {
            focusInfo.left = focusRect[0].getX();
            focusInfo.top = focusRect[0].getY();
            focusInfo.right = focusRect[0].getX() + (focusRect[0].getWidth());
            focusInfo.bottom = focusRect[0].getY() + (focusRect[0].getHeight());
        }

        CalculationUtils.convertRectActiveArrayBaseToImageBase(focusInfo, pictureSize, sensorInfoActiveArraySize, cropRegion);
        nativeCall(NATIVE_COMMAND_SET_FOCUS_INFO, focusInfo, pictureSize);
    }

    private void setCropMode(@NonNull CaptureMetadata captureMetadata) {
        final int[] rawSensorInfo = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.SCALER_RAW_SENSOR_INFO))
                .orElse(mCamCapability.getRawSensorInfo());

        final int cropMode;
        if (rawSensorInfo != null && rawSensorInfo.length >= 5) {
            cropMode = rawSensorInfo[SCALER_RAW_SENSOR_INFO_INDEX_CROP_MODE];
        } else {
            cropMode = -1;
        }

        nativeCall(NATIVE_COMMAND_SET_CROP_MODE, cropMode);
    }

    private void setDualCalibrationNativeCall(byte[] dualCalibration, @NonNull NativeNode.Command<Void> command, @NonNull String cmdName) {
        if (null != dualCalibration) {
            final DirectBuffer directBuffer = DirectBuffer.allocate(dualCalibration.length);
            try {
                directBuffer.put(dualCalibration);
                directBuffer.rewind();
                CLog.i(getNodeTag(), cmdName + " is set");
                nativeCall(command, directBuffer);
            } finally {
                directBuffer.release();
            }
        } else {
            CLog.i(getNodeTag(), cmdName + " is null on onInitialized");
        }
    }

    @Override
    public void setDualCalibration() {
        setDualCalibrationNativeCall(mDualCalibrationW_UW, NATIVE_COMMAND_SET_CALIBRATION_W_UW_DATA, "DualCalibrationW_UW");
        setDualCalibrationNativeCall(mDualCalibrationT_W, NATIVE_COMMAND_SET_CALIBRATION_T_W_DATA, "DualCalibrationT_W");
        setDualCalibrationNativeCall(mDualCalibrationT2_T1, NATIVE_COMMAND_SET_CALIBRATION_T2_T1_DATA, "DualCalibrationT2_T1");
    }

    @Override
    protected void setExtraCaptureInfo(boolean isMainPhysicalId, @NonNull ImageInfo imageInfo) {
        final CaptureMetadata captureMetadata = Objects.requireNonNull(imageInfo.getCaptureMetadata());
        final StrideInfo strideInfo = Objects.requireNonNull(imageInfo.getStrideInfo());
        final ExtraCaptureInfo dualBokehExtraInfo =
                new ExtraCaptureInfo.Builder(getNodeTag(), captureMetadata, mCamCapability)
                        .setBrightnessValue()
                        .setWdrSensitivity()
                        .setWdrExposureTime()
                        .setDrcRatio()
                        .setGyroState()
                        .setStrideInfo(strideInfo)
                        .setZoomRatio()
                        .setCaptureEv()
                        .setLensFocusDistance()
                        .setSensorPixelMode()
                        .build();

        nativeCall(NATIVE_COMMAND_SET_CAPTURE_METADATA_INFO, dualBokehExtraInfo, isMainPhysicalId);
    }

    @Override
    protected void setMaxInputCount(int dsMode, int mainProcessCount, int subProcessCount) {
        mMaxMainInputCount = DEFAULT_MAIN_PICTURE_COUNT;
        mMaxSubInputCount = subProcessCount;
    }

    @Override
    public void setShotMode(@NonNull CaptureMetadata captureMetadata) {
        final int dsHint = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_DYNAMIC_SHOT_HINT))
                .orElse(SemCameraMetadata.CONTROL_DS_MODE_SINGLE);
        final int dsMode = DynamicShotUtils.getDsMode(dsHint);

        // Processed output(ex, HDR, AI ZOOM, NIGHT) is passing to solution, so mainCnt is always 1 for dualbokeh
        mMainCnt = DEFAULT_MAIN_COUNT;
        mSubCnt = CalculationUtils.ifPositive(DynamicShotUtils.getDsPicSubCount(dsHint), DEFAULT_SUB_PICTURE_COUNT);
        CLog.i(getNodeTag(), "setShotMode: dsMode = 0x%X, main cnt = %d, sub cnt = %d", dsMode, mMainCnt, mSubCnt);
        nativeCall(NATIVE_COMMAND_SET_SHOT_MODE, dsMode, new int[]{mMainCnt, mSubCnt});
    }

    private void setDraftPreprocessedDataPath(@NonNull ExtraBundle extraBundle) {
        final Path draftPreprocessedDataPath = extraBundle.get(ExtraBundle.BOKEH_DRAFT_PREPROCESSED_DATA_PATH);
        if (null != draftPreprocessedDataPath) {
            CLog.i(getNodeTag(), "setDraftPreprocessedDataPath : " + draftPreprocessedDataPath);
            nativeCall(NATIVE_COMMAND_SET_DRAFT_PREPROCESSED_DATA_PATH, draftPreprocessedDataPath.toString());
        } else {
            CLog.i(getNodeTag(), "setDraftPreprocessedDataPath : DraftPreprocessedDataPath is null");
        }
    }

    private void setMainImageBufferAndSize(@NonNull ImageBuffer picture) {
        final ImageInfo pictureImageInfo = picture.getImageInfo();
        final CaptureMetadata captureMetadata = Objects.requireNonNull(pictureImageInfo.getCaptureMetadata());
        final Size pictureSize = Objects.requireNonNull(pictureImageInfo.getSize());
        final StrideInfo strideInfo = pictureImageInfo.getStrideInfo();

        final int bufferSize = pictureImageInfo.getFormat().getBufferSize(pictureSize, strideInfo);

        if (null == mMainImageBuffer || mMainImageBuffer.capacity() != bufferSize) {
            Optional.ofNullable(mMainImageBuffer).ifPresent(DirectBuffer::release);
            mMainImageBuffer = DirectBuffer.allocate(bufferSize);
        }

        if (picture.capacity() >= bufferSize) {
            picture.rewind();
            mMainImageBuffer.rewind();
            picture.get(mMainImageBuffer);
            mMainImageBuffer.rewind();
            picture.rewind();
            CLog.i(getNodeTag(), "setMainImageBufferAndSize: picture input size : %d, buffer size : %d", picture.capacity(), bufferSize);
        }

        CLog.i(getNodeTag(), "setMainImageBufferAndSize: bufferSize : " + bufferSize + "byte");
        mMainPictureSize = pictureSize;

        mMainPictureImageInfo = ImageInfo.createAfterCopy(pictureImageInfo, info -> {
            info.setCaptureMetadata(captureMetadata);
            info.setStrideInfo(strideInfo);
        });

        nativeCall(NATIVE_COMMAND_SET_MAIN_PICTURE_SIZE, mMainPictureSize, strideInfo);
    }

    private void setCapturePhysicalId(@NonNull CaptureMetadata captureMetadata) {
        final Integer mainPhysicalId = SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_CAPTURE_PHYSICAL_ID);
        final Integer subPhysicalId = SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_CAPTURE_SUB_PHYSICAL_ID);
        CLog.i(getNodeTag(), "processPictureYuv: mainPhysicalId: " + mainPhysicalId + " subPhysicalId :" + subPhysicalId);
        nativeCall(NATIVE_COMMAND_SET_CAPTURE_PHYSICAL_PAIR, mainPhysicalId, subPhysicalId);
    }

    private void setLightMapInfo(@NonNull ExtraBundle bundle) {
        final DirectBuffer lightMapBuffer = bundle.get(ExtraBundle.LIGHT_MAP_BUFFER);
        final LightMapInfo lightMapinfo = bundle.get(ExtraBundle.LIGHT_MAP_INFO);
        if (null != lightMapBuffer && null != lightMapinfo) {
            CLog.i(getNodeTag(), "setLightMapInfo : " + lightMapinfo);
            nativeCall(NATIVE_COMMAND_SET_LIGHT_MAP_INFO, lightMapBuffer, lightMapinfo);
            bundle.clear(ExtraBundle.LIGHT_MAP_BUFFER);
            bundle.clear(ExtraBundle.LIGHT_MAP_INFO);
        } else {
            CLog.i(getNodeTag(), "setLightMapInfo : lightMapinfo is null");
        }
    }

    @Override
    protected void abortProcess() {
        nativeCall2(NATIVE_COMMAND_ABORT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void release() {
        super.release();
        mDualCalibrationT_W = null;
        mDualCalibrationT2_T1 = null;
        mDualCalibrationW_UW = null;
    }

    public enum EntryMode {
        DUAL_PORTRAIT_ENTRY_MODE_OUT,
        DUAL_PORTRAIT_ENTRY_MODE_IN
    }
}
