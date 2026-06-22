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

package com.samsung.android.camera.core2.container;

import android.graphics.Rect;
import android.hardware.camera2.params.Face;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.samsung.android.camera.core2.CamCapability;
import com.samsung.android.camera.core2.PrivateMetadata;
import com.samsung.android.camera.core2.local.vendorkey.CaptureMetadata;
import com.samsung.android.camera.core2.ml.CaptureMetrics;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionProfiler;
import com.samsung.android.camera.core2.node.SefNode;
import com.samsung.android.camera.core2.processor.json.data.component.FilterFileData;
import com.samsung.android.camera.core2.util.BufferBase;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.DirectBuffer;
import com.samsung.android.camera.core2.util.ExifUtils;
import com.samsung.android.camera.core2.util.FileUtils;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.PLog;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * <div class="camera_en">
 * ExtraBundle class, which represent image's extra metaData. <br>
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * ExtraBundle class, image 의 metaData 를 나타낸다. <br>
 * </div>
 */
public class ExtraBundle {
    /*
     * key naming rule  -> [usage]_[type]_[name]
     * 1) usage is node name or common(skip) or multi picture, etc.
     * 2) type is info, data, control, etc.
     *  2-1 => info : information, feature, id, etc.
     *         data : buffer, file, array type info, etc.
     *         control : Use to control something
     * 3) name : name of key
     *
     * Example : INFO_CAMCAPABILITY => info.camCapability
     *     SINGLE_BOKEH_INFO_PREVIEW_STATUS => singleBokeh.info.previewStatus
     */

    /*
     * Guide to freeing metadata
     * For metadata that requires separate release (e.g., ImageBuffer, Watermark),
     * you must register a resourceReleaser with the key to explicitly release it.
     *
     * Example :
     * new Key<>("data.extraImageBuffer", ImageBuffer.class, ImageBuffer::release);
     */

    /**
     * 1. COMMON - skip usage.
     */
    // capture time bundles
    public static final Key<CamCapability> INFO_CAMCAPABILITY = new Key<>("info.camCapability", CamCapability.class);
    public static final Key<Size> INFO_RESULT_CAPTURE_SIZE = new Key<>("info.resultCaptureSize", Size.class);
    public static final Key<Size> INFO_RESULT_RAW_SIZE = new Key<>("info.resultRawSize", Size.class);
    public static final Key<Integer> INFO_RESULT_IMAGE_FORMAT = new Key<>("info.resultImageFormat", Integer.class);
    public static final Key<ImageBuffer> DATA_EXTRA_IMAGE_BUFFER = new Key<>("data.extraImageBuffer", ImageBuffer.class, ImageBuffer::release);
    public static final Key<ConcurrentHashMap> CONTROL_MAKER_PRIVATE_KEYS = new Key<>("control.makerPrivateKeys", ConcurrentHashMap.class);
    public static final Key<Integer> CONTROL_PREVIEW_SENSOR_CAPTURE_EV = new Key<>("control.previewSensorCaptureEv", Integer.class);
    public static final Key<ExifUtils.CaptureDateTime> INFO_CAPTURE_DATE_TIME = new Key<>("info.captureDateTime", (Class) ExifUtils.CaptureDateTime.class);
    public static final Key<Float> INFO_EV_COMP = new Key<>("info.evComp", Float.class);
    public static final Key<Long> INFO_BOOTING_TIME = new Key<>("info.bootingTime", Long.class);
    public static final Key<Integer> INFO_SCENE_OPTIMIZER_MODE = new Key<>("info.sceneOptimizerMode", Integer.class);
    public static final Key<Size> INFO_DUAL_PIXEL_SIZE = new Key<>("info.dualPixelSize", Size.class);

    // metrics
    public static final Key<CaptureMetrics> DATA_CAPTURE_METRICS = new Key<>("data.captureMetrics", CaptureMetrics.class);
    public static final Key<DraftSequenceExecutionProfiler> DATA_DRAFT_SEQUENCE_EXECUTION_PROFILER = new Key<>("data.draftSequenceExectionProfiler", DraftSequenceExecutionProfiler.class);
    public static final Key<Boolean> DATA_DRAFT_SEQUENCE_ERROR_PROFILER = new Key<>("data.draftSequenceErrorProfiler", Boolean.class);

    //should not be changed during processing, Except Dual Bokeh Lite, always Jpeg
    public static final Key<ImageBuffer> DATA_ORIGINAL_DRAFT = new Key<>("data.originalDraft", ImageBuffer.class, ImageBuffer::release);

    // real time bundles
    public static final Key<Integer> REALTIME_OVER_HEAT_HINT = new Key<>("realtime.overHeatHint", Integer.class);

    // Processed option
    public static final int PROCESSED_OPTION_NONE = 0x0;
    public static final int PROCESSED_OPTION_NON_DESTRUCTION = 0x00000001;
    public static final int PROCESSED_OPTION_FILTER = 0x00000002;
    public static final int PROCESSED_OPTION_WATER_MARK = 0x00000004;
    public static final int PROCESSED_OPTION_BOKEH = 0x00000008;
    public static final Key<Integer> INFO_PROCESSED_OPTION = new Key<>("info.processedOption", Integer.class);

    // Capture Type
    public static final int CAPTURE_TYPE_NORMAL = 0;
    public static final int CAPTURE_TYPE_SCALE_UP = 1;
    public static final int CAPTURE_TYPE_NO_SCALE_UP = 2;
    public static final int CAPTURE_TYPE_SW_ISP = 3;
    public static final Key<Integer> INFO_CAPTURE_TYPE = new Key<>("info.captureType", Integer.class);

    // Process Fail Info
    public static final int PROCESS_FAIL_NONE = 0x0;
    public static final int PROCESS_FAIL_SUPER_NIGHT_SKIP_LOW_MEM = 0x1;
    public static final Key<Integer> PROCESS_FAIL_INFO = new Key<>("info.processFailInfo", Integer.class);

    // If user has restricted application's background operation for battery saving. (Camera application information -> Battery -> restricted setting), then true.
    public static final Key<Boolean> IS_BACKGROUND_RESTRICTED = new Key<>("info.isBackgroundRestricted", Boolean.class);

    // Pro Expert save option info
    public static final int PRO_EXPERT_SAVE_OPTION_LEGACY = 0;
    public static final int PRO_EXPERT_SAVE_OPTION_JPEG = 1;
    public static final int PRO_EXPERT_SAVE_OPTION_RAW = 2;
    public static final int PRO_EXPERT_SAVE_OPTION_RAW_JPEG = 3;
    public static final Key<Integer> PRO_EXPERT_SAVE_OPTION = new Key<>("proExpert.info.saveOption", Integer.class);

    // preprocessing option
    public static final Key<BitSet> INFO_PREPROCESSING_OPTION = new Key<>("info.preprocessingOption", BitSet.class);

    /**
     * 2. MULTI_PICTURE
     */
    public static final Key<Integer> MULTI_PICTURE_INFO_PP_SEQUENCE_ID = new Key<>("multiPicture.info.ppSequenceId", Integer.class);
    public static final Key<File> MULTI_PICTURE_DATA_RESULT_FILE = new Key<>("multiPicture.data.resultFile", File.class);
    public static final Key<File[]> MULTI_PICTURE_DATA_EXTRA_RESULT_FILES = new Key<>("multiPicture.data.extraResultFiles", File[].class);
    public static final Key<ImageBuffer> MULTI_PICTURE_DATA_DUAL_PIXEL = new Key<>("multiPicture.data.dualPixel", ImageBuffer.class, ImageBuffer::release);
    public static final Key<Set<PictureStreamInfo>> MULTI_PICTURE_REQUEST_PICTURE_STREAM_INFO_SET = new Key<Set<PictureStreamInfo>>("multiPicture.request.pictureStreamDataSet", (Class) Set.class);
    public static final Key<ImageBuffer> MULTI_PICTURE_EXTRA_YUV_IMAGE_FOR_GAIN_MAP = new Key<>("multiPicture.extraYuvImageForGainMap", ImageBuffer.class, ImageBuffer::release);
    public static final Key<Integer> MULTI_PICTURE_EXTRA_YUV_EV = new Key<>("multiPicture.extraYuvEv", Integer.class);
    public static final Key<ImageBuffer> MULTI_PICTURE_REF_MAIN_YUV_IMAGE_FOR_DUAL_CAMERA = new Key<>("multiPicture.refMainYuvImageForDualCamera", ImageBuffer.class, ImageBuffer::release);
    public static final Key<Map<File, ImageBuffer>> MULTI_PICTURE_EXTRA_RESULT_BUFFER_MAP = new Key<Map<File, ImageBuffer>>("multiPicture.extraResultBufferMap", (Class) Map.class, map -> map.values().forEach(ImageBuffer::release));

    /**
     * 3. Processor (IPP/PPP)
     */
    // Process Type
    public static final int PROCESS_TYPE_IMMEDIATE_PROCESS = ProcessType.IMMEDIATE_PROCESS.getTypeId();
    public static final int PROCESS_TYPE_POST_PROCESS = ProcessType.POST_PROCESS.getTypeId();
    public static final int PROCESS_TYPE_SINGLE_PROCESS = ProcessType.SINGLE_PROCESS.getTypeId();
    public static final Key<Integer> PROCESSOR_INFO_PROCESS_TYPE = new Key<>("processor.info.processType", Integer.class/*PROCESS_TYPE*/);

    public static final Key<Boolean> PROCESSOR_INFO_IS_DRAFT_PROCESSING = new Key<>("processor.info.isDraftProcessing", Boolean.class);

    // Recovery Info
    public static final Key<String> PROCESSOR_INFO_PPP_RECOVERY_DATA_FILE_NAME = new Key<>("processor.info.pppRecoveryDataFileName", String.class);
    public static final Key<Integer> PROCESSOR_INFO_PPP_RECOVERY_TRY_COUNT = new Key<>("processor.info.pppRecoveryTryCount", Integer.class);

    /**
     * 4. NODE
     */
    public static final Key<SaveDataType> SEF_INFO_SAVE_DATA_TYPE = new Key<>("sef.info.saveDataType", SaveDataType.class);
    @SuppressWarnings("unchecked")
    public static final Key<Map<SefNode.SefNodeParam, byte[]>> SEF_CONTROL_NODE_PARAM_MAP = new Key<Map<SefNode.SefNodeParam, byte[]>>("sef.control.nodeParamMap", (Class) Map.class);

    //bokeh
    public static final Key<Path> BOKEH_DRAFT_PREPROCESSED_DATA_PATH = new Key<>("bokeh.data.bokehDraftPreprocessedDataPath", Path.class);
    public static final Key<Integer> BOKEH_INFO_SEF_TYPE = new Key<>("bokeh.info.sefType", Integer.class);

    // single bokeh
    public static final Key<Size> SINGLE_BOKEH_INFO_PREVIEW_SIZE = new Key<>("singleBokeh.info.previewSize", Size.class);
    public static final Key<Integer> SINGLE_BOKEH_INFO_PREVIEW_STATUS = new Key<>("singleBokeh.info.previewStatus", Integer.class);
    public static final Key<Face[]> SINGLE_BOKEH_INFO_PREVIEW_FACES = new Key<>("singleBokeh.info.previewFaces", Face[].class);
    public static final Key<Boolean> SINGLE_BOKEH_INFO_NEED_CROP_PICTURE = new Key<>("singleBokeh.info.needCropPicture", Boolean.class);
    public static final Key<int[][]> SINGLE_BOKEH_DATA_PREVIEW_LANDMARK_X = new Key<>("singleBokeh.data.previewLandmarkX", int[][].class);
    public static final Key<int[][]> SINGLE_BOKEH_DATA_PREVIEW_LANDMARK_Y = new Key<>("singleBokeh.data.previewLandmarkY", int[][].class);
    public static final Key<byte[]> SINGLE_BOKEH_DATA_PREVIEW_COLOR = new Key<>("singleBokeh.data.previewColor", byte[].class);
    public static final Key<Integer> SINGLE_BOKEH_DATA_PREVIEW_COLOR_FORMAT = new Key<>("singleBokeh.data.previewColorFormat", Integer.class);
    public static final Key<Boolean> SINGLE_BOKEH_INFO_RELIGHT_SUPPORTED = new Key<>("singleBokeh.info.relightSupported", Boolean.class);
    public static final Key<int[]> SINGLE_BOKEH_DATA_PREVIEW_INSTANCE_INFO = new Key<>("singleBokeh.data.previewInstanceInfo", int[].class);
    public static final Key<Rect[]> SINGLE_BOKEH_DATA_PREVIEW_INSTANCE_ROI = new Key<>("singleBokeh.data.previewInstanceRoi", Rect[].class);

    // dual bokeh
    public static final Key<Boolean> DUAL_BOKEH_INFO_RELIGHT_SUPPORTED = new Key<>("dualBokeh.info.relightSupported", Boolean.class);

    // night result info
    public static final Key<byte[]> NIGHT_INFO_PROCESS_RESULT = new Key<>("night.info.processResult", byte[].class);

    public static final Key<Integer> JPEG_INFO_MODIFIED_ORIENTATION = new Key<>("jpeg.info.modifiedOrientation", Integer.class);

    // watermark
    public static final Key<JSONObject> WATERMARK_CONFIG = new Key<>("watermark.data.watermarkConfig", JSONObject.class);
    public static final Key<JSONObject> WATERMARK_INFO = new Key<>("watermark.data.watermarkInfo", JSONObject.class);

    // filter
    public static final Key<FilterFileData> REMOVABLE_FILTER_FILE_DATA_FOR_PENDING = new Key<>("removableFilterFileData.for.pending", FilterFileData.class);

    // beauty
    public static final Key<Integer> BEAUTY_CONTROL_PREVIEW_BRIGHTEN = new Key<>("beauty.control.previewBrighten", Integer.class);

    // PANORAMA
    public static final Key<Boolean> PANORAMA_DISABLE_EXIF_EV_WRITING = new Key<>("panorama.disable.exif.ev.writing", Boolean.class);
    public static final Key<byte[]> PANORAMA_XMP_DATA = new Key<>("panorama.xmp.data", byte[].class);

    // non destruction
    public static final Key<String> NON_DESTRUCTION_ORIGIN_PATH = new Key<>("nonDestruction.info.originPath", String.class);

    // hdr
    public static final Key<Integer> HDR_MOTION_STATE = new Key<>("hdr.info.motionState", Integer.class);
    public static final Key<Rect> HDR_CROP_REGION = new Key<>("hdr.info.cropRegion", Rect.class);

    // mfrp
    public static final int MFRP_HDR_MODE_OFF = 0;
    public static final int MFRP_HDR_MODE_ON = 1;
    public static final Key<Integer> MFRP_INFO_HDR_MODE = new Key<>("mfrp.info.hdrMode", Integer.class);

    // dng
    public static final int RAW_DATA_FORMAT_RAW_SENSOR = 0;
    public static final int RAW_DATA_FORMAT_LINEAR_RGB16 = 1;
    public static final Key<Integer> DNG_INFO_RAW_DATA_FORMAT = new Key<>("dng.info.rawDataFormat", Integer.class);
    public static final Key<ImageBuffer> DNG_DATA_PROCESSED_RAW_FRAME = new Key<>("dng.data.processedRawFrame", ImageBuffer.class, ImageBuffer::release);
    public static final Key<byte[]> DNG_DATA_DNG_EXTRA_METADATA = new Key<>("dng.data.dngExtraMetadata", byte[].class);
    public static final Key<ImageBuffer> DNG_RESULT_DNG = new Key<>("dng.data.resultDng", ImageBuffer.class, ImageBuffer::release);
    public static final Key<PrivateMetadata.DngLinearCompressionMode> DNG_INFO_LINEAR_COMPRESSION_MODE = new Key<>("dng.info.linearCompressionMode", PrivateMetadata.DngLinearCompressionMode.class);

    // stereo photo
    public static final Key<ImageBuffer> STEREO_PHOTO_MAIN_RECTIFIED_OUTPUT = new Key<>("data.stereoPhoto.mainRectifiedOutput", ImageBuffer.class, ImageBuffer::release);
    public static final Key<ImageBuffer> STEREO_PHOTO_SUB_RECTIFIED_OUTPUT = new Key<>("data.stereoPhoto.subRectifiedOutput", ImageBuffer.class, ImageBuffer::release);
    public static final Key<ImageBuffer> STEREO_PHOTO_DEPTH_MAP_OUTPUT = new Key<>("data.stereoPhoto.depthMapOutput", ImageBuffer.class, ImageBuffer::release);
    public static final Key<byte[]> STEREO_PHOTO_METADATA = new Key<>("data.stereoPhoto.metadata", byte[].class);
    public static final Key<StereoMainCameraView> STEREO_PHOTO_MAIN_CAMERA_VIEW = new Key<>("data.stereoPhoto.mainCameraView", StereoMainCameraView.class);
    public static final Key<StereoDeviceType> STEREO_PHOTO_DEVICE_TYPE = new Key<>("data.stereoPhoto.deviceType", StereoDeviceType.class);

    // selfie Correction
    public static final Key<Boolean> SELFIE_CORRECTION_APPLIED = new Key<>("selfieCorrection.info.applied", Boolean.class);

    // wide distortion
    public static final Key<Boolean> WIDE_DISTORTION_APPLIED = new Key<>("wideDistortion.info.applied", Boolean.class);

    // uw distortion
    public static final Key<Boolean> UW_DISTORTION_APPLIED = new Key<>("uwDistortion.info.applied", Boolean.class);

    // light map
    public static final Key<DirectBuffer> LIGHT_MAP_BUFFER = new Key<>("lightMap.buffer", DirectBuffer.class, DirectBuffer::release);
    public static final Key<LightMapInfo> LIGHT_MAP_INFO = new Key<>("lightMap.info", LightMapInfo.class);

    private static final String TAG = "ExtraBundle";

    private final Map<Key<?>, Object> bundleMap = new ConcurrentHashMap<>();

    /**
     * <div class="camera_en">
     * Put key and value. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Key 와 bundle 을 넣는다. <br>
     * </div>
     *
     * @param key   key string.
     * @param value value.
     * @return the previous value.
     */
    public <T> T put(@NonNull Key<T> key, @NonNull T value) {
        if (value instanceof ImageBuffer imageBuffer) {
            final ImageInfo imageInfo = imageBuffer.getImageInfo();
            final CaptureMetadata captureMetadata = imageInfo.getCaptureMetadata();
            if (null != captureMetadata) {
                imageInfo.setCaptureMetadata(captureMetadata.with(Collections.emptySet()));
            }
        }
        return key.getType().cast(bundleMap.put(key, value));
    }

    /**
     * <div class="camera_en">
     * put the value of bufferBase type into type SingleUseFile. <br>
     * when get() API is called, original buffer is stored back and SingleUseFile is released. <br>
     * If creating SingleUseFile is failed, still put the original value. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * BufferBase 타입의 value 를 SingleUseFile 형태로 넣는다. <br>
     * get() API가 호출되면 기존의 buffer 형태로 담기고 SingleUseFile 는 제거된다. <br>
     * SingleUseFile 을 만드는 데 실패하면 원래 값을 그대로 넣는다. <br>
     * </div>
     *
     * @param key   key string.
     * @param value value.
     * @return If putting buffer to SingleUseFile type succeeds, return true.
     */
    public <T extends BufferBase> boolean putBufferAsSingleUseFile(@NonNull Key<T> key, @NonNull T value) {
        Optional.ofNullable(bundleMap.get(key))
                .filter(v -> v instanceof SingleUseFile)
                .ifPresent(v -> ((SingleUseFile<?>) v).clear());

        final Object singleUseFile = SingleUseFile.wrap(value, FileUtils.SECURE_PPP_DIRECTORY_PATH);
        if (null != singleUseFile) {
            bundleMap.put(key, singleUseFile);
            return true;
        }

        CLog.w(TAG, "putBufferAsSingleUseFile : SingleUseFile wrapping failed, put Original Buffer");
        bundleMap.put(key, value);
        return false;
    }

    /**
     * <div class="camera_en">
     * put the value of Image buffer type into type SingleUseFile. <br>
     * If a file already exists, it is created as a SingleUseFile by receiving the path and imageInfo as arguments. <br>
     * This is an API used in the pending state. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ImageBuffer 타입을 SingleUseFile 형태로 넣는다. <br>
     * 이미 File이 존재하는 경우로 path와 imageInfo를 인자로 받아서 SingleUseFile로 만들어진다. <br>
     * pending에서 사용되는 API이다. <br>
     * </div>
     *
     * @param key       key string.
     * @param path      path.
     * @param imageInfo imageInfo
     */
    public void putPathAsSingleUseFile(@NonNull Key<ImageBuffer> key, @NonNull Path path, @NonNull ImageInfo imageInfo) {
        Optional.ofNullable(bundleMap.get(key))
                .filter(v -> v instanceof SingleUseFile)
                .ifPresent(v -> ((SingleUseFile<?>) v).clear());

        bundleMap.put(key, new SingleUseFile<>(path, imageInfo, ImageBuffer.class, true));
    }

    /**
     * <div class="camera_en">
     * put the value of bufferBase type into type SingleUseFile into the transmitted path. <br>
     * when get() API is called, original buffer is stored back and SingleUseFile is released. <br>
     * If creating SingleUseFile is failed, still put the original value. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * BufferBase 타입의 value 를 전달된 path에 SingleUseFile 형태로 넣는다. <br>
     * get() API가 호출되면 기존의 buffer 형태로 담기고 SingleUseFile 는 제거된다. <br>
     * SingleUseFile 을 만드는 데 실패하면 원래 값을 그대로 넣는다. <br>
     * </div>
     *
     * @param key   key string.
     * @param value value.
     * @param path path
     * @return If putting buffer to SingleUseFile type succeeds, return true.
     */
    public <T extends BufferBase> boolean putBufferAsSingleUseFile(@NonNull Key<T> key, @NonNull T value, @NonNull Path path) {
        Optional.ofNullable(bundleMap.get(key))
                .filter(v -> v instanceof SingleUseFile)
                .ifPresent(v -> ((SingleUseFile<?>) v).clear());

        final Object singleUseFile = SingleUseFile.wrap(value, path, /*isPending*/true);
        if (null != singleUseFile) {
            bundleMap.put(key, singleUseFile);
            return true;
        }

        CLog.w(TAG, "putBufferAsSingleUseFile : SingleUseFile wrapping failed, put Original Buffer");
        bundleMap.put(key, value);
        return false;
    }

    /**
     * <div class="camera_en">
     * Associates the specified key with a value computed by the given supplier if the key is not already present.
     * If the key is already present, the existing value is returned without invoking the supplier.
     * <p>
     * Operations:
     * 1. If the key is absent from {@code bundleMap}:
     *  - The supplier's {@code get()} method is called to generate a new value.
     *  - The new value is stored in {@code bundleMap}.
     * 2. The result is cast to the type specified by {@code key.getType()} and returned.
     * </p>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 키가 존재하지 않을 경우 공급자를 사용하여 값을 계산하고 맵에 저장합니다.
     * 키가 존재할 경우 기존 값을 반환하며, 공급자는 실행되지 않습니다.
     * <p>
     * 주요 동작:
     * 1. {@code key}가 {@code bundleMap}에 없을 때:
     *  - 공급자의 {@code get()} 메서드로 새 값 생성
     *  - 생성된 값을 {@code bundleMap}에 저장
     * 2. {@code key.getType()}으로 타입 캐스팅 후 반환
     * </p>
     * </div>
     *
     * @param key   key string.
     * @param supplier value supplier.
     * @return the current value.
     */
    public <T> T computeIfAbsent(@NonNull Key<T> key, @NonNull Supplier<T> supplier) {
        return key.getType().cast(bundleMap.computeIfAbsent(key, k -> supplier.get()));
    }

    /**
     * <div class="camera_en">
     * Remove entry which correspond key. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Key 에 대응되는 Entry 를 제거한다. <br>
     * </div>
     *
     * @param key key string.
     * @return this.
     */
    public <T> ExtraBundle remove(@NonNull Key<T> key) {
        Optional.ofNullable(bundleMap.get(key))
                .filter(v -> v instanceof SingleUseFile)
                .ifPresent(v -> ((SingleUseFile<?>) v).clear());

        bundleMap.remove(key);
        return this;
    }

    /**
     * <div class="camera_en">
     * Get value which correspond key. if it doesn't exist then return null. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Key 에 대응되는 Value 를 가져온다. 없다면 null 이 반환된다. <br>
     * </div>
     *
     * @param key key string.
     * @return value.
     * @throws IllegalArgumentException if value which correspond key can't be cast by {@code clazz}.
     */
    @Nullable
    public <T> T get(@NonNull Key<T> key) {
        try {
            Object value = bundleMap.get(key);
            if (value instanceof SingleUseFile) {
                value = getSingleUseFile(key, value);
            }
            return key.getType().cast(value);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("get fail", e);
        }
    }

    /**
     * <div class="camera_en">
     * Get value which correspond key. if it doesn't exist then return defaultValue. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Key 에 대응되는 Value 를 가져온다. 없다면  defaultValue 가 반환된다. <br>
     * </div>
     *
     * @param key key string.
     * @param defaultValue default value.
     * @return value.
     * @throws IllegalArgumentException if value which correspond key can't be cast by {@code clazz}.
     */
    @NonNull
    public <T> T getOrDefault(@NonNull Key<T> key, @NonNull T defaultValue) {
        return Optional.ofNullable(get(key)).orElse(defaultValue);
    }

    /**
     * <div class="camera_en">
     * Get value which correspond key. if it doesn't exist then return defaultValue.get(). <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Key 에 대응되는 Value 를 가져온다. 없다면  defaultValue.get() 의 결과가 반환된다. <br>
     * </div>
     *
     * @param key key string.
     * @param supplier default value supplier.
     * @return value.
     * @throws IllegalArgumentException if value which correspond key can't be cast by {@code clazz}.
     */
    @Nullable
    public <T> T getOrElseGet(@NonNull Key<T> key, @NonNull Supplier<? extends T> supplier) {
        return Optional.ofNullable(get(key)).orElseGet(supplier);
    }

    /**
     * <div class="camera_en">
     * load the SingleUseFile in the map to the buffer and put it back into the map. <br>
     * If the load fails, delete it from the map and return the null. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * map 에 담아져있는 SingleUseFile 를 buffer 로 로드해서 다시 map 에 넣는다. <br>
     * 만약 로드를 실패한다면 map 에서 지우고 null 을 리턴한다. <br>
     * </div>
     *
     * @param key key string.
     * @return value.
     */
    private <T> Object getSingleUseFile(Key<T> key, Object value) {
        value = key.getType().cast(((SingleUseFile<?>) value).use());
        if (null == value) {
            CLog.w(TAG, "getSingleUseFile : Load from SingleUseFile to Buffer failed.");
            bundleMap.remove(key);
        } else {
            bundleMap.put(key, value);
        }
        return value;
    }

    /**
     * <div class="camera_en">
     * Release all entries. <br>
     * Release through finalize of DirectBuffer may cause delay <br>
     * So, element of DirectBuffer type precedes map clear and performs release. <br>
     * In the case of SingleUseFile, clear is performed to delete the file. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Entry 자원 들을 모두 해제 한다. <br>
     * DirectBuffer 의 finalize 를 통한 release 는 delay 가 발생 할 수 있으 므로 <br>
     * DirectBuffer type 의 element 는 map clear 전에 선행 되어 release 를 수행 한다. <br>
     * SingleUseFile 케이스 인 경우 clear 를 수행해 file을 삭제 한다. <br>
     * </div>
     */
    public void release() {
        if (!bundleMap.isEmpty()) {
            PLog.i(TAG, "release E");
            bundleMap.forEach((key, value) -> {
                if (value instanceof SingleUseFile<?> singleUseFile) {
                    singleUseFile.clear();
                } else {
                    releaseResource(key, value);
                }
            });
            bundleMap.clear();
            PLog.i(TAG, "release X");
        }
    }

    /**
     * <div class="camera_en">
     * If there is a resourceReleaser registered to the key, the resource is released using resourceReleaser. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * key 에 등록된 resourceReleaser 가 있을 경우 resourceReleaser 사용해 resource 를 해제 해준다. <br>
     * </div>
     *
     * @param key   key string.
     * @param value value.
     */
    private <T> void releaseResource(@NonNull Key<T> key, @NonNull Object value) {
        if (null != key.resourceReleaser) {
            PLog.i(TAG, "releaseResource : resource(%s)", key.getName());
            key.resourceReleaser.accept(key.getType().cast(value));
        }
    }

    /**
     * <div class="camera_en">
     * Unlocks the value corresponding to the key and deletes it from the map.<br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Key 에 해당 하는 value를 해제 하고 Map 에서 지워 준다.<br>
     * </div>
     *
     * @param key   key string.
     */
    public <T> void clear(@NonNull Key<T> key) {
        final Object value = bundleMap.get(key);
        if (null == value) {
            return;
        }

        if (value instanceof SingleUseFile<?> singleUseFile) {
            PLog.i(TAG, "clear : singleUseFile(%s)", key.getName());
            singleUseFile.clear();
        } else if (null != key.resourceReleaser) {
            PLog.i(TAG, "clear : resource(%s)", key.getName());
            key.resourceReleaser.accept(key.getType().cast(value));
        }
        bundleMap.remove(key);
    }

    /**
     * <div class="camera_en">
     * Create new NodeExtraBundle which has the same entry with {@code bundle}. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@code bundle} 과 같은 entry 들을 가지는 새로운 NodeExtraBundle 을 생성한다. <br>
     * </div>
     *
     * @param bundle NodeExtraBundle.
     * @return new NodeExtraBundle.
     */
    public static ExtraBundle obtain(@NonNull ExtraBundle bundle) {
        final ExtraBundle newBundle = new ExtraBundle();
        newBundle.bundleMap.putAll(bundle.bundleMap);
        return newBundle;
    }

    /**
     * <div class="camera_en">
     * Create new NodeExtraBundle with {@code keyValue}. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@code keyValue} 로 새로운 NodeExtraBundle 을 생성한다. <br>
     * </div>
     *
     * @param keyValues continuing key, value array. (e.g. "string0", value0, "string1", value1 ...)
     * @throws IllegalArgumentException if length of keyValues is not even number or odd index value is not instance of String.
     */
    public static ExtraBundle obtain(Object... keyValues) {
        if (keyValues.length % 2 > 0) {
            throw new IllegalArgumentException(String.format(Locale.UK, "keyValues length(%d) is not even number", keyValues.length));
        }

        final ExtraBundle newBundle = new ExtraBundle();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (!(keyValues[i] instanceof Key<?>)) {
                throw new IllegalArgumentException(String.format(Locale.UK, "keyValues index[%d] is not instance of Key.class", i));
            }
            newBundle.bundleMap.put((Key<?>) keyValues[i], keyValues[i + 1]);
        }

        return newBundle;
    }

    public enum StereoMainCameraView {
        UNKNOWN(-1),
        LEFT(0),
        RIGHT(1);

        private final int id;

        StereoMainCameraView(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        public static StereoMainCameraView getType(int id) {
            return Arrays.stream(values())
                    .filter(cameraType -> cameraType.id == id)
                    .findAny()
                    .orElse(UNKNOWN);
        }
    }

    public enum StereoDeviceType {
        DEVICE_TYPE_MOBILE(0),
        DEVICE_TYPE_VST(1);

        private final int id;

        StereoDeviceType(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    public enum SaveDataType {
        NORMAL_JPEG,
        DUAL_BOKEH_MAIN_JPEG,
        DUAL_BOKEH_SUB_JPEG
    }

    public enum PreprocessingOption {
        RESIZE(0),
        SAVE_YUV_FOR_GAIN_MAP(1),
        SAVE_REF_MAIN_YUV_FOR_DUAL_CAMERA(2),
        DECODE_COMPRESSED_RAW(3);

        private final int id;

        PreprocessingOption(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    @VisibleForTesting
    public static class Key<T> {
        private final String name;
        private final Class<T> type;
        private final Consumer<T> resourceReleaser;

        /**
         * <div class="camera_en">
         * Constructor for ExtraBundle.Key. <br>
         * Defines the name and type of metadata. <br>
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * ExtraBundle.Key 의 생성자. <br>
         * Metadata 의 이름 과 타입을 정의 한다. <br>
         * </div>
         *
         * @param name key name.
         * @param type key type.
         */
        public Key(@NonNull String name, @NonNull Class<T> type) {
            this.name = name;
            this.type = type;
            this.resourceReleaser = null;
        }

        /**
         * <div class="camera_en">
         * Constructor for ExtraBundle.Key. <br>
         * Defines the name and type of metadata. <br>
         * For metadata that requires separate release, you must register resourceReleaser to specify release. <br>
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * ExtraBundle.Key 의 생성자. <br>
         * Metadata 의 이름 과 타입을 정의 한다. <br>
         * 별도의 해제가 필요한 metadata의 경우 resourceReleaser를 등록 하여 해제를 명시 해야 한다. <br>
         * </div>
         *
         * @param name              key name.
         * @param type              key type.
         * @param resourceReleaser  metadata resource releaser.
         */
        public Key(@NonNull String name, @NonNull Class<T> type, @NonNull Consumer<T> resourceReleaser) {
            this.name = name;
            this.type = type;
            this.resourceReleaser = resourceReleaser;
        }

        @NonNull
        public String getName() {
            return name;
        }

        @NonNull
        public Class<T> getType() {
            return type;
        }

        public int hashCode() {
            return name.hashCode();
        }

        public boolean equals(Object obj) {
            return (this == obj)
                    || (obj instanceof Key<?> other
                    && name.equals(other.name)
                    && type.equals(other.type));
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.UK, "ExtraBundle.Key(%s), type(%s)", name, type.getSimpleName());
        }
    }

    private static class SingleUseFile<T extends BufferBase> {
        private final Path path;
        private final ImageInfo imageInfo;
        private final Class<T> bufferType;
        private boolean isUsed = false;
        private final boolean isPending;

        private SingleUseFile(@NonNull Path path, @Nullable ImageInfo imageInfo, @NonNull Class<T> bufferType, boolean isPending) {
            this.path = path;
            this.imageInfo = imageInfo;
            this.bufferType = bufferType;
            this.isPending = isPending;
        }

        /**
         * <div class="camera_en">
         * Receives the buffer and path and returns it to SingleUseFile by using wrapHelper. <br>
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * Buffer 와 path wrapHelper 함수를 통해 SingleUseFile 로 만들어 리턴한다.<br>
         * </div>
         *
         * @param value      value
         * @param parentPath parentPath
         * @return SingleUseFile.
         */
        @Nullable
        public static <T extends BufferBase> SingleUseFile<T> wrap(@NonNull T value, @NonNull Path parentPath) {
            return wrapHelper(value, parentPath, /*isPending*/false);
        }

        /**
         * <div class="camera_en">
         * Receives the buffer, path and whether pending and then returns it to SingleUseFile using wrapHelper. <br>
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * Buffer, path, pending 유무를 받아와 wrapHelper 함수를 통해 SingleUseFile 로 만들어 리턴한다.<br>
         * </div>
         *
         * @param value      value
         * @param parentPath parentPath
         * @param isPending isPending
         * @return SingleUseFile.
         */
        @Nullable
        public static <T extends BufferBase> SingleUseFile<T> wrap(@NonNull T value, @NonNull Path parentPath, boolean isPending) {
            return wrapHelper(value, parentPath, isPending);
        }

        /**
         * <div class="camera_en">
         * Receives the buffer, path and whether pending and then returns it to SingleUseFile. <br>
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * Buffer, path, pending 유무를 받아와 SingleUseFile 로 만들어 리턴한다.<br>
         * </div>
         *
         * @param value      value
         * @param parentPath parentPath
         * @param isPending isPending
         * @return SingleUseFile.
         */
        public static <T extends BufferBase> SingleUseFile<T> wrapHelper(@NonNull T value, @NonNull Path parentPath, boolean isPending) {
            Path resultPath = parentPath;
            if (isPending) {
                if (!FileUtils.saveToFile((DirectBuffer) value, parentPath.toFile(), /*makeDirIfNotExist*/true)) {
                    CLog.w(TAG, "Failed to wrap with SingleUseFile : return null");
                    return null;
                }
            } else {
                resultPath = FileUtils.saveToFile(value, parentPath, "SingleUseFile_");
                if (null == resultPath) {
                    CLog.w(TAG, "Failed to wrap with SingleUseFile : return null");
                    return null;
                }
            }

            ImageInfo imageInfo = null;
            if (value instanceof ImageBuffer imageBuffer) {
                imageInfo = imageBuffer.getImageInfo();
            }
            return new SingleUseFile<>(resultPath, imageInfo, (Class<T>) value.getClass(), isPending);
        }

        synchronized public T use() {
            if (isUsed) {
                CLog.w(TAG, "SingleUseFile is already used.");
                return null;
            }

            try {
                T loadedBuffer = null;
                final int length = (int) path.toFile().length();
                if (bufferType.equals(ImageBuffer.class)) {
                    loadedBuffer = bufferType.cast(ImageBuffer.allocate(length, imageInfo));
                } else if (bufferType.equals(DirectBuffer.class)) {
                    loadedBuffer = bufferType.cast(DirectBuffer.allocate(length));
                }

                if (null != loadedBuffer) {
                    loadedBuffer.put(path.toFile());
                    loadedBuffer.rewind();
                    return loadedBuffer;
                }
            } catch (Exception e) {
                CLog.e(TAG, "use SingleUseFile failed : " + e);
            } finally {
                isUsed = true;
                CLog.i(TAG, " use SingleUseFile isPending : " + isPending);
                if (!isPending) {
                    clear();
                }
            }
            return null;
        }

        synchronized public void clear() {
            CLog.i(TAG, "SingleUseFile clear : " + path);
            FileUtils.deleteFiles(path);
        }
    }
}
