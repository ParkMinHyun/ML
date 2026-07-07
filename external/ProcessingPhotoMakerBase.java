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

import static com.samsung.android.camera.core2.PublicMetadata.CONTROL_DS_CONDITION_PRESET_SINGLE_REMOSAIC;
import static com.samsung.android.camera.core2.PublicMetadata.CONTROL_DS_EXTRA_INFO_NONE;
import static com.samsung.android.camera.core2.PublicMetadata.CONTROL_DS_MODE_SINGLE;
import static com.samsung.android.camera.core2.callback.helper.CallbackHelper.PictureCallbackHelper;
import static com.samsung.android.camera.core2.callback.helper.CallbackHelper.PostProcessorStatusCallbackHelper;
import static com.samsung.android.camera.core2.callback.helper.CallbackHelper.ThumbnailCallbackHelper;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicFormat.COMP;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicFormat.RAW;
import static com.samsung.android.camera.core2.container.PictureDataInfo.PicFormat.UN_COMP;

import android.content.Context;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.Face;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Size;

import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.primitives.Floats;
import com.samsung.android.camera.core2.CamCapability;
import com.samsung.android.camera.core2.CamDevice;
import com.samsung.android.camera.core2.CamDeviceRequestOptions;
import com.samsung.android.camera.core2.MakerPrivateKey;
import com.samsung.android.camera.core2.PrivateMetadata;
import com.samsung.android.camera.core2.PublicMetadata;
import com.samsung.android.camera.core2.callback.DynamicShotInfoCallback;
import com.samsung.android.camera.core2.callback.PictureCallback;
import com.samsung.android.camera.core2.container.CaptureExtraInfo;
import com.samsung.android.camera.core2.container.CaptureIndexInfo;
import com.samsung.android.camera.core2.container.DeviceConfiguration;
import com.samsung.android.camera.core2.container.DynamicShotInfo;
import com.samsung.android.camera.core2.container.DynamicShotMode;
import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.FilterInfo;
import com.samsung.android.camera.core2.container.PictureDataInfo.PicType;
import com.samsung.android.camera.core2.container.ProcessType;
import com.samsung.android.camera.core2.exception.CamAccessException;
import com.samsung.android.camera.core2.exception.CamDeviceException;
import com.samsung.android.camera.core2.exception.InvalidOperationException;
import com.samsung.android.camera.core2.local.vendorkey.CaptureMetadata;
import com.samsung.android.camera.core2.local.vendorkey.SemCameraMetadata;
import com.samsung.android.camera.core2.local.vendorkey.SemCaptureRequest;
import com.samsung.android.camera.core2.local.vendorkey.SemCaptureResult;
import com.samsung.android.camera.core2.maker.MakerUtils.CamDeviceSessionState;
import com.samsung.android.camera.core2.ml.CaptureMetrics;
import com.samsung.android.camera.core2.node.NodeFeatureUtil;
import com.samsung.android.camera.core2.node.watermark.WatermarkNode;
import com.samsung.android.camera.core2.processor.MotionPhotoManager;
import com.samsung.android.camera.core2.processor.PictureProcessorManager;
import com.samsung.android.camera.core2.processor.ProcessResult;
import com.samsung.android.camera.core2.processor.ProcessorManagerInterface;
import com.samsung.android.camera.core2.processor.container.NodeChainKeyContainer;
import com.samsung.android.camera.core2.processor.request.ProcessRequest;
import com.samsung.android.camera.core2.processor.request.ProcessRequestImpl;
import com.samsung.android.camera.core2.util.BasketCollector;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.ConditionChecker;
import com.samsung.android.camera.core2.util.DynamicShotUtils;
import com.samsung.android.camera.core2.util.FileUtils;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.SemImageFormat;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * <div class="camera_en">
 * ProcessingPhotoMakerBase.
 * {@link WatermarkNode}
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * ProcessingPhotoMakerBase.
 * {@link WatermarkNode}
 * </div>
 */
abstract class ProcessingPhotoMakerBase extends PhotoMakerBase {

    private static final String PRIVATE_TAG = "ProcessingPhotoMakerBase";

    private final String PROCESSING_PHOTO_TAG = getMakerTag();

    protected ConcurrentHashMap<MakerPrivateKey<?>, Object> mMakerPrivateKeys = new ConcurrentHashMap<>();
    protected ConcurrentHashMap<Integer, BasketCollector> mBasketCollectorMap = new ConcurrentHashMap<>();

    protected ProcessRequest.Sequence<ImageBuffer> mLatestSequence;

    protected boolean mIsIPPCapturing;
    protected boolean mIsMotionPhotoPppEnabled;

    protected FilterInfo mFilterInfo;

    protected JSONObject mWatermarkConfig;

    protected boolean mIsWideDistortionEnable;
    protected Float mWideDistortionMaxZoomRatio;

    protected boolean mDelayedShutter;

    private final ProcessorManagerInterface.ImmediateProcessCallback mImmediateProcessCallback
            = new ProcessorManagerInterface.ImmediateProcessCallback() {

        @Override
        public void onProcessError(int ppSequenceId) {
            final ProcessRequest.Sequence<ImageBuffer> sequence = mLatestSequence;
            if (null == sequence) {
                CLog.w(PROCESSING_PHOTO_TAG, "onProcessError : Process Sequence(ppSequenceId:%d) is null in sequence map", ppSequenceId);
                return;
            }

            if (sequence.getId() == ppSequenceId) {
                sequence.errorRequest(CaptureFailure.REASON_ERROR,
                        String.format(Locale.UK, "%s : getting IPP onProcessError ppSequenceId %d", getMakerTag(), ppSequenceId));
            }

            if (!NodeChainKeyContainer.isSupportIncompleteMerge(sequence.getDsMode())) {
                PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
            }
            mIsIPPCapturing = false;
        }

        @Override
        public void onProcessProgress(@NonNull ProcessResult<ImageBuffer> processResult, int progress) {
            // do nothing now
        }

        @Override
        public void onProcessCompleted(@NonNull ProcessResult<ImageBuffer> processResult) {
            final ExtraBundle extraBundle = processResult.extraBundle();

            final CamCapability camCapability = Optional.ofNullable(extraBundle.get(ExtraBundle.INFO_CAMCAPABILITY)).orElseGet(() -> mCamDevice.getCamCapability());

            mCamDevicePictureCallback.onPictureTaken(processResult.data(), extraBundle, camCapability, /*hasThumbnailImage*/false);
            extraBundle.release();
            mIsIPPCapturing = false;
        }
    };

    private final ProcessorManagerInterface.PostProcessCallback mPostProcessCallback
            = new ProcessorManagerInterface.PostProcessCallback() {

        @Override
        public void onProcessError(int ppSequenceId) {
            final int sequenceId = Optional.ofNullable(mCamDevice).map(camDevice -> camDevice.getSequenceId(ppSequenceId)).orElse(-1);
            PictureCallbackHelper.onPostProcessingError(PROCESSING_PHOTO_TAG, mPictureCallback, sequenceId, mCamDevice);
        }

        @Override
        public void onDraftPictureSaved(int ppSequenceId, @Nullable Uri[] uris, @NonNull File[] resultFiles) {
            final int sequenceId = Optional.ofNullable(mCamDevice).map(camDevice -> camDevice.getSequenceId(ppSequenceId)).orElse(-1);
            PictureCallbackHelper.onDraftPostProcessingPictureTaken(PROCESSING_PHOTO_TAG, mPictureCallback, sequenceId, uris, resultFiles, mCamDevice);
        }

        @Override
        public void onRequestCollectionCompleted(int ppSequenceId) {
            final int sequenceId = Optional.ofNullable(mCamDevice).map(camDevice -> camDevice.getSequenceId(ppSequenceId)).orElse(-1);
            sendCaptureAvailableImmediately(sequenceId,
                    /*timestamp*/0L,
                    () -> PictureCallbackHelper.onPostProcessingFrameCollectionCompleted(PROCESSING_PHOTO_TAG, mPictureCallback, sequenceId, mCamDevice));
        }

        @Override
        public void onRequestCollectionStopped(int ppSequenceId) {
            final int sequenceId = Optional.ofNullable(mCamDevice).map(camDevice -> camDevice.getSequenceId(ppSequenceId)).orElse(-1);
            sendCaptureAvailableImmediately(sequenceId,
                    /*timestamp*/0L,
                    () -> PictureCallbackHelper.onPostProcessingFrameCollectionStopped(PROCESSING_PHOTO_TAG, mPictureCallback, sequenceId, mCamDevice));
        }

        @Override
        public void onProcessProgress(@NonNull ProcessResult<ImageBuffer> processResult, int progress) {
            // do nothing now
        }

        @Override
        public void onProcessCompleted(@NonNull ProcessResult<ImageBuffer> processResult, @Nullable File resultFile) {
            final int sequenceId = Optional.ofNullable(mCamDevice).map(camDevice -> camDevice.getSequenceId(processResult.ppSequenceId())).orElse(-1);
            PictureCallbackHelper.onPostProcessingPictureTaken(PROCESSING_PHOTO_TAG, mPictureCallback, sequenceId, resultFile, mCamDevice);
        }

        @Override
        public void onRequestCollectionCompletedInSequenceApprovalState(int ppSequenceId) {
            final int sequenceId = Optional.ofNullable(mCamDevice)
                    .map(camDevice -> camDevice.getSequenceId(ppSequenceId))
                    .orElse(-1);
            sendCaptureAvailable(sequenceId, /*timestamp*/0L, null);
        }

        @Override
        public void onRequestCollectionStoppedInSequenceApprovalState(int ppSequenceId) {
            final int sequenceId = Optional.ofNullable(mCamDevice)
                    .map(camDevice -> camDevice.getSequenceId(ppSequenceId))
                    .orElse(-1);
            sendCaptureAvailable(sequenceId, /*timestamp*/0L, null);
        }
    };

    private final ProcessorManagerInterface.PppStatusCallback mPppStatusCallback
            = new ProcessorManagerInterface.PppStatusCallback() {
        @Override
        public void onPostProcessorSequenceCountChanged(int activatedSequenceCount, int pendingSequenceCount) {
            PostProcessorStatusCallbackHelper.onPostProcessorSequenceCountChanged(
                    PROCESSING_PHOTO_TAG,
                    mMakerCallbackManager.getCallback(MakerCallbackType.POST_PROCESSOR_STATUS_CALLBACK),
                    activatedSequenceCount,
                    pendingSequenceCount,
                    mCamDevice);
        }

        @Override
        public void onPostProcessorEnded() {
            // do nothing now
        }
    };

    private class MultiPictureCallback implements CamDevice.MultiPictureCallback {
        @Override
        public void onError(@Nullable ProcessRequest.Sequence<ImageBuffer> sequence, @NonNull CaptureFailure failure, int index, int totalCount) {
            CLog.e(PROCESSING_PHOTO_TAG, "MultiPictureCallback onError - reason %d", failure.getReason());

            if (null == sequence) {
                mIsIPPCapturing = false;
                // invoking new takePostProcessingPicture after error occurred, but this callback was triggered by old takePostProcessingPicture.
                CLog.e(PROCESSING_PHOTO_TAG, "MultiPictureCallback onError - Process Sequence(sequenceId:%d) is null in sequence map",
                        /*sequenceId*/failure.getSequenceId());
                return;
            }

            mIsIPPCapturing = false;
            final ProcessRequest<ImageBuffer> errorRequest = sequence.errorRequest(failure.getReason(),
                    String.format(Locale.UK, "%s : getting onError sequenceId %d, frameNumber %d, request %d/%d, reason %d",
                            getMakerTag(), /*sequenceId*/failure.getSequenceId(), failure.getFrameNumber(), index + 1, totalCount, failure.getReason()));
            Optional.ofNullable(errorRequest).ifPresent(request -> {
                PictureProcessorManager.getInstance().process(request, mContext);
                if (!NodeChainKeyContainer.isSupportIncompleteMerge(sequence.getDsMode())
                        || (NodeChainKeyContainer.isSupportIncompleteMerge(sequence.getDsMode())
                        && (index == 0/*draft*/ || failure.getReason() == CaptureFailure.REASON_FLUSHED))) {
                    PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, failure.getReason(), mCamDevice);
                }
            });
        }

        @Override
        public void onPictureDepth(@Nullable ProcessRequest.Sequence<ImageBuffer> sequence, @NonNull ImageBuffer depthData, @NonNull CamCapability camCapability) {
            CLog.i(PROCESSING_PHOTO_TAG, "MultiPictureCallback onPictureDepth - depthData : %s, format : %s", depthData, depthData.getImageInfo().getFormat());

            final ImageInfo depthDataImageInfo = depthData.getImageInfo();
            final CaptureMetadata captureMetadata = depthDataImageInfo.getCaptureMetadata();
            ConditionChecker.checkNotNull(captureMetadata, "captureMetadata");

            mTakePictureRequestLock.lock();
            try {
                if (null == sequence) {
                    // invoking new takePostProcessingPicture before sequence isn't done, but this callback was triggered by old takePostProcessingPicture.
                    CLog.e(PROCESSING_PHOTO_TAG, "MultiPictureCallback onPictureDepth - Process Sequence(sequenceId:%d) is null in sequence map",
                            captureMetadata.getSequenceId());
                    PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
                    return;
                }
            } finally {
                mTakePictureRequestLock.unlock();
            }

            if (depthDataImageInfo.getFormat() == SemImageFormat.DEPTH16) {
                if (mPictureProcessLock.lockIfFlagEnabled()) {
                    try {
                        processWithBasketCollector(sequence, depthData, camCapability);
                    } finally {
                        mPictureProcessLock.unlock();
                    }
                } else {
                    CLog.e(PROCESSING_PHOTO_TAG, "MultiPictureCallback onPictureDepth - pictureProcess is not enabled");
                    final ProcessRequest<ImageBuffer> errorRequest = sequence.errorRequest(CaptureFailure.REASON_ERROR,
                            String.format(Locale.UK, "%s : maker was disconnected but getting image(format %s) from onPictureDepth",
                                    getMakerTag(), depthDataImageInfo.getFormat()));
                    Optional.ofNullable(errorRequest).ifPresent(request -> {
                        PictureProcessorManager.getInstance().process(request, mContext);
                        PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
                    });
                }
            } else {
                CLog.w(PROCESSING_PHOTO_TAG, "unsupported format(" + depthData.getImageInfo().getFormat() + ").");
            }
        }

        @Override
        public void onPictureSequenceCompleted(int sequenceId, long frameNumber) { }

        @Override
        public void onPictureTaken(@Nullable ProcessRequest.Sequence<ImageBuffer> sequence,
                                   @NonNull ImageBuffer pictureData,
                                   @NonNull CamCapability camCapability,
                                   boolean hasThumbnailImage,
                                   int requestIndex,
                                   int requestListSize) {
            CLog.i(PROCESSING_PHOTO_TAG, "MultiPictureCallback onPictureTaken - pictureData %s\n hasThumbnailImage %b, requestIndex %d, requestListSize %d",
                    pictureData, hasThumbnailImage, requestIndex, requestListSize);
            final ImageInfo pictureDataImageInfo = pictureData.getImageInfo();
            final CaptureMetadata captureMetadata = pictureDataImageInfo.getCaptureMetadata();
            ConditionChecker.checkNotNull(captureMetadata, "captureMetadata");

            if (null == sequence) {
                // invoking new takePostProcessingPicture before sequence isn't done, but this callback was triggered by old takePostProcessingPicture.
                CLog.e(PROCESSING_PHOTO_TAG, "MultiPictureCallback onPictureTaken - Sequence(sequenceId:%d) is null in sequence map", captureMetadata.getSequenceId());
                PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
                return;
            }

            final SemImageFormat imageFormat = pictureDataImageInfo.getFormat();
            if (SemImageFormat.isJpegFormat(imageFormat)) {
                handleDraftRequest(sequence, pictureData, camCapability);
            } else if (SemImageFormat.isRawFormat(imageFormat)) {
                handleResourceRequest(sequence, pictureData, camCapability, pictureDataImageInfo);
            } else if (imageFormat == SemImageFormat.YUV_420_888 ||  imageFormat == SemImageFormat.YCBCR_P010 ) {
                if (handleDraftRequest(sequence, pictureData, camCapability)) {
                    if (!sequence.isResourceHandlingNeeded(pictureData.getImageInfo())) {
                        return;
                    }
                }
                handleResourceRequest(sequence, pictureData, camCapability, pictureDataImageInfo);
            } else {
                CLog.w(PROCESSING_PHOTO_TAG, "unsupported format(" + pictureDataImageInfo.getFormat() + ").");
            }
        }

        @Override
        public void onShutter(int sequenceId, @Nullable Long timeStamp) {
            final CamDevice camDevice = mCamDevice;
            if (null == camDevice) {
                CLog.w(PROCESSING_PHOTO_TAG, "onShutter error - CamDevice is already closed");
                return;
            }

            // NOTE : DelayedShutter will be played in concrete maker.
            if (mDelayedShutter && mIsIPPCapturing) {
                CLog.i(PROCESSING_PHOTO_TAG, "onShutter skip");
            } else {
                Optional.ofNullable(mLatestSequence)
                        .map(sequence -> sequence.get(ExtraBundle.DATA_CAPTURE_METRICS))
                        .ifPresent(captureMetrics -> captureMetrics.setTimeoutTimestampMs(SystemClock.uptimeMillis() + MakerFeature.CAPTURE_TIMEOUT_MS));
                PictureCallbackHelper.onShutter(PROCESSING_PHOTO_TAG, mPictureCallback, sequenceId, timeStamp, camDevice);
            }

            if (!mIsIPPCapturing && mIsMotionPhotoPppEnabled) {
                storeMotionPhotoPpp(timeStamp, camDevice);
            }
        }

        @Override
        public void onCaptureAvailable(int sequenceId, @Nullable Long timeStamp) {
            sendCaptureAvailableFromHAL(sequenceId, timeStamp);
        }

        private void storeMotionPhotoPpp(@Nullable Long timeStamp, @NonNull CamDevice camDevice) {
            CLog.i(PROCESSING_PHOTO_TAG, "storeMotionPhotoPpp E");
            final ProcessRequest.Sequence<ImageBuffer> sequence = mLatestSequence;
            if (null == sequence) {
                CLog.w(PROCESSING_PHOTO_TAG, "storeMotionPhotoPpp X : failed - Process Sequence is null in sequence map");
                return;
            }

            final int jpegOrientation = getJpegOrientation(camDevice);
            final long motionPhotoTimestamp = calculateMotionPhotoTimestamp(timeStamp);
            final Location jpegGpsLocation = SemCaptureRequest.get(mPictureRequestBuilderMap, camDevice.getId(), CaptureRequest.JPEG_GPS_LOCATION);

            final CaptureResult latestTotalCaptureResult = getLatestRepeatingCaptureResult();
            final int textDetectionInfo = Optional.ofNullable(SemCaptureResult.get(latestTotalCaptureResult, SemCaptureResult.CONTROL_TEXT_DETECTION_INFO))
                    .orElse(PublicMetadata.CONTROL_TEXT_DETECTION_INFO_DETECTED_NONE);
            final Face[] faces = SemCaptureResult.get(latestTotalCaptureResult, CaptureResult.STATISTICS_FACES);

            PictureProcessorManager.getInstance().storeMotionPhotoPpp(
                    new MotionPhotoManager.MotionPhotoStoreInfo(
                            mContext,
                            sequence.getId(),
                            motionPhotoTimestamp,
                            jpegOrientation,
                            (null != faces && faces.length > 0),
                            textDetectionInfo,
                            sequence.get(ExtraBundle.PROCESSOR_INFO_PPP_RECOVERY_DATA_FILE_NAME),
                            jpegGpsLocation,
                            mFilterInfo,
                            mWatermarkConfig)
                    );
            CLog.i(PROCESSING_PHOTO_TAG, "storeMotionPhotoPpp X : timestamp for motion photo: %d(ms)", motionPhotoTimestamp);
        }

        private long calculateMotionPhotoTimestamp(@Nullable Long timeStamp) {
            // Offset for converting SYSTEM_TIME_BOOT_TIME to SYSTEM_TIME_MONOTONIC
            final long timeStampOffset = (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()) * 1000;
            final long halTimeStamp = Optional.ofNullable(timeStamp).orElse(0L) / 1000;
            CLog.i(PROCESSING_PHOTO_TAG, "calculateMotionPhotoTimestamp : halTimeStamp: %d(ms), timeStampOffset: %d(ms)", halTimeStamp, timeStampOffset);

            return (halTimeStamp > 0L) ? (halTimeStamp - timeStampOffset) : 0L;
        }

        private int getJpegOrientation(@NonNull CamDevice camDevice) {
            Integer jpegOrientation = SemCaptureRequest.get(mPictureRequestBuilderMap, camDevice.getId(), CaptureRequest.JPEG_ORIENTATION);
            if (null == jpegOrientation) {
                CLog.e(PROCESSING_PHOTO_TAG, "getJpegOrientation error - can't get jpeg orientation");
                jpegOrientation = 0;
            }

            final int flipMode = Optional.ofNullable(SemCaptureRequest.get(mPictureRequestBuilderMap, camDevice.getId(), SemCaptureRequest.SCALER_FLIP_MODE))
                    .orElse(SemCaptureRequest.SCALER_FLIP_MODE_NONE);
            final int lensFacing = Optional.ofNullable(camDevice.getCamCapability().getLensFacing()).orElse(Integer.MIN_VALUE);
            if (((flipMode ^ SemCaptureRequest.SCALER_FLIP_MODE_VERTICAL) == 0) && Objects.equals(lensFacing, PublicMetadata.LENS_FACING_BACK)) {
                jpegOrientation = (jpegOrientation + 180) % 360;
            }

            return jpegOrientation;
        }
    }

    private class ThumbnailCallback implements CamDevice.ThumbnailCallback {
        @Override
        public void onThumbnailTaken(@NonNull ImageBuffer thumbnailData, @NonNull CamCapability camCapability) {
            if (ProcessingPhotoMakerBase.this.isDraftThumbnail(thumbnailData, camCapability)) {
                ThumbnailCallbackHelper.onDraftThumbnailTaken(PROCESSING_PHOTO_TAG, mThumbnailCallback, thumbnailData, mCamDevice);
            } else {
                ThumbnailCallbackHelper.onThumbnailTaken(PROCESSING_PHOTO_TAG, mThumbnailCallback, thumbnailData, mCamDevice);
            }
        }
    }

    /**
     * <div class="camera_en">
     * Constructor of ProcessingPhotoMakerBase class.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ProcessingPhotoMakerBase class 의 생성자.
     * </div>
     *
     * @param keyClass key class of maker.
     * @param context application context.
     * @param handler event callback handler.
     */
    protected ProcessingPhotoMakerBase(@NonNull Class<?> keyClass, @NonNull Context context, @Nullable Handler handler) {
        super(keyClass, context, handler);

        mCamDeviceMultiPictureCallback = new MultiPictureCallback();
        mCamDeviceThumbnailCallback = new ThumbnailCallback();
    }

    private void handleResourceRequest(@NonNull ProcessRequest.Sequence<ImageBuffer> sequence, @NonNull ImageBuffer pictureData, @NonNull CamCapability camCapability, ImageInfo pictureDataImageInfo) {
        CLog.i(PROCESSING_PHOTO_TAG, "handleResourceRequest E");
        if (mPictureProcessLock.lockIfFlagEnabled()) {
            CLog.i(PROCESSING_PHOTO_TAG, "MultiPictureCallback onPictureTaken - CurrentProcessCount=%d, TotalProcessCount=%d",
                    (sequence.getCurrentProcessCount() + 1), sequence.getTotalProcessCount());
            PictureCallbackHelper.onProcessingFrameCollected(PROCESSING_PHOTO_TAG, mPictureCallback, mCamDevice.getSequenceId(sequence.getId()),
                    (int) (((float) (sequence.getCurrentProcessCount() + 1) / sequence.getTotalProcessCount()) * 100), mCamDevice);

            try {
                if (!processWithBasketCollector(sequence, pictureData, camCapability)) {
                    final ProcessRequest<ImageBuffer> request = sequence.nextRequest(ProcessRequest.Usage.RESOURCE_IMAGE, pictureData, camCapability);
                    if (null == request) {
                        CLog.e(PROCESSING_PHOTO_TAG, "MultiPictureCallback onPictureTaken - nextRequest is null");
                        if (!sequence.isError()) {
                            PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
                        }
                        return;
                    }
                    PictureProcessorManager.getInstance().process(request, mContext);
                }
            } finally {
                mPictureProcessLock.unlock();
            }
        } else {
            CLog.e(PROCESSING_PHOTO_TAG, "MultiPictureCallback onPictureTaken - pictureProcess is not enabled");
            final ProcessRequest<ImageBuffer> errorRequest = sequence.errorRequest(CaptureFailure.REASON_ERROR,
                    String.format(Locale.UK, "%s : maker was disconnected but getting image(format %s) from onPictureTaken",
                            getMakerTag(), pictureDataImageInfo.getFormat()));
            Optional.ofNullable(errorRequest).ifPresent(request -> {
                PictureProcessorManager.getInstance().process(request, mContext);
                PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
            });
            return;
        }
        CLog.i(PROCESSING_PHOTO_TAG, "handleResourceRequest X");
    }

    protected boolean handleDraftRequest(@NonNull ProcessRequest.Sequence<ImageBuffer> sequence, @NonNull ImageBuffer pictureData, @NonNull CamCapability camCapability) {
        final ImageInfo imageInfo = pictureData.getImageInfo();
        final CaptureMetadata captureMetadata = imageInfo.getCaptureMetadata();
        ConditionChecker.checkNotNull(captureMetadata, "captureMetadata");
        CLog.i(PROCESSING_PHOTO_TAG, "handleDraftRequest E - sequenceId(%s), imageComesFrom(%s), format(%s)",
                captureMetadata.getSequenceId(), imageInfo.getImageComesFrom(), imageInfo.getFormat());

        if (!sequence.isNeededForProcessDraft(imageInfo.getImageComesFrom(), imageInfo.getFormat())) {
            CLog.w(PROCESSING_PHOTO_TAG, "handleDraftRequest X - sequenceId(%s) : not needDraftProcess", captureMetadata.getSequenceId());
            return false;
        }

        final ImageInfo pictureDataImageInfo = pictureData.getImageInfo();
        if (mPictureProcessLock.lockIfFlagEnabled()) {
            try {
                final ProcessRequest<ImageBuffer> request = sequence.nextRequest(ProcessRequest.Usage.DRAFT_IMAGE, pictureData, camCapability);
                if (null == request) {
                    CLog.e(PROCESSING_PHOTO_TAG, "handleDraftRequest X - sequenceId(%s) : onPictureTaken - nextRequest is null", captureMetadata.getSequenceId());
                    if (!sequence.isError()) {
                        PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
                    }
                    return false;
                }
                PictureProcessorManager.getInstance().process(request, mContext);
            } finally {
                mPictureProcessLock.unlock();
            }
        } else {
            CLog.e(PROCESSING_PHOTO_TAG, "handleDraftRequest onPictureTaken - pictureProcess is not enabled");
            final ProcessRequest<ImageBuffer> errorRequest = sequence.errorRequest(CaptureFailure.REASON_ERROR,
                    String.format(Locale.UK, "%s : maker was disconnected but getting image(format %s) from onPictureTaken",
                            getMakerTag(), pictureDataImageInfo.getFormat()));
            Optional.ofNullable(errorRequest).ifPresent(request -> {
                PictureProcessorManager.getInstance().process(request, mContext);
                PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
            });
        }
        CLog.i(PROCESSING_PHOTO_TAG, "handleDraftRequest X - sequenceId(%s)", captureMetadata.getSequenceId());
        return true;
    }

    /**
     * <div class="camera_en">
     * Checks if {@param thumbnailData} is draft thumbnail.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@param thumbnailData} 가 draft thumbnail 인지 확인한다.
     * </div>
     *
     * @param thumbnailData thumbnailData.
     * @param camCapability camCapability.
     * @return true, if {@param thumbnailData} is draft thumbnail.
     */
    protected boolean isDraftThumbnail(@NonNull ImageBuffer thumbnailData, @NonNull CamCapability camCapability) {
        if (!camCapability.getSamsungFeatureDynamicShotInfoAvailable()) {
            return false;
        }

        final CaptureMetadata captureMetadata = thumbnailData.getImageInfo().getCaptureMetadata();
        final int dsExtraInfo = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_DYNAMIC_SHOT_EXTRA_INFO))
                .orElse(SemCameraMetadata.CONTROL_DS_EXTRA_INFO_NONE);
        final int dsHint = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_DYNAMIC_SHOT_HINT))
                .orElse(SemCameraMetadata.CONTROL_DS_MODE_SINGLE);
        final int captureHint = Optional.ofNullable(SemCaptureResult.get(captureMetadata, SemCaptureResult.CONTROL_CAPTURE_HINT))
                .orElse(SemCameraMetadata.CONTROL_CAPTURE_HINT_NONE);

        return DynamicShotUtils.isDsProcessingMode(dsHint, dsExtraInfo)
                && !Objects.equals(captureHint, PublicMetadata.CONTROL_CAPTURE_HINT_BURST);
    }

    /**
     * <div class="camera_en">
     * Function to check whether dynamicShotInfo is valid for processingPicture capture.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * DynamicShotInfo 가 processingPicture 촬영에 적합한지 확인하는 함수.
     * </div>
     *
     * @param dynamicShotInfo dynamicShotInfo.
     * @throws IllegalArgumentException If dynamicShotInfo is invalid for processingPicture.
     */
    protected static void checkDynamicShotInfoForProcessingPicture(@NonNull DynamicShotInfo dynamicShotInfo) {
        if (CONTROL_DS_MODE_SINGLE == dynamicShotInfo.getProcessingMode()) {
            throw new IllegalArgumentException("SingleMode is not supported for processingPicture");
        } else if (0 == dynamicShotInfo.getDsCondition() && 0 == dynamicShotInfo.getDsExtraInfo()) {
            throw new IllegalArgumentException("DynamicShotInfo is not proper for processingPicture");
        }
    }

    /**
     * <div class="camera_en">
     * Function to check whether PostProcessorState is valid.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PostProcessorState 가 적합한지 확인하는 함수.
     * </div>
     *
     * @throws IllegalArgumentException If PostProcessorState is invalid.
     */
    protected static void checkPostProcessorState() {
        if (!PictureProcessorManager.getInstance().isPppInitialized()) {
            throw new InvalidOperationException("PostProcessor is not initialized");
        }
    }

    /**
     * <div class="camera_en">
     * Process with BasketCollector
     * When a buffer is inserted into the basketCollector, a map is returned with the basket as a key and item as a value.
     * If there are items in the map, it takes them out and sets them in the sequence, and then obtains and processes the request through the sequence.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * BasketCollector 를 사용하여 sequence 를 process 한다.
     * basketCollector 에 buffer 를 넣으면 basket 을 key 로 하고 item 을 value 로 하는 Map 이 return 된다.
     * 해당 Map 에서 Item 들이 있다면 꺼내와서 sequence 에 set 을 한 뒤에 sequence 를 통해 request 를 얻어와 processing 한다.
     * </div>
     *
     * @param sequence        sequence.
     * @param imageBuffer     imageBuffer.
     * @param camCapability   camCapability.
     */
    protected boolean processWithBasketCollector(@NonNull ProcessRequest.Sequence<ImageBuffer> sequence,
                                                 @NonNull ImageBuffer imageBuffer,
                                                 @NonNull CamCapability camCapability) {
        final BasketCollector basketCollector = mBasketCollectorMap.get(sequence.getId());
        if (null == basketCollector) {
            return false;
        }

        final Map<ImageBuffer, List<BasketCollector.Item>> bufferMap = basketCollector.collect(imageBuffer);
        if (null == bufferMap) {
            CLog.i(PROCESSING_PHOTO_TAG, "processWithBasketCollector - bufferMap is null");
            return true;
        }

        for (Map.Entry<ImageBuffer, List<BasketCollector.Item>> entry : bufferMap.entrySet()) {
            final ImageBuffer buffer = entry.getKey();

            for (BasketCollector.Item item : Objects.requireNonNull(entry.getValue())) {
                if (Objects.equals(item.getKey(), ExtraBundle.MULTI_PICTURE_DATA_DUAL_PIXEL)) {

                    if (DynamicShotUtils.isPendingRequest(sequence.getDsMode(), sequence.getDsExtraInfo())) {
                        final String recoveryDataFileName = sequence.get(ExtraBundle.PROCESSOR_INFO_PPP_RECOVERY_DATA_FILE_NAME);
                        final Path dualPixelFilePath = FileUtils.SECURE_PPP_DIRECTORY_PATH.resolve(recoveryDataFileName).resolve(FileUtils.DUAL_PIXEL_FILE_NAME);
                        CLog.d(PROCESSING_PHOTO_TAG, "dualPixelFilePath" + dualPixelFilePath);

                        if (sequence.setBufferAsSingleUseFile(item.getKey(), item.getData(), dualPixelFilePath)) {
                            item.getData().release();
                        }
                    } else {
                        if (sequence.setBufferAsSingleUseFile(item.getKey(), item.getData())) {
                            item.getData().release();
                        }
                    }
                } else {
                    if (item.getKey() != ExtraBundle.DATA_ORIGINAL_DRAFT || null == sequence.get(item.getKey())) {
                        CLog.i(PROCESSING_PHOTO_TAG, "processWithBasketCollector - set sequence (key:" + item.getKey() + ")");
                        sequence.set(item.getKey(), item.getData());
                    }
                }
            }

            final ProcessRequest<ImageBuffer> request = sequence.nextRequest(ProcessRequest.Usage.RESOURCE_IMAGE, buffer, camCapability);
            if (null == request) {
                CLog.e(PROCESSING_PHOTO_TAG, "MultiPictureCallback onPictureTaken - nextRequest is null");
                if (!sequence.isError()) {
                    PictureCallbackHelper.onError(PROCESSING_PHOTO_TAG, mPictureCallback, CaptureFailure.REASON_ERROR, mCamDevice);
                }
            } else {
                PictureProcessorManager.getInstance().process(request, mContext);
            }

            if (basketCollector.isDone()) {
                basketCollector.release();
                mBasketCollectorMap.remove(sequence.getId());
            }
        }

        return true;
    }

    /**
     * <div class="camera_en">
     * Creates and returns to {@link DynamicShotInfo} for single capture.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Single capture 에 대한 {@link DynamicShotInfo} 로 생성 하여 반환 한다.
     * </div>
     *
     * @param dynamicShotInfo dynamicShotInfo
     * @return single capture dynamicShotInfo.
     */
    protected DynamicShotInfo createSingleCaptureDynamicShotInfo(@NonNull DynamicShotInfo dynamicShotInfo) {
        final int dsMode = dynamicShotInfo.getDsMode();

        if (dsMode == PublicMetadata.CONTROL_DS_MODE_SINGLE) {
            return new DynamicShotInfo(
                    /*processingMode*/0,
                    dynamicShotInfo.getDsCondition(),
                    SemCameraMetadata.CONTROL_DS_EXTRA_INFO_NONE,
                    dynamicShotInfo.getDsDeviceInfo(),
                    dynamicShotInfo.getRunningPhysicalId()
            );
        } else {
            return new DynamicShotInfo(
                    /*processingMode*/0,
                    SemCameraMetadata.CONTROL_DS_MODE_SINGLE,
                    SemCameraMetadata.CONTROL_DS_EXTRA_INFO_NONE,
                    /*dsDeviceInfo*/0,
                    dynamicShotInfo.getRunningPhysicalId()
            );
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException         If cam device is not connected.
     * @throws InvalidOperationException     If takeProcessingPicture is failed internally.
     * @throws UnsupportedOperationException Always throw the exception if Maker does not support this function.
     * @throws CamAccessException            If a CameraAccessException occurs.
     * @see DynamicShotInfoCallback#onDynamicShotInfoChanged(Long, DynamicShotInfo, CamDevice)
     */
    @Override
    synchronized public int takeProcessingPicture(@NonNull DynamicShotInfo dynamicShotInfo, @NonNull CaptureExtraInfo captureExtraInfo) throws CamAccessException {
        CLog.i(PROCESSING_PHOTO_TAG, "takeProcessingPicture - dynamicShotInfo %s, DFovStreamType %s, %s",
                dynamicShotInfo, mDFovStreamType, captureExtraInfo);
        try {
            checkDynamicShotInfoForProcessingPicture(dynamicShotInfo);
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException(e);
        }

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        mWatermarkConfig = captureExtraInfo.getWatermarkConfig();

        final int sequenceId = takeProcessingPictureInternal(dynamicShotInfo, /*postModeFile*/null, /*incompleteFccSequenceCount*/0);
        mIsIPPCapturing = true;

        return sequenceId;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException         If cam device is not connected.
     * @throws InvalidOperationException     If takePostProcessingPicture is failed internally.
     * @throws UnsupportedOperationException Always throw the exception if Maker does not support this function.
     * @throws CamAccessException            If a CameraAccessException occurs.
     * @see DynamicShotInfoCallback#onDynamicShotInfoChanged(Long, DynamicShotInfo, CamDevice)
     */
    @Override
    synchronized public int takePostProcessingPicture(@NonNull DynamicShotInfo dynamicShotInfo,
                                                      @NonNull File[] resultFiles,
                                                      @NonNull CaptureExtraInfo captureExtraInfo) throws CamAccessException {
        CLog.i(PROCESSING_PHOTO_TAG, "takePostProcessingPicture - dynamicShotInfo %s, DFovStreamType %s, %s",
                dynamicShotInfo, mDFovStreamType, captureExtraInfo);
        try {
            ConditionChecker.checkNotNull(resultFiles, "resultFiles");
            checkDynamicShotInfoForProcessingPicture(dynamicShotInfo);
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException(e);
        }
        checkPostProcessorState();

        getCamDeviceSessionState().checkState(CamDeviceSessionState.CONNECTED);

        mWatermarkConfig = captureExtraInfo.getWatermarkConfig();

        return takeProcessingPictureInternal(dynamicShotInfo, resultFiles, captureExtraInfo.getIncompleteFccSequenceCount());
    }

    /**
     * <div class="camera_en">
     * Take the ProcessingPicture.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ProcessingPicture 를 촬영한다.
     * </div>
     *
     * @param dynamicShotInfo               dynamicShotInfo.
     * @param postModeFiles                 If it is not null, Post mode.
     * @param incompleteFccSequenceCount    incompleteFccSequenceCount
     * @throws IllegalStateException         If cam device is not connected.
     * @throws InvalidOperationException     If takeProcessingPictureInternal is failed internally.
     * @throws UnsupportedOperationException Always throw the exception if Maker does not support this function.
     * @throws CamAccessException            If a CameraAccessException occurs.
     * @see DynamicShotInfoCallback#onDynamicShotInfoChanged(Long, DynamicShotInfo, CamDevice)
     */
    protected int takeProcessingPictureInternal(@NonNull DynamicShotInfo dynamicShotInfo,
                                                @Nullable File[] postModeFiles,
                                                int incompleteFccSequenceCount) throws CamAccessException {
        CLog.i(PROCESSING_PHOTO_TAG, "takeProcessingPictureInternal E");

        final CamCapability camCapability = mCamDevice.getCamCapability();
        final List<CamDeviceRequestOptions> camDeviceRequestOptionsList = new ArrayList<>();

        int dsCondition = dynamicShotInfo.getDsCondition();
        final int dsExtraInfo = dynamicShotInfo.getDsExtraInfo();
        final long dsDeviceInfo = dynamicShotInfo.getDsDeviceInfo();
        final int dsMode = DynamicShotUtils.getDsMode(dsCondition);
        final int dsPicCnt = DynamicShotUtils.getDsPicMainCount(dsCondition);
        final Integer dFovStreamType = mDFovStreamType;

        final DynamicShotMode dynamicShotMode = DynamicShotMode.getDsMode(dsMode);
        final boolean isDsModeNeedSeparatedCompCapture = DynamicShotUtils.isSeparatedCompCaptureDsMode(dsMode);
        final boolean isDsExtraInfoNeedPreviewTarget = DynamicShotUtils.getDsExtraInfoNeedPreviewTarget(dsExtraInfo);

        if (!DynamicShotUtils.isDsProcessingMode(dsCondition, dsExtraInfo)) {
            throw new InvalidOperationException("dynamicShotConditionForMultiPicture is invalid - wrong shot mode");
        }

        setCapturePhysicalId(dynamicShotInfo, camCapability);

        final PicType compPicType = getPicType(COMP, dynamicShotInfo, dFovStreamType);

        final Size resultSize = Objects.requireNonNull(mMakerPicStreamConfig.getSize(compPicType, COMP));

        final ProcessRequest.Sequence<ImageBuffer> sequence = new ProcessRequestImpl.Sequence<>(
                mPictureEncodeFormat,
                resultSize,
                (null != postModeFiles) ? ProcessType.POST_PROCESS : ProcessType.IMMEDIATE_PROCESS,
                postModeFiles,
                dynamicShotInfo,
                mMakerPrivateKeys,
                mMakerPicStreamConfig,
                mCamDevice.getCamCapability(),
                /*needDepth*/(null != mPictureDepthStreamInfo)
        );

        initializeSequence(sequence);
        CLog.i(PROCESSING_PHOTO_TAG, "takeProcessingPictureInternal - sequence=" + sequence);

        // In case of DsModeNeedSeparatedCompPic, add 1 dsCondition (jpg, thumb)
        if (sequence.getProcessType() == ProcessType.POST_PROCESS && isDsModeNeedSeparatedCompCapture) {
            dsCondition += 1;
            CLog.i(PROCESSING_PHOTO_TAG, "takeProcessingPictureInternal - add pic count of dsCondition to 0x%X", dsCondition);
        }

        final CamDeviceRequestOptions.Builder requestOptionsBuilder = CamDeviceRequestOptions.createRequestOptions();
        requestOptionsBuilder.put(SemCaptureRequest.CONTROL_CAPTURE_PHYSICAL_ID, mCapturePhysicalId);
        requestOptionsBuilder.put(SemCaptureRequest.CONTROL_DYNAMIC_SHOT_HINT, dsCondition);
        requestOptionsBuilder.put(SemCaptureRequest.CONTROL_DYNAMIC_SHOT_EXTRA_INFO, dsExtraInfo);
        if (camCapability.getSamsungFeatureDynamicShotDeviceInfoAvailable()) {
            requestOptionsBuilder.put(SemCaptureRequest.CONTROL_DYNAMIC_SHOT_DEVICE_INFO, dsDeviceInfo);
        }
        if (camCapability.getSamsungFeatureAdaptableParallelCaptureAvailable()) {
            requestOptionsBuilder.put(SemCaptureRequest.CONTROL_INCOMPLETE_FCC_SEQUENCE_COUNT, incompleteFccSequenceCount);
        }
        setTargetPictureSize(camCapability, requestOptionsBuilder, compPicType);

        final List<Integer> evCompList = getEvCompensationList(camCapability, dsMode);

        final DynamicShotInfo clonedDsInfo = new DynamicShotInfo(dynamicShotInfo);
        if (DynamicShotMode.SINGLE == dynamicShotMode && mNeedFusionHighRes) {
            clonedDsInfo.setDsCondition(PublicMetadata.CONTROL_DS_CONDITION_FUSION_HIGHRES_SINGLE);
        }

        for (int i = 0; i < dsPicCnt; i++) {
            final PicType unCompPicType = getPicType(UN_COMP, clonedDsInfo, dFovStreamType, i, dsPicCnt);
            final PicType rawPicType = getPicType(RAW, clonedDsInfo, dFovStreamType, i, dsPicCnt);

            if (!evCompList.isEmpty()) {
                final int evValue = i < evCompList.size() ? evCompList.get(i) : 0;
                requestOptionsBuilder.put(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evValue);
            }

            if (sequence.getProcessType() == ProcessType.POST_PROCESS && i == 0) {
                requestOptionsBuilder.setPreview(isDsExtraInfoNeedPreviewTarget);
                requestOptionsBuilder.setPicture(compPicType, COMP, /*enable*/true);
                requestOptionsBuilder.setThumbnail(null != mThumbnailStreamInfo);

                if (isDsModeNeedSeparatedCompCapture) {
                    camDeviceRequestOptionsList.add(requestOptionsBuilder.build());
                    requestOptionsBuilder.clearStreamOption();
                }
            }

            requestOptionsBuilder.setPreview(isDsExtraInfoNeedPreviewTarget);

            final CaptureIndexInfo captureIndexInfo = dynamicShotMode.getCaptureIndexInfo(i, dsPicCnt, dsExtraInfo);
            requestOptionsBuilder.setPicture(rawPicType, RAW, captureIndexInfo.isNeedRawCapture());
            requestOptionsBuilder.setPicture(unCompPicType, UN_COMP, captureIndexInfo.isNeedYuvCapture());

            camDeviceRequestOptionsList.add(requestOptionsBuilder.build());
            requestOptionsBuilder.clearStreamOption();
        }

        mTakePictureRequestLock.lock();
        try {
            final int sequenceId = mCamDevice.takeMultiPicture(sequence, camDeviceRequestOptionsList);
            mLatestSequence = sequence;
            CLog.i(PROCESSING_PHOTO_TAG, "takeProcessingPictureInternal X - sequenceId(%d), ppSequenceId(%d)", sequenceId, sequence.getId());
            return sequenceId;
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("takeProcessingPictureInternal X - fail", e);
        } finally {
            mTakePictureRequestLock.unlock();
        }
    }

    /**
     * <div class="camera_en">
     * Take Single Processing Picture.
     * there is dsMode, but it is changed to single mode.
     * single-based processing works.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Single Processing Picture 를 촬영한다.
     * dsMode 가 있지만 single 로 변경 되어 촬영 된다.
     * single 기반 processing 이 동작 한다.
     * </div>
     *
     * @param dynamicShotInfo dynamicShotInfo.
     * @throws IllegalStateException         If cam device is not connected.
     * @throws InvalidOperationException     If takeProcessingPictureInternal is failed internally.
     * @throws UnsupportedOperationException Always throw the exception if Maker does not support this function.
     * @throws CamAccessException            If a CameraAccessException occurs.
     * @see DynamicShotInfoCallback#onDynamicShotInfoChanged(Long, DynamicShotInfo, CamDevice)
     */
    protected int takeSingleProcessingPicture(@NonNull DynamicShotInfo dynamicShotInfo) throws CamAccessException {
        CLog.i(PROCESSING_PHOTO_TAG, "takeSingleProcessingPicture - change to Single, isPostMode false runningPhysicalId %s DFovStreamType %s",
                dynamicShotInfo.getRunningPhysicalId(), mDFovStreamType);

        mIsIPPCapturing = true;

        final CamCapability camCapability = mCamDevice.getCamCapability();
        final Integer dFovStreamType = mDFovStreamType;

        setCapturePhysicalId(dynamicShotInfo, camCapability);

        final PicType compPicType = getPicType(COMP, dynamicShotInfo, dFovStreamType);
        final Size resultSize = Objects.requireNonNull(mMakerPicStreamConfig.getSize(compPicType, COMP));

        final ProcessRequest.Sequence<ImageBuffer> sequence = new ProcessRequestImpl.Sequence<>(
                mPictureEncodeFormat,
                resultSize,
                ProcessType.SINGLE_PROCESS,
                /*postModeFile*/null,
                new DynamicShotInfo(CONTROL_DS_MODE_SINGLE, CONTROL_DS_MODE_SINGLE, dynamicShotInfo.getDsExtraInfo(),
                        dynamicShotInfo.getDsDeviceInfo(), dynamicShotInfo.getRunningPhysicalId()),
                mMakerPrivateKeys,
                mMakerPicStreamConfig,
                mCamDevice.getCamCapability(),
                /*needDepth*/false
        );

        initializeSequence(sequence);

        final CamDeviceRequestOptions.Builder requestOptionsBuilder = CamDeviceRequestOptions.createRequestOptions();

        //set request options
        if (camCapability.getSamsungFeatureDynamicShotInfoAvailable()) {
            if (DynamicShotUtils.isHighResolutionDsMode(dynamicShotInfo.getDsMode())) {
                CLog.i(PROCESSING_PHOTO_TAG, "takeSingleProcessingPicture - single remosaic shot, so set dsHint(0x%d) dsExtraInfo(0x%d)",
                        CONTROL_DS_CONDITION_PRESET_SINGLE_REMOSAIC, CONTROL_DS_EXTRA_INFO_NONE);
                requestOptionsBuilder.put(SemCaptureRequest.CONTROL_DYNAMIC_SHOT_HINT, CONTROL_DS_CONDITION_PRESET_SINGLE_REMOSAIC);
                requestOptionsBuilder.put(SemCaptureRequest.CONTROL_DYNAMIC_SHOT_EXTRA_INFO, CONTROL_DS_EXTRA_INFO_NONE);
            } else {
                requestOptionsBuilder.put(SemCaptureRequest.CONTROL_DYNAMIC_SHOT_HINT, dynamicShotInfo.getDsCondition());
                requestOptionsBuilder.put(SemCaptureRequest.CONTROL_DYNAMIC_SHOT_EXTRA_INFO, dynamicShotInfo.getDsExtraInfo());
            }
            if (camCapability.getSamsungFeatureDynamicShotDeviceInfoAvailable()) {
                requestOptionsBuilder.put(SemCaptureRequest.CONTROL_DYNAMIC_SHOT_DEVICE_INFO, dynamicShotInfo.getDsDeviceInfo());
            }
            requestOptionsBuilder.put(SemCaptureRequest.CONTROL_CAPTURE_PHYSICAL_ID, mCapturePhysicalId);
            requestOptionsBuilder.setPreview(DynamicShotUtils.getDsExtraInfoNeedPreviewTarget(dynamicShotInfo.getDsExtraInfo()));
        }
        setTargetPictureSize(camCapability, requestOptionsBuilder, compPicType);

        final DynamicShotInfo clonedDsInfo = new DynamicShotInfo(dynamicShotInfo);
        if (mNeedFusionHighRes) {
            clonedDsInfo.setDsCondition(PublicMetadata.CONTROL_DS_CONDITION_FUSION_HIGHRES_SINGLE);
        }

        requestOptionsBuilder.setPicture(getPicType(UN_COMP, clonedDsInfo, mDFovStreamType), UN_COMP, true);

        mTakePictureRequestLock.lock();
        try {
            final int sequenceId = mCamDevice.takeMultiPicture(sequence, List.of(requestOptionsBuilder.build()));
            mLatestSequence = sequence;
            CLog.i(PROCESSING_PHOTO_TAG, "takeSingleProcessingPicture : sequenceId(%d)", sequenceId);
            return sequenceId;
        } catch (CamDeviceException e) {
            throw new InvalidOperationException("takeSingleProcessingPicture fail : ", e);
        } finally {
            mTakePictureRequestLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void approvePictureTaken(int sequenceId) {
        CLog.i(PROCESSING_PHOTO_TAG, "approvePictureTaken (sequenceId %d)", sequenceId);
        handleSequenceApprovalState(sequenceId, PictureProcessorManager.getInstance()::approveSequence);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void discardPictureTaken(int sequenceId) {
        CLog.i(PROCESSING_PHOTO_TAG, "discardPictureTaken (sequenceId %d)", sequenceId);
        handleSequenceApprovalState(sequenceId, discardedPpSequenceId -> {
            if (mIsMotionPhotoPppEnabled) {
                MotionPhotoManager.getInstance().cancelStoreMotionPhotoPpp(discardedPpSequenceId);
            }
            PictureProcessorManager.getInstance().discardSequence(discardedPpSequenceId);
        });
    }

    /**
     * <div class="camera_en">
     * To manage the sequence approval status, check whether the sequence requires management and, if necessary, perform consumer processing.
     * The consumer receives the ppSequenceId of the corresponding sequence as an argument and performs the necessary actions.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Sequence 승인 상태 관리를 위해 해당 sequence가 관리가 필요한지 확인하고, 필요하다면 consumer를 수행한다.
     * consumer는 해당 sequence의 ppSequenceId를 인자로 받아 필요한 동작을 수행 한다.
     * </div>
     *
     * @param sequenceId sequenceId
     * @param consumer   consumer
     */
    private synchronized void handleSequenceApprovalState(int sequenceId, @NonNull Consumer<Integer> consumer) {
        Optional.ofNullable(mLatestSequence)
                .filter(latestSequence -> mCamDevice.getSequenceId(latestSequence.getId()) == sequenceId
                        && latestSequence.getProcessType() == ProcessType.POST_PROCESS)
                .ifPresent(latestSequence -> consumer.accept(latestSequence.getId()));
    }

    @NonNull
    protected List<Integer> getEvCompensationList(@NonNull CamCapability camCapability, int dsMode) {
        final List<Float> evCompensationList;

        if (DynamicShotUtils.isMfHdrDsMode(dsMode)) {
            evCompensationList = Floats.asList(camCapability.getSamsungControlMfHdrEvCompensationList());
        } else if (DynamicShotUtils.isLlHdrDsMode(dsMode)) {
            evCompensationList = Floats.asList(camCapability.getSamsungControlLlHdrEvCompensationList());
        } else {
            return Collections.emptyList();
        }

        final int aeCompensationStepReciprocal = camCapability.getControlAeCompensationStepReciprocal();
        final int currentEvCompensation = Optional.ofNullable(getPublicSetting(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
                .orElse(0);

        return evCompensationList.stream()
                .map(ev -> (int) (ev * aeCompensationStepReciprocal + currentEvCompensation))
                .collect(Collectors.toList());
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
     * @param TAG             PROCESSING_PHOTO_TAG.
     * @param pictureCallback PictureCallback
     * @param data            ImageBuffer
     * @param extraBundle     extraBundle
     */
    protected void sendPictureTakenCallback(String TAG, PictureCallback pictureCallback, ImageBuffer data, @NonNull ExtraBundle extraBundle) {
        sendCaptureAvailable(data.getImageInfo().getCaptureMetadata());

        final int processType = Optional.ofNullable(extraBundle.get(ExtraBundle.PROCESSOR_INFO_PROCESS_TYPE)).orElse(ExtraBundle.PROCESS_TYPE_IMMEDIATE_PROCESS);
        if (mIsIPPCapturing && processType == ExtraBundle.PROCESS_TYPE_IMMEDIATE_PROCESS) {
            PictureCallbackHelper.onProcessingPictureTaken(PROCESSING_PHOTO_TAG, pictureCallback, data, extraBundle, mCamDevice);
        } else {
            PictureCallbackHelper.onPictureTaken(PROCESSING_PHOTO_TAG, pictureCallback, data, extraBundle, mCamDevice);
        }
    }

    /**
     * <div class="camera_en">
     * Returns whether it is an Extra PostProcessing condition.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Extra PostProcessing 조건인지 반환한다.
     * </div>
     *
     * @param result        CaptureResult.
     * @param capability    CamCapability.
     * @return true, if it is extra PostProcessing condition.
     */
    @CallSuper
    protected boolean isExtraPostProcessCondition(@NonNull CaptureResult result, @NonNull CamCapability capability) {
        return DeviceConfiguration.PICTURE_ENCODE_FORMAT_HEIC == mPictureEncodeFormat;
    }

    /**
     * <div class="camera_en">
     * Set value in Sequence. Usually used in Sub-Maker.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Sequence 에 필요한 값을 설정한다. 주로 자식 maker 에서 사용된다.
     * </div>
     *
     * @param sequence sequence
     */
    @CallSuper
    protected void initializeSequence(@NonNull ProcessRequest.Sequence<?> sequence) {
        final CaptureMetrics captureMetrics = new CaptureMetrics(
                sequence.getId(),
                sequence.getDsMode(),
                sequence.getDsExtraInfo(),
                sequence.getResultSize(),
                sequence.getResultFormat(),
                sequence.get(ExtraBundle.MULTI_PICTURE_DATA_RESULT_FILE).getName()); // TODO : change when ExtraBundle.MULTI_PICTURE_DATA_RESULT_FILE is null
        sequence.set(ExtraBundle.DATA_CAPTURE_METRICS, captureMetrics);

        final int sceneOptimizerMode = Optional.ofNullable(SemCaptureRequest.get(mPreviewRequestBuilderMap, mCamDevice.getId(), SemCaptureRequest.CONTROL_SCENE_DETECTION_INFO))
                .filter(sceneDetectionInfo -> sceneDetectionInfo.length > PublicMetadata.SCENE_DETECTION_INFO_INDEX_SCENE_INDEX)
                .map(sceneDetectionInfo -> (int) sceneDetectionInfo[PublicMetadata.SCENE_DETECTION_INFO_INDEX_SCENE_INDEX])
                .orElse(PublicMetadata.CONTROL_SCENE_INDEX_SCENE_DETECTION_OFF);
        sequence.set(ExtraBundle.INFO_SCENE_OPTIMIZER_MODE, sceneOptimizerMode);

        final boolean watermarkEnabled = Optional.ofNullable((Boolean) mMakerPrivateKeys.get(MakerPrivateKey.ENABLE_WATERMARK)).orElse(false);
        if (watermarkEnabled && null != mWatermarkConfig) {
            sequence.set(ExtraBundle.WATERMARK_CONFIG, mWatermarkConfig);
        }
        if (sequence.getProcessType() == ProcessType.POST_PROCESS) {
            PictureProcessorManager.getInstance().createSequenceApprovalStateMachine(mContext, sequence.getProcessType(), sequence.getId());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getCurrentPostProcessState() {
        if (null != PictureProcessorManager.getInstance()) {
            return PictureProcessorManager.getInstance().getCurrentPostProcessState();
        }
        return PrivateMetadata.POST_PROCESS_STATE_UNKNOWN;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getActivatedSequenceStackedCount() {
        if (null != PictureProcessorManager.getInstance()) {
            return PictureProcessorManager.getInstance().getActivatedSequenceStackedCount();
        }
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getPendingSequenceStackedCount() {
        if (null != PictureProcessorManager.getInstance()) {
            return PictureProcessorManager.getInstance().getPendingSequenceStackedCount();
        }
        return 0;
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
            mSupportedPrivateKeyExecutorMap.put(MakerPrivateKey.ENABLE_MOTION_PHOTO_PPP, (value) -> mIsMotionPhotoPppEnabled = (Boolean) value);
            mSupportedPrivateKeyExecutorMap.put(MakerPrivateKey.DISABLE_NON_DESTRUCTION, (value) -> {});
            mSupportedPrivateKeyExecutorMap.put(MakerPrivateKey.ENABLE_WIDE_SHAPE_CORRECTION, (value) -> {
                mIsWideDistortionEnable = (Boolean) value;
                if (mIsWideDistortionEnable && null == mWideDistortionMaxZoomRatio) {
                    mWideDistortionMaxZoomRatio = NodeFeatureUtil.getWideDistortionCorrectionZoomRatio();
                }
            });
        }
        return mSupportedPrivateKeyExecutorMap;
    }

    /**
     * <div class="camera_en">
     * Set a private setting value in Maker.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Maker 에 private 한 setting 값을 설정한다.
     * </div>
     *
     * @param privateKey The settingIndex value defined in the MakerPrivateKey
     * @param value      setting value.
     * @throws IllegalArgumentException      If value is invalid.
     * @throws UnsupportedOperationException If key is not supported.
     * @throws InvalidOperationException     If set value is failed with unknown cause.
     */
    @GuardedBy("this")
    @CallSuper
    @Override
    protected <T> void setPrivateSettingInternal(@NonNull MakerPrivateKey<T> privateKey, @NonNull T value) {
        mMakerPrivateKeys.put(privateKey, value);
        super.setPrivateSettingInternal(privateKey, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int getDsExtraInfo(@NonNull CaptureResult result, @NonNull CamCapability capability) {
        int dsExtraInfo = super.getDsExtraInfo(result, capability);

        if (mIsWatermarkEnable) {
            dsExtraInfo |= PublicMetadata.CONTROL_DS_EXTRA_INFO_MODE_EXTRA_POST_PROCESS;
        }

        if (Objects.equals(mPictureEncodeFormat, DeviceConfiguration.PICTURE_ENCODE_FORMAT_JPEG_R)
            || Objects.equals(mPictureEncodeFormat, DeviceConfiguration.PICTURE_ENCODE_FORMAT_HEIC_ULTRAHDR)) {
            dsExtraInfo |= PublicMetadata.CONTROL_DS_EXTRA_INFO_SUPER_HDR;
        }

        return dsExtraInfo;
    }

    /**
     * <div class="camera_en">
     * Returns whether it is a SingleProcessPicture condition.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * SingleProcessPicture 촬영 조건 인지 반환 한다.
     * </div>
     *
     * @param dsExtraInfo   dsExtraInfo.
     * @param result        CaptureResult.
     * @param capability    CamCapability.
     * @return true, if it is SPP capture condition.
     */
    protected boolean isSingleProcessingPictureCondition(int dsExtraInfo,
                                                         @NonNull CaptureResult result,
                                                         @NonNull CamCapability capability) {
        if (DynamicShotUtils.getDsExtraInfoNeedSuperHdr(dsExtraInfo)) {
            return true;
        }
        return isExtraPostProcessCondition(result, capability);
    }

    /**
     * <div class="camera_en">
     * Check if Ultra Wide Distortion is enabled in the current shooting mode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 현재 촬영 모드에서 Ultra Wide 왜곡이 활성화 되어 있는지 확인 한다.
     * </div>
     *
     * @return true,  if Ultra Wide Distortion is enabled or false.
     */
    protected boolean isUwDistortionEnabled(@NonNull CamCapability capability) {
        final Integer lensFacing = capability.getLensFacing();
        if (Objects.equals(lensFacing, PublicMetadata.LENS_FACING_BACK)) {
            final Integer ldcMode = SemCaptureRequest.get(mPreviewRequestBuilderMap, capability.getCameraId(), SemCaptureRequest.CONTROL_LENS_DISTORTION_CORRECTION_MODE);
            return Objects.equals(ldcMode, SemCameraMetadata.CONTROL_LENS_DISTORTION_CORRECTION_MODE_ON);
        }
        return false;
    }

    /**
     * <div class="camera_en">
     * Check if Wide Distortion is enabled in the current shooting mode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 현재 촬영 모드에서 Wide 왜곡이 활성화 되어 있는지 확인 한다.
     * </div>
     *
     * @return true,  if Wide Distortion is enabled or false.
     */
    protected boolean isWideDistortionEnabled(@NonNull CamCapability capability) {
        final Integer lensFacing = capability.getLensFacing();
        if (Objects.equals(lensFacing, PublicMetadata.LENS_FACING_BACK)) {
            if (mIsWideDistortionEnable) {
                final CaptureResult latestTotalCaptureResult = mLatestRepeatingCaptureResult.getNow();
                final Face[] faces = SemCaptureResult.get(latestTotalCaptureResult, CaptureResult.STATISTICS_FACES);
                if (null != faces && faces.length > 0) {
                    final float zoomRatio = Optional.ofNullable(SemCaptureResult.get(latestTotalCaptureResult, SemCaptureResult.SCALER_ZOOM_RATIO))
                            .orElse(1.0f);
                    return 1.0f <= zoomRatio && zoomRatio <= mWideDistortionMaxZoomRatio;
                }
            }
        }
        return false;
    }

    @Override
    protected void onCamDeviceConnected() {
        mCamDevice.setPictureDepthCallback(mCamDeviceMultiPictureCallback);

        if (mCamDevice.getCamCapability().getSamsungFeatureDynamicShotInfoAvailable()) {
            final int pppTid = PictureProcessorManager.getInstance().getPostProcessThreadId();
            if (pppTid != 0) {
                SemCaptureRequest.set(mPreviewRequestBuilderMap, mCamDevice.getId(), SemCaptureRequest.CONTROL_PPP_TID, pppTid);
                SemCaptureRequest.set(mPictureRequestBuilderMap, mCamDevice.getId(), SemCaptureRequest.CONTROL_PPP_TID, pppTid);
                CLog.i(PRIVATE_TAG, "onCamDeviceConnected - set CONTROL_PPP_TID : " + pppTid);
            }
        }
    }

    @Override
    protected void onCamDeviceClosed() {
        CLog.i(PRIVATE_TAG, "onCamDeviceClosed");

        mIsIPPCapturing = false;
    }

    /**
     * {@inheritDoc}
     */
    @CallSuper
    @Override
    protected void initializeMaker(@NonNull CamCapability camCapability) {
        CLog.i(PRIVATE_TAG, "initializeProcessingPhotoMaker E");
        mPictureProcessLock.lock();
        try {
            if (camCapability.getSamsungFeatureDynamicShotInfoAvailable()) {
                PictureProcessorManager.getInstance().initialize(camCapability, mContext);
                PictureProcessorManager.getInstance().setImmediateProcessCallback(mImmediateProcessCallback);
                PictureProcessorManager.getInstance().setPostProcessCallback(mPostProcessCallback);
                PictureProcessorManager.getInstance().setPppStatusCallback(mPppStatusCallback);
            }

            mIsMotionPhotoPppEnabled = false;
        } finally {
            mPictureProcessLock.unlock();
        }
        CLog.i(PRIVATE_TAG, "initializeProcessingPhotoMaker X");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void releaseMaker(@NonNull CamCapability camCapability) {
        CLog.i(PRIVATE_TAG, "releaseMaker E");

        mPictureProcessLock.lock();
        try {
            if (null != mMakerPrivateKeys) {
                mMakerPrivateKeys.clear();
            }

            if (camCapability.getSamsungFeatureDynamicShotInfoAvailable()) {
                PictureProcessorManager.getInstance().setImmediateProcessCallback(null);
                PictureProcessorManager.getInstance().deinitialize();
            }

            mIsIPPCapturing = false;
            mIsWideDistortionEnable = false;
        } finally {
            mPictureProcessLock.unlock();
        }
        super.releaseMaker(camCapability);
        CLog.i(PRIVATE_TAG, "releaseMaker X");
    }
}
