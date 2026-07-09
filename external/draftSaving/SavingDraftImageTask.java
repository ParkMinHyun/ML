package com.samsung.android.camera.core2.processor.draftSaving;

import static com.samsung.android.camera.core2.node.imageCodec.ImageCodecNodeBase.ImageCodecNodeCallback;
import static com.samsung.android.camera.core2.processor.json.converter.PostProcessTempImageJsonConverter.VERSION_1_1;
import static com.samsung.android.camera.core2.util.FileUtils.JSON_FILE_EXTENSION;

import android.util.Size;

import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.CamCapability;
import com.samsung.android.camera.core2.MakerPrivateKey;
import com.samsung.android.camera.core2.apm.AdaptivePerformanceManager;
import com.samsung.android.camera.core2.apm.data.DraftTimeApmData;
import com.samsung.android.camera.core2.container.CodecConfiguration;
import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.SavingInfoContainer;
import com.samsung.android.camera.core2.exception.InvalidOperationException;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionProfiler;
import com.samsung.android.camera.core2.local.vendorkey.CaptureMetadata;
import com.samsung.android.camera.core2.local.vendorkey.FileCaptureResult;
import com.samsung.android.camera.core2.node.Node;
import com.samsung.android.camera.core2.node.NodeFactory;
import com.samsung.android.camera.core2.node.imageCodec.ImageCodecNodeBase;
import com.samsung.android.camera.core2.node.preprocessor.ImageResizeNode;
import com.samsung.android.camera.core2.processor.container.NodeChainConfiguration;
import com.samsung.android.camera.core2.processor.json.converter.PostProcessTempImageJsonConverter;
import com.samsung.android.camera.core2.processor.json.data.PostProcessTempImageJsonData;
import com.samsung.android.camera.core2.processor.nodeController.DraftNodeChainAccessor;
import com.samsung.android.camera.core2.processor.postSaving.PostSavingStateManagerGroup;
import com.samsung.android.camera.core2.processor.request.ProcessRequest;
import com.samsung.android.camera.core2.util.FileUtils;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.PLog;
import com.samsung.android.camera.core2.util.QuramResizer;
import com.samsung.android.camera.core2.util.SemImageFormat;
import com.samsung.android.camera.core2.util.StrideInfo;
import com.samsung.android.camera.watermark.Watermark;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * <div class="camera_en">
 * SavingDraftImageTask class, task to save draft image.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * SavingDraftImageTask class, draft image 를 저장하는 task 이다.
 * </div>
 */
public abstract class SavingDraftImageTask implements Runnable {
    protected final DraftNodeChainAccessor draftJpegNodeChainAccessor;
    protected final ReentrantLock processLock = new ReentrantLock();
    protected final CamCapability camCapability;
    protected final int ppSequenceId;
    protected final int sequenceId;
    protected final NodeChainConfiguration nodeChainConfiguration;

    private final PostSavingStateManagerGroup postSavingStateManagerGroup;
    private final Consumer<SavingInfoContainer> savedDraftImageConsumer;
    private final Consumer<Integer> skippedDraftImageConsumer;
    private final Consumer<Integer> finishedTaskConsumer;

    protected boolean skipSaveDraftImage;
    protected ExtraBundle extraBundle;
    protected boolean saveOriginalDraftImage = false;

    private ImageBuffer originalMainJpegBuffer = null;

    public SavingDraftImageTask(@NonNull ProcessRequest<ImageBuffer> processRequest,
                                @NonNull PostSavingStateManagerGroup postSavingStateManagerGroup,
                                @NonNull DraftNodeChainAccessor draftJpegNodeChainAccessor,
                                @Nullable Consumer<SavingInfoContainer> savedDraftImageConsumer,
                                @NonNull Consumer<Integer> skippedDraftImageConsumer,
                                @NonNull Consumer<Integer> finishedTaskConsumer) {
        this.ppSequenceId = processRequest.getPpSequenceId();
        this.sequenceId = Objects.requireNonNull(processRequest.getData().getImageInfo().getCaptureMetadata()).getSequenceId();
        this.camCapability = processRequest.getCamCapability();
        this.extraBundle = processRequest.getExtraBundle();
        this.nodeChainConfiguration = processRequest.getNodeChainConfiguration();
        this.draftJpegNodeChainAccessor = draftJpegNodeChainAccessor;
        this.postSavingStateManagerGroup = postSavingStateManagerGroup;
        this.savedDraftImageConsumer = savedDraftImageConsumer;
        this.skippedDraftImageConsumer = skippedDraftImageConsumer;
        this.finishedTaskConsumer = finishedTaskConsumer;
    }

    @Override
    public final void run() {
        final DraftTimeApmData.DraftTimeApmDataBuilder draftTimeApmDataBuilder = new DraftTimeApmData.DraftTimeApmDataBuilder();
        draftTimeApmDataBuilder.setStartTime();
        processLock.lock();
        PLog.i(getTag(), "run(ppSequenceId:%d, sequenceId:%d) E", ppSequenceId, sequenceId);
        try {
            if (!postSavingStateManagerGroup.runDraft(ppSequenceId, extraBundle, this::processDraftImage, savedDraftImageConsumer)) {
                PLog.e(getTag(), "run - runDraft is failed, call forwardCallbackByDraftImageSkipped");
                skippedDraftImageConsumer.accept(ppSequenceId);
            }
        } catch (Exception e) {
            PLog.e(getTag(), "run - exception occurred : runDraft with original draftImage : " + e);
            final ImageBuffer originalBuffer = getOriginalJpegBuffer();
            if (null == originalBuffer || !postSavingStateManagerGroup.runDraft(ppSequenceId, originalBuffer, extraBundle, savedDraftImageConsumer)) {
                PLog.e(getTag(), "run - runDraft is failed, call forwardCallbackByDraftImageSkipped - originalBuffer=" + originalBuffer);
                skippedDraftImageConsumer.accept(ppSequenceId);
            }
        } finally {
            releaseBuffers();
            finishedTaskConsumer.accept(ppSequenceId);
            processLock.unlock();
            draftTimeApmDataBuilder.setEndTime();
            AdaptivePerformanceManager.getInstance().updateData(draftTimeApmDataBuilder.build());
            PLog.i(getTag(), "run(ppSequenceId:%d, sequenceId:%d) X", ppSequenceId, sequenceId);
        }
    }

    @GuardedBy("processLock")
    private ImageBuffer processDraftImage() {
        if (skipSaveDraftImage) {
            PLog.i(getTag(), "processDraftImage - skipSaveDraftImage is true, return null");
            return null;
        }

        initOriginalBuffers();
        final ImageBuffer resultBuffer = processDraftImageInternal();

        if (nodeChainConfiguration.isPendingRequest()) {
            setOriginalDraftBufferToFile(extraBundle, extraBundle.get(ExtraBundle.DATA_ORIGINAL_DRAFT));
        }
        return resultBuffer;
    }

    /**
     * <div class="camera_en">
     * Save the original draft buffer for recovery in file format.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 복구를 위한 원본 draft buffer를 파일 형태로 저장 한다.
     * </div>
     *
     * @param extraBundle           extraBundle
     * @param originalDraftBuffer   originalDraftBuffer
     */
    private void setOriginalDraftBufferToFile(@NonNull ExtraBundle extraBundle, @Nullable ImageBuffer originalDraftBuffer) {
        if (null == originalDraftBuffer) {
            PLog.w(getTag(), "setOriginalDraftBufferToFile - originalDraftBuffer is null");
            return;
        }

        final String recoveryDataFileName = extraBundle.get(ExtraBundle.PROCESSOR_INFO_PPP_RECOVERY_DATA_FILE_NAME);
        final Path originalDraftFilePath = FileUtils.SECURE_PPP_DIRECTORY_PATH.resolve(recoveryDataFileName).resolve(FileUtils.ORIGINAL_DRAFT_FILE_NAME);
        if (extraBundle.putBufferAsSingleUseFile(ExtraBundle.DATA_ORIGINAL_DRAFT, originalDraftBuffer, originalDraftFilePath)) {
            PLog.i(getTag(), "setOriginalDraftBufferToFile - buffer to file for pending request recovery");
            saveOriginalDraftImageMetaDataToFile(originalDraftBuffer.getImageInfo(), originalDraftFilePath);
            originalDraftBuffer.release();
        }
    }

    private void saveOriginalDraftImageMetaDataToFile(@NonNull ImageInfo imageInfo, @NonNull Path originalDraftFilePath) {
        PLog.i(getTag(), "saveOriginalDraftImageMetaDataToFile E");

        final Path tempImageJsonDataFilePath = FileUtils.convertFileExtension(originalDraftFilePath, JSON_FILE_EXTENSION);
        final String tempImageJsonDataString = PostProcessTempImageJsonConverter.INSTANCE.toJsonString(createTempImageJsonData(imageInfo));

        try {
            FileUtils.createFile(tempImageJsonDataFilePath, "rw-rw----");
            Files.write(tempImageJsonDataFilePath, tempImageJsonDataString.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE);
        } catch (Exception e) {
            PLog.e(getTag(), "saveOriginalDraftImageMetaDataToFile is failed : " + e);
            throw new InvalidOperationException("can't create file using tempImageDataFilePath");
        }

        imageInfo.setCaptureMetadata(new CaptureMetadata(new FileCaptureResult(tempImageJsonDataFilePath)));
        PLog.i(getTag(), "saveOriginalDraftImageMetaDataToFile X");
    }

    @NonNull
    private PostProcessTempImageJsonData createTempImageJsonData(@NonNull ImageInfo imageInfo) {
        return new PostProcessTempImageJsonData.Builder(VERSION_1_1).create(builder -> {
            final StrideInfo strideInfo = Objects.requireNonNull(imageInfo.getStrideInfo());
            builder.imageFormat = imageInfo.getFormat().getValue();
            builder.imageSize = imageInfo.getSize();
            builder.rowStride = strideInfo.getRowStride();
            builder.heightSlice = strideInfo.getHeightSlice();
            builder.timeStamp = imageInfo.getTimestamp();
            builder.physicalId = imageInfo.getPhysicalId();
            builder.captureMetadata = imageInfo.getCaptureMetadata();
            builder.nodeChainConfiguration = nodeChainConfiguration;
            builder.imageComesFrom = imageInfo.getImageComesFrom();
        });
    }

    /**
     * <div class="camera_en">
     * Does not save DraftImage.
     * Only the work to complete the PostSaving State is performed.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * DraftImage 저장을 하지 않는다.
     * PostSaving 의 State 를 완료 하기 위한 작업만 수행 한다.
     * </div>
     *
     * @return true if {@code skipDraftImageProcess} is set
     */
    public boolean setSkipSaveDraftImage() {
        final long TRY_LOCK_TIMEOUT_MILLIS = 50L;
        boolean ret = false;
        try {
            ret = processLock.tryLock(TRY_LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (ret) {
                skipSaveDraftImage = true;
                releaseBuffers();
                return true;
            }
        } catch (InterruptedException e) {
            PLog.e(getTag(), "setSkipSaveDraftImage(ppSequenceId:%d) - interrupted : ", ppSequenceId, e.getMessage());
        } finally {
            if (ret) {
                processLock.unlock();
            }
        }
        return false;
    }

    /**
     * <div class="camera_en">
     * For buffers that require resizing, perform the resize operation. <br>
     *
     * 1. In the case of a draft, if the process fails, the YUV needs to be encoded and saved as is, so resizing is performed before the process and then replaced. <br>
     * 2. The reason for performing the operation at the run time rather than the add time is to allow the resize to be carried out in the draft task thread. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * resize가 필요한 버퍼의 경우 resize한다. <br>
     *
     * 1. draft 경우는 process가 실패하면 yuv를 그대로 encode해서 저장해야 하기 때문에 process 전에 resize를 진행하여 replace해준다 <br>
     * 2. add 시점에 하지 않고 run 시점에 하는 이유는 draft task thread에서 resize를 진행하기 위함이다. <br>
     * </div>
     *
     * @param  originalBuffer originalBuffer
     * @return originalBuffer. Resized OriginalBuffer if resizing is required.
     */
    protected ImageBuffer getActualOriginalBuffer(ImageBuffer originalBuffer) {
        final ImageInfo imageInfo = originalBuffer.getImageInfo();
        if (imageInfo.getFormat() != SemImageFormat.YUV_420_888) {
            return originalBuffer;
        }

        final BitSet preprocessingOption = Optional.ofNullable(extraBundle.get(ExtraBundle.INFO_PREPROCESSING_OPTION))
                .orElseGet(BitSet::new);
        if (!preprocessingOption.get(ExtraBundle.PreprocessingOption.RESIZE.getId())) {
            return originalBuffer;
        }

        return processImageResizeNode(originalBuffer);
    }

    private ImageBuffer processImageResizeNode(ImageBuffer originalBuffer) {
        try {
            final ImageResizeNode imageResizeNode = new ImageResizeNode(camCapability,
                    () -> {
                        throw new InvalidOperationException("error occurred in ImageResizeNode");
                    });

            imageResizeNode.initialize(true);
            imageResizeNode.reconfigure(camCapability);
            final ImageBuffer resizedBuffer = (ImageBuffer) Node.set(imageResizeNode.INPUTPORT_PICTURE, originalBuffer, extraBundle);
            imageResizeNode.release();
            return resizedBuffer;
        } catch (Exception e) {
            PLog.e(getTag(), "processImageSizeNode - error while getting resized original buffer : " + e);
        }
        return originalBuffer;
    }

    @GuardedBy("processLock")
    protected final ImageBuffer getOriginalJpegBuffer() {
        if (skipSaveDraftImage) {
            PLog.i(getTag(), "getOriginalJpegBuffer - skipSaveDraftImage is true, return null");
            return null;
        }

        PLog.i(getTag(), "getOriginalJpegBuffer E");
        if (null != originalMainJpegBuffer) {
            originalMainJpegBuffer.rewind();
            PLog.i(getTag(), "getOriginalJpegBuffer X - OriginalMainJpegBuffer already exists");
            return originalMainJpegBuffer;
        }

        final ImageBuffer originalMainBuffer = getOriginalMainBuffer();
        if (SemImageFormat.isCompressedFormat(originalMainBuffer.getImageInfo().getFormat())) {
            // Assume image size that compressed is same to result size.
            originalMainJpegBuffer = originalMainBuffer;
            originalMainJpegBuffer.rewind();
            PLog.i(getTag(), "getOriginalJpegBuffer X - originalMainBuffer is compressed format");
            return originalMainJpegBuffer;
        }

        final Size imageSize = originalMainBuffer.getImageInfo().getSize();
        final Size resultSize = extraBundle.get(ExtraBundle.INFO_RESULT_CAPTURE_SIZE);
        if (null != resultSize && !resultSize.equals(imageSize)) {
            PLog.i(getTag(), "getOriginalJpegBuffer - resize from %s to %s", imageSize, resultSize);
            final ImageBuffer resizedMainBuffer = QuramResizer.resizeNV21ToPackedNV21(originalMainBuffer, resultSize);
            originalMainJpegBuffer = encode(resizedMainBuffer);
            resizedMainBuffer.release();
        } else {
            originalMainJpegBuffer = encode(originalMainBuffer);
        }
        originalMainJpegBuffer.rewind();

        PLog.i(getTag(), "getOriginalJpegBuffer X - encode");
        return originalMainJpegBuffer;
    }

    protected boolean isValidResultBuffer(@Nullable ImageBuffer resultBuffer) {
        return null != resultBuffer
                && resultBuffer.getImageInfo().getImageComesFrom() == ImageInfo.CameraUsage.MAIN_CAM
                && SemImageFormat.isCompressedFormat(resultBuffer.getImageInfo().getFormat());
    }

    protected boolean needDraftProcessing() {
        @SuppressWarnings("unchecked") final ConcurrentHashMap<MakerPrivateKey<?>, Object> makerPrivateKeys = extraBundle.get(ExtraBundle.CONTROL_MAKER_PRIVATE_KEYS);
        if (null == makerPrivateKeys) {
            return false;
        }
        final boolean isWatermarkEnabled = Optional.ofNullable((Boolean) makerPrivateKeys.get(MakerPrivateKey.ENABLE_WATERMARK)).orElse(false);
        final Watermark.WatermarkType watermarkType = Optional.ofNullable((Watermark.WatermarkType) makerPrivateKeys.get(MakerPrivateKey.WATERMARK_TYPE))
                .orElse(Watermark.WatermarkType.OVERLAY);

        return isWatermarkEnabled && watermarkType == Watermark.WatermarkType.FRAME;
    }

    private ImageBuffer encode(ImageBuffer yuvImageBuffer) {
        final ImageCodecNodeBase encodeImageCodecNode = NodeFactory.create(
                ImageCodecNodeBase.class,
                ImageCodecNodeCallback.createNoOpCallback()
        );

        final SemImageFormat resultFormat = Optional.ofNullable(extraBundle.get(ExtraBundle.INFO_RESULT_IMAGE_FORMAT))
                .map(SemImageFormat::valueOf)
                .orElse(SemImageFormat.JPEG);
        final SemImageFormat draftFormat = switch (resultFormat) {
            case JPEG_R, HEIC_ULTRAHDR -> SemImageFormat.JPEG_R;
            default -> SemImageFormat.JPEG;
        };

        encodeImageCodecNode.setCodecConfiguration(
                new CodecConfiguration.EncodeBuilder(draftFormat.getValue(), camCapability, yuvImageBuffer.getImageInfo())
                        .setSupportedKeepOriginImage(false)
                        .build());
        encodeImageCodecNode.initialize(true);

        final ImageBuffer encodedJpegBuffer = (ImageBuffer) Node.set(encodeImageCodecNode.INPUTPORT_PICTURE, yuvImageBuffer, extraBundle);
        encodedJpegBuffer.rewind();
        encodeImageCodecNode.release();

        return encodedJpegBuffer;
    }

    /**
     * <div class="camera_en">
     * Add OriginalBuffer.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * OriginalBuffer 를 넣어 준다.
     * </div>
     *
     * @param originalBuffer OriginalBuffer to add
     */
    public abstract void addOriginalBuffer(@NonNull ImageBuffer originalBuffer, @NonNull ExtraBundle extraBundle);

    /**
     * <div class="camera_en">
     * Save the original DraftImage.
     * For multi-series tasks, save the DraftImage as the main image jpeg.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Original DraftImage 를 저장 한다.
     * Multi 계열 task 의 경우 main 이미지 jpeg 으로 DraftImage 를 저장 한다.
     * </div>
     */
    protected void handleIsDraftProcessing() {
        processLock.lock();
        try {
            if (skipSaveDraftImage) {
                return;
            }
            saveOriginalDraftImage = true;
            extraBundle.put(ExtraBundle.PROCESSOR_INFO_IS_DRAFT_PROCESSING, true);
            PLog.i(getTag(), "PreviousDraftProcessing");
        } finally {
            processLock.unlock();
        }
    }

    protected void markSaveOriginalDraftImageOnly() {
        handleIsDraftProcessing();
    }

    @GuardedBy("processLock")
    protected abstract ImageBuffer processDraftImageInternal();

    @GuardedBy("processLock")
    protected abstract ImageBuffer getOriginalMainBuffer();

    @GuardedBy("processLock")
    protected abstract void initOriginalBuffers();

    @GuardedBy("processLock")
    protected void deinitializeDraftNodeChain() {
        final DraftSequenceExecutionProfiler draftSequenceExecutionProfiler = extraBundle.get(ExtraBundle.DATA_DRAFT_SEQUENCE_EXECUTION_PROFILER);
        if (null != draftSequenceExecutionProfiler) {
            draftSequenceExecutionProfiler.deinitializeDraftNodeChain();
        } else {
            draftJpegNodeChainAccessor.deinitializeNodeChain();
        }
    }

    @GuardedBy("processLock")
    @CallSuper
    protected void releaseBuffers() {
        if (null != originalMainJpegBuffer && !originalMainJpegBuffer.isReleased()) {
            PLog.i(getTag(), "release OriginalMainJpegBuffer");
            originalMainJpegBuffer.release();
            originalMainJpegBuffer = null;
        }
    }

    protected abstract String getTag();
}
