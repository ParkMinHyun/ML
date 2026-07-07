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

package com.samsung.android.camera.core2.maker;

import static com.samsung.android.camera.core2.CamDeviceRequestOptions.PictureRequestType.FIRST_COMP;
import static com.samsung.android.camera.core2.CamDeviceRequestOptions.PictureRequestType.FIRST_RAW;
import static com.samsung.android.camera.core2.CamDeviceRequestOptions.PictureRequestType.FIRST_UN_COMP;
import static com.samsung.android.camera.core2.CamDeviceRequestOptions.PictureRequestType.SECOND_COMP;
import static com.samsung.android.camera.core2.CamDeviceRequestOptions.PictureRequestType.SECOND_UN_COMP;
import static com.samsung.android.camera.core2.callback.helper.CallbackHelper.BurstPictureCallbackHelper;
import static com.samsung.android.camera.core2.callback.helper.CallbackHelper.PictureCallbackHelper;
import static com.samsung.android.camera.core2.callback.helper.CallbackHelper.ThumbnailCallbackHelper;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicFormat.COMP;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicFormat.RAW;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicType.FIFTH;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicType.FIRST;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicType.FOURTH;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicType.SECOND;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicType.SIXTH;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicType.THIRD;
import static com.samsung.android.camera.core2.container.SessionConfig.BuilderConfig;
import static com.samsung.android.camera.core2.container.SessionConfig.ExtraPreviewSurfaceConfig;
import static com.samsung.android.camera.core2.container.SessionConfig.MirrorPreviewSurfaceConfig;
import static com.samsung.android.camera.core2.container.SessionConfig.PreviewSurfaceConfig;
import static com.samsung.android.camera.core2.container.SessionConfig.SurfaceConfigCollector;
import static com.samsung.android.camera.core2.exception.CamDeviceException.Type.ILLEGAL_ARGUMENT;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_DEPTH_SURFACE;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_FIRST_EXTRA_PREVIEW_SURFACE;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_FIRST_PRIVATE_EXTRA_PREVIEW_SURFACE;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_MAIN_PREVIEW_CALLBACK;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_MIRROR_PREVIEW_SURFACE;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_PREVIEW_SURFACE;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_PRIVATE_PREVIEW_SURFACE;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_SECOND_EXTRA_PREVIEW_SURFACE;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_SECOND_PRIVATE_EXTRA_PREVIEW_SURFACE;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.REPEATING_KEY_SUB_PREVIEW_CALLBACK;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.RepeatingKey;
import static com.samsung.android.camera.core2.maker.MakerRepeatingModeManager.RepeatingMode;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.os.Handler;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.CamCapability;
import com.samsung.android.camera.core2.CamDevice;
import com.samsung.android.camera.core2.CamDeviceRepeatingRequestCnt;
import com.samsung.android.camera.core2.CamDeviceRepeatingState;
import com.samsung.android.camera.core2.CamDeviceRequestOptions;
import com.samsung.android.camera.core2.CamDeviceRequestOptions.PictureRequestType;
import com.samsung.android.camera.core2.MakerInterface;
import com.samsung.android.camera.core2.MakerPrivateCommand;
import com.samsung.android.camera.core2.MakerPrivateKey;
import com.samsung.android.camera.core2.MakerPublicKey;
import com.samsung.android.camera.core2.PublicMetadata;
import com.samsung.android.camera.core2.apm.AdaptivePerformanceManager;
import com.samsung.android.camera.core2.apm.policy.CaptureAvailableApmPolicy;
import com.samsung.android.camera.core2.callback.MakerCallback;
import com.samsung.android.camera.core2.callback.MakerStateCallback;
import com.samsung.android.camera.core2.callback.PictureCallback;
import com.samsung.android.camera.core2.callback.PreviewCallback;
import com.samsung.android.camera.core2.callback.PreviewStateCallback;
import com.samsung.android.camera.core2.callback.RawPictureCallback;
import com.samsung.android.camera.core2.callback.ThumbnailCallback;
import com.samsung.android.camera.core2.callback.forwarder.MakerStateCallbackForwarder;
import com.samsung.android.camera.core2.callback.forwarder.PictureCallbackForwarder;
import com.samsung.android.camera.core2.callback.forwarder.RawPictureCallbackForwarder;
import com.samsung.android.camera.core2.callback.forwarder.ThumbnailCallbackForwarder;
import com.samsung.android.camera.core2.callback.helper.BufferCallbackForwarderHelper;
import com.samsung.android.camera.core2.callbackutil.BufferForwarder;
import com.samsung.android.camera.core2.container.CaptureExtraInfo;
import com.samsung.android.camera.core2.container.DeviceConfiguration;
import com.samsung.android.camera.core2.container.DynamicShotInfo;
import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.FrameRate;
import com.samsung.android.camera.core2.container.PicCbImgSizeConfig;
import com.samsung.android.camera.core2.container.PictureDataInfo;
import com.samsung.android.camera.core2.container.PictureStreamInfo;
import com.samsung.android.camera.core2.container.PreviewCbImgSizeConfig;
import com.samsung.android.camera.core2.container.SecStreamConfig;
import com.samsung.android.camera.core2.container.SensorPixelMode;
import com.samsung.android.camera.core2.container.SessionConfig;
import com.samsung.android.camera.core2.container.TargetPictureSizeInfo;
import com.samsung.android.camera.core2.exception.CamAccessException;
import com.samsung.android.camera.core2.exception.CamDeviceException;
import com.samsung.android.camera.core2.exception.InvalidOperationException;
import com.samsung.android.camera.core2.featureprovider.FeatureProvider;
import com.samsung.android.camera.core2.local.vendorkey.CaptureMetadata;
import com.samsung.android.camera.core2.local.vendorkey.SemCaptureRequest;
import com.samsung.android.camera.core2.local.vendorkey.SemCaptureResult;
import com.samsung.android.camera.core2.maker.MakerUtils.CamDeviceSessionState;
import com.samsung.android.camera.core2.processor.PictureProcessorManager;
import com.samsung.android.camera.core2.processor.request.ProcessRequest;
import com.samsung.android.camera.core2.util.ArrayUtils;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.ConditionChecker;
import com.samsung.android.camera.core2.util.DebugUtils;
import com.samsung.android.camera.core2.util.ExifUtils;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.ImageUtils;
import com.samsung.android.camera.core2.util.MemoryUtils;
import com.samsung.android.camera.core2.util.NativeUtils;
import com.samsung.android.camera.core2.util.SemImageFormat;
import com.samsung.android.camera.core2.util.SizeUtils;
import com.samsung.android.camera.core2.util.StreamConfigUtils;
import com.samsung.android.camera.core2.util.StringUtils;
import com.samsung.android.camera.watermark.Watermark;
import com.sec.android.app.TraceWrapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * <div class="camera_en">
 * It is the super class that including base/common implementation of PhotoMaker.
 * If SemCamera2.0's user calls
 * {@link #connectCamDevice(CamDevice, DeviceConfiguration, MakerStateCallback, Handler)}
 * /{@link #startPreviewRepeating()}/{@link #stopRepeating()}
 * /{@link MakerInterface#takePicture(DynamicShotInfo, CaptureExtraInfo)},
 * PhotoMaker receives previewData/pictureData via {@link CamDevice.PreviewCallback#onPreviewFrame(Image, CamCapability)} and
 * {@link CamDevice.PictureCallback#onPictureTaken(ImageBuffer, ExtraBundle, CamCapability, boolean)},
 * Processes that according to each PhotoMaker's characteristics and transmits to SemCamera2.0's user.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * PhotoMaker 의 기본 구현부, 공통부를 포함하는 Super class.
 * SemCamera2.0 User 가
 * {@link #connectCamDevice(CamDevice, DeviceConfiguration, MakerStateCallback, Handler)}
 * /{@link #startPreviewRepeating()}/{@link #stopRepeating()}
 * /{@link MakerInterface#takePicture(DynamicShotInfo, CaptureExtraInfo)} 등의 함수를 호출하면,
 * PhotoMaker 는 {@link CamDevice.PreviewCallback#onPreviewFrame(Image, CamCapability)} 와
 * {@link CamDevice.PictureCallback#onPictureTaken(ImageBuffer, ExtraBundle, CamCapability, boolean)}을 통해 previewData/pictureData 를 전달받고,
 * 이를 각 PhotoMaker 특성에 맞게 가공해서 SemCamera2.0 User 에게 전달한다.
 * </div>
 */
abstract class PhotoMakerBase extends MakerBase {

    protected static final int PREVIEW_BUFFER_FORWARDER_MAX_CONCURRENT = 2;
    protected static final BufferForwarder.ForwardMode PREVIEW_BUFFER_FORWARDER_MODE = BufferForwarder.ForwardMode.SKIP;
    protected static final int PRODUCE_PREVIEW_FRAME_TIMEOUT_MILLIS = 10;
    protected static final int PARALLEL_CAPTURE_MAX_COUNT = 50;

    protected static final int BURST_PICTURE_BUFFER_FORWARDER_MAX_CONCURRENT = 2;
    protected static final BufferForwarder.ForwardMode BURST_PICTURE_BUFFER_FORWARDER_MODE = BufferForwarder.ForwardMode.FULL;

    protected final MakerRepeatingModeManager mRepeatingModeManager = new PhotoMakerRepeatingModeManager(getMakerTag());

    protected TargetPictureSizeInfo mTargetPictureSizeInfo;

    protected PictureStreamInfo mThumbnailStreamInfo;
    protected PictureStreamInfo mPictureDepthStreamInfo;

    protected PictureCallback mPictureCallback;
    protected RawPictureCallback mRawPictureCallback;
    protected ThumbnailCallback mThumbnailCallback;

    protected Surface mFirstPrivateExtraPreviewSurface;
    protected Surface mSecondPrivateExtraPreviewSurface;
    // ANW retrieved from the Surface will be altered every time relayout the window
    protected Surface mPrivatePreviewSurface;

    protected boolean mNeedFusionHighRes;

    protected DeviceConfiguration.Parameters.ColorSpaceMode mColorSpaceMode;

    protected FrameRate mSubPreviewCbFrameRate = FrameRate.RATIO_MAX_PREVIEW_FPS;

    protected boolean mIsWatermarkEnable;
    protected Watermark.WatermarkType mWatermarkType = Watermark.WatermarkType.OVERLAY;

    private boolean mIsFirstPreviewProduceFrame;
    private boolean mIsFirstExtraProduceFrame;

    /**
     * <div class="camera_en">
     * The callback interface to receive pictureData, metadata from CamDevice.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * CamDevice 로 부터 pictureData 및 metadata 를 전달받기 위한 callback interface.
     * </div>
     */
    @SuppressWarnings("WeakerAccess")
    protected CamDevice.BurstPictureCallback mCamDeviceBurstPictureCallback
            = new CamDevice.BurstPictureCallback() {

        /**
         * <div class="camera_en">
         *     The callback function that will be called when receives the pictureData and metadata from CamDevice.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     CamDevice 로 부터 pictureData 와 metadata 를 전달 받을 때 호출된다.
         * </div>
         *
         * @param pictureData pictureData.
         * @param camCapability CamCapability.
         * @param hasThumbnailImage Flag to notify if there is thumbnailImage for this pictureData.
         */
        @Override
        public void onBurstPictureTaken(@NonNull ImageBuffer pictureData, @NonNull CamCapability camCapability, boolean hasThumbnailImage) {
            if (!hasThumbnailImage) {
                sendJpegThumbnail(pictureData);
            }
            BurstPictureCallbackHelper.onBurstPictureTaken(getMakerTag(), mBurstPictureCallbackForwarder, pictureData, mCamDevice);
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when burstRequest is applied.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     BurstRequest 가 반영 되었을때 호출되는 callback 함수.
         * </div>
         *
         * @param sequenceId sequenceId.
         *
         */
        @Override
        public void onBurstRequestApplied(int sequenceId) {
            BurstPictureCallbackHelper.onBurstPictureStarted(getMakerTag(), mBurstPictureCallbackForwarder, sequenceId, mCamDevice);
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when encounter error during BurstPicture Capture.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     BurstPicture Capture 도중 error 가 발생하는 경우 호출된다.
         * </div>
         *
         * @param failure captureFailure
         *
         */
        @Override
        public void onBurstRequestError(@NonNull CaptureFailure failure) {
            // CLog.e(getMakerTag(), "BurstPictureCallback onBurstRequestError - sequenceId %d", failure.getSequenceId());
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when burst capture is completed in CamDevice.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     CamDevice 에서 Burst 촬영이 완료 될 때 호출된다.
         * </div>
         *
         * @param sequenceId sequenceId.
         *
         */
        @Override
        public void onBurstRequestRemoved(int sequenceId) {
            BurstPictureCallbackHelper.onBurstPictureCompleted(getMakerTag(), mBurstPictureCallbackForwarder, sequenceId, mCamDevice);
        }
    };

    /**
     * <div class="camera_en">
     * The callback interface to receive pictureData and metadata for multi picture from CamDevice.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * CamDevice 로 부터 multi picture 를 위한 pictureData 및 metadata 를 전달받기 위한 callback interface.
     * </div>
     */
    protected CamDevice.MultiPictureCallback mCamDeviceMultiPictureCallback
            = new CamDevice.MultiPictureCallback() {
        @Override
        public void onPictureDepth(@Nullable ProcessRequest.Sequence<ImageBuffer> sequence, @NonNull ImageBuffer depthData, @NonNull CamCapability camCapability) { }

        @Override
        public void onError(@Nullable ProcessRequest.Sequence<ImageBuffer> sequence, @NonNull CaptureFailure failure, int index, int totalCount) { }

        @Override
        public void onPictureSequenceCompleted(int sequenceId, long frameNumber) { }

        @Override
        public void onPictureTaken(@Nullable ProcessRequest.Sequence<ImageBuffer> sequence,
                                   @NonNull ImageBuffer pictureData,
                                   @NonNull CamCapability camCapability,
                                   boolean hasThumbnailImage,
                                   int requestIndex,
                                   int requestListSize) {

        }

        @Override
        public void onShutter(int sequenceId, @Nullable Long timeStamp) { }

        @Override
        public void onCaptureAvailable(int sequenceId, @Nullable Long timeStamp) {

        }
    };

    /**
     * <div class="camera_en">
     * The callback interface to receive pictureData, metadata from CamDevice.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * CamDevice 로 부터 pictureData 및 metadata 를 전달받기 위한 callback interface.
     * </div>
     */
    protected CamDevice.PictureCallback mCamDevicePictureCallback
            = new CamDevice.PictureCallback() {
        @Override
        public void onError(@NonNull CaptureFailure failure) {
            CLog.i(getMakerTag(), "PictureCallback onError %d", failure.getReason());
            PictureCallbackHelper.onError(getMakerTag(), mPictureCallback, failure.getReason(), mCamDevice);
        }

        @Override
        public void onPictureSequenceCompleted(int sequenceId, long frameNumber) {
            CLog.i(getMakerTag(), "PictureCallback onPictureSequenceCompleted - sequenceId %d, frameNumber %d", sequenceId, frameNumber);
        }

        @Override
        public void onPictureTaken(@NonNull ImageBuffer pictureData,
                                   @NonNull ExtraBundle extraBundle,
                                   @NonNull CamCapability camCapability,
                                   boolean hasThumbnailImage) {
            CLog.i(getMakerTag(), "PictureCallback onPictureTaken - pictureData %s, hasThumbnailImage %b", pictureData, hasThumbnailImage);

            if (mPictureProcessLock.lockIfFlagEnabled()) {
                try {
                    final ImageInfo imageInfo = pictureData.getImageInfo();
                    if (SemImageFormat.isCompressedFormat(imageInfo.getFormat())) {
                        if (!hasThumbnailImage) {
                            sendThumbnailFromEncodedImage(pictureData);
                        }
                        sendPictureTakenCallback(getMakerTag(), mPictureCallback, pictureData, extraBundle);
                    } else {// can't be reached
                        CLog.e(getMakerTag(), "PictureCallback onPictureTaken fail - unsupported pictureFormat" + imageInfo.getFormat());
                    }
                } finally {
                    mPictureProcessLock.unlock();
                }
            } else {
                CLog.e(getMakerTag(), "PictureCallback onPictureTaken fail - pictureProcess is not enabled");
                PictureCallbackHelper.onError(getMakerTag(), mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
            }
        }

        @Override
        public void onShutter(int sequenceId, @Nullable Long timeStamp) {
            PictureCallbackHelper.onShutter(getMakerTag(), mPictureCallback, sequenceId, timeStamp, mCamDevice);
        }

        @Override
        public void onCaptureAvailable(int sequenceId, @Nullable Long timeStamp) {
            sendCaptureAvailableFromHAL(sequenceId, timeStamp);
        }
    };

    /**
     * <div class="camera_en">
     * The callback interface to receive pictureDepthData from CamDevice.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * CamDevice 로 부터 pictureDepthData 를 전달받기 위한 callback interface.
     * </div>
     */
    @SuppressWarnings("WeakerAccess")
    protected CamDevice.PictureDepthCallback mCamDevicePictureDepthCallback
            = new CamDevice.PictureDepthCallback() {

        /**
         * <div class="camera_en">
         *     The callback function that will be called when receives the pictureDepthData from CamDevice.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     CamDevice 로 부터 pictureDepthData 를 전달 받을 때 호출된다.
         * </div>
         *
         * @param depthData pictureDepthData.
         * @param camCapability CamCapability.
         */
        @Override
        public void onPictureDepth(@Nullable ProcessRequest.Sequence<ImageBuffer> sequence, @NonNull ImageBuffer depthData, @NonNull CamCapability camCapability) {

        }
    };

    /**
     * <div class="camera_en">
     * The callback interface to receive thumbnailData, metadata from CamDevice.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * CamDevice 로 부터 thumbnailData 및 metadata 를 전달받기 위한 callback interface.
     * </div>
     */
    @SuppressWarnings("WeakerAccess")
    protected CamDevice.ThumbnailCallback mCamDeviceThumbnailCallback
            = new CamDevice.ThumbnailCallback() {

        /**
         * <div class="camera_en">
         *     The callback function that will be called when transmits the thumbnailData.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     thumbnailData 를 전달하는 callback 함수.
         * </div>
         *
         * @param thumbnailData ThumbnailData.
         * @param camCapability CamCapability.
         */
        @Override
        public void onThumbnailTaken(@NonNull ImageBuffer thumbnailData, @NonNull CamCapability camCapability) {
            ThumbnailCallbackHelper.onThumbnailTaken(getMakerTag(), mThumbnailCallback, thumbnailData, mCamDevice);
        }
    };

    /**
     * <div class="camera_en">
     * The callback interface to receive PreviewState in CamDevice.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * CamDevice 의 PreviewState 를 전달받기 위한 callback interface.
     * </div>
     */
    private final CamDevice.PreviewStateCallback mCamDevicePreviewStateCallback
            = new CamDevice.PreviewStateCallback() {

        /**
         * <div class="camera_en">
         *     The callback function that will be called when receives TotalCaptureResult from CamDevice.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     TotalCaptureResult 를 전달 받을 때 호출되는 callback 함수.
         * </div>
         *
         * @param result TotalCaptureResult instance.
         * @param camCapability Capability of CamDevice.
         *
         */
        @Override
        public void onPreviewCaptureResult(@NonNull TotalCaptureResult result, @NonNull CamCapability camCapability) {
            mLatestRepeatingCaptureResult.set(result);
            onPreviewResult(result, camCapability);

            if (!camCapability.getSamsungFeatureRunningPhysicalIdSupportPartialResult()) {
                setRunningPhysicalId(camCapability, result);
            }
            setDynamicFovStreamType(camCapability, result);

            final Long photoMakerBaseTimeStamp = result.get(CaptureResult.SENSOR_TIMESTAMP);

            mMakerCallbackManager.sendCallbacks(Arrays.asList(
                    MakerCallbackType.LENS_SUGGESTION_CALLBACK,
                    MakerCallbackType.GENERAL_LENS_TYPE_CALLBACK,
                    MakerCallbackType.ADAPTIVE_LENS_INFO_CALLBACK,
                    MakerCallbackType.DEPTH_INFO_CALLBACK,
                    MakerCallbackType.OBJECT_TRACKING_INFO_CALLBACK,
                    MakerCallbackType.EXPOSURE_TIME_CALLBACK,
                    MakerCallbackType.SENSOR_SENSITIVITY_CALLBACK,
                    MakerCallbackType.LENS_INFO_CALLBACK,
                    MakerCallbackType.LENS_DIRTY_DETECT_CALLBACK,
                    MakerCallbackType.BRIGHTNESS_VALUE_CALLBACK,
                    MakerCallbackType.EV_COMPENSATION_VALUE_CALLBACK,
                    MakerCallbackType.FACE_DETECTION_INFO_CALLBACK,
                    MakerCallbackType.UNIHAL_BEAUTY_FACE_DETECTION_CALLBACK,
                    MakerCallbackType.UNIHAL_DOCUMENT_DETECTION_CALLBACK,
                    MakerCallbackType.UNIHAL_QR_CODE_DETECTION_CALLBACK,
                    MakerCallbackType.PET_DETECTION_INFO_CALLBACK,
                    MakerCallbackType.LIVE_HDR_STATE_CALLBACK,
                    MakerCallbackType.MOTION_PHOTO_VDIS_INFO_CALLBACK
            ), mCamDevice, photoMakerBaseTimeStamp, result);

            mMakerCallbackManager.sendSunDetectionInfo(mCamDevice, result);
            if (!FeatureProvider.isSuperNightIntegratedPhotoModeSupport()) {
                mMakerCallbackManager.sendCallback(MakerCallbackType.NIGHT_SCENE_INFO_CALLBACK, mCamDevice, photoMakerBaseTimeStamp, result);
            }
            if (!camCapability.getSamsungFeatureDsInfoSupportPartialResult()) {
                mMakerCallbackManager.sendDynamicShotInfoCallback(
                        mCamDevice,
                        photoMakerBaseTimeStamp,
                        result,
                        getDsCondition(result),
                        getDsExtraInfo(result, camCapability),
                        mRunningPhysicalId,
                        mRunningSubPhysicalId);
                mMakerCallbackManager.sendCallback(MakerCallbackType.DYNAMIC_SHOT_CAPTURE_DURATION_CALLBACK, mCamDevice, photoMakerBaseTimeStamp, result);
            }

            // 3A State and related result could forward in this unless partial result is supported.
            if (!usePartialCaptureResult()) {
                mMakerCallbackManager.sendCallbacks(Arrays.asList(
                        MakerCallbackType.AE_INFO_CALLBACK,
                        MakerCallbackType.AF_INFO_CALLBACK,
                        MakerCallbackType.TOUCH_AE_STATE_CALLBACK,
                        MakerCallbackType.DOF_MULTI_INFO_CALLBACK,
                        MakerCallbackType.STILL_CAPTURE_PROGRESS_CALLBACK
                ), mCamDevice, photoMakerBaseTimeStamp, result);
            }

            mMakerCallbackManager.sendCallbacks(Arrays.asList(
                    MakerCallbackType.LIGHT_CONDITION_CALLBACK,
                    MakerCallbackType.BURST_SHOT_FPS_CALLBACK,
                    MakerCallbackType.BOKEH_INFO_CALLBACK,
                    MakerCallbackType.COLOR_TEMPERATURE_CALLBACK,
                    MakerCallbackType.COMPOSITION_GUIDE_INFO_CALLBACK,
                    MakerCallbackType.SCENE_DETECTION_INFO_CALLBACK,
                    MakerCallbackType.ZOOM_LOCK_STATE_CALLBACK,
                    MakerCallbackType.HAND_GESTURE_DETECTION_INFO_CALLBACK,
                    MakerCallbackType.CAMERA_RUNNING_DEBUG_INFO_CALLBACK,
                    MakerCallbackType.ACTION_SHOT_RESULT_CALLBACK,
                    MakerCallbackType.OBJECT_DETECTION_INFO_CALLBACK,
                    MakerCallbackType.TEXT_DETECTION_INFO_CALLBACK,
                    MakerCallbackType.ZOOM_RATIO_SUGGESTION_CALLBACK,
                    MakerCallbackType.STEREO_STATE_CALLBACK,
                    MakerCallbackType.SMART_TRACKING_AF_INFO_CALLBACK,
                    MakerCallbackType.PREVIEW_DATASPACE_RANGE_TYPE_CALLBACK
            ), mCamDevice, photoMakerBaseTimeStamp, result);
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when receives Partial CaptureResult from CamDevice.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     Partial CaptureResult 를 전달 받을 때 호출되는 callback 함수.
         * </div>
         *
         * @param partialResult Partial CaptureResult instance.
         * @param camCapability Capability of CamDevice.
         *
         */
        @Override
        public void onPreviewPartialCaptureResult(@NonNull CaptureResult partialResult, @NonNull CamCapability camCapability) {
            final Long timeStamp = partialResult.get(CaptureResult.SENSOR_TIMESTAMP);

            if (camCapability.getSamsungFeatureRunningPhysicalIdSupportPartialResult()) {
                setRunningPhysicalId(camCapability, partialResult);
            }
            if (FeatureProvider.isSuperNightIntegratedPhotoModeSupport()) {
                mMakerCallbackManager.sendCallback(MakerCallbackType.NIGHT_SCENE_INFO_CALLBACK, mCamDevice, timeStamp, partialResult);
            }
            if (camCapability.getSamsungFeatureDsInfoSupportPartialResult()) {
                mMakerCallbackManager.sendDynamicShotInfoCallback(
                        mCamDevice,
                        timeStamp,
                        partialResult,
                        getDsCondition(partialResult),
                        getDsExtraInfo(partialResult, camCapability),
                        mRunningPhysicalId,
                        mRunningSubPhysicalId);
                mMakerCallbackManager.sendCallback(MakerCallbackType.DYNAMIC_SHOT_CAPTURE_DURATION_CALLBACK, mCamDevice, timeStamp, partialResult);
            }

            // 3A State and related result.
            if (usePartialCaptureResult()) {
                mMakerCallbackManager.sendCallback(MakerCallbackType.AE_INFO_CALLBACK, mCamDevice, timeStamp, partialResult);
                mMakerCallbackManager.sendCallback(MakerCallbackType.AF_INFO_CALLBACK, mCamDevice, timeStamp, partialResult);
                mMakerCallbackManager.sendCallback(MakerCallbackType.STILL_CAPTURE_PROGRESS_CALLBACK, mCamDevice, timeStamp, partialResult);
                mMakerCallbackManager.sendCallback(MakerCallbackType.TOUCH_AE_STATE_CALLBACK, mCamDevice, timeStamp, partialResult);
                mMakerCallbackManager.sendCallback(MakerCallbackType.DOF_MULTI_INFO_CALLBACK, mCamDevice, timeStamp, partialResult);
            }
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when PreviewRequest is applied.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     PreviewRequest 가 반영 되었을때 호출되는 callback 함수.
         * </div>
         *
         * @param sequenceId sequenceId.
         */
        @Override
        public void onPreviewRequestApplied(int sequenceId) {
            CLog.i(getMakerTag(), "onPreviewRequestApplied - sequenceId " + sequenceId);
            final CamDevice camDevice = mCamDevice;
            if (null != camDevice) {
                Optional.ofNullable(mMakerCallbackManager.getCallback(MakerCallbackType.PREVIEW_STATE_CALLBACK))
                        .map(PreviewStateCallback.class::cast)
                        .ifPresent(callback -> callback.onPreviewRequestApplied(sequenceId, camDevice));
            }
            onPrevRepeatingRequestApplied(sequenceId);
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when encounter error during Preview Repeating.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     Preview Repeating 도중 error 가 발생하는 경우 호출된다.
         * </div>
         *
         * @param failure captureFailure.
         *
         */
        @Override
        public void onPreviewRequestError(@NonNull CaptureFailure failure) {
            if (!isIntentionalRequestError()) {
                CLog.w(getMakerTag(), "onPreviewRequestError - sequenceId " + failure.getSequenceId());
            }
            final CamDevice camDevice = mCamDevice;
            if (null != camDevice) {
                Optional.ofNullable(mMakerCallbackManager.getCallback(MakerCallbackType.PREVIEW_STATE_CALLBACK))
                        .map(PreviewStateCallback.class::cast)
                        .ifPresent(callback -> callback.onPreviewRequestError(failure.getSequenceId(), camDevice));
            }
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when PreviewRequest is removed.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     PreviewRequest 가 해제 되었을때 호출되는 callback 함수.
         * </div>
         *
         * @param sequenceId sequenceId.
         *
         */
        @Override
        public void onPreviewRequestRemoved(int sequenceId) {
            CLog.v(getMakerTag(), "onPreviewRequestRemoved - sequenceId " + sequenceId);
            final CamDevice camDevice = mCamDevice;
            if (null != camDevice) {
                Optional.ofNullable(mMakerCallbackManager.getCallback(MakerCallbackType.PREVIEW_STATE_CALLBACK))
                        .map(PreviewStateCallback.class::cast)
                        .ifPresent(callback -> callback.onPreviewRequestRemoved(sequenceId, camDevice));
            }
        }
    };

    /**
     * <div class="camera_en">
     * The callback interface to receive  {@link CameraCaptureSession}'s state in CamDevice.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * CamDevice 의 {@link CameraCaptureSession} 의 상태를 전달받기 위한 callback interface.
     * </div>
     */
    protected CamDevice.SessionStateCallback mCamDeviceSessionStateCallback
            = new CamDevice.SessionStateCallback() {

        /**
         * <div class="camera_en">
         *     The callback function that will be called {@link android.hardware.camera2.CameraCaptureSession} in CamDevice is not configured well.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     CamDevice 의 {@link android.hardware.camera2.CameraCaptureSession} 의 생성이 실패하였을 때 호출된다.
         * </div>
         */
        @Override
        public void onConfigureFailed() {
            synchronized (PhotoMakerBase.this) {
                CLog.i(getMakerTag(), "onConfigureFailed E - sessionStateCallback(%s)", Integer.toHexString(System.identityHashCode(this)));
                try {
                    joinInitializeMakerThread();

                    setCamDeviceSessionState(CamDeviceSessionState.CONNECT_FAILED);

                    final CamCapability camCapability = mCamDevice.getCamCapability();

                    TraceWrapper.traceBegin(getMakerTag() + "-releaseMaker");
                    releaseMaker(camCapability);
                    TraceWrapper.traceEnd();

                    if (camCapability.getSamsungFeatureDynamicShotInfoAvailable()) {
                        PictureProcessorManager.getInstance().resumePpp();
                    }

                    mCamDevice = null;
                    mMakerStateCallback = null;
                    mMakerPicStreamConfig.clear();
                } catch (IllegalStateException e) {
                    CLog.e(getMakerTag(), "onConfigureFailed - " + e);
                }
                CLog.i(getMakerTag(), "onConfigureFailed X - sessionStateCallback(%s)", Integer.toHexString(System.identityHashCode(this)));
            }
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when {@link android.hardware.camera2.CameraCaptureSession} in CamDevice is configured normally.
         *     Create and Set PreviewRequestBuilder at CamDevice.
         *     Create and Set PictureRequestBuilder at CamDevice.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     CamDevice 의 {@link android.hardware.camera2.CameraCaptureSession} 의 생성이 정상적으로 완료되었을 때 호출된다.
         *     CamDevice 에 PreviewRequestBuilder 를 생성/설정한다.
         *     CamDevice 에 PictureRequestBuilder 를 생성/설정한다.
         * </div>
         */
        @Override
        public void onConfigured() {
            synchronized (PhotoMakerBase.this) {
                CLog.i(getMakerTag(), "[CAMFWKPI] onConfigured E - sessionStateCallback(%s)", Integer.toHexString(System.identityHashCode(this)));
                final long startTime = System.currentTimeMillis();

                try {
                    getCamDeviceSessionState().checkTransitState(CamDeviceSessionState.CONNECTED);

                    mCamDevice.setMainPreviewCallback(mCamDeviceMainPreviewCallback);
                    mCamDevice.setSubPreviewCallback(mCamDeviceSubPreviewCallback);
                    mCamDevice.setPictureCallback(mCamDevicePictureCallback);
                    mCamDevice.setMultiPictureCallback(mCamDeviceMultiPictureCallback);
                    mCamDevice.setThumbnailCallback(mCamDeviceThumbnailCallback);
                    mCamDevice.setPictureDepthCallback(mCamDevicePictureDepthCallback);
                    mCamDevice.setBurstPictureCallback(mCamDeviceBurstPictureCallback);

                    final List<Size> jpegAvailableThumbnailSizeList = mCamDevice.getCamCapability().getJpegAvailableThumbnailSizes();
                    final Size thumbnailSize = Optional.ofNullable(mMakerPicStreamConfig.getSize(FIRST_COMP))
                            .map(firstCompPictureSize -> SizeUtils.getNearestSizeByRatio(firstCompPictureSize, jpegAvailableThumbnailSizeList))
                            .orElseGet(() -> new Size(0, 0));

                    CLog.i(getMakerTag(), "Jpeg thumbnail size: " + thumbnailSize);
                    SemCaptureRequest.set(mPictureRequestBuilderMap, mCamDevice.getId(), CaptureRequest.JPEG_THUMBNAIL_SIZE, thumbnailSize);

                    joinInitializeMakerThread();

                    mIsFirstPreviewProduceFrame = true;
                    mIsFirstExtraProduceFrame = true;

                    if (mCamDevice.getCamCapability().getSamsungFeatureDynamicShotInfoAvailable()) {
                        PictureProcessorManager.getInstance().resumePpp();
                    }

                    setCamDeviceSessionState(CamDeviceSessionState.CONNECTED);
                } catch (IllegalStateException e) {
                    CLog.e(getMakerTag(), "onConfigured - " + e);
                }
                mEnablePppLogging = DebugUtils.isDebugModeEnabled() && DebugUtils.isPppLoggingEnabled();
                CLog.i(getMakerTag(), "[CAMFWKPI] onConfigured X - sessionStateCallback(%s) - %d ms",
                        Integer.toHexString(System.identityHashCode(this)), System.currentTimeMillis() - startTime);
            }
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when CamDevice is closed.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     CamDevice 가 종료 되었을 때 호출된다.
         * </div>
         */
        @Override
        public void onDeviceClosed() {
            synchronized (PhotoMakerBase.this) {
                CLog.i(getMakerTag(), "onDeviceClosed E - sessionStateCallback(%s)", Integer.toHexString(System.identityHashCode(this)));
                try {
                    joinInitializeMakerThread();

                    setCamDeviceSessionState(CamDeviceSessionState.DEVICE_CLOSED);

                    TraceWrapper.traceBegin(getMakerTag() + "-releaseMaker");
                    releaseMaker(mCamDevice.getCamCapability());
                    TraceWrapper.traceEnd();

                    BufferCallbackForwarderHelper.releaseBufferForwarder(mMainPreviewCallbackForwarder);
                    BufferCallbackForwarderHelper.releaseBufferForwarder(mSubPreviewCallbackForwarder);
                    BufferCallbackForwarderHelper.releaseBufferForwarder(mBurstPictureCallbackForwarder);

                    mCamDevice = null;
                    mMakerStateCallback = null;
                    mMakerPicStreamConfig.clear();
                } catch (IllegalStateException e) {
                    CLog.e(getMakerTag(), "onDeviceClosed - " + e);
                }
                CLog.i(getMakerTag(), "onDeviceClosed X - sessionStateCallback(%s)", Integer.toHexString(System.identityHashCode(this)));
            }
        }

        /**
         * <div class="camera_en">
         *     The callback function that will be called when {@link android.hardware.camera2.CameraCaptureSession} in CamDevice is re-created by someone.
         *     To use CamDevice again after this function's call, has to create CaptureSession again.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         *     CamDevice 의 {@link android.hardware.camera2.CameraCaptureSession} 이 누군가에 의해 다시 생성되었을 때 호출된다.
         *     이 callback 을 받은 이후에 CamDevice 를 사용하기 위해서는 CaptureSession 을 다시 생성해야 한다.
         * </div>
         */
        @Override
        public void onDisconnected() {
            synchronized (PhotoMakerBase.this) {
                CLog.i(getMakerTag(), "onDisconnected E - sessionStateCallback(%s)", Integer.toHexString(System.identityHashCode(this)));
                try {
                    joinInitializeMakerThread();

                    setCamDeviceSessionState(CamDeviceSessionState.DISCONNECTED);

                    // if mCamDeviceSessionState was RECONNECTING, state would not be changed to DISCONNECTED.
                    if (getCamDeviceSessionState() == CamDeviceSessionState.DISCONNECTED) {
                        TraceWrapper.traceBegin(getMakerTag() + "-releaseMaker");
                        releaseMaker(mCamDevice.getCamCapability());
                        TraceWrapper.traceEnd();

                        BufferCallbackForwarderHelper.releaseBufferForwarder(mMainPreviewCallbackForwarder);
                        BufferCallbackForwarderHelper.releaseBufferForwarder(mSubPreviewCallbackForwarder);
                        BufferCallbackForwarderHelper.releaseBufferForwarder(mBurstPictureCallbackForwarder);

                        mCamDevice = null;
                        mMakerStateCallback = null;
                        mMakerPicStreamConfig.clear();
                    }
                } catch (IllegalStateException e) {
                    CLog.e(getMakerTag(), "onDisconnected - " + e);
                }
                CLog.i(getMakerTag(), "onDisconnected X - sessionStateCallback(%s)", Integer.toHexString(System.identityHashCode(this)));
            }
        }

        /**
         * <div class="camera_en">
         * The callback function that will be sent to the user that {@link android.hardware.camera2.CameraCaptureSession} in CamDevice has no more requests to process.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * CamDevice의 {@link android.hardware.camera2.CameraCaptureSession}이 처리할 더 이상의 request가 없음을 사용자에게 보낸다.
         * {@link android.hardware.camera2.CameraCaptureSession}의 abortCaptures를 실행 후, onReady() callback을 받은 이 후에 CamDevice의 startPreviewRepeating이 가능하다.
         * </div>
         */
        @Override
        public void onReady() {
            synchronized (PhotoMakerBase.this) {
                CLog.i(getMakerTag(), "onReady E - sessionStateCallback(%s)", Integer.toHexString(System.identityHashCode(this)));
                try {
                    joinInitializeMakerThread();
                    Optional.ofNullable(mMakerStateCallback).ifPresent(callback -> callback.onCamDeviceReady(PhotoMakerBase.this, mCamDevice));
                } catch (IllegalStateException e) {
                    CLog.e(getMakerTag(), "onReady - " + e);
                }
                CLog.i(getMakerTag(), "onReady X - sessionStateCallback(%s)", Integer.toHexString(System.identityHashCode(this)));
            }
        }

        /**
         * <div class="camera_en">
         * The callback function that will be called when the buffer pre-allocation for an output Surface is complete.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * {@link CameraCaptureSession} 에 등록된 출력 Surface 의 pre-allocation 이 완료되었을 때 호출 된다.
         * </div>
         *
         * @param surface Surface that is wanted to prepare.
         */
        @Override
        public void onSurfacePrepared(@NonNull Surface surface) {

        }

        @NonNull
        @Override
        public String toString() {
            return "@" + Integer.toHexString(System.identityHashCode(this));
        }
    };

    /**
     * <div class="camera_en">
     * Constructor of PhotoMakerBase class.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PhotoMakerBase class 의 생성자.
     * </div>
     *
     * @param keyClass key class of maker.
     * @param context  application context.
     * @param handler  event callback handler.
     */
    protected PhotoMakerBase(@NonNull Class<?> keyClass, @NonNull Context context, @Nullable Handler handler) {
        super(keyClass, context, handler);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  {@inheritDoc}
     * @throws IllegalStateException     {@inheritDoc}
     * @throws InvalidOperationException {@inheritDoc}
     * @throws CamAccessException        {@inheritDoc}
     */
    @Override
    synchronized public void addMainPreviewSurface(@NonNull Surface mainPreviewSurface) throws CamAccessException {
        CLog.v(getMakerTag(), "addMainPreviewSurface - %s", mainPreviewSurface);

        ConditionChecker.checkNotNull(mainPreviewSurface, "mainPreviewSurface");

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        final Size previewSurfaceSize;
        try {
            previewSurfaceSize = NativeUtils.getSurfaceSize(mainPreviewSurface);
        } catch (NativeUtils.BufferQueueAbandonedException e) {
            throw new InvalidOperationException("getSurfaceSize for mainPreviewSurface fail", e);
        }

        if (!Objects.equals(getMainPreviewSurfaceSize(), previewSurfaceSize)) {
            throw new IllegalArgumentException(String.format(Locale.UK,
                    "previewSurfaceSize %s is not equal with previous size %s getting in connectCamDevice", previewSurfaceSize, getMainPreviewSurfaceSize()));
        }

        try {
            mCamDevice.addMainPreviewSurface(mainPreviewSurface);
        } catch (CamDeviceException e) {
            if (e.getType() == ILLEGAL_ARGUMENT) {
                throw new IllegalArgumentException("mainPreviewSurface is invalid - " + e);
            } else {
                throw new InvalidOperationException("addMainPreviewSurface fail", e);
            }
        }

        mPrivatePreviewSurface = createPrivatePreviewSurface(mainPreviewSurface, mCamDevice.getCamCapability());
        setMainPreviewSurface(mainPreviewSurface);
        if (mPreviewUpdateByHal) {
            enableRepeatingKey(REPEATING_KEY_PREVIEW_SURFACE, true);
        } else {
            enableRepeatingKey(REPEATING_KEY_PRIVATE_PREVIEW_SURFACE, true);
        }
    }

    /**
     * <div class="camera_en">
     * prepare surface configuration
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * surface 구성을 위한 준비를 한다.
     * </div>
     *
     * @param deviceConfiguration deviceConfiguration
     */
    protected void prepareSurfaceConfig(@NonNull DeviceConfiguration deviceConfiguration) {
        setMainPreviewSurface(deviceConfiguration.getMainPreviewSurface());
        setMainPreviewSurfaceOption(deviceConfiguration.getMainPreviewSurfaceUsageType());
        setMainPreviewSurfaceSize(deviceConfiguration);
        setFirstExtraPreviewSurface(deviceConfiguration.getFirstExtraPreviewSurface());
        setSecondExtraPreviewSurface(deviceConfiguration.getSecondExtraPreviewSurface());
        setMirrorPreviewSurface(deviceConfiguration.getMirrorPreviewSurface());
        setDepthSurface(deviceConfiguration.getDepthSurface());
    }

    /**
     * <div class="camera_en">
     * prepare preview callback stream.
     * The supporting Photo Maker about preview callback must be implemented after overriding.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * preview callback stream 구성을 위한 준비를 한다.
     * preview callback 지원하는 Photo Maker 가 overriding 후 구현 해야 한다.
     * </div>
     *
     * @param camCapability camCapability
     * @param deviceConfiguration deviceConfiguration
     */
    protected void preparePreviewCbStreamConfig(@NonNull CamCapability camCapability, @NonNull DeviceConfiguration deviceConfiguration) {
        CLog.i(getMakerTag(), "This Photo Maker does not require previewCB");
    }

    /**
     * <div class="camera_en">
     * prepare main previewCbStreamInfo.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * main previewCbStreamInfo 구성을 위한 준비를 한다.
     * </div>
     *
     * @param deviceConfiguration deviceConfiguration
     */
    protected void prepareMainPreviewCbStreamConfig(@NonNull DeviceConfiguration deviceConfiguration) {
        final PreviewCbImgSizeConfig mainPreviewCbImgSizeConfig = deviceConfiguration.getMainPreviewCbImgSizeConfig();
        final Size mainPreviewCbImgSize = (mPreviewUpdateByHal && null != mainPreviewCbImgSizeConfig)
                ? mainPreviewCbImgSizeConfig.size()
                : getMainPreviewSurfaceSize();

        mMainPreviewCbStreamInfo = null;
        if (null != mainPreviewCbImgSize) {
            mMainPreviewCbStreamInfo = new PictureStreamInfo(
                    SemImageFormat.YUV_420_888,
                    mainPreviewCbImgSize,
                    SensorPixelMode.MODE_DEFAULT,
                    /*cameraId*/null,
                    CamDevice.STREAM_OPTION_PREVIEW,
                    /*usePhysicalStream*/false);
        }
    }

    /**
     * <div class="camera_en">
     * prepare sub previewCbStreamInfo.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * sub previewCbStreamInfo 구성을 위한 준비를 한다.
     * </div>
     *
     * @param camCapability       camCapability
     * @param deviceConfiguration deviceConfiguration
     */
    protected void prepareSubPreviewCbStreamConfig(@NonNull CamCapability camCapability, @NonNull DeviceConfiguration deviceConfiguration) {
        mSubPreviewCbStreamInfo = null;

        if (!camCapability.getSamsungFeatureSubPreviewCbAvailable()) {
            CLog.i(getMakerTag(), "prepareSubPreviewCbStreamConfig - SamsungFeatureSubPreviewCbAvailable is false");
            return;
        }

        final PreviewCbImgSizeConfig subPreviewCbImgSizeConfig = deviceConfiguration.getSubPreviewCbImgSizeConfig();
        final Size subPreviewCbImgSize = (null != subPreviewCbImgSizeConfig)
                ? subPreviewCbImgSizeConfig.size()
                : getMainPreviewSurfaceSize();

        if (null != subPreviewCbImgSize) {
            mSubPreviewCbStreamInfo = new PictureStreamInfo(
                    SemImageFormat.YUV_420_888,
                    subPreviewCbImgSize,
                    SensorPixelMode.MODE_DEFAULT,
                    /*cameraId*/null,
                    CamDevice.STREAM_OPTION_SUB | CamDevice.STREAM_OPTION_PREVIEW,
                    /*usePhysicalStream*/false);
        }
    }

    /**
     * <div class="camera_en">
     * prepare picture stream configuration.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * picture stream 구성을 위한 준비를 한다.
     * </div>
     *
     * @param camCapability       camCapability
     * @param deviceConfiguration deviceConfiguration
     */
    @CallSuper
    protected void preparePictureStreamConfig(@NonNull CamCapability camCapability, @NonNull DeviceConfiguration deviceConfiguration) {
        mMakerPicStreamConfig.clear();
        mPictureDepthStreamInfo = null;
        mThumbnailStreamInfo = null;
    }

    /**
     * <div class="camera_en">
     * prepare FirstPicStreamConfig configuration
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * FirstPicStreamConfig 를 구성하기 위한 준비를 한다.
     * </div>
     *
     * @param camCapability       camCapability
     * @param deviceConfiguration deviceConfiguration
     */
    protected void prepareFirstPicStreamConfig(@NonNull CamCapability camCapability, @NonNull DeviceConfiguration deviceConfiguration) {
        final PicCbImgSizeConfig firstPicCbImgSizeConfig = deviceConfiguration.getFirstPicCbImgSizeConfig();
        if (null != firstPicCbImgSizeConfig) {
            mMakerPicStreamConfig.put(FIRST_COMP, new PictureStreamInfo(
                    SemImageFormat.JPEG,
                    firstPicCbImgSizeConfig,
                    CamDevice.STREAM_OPTION_PICTURE,
                    /*usePhysicalStream*/false
            ));
            mMakerPicStreamConfig.put(FIRST_UN_COMP, new PictureStreamInfo(
                    SemImageFormat.YUV_420_888,
                    firstPicCbImgSizeConfig,
                    CamDevice.STREAM_OPTION_PICTURE,
                    /*usePhysicalStream*/false
            ));
        }
    }

    /**
     * <div class="camera_en">
     * prepare SecondPicStreamConfig configuration.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * SecondPicStreamConfig 를 구성하기 위한 준비를 한다.
     * </div>
     *
     * @param camCapability       camCapability
     * @param deviceConfiguration deviceConfiguration
     */
    protected void prepareSecondPicStreamConfig(@NonNull CamCapability camCapability, @NonNull DeviceConfiguration deviceConfiguration) {
        final PicCbImgSizeConfig secondPicCbImgSizeConfig = deviceConfiguration.getSecondPicCbImgSizeConfig();
        if (null != secondPicCbImgSizeConfig) {
            final String cameraId = secondPicCbImgSizeConfig.getCameraId();
            final int streamOption = getSecondPicStreamOption(camCapability, cameraId);

            mMakerPicStreamConfig.put(SECOND_COMP, new PictureStreamInfo(
                    SemImageFormat.JPEG,
                    secondPicCbImgSizeConfig,
                    streamOption,
                    /*usePhysicalStream*/false
            ));
            mMakerPicStreamConfig.put(SECOND_UN_COMP, new PictureStreamInfo(
                    SemImageFormat.YUV_420_888,
                    secondPicCbImgSizeConfig,
                    streamOption,
                    /*usePhysicalStream*/false
            ));
        }
    }

    private int getSecondPicStreamOption(@NonNull CamCapability camCapability, @Nullable String cameraId) {
        final int lensFacing = Objects.requireNonNull(camCapability.getLensFacing());

        if (lensFacing == PublicMetadata.LENS_FACING_FRONT && camCapability.getSamsungFeatureDynamicFovAvailable()) {
            return CamDevice.STREAM_OPTION_PICTURE | CamDevice.STREAM_OPTION_SIBLING;
        } else if (null != cameraId) {
            return CamDevice.STREAM_OPTION_PICTURE | (Integer.parseInt(cameraId) << CamDevice.STREAM_OPTION_CAMERA_ID_BIT_SHIFT_CNT);
        }
        return CamDevice.STREAM_OPTION_PICTURE;
    }

    /**
     * <div class="camera_en">
     * prepare RawPicStreamConfig configuration
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * RawPicStreamConfig 를 구성하기 위한 준비를 한다.
     * </div>
     *
     * @param camCapability       camCapability
     * @param deviceConfiguration deviceConfiguration
     */
    protected void prepareRawPicStreamConfig(@NonNull CamCapability camCapability, @NonNull DeviceConfiguration deviceConfiguration) {
        final Size rawPictureSize;
        if (camCapability.getSamsungFeatureMaxRawSizeOnly()) {
            rawPictureSize = camCapability.getScalerAvailableRawPictureSizes().get(0);
        } else {
            final Size baseSize = deviceConfiguration.getFirstPicCbImgSizeConfig().getSize();
            final int streamType = deviceConfiguration.getParameters().getStreamType().getValue();
            final List<Size> availableJpegPictureSizeList = camCapability.getSamsungScalerAvailableJpegPictureSizes(streamType);
            rawPictureSize = SizeUtils.getMaximumSizeByRatio(baseSize, availableJpegPictureSizeList);
        }

        if (null != rawPictureSize) {
            mMakerPicStreamConfig.put(FIRST_RAW, new PictureStreamInfo(
                    SemImageFormat.RAW_SENSOR,
                    rawPictureSize,
                    SensorPixelMode.MODE_DEFAULT,
                    camCapability.getCameraId(),
                    CamDevice.STREAM_OPTION_PICTURE,
                    /*usePhysicalStream*/false
            ));
        }
    }

    /**
     * <div class="camera_en">
     * Set RawPicStreamConfig.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * RawPicStreamConfig 를 설정한다.
     * </div>
     *
     * @param camCapability       camCapability
     * @param rawStreamConfigList rawStreamConfigList
     */
    protected final void setRawPicStreamConfig(@NonNull CamCapability camCapability, @NonNull List<SecStreamConfig> rawStreamConfigList) {
        if (camCapability.getSamsungFeatureSingleCamSupportMultiRawStream()) {
            final Size firstCompPictureSize = Objects.requireNonNull(mMakerPicStreamConfig.getSize(FIRST_COMP));
            final SecStreamConfig rawStreamConfig = StreamConfigUtils.getFirstOverFitRawStreamConfig(rawStreamConfigList, firstCompPictureSize);
            if (null != rawStreamConfig) {
                mMakerPicStreamConfig.put(FIRST_RAW, new PictureStreamInfo(rawStreamConfig, CamDevice.STREAM_OPTION_PICTURE, /*usePhysicalStream*/false));
            }
            return;
        }

        final List<PictureRequestType> rawPictureRequestTypeList = PictureRequestType.getPictureRequestTypeList(RAW);
        final int rawStreamSize = Math.min(rawPictureRequestTypeList.size(), rawStreamConfigList.size());
        for (int index = 0; index < rawStreamSize; index++) {
            final SecStreamConfig rawStreamConfig = rawStreamConfigList.get(index);
            final int deviceId = rawStreamConfig.deviceId();
            final int rawStreamOption = (index == 0)
                    ? CamDevice.STREAM_OPTION_PICTURE
                    : CamDevice.STREAM_OPTION_PICTURE | (deviceId << CamDevice.STREAM_OPTION_CAMERA_ID_BIT_SHIFT_CNT);

            final PictureRequestType rawPictureRequestType = rawPictureRequestTypeList.get(index);
            mMakerPicStreamConfig.put(rawPictureRequestType, new PictureStreamInfo(rawStreamConfig, rawStreamOption, /*usePhysicalStream*/false));
        }
    }

    /**
     * <div class="camera_en">
     * prepare PictureDepthStream configuration
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PictureDepthStream을 구성하기 위한 준비를 한다.
     * </div>
     *
     * @param camCapability camCapability
     */
    protected void preparePictureDepthStreamConfig(@NonNull CamCapability camCapability) {
        final List<Size> availableDepthSizeList = camCapability.getSamsungDepthAvailableDepthSizes(ImageFormat.RAW_SENSOR);
        if (availableDepthSizeList.isEmpty()) {
            CLog.i(getMakerTag(), "preparePictureDepthStreamConfig - availableDepthSizeList is empty");
            return;
        }

        final Size depthStreamSize = (camCapability.getSamsungFeatureSingleCamSupportMultiRawStream())
                ? getPictureDepthStreamSize(availableDepthSizeList)
                : ArrayUtils.getMaxSize(availableDepthSizeList);
        if (null != depthStreamSize) {
            mPictureDepthStreamInfo = new PictureStreamInfo(
                    SemImageFormat.RAW_SENSOR,
                    depthStreamSize,
                    SensorPixelMode.MODE_DEFAULT,
                    /*cameraId*/null,
                    CamDevice.STREAM_OPTION_PICTURE | CamDevice.STREAM_OPTION_DEPTH,
                    /*usePhysicalStream*/false);
            CLog.i(getMakerTag(), "preparePictureDepthStreamConfig - " + depthStreamSize);
        } else {
            CLog.w(getMakerTag(), "preparePictureDepthStreamConfig - there isn't size matched with designated condition in the availableDepthSizeList");
        }
    }

    @Nullable
    private Size getPictureDepthStreamSize(@NonNull List<Size> availableDepthSizeList) {
        final Size firstCompSize = Objects.requireNonNull(mMakerPicStreamConfig.getSize(FIRST_COMP));
        final int firstCompWidth = firstCompSize.getWidth();

        return availableDepthSizeList.stream()
                .filter(depthSize -> depthSize.getWidth() >= firstCompWidth)
                .findFirst()
                .orElseGet(() -> {
                    // case. depth data is binning. Example : width of the 2PD depth is set to 1/2 of compPic
                    final int binningWidth = firstCompWidth / 2;
                    return availableDepthSizeList.stream()
                            .filter(depthSize -> depthSize.getWidth() >= binningWidth)
                            .findFirst()
                            .orElse(null);
                });
    }

    /**
     * <div class="camera_en">
     * prepare ThumbnailStream configuration
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ThumbnailStream 을 구성하기 위한 준비를 한다.
     * </div>
     *
     * @param camCapability camCapability
     * @param deviceConfiguration deviceConfiguration
     */
    protected void prepareThumbnailStreamConfig(@NonNull CamCapability camCapability, @NonNull DeviceConfiguration deviceConfiguration) {
        if (deviceConfiguration.getExtraShotInfoNeedNoThumbnailStream()) {
            return;
        }

        final Size firstCompSize = mMakerPicStreamConfig.getSize(FIRST_COMP);
        final List<Size> availableThumbnailSizeList = camCapability.getSamsungScalerAvailableThumbnailSizes();
        if (null == firstCompSize || availableThumbnailSizeList.isEmpty()) {
            return;
        }

        final Size thumbnailSize = SizeUtils.getMinimumSizeByRatio(firstCompSize, availableThumbnailSizeList);
        mThumbnailStreamInfo = new PictureStreamInfo(
                SemImageFormat.YUV_420_888,
                Objects.requireNonNull(thumbnailSize, "thumbnailSize is null"),
                SensorPixelMode.MODE_DEFAULT,
                /*cameraId*/null,
                CamDevice.STREAM_OPTION_THUMBNAIL,
                /*usePhysicalStream*/false);
    }

    /**
     * <div class="camera_en">
     * configure surfaceConfig
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * SurfaceConfig 를 구성한다.
     * </div>
     *
     * @param deviceConfiguration deviceConfiguration
     */
    protected SurfaceConfigCollector createSurfaceConfig(@NonNull DeviceConfiguration deviceConfiguration) {
        return new SurfaceConfigCollector(
                new PreviewSurfaceConfig(getMainPreviewSurface(), mMainPreviewSurfaceOption, getMainPreviewSurfaceSize(), deviceConfiguration.getMainPreviewSurfaceSource(), mMirrorMode),
                /*firstRecordSurfaceConfig*/null,
                /*secondRecordSurfaceConfig*/null,
                new ExtraPreviewSurfaceConfig(getFirstExtraPreviewSurface(), mExtraPreviewSurfaceOption, mPreviewUpdateByHal),
                new ExtraPreviewSurfaceConfig(getSecondExtraPreviewSurface(), mExtraPreviewSurfaceOption, mPreviewUpdateByHal),
                new MirrorPreviewSurfaceConfig(getMirrorPreviewSurface(), mMirrorPreviewSurfaceOption, deviceConfiguration.getMirrorPreviewOption()),
                new SessionConfig.SurfaceConfig(getDepthSurface(), mDepthSurfaceOption),
                /*metadataSurfaceConfig*/null);
    }

    /**
     * <div class="camera_en">
     * configure builderConfig
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * BuilderConfig 를 구성한다.
     * </div>
     */
    protected BuilderConfig createBuilderConfig() {
        return new BuilderConfig(mPreviewRequestBuilderMap, mPictureRequestBuilderMap, /*mRecordRequestBuilder*/null);
    }

    /**
     * <div class="camera_en">
     * Set repeating key
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * repeating key 를 설정한다.
     * </div>
     *
     * @param camCapability camCapability
     */
    protected void setRepeatingKey(@NonNull CamCapability camCapability) {
        enableRepeatingKey(REPEATING_KEY_MAIN_PREVIEW_CALLBACK, /*enable*/null != mMainPreviewCallbackForwarder);
        enableRepeatingKey(REPEATING_KEY_SUB_PREVIEW_CALLBACK,
                camCapability.getSamsungFeatureSubPreviewCbUseRequestSampling() ? mSubPreviewCbFrameRate : FrameRate.RATIO_MAX_PREVIEW_FPS,
                /*enable*/null != mSubPreviewCallbackForwarder);
        enableRepeatingKey(REPEATING_KEY_DEPTH_SURFACE, /*enable*/null != getDepthSurface());
        // NOTE : MirrorSurface only can support Hal updating.
        enableRepeatingKey(REPEATING_KEY_MIRROR_PREVIEW_SURFACE, /*enable*/null != getMirrorPreviewSurface());
        if (mPreviewUpdateByHal) {
            enableRepeatingKey(REPEATING_KEY_PREVIEW_SURFACE, /*enable*/null != getMainPreviewSurface());
            enableRepeatingKey(REPEATING_KEY_FIRST_EXTRA_PREVIEW_SURFACE, /*enable*/null != getFirstExtraPreviewSurface());
            enableRepeatingKey(REPEATING_KEY_SECOND_EXTRA_PREVIEW_SURFACE, /*enable*/null != getSecondExtraPreviewSurface());
        } else {
            preparePrivateSurfaces(camCapability);
            enableRepeatingKey(REPEATING_KEY_PRIVATE_PREVIEW_SURFACE, /*enable*/null != getMainPreviewSurface());
            enableRepeatingKey(REPEATING_KEY_FIRST_PRIVATE_EXTRA_PREVIEW_SURFACE, /*enable*/null != getFirstExtraPreviewSurface());
            enableRepeatingKey(REPEATING_KEY_SECOND_PRIVATE_EXTRA_PREVIEW_SURFACE, /*enable*/null != getSecondExtraPreviewSurface());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  {@inheritDoc}
     * @throws IllegalStateException     {@inheritDoc}
     * @throws InvalidOperationException {@inheritDoc}
     * @throws CamAccessException        {@inheritDoc}
     */
    @Override
    synchronized public void connectCamDevice(@NonNull CamDevice camDevice,
                                              @NonNull DeviceConfiguration deviceConfiguration,
                                              @NonNull MakerStateCallback callback,
                                              @Nullable Handler handler) throws CamAccessException {
        CLog.i(getMakerTag(), "[CAMFWKPI] connectCamDevice E - %s, %s, %s", camDevice, deviceConfiguration,
                Integer.toHexString(System.identityHashCode(callback)));
        final long startTimeToConnectCamDevice = System.currentTimeMillis();

        ConditionChecker.checkNotNull(camDevice, "camDevice");
        ConditionChecker.checkNotNull(deviceConfiguration, "deviceConfiguration");
        ConditionChecker.checkNotNull(deviceConfiguration.getParameters(), "parameters in deviceConfiguration");
        ConditionChecker.checkNotNull(callback, "callback");

        checkAvailableDeviceConfiguration(camDevice, deviceConfiguration);

        final boolean isReconnecting = camDevice.equals(mCamDevice);

        getCamDeviceSessionState().checkTransitState(isReconnecting ? CamDeviceSessionState.RECONNECTING : CamDeviceSessionState.CONNECTING);

        mNeedFusionHighRes = deviceConfiguration.getExtraShotInfoNeedFusionHighres();
        mMakerStateCallback = MakerStateCallbackForwarder.newInstance(callback, Optional.ofNullable(handler).orElse(getEventHandler()));

        final CamCapability camCapability = camDevice.getCamCapability();
        mCamDevice = camDevice;

        prepareSurfaceConfig(deviceConfiguration);
        preparePreviewCbStreamConfig(camCapability, deviceConfiguration);
        preparePictureStreamConfig(camCapability, deviceConfiguration);

        mDeviceUsageType = deviceConfiguration.getDeviceUsageType();
        setPictureEncodeFormat(deviceConfiguration);
        setMirrorMode(deviceConfiguration.getParameters().getMirrorMode());
        mColorSpaceMode = deviceConfiguration.getParameters().getColorSpaceMode();

        if (isReconnecting) {
            enableProcesses(false);
            TraceWrapper.traceBegin(getMakerTag() + "-releaseMaker");
            releaseMaker(camCapability);
            TraceWrapper.traceEnd();
        }

        startInitializeMakerThread(camCapability);

        try {
            createMakerRequestBuilder();
            setSessionKeys(deviceConfiguration.getParameters().getSessionKeys());

            CLog.i(getMakerTag(), "[CAMFWKPI] createCaptureSession E");
            final long startTimeToCreateCaptureSession = System.currentTimeMillis();
            camDevice.createCaptureSession(
                    new SessionConfig.Builder(
                            createSurfaceConfig(deviceConfiguration),
                            createBuilderConfig(),
                            buildCameraParameter(deviceConfiguration.getParameters()),
                            mCamDeviceSessionStateCallback)
                            .setPreviewCbConfigs(createPreviewCbConfigCollector())
                            .setFirstPicCbConfigs(createPicCbConfigCollector(FIRST))
                            .setSecondPicCbConfigs(createPicCbConfigCollector(SECOND))
                            .setThirdPicCbConfigs(createPicCbConfigCollector(THIRD))
                            .setFourthPicCbConfigs(createPicCbConfigCollector(FOURTH))
                            .setFifthPicCbConfigs(createPicCbConfigCollector(FIFTH))
                            .setSixthPicCbConfigs(createPicCbConfigCollector(SIXTH))
                            .setDepthCbStreamInfo(mPictureDepthStreamInfo)
                            .setThumbnailCbStreamInfo(mThumbnailStreamInfo)
                            .build());
            CLog.i(getMakerTag(), "[CAMFWKPI] createCaptureSession X - %d ms",
                    System.currentTimeMillis() - startTimeToCreateCaptureSession);
        } catch (CamDeviceException e) {
            joinInitializeMakerThread();
            releaseMaker(camCapability);
            throw new InvalidOperationException("createCaptureSession fail", e);
        } catch (CamAccessException | IllegalArgumentException e) {
            CLog.e(getMakerTag(), "createCaptureSession fail - " + e);
            joinInitializeMakerThread();
            releaseMaker(camCapability);
            throw e;
        }

        setRepeatingKey(camCapability);
        setCamDeviceSessionState(isReconnecting ? CamDeviceSessionState.RECONNECTING : CamDeviceSessionState.CONNECTING);
        CLog.i(getMakerTag(), "[CAMFWKPI] connectCamDevice X - %d ms", System.currentTimeMillis() - startTimeToConnectCamDevice);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException     {@inheritDoc}
     * @throws InvalidOperationException {@inheritDoc}
     */
    @Override
    synchronized public void disconnectCamDevice() {
        CLog.i(getMakerTag(), "disconnectCamDevice");

        getCamDeviceSessionState().checkTransitState(CamDeviceSessionState.DISCONNECTING);

        try {
            mCamDevice.closeCaptureSession();
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("closeCaptureSession fail", e);
        }

        setCamDeviceSessionState(CamDeviceSessionState.DISCONNECTING);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBurstShotFpsCallback(@Nullable MakerCallback callback, @Nullable Handler handler) {
        mMakerCallbackManager.setCallback(MakerCallbackType.BURST_SHOT_FPS_CALLBACK, callback, handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLensSuggestionCallback(@Nullable MakerCallback callback, @Nullable Handler handler) {
        mMakerCallbackManager.setCallback(MakerCallbackType.LENS_SUGGESTION_CALLBACK, callback, handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setNightSceneInfoCallback(@Nullable MakerCallback callback, @Nullable Handler handler) {
        mMakerCallbackManager.setCallback(MakerCallbackType.NIGHT_SCENE_INFO_CALLBACK, callback, handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setObjectDetectionInfoCallback(@Nullable MakerCallback callback, @Nullable Handler handler) {
        mMakerCallbackManager.setCallback(MakerCallbackType.OBJECT_DETECTION_INFO_CALLBACK, callback, handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setStillCaptureProgressCallback(@Nullable MakerCallback callback, @Nullable Handler handler) {
        mMakerCallbackManager.setCallback(MakerCallbackType.STILL_CAPTURE_PROGRESS_CALLBACK, callback, handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setTextDetectionInfoCallback(@Nullable MakerCallback callback, @Nullable Handler handler) {
        mMakerCallbackManager.setCallback(MakerCallbackType.TEXT_DETECTION_INFO_CALLBACK, callback, handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUnihalBeautyFaceDetectionCallback(@Nullable MakerCallback callback, @Nullable Handler handler) {
        mMakerCallbackManager.setCallback(MakerCallbackType.UNIHAL_BEAUTY_FACE_DETECTION_CALLBACK, callback, handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUnihalDocumentDetectionCallback(@Nullable MakerCallback callback, @Nullable Handler handler) {
        mMakerCallbackManager.setCallback(MakerCallbackType.UNIHAL_DOCUMENT_DETECTION_CALLBACK, callback, handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUnihalQrCodeDetectionCallback(@Nullable MakerCallback callback, @Nullable Handler handler) {
        mMakerCallbackManager.setCallback(MakerCallbackType.UNIHAL_QR_CODE_DETECTION_CALLBACK, callback, handler);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException     {@inheritDoc}
     * @throws InvalidOperationException {@inheritDoc}
     * @throws CamAccessException        {@inheritDoc}
     */
    @Override
    synchronized public int startPreviewRepeating() throws CamAccessException {
        CLog.i(getMakerTag(), "[CAMFWKPI] startPreviewRepeating");

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        preparePreviewBufferCallbackForwarder();

        final MakerRepeatingModeManager.RepeatingCount repeatingCount = mRepeatingModeManager.calculateRepeatingCount();
        try {
            return mCamDevice.startPreviewRepeating(
                    CamDeviceRepeatingRequestCnt.create()
                            .setMainPreviewCbRequestCnt(repeatingCount.getCount(RepeatingMode.MAIN_PREVIEW_CALLBACK))
                            .setSubPreviewCbRequestCnt(repeatingCount.getCount(RepeatingMode.SUB_PREVIEW_CALLBACK))
                            .setMainPreviewRequestCnt(repeatingCount.getCount(RepeatingMode.PREVIEW_SURFACE))
                            .setFirstExtraPreviewRequestCnt(repeatingCount.getCount(RepeatingMode.FIRST_EXTRA_PREVIEW_SURFACE))
                            .setSecondExtraPreviewRequestCnt(repeatingCount.getCount(RepeatingMode.SECOND_EXTRA_PREVIEW_SURFACE))
                            .setMirrorPreviewRequestCnt(repeatingCount.getCount(RepeatingMode.MIRROR_PREVIEW_SURFACE))
                            .build(),
                    mCamDevicePreviewStateCallback);
        } catch (CamDeviceException e) {
            CLog.e(getMakerTag(), "startPreviewRepeating fail: " + e.getMessage());
            throw new InvalidOperationException("startPreviewRepeating fail", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException     {@inheritDoc}
     * @throws InvalidOperationException {@inheritDoc}
     * @throws CamAccessException        {@inheritDoc}
     */
    @Override
    synchronized public int restartPreviewRepeating() throws CamAccessException {
        CLog.v(getMakerTag(), "restartPreviewRepeating");

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        try {
            return mCamDevice.restartPreviewRepeating();
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("restartPreviewRepeating fail", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException     {@inheritDoc}
     * @throws InvalidOperationException {@inheritDoc}
     * @throws CamAccessException        {@inheritDoc}
     */
    @Override
    synchronized public void stopRepeating() throws CamAccessException {
        CLog.i(getMakerTag(), "stopRepeating");

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        try {
            mCamDevice.stopRepeating();
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("stopRepeating fail", e);
        }
    }

    /**
     * <div class="camera_en">
     * Prepare PreviewBufferCallbackForwarder.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PreviewBufferCallbackForwarder 를 준비한다.
     * </div>
     *
     * @throws IllegalArgumentException prepareBufferCallbackForwarder fail
     */
    synchronized protected void preparePreviewBufferCallbackForwarder() {
        try {
            final int mainPreviewCbBufferSize = Optional.ofNullable(mMainPreviewCbStreamInfo)
                    .map(previewCbStreamInfo -> ImageUtils.getPaddedBufferSize(previewCbStreamInfo.format().getValue(), previewCbStreamInfo.size()))
                    .orElse(0);
            BufferCallbackForwarderHelper.prepareBufferForwarderIfUsed(mMainPreviewCallbackForwarder, mainPreviewCbBufferSize, PREVIEW_BUFFER_FORWARDER_MAX_CONCURRENT, PREVIEW_BUFFER_FORWARDER_MODE);

            final int subPreviewCbBufferSize = Optional.ofNullable(mSubPreviewCbStreamInfo)
                    .map(previewCbStreamInfo -> ImageUtils.getPaddedBufferSize(previewCbStreamInfo.format().getValue(), previewCbStreamInfo.size()))
                    .orElse(0);
            BufferCallbackForwarderHelper.prepareBufferForwarderIfUsed(mSubPreviewCallbackForwarder, subPreviewCbBufferSize, PREVIEW_BUFFER_FORWARDER_MAX_CONCURRENT,
PREVIEW_BUFFER_FORWARDER_MODE);
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("prepareBufferCallbackForwarder fail", e);
        }
    }

    /**
     * <div class="camera_en">
     * Set updateRate of preview first extra surface through the REPEATING_KEY_FIRST_PREVIEW_EXTRA_SURFACE
     * or REPEATING_KEY_FIRST_PRIVATE_PREVIEW_EXTRA_SURFACE key and the frameRate of the type {@link FrameRate}
     * using {@link PhotoMakerBase#applyRepeatingKey}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@link PhotoMakerBase#applyRepeatingKey} 함수를 이용해 REPEATING_KEY_FIRST_PREVIEW_EXTRA_SURFACE 키 또는
     * REPEATING_KEY_FIRST_PRIVATE_PREVIEW_EXTRA_SURFACE 키와 {@link FrameRate} 타입의 frameRate 을 통해
     * first preview extra surface 의 갱신주기를 설정한다.
     * </div>
     *
     * @param frameRate frameRate.
     * @return the {@code sequenceId} if previewRepeating is started, or -1 otherwise
     * @throws UnsupportedOperationException If first ExtraPreviewSurface is null.
     * @throws InvalidOperationException     If startPreviewRepeating fail internally.
     */
    protected int setFirstExtraSurfaceUpdateRate(@NonNull FrameRate frameRate) {
        if (null == getFirstExtraPreviewSurface()) {
            throw new UnsupportedOperationException("mFirstExtraPreviewSurface is null, so can't adjust FIRST_EXTRA_SURFACE_UPDATING_RATE");
        }

        if (mPreviewUpdateByHal) {
            try {
                return applyRepeatingKey(REPEATING_KEY_FIRST_EXTRA_PREVIEW_SURFACE, frameRate, true);
            } catch (CamAccessException e) {
                throw new InvalidOperationException("setFirstExtraSurfaceUpdateRate fail - ", e);
            }
        } else {
            enableRepeatingKey(REPEATING_KEY_FIRST_PRIVATE_EXTRA_PREVIEW_SURFACE, frameRate, frameRate != FrameRate.RATIO_NONE);
            return -1;
        }
    }

    /**
     * <div class="camera_en">
     * Set updateRate of second preview extra surface through the REPEATING_KEY_SECOND_PREVIEW_EXTRA_SURFACE
     * or REPEATING_KEY_SECOND_PRIVATE_PREVIEW_EXTRA_SURFACE key and the frameRate of the type {@link FrameRate}
     * using {@link PhotoMakerBase#applyRepeatingKey}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@link PhotoMakerBase#applyRepeatingKey} 함수를 이용해 REPEATING_KEY_SECOND_PREVIEW_EXTRA_SURFACE 키 또는
     * REPEATING_KEY_SECOND_PRIVATE_PREVIEW_EXTRA_SURFACE 키와 {@link FrameRate} 타입의 frameRate 을 통해
     * second preview extra surface 의 갱신주기를 설정한다.
     * </div>
     *
     * @param frameRate frameRate.
     * @return the {@code sequenceId} if previewRepeating is not started, or -1 otherwise
     * @throws UnsupportedOperationException If second ExtraPreviewSurface is null.
     * @throws InvalidOperationException     If startPreviewRepeating fail internally.
     */
    protected int setSecondExtraSurfaceUpdateRate(@NonNull FrameRate frameRate) {
        if (null == getSecondExtraPreviewSurface()) {
            throw new UnsupportedOperationException("mSecondExtraPreviewSurface is null, so can't adjust SECOND_EXTRA_SURFACE_UPDATING_RATE");
        }

        if (mPreviewUpdateByHal) {
            try {
                return applyRepeatingKey(REPEATING_KEY_SECOND_EXTRA_PREVIEW_SURFACE, frameRate, true);
            } catch (CamAccessException e) {
                throw new InvalidOperationException("setSecondExtraSurfaceUpdateRate fail - ", e);
            }
        } else {
            enableRepeatingKey(REPEATING_KEY_SECOND_PRIVATE_EXTRA_PREVIEW_SURFACE, frameRate, frameRate != FrameRate.RATIO_NONE);
            return -1;
        }
    }

    /**
     * <div class="camera_en">
     * Set updateRate of mirror Surface through the REPEATING_KEY_MIRROR_PREVIEW_SURFACE key and
     * the frameRate of the type {@link FrameRate} using {@link VideoMakerBase#applyRepeatingKey}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@link VideoMakerBase#applyRepeatingKey} 함수를 이용해 REPEATING_KEY_MIRROR_PREVIEW_SURFACE 키와
     * {@link FrameRate} 타입의 frameRate 을 통해 mirror Surface 의 갱신주기를 설정한다.
     * </div>
     *
     * @param frameRate frameRate.
     * @return the {@code sequenceId} if previewRepeating is not started, or -1 otherwise
     * @throws UnsupportedOperationException If mirror PreviewSurface is null.
     * @throws InvalidOperationException     If startPreviewRepeating fail internally.
     */
    protected int setMirrorSurfaceUpdateRate(@NonNull FrameRate frameRate) {
        if (null == getMirrorPreviewSurface()) {
            throw new UnsupportedOperationException("mMirrorPreviewSurface is null, so can't adjust MIRROR_SURFACE_UPDATING_RATE");
        }

        // NOTE : MirrorSurface only can support Hal updating.
        try {
            return applyRepeatingKey(REPEATING_KEY_MIRROR_PREVIEW_SURFACE, frameRate, true);
        } catch (CamAccessException e) {
            throw new InvalidOperationException("setMirrorSurfaceUpdateRate fail - ", e);
        }
    }

    /**
     * <div class="camera_en">
     * Enable repeatingKey.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * RepeatingKey 를 설정한다.
     * </div>
     *
     * @param repeatingKey repeatingKey.
     * @param frameRate    frameRate.
     * @param enable       enable of repeatingKey.
     * @return true if condition of repeatingMode corresponding repeatingKey is changed return true or false.
     */
    protected final boolean enableRepeatingKey(@NonNull RepeatingKey repeatingKey, @NonNull FrameRate frameRate, boolean enable) {
        CLog.v(getMakerTag(), "enableRepeatingKey - %s, frameRate %s, enable %b", repeatingKey, frameRate, enable);
        return mRepeatingModeManager.enableRepeatingKey(repeatingKey, frameRate, enable);
    }

    /**
     * <div class="camera_en">
     * Enable repeatingKey.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * RepeatingKey 를 설정한다.
     * </div>
     *
     * @param repeatingKey repeatingKey.
     * @param enable       enable of repeatingKey.
     * @return true if condition of repeatingMode corresponding repeatingKey is changed return true or false.
     */
    protected final boolean enableRepeatingKey(@NonNull RepeatingKey repeatingKey, boolean enable) {
        return enableRepeatingKey(repeatingKey, FrameRate.RATIO_MAX_PREVIEW_FPS, enable);
    }

    /**
     * <div class="camera_en">
     * Enable repeatingKey and if repeatingMode for repeatingKey is changed and current repeating state of camDevice is preview or record then,
     * start previewRepeating or recordRepeating.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * RepeatingKey 를 설정하고, repeatingKey 에 연관된 repeatingMode 가 변경되고 camDevice 의 repeating state
     * 가 preview 또는 record 일경우 previewRepeating 또는 recordRepeating 을 시작한다.
     * </div>
     *
     * @param repeatingKey repeatingKey.
     * @param frameRate    frameRate.
     * @param enable       enable of repeatingKey.
     * @return the {@code sequenceId} if previewRepeating is not started, or -1 otherwise
     * @throws InvalidOperationException If startPreviewRepeating fail internally.
     * @throws CamAccessException        If a CameraAccessException occurs.
     */
    protected final int applyRepeatingKey(@NonNull RepeatingKey repeatingKey, @NonNull FrameRate frameRate, boolean enable)
            throws CamAccessException {
        CLog.v(getMakerTag(), "applyRepeatingKey - %s, frameRate %s, enable %b", repeatingKey, frameRate, enable);

        if (enableRepeatingKey(repeatingKey, frameRate, enable)) {
            if (CamDeviceRepeatingState.REPEATING_PREVIEW == mCamDevice.getRepeatingState().getId()) {
                return startPreviewRepeating();
            }
        }

        return -1;
    }

    /**
     * <div class="camera_en">
     * Enable repeatingKey and if repeatingMode for repeatingKey is changed and current repeating state of camDevice is preview then,
     * start previewRepeating.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * RepeatingKey 를 설정하고, repeatingKey 에 연관된 repeatingMode 가 변경되고 camDevice 의 repeating state
     * 가 preview 일경우 previewRepeating 을 시작한다.
     * </div>
     *
     * @param repeatingKey repeatingKey.
     * @param enable       enable of repeatingKey.
     * @return the {@code sequenceId} if previewRepeating is not started, or -1 otherwise
     * @throws InvalidOperationException if startPreviewRepeating fail internally.
     * @throws CamAccessException        if a CameraAccessException occurs.
     */
    protected final int applyRepeatingKey(@NonNull RepeatingKey repeatingKey, boolean enable)
            throws CamAccessException {
        return applyRepeatingKey(repeatingKey, FrameRate.RATIO_MAX_PREVIEW_FPS, enable);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized protected void createMakerRequestBuilder() throws CamAccessException {
        if (null == mCamDevice) {
            CLog.e(getMakerTag(), "createMakerRequestBuilder fail - mCamDevice is null");
            return;
        }
        createRequestBuilder(mCamDevice, mPreviewRequestBuilderMap, CameraDevice.TEMPLATE_PREVIEW,
                /*physicalCameraIdSet*/null);
        createRequestBuilder(mCamDevice, mPictureRequestBuilderMap, CameraDevice.TEMPLATE_STILL_CAPTURE,
                /*physicalCameraIdSet*/null);
    }

    /**
     * <div class="camera_en">
     * Start the burst picture repeating.
     * After calling this method, you should not take another picture until the picture callback has invoked.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 버스트 가용 fps 를 참조하여 사진을 촬영을 반복시킨다. 기존 촬영이 완료되기 전에 다른 촬영을 시작할 수 없다.
     * </div>
     *
     * @param requestOptions CaptureRequest options that is set in pictureRequestBuilder.
     * @return sequenceId                sequenceId.
     * @throws InvalidOperationException If startBurstPictureRepeatingInternal is failed internally.
     * @throws CamAccessException        If a CameraAccessException occurs.
     */
    @SuppressWarnings("WeakerAccess")
    protected final int startBurstPictureRepeatingInternal(@NonNull CamDeviceRequestOptions requestOptions) throws CamAccessException {
        CLog.v(getMakerTag(), "startBurstPictureRepeatingInternal - requestOptions %s", requestOptions);

        try {
            if (null != mBurstPictureCallbackForwarder) {
                final boolean useBufferForwarder = mCamDevice.getCamCapability().getSamsungControlMaxBurstShotFps() >= 10
                        && MemoryUtils.isGreaterThan(MemoryUtils.MemoryLevel.MID);
                mBurstPictureCallbackForwarder.enableUseBufferForwarder(useBufferForwarder);
                if (useBufferForwarder) {
                    final int bufferSize;
                    if (requestOptions.isPicTypeRequested(FIRST_COMP) || requestOptions.isPicTypeRequested(FIRST_UN_COMP)) {
                        bufferSize = ImageUtils.estimateJpegBufferSize(mMakerPicStreamConfig.getSize(FIRST_COMP), /*quality*/100);
                    } else {
                        bufferSize = ImageUtils.estimateJpegBufferSize(mMakerPicStreamConfig.getSize(SECOND_COMP), /*quality*/100);
                    }
                    mBurstPictureCallbackForwarder.prepareBufferForwarder(bufferSize, BURST_PICTURE_BUFFER_FORWARDER_MAX_CONCURRENT, BURST_PICTURE_BUFFER_FORWARDER_MODE);
                }
            } else {
                CLog.i(getMakerTag(), "startBurstPictureRepeatingInternal - don't use BurstPictureBufferForwarder");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("prepareBufferCallbackForwarder fail", e);
        }

        try {
            return mCamDevice.startBurstPictureRepeating(requestOptions);
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("startBurstPictureRepeating fail", e);
        }
    }

    /**
     * <div class="camera_en">
     * Stop the burst picture repeating.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Burst Picture 촬영 반복을 중단한다.
     * </div>
     *
     * @return newRepeatingRequestSequenceId newRepeatingRequestSequenceId.
     * @throws InvalidOperationException if stopBurstPictureRepeatingInternal is failed internally.
     * @throws CamAccessException        If a CameraAccessException occurs.
     */
    @SuppressWarnings("WeakerAccess")
    protected final int stopBurstPictureRepeatingInternal() throws CamAccessException {
        CLog.v(getMakerTag(), "stopBurstPictureRepeatingInternal");

        try {
            return mCamDevice.stopBurstPictureRepeating();
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("stopBurstPictureRepeating fail", e);
        }
    }

    /**
     * <div class="camera_en">
     * send Thumbnail from Encoded Image
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Encoded Image 로 부터 Thumbnail 을 전달 한다.
     * </div>
     *
     * @param pictureData pictureData.
     */
    protected final void sendThumbnailFromEncodedImage(@NonNull ImageBuffer pictureData) {
        final ImageUtils.SimpleImage thumbnail;

        final SemImageFormat format = pictureData.getImageInfo().getFormat();
        switch (format) {
            case JPEG, JPEG_R -> thumbnail = ExifUtils.extractThumbnailFromJpeg(pictureData);
            case HEIC, HEIC_ULTRAHDR -> thumbnail = ExifUtils.extractThumbnailFromHeic(pictureData);
            default -> {
                CLog.w(getMakerTag(), "sendThumbnailFromEncodedImage - failed : unsupported format(" + format + ")");
                return;
            }
        }

        CLog.i(getMakerTag(), "sendThumbnailFromEncodedImage - Thumbnail : " + thumbnail);
        ThumbnailCallbackHelper.onThumbnailTaken(getMakerTag(), mThumbnailCallback, thumbnail.buffer(), thumbnail.format(), thumbnail.size(), pictureData.getImageInfo().getCaptureResult(), mCamDevice);
    }

    protected final void sendJpegThumbnail(@NonNull ImageBuffer pictureData) {
        final Size size = SemCaptureResult.get(pictureData.getImageInfo().getCaptureResult(), CaptureResult.JPEG_THUMBNAIL_SIZE);
        CLog.i(getMakerTag(), "sendJpegThumbnail - pictureData " + pictureData + ", Thumbnail size " + size);

        ThumbnailCallbackHelper.onThumbnailTaken(getMakerTag(), mThumbnailCallback, ExifUtils.extractThumbnailFromJpeg(pictureData).buffer(),
                SemImageFormat.JPEG, size, pictureData.getImageInfo().getCaptureResult(), mCamDevice);
    }

    protected void sendCaptureAvailableFromHAL(int sequenceId, @Nullable Long timeStamp) {
        final int activatedSequenceStackedCount = PictureProcessorManager.getInstance().getActivatedSequenceStackedCount();
        if (activatedSequenceStackedCount < PARALLEL_CAPTURE_MAX_COUNT) {
            final Runnable callback = () -> {
                PictureCallbackHelper.onCaptureAvailable(getMakerTag(), mPictureCallback, sequenceId, timeStamp, mCamDevice);
                CLog.i(getMakerTag(), "sendCaptureAvailableFromHAL - onCaptureAvailable from HAL.[sequence id : %d]", sequenceId);
            };
            final CaptureAvailableApmPolicy policy = AdaptivePerformanceManager.getInstance().getPolicy(CaptureAvailableApmPolicy.class);
            if (policy == null || !policy.execute(sequenceId, callback)) {
                callback.run();
            }
        } else {
            CLog.i(getMakerTag(), "sendCaptureAvailableFromHAL - skip captureAvailable from HAL.[sequence id : %d], ppp stack count : %d", sequenceId, activatedSequenceStackedCount);
        }
    }

    protected void sendCaptureAvailable(int sequenceId, @Nullable Long timeStamp, @Nullable Runnable runnable) {
        final Runnable callback = () -> {
            PictureCallbackHelper.onCaptureAvailable(getMakerTag(), mPictureCallback, sequenceId, timeStamp, mCamDevice);
            Optional.ofNullable(runnable).ifPresent(Runnable::run);
            CLog.i(getMakerTag(), "sendCaptureAvailable - onCaptureAvailable [sequence id : %d]", sequenceId);
        };
        final CaptureAvailableApmPolicy policy = AdaptivePerformanceManager.getInstance().getPolicy(CaptureAvailableApmPolicy.class);
        if (policy == null || !policy.execute(sequenceId, callback)) {
            callback.run();
        }
    }

    protected void sendCaptureAvailableImmediately(int sequenceId, @Nullable Long timeStamp, @Nullable Runnable runnable) {
        PictureCallbackHelper.onCaptureAvailable(getMakerTag(), mPictureCallback, sequenceId, timeStamp, mCamDevice);
        Optional.ofNullable(runnable).ifPresent(Runnable::run);
        CLog.i(getMakerTag(), "sendCaptureAvailableImmediately - onCaptureAvailable [sequence id : %d]", sequenceId);
    }

    protected void sendCaptureAvailable(@Nullable CaptureMetadata captureMetadata) {
        if (null == captureMetadata) {
            CLog.w(getMakerTag(), "sendCaptureAvailable is failed - captureMetadata is null");
            return;
        }

        final int sequenceId = captureMetadata.getSequenceId();
        final long timeStamp = Optional.ofNullable(SemCaptureResult.get(captureMetadata, CaptureResult.SENSOR_TIMESTAMP)).orElse(0L);
        PictureCallbackHelper.onCaptureAvailable(getMakerTag(), mPictureCallback, sequenceId, timeStamp, mCamDevice);
    }

    /**
     * <div class="camera_en">
     * Write frame to preview surface.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Preview surface 에 preview frame 을 써준다.
     * </div>
     *
     * @param previewData PreviewData.
     */
    protected void producePreviewFrame(@Nullable Image previewData) {
        TraceWrapper.traceBegin(getMakerTag() + (mIsFirstPreviewProduceFrame ? " first " : "") + "-producePreviewFrame");
        // CLog.i(getMakerTag(), "producePreviewFrame - previewData " + StringUtils.toString(previewData));

        if (null == previewData) {
            CLog.v(getMakerTag(), "producePreviewFrame - previewData is null");
            TraceWrapper.traceEnd();
            return;
        }

        final Surface privatePreviewSurface = mPrivatePreviewSurface;
        if (null == privatePreviewSurface
                || mPreviewUpdateByHal
                || !mRepeatingModeManager.isRepeatingKeyEnabled(REPEATING_KEY_PRIVATE_PREVIEW_SURFACE)) {
            TraceWrapper.traceEnd();
            return;
        }

        try {
            if (mIsFirstPreviewProduceFrame) {
                CLog.i(getMakerTag(), "[CAMFWKPI] first producePreviewFrame E - " + StringUtils.toString(previewData));
                NativeUtils.produceFrameWithYuv420(privatePreviewSurface, previewData);
                CLog.i(getMakerTag(), "[CAMFWKPI] first producePreviewFrame X");
                mIsFirstPreviewProduceFrame = false;
                return;
            }

            final long startTime = System.currentTimeMillis();
            NativeUtils.produceFrameWithYuv420(privatePreviewSurface, previewData);
            final long diffTime = System.currentTimeMillis() - startTime;
            if (diffTime > PRODUCE_PREVIEW_FRAME_TIMEOUT_MILLIS) {
                CLog.w(getMakerTag(), "producePreviewFrame - produceFrameWithYuv420 timeout " + PRODUCE_PREVIEW_FRAME_TIMEOUT_MILLIS + "ms : " + diffTime + "ms");
            }
        } catch (Exception e) {
            CLog.e(getMakerTag(), "producePreviewFrame - produceFrameWithYuv420 failed, " + e);
        } finally {
            TraceWrapper.traceEnd();
        }
    }

    /**
     * <div class="camera_en">
     * Write frame to extra preview surface.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Extra preview surface 에 preview frame 을 써준다.
     * </div>
     *
     * @param previewData PreviewData.
     */
    protected void produceExtraPreviewFrame(@NonNull Image previewData) {
        // CLog.i(getMakerTag(), "produceExtraPreviewFrame - previewData " + StringUtils.toString(previewData));
        if (mPreviewUpdateByHal) {
            return;
        }

        if (null != mFirstPrivateExtraPreviewSurface
                && mRepeatingModeManager.isRepeatingKeyEnabled(REPEATING_KEY_FIRST_PRIVATE_EXTRA_PREVIEW_SURFACE)) {

            produceExtraPreviewFrameInternal(previewData, mFirstPrivateExtraPreviewSurface);
        }

        if (null != mSecondPrivateExtraPreviewSurface
                && mRepeatingModeManager.isRepeatingKeyEnabled(REPEATING_KEY_SECOND_PRIVATE_EXTRA_PREVIEW_SURFACE)) {

            produceExtraPreviewFrameInternal(previewData, mSecondPrivateExtraPreviewSurface);
        }
    }

    private void produceExtraPreviewFrameInternal(Image previewData, Surface privateExtraPreviewSurface) {
        try {
            if (mIsFirstExtraProduceFrame) {
                CLog.i(getMakerTag(), "[CAMFWKPI] first produceExtraPreviewFrame E - " + StringUtils.toString(previewData));
                NativeUtils.produceFrameWithYuv420(privateExtraPreviewSurface, previewData);
                CLog.i(getMakerTag(), "[CAMFWKPI] first produceExtraPreviewFrame X");
                mIsFirstExtraProduceFrame = false;
                return;
            }
            NativeUtils.produceFrameWithYuv420(privateExtraPreviewSurface, previewData);
        } catch (Exception e) {
            CLog.e(getMakerTag(), "produceExtraPreviewFrame - produceFrameWithYuv420 failed, " + e);
        }
    }

    /**
     * <div class="camera_en">
     * Create private preview surface.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Private preview surface 를 생성한다.
     * </div>
     *
     * @param previewSurface preview surface.
     * @param camCapability  camCapability.
     * @return privatePreviewSurface.
     * @throws IllegalArgumentException if {@code previewSurface} is invalid.
     */
    @Nullable
    protected Surface createPrivatePreviewSurface(@NonNull Surface previewSurface, @NonNull CamCapability camCapability) {
        CLog.i(getMakerTag(), "createPrivatePreviewSurface - %s, lensFacing %d, sensorOrientation %d, mirrorMode %d",
                previewSurface, camCapability.getLensFacing(), camCapability.getSensorOrientation(), mMirrorMode);
        final Surface privatePreviewSurface;
        try {
            privatePreviewSurface = NativeUtils.createPrivateSurface(previewSurface);
            NativeUtils.setSurfaceFormat(privatePreviewSurface, NativeUtils.HAL_PIXEL_FORMAT_YCrCb_420_SP, true);
            NativeUtils.setScalingMode(privatePreviewSurface, NativeUtils.NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);
            NativeUtils.setSurfaceMirror(privatePreviewSurface,
                    Objects.requireNonNull(camCapability.getLensFacing()),
                    Objects.requireNonNull(camCapability.getSensorOrientation()),
                    mMirrorMode);
            if (mColorSpaceMode == DeviceConfiguration.Parameters.ColorSpaceMode.MODE_DISPLAY_P3_PHOTO) {
                NativeUtils.setDataSpaceToSurface(privatePreviewSurface, NativeUtils.DATASPACE_DISPLAY_P3);
            } else {
                NativeUtils.setDataSpaceToSurface(privatePreviewSurface, NativeUtils.DATASPACE_V0_JFIF);
            }
        } catch (NativeUtils.BufferQueueAbandonedException e) {
            throw new IllegalArgumentException("set attribute for privatePreviewSurface fail - " + e);
        }
        return privatePreviewSurface;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  {@inheritDoc}
     * @throws IllegalStateException     {@inheritDoc}
     * @throws InvalidOperationException {@inheritDoc}
     * @throws CamAccessException        {@inheritDoc}
     * @see MakerPublicKey#SEM_TRIGGER_REQUEST_CONTROL_COMPOSITION_GUIDE_TRIGGER
     * @see PublicMetadata.CompositionGuideTrigger
     * @see MakerPublicKey#SEM_TRIGGER_REQUEST_CONTROL_HYPERLAPSE_TRIGGER
     * @see PublicMetadata.HyperlapseTrigger
     * @see MakerPublicKey#SEM_TRIGGER_REQUEST_CONTROL_OBJECT_DETECTION_TRIGGER
     * @see MakerPublicKey#SEM_TRIGGER_REQUEST_CONTROL_RECORDING_TRIGGER
     * @see PublicMetadata.RecordingTrigger
     * @see MakerPublicKey#SEM_TRIGGER_REQUEST_CONTROL_SUPER_SLOW_MOTION_TRIGGER
     * @see PublicMetadata.SuperSlowMotionTrigger
     * @see MakerPublicKey#SEM_TRIGGER_REQUEST_CONTROL_ZOOM_LOCK_TRIGGER
     * @see PublicMetadata.ZoomLockTrigger
     */
    @Override
    synchronized public <T> void setTrigger(@NonNull CaptureRequest.Key<T> key, T value) throws CamAccessException {
        ConditionChecker.checkNotNull(key, "CaptureRequest key");

        CLog.v(getMakerTag(), "setTrigger - %s : %s", key, StringUtils.deepToString(value));

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        try {
            mCamDevice.setTrigger(key, value);
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("setTrigger fail", e);
        }
    }

    /**
     * <div class="camera_en">
     * Set Af and AePreCapture trigger.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Af, AePreCapture trigger 를 set 한다.
     * </div>
     *
     * @param afTrigger           Af's trigger.
     * @param aePreCaptureTrigger AePreCapture's trigger.
     * @throws IllegalStateException     If cam device is not connected.
     * @throws InvalidOperationException If setAfAndAePreCaptureTrigger is failed internally.
     * @throws CamAccessException        If a CameraAccessException occurs.
     * @see PublicMetadata#CONTROL_AF_TRIGGER_START
     * @see PublicMetadata#CONTROL_AF_TRIGGER_CANCEL
     * @see PublicMetadata#CONTROL_AE_PRECAPTURE_TRIGGER_START
     * @see PublicMetadata#CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL
     */
    @Override
    synchronized public void setAfAndAePreCaptureTrigger(@PublicMetadata.AfTrigger int afTrigger,
                                                         @PublicMetadata.AePreCaptureTrigger int aePreCaptureTrigger)
            throws CamAccessException {
        CLog.v(getMakerTag(), "setAfAndAePreCaptureTrigger(%d)(%d)", afTrigger, aePreCaptureTrigger);

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        try {
            mCamDevice.setAfAndAePreCaptureTrigger(afTrigger, aePreCaptureTrigger);
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("setAfAndAePreCaptureTrigger fail", e);
        }
    }

    /**
     * <div class="camera_en">
     * Set AePreCapture trigger.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * AePreCapture trigger 를 set 한다.
     * </div>
     *
     * @throws IllegalStateException     If cam device is not connected.
     * @throws InvalidOperationException If setAePreCaptureTrigger is failed internally.
     * @throws CamAccessException        If a CameraAccessException occurs.
     * @see PublicMetadata#CONTROL_AE_PRECAPTURE_TRIGGER_START
     * @see PublicMetadata#CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL
     */
    @Override
    synchronized public void setAePreCaptureTrigger(@PublicMetadata.AePreCaptureTrigger int trigger) throws CamAccessException {
        CLog.v(getMakerTag(), "setAePreCaptureTrigger(%d)", trigger);

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        try {
            mCamDevice.setAePreCaptureTrigger(trigger);
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("setAePreCaptureTrigger fail", e);
        }
    }

    /**
     * <div class="camera_en">
     * Set PictureCallback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PictureCallback 을 설정 한다.
     * </div>
     *
     * @param callback PictureCallback's instance.
     * @param handler  For using separate handler.
     */
    @Override
    public void setPictureCallback(@Nullable PictureCallback callback, @Nullable Handler handler) {
        CLog.v(getMakerTag(), "setPictureCallback(%s)", Integer.toHexString(System.identityHashCode(callback)));
        mPictureCallback = PictureCallbackForwarder.newInstance(callback, Optional.ofNullable(handler).orElse(getEventHandler()), mTakePictureRequestLock);
    }

    /**
     * <div class="camera_en">
     * Set MainPreviewCallback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * MainPreviewCallback 을 설정 한다.
     * </div>
     *
     * @param callback PreviewCallback's instance.
     * @param handler  For using separate handler.
     * @return the {@code sequenceId}, or -1 if a new sequence ID cannot be created
     * @throws InvalidOperationException If startPreviewRepeating for new sequence fail internally.
     * @throws CamAccessException        If a CameraAccessException occurs.
     */
    @Override
    synchronized public int setMainPreviewCallback(@Nullable PreviewCallback callback, @Nullable Handler handler) throws CamAccessException {
        CLog.i(getMakerTag(), "setMainPreviewCallback(%s)", Integer.toHexString(System.identityHashCode(callback)));

        mMainPreviewCallbackForwarder = BufferCallbackForwarderHelper.releaseAndNewInstance(mMainPreviewCallbackForwarder,
                callback, Optional.ofNullable(handler).orElse(getEventHandler()), /*useBufferForwarder*/true);

        if (getCamDeviceSessionState().compareState(CamDeviceSessionState.CONNECTED)) {
            try {
                final int mainPreviewCbBufferSize = Optional.ofNullable(mMainPreviewCbStreamInfo)
                        .map(previewCbStreamInfo -> ImageUtils.getPaddedBufferSize(previewCbStreamInfo.format().getValue(), previewCbStreamInfo.size()))
                        .orElse(0);
                BufferCallbackForwarderHelper.prepareBufferForwarderIfUsed(mMainPreviewCallbackForwarder, mainPreviewCbBufferSize, PREVIEW_BUFFER_FORWARDER_MAX_CONCURRENT, PREVIEW_BUFFER_FORWARDER_MODE);
            } catch (IllegalArgumentException e) {
                throw new InvalidOperationException("setMainPreviewCallback fail", e);
            }
            return applyRepeatingKey(REPEATING_KEY_MAIN_PREVIEW_CALLBACK, null != callback);
        } else {
            enableRepeatingKey(REPEATING_KEY_MAIN_PREVIEW_CALLBACK, null != callback);
            return -1;
        }
    }

    /**
     * <div class="camera_en">
     * Set SubPreviewCallback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * SubPreviewCallback 을 설정 한다.
     * </div>
     *
     * @param callback PreviewCallback's instance.
     * @param handler  For using separate handler.
     * @return the {@code sequenceId}, or -1 if a new sequence ID cannot be created
     * @throws InvalidOperationException If startPreviewRepeating for new sequence fail internally.
     * @throws CamAccessException        If a CameraAccessException occurs.
     */
    @Override
    synchronized public int setSubPreviewCallback(@Nullable PreviewCallback callback, @Nullable Handler handler) throws CamAccessException {
        CLog.i(getMakerTag(), "setSubPreviewCallback(%s)", Integer.toHexString(System.identityHashCode(callback)));

        mSubPreviewCallbackForwarder = BufferCallbackForwarderHelper.releaseAndNewInstance(mSubPreviewCallbackForwarder,
                callback, Optional.ofNullable(handler).orElse(getEventHandler()), /*useBufferForwarder*/true);

        if (getCamDeviceSessionState().compareState(CamDeviceSessionState.CONNECTED)) {
            try {
                final int subPreviewCbBufferSize = Optional.ofNullable(mSubPreviewCbStreamInfo)
                        .map(previewCbStreamInfo -> ImageUtils.getPaddedBufferSize(previewCbStreamInfo.format().getValue(), previewCbStreamInfo.size()))
                        .orElse(0);
                BufferCallbackForwarderHelper.prepareBufferForwarderIfUsed(mSubPreviewCallbackForwarder, subPreviewCbBufferSize, PREVIEW_BUFFER_FORWARDER_MAX_CONCURRENT, PREVIEW_BUFFER_FORWARDER_MODE);
            } catch (IllegalArgumentException e) {
                throw new InvalidOperationException("setSubPreviewCallback fail", e);
            }
            return applyRepeatingKey(REPEATING_KEY_SUB_PREVIEW_CALLBACK, null != callback);
        } else {
            enableRepeatingKey(REPEATING_KEY_SUB_PREVIEW_CALLBACK, null != callback);
            return -1;
        }
    }

    /**
     * <div class="camera_en">
     * send PictureData with callback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * picture data 를 callback 을 통해 전달한다.
     * </div>
     *
     * @param tag             tag
     * @param pictureCallback PictureCallback
     * @param data            ImageBuffer
     * @param extraBundle     extraBundle
     */
    protected void sendPictureTakenCallback(String tag, PictureCallback pictureCallback, ImageBuffer data, @NonNull ExtraBundle extraBundle) {
        sendCaptureAvailable(data.getImageInfo().getCaptureMetadata());
        PictureCallbackHelper.onPictureTaken(tag, pictureCallback, data, extraBundle, mCamDevice);
    }

    /**
     * <div class="camera_en">
     * Set RawPictureCallback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * RawPictureCallback 을 설정 한다.
     * </div>
     *
     * @param callback RawPictureCallback's instance.
     * @param handler  For using separate handler.
     */
    @Override
    public void setRawPictureCallback(@Nullable RawPictureCallback callback, @Nullable Handler handler) {
        CLog.v(getMakerTag(), "setRawPictureCallback(%s)", Integer.toHexString(System.identityHashCode(callback)));
        mRawPictureCallback = RawPictureCallbackForwarder.newInstance(callback,
                Optional.ofNullable(handler).orElse(getEventHandler()));
    }

    /**
     * <div class="camera_en">
     * Set ThumbnailCallback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ThumbnailCallback 을 설정 한다.
     * </div>
     *
     * @param callback ThumbnailCallback's instance.
     * @param handler  For using separate handler.
     */
    @Override
    public void setThumbnailCallback(@Nullable ThumbnailCallback callback, @Nullable Handler handler) {
        CLog.v(getMakerTag(), "setThumbnailCallback(%s)", Integer.toHexString(System.identityHashCode(callback)));
        mThumbnailCallback = ThumbnailCallbackForwarder.newInstance(callback,
                Optional.ofNullable(handler).orElse(getEventHandler()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onFirstPrevRepeatingReqApplied(int sequenceId) {
        CLog.i(getMakerTag(), "onFirstPrevRepeatingReqApplied : " + sequenceId);
    }

    /**
     * <div class="camera_en">
     * Sets the target picture size in the capture request options for seamless ratio transition.
     * This method configures the appropriate picture size based on the target picture size info
     * and validates that the target size fits within the compStream dimensions.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * requestOptionsBuilder 에 {@link SemCaptureRequest#SCALER_TARGET_PICTURE_SIZE}를 넣는다.
     * compStream size가 target picture size 보다 작거나 맞지 않으면 InvalidOperationException 던진다.
     * </div>
     *
     * @param camCapability Camera capability
     * @param requestOptionsBuilder Builder for constructing camera device request options
     * @param targetComPicType The type of compressed picture for which to set the target size
     *
     * @throws InvalidOperationException if the target picture size does not fit within the compStream picture size boundaries
     */
    protected void setTargetPictureSize(@NonNull CamCapability camCapability,
                                        @NonNull CamDeviceRequestOptions.Builder requestOptionsBuilder,
                                        @NonNull PictureDataInfo.PicType targetComPicType) {
        if (camCapability.getSamsungFeatureSeamlessRatioTransitionAvailable() && Objects.nonNull(mTargetPictureSizeInfo)) {
            final Size compStreamPictureSize = Objects.requireNonNull(mMakerPicStreamConfig.getSize(targetComPicType, COMP));
            final Size targetPictureSize = mTargetPictureSizeInfo.getSize(targetComPicType);
            if (!SizeUtils.fitsIn(compStreamPictureSize, targetPictureSize)) {
                throw new InvalidOperationException("targetPictureSize(" + targetPictureSize + ") does not fit within compStreamPictureSize(" + compStreamPictureSize + ")");
            }
            CLog.i(getMakerTag(), "setTargetPictureSize - targetPictureSize: " + targetPictureSize + ", compStreamPictureSize: " + compStreamPictureSize);
            requestOptionsBuilder.put(SemCaptureRequest.SCALER_TARGET_PICTURE_SIZE, targetPictureSize);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException      {@inheritDoc}
     * @throws IllegalStateException         {@inheritDoc}
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws InvalidOperationException     {@inheritDoc}
     */
    @Override
    synchronized public int setPrivateCommand(@NonNull MakerPrivateCommand privateCommand) {
        CLog.v(getMakerTag(), "setPrivateCommand - %s", privateCommand);

        ConditionChecker.checkNotNull(privateCommand, "privateCommand");

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        return setPrivateCommandInternal(privateCommand);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException {@inheritDoc}
     */
    @Nullable
    @Override
    synchronized public <T> T getPublicSetting(@NonNull CaptureRequest.Key<T> key) {
        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);
        return getPublicSettingInternal(key);
    }

    @Nullable
    synchronized private <T> T getPublicSettingInternal(@NonNull CaptureRequest.Key<T> key) {
        if (null == mCamDevice) {
            return null;
        }

        CaptureRequest.Builder builder = null;
        for (Map.Entry<Pair<String, Set<String>>, CaptureRequest.Builder> mapEntry :
                mPictureRequestBuilderMap.entrySet()) {
            if (Objects.equals(mapEntry.getKey().first, mCamDevice.getId())) {
                builder = mapEntry.getValue();
                break;
            }
        }
        if (null == builder) {
            return null;
        }

        return SemCaptureRequest.get(builder, key);
    }

    /**
     * {@inheritDoc}
     */
    @CallSuper
    @NonNull
    @Override
    protected HashMap<MakerPrivateKey<?>, PrivateKeyExecutor<Object>> getSupportedPrivateKeyExecutorMap() {
        if (null == mSupportedPrivateKeyExecutorMap) {
            mSupportedPrivateKeyExecutorMap = super.getSupportedPrivateKeyExecutorMap();
            mSupportedPrivateKeyExecutorMap.put(MakerPrivateKey.ENABLE_WATERMARK, (value) -> mIsWatermarkEnable = (Boolean) value);
            mSupportedPrivateKeyExecutorMap.put(MakerPrivateKey.TARGET_PICTURE_SIZE_INFO, (value) -> mTargetPictureSizeInfo = (TargetPictureSizeInfo) value);
            mSupportedPrivateKeyExecutorMap.put(MakerPrivateKey.WATERMARK_TYPE, (value) -> mWatermarkType = (Watermark.WatermarkType) value);
        }
        return mSupportedPrivateKeyExecutorMap;
    }

    /**
     * {@inheritDoc}
     */
    @CallSuper
    @NonNull
    @Override
    protected HashMap<MakerPrivateKey<?>, ApplyRepeatingKeyExecutor<Object, Integer>> getSupportedRepeatingKeyExecutorMap() {
        if (null == mSupportedRepeatingKeyExecutorMap) {
            mSupportedRepeatingKeyExecutorMap = super.getSupportedRepeatingKeyExecutorMap();
            mSupportedRepeatingKeyExecutorMap.put(MakerPrivateKey.FIRST_EXTRA_SURFACE_UPDATING_RATE, value -> setFirstExtraSurfaceUpdateRate((FrameRate) value));
            mSupportedRepeatingKeyExecutorMap.put(MakerPrivateKey.SECOND_EXTRA_SURFACE_UPDATING_RATE, value -> setSecondExtraSurfaceUpdateRate((FrameRate) value));
            mSupportedRepeatingKeyExecutorMap.put(MakerPrivateKey.MIRROR_SURFACE_UPDATING_RATE, (value) -> setMirrorSurfaceUpdateRate((FrameRate) value));
        }
        return mSupportedRepeatingKeyExecutorMap;
    }

    @Override
    protected void onCamDeviceConnectFailed() {
        CLog.v(getMakerTag(), "onCamDeviceConnectFailed");
    }

    @Override
    protected void onCamDeviceConnected() {
        CLog.v(getMakerTag(), "onCamDeviceConnected");
    }

    @Override
    protected void onCamDeviceDisconnected() {
        CLog.v(getMakerTag(), "onCamDeviceDisconnected");
    }

    @Override
    protected void onCamDeviceClosed() {
        CLog.v(getMakerTag(), "onCamDeviceClosed");
    }

    @SuppressWarnings("WeakerAccess")
    protected void preparePrivateSurfaces(@Nullable CamCapability camCapability) {
        if (null == camCapability) {
            return;
        }
        CLog.i(getMakerTag(), "preparePrivateSurfaces");

        // In case of reconnecting, we can guarantee image of previous previewCallback can't be written to new privatePreviewSurface
        // if this code is after camDevice.createCaptureSession(because old preview imageReader will close and callback will be null in this method).
        // don't move this code before camDevice.createCaptureSession.
        if (null != getMainPreviewSurface()) {
            try {
                mPrivatePreviewSurface = createPrivatePreviewSurface(getMainPreviewSurface(), camCapability);
            } catch (Exception e) {
                joinInitializeMakerThread();
                releaseMaker(camCapability);
                throw new InvalidOperationException("createPrivatePreviewSurface for previewSurface fail", e);
            }
        } else {
            mPrivatePreviewSurface = null;
        }

        if (null != getFirstExtraPreviewSurface()) {
            try {
                mFirstPrivateExtraPreviewSurface = createPrivatePreviewSurface(getFirstExtraPreviewSurface(), camCapability);
            } catch (Exception e) {
                joinInitializeMakerThread();
                releaseMaker(camCapability);
                throw new InvalidOperationException("createPrivatePreviewSurface for first extraPreviewSurface fail", e);
            }
        } else {
            mFirstPrivateExtraPreviewSurface = null;
        }

        if (null != getSecondExtraPreviewSurface()) {
            try {
                mSecondPrivateExtraPreviewSurface = createPrivatePreviewSurface(getSecondExtraPreviewSurface(), camCapability);
            } catch (Exception e) {
                joinInitializeMakerThread();
                releaseMaker(camCapability);
                throw new InvalidOperationException("createPrivatePreviewSurface for second extraPreviewSurface fail", e);
            }
        } else {
            mSecondPrivateExtraPreviewSurface = null;
        }
    }

    @Override
    protected void initializeMaker(@NonNull CamCapability camCapability) {
        CLog.i(getMakerTag(), "initializeMaker");
    }

    @CallSuper
    @Override
    protected void releaseMaker(@NonNull CamCapability camCapability) {
        CLog.i(getMakerTag(), "releaseMaker");

        mPrivatePreviewSurface = null;
        mFirstPrivateExtraPreviewSurface = null;
        mSecondPrivateExtraPreviewSurface = null;

        mIsWatermarkEnable = false;
        mRepeatingModeManager.reset();
    }

    @SuppressWarnings("WeakerAccess")
    protected CamDevice.PreviewStateCallback getCamDevicePreviewStateCallback() {
        return mCamDevicePreviewStateCallback;
    }

    protected CamDevice.SessionStateCallback getCamDeviceSessionStateCallback() {
        return mCamDeviceSessionStateCallback;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMakerType() {
        return MAKER_TYPE_PHOTO;
    }

    /**
     * <div class="camera_en">
     * A class represent to manage repeating mode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Repeating mode 를 관리하는 클래스.
     * </div>
     */
    protected static class PhotoMakerRepeatingModeManager extends MakerRepeatingModeManager {
        // MAIN_PREVIEW_CALLBACK keys
        protected static final RepeatingKey REPEATING_KEY_QR_CODE_DETECTION = new RepeatingKey("QR_CODE_DETECTION", RepeatingMode.MAIN_PREVIEW_CALLBACK);
        protected static final RepeatingKey REPEATING_KEY_SW_FACE_DETECTION = new RepeatingKey("SW_FACE_DETECTION", RepeatingMode.MAIN_PREVIEW_CALLBACK);
        protected static final RepeatingKey REPEATING_KEY_PALM_DETECTION = new RepeatingKey("PALM_DETECTION", RepeatingMode.MAIN_PREVIEW_CALLBACK);
        protected static final RepeatingKey REPEATING_KEY_FOOD_MAKER = new RepeatingKey("FOOD_MAKER", RepeatingMode.MAIN_PREVIEW_CALLBACK);
        protected static final RepeatingKey REPEATING_KEY_STITCHING_MAKER = new RepeatingKey("STITCHING_MAKER", RepeatingMode.MAIN_PREVIEW_CALLBACK);
        protected static final RepeatingKey REPEATING_KEY_SCENE_DETECTION = new RepeatingKey("SCENE_DETECTION", RepeatingMode.MAIN_PREVIEW_CALLBACK);
        protected static final RepeatingKey REPEATING_KEY_COMPOSITION_GUIDE = new RepeatingKey("COMPOSITION_GUIDE", RepeatingMode.MAIN_PREVIEW_CALLBACK);
        protected static final RepeatingKey REPEATING_KEY_LABS_CAPTURE_MODE = new RepeatingKey("LABS_CAPTURE_MODE", RepeatingMode.MAIN_PREVIEW_CALLBACK);

        public PhotoMakerRepeatingModeManager(@NonNull String userTag) {
            super(userTag);
        }
    }
}
