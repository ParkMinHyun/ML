package com.samsung.android.camera.core2.processor.draftSaving;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.SavingInfoContainer;
import com.samsung.android.camera.core2.exception.InvalidOperationException;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionProfiler;
import com.samsung.android.camera.core2.processor.nodeController.DraftNodeChainAccessor;
import com.samsung.android.camera.core2.processor.postSaving.PostSavingStateManagerGroup;
import com.samsung.android.camera.core2.processor.request.ProcessRequest;
import com.samsung.android.camera.core2.util.ConditionChecker;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.PLog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    public SavingMultiDraftImageTask(@NonNull ProcessRequest<ImageBuffer> processRequest,
                                     @NonNull PostSavingStateManagerGroup postSavingStateManagerGroup,
                                     @NonNull DraftNodeChainAccessor draftJpegNodeChainAccessor,
                                     @Nullable Consumer<SavingInfoContainer> savedDraftImageConsumer,
                                     @NonNull Consumer<Integer> skippedDraftImageConsumer,
                                     @NonNull Consumer<Integer> finishedTaskConsumer) {
        super(processRequest, postSavingStateManagerGroup, draftJpegNodeChainAccessor, savedDraftImageConsumer, skippedDraftImageConsumer, finishedTaskConsumer);
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

        final DraftSequenceExecutionProfiler draftSequenceExecutionProfiler = extraBundle.get(ExtraBundle.DATA_DRAFT_SEQUENCE_EXECUTION_PROFILER);
        ConditionChecker.checkNotNull(draftSequenceExecutionProfiler, "draftSequenceExecutionProfiler");

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
                    draftSequenceExecutionProfiler.setDraftNodeChainAccessor(draftJpegNodeChainAccessor);
                }

                PLog.i(TAG, "[mhyun2.park] processDraftImageInternal - NodeChain process E - " + getConfiguredNodeNames());
                resultBuffer = draftJpegNodeChainAccessor.getNodeChain().processFull(ImageBuffer.class, buffer, extraBundle);
                PLog.i(TAG, "[mhyun2.park] processDraftImageInternal - NodeChain process X");

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
            deinitializeDraftNodeChain();
            PLog.i(TAG, "processDraftImageInternal(ppSequenceId:%d, sequenceId:%d) X", ppSequenceId, sequenceId);
        }
    }

    private List<String> getConfiguredNodeNames() {
        return draftJpegNodeChainAccessor.getConfiguredNodeList().stream()
                .map(node -> node.getClass().getSimpleName())
                .collect(Collectors.toList());
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
