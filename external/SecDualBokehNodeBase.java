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

package com.samsung.android.camera.core2.node.dualBokeh.samsung;

import static com.samsung.android.camera.core2.container.ExtraBundle.PROCESSED_OPTION_NONE;
import static com.samsung.android.camera.core2.node.dualBokeh.DualBokehNodeBase.DualBokehInitParam.SOLUTION_TYPE_LITE;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.MeteringRectangle;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.CamCapability;
import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.ExtraCaptureInfo;
import com.samsung.android.camera.core2.container.PetDetectionInfo;
import com.samsung.android.camera.core2.container.PetInfo;
import com.samsung.android.camera.core2.exception.InvalidOperationException;
import com.samsung.android.camera.core2.local.util.MarshalUtils;
import com.samsung.android.camera.core2.local.vendorkey.CaptureMetadata;
import com.samsung.android.camera.core2.local.vendorkey.SemCameraMetadata;
import com.samsung.android.camera.core2.local.vendorkey.SemCaptureResult;
import com.samsung.android.camera.core2.local.vendorkey.metadata.CaptureMetadataKey;
import com.samsung.android.camera.core2.local.vendorkey.metadata.RequiredCaptureMetadata;
import com.samsung.android.camera.core2.node.NativeNode;
import com.samsung.android.camera.core2.node.Node;
import com.samsung.android.camera.core2.node.NodeId;
import com.samsung.android.camera.core2.node.SefNode;
import com.samsung.android.camera.core2.node.SefNode.SefNodeParam;
import com.samsung.android.camera.core2.node.dualBokeh.DualBokehNodeBase;
import com.samsung.android.camera.core2.util.BufferInfo;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.CalculationUtils;
import com.samsung.android.camera.core2.util.DebugUtils;
import com.samsung.android.camera.core2.util.DirectBuffer;
import com.samsung.android.camera.core2.util.DynamicShotUtils;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.MemoryUtils;
import com.samsung.android.camera.core2.util.StrideInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@RequiredCaptureMetadata(keys = {
        CaptureMetadataKey.CONTROL_AF_MODE,
        CaptureMetadataKey.CONTROL_AF_REGIONS,
        CaptureMetadataKey.CONTROL_BEAUTY_FACE_RETOUCH_LEVEL,
        CaptureMetadataKey.CONTROL_BEAUTY_FACE_SKIN_COLOR,
        CaptureMetadataKey.CONTROL_BOKEH_BLUR_STRENGTH,
        CaptureMetadataKey.CONTROL_BOKEH_DUAL_PREVIEW_INFO,
        CaptureMetadataKey.CONTROL_BOKEH_RELIGHT_LEVEL,
        CaptureMetadataKey.CONTROL_BOKEH_SPECIAL_EFFECT_INFO,
        CaptureMetadataKey.CONTROL_BOKEH_STATE,
        CaptureMetadataKey.CONTROL_BRIGHTNESS_VALUE,
        CaptureMetadataKey.CONTROL_CAPTURE_PHYSICAL_ID,
        CaptureMetadataKey.CONTROL_COLOR_TEMPERATURE,
        CaptureMetadataKey.CONTROL_DEVICE_STATE,
        CaptureMetadataKey.CONTROL_DYNAMIC_SHOT_EXTRA_INFO,
        CaptureMetadataKey.CONTROL_DYNAMIC_SHOT_HINT,
        CaptureMetadataKey.CONTROL_LIGHT_CONDITION,
        CaptureMetadataKey.CONTROL_OVER_HEAT_HINT,
        CaptureMetadataKey.CONTROL_PERSONAL_PRESET_INDEX,
        CaptureMetadataKey.CONTROL_PET_DETECTION_INFO,
        CaptureMetadataKey.CONTROL_RUNNING_PHYSICAL_ID,
        CaptureMetadataKey.CONTROL_SCENE_DETECTION_INFO,
        CaptureMetadataKey.CONTROL_SUB_STREAM_TIMESTAMP,
        CaptureMetadataKey.CONTROL_ZOOM_RATIO,
        CaptureMetadataKey.JPEG_ORIENTATION,
        CaptureMetadataKey.LENS_FOCUS_DISTANCE,
        CaptureMetadataKey.SAMSUNG_SCALER_CROP_REGION,
        CaptureMetadataKey.SCALER_BASE_IMAGE_COORDINATES,
        CaptureMetadataKey.SCALER_CROP_REGION,
        CaptureMetadataKey.SCALER_FLIP_MODE,
        CaptureMetadataKey.SCALER_ZOOM_RATIO,
        CaptureMetadataKey.SENSOR_CAPTURE_EV,
        CaptureMetadataKey.SENSOR_CAPTURE_TOTAL_GAIN,
        CaptureMetadataKey.SENSOR_DRC_RATIO,
        CaptureMetadataKey.SENSOR_GYRO_STATE,
        CaptureMetadataKey.SENSOR_STREAM_TYPE,
        CaptureMetadataKey.SENSOR_WDR_EXPOSURE_TIME,
        CaptureMetadataKey.SENSOR_WDR_SENSITIVITY,
        CaptureMetadataKey.STATISTICS_FACES,
        CaptureMetadataKey.STATISTICS_OIS_HALL_INFO,
        CaptureMetadataKey.STATISTICS_SPECIAL_SCENE_AE
})
public abstract class SecDualBokehNodeBase extends DualBokehNodeBase {

    protected static final int FIRST_CAPTURE_COUNT = 1;

    protected static final int NATIVE_CALLBACK_DEFAULT_META_DATA = 1;
    protected static final int NATIVE_CALLBACK_CORE_INFO_META_DATA = 2;
    protected static final int NATIVE_CALLBACK_BOKEH_DEBUG_DATA = 3;
    protected static final int NATIVE_CALLBACK_RELIGHT_EXTRA_DATA = 4;

    protected static final NativeNode.Command<Boolean> NATIVE_COMMAND_INIT = new NativeNode.Command<>(100, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_ORIENTATION = new NativeNode.Command<>(101, Integer.class) {};
    protected static final NativeNode.Command<Integer> NATIVE_COMMAND_SET_BUFFER_INFO = new NativeNode.Command<>(102, DirectBuffer.class, Size.class, Boolean.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_CAPTURE_METADATA_INFO = new NativeNode.Command<>(103, ExtraCaptureInfo.class, Boolean.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_FACE_INFO = new NativeNode.Command<>(104, Rect[].class, int[].class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_FOCUS_INFO = new NativeNode.Command<>(105, Rect.class, Size.class) {};
    protected static final NativeNode.Command<Integer> NATIVE_COMMAND_MAKE_DUAL_BOKEH = new NativeNode.Command<>(106, DirectBuffer.class, DirectBuffer.class, Size.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_PICTURE_SKIN_SOFTEN_LEVEL = new NativeNode.Command<>(107, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_PICTURE_FACE_COLOR_LEVEL = new NativeNode.Command<>(108, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_PICTURE_BLUR_LEVEL = new NativeNode.Command<>(109, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_PICTURE_EFFECT_TYPE = new NativeNode.Command<>(110, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_PICTURE_EFFECT_LEVEL = new NativeNode.Command<>(111, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_BOKEH_STATE = new NativeNode.Command<>(112, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_FLIP_MODE = new NativeNode.Command<>(113, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_SHOT_MODE = new NativeNode.Command<>(115, Integer.class, int[].class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_CAMERA_ID = new NativeNode.Command<>(116, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_SENSOR_STREAM_TYPE = new NativeNode.Command<>(118, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_AF_MODE = new NativeNode.Command<>(119, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_BUFFER_TIMESTAMP = new NativeNode.Command<>(123, Long.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_MAIN_PICTURE_SIZE = new NativeNode.Command<>(124, Size.class, StrideInfo.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_PREVIEW_INFO = new NativeNode.Command<>(125, DirectBuffer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_RELIGHT_LEVEL = new NativeNode.Command<>(126, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_NIGHT_RESULT_INFO = new NativeNode.Command<>(127, byte[].class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_SUB_IMAGE_BUFFER = new NativeNode.Command<>(128, DirectBuffer.class, Size.class, StrideInfo.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_OVER_HEAT_LEVEL = new NativeNode.Command<>(129, Integer.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_MEMORY_INFO = new NativeNode.Command<>(130, Long.class, Long.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_PET_DETECTION_INFO = new NativeNode.Command<>(131, int[].class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_DEVICE_STATE = new NativeNode.Command<>(132, long.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_OIS_HALL_INFO = new NativeNode.Command<>(133, long[].class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_SPECIAL_SCENE_AE_INFO = new NativeNode.Command<>(134, int[].class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_CROP_INFO = new NativeNode.Command<>(135, Rect.class, Rect.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_HDR_CROP_REGION = new NativeNode.Command<>(136, Rect.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_SET_EXTRA_YUV_BUFFER = new NativeNode.Command<>(137, BufferInfo.class) {};
    protected static final NativeNode.Command<Void> NATIVE_COMMAND_ABORT = new NativeNode.Command<>(1000) {};

    protected final ActivityManager mActivityManager;
    protected final MultiFrameNodeCallback mNodeCallback;
    protected final Map<SefNode.SefNodeParam, byte[]> mSefNodeParamMap = new HashMap<>();

    protected Size mMainPictureSize;
    protected Size mSubPictureSize;

    protected ImageInfo mMainPictureImageInfo;
    protected ImageInfo mSubPictureImageInfo;

    protected int mCameraId;

    protected CamCapability mCamCapability;

    protected DirectBuffer mBokehResultBuffer;
    protected DirectBuffer mMainImageBuffer;
    protected DirectBuffer mSubImageBuffer;

    protected byte[] mBokehDebugInfo = null;

    protected boolean mIsBokehRelightSupport;
    protected boolean mIsBokehEffectSupport;
    protected boolean mAvailableFlipMode;
    protected boolean mPetDetectionInfoAvailable;

    protected int mMainFrameCount;
    protected int mMainCnt;
    protected int mSubCnt;
    protected int mSolutionType;

    {
        mNativeCallbacks.put(NATIVE_CALLBACK_DEFAULT_META_DATA, new NativeNode.NativeCallback<byte[], byte[], byte[]>() {
            @Override
            public void onPostEventFromNative(byte[] arg, byte[] arg1, byte[] arg2) {
                CLog.i(getNodeTag(), "onPostEventFromNative - DEFAULT_META_DATA");
                mSefNodeParamMap.put(SefNodeParam.DUAL_BOKEH_META, arg);
                mSefNodeParamMap.put(SefNodeParam.DUAL_BOKEH_DEPTH_MAP, arg1);
                mSefNodeParamMap.put(SefNodeParam.DUAL_BOKEH_EXTRA, arg2);
            }
        });

        mNativeCallbacks.put(NATIVE_CALLBACK_CORE_INFO_META_DATA, new NativeNode.NativeCallback<byte[], Void, Void>() {
            @Override
            public void onPostEventFromNative(byte[] arg, Void arg1, Void arg2) {
                CLog.i(getNodeTag(), "onPostEventFromNative - CORE_INFO_META_DATA : coreInfo Size= %d", arg == null ? null : arg.length);
                if (null != arg) {
                    mSefNodeParamMap.put(SefNodeParam.DUAL_BOKEH_CORE_INFO, arg);
                }
            }
        });

        mNativeCallbacks.put(NATIVE_CALLBACK_BOKEH_DEBUG_DATA, new NativeNode.NativeCallback<byte[], Void, Void>() {
            @Override
            public void onPostEventFromNative(byte[] arg, Void arg1, Void arg2) {
                CLog.i(getNodeTag(), "onPostEventFromNative - BOKEH_DEBUG_DATA : debugInfo size= %d", arg == null ? null : arg.length);
                mBokehDebugInfo = arg;
            }
        });

        mNativeCallbacks.put(NATIVE_CALLBACK_RELIGHT_EXTRA_DATA, new NativeNode.NativeCallback<byte[], Void, Void>() {
            @Override
            public void onPostEventFromNative(byte[] arg, Void arg1, Void arg2) {
                CLog.i(getNodeTag(), "onPostEventFromNative - DUAL_BOKEH_RELIGHT_EXTRA_INFO : size= %d", arg == null ? null : arg.length);
                if (null != arg) {
                    mSefNodeParamMap.put(SefNodeParam.DUAL_BOKEH_RELIGHT_EXTRA_INFO, arg);
                }
            }
        });
    }

    public SecDualBokehNodeBase(@NonNull NodeId nodeId, @NonNull String nodeTag, @NonNull DualBokehNodeBase.DualBokehInitParam dualBokehInitParam, @NonNull MultiFrameNodeCallback callback) {
        super(nodeId, nodeTag, /*hasNativeNode*/true, callback);

        mNodeCallback = callback;
        mActivityManager = (ActivityManager) dualBokehInitParam.context().getSystemService(Context.ACTIVITY_SERVICE);
        mCamCapability = dualBokehInitParam.camCapability();
        mSolutionType = dualBokehInitParam.solutionType();
        mIsBokehRelightSupport = mCamCapability.getSamsungFeatureBokehRelightAvailable();
        mIsBokehEffectSupport = mCamCapability.getSamsungFeatureBokehSpecialEffectAvailable();
        mAvailableFlipMode = mCamCapability.getSamsungScalerFlipAvailableModes().length > 1;
        mPetDetectionInfoAvailable = mCamCapability.getSamsungFeaturePetDetectionInfoAvailable();
    }

    @Override
    synchronized public void reconfigure(@NonNull Object initParam) {
        super.reconfigure(initParam);
        final DualBokehNodeBase.DualBokehInitParam dualBokehInitParam = (DualBokehNodeBase.DualBokehInitParam) initParam;
        CLog.i(getNodeTag(), "reconfigure - %s", dualBokehInitParam);

        mCamCapability = dualBokehInitParam.camCapability();
        mSolutionType = dualBokehInitParam.solutionType();
        mIsBokehRelightSupport = mCamCapability.getSamsungFeatureBokehRelightAvailable();
        mIsBokehEffectSupport = mCamCapability.getSamsungFeatureBokehSpecialEffectAvailable();
        mAvailableFlipMode = mCamCapability.getSamsungScalerFlipAvailableModes().length > 1;
        mPetDetectionInfoAvailable = mCamCapability.getSamsungFeaturePetDetectionInfoAvailable();
        mSefNodeParamMap.clear();
    }

    @Override
    protected void onInitialized(@NonNull Map<NativeNode.Command<?>, Object[]> initParams) {
        if (!nativeCall(NATIVE_COMMAND_INIT, mSolutionType)) {
            throw new InvalidOperationException("onInitialized fail - init lib fail");
        }
        setCameraId(mCameraId);
        setDualCalibration();
        mMainFrameCount = 0;
        super.onInitialized(initParams);
    }

    @Override
    protected void prepareFirstYuvProcessPicture(@NonNull ImageInfo imageInfo, @NonNull ExtraBundle bundle) {
        CLog.i(getNodeTag(), "prepareFirstYuvProcessPicture E");
        final CaptureMetadata captureMetadata = Objects.requireNonNull(imageInfo.getCaptureMetadata());
        setShotMode(captureMetadata);
        CLog.i(getNodeTag(), "prepareFirstYuvProcessPicture X");
    }

    @Override
    protected void setSubPictureYuv(@NonNull ImageBuffer picture, @NonNull CaptureMetadata captureMetadata) {
        CLog.i(getNodeTag(), "setSubPictureYuv");
        setSubImageBuffer(picture);
        setBokehState(Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_BOKEH_STATE))
                .orElse(SemCameraMetadata.CONTROL_BOKEH_STATE_SUCCESS));
    }

    protected void setProcessedOption(@NonNull ExtraBundle bundle, boolean isBokehSuccess) {
        if (!isBokehSuccess) {
            return;
        }

        final int processedOption = Optional.ofNullable(bundle.get(ExtraBundle.INFO_PROCESSED_OPTION)).orElse(PROCESSED_OPTION_NONE);
        bundle.put(ExtraBundle.INFO_PROCESSED_OPTION, processedOption | ExtraBundle.PROCESSED_OPTION_BOKEH);
        CLog.i(getNodeTag(), "setProcessedOption : PROCESSED_OPTION_BOKEH");
    }

    protected void saveSourceImageToSefParam(@NonNull ExtraBundle bundle, boolean isBokehSuccess) {
        // Set SEF Data.
        final Map<SefNode.SefNodeParam, byte[]> sefNodeParamMap = bundle.computeIfAbsent(ExtraBundle.SEF_CONTROL_NODE_PARAM_MAP, HashMap::new);
        sefNodeParamMap.putAll(mSefNodeParamMap);
        mSefNodeParamMap.clear();
        mMainFrameCount = 0;

        if (mSolutionType == SOLUTION_TYPE_LITE) {
            CLog.i(getNodeTag(), "saveSourceImageToSefParam - skip : solution type is lite");
            return;
        }

        CLog.i(getNodeTag(), "saveSourceImageToSefParam");
        // Set Main Jpeg data.
        if (null != mMainImageBuffer && isBokehSuccess) {
            final ExtraBundle mainImageBundle = new ExtraBundle();
            mainImageBundle.put(ExtraBundle.SEF_INFO_SAVE_DATA_TYPE, ExtraBundle.SaveDataType.DUAL_BOKEH_MAIN_JPEG);

            mMainPictureImageInfo.setSize(mMainPictureSize);
            mMainImageBuffer.rewind();
            final ImageBuffer mainImageBuffer = ImageBuffer.allocate(mMainImageBuffer.capacity(), mMainPictureImageInfo);
            mainImageBuffer.put(mMainImageBuffer);
            mainImageBuffer.rewind();
            mMainImageBuffer.rewind();

            Optional.ofNullable(convertImageToEncodedData(mainImageBuffer, mainImageBundle))
                    .ifPresent(encodedImage -> sefNodeParamMap.put(SefNode.SefNodeParam.DUAL_BOKEH_INPUT_MAIN, encodedImage));
            mainImageBuffer.release();
        }

        final boolean needSubJpegData = (DebugUtils.BOOT_DEBUG_LEVEL == DebugUtils.DebugLevel.NONE)
                ? DebugUtils.isDebugModeEnabled()
                : (DebugUtils.BOOT_DEBUG_LEVEL != DebugUtils.DebugLevel.LOW);

        // Set Sub Jpeg data.
        if (null != mSubImageBuffer && isBokehSuccess && needSubJpegData) {
            final ExtraBundle subImageBundle = new ExtraBundle();
            subImageBundle.put(ExtraBundle.SEF_INFO_SAVE_DATA_TYPE, ExtraBundle.SaveDataType.DUAL_BOKEH_SUB_JPEG);

            mSubPictureImageInfo.setSize(mSubPictureSize);
            mSubImageBuffer.rewind();
            final ImageBuffer subImageBuffer = ImageBuffer.allocate(mSubImageBuffer.capacity(), mSubPictureImageInfo);
            subImageBuffer.put(mSubImageBuffer);
            subImageBuffer.rewind();
            mSubImageBuffer.rewind();

            Optional.ofNullable(convertImageToEncodedData(subImageBuffer, subImageBundle))
                    .ifPresent(encodedImage -> sefNodeParamMap.put(SefNode.SefNodeParam.DUAL_BOKEH_INPUT_SUB, encodedImage));
            subImageBuffer.release();
        }
    }

    @Nullable
    protected byte[] convertImageToEncodedData(@NonNull ImageBuffer sourceImageBuffer,
                                               @NonNull ExtraBundle sourceImageBundle) {
        final ImageBuffer encodedImageBuffer = (ImageBuffer) Node.set(OUTPUTPORT_PICTURE, sourceImageBuffer, sourceImageBundle);
        if (null == encodedImageBuffer) {
            CLog.w(getNodeTag(), "convertImageToEncodedData is failed - encodedImageBuffer is null");
            return null;
        }

        final byte[] encodedImageData = new byte[encodedImageBuffer.capacity()];
        encodedImageBuffer.get(encodedImageData);
        encodedImageBuffer.rewind();
        return encodedImageData;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDeviceOrientation(int orientation) {
        CLog.i(getNodeTag(), "setDeviceOrientation " + orientation);
        tryNativeCall(NATIVE_COMMAND_ORIENTATION, orientation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFaceColorLevel(int level) {
        CLog.i(getNodeTag(), "setFaceColorLevel " + level);
        tryNativeCall(NATIVE_COMMAND_SET_PICTURE_FACE_COLOR_LEVEL, level);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setSkinSoftenLevel(int level) {
        CLog.i(getNodeTag(), "setPictureSkinSoftenLevel " + level);
        tryNativeCall(NATIVE_COMMAND_SET_PICTURE_SKIN_SOFTEN_LEVEL, level);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBokehBlurLevel(int level) {
        CLog.i(getNodeTag(), "setBokehBlurLevel " + level);
        tryNativeCall(NATIVE_COMMAND_SET_PICTURE_BLUR_LEVEL, level);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBokehRelightLevel(int level) {
        CLog.i(getNodeTag(), "setBokehRelightLevel " + level);
        tryNativeCall(NATIVE_COMMAND_SET_RELIGHT_LEVEL, level);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBokehEffectType(int type) {
        CLog.i(getNodeTag(), "setBokehEffectType " + type);
        tryNativeCall(NATIVE_COMMAND_SET_PICTURE_EFFECT_TYPE, type);
    }

    @Override
    public void setBokehEffectLevel(int level) {
        CLog.i(getNodeTag(), "setBokehEffectLevel " + level);
        tryNativeCall(NATIVE_COMMAND_SET_PICTURE_EFFECT_LEVEL, level);
    }

    @Override
    public void setBokehState(int state) {
        CLog.i(getNodeTag(), "setBokehState " + state);
        tryNativeCall(NATIVE_COMMAND_SET_BOKEH_STATE, state);
    }

    @Override
    public void setCameraId(int cameraId) {
        CLog.i(getNodeTag(), "setCameraId " + cameraId);
        tryNativeCall(NATIVE_COMMAND_SET_CAMERA_ID, cameraId);
    }

    @Override
    public void setFlipMode(int flipMode) {
        tryNativeCall(NATIVE_COMMAND_SET_FLIP_MODE, flipMode);
    }

    protected void setOverHeatLevel(@NonNull CaptureMetadata captureMetadata, @NonNull ExtraBundle bundle) {
        if (mNodeId != NodeId.NODE_SEC_V1_DUAL_BOKEH) {
            Integer overHeatHint = bundle.get(ExtraBundle.REALTIME_OVER_HEAT_HINT);
            if (null == overHeatHint) {
                overHeatHint = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_OVER_HEAT_HINT))
                        .orElse(SemCameraMetadata.CONTROL_OVER_HEAT_HINT_NONE);
            }
            CLog.i(getNodeTag(), "setOverHeatLevel: overHeatHint = 0x%X", overHeatHint);
            nativeCall(NATIVE_COMMAND_SET_OVER_HEAT_LEVEL, overHeatHint);
        }
    }

    protected void setMemoryInfo() {
        if (mNodeId != NodeId.NODE_SEC_V1_DUAL_BOKEH) {
            final ActivityManager.MemoryInfo memoryInfo = MemoryUtils.getMemoryInfo(mActivityManager);
            CLog.v(getNodeTag(), "setMemoryInfo: memoryInfo = {available %d, threshold %d}", memoryInfo.availMem, memoryInfo.threshold);
            nativeCall(NATIVE_COMMAND_SET_MEMORY_INFO, memoryInfo.availMem, memoryInfo.threshold);
        }
    }

    protected void setAfMode(@NonNull CaptureMetadata captureMetadata) {
        final Integer afMode = SemCaptureResult.get(captureMetadata, CaptureResult.CONTROL_AF_MODE);
        CLog.i(getNodeTag(), "setAfMode: af mode is %d" , afMode);

        if (null != afMode) {
            nativeCall(NATIVE_COMMAND_SET_AF_MODE, afMode);
        }
    }

    protected void setBokehExtraInfo(@NonNull ImageBuffer picture,
                                     @NonNull ExtraBundle bundle,
                                     @Nullable Integer streamType,
                                     boolean isMainPhysicalId,
                                     @NonNull CaptureMetadata captureMetadata) {
        Optional.ofNullable(streamType).ifPresent(sensorStreamType -> nativeCall(NATIVE_COMMAND_SET_SENSOR_STREAM_TYPE, sensorStreamType));

        if (mIsBokehRelightSupport) {
            bundle.put(ExtraBundle.DUAL_BOKEH_INFO_RELIGHT_SUPPORTED, true);
            setBokehRelightLevel(Optional.ofNullable(SemCaptureResult.get(captureMetadata,
                    SemCaptureResult.CONTROL_BOKEH_RELIGHT_LEVEL)).orElse(0));
        }

        if (mIsBokehEffectSupport) {
            final int[] bokehEffectInfo = SemCaptureResult.get(captureMetadata,
                    SemCaptureResult.CONTROL_BOKEH_SPECIAL_EFFECT_INFO);
            if (null != bokehEffectInfo && bokehEffectInfo.length >= 2) {
                setBokehEffectType(bokehEffectInfo[SemCameraMetadata.CONTROL_BOKEH_SPECIAL_EFFECT_INFO_INDEX_MODE]);
                setBokehEffectLevel(bokehEffectInfo[SemCameraMetadata.CONTROL_BOKEH_SPECIAL_EFFECT_INFO_INDEX_VALUE]);
            } else {
                setBokehEffectType(SemCameraMetadata.CONTROL_BOKEH_SPECIAL_EFFECT_BOKEH_LENS);
                setBokehEffectLevel(0);
            }
        } else {
            setBokehBlurLevel(Optional.ofNullable(SemCaptureResult.get(captureMetadata,
                    SemCaptureResult.CONTROL_BOKEH_BLUR_STRENGTH)).orElse(0));
        }

        if (mAvailableFlipMode) {
            setFlipMode(Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.SCALER_FLIP_MODE))
                    .orElse(SemCameraMetadata.SCALER_FLIP_MODE_NONE));
        }
        setSkinSoftenLevel(Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_BEAUTY_FACE_RETOUCH_LEVEL))
                .orElse(0));
        setFaceColorLevel(Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_BEAUTY_FACE_SKIN_COLOR))
                .orElse(0));

        long timestamp = picture.getImageInfo().getTimestamp();
        if (!isMainPhysicalId) {
            timestamp = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_SUB_STREAM_TIMESTAMP))
                    .orElse(timestamp);
            CLog.i(getNodeTag(), "setBokehExtraInfo - CONTROL_SUB_STREAM_TIMESTAMP : " + SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_SUB_STREAM_TIMESTAMP));
        }
        nativeCall(NATIVE_COMMAND_SET_BUFFER_TIMESTAMP, timestamp);
    }

    protected int setBufferInfo(@NonNull ImageBuffer picture, boolean isMainPhysicalId) {
        final Size pictureSize = Objects.requireNonNull(picture.getImageInfo().getSize());
        return nativeCall(NATIVE_COMMAND_SET_BUFFER_INFO, picture, pictureSize, isMainPhysicalId);
    }

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
                        .build();

        nativeCall(NATIVE_COMMAND_SET_CAPTURE_METADATA_INFO, dualBokehExtraInfo, isMainPhysicalId);
    }

    /**
     * <div class="camera_en">
     * setFaceInfo. coordinate transformation is performed in the solution depending on whether it is flipped or not.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Face 정보를 설정한다. flip 여부에 따른 좌표 변환은 솔루션에서 한다.
     * </div>
     *
     * @param captureMetadata           captureMetadata.
     * @param sensorInfoActiveArraySize sensorInfoActiveArraySize.
     * @param pictureSize               pictureSize.
     */
    protected void setFaceInfo(@NonNull CaptureMetadata captureMetadata, @NonNull Rect sensorInfoActiveArraySize, @NonNull Size pictureSize) {
        final Face[] faces = SemCaptureResult.get(captureMetadata, CaptureResult.STATISTICS_FACES);
        if (null == faces) {
            CLog.w(getNodeTag(), "setFaceInfo: failed because faces is null");
            return;
        }
        CLog.i(getNodeTag(), "setFaceInfo: face num = " + faces.length);

        final int length = faces.length;
        final Rect[] faceInfo = new Rect[length];
        final int[] scores = new int[length];
        final Rect cropRegion = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.SCALER_CROP_REGION))
                .orElseGet(() -> SemCaptureResult.get(captureMetadata, CaptureResult.SCALER_CROP_REGION));

        for (int i = 0; i < length; i++) {
            faceInfo[i] = new Rect(faces[i].getBounds());
            scores[i] = faces[i].getScore();
            CalculationUtils.convertRectActiveArrayBaseToImageBase(faceInfo[i], pictureSize, sensorInfoActiveArraySize, cropRegion);
        }
        nativeCall(NATIVE_COMMAND_SET_FACE_INFO, faceInfo, scores);
    }

    protected void setCropRegionInfo(@NonNull CaptureMetadata captureMetadata) {
        if (mNodeId != NodeId.NODE_SEC_V1_DUAL_BOKEH) {
            final Rect cropRegion = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.SCALER_CROP_REGION))
                    .orElseGet(() -> SemCaptureResult.get(captureMetadata, CaptureResult.SCALER_CROP_REGION));
            final Rect baseInfo = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.SCALER_BASE_IMAGE_COORDINATES))
                    .orElseGet(() -> new Rect(0, 0, 0, 0));
            nativeCall(NATIVE_COMMAND_SET_CROP_INFO, cropRegion, baseInfo);
        }
    }

    protected void setDeviceState(@NonNull CaptureMetadata captureMetadata) {
        if (mCamCapability.getSamsungFeatureFoldable()) {
            final long deviceState = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_DEVICE_STATE))
                    .orElse((long) SemCameraMetadata.CONTROL_DEVICE_STATE_NONE);
            CLog.i(getNodeTag(), "setDeviceState: deviceState = 0x%X", deviceState);
            nativeCall(NATIVE_COMMAND_SET_DEVICE_STATE, deviceState);
        }
    }

    protected void setHdrCropRegion(@NonNull ExtraBundle bundle) {
        if (mNodeId != NodeId.NODE_SEC_V1_DUAL_BOKEH) {
            Optional.ofNullable(bundle.get(ExtraBundle.HDR_CROP_REGION))
                    .ifPresent(hdrCropRegion -> nativeCall(NATIVE_COMMAND_SET_HDR_CROP_REGION, hdrCropRegion));
        }
    }

    protected void setRefMainYuvImage(@NonNull ExtraBundle bundle) {
        final ImageBuffer referenceMainYuvImage = bundle.get(ExtraBundle.MULTI_PICTURE_REF_MAIN_YUV_IMAGE_FOR_DUAL_CAMERA);
        if (null != referenceMainYuvImage) {
            final ImageInfo pictureImageInfo = referenceMainYuvImage.getImageInfo();
            final BufferInfo inputBuffer = new BufferInfo(referenceMainYuvImage, pictureImageInfo);
            CLog.i(getNodeTag(), "setRefMainYuvImage pictureSize " + pictureImageInfo.getSize());
            nativeCall(NATIVE_COMMAND_SET_EXTRA_YUV_BUFFER, inputBuffer);
            referenceMainYuvImage.release();
            bundle.remove(ExtraBundle.MULTI_PICTURE_REF_MAIN_YUV_IMAGE_FOR_DUAL_CAMERA);
        }
    }

    /**
     * <div class="camera_en">
     * setFocusInfo. coordinate transformation is performed in the solution depending on whether it is flipped or not.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Focus 정보를 설정한다. flip 여부에 따른 좌표 변환은 솔루션에서 한다.
     * </div>
     *
     * @param captureMetadata           captureMetadata.
     * @param sensorInfoActiveArraySize sensorInfoActiveArraySize.
     * @param pictureSize               pictureSize.
     */
    protected void setFocusInfo(@NonNull Size pictureSize, @NonNull Rect sensorInfoActiveArraySize, @NonNull CaptureMetadata captureMetadata) {
        CLog.i(getNodeTag(), "setFocusInfo");
        final MeteringRectangle[] focusRect = SemCaptureResult.get(captureMetadata, CaptureResult.CONTROL_AF_REGIONS);
        final Rect cropRegion = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.SCALER_CROP_REGION))
                .orElseGet(() -> SemCaptureResult.get(captureMetadata, CaptureResult.SCALER_CROP_REGION));
        final Rect focusInfo = new Rect();

        if (null != focusRect) {
            focusInfo.left = focusRect[0].getX();
            focusInfo.top = focusRect[0].getY();
            focusInfo.right = focusRect[0].getX() + (focusRect[0].getWidth());
            focusInfo.bottom = focusRect[0].getY() + (focusRect[0].getHeight());
        }

        CalculationUtils.convertRectActiveArrayBaseToImageBase(focusInfo, pictureSize, sensorInfoActiveArraySize, cropRegion);

        nativeCall(NATIVE_COMMAND_SET_FOCUS_INFO, focusInfo, pictureSize);
    }

    protected void setNightResultInfo(@NonNull ExtraBundle bundle) {
        byte[] nightResultInfo = null;
        if (null != bundle.get(ExtraBundle.NIGHT_INFO_PROCESS_RESULT)) {
            nightResultInfo = bundle.get(ExtraBundle.NIGHT_INFO_PROCESS_RESULT);
        }
        CLog.i(getNodeTag(), "setNightResultInfo: data size = " + ((null != nightResultInfo) ? nightResultInfo.length : null));
        nativeCall(NATIVE_COMMAND_SET_NIGHT_RESULT_INFO, new Object[]{nightResultInfo});
    }

    protected void setOisHallInfo(@NonNull CaptureMetadata captureMetadata) {
        if (mNodeId != NodeId.NODE_SEC_V1_DUAL_BOKEH) {
            final long[] oisHallInfo = SemCaptureResult.get(captureMetadata, SemCaptureResult.STATISTICS_OIS_HALL_INFO);
            if (null != oisHallInfo) {
                CLog.i(getNodeTag(), "setOisHallInfo: data size = " + oisHallInfo.length);
                nativeCall(NATIVE_COMMAND_SET_OIS_HALL_INFO, new Object[]{oisHallInfo});
            }
        }
    }

    protected void setPetDetectionInfo(@NonNull CaptureMetadata captureMetadata,
                                       @NonNull Rect sensorInfoActiveArraySize,
                                       @NonNull Size pictureSize) {
        if (!mPetDetectionInfoAvailable) {
            return;
        }
        CLog.i(getNodeTag(), "setPetDetectionInfo");
        final PetInfo petInfo = MarshalUtils.unmarshalPetDetectionInfo(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_PET_DETECTION_INFO));

        if (null != petInfo && petInfo.petDetectionInfo().length > 0) {
            final List<PetDetectionInfo> calculatedPetDetectionInfo = new ArrayList<>();
            final Rect cropRegion = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.SCALER_CROP_REGION))
                    .orElseGet(() -> SemCaptureResult.get(captureMetadata, CaptureResult.SCALER_CROP_REGION));

            for (int i = 0; i < petInfo.petDetectionInfo().length; i++) {
                CalculationUtils.convertRectActiveArrayBaseToImageBase(petInfo.petDetectionInfo()[i].getDetectedRect(), pictureSize, sensorInfoActiveArraySize, cropRegion);
                calculatedPetDetectionInfo.add(i, new PetDetectionInfo(petInfo.petDetectionInfo()[i].getId(),
                        petInfo.petDetectionInfo()[i].getCategory(),
                        petInfo.petDetectionInfo()[i].getScore(),
                        petInfo.petDetectionInfo()[i].getDetectedRect()));
            }

            final int[] marshaledPetDetectionInfo = MarshalUtils.marshalPetDetectionInfo(calculatedPetDetectionInfo.toArray(new PetDetectionInfo[0]), petInfo.petVersion());
            if (null != marshaledPetDetectionInfo) {
                nativeCall(NATIVE_COMMAND_SET_PET_DETECTION_INFO, new Object[]{marshaledPetDetectionInfo});
            } else {
                CLog.i(getNodeTag(), "marshaledPetDetectionInfo is null");
            }
        }
    }

    protected void setPreviewInfo(@NonNull CaptureMetadata captureMetadata) {
        if (mNodeId != NodeId.NODE_SEC_V1_DUAL_BOKEH) {
            final byte[] previewInfo = SemCaptureResult.get(captureMetadata,
                    SemCaptureResult.CONTROL_BOKEH_DUAL_PREVIEW_INFO);
            if (null != previewInfo) {
                final DirectBuffer directBuffer = DirectBuffer.allocate(previewInfo.length);
                try {
                    directBuffer.put(previewInfo);
                    directBuffer.rewind();
                    nativeCall(NATIVE_COMMAND_SET_PREVIEW_INFO, directBuffer);
                } finally {
                    directBuffer.release();
                }
            } else {
                CLog.e(getNodeTag(), "setPreviewInfo: Preview info is null.");
            }
        }
    }

    protected void setSpecialSceneAeInfo(@NonNull CaptureMetadata captureMetadata) {
        if (mNodeId != NodeId.NODE_SEC_V1_DUAL_BOKEH) {
            final int[] specialSceneAeInfo = SemCaptureResult.get(captureMetadata, SemCaptureResult.STATISTICS_SPECIAL_SCENE_AE);
            if (null != specialSceneAeInfo) {
                CLog.i(getNodeTag(), "specialSceneAeInfo: data size = %d", specialSceneAeInfo.length);
                nativeCall(NATIVE_COMMAND_SET_SPECIAL_SCENE_AE_INFO, new Object[]{specialSceneAeInfo});
            }
        }
    }

    protected void setSubImageBuffer(@NonNull ImageBuffer picture) {
        final ImageInfo pictureImageInfo = picture.getImageInfo();
        final CaptureMetadata captureMetadata = pictureImageInfo.getCaptureMetadata();
        final Size pictureSize = Objects.requireNonNull(pictureImageInfo.getSize());
        final StrideInfo strideInfo = pictureImageInfo.getStrideInfo();

        final int bufferSize = pictureImageInfo.getFormat().getBufferSize(pictureSize, strideInfo);

        mSubPictureSize = pictureSize;
        mSubPictureImageInfo = ImageInfo.createAfterCopy(pictureImageInfo, info ->
                info.setCaptureMetadata(Objects.requireNonNull(captureMetadata)));

        if (null == mSubImageBuffer || mSubImageBuffer.capacity() != bufferSize) {
            Optional.ofNullable(mSubImageBuffer).ifPresent(DirectBuffer::release);
            mSubImageBuffer = DirectBuffer.allocate(bufferSize);
        }
        mSubImageBuffer.rewind();
        picture.get(mSubImageBuffer);
        mSubImageBuffer.rewind();
    }

    protected Integer getStreamType(@NonNull CaptureMetadata captureMetadata) {
        if (mCamCapability.getSamsungFeatureSensorCropAvailable()) {
            return SemCaptureResult.get(captureMetadata, SemCaptureResult.SENSOR_STREAM_TYPE);
        }
        return null;
    }

    protected int getDsMode(@NonNull CaptureMetadata captureMetadata) {
        final int dsHint = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_DYNAMIC_SHOT_HINT))
                .orElse(SemCameraMetadata.CONTROL_DS_MODE_SINGLE);
        return DynamicShotUtils.getDsMode(dsHint);
    }

    /**
     * {@inheritDoc}
     *
     * @throws InvalidOperationException {@inheritDoc}.
     */
    @Override
    synchronized protected void onDeinitialized() {
        if (null != mBokehResultBuffer) {
            mBokehResultBuffer.release();
            mBokehResultBuffer = null;
        }
        if (null != mMainImageBuffer) {
            mMainImageBuffer.release();
            mMainImageBuffer = null;
        }
        if (null != mSubImageBuffer) {
            mSubImageBuffer.release();
            mSubImageBuffer = null;
        }
        mSefNodeParamMap.clear();
        mMainFrameCount = 0;
        mMainCnt = 0;
        mSubCnt = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void release() {
        super.release();
    }
}
