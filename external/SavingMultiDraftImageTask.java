package com.samsung.android.camera.core2.processor.draftSaving;

import android.os.SystemClock;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.SavingInfoContainer;
import com.samsung.android.camera.core2.exception.InvalidOperationException;
import com.samsung.android.camera.core2.ml.CaptureMetrics;
import com.samsung.android.camera.core2.ml.DeviceStateReader;
import com.samsung.android.camera.core2.ml.DeviceStateSnapshot;
import com.samsung.android.camera.core2.ml.DraftSequenceMetrics;
import com.samsung.android.camera.core2.ml.PostExecutionMetrics;
import com.samsung.android.camera.core2.ml.PreExecutionMetrics;
import com.samsung.android.camera.core2.ml.SavingExecutionMetrics;
import com.samsung.android.camera.core2.processor.nodeController.DraftNodeChainAccessor;
import com.samsung.android.camera.core2.processor.postSaving.PostSavingStateManagerGroup;
import com.samsung.android.camera.core2.processor.request.ProcessRequest;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.PLog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * <div class="camera_en">
 * SavingDraftImageTask class, task to save draft image.
 * It is a multi-image task that can handle multiple requests.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * SavingDraftImageTask class, draft image 를 저장하는 task 이다.
 * 여러 장의 request를 처리할 수 있는 multi image task 이다.
 * </div>
 */
public class SavingMultiDraftImageTask extends SavingDraftImageTask {
    private static final String TAG = "SavingMultiDraftImageTask";
    private static final int FIRST_IMAGE_INDEX = 0;
    private final List<ImageBuffer> originalBufferList = new ArrayList<>();
    private final CaptureMetrics captureMetrics;
    private final DeviceStateReader deviceStateReader;

    public SavingMultiDraftImageTask(@NonNull ProcessRequest<ImageBuffer> processRequest,
                                     @NonNull PostSavingStateManagerGroup postSavingStateManagerGroup,
                                     @NonNull DraftNodeChainAccessor draftJpegNodeChainAccessor,
                                     @Nullable Consumer<SavingInfoContainer> savedDraftImageConsumer,
                                     @NonNull Consumer<Integer> skippedDraftImageConsumer,
                                     @NonNull Consumer<Integer> finishedTaskConsumer) {
        super(processRequest, postSavingStateManagerGroup, draftJpegNodeChainAccessor, savedDraftImageConsumer, skippedDraftImageConsumer, finishedTaskConsumer);

        captureMetrics = Objects.requireNonNull(processRequest.getExtraBundle().get(ExtraBundle.DATA_CAPTURE_METRICS), "captureMetrics");
        captureMetrics.setDraftSequenceMetrics(new DraftSequenceMetrics());
        deviceStateReader = Objects.requireNonNull(processRequest.getExtraBundle().get(ExtraBundle.DATA_DEVICE_STATE_READER), "deviceStateReader");
    }

    @GuardedBy("processLock")
    @Override
    protected void initOriginalBuffers() {
        originalBufferList.replaceAll(this::getActualOriginalBuffer);
    }

    @Override
    public void addOriginalBuffer(@NonNull ImageBuffer originalBuffer, @NonNull ExtraBundle extraBundle) {
        processLock.lock();
        try {
            if (!skipSaveDraftImage) {
                originalBufferList.add(originalBuffer);
            } else {
                originalBuffer.release();
            }
            this.extraBundle = extraBundle;
        } finally {
            processLock.unlock();
        }
    }

    @Override
    public void handleIsDraftProcessing() {
        super.handleIsDraftProcessing();
        processLock.lock();
        try {
            removeSubImageBuffers();
        } finally {
            processLock.unlock();
        }
    }

    @GuardedBy("processLock")
    @Override
    protected ImageBuffer processDraftImageInternal() {
        PLog.i(TAG, "processDraftImageInternal(ppSequenceId:%d, sequenceId:%d) E", ppSequenceId, sequenceId);

        try {
            if (saveOriginalDraftImage && !needDraftProcessing()) {
                PLog.i(TAG, "processDraftImageInternal - save original draft image (ppSequenceId:%d, sequenceId:%d)", ppSequenceId, sequenceId);
                return getOriginalJpegBuffer();
            }

            ImageBuffer resultBuffer = null;
            final Iterator<ImageBuffer> iterator = originalBufferList.iterator();
            while (iterator.hasNext()) {
                final ImageBuffer buffer = iterator.next();
                buffer.rewind();

                if (FIRST_IMAGE_INDEX == originalBufferList.indexOf(buffer)) {
                    draftJpegNodeChainAccessor.createNodeChain(camCapability);
                    draftJpegNodeChainAccessor.configureNodeChain(buffer.getImageInfo(), camCapability, extraBundle, nodeChainConfiguration);
                }

                PLog.i(TAG, "processDraftImageInternal - NodeChain process E");
                resultBuffer = draftJpegNodeChainAccessor.getNodeChain().processFull(ImageBuffer.class, buffer, extraBundle);
                PLog.i(TAG, "processDraftImageInternal - NodeChain process X");

                if (iterator.hasNext()
                        && isValidResultBuffer(resultBuffer)) {
                    PLog.w(TAG, "processDraftImageInternal - stopped");
                    break;
                }
            }

            if (!isValidResultBuffer(resultBuffer)){
                throw new InvalidOperationException("result buffer is invalid");
            }

            return resultBuffer;
        } catch (Exception e) {
            PLog.w(TAG, "processDraftImageInternal fail : " + e);
            return getOriginalJpegBuffer();
        } finally {
            final DeviceStateSnapshot deviceStateSnapshot = deviceStateReader.read();
            final long budgetMs = Objects.requireNonNull(captureMetrics.getTimeoutTimestampMs(), "timeoutMs") - SystemClock.uptimeMillis();
            final DraftSequenceMetrics draftSequenceMetrics = Objects.requireNonNull(captureMetrics.getDraftSequenceMetrics(), "draftSequenceMetrics");
            draftSequenceMetrics.setSavingExecutionMetrics(new SavingExecutionMetrics(
                    /*isPendingRequest*/true,
                    captureMetrics.getResultImageSize(),
                    captureMetrics.getResultImageFormat(),
                    new PreExecutionMetrics(budgetMs,
                            deviceStateSnapshot.getMemorySnapshot(),
                            deviceStateSnapshot.getThermalSnapshot(),
                            deviceStateSnapshot.getStorageSnapshot()),
                    new PostExecutionMetrics(),
                    SystemClock.uptimeMillis()));
            draftJpegNodeChainAccessor.deinitializeNodeChain();
            PLog.i(TAG, "processDraftImageInternal(ppSequenceId:%d, sequenceId:%d) X", ppSequenceId, sequenceId);
        }
    }

    @GuardedBy("processLock")
    @Override
    protected ImageBuffer getOriginalMainBuffer() {
        PLog.i(TAG, "getOriginalJpegBuffer");
        return originalBufferList.stream()
                .filter(buffer -> buffer.getImageInfo().getImageComesFrom() == ImageInfo.CameraUsage.MAIN_CAM)
                .findFirst()
                .orElse(originalBufferList.getFirst());
    }

    @GuardedBy("processLock")
    @Override
    protected void releaseBuffers() {
        super.releaseBuffers();
        try {
            for (ImageBuffer buffer : originalBufferList) {
                if (!buffer.isReleased()) {
                    PLog.i(TAG, "release buffer");
                    buffer.release();
                }
            }
            originalBufferList.clear();
        } catch (Exception e) {
            PLog.e(TAG, "releaseBuffers - error : " + e);
        }
    }

    @GuardedBy("processLock")
    private void removeSubImageBuffers() {
        final Iterator<ImageBuffer> iterator = originalBufferList.iterator();
        while (iterator.hasNext()) {
            final ImageBuffer imageBuffer = iterator.next();
            if (imageBuffer.getImageInfo().getImageComesFrom() == ImageInfo.CameraUsage.SUB_CAM) {
                imageBuffer.release();
                iterator.remove();
            }
        }
    }

    @Override
    protected String getTag() {
        return TAG;
    }
}
