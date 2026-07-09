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

package com.samsung.android.camera.core2.node;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.CamCapability;
import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.PictureStreamInfo;
import com.samsung.android.camera.core2.exception.AbortProcessException;
import com.samsung.android.camera.core2.exception.InvalidOperationException;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.DumpUtils;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.ImageInfo.CameraUsage;
import com.samsung.android.camera.core2.util.PLog;
import com.samsung.android.camera.core2.util.SemImageFormat;
import com.sec.android.app.TraceWrapper;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * <div class="camera_en">
 * MultiFrameNodeBase
 * Base Class of MultiFrame node.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * MultiFrameNodeBase
 * MultiFrame Node 관련 기초 Class
 * </div>
 */
public abstract class MultiFrameNodeBase extends Node2 {

    protected static final int DEFAULT_MAIN_PICTURE_COUNT = 1;
    protected static final int DEFAULT_SUB_PICTURE_COUNT = 1;
    private static final int FIRST_CAPTURE_COUNT = 1;

    protected int mMaxMainInputCount = 0;
    protected int mMaxSubInputCount = 0;

    private int mCurrentMainInputCount = 0;
    private int mCurrentSubInputCount = 0;

    protected final MultiFrameNodeCallback mMultiFrameNodeCallback;

    private final Map<SemImageFormat, BiConsumer<ImageInfo, ExtraBundle>> mPrepareFirstFormatProcessPictureMap = new EnumMap<>(SemImageFormat.class);

    protected MultiFrameNodeBase(@NonNull NodeId nodeId,
                                 @NonNull String nodeTag,
                                 boolean hasNativeNode,
                                 @Nullable MultiFrameNodeCallback multiFrameNodeCallback) {
        super(nodeId, nodeTag, hasNativeNode);
        INPUTPORT_PICTURE.setCoreInterface(new MultiFramePictureProcessCore(this));
        mMultiFrameNodeCallback = multiFrameNodeCallback;
    }

    private void initPrepareFirstFormatProcessPictureMap() {
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.YCBCR_P010, this::prepareFirstYuvProcessPicture);
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.YUV_420_888, this::prepareFirstYuvProcessPicture);
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.JPEG, this::prepareFirstJpegProcessPicture);
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.JPEG_R, this::prepareFirstJpegProcessPicture);
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.RAW_SENSOR, this::prepareFirstRawProcessPicture);
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.RAW10, this::prepareFirstRawProcessPicture);
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.RAW12, this::prepareFirstRawProcessPicture);
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.RAW14, this::prepareFirstRawProcessPicture);
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.HEIC, this::prepareFirstHeicProcessPicture);
        mPrepareFirstFormatProcessPictureMap.put(SemImageFormat.HEIC_ULTRAHDR, this::prepareFirstHeicProcessPicture);
    }

    @Override
    public void prepareProcessCapture(int dsMode, int mainProcessCount, int subProcessCount) {
        super.prepareProcessCapture(dsMode, mainProcessCount, subProcessCount);
        setMaxInputCount(dsMode, mainProcessCount, subProcessCount);
        CLog.i(getNodeTag(), "prepareProcessCapture: {main : %d, sub : %d, supported camType : %s}",
                mMaxMainInputCount, mMaxSubInputCount, mSupportedCamType);
    }

    @Override
    protected ImageBuffer processPicture(@NonNull ImageBuffer picture, @NonNull ExtraBundle bundle) {
        initProcessPicture(picture.getImageInfo(), bundle);

        if (isFirstProcessPicture()) {
            initPrepareFirstFormatProcessPictureMap();
        }

        final SemImageFormat imgFormat = picture.getImageInfo().getFormat();
        Optional.ofNullable(mPrepareFirstFormatProcessPictureMap.remove(imgFormat))
                .ifPresent(consumer -> consumer.accept(picture.getImageInfo(), bundle));

        return super.processPicture(picture, bundle);
    }

    protected void initProcessPicture(@NonNull ImageInfo imageInfo, @NonNull ExtraBundle bundle) {
    }

    protected void prepareFirstYuvProcessPicture(@NonNull ImageInfo imageInfo, @NonNull ExtraBundle bundle) {
    }

    protected void prepareFirstJpegProcessPicture(@NonNull ImageInfo imageInfo, @NonNull ExtraBundle bundle) {
    }

    protected void prepareFirstRawProcessPicture(@NonNull ImageInfo imageInfo, @NonNull ExtraBundle bundle) {
    }

    protected void prepareFirstHeicProcessPicture(@NonNull ImageInfo imageInfo, @NonNull ExtraBundle bundle) {
    }

    protected void handleErrorCallback(int errorCode, @Nullable ExtraBundle extraBundle) {
        if (NodeErrors.NO_ERROR == errorCode) {
            return;
        }

        CLog.e(getNodeTag(), "handleErrorCallback - onError(0x%X)", errorCode);
        if (NodeErrors.ABORT == errorCode) {
            Optional.ofNullable(mMultiFrameNodeCallback)
                    .ifPresent(callback -> callback.onAborted(extraBundle));
        } else {
            Optional.ofNullable(mMultiFrameNodeCallback)
                    .ifPresent(callback -> callback.onError(NodeErrors.UNKNOWN_ERROR, extraBundle));
        }
    }

    @Override
    protected void setSupportedCamType(int dsMode) {
        mSupportedCamType.clear();
        mSupportedCamType.add(ImageInfo.CameraUsage.MAIN_CAM);
    }

    /**
     * <div class="camera_en">
     * set max input count.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 최대 입력 이미지 장수를 설정한다.
     * </div>
     *
     * @param dsMode            dsMode.
     * @param mainProcessCount  mainProcessCount.
     * @param subProcessCount   subProcessCount.
     */
    protected void setMaxInputCount(int dsMode, int mainProcessCount, int subProcessCount) {
        resetMaxInputCount();
        if (isSupportedCamType(CameraUsage.MAIN_CAM)) {
            mMaxMainInputCount = mainProcessCount;
        }
        if (isSupportedCamType(CameraUsage.SUB_CAM)) {
            mMaxSubInputCount = subProcessCount;
        }
    }

    /**
     * <div class="camera_en">
     * Reconfigure node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 노드를 재설정한다.
     * </div>
     *
     * @param initParam initParam.
     */
    @CallSuper
    public void reconfigure(@NonNull Object initParam) {
        resetCurrentInputCount();
    }

    /**
     * {@inheritDoc}
     */
    public void release() {
        super.release();
    }

    /**
     * <div class="camera_en">
     * Process for incomplete merge.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * IncompleteMerge 를 처리한다.
     * </div>
     *
     * @param bundle ExtraBundle.
     * @return picture.
     */
    protected ImageBuffer processIncompleteMerge(@NonNull ExtraBundle bundle) {
        throw new InvalidOperationException("this function should be override at sub node to process incomplete merge.");
    }

    /**
     * <div class="camera_en">
     * Get current input count.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 현재 input count 를 반환 한다.
     * </div>
     *
     * @return current input count.
     */
    protected int getCurrentInputCount() {
        return mCurrentMainInputCount + mCurrentSubInputCount;
    }

    /**
     * <div class="camera_en">
     * Return whether the process picture is the first process.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * process picture가 첫 번째 인지 확인 한다.
     * </div>
     *
     * @return true, if current input count is the first capture count.
     */
    protected boolean isFirstProcessPicture() {
        return getCurrentInputCount() == FIRST_CAPTURE_COUNT;
    }

    /**
     * <div class="camera_en">
     * Return max input count.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Max input count 값을 반환 한다.
     * </div>
     *
     * @return max input count.
     */
    protected int getMaxInputCount() {
        return mMaxMainInputCount + mMaxSubInputCount;
    }

    /**
     * <div class="camera_en">
     * Return whether the current input count is greater or equal than max input count.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 현재 input count 가 max input count 보다 크거나 같은지 확인 한다.
     * </div>
     *
     * @return true, if current input count is greater or equal than max input count.
     */
    public boolean isMaxInputCount() {
        final int currentInputCount = mCurrentMainInputCount + mCurrentSubInputCount;
        final int maxInputCount = mMaxMainInputCount + mMaxSubInputCount;

        return (currentInputCount >= maxInputCount);
    }

    // TODO: access modifier to private

    /**
     * <div class="camera_en">
     * Reset current input count.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Current input count 값을 리셋 한다.
     * </div>
     */
    protected void resetCurrentInputCount() {
        CLog.i(getNodeTag(), "resetCurrentInputCount");
        mCurrentMainInputCount = 0;
        mCurrentSubInputCount = 0;
    }

    /**
     * <div class="camera_en">
     * Increase current main input count.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Current main input count 값을 1 증가 시킨다.
     * </div>
     */
    private void increaseCurrentMainInputCount() {
        if (isSupportedCamType(CameraUsage.MAIN_CAM)) {
            mCurrentMainInputCount++;
        }
    }

    /**
     * <div class="camera_en">
     * Increase current sub input count.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Current sub input count 값을 1 증가 시킨다.
     * </div>
     */
    private void increaseCurrentSubInputCount() {
        if (isSupportedCamType(CameraUsage.SUB_CAM)) {
            mCurrentSubInputCount++;
        }
    }

    /**
     * <div class="camera_en">
     * Reset max input count.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Max input count 값을 리셋 한다.
     * </div>
     */
    private void resetMaxInputCount() {
        mMaxMainInputCount = 0;
        mMaxSubInputCount = 0;
    }

    /**
     * <div class="camera_en">
     * Get PictureStreamInfo by {@param predicate} in {@link ExtraBundle#MULTI_PICTURE_REQUEST_PICTURE_STREAM_INFO_SET}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@link ExtraBundle#MULTI_PICTURE_REQUEST_PICTURE_STREAM_INFO_SET} 에서 {@param predicate} 에 부합하는 PictureStreamInfo 를 얻는다.
     * </div>
     *
     * @param extraBundle   extraBundle
     * @param predicate     predicate
     * @return PictureStreamInfo, or null if there is no matching PictureStreamInfo.
     */
    @Nullable
    protected PictureStreamInfo getPictureStreamInfo(@NonNull ExtraBundle extraBundle, @NonNull Predicate<PictureStreamInfo> predicate) {
        final Set<PictureStreamInfo> pictureStreamInfoSet = Optional.ofNullable(extraBundle.get(ExtraBundle.MULTI_PICTURE_REQUEST_PICTURE_STREAM_INFO_SET))
                .orElseGet(Collections::emptySet);

        return pictureStreamInfoSet.stream()
                .filter(predicate)
                .findFirst()
                .orElse(null);
    }

    public enum EffectType {
        NORMAL(0x0),
        BASIC_FILTER(0x1),
        MY_FILTER(0x2),
        FACE_RETOUCHING(0x4);
        private final int maskBit;

        EffectType(int maskBit) {
            this.maskBit = maskBit;
        }

        public int getMaskBit() {
            return maskBit;
        }
    }

    /**
     * <div class="camera_en">
     * Common Multi-Frame node init parameter class
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Multi-Frame node의 초기화 인자 클래스
     * </div>
     */
    public record MultiFrameInitParam(@NonNull CamCapability camCapability) {
    }

    protected class MultiFramePictureProcessCore extends PictureProcessCore {

        public MultiFramePictureProcessCore(Node node) {
            super(node);
        }

        @Override
        protected ImageBuffer handleNullInputData(@NonNull ExtraBundle bundle) {
            CLog.i(getNodeTag(), "handleNullInputData : incomplete merge, reset count at (%d/%d)",
                    getCurrentInputCount(), getMaxInputCount());

            final ImageBuffer processedData = processIncompleteMerge(bundle);

            resetCurrentInputCount();
            resetMaxInputCount();

            return processedData;
        }

        @Override
        protected void dumpInputData(@NonNull ImageBuffer data) {
            if (needPictureDump()) {
                DumpUtils.dumpCaptureIfEnabled(data, String.format(Locale.UK, "input_%d_%s", getCurrentInputCount() + 1, getNodeTagNameWithoutVersion()));
            }
        }

        @Override
        protected void dumpProcessedData(@Nullable ImageBuffer processedData) {
            if (processedData == null) {
                return;
            }

            if (needPictureDump()) {
                if (processedData.getImageInfo().getImageComesFrom() == CameraUsage.SUB_CAM) {
                    DumpUtils.dumpCaptureIfEnabled(processedData, "processed_sub_" + mCurrentSubInputCount + "_" + getNodeTagNameWithoutVersion());
                } else {
                    DumpUtils.dumpCaptureIfEnabled(processedData, "processed_main_" + mCurrentMainInputCount + "_" + getNodeTagNameWithoutVersion());
                }
            }
        }

        @Override
        protected void prepareProcess(@NonNull ImageBuffer data, @NonNull ExtraBundle bundle) {
            increaseInputCount(data);
            super.prepareProcess(data, bundle);
        }

        @Override
        protected ImageBuffer doProcess(@NonNull ImageBuffer data, @NonNull ExtraBundle bundle) {
            TraceWrapper.traceBegin(String.format(Locale.UK, LOG_DO_PROCESS_TRACE_BEGIN + "(%d/%d)", getNodeTag(), getCurrentInputCount(), getMaxInputCount()));
            CLog.i(getNodeTag(), LOG_DO_PROCESS_START + "(%d/%d), %s", getCurrentInputCount(), getMaxInputCount(), data);
            final ImageBuffer processedData = selectImageBuffer(data, bundle);
            CLog.i(getNodeTag(), LOG_DO_PROCESS_END + "(%d/%d), %s", getCurrentInputCount(), getMaxInputCount(), (null != processedData ? processedData : "skip"));
            TraceWrapper.traceEnd();

            return processedData;
        }

        @Override
        protected void finishProcess(@NonNull ImageBuffer data, @Nullable ImageBuffer processedData) {
            super.finishProcess(data, processedData);
            resetInputCountIfComplete();
        }

        private void increaseInputCount(@NonNull ImageBuffer data) {
            if (data.getImageInfo().getImageComesFrom() == CameraUsage.MAIN_CAM) {
                increaseCurrentMainInputCount();
                if (mCurrentMainInputCount > mMaxMainInputCount) {
                    PLog.e(getNodeTag(), "[ERROR] CurrentMainInputCount(%d) is bigger than MaxMainInputCount(%d).",
                            mCurrentMainInputCount, mMaxMainInputCount);
                }
            }

            if (data.getImageInfo().getImageComesFrom() == CameraUsage.SUB_CAM) {
                increaseCurrentSubInputCount();
                if (mCurrentSubInputCount > mMaxSubInputCount) {
                    PLog.e(getNodeTag(), "[ERROR] CurrentSubInputCount(%d) is bigger than MaxSubInputCount(%d).",
                            mCurrentSubInputCount, mMaxSubInputCount);
                }
            }
        }

        private void resetInputCountIfComplete() {
            if (isMaxInputCount()) {
                resetCurrentInputCount();
                resetMaxInputCount();
            }
        }
    }

    /**
     * <div class="camera_en">
     * MultiFrameNodeCallback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * MultiFrameNodeCallback.
     * </div>
     */
    public interface MultiFrameNodeCallback extends NodeCallback {

        /**
         * <div class="camera_en">
         * The callback function that will be called when encounter error in node.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * Node 에서 error 가 발생한 경우 호출된다.
         * </div>
         *
         * @param errorCode error code.
         * @param bundle    ExtraBundle.
         */
        default void onError(int errorCode, @Nullable ExtraBundle bundle) {
            // do nothing
        }

        /**
         * <div class="camera_en">
         * The callback function that notifies that the bokeh process is complete.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * Dual Bokeh process 가 완료 됐음을 알려 주는 callback 함수.
         * </div>
         */
        default void onCompleted() {
            // do nothing
        }

        /**
         * <div class="camera_en">
         * The callback function that will be called when abort in node.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * Node 에서 abort 가 발생한 경우 호출된다.
         * </div>
         *
         * @param bundle ExtraBundle.
         */
        default void onAborted(@Nullable ExtraBundle bundle) {
            // do nothing
        }

        /**
         * <div class="camera_en">
         * Creates a MultiFrameNodeCallback that does nothing.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * 아무 동작도 하지 않는 MultiFrameNodeCallback 을 생성한다.
         * </div>
         *
         * @return MultiFrameNodeCallback instance that ignores all events
         */
        static MultiFrameNodeCallback createNoOpCallback() {
            return new MultiFrameNodeCallback() {};
        }

        /**
         * <div class="camera_en">
         * Creates a MultiFrameNodeCallback that throws InvalidOperationException on error.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * 에러 발생 시 InvalidOperationException 을 던지는 MultiFrameNodeCallback 을 생성합니다.
         * </div>
         *
         * @param nodeName node name to include in error message.
         * @return MultiFrameNodeCallback instance.
         */
        static MultiFrameNodeCallback createErrorCallback(String nodeName) {
            return new MultiFrameNodeCallback() {
                @Override
                public void onError(int errorCode, @Nullable ExtraBundle bundle) {
                    if (nodeName != null) {
                        throw new InvalidOperationException("error occurred in " + nodeName);
                    }
                }
            };
        }

        /**
         * <div class="camera_en">
         * Creates a MultiFrameNodeCallback that throws InvalidOperationException on error
         * and AbortProcessException on abort.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * 에러 발생 시 InvalidOperationException 을, 중단 발생 시 AbortProcessException 을
         * 던지는 MultiFrameNodeCallback 을 생성합니다.
         * </div>
         *
         * @param nodeName node name to include in error/abort message.
         * @return MultiFrameNodeCallback instance.
         */
        static MultiFrameNodeCallback createErrorAndAbortCallback(String nodeName) {
            MultiFrameNodeCallback baseCallback = createErrorCallback(nodeName);
            return new MultiFrameNodeCallback() {
                @Override
                public void onError(int errorCode, @Nullable ExtraBundle bundle) {
                    baseCallback.onError(errorCode, bundle);
                }

                @Override
                public void onAborted(@Nullable ExtraBundle bundle) {
                    if (nodeName != null) {
                        throw new AbortProcessException("abort in " + nodeName);
                    }
                }
            };
        }
    }
}