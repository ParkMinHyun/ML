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
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.PLog;

import java.util.function.Consumer;

public class SavingSingleDraftImageTask extends SavingDraftImageTask {
    private static final String TAG = "SavingSingleDraftImageTask";
    private ImageBuffer originalBuffer;

    public SavingSingleDraftImageTask(@NonNull ProcessRequest<ImageBuffer> processRequest,
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
        originalBuffer = getActualOriginalBuffer(originalBuffer);
    }

    @Override
    public void addOriginalBuffer(@NonNull ImageBuffer originalBuffer, @NonNull ExtraBundle extraBundle) {
        processLock.lock();
        try {
            if (!skipSaveDraftImage) {
                this.originalBuffer = originalBuffer;
            } else {
                originalBuffer.release();
            }
            this.extraBundle = extraBundle;
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

            originalBuffer.rewind();
            draftJpegNodeChainAccessor.createNodeChain(camCapability);
            draftJpegNodeChainAccessor.configureNodeChain(originalBuffer.getImageInfo(), camCapability, extraBundle, nodeChainConfiguration);

            final DraftSequenceExecutionProfiler draftSequenceExecutionProfiler = extraBundle.get(ExtraBundle.DATA_DRAFT_SEQUENCE_EXECUTION_PROFILER);
            if (null != draftSequenceExecutionProfiler) {
                draftSequenceExecutionProfiler.setDraftNodeChainAccessor(draftJpegNodeChainAccessor);
            }
            final ImageBuffer resultBuffer = draftJpegNodeChainAccessor.getNodeChain().processFull(ImageBuffer.class, originalBuffer, extraBundle);

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

    @Override
    protected ImageBuffer getOriginalMainBuffer() {
        return originalBuffer;
    }

    @GuardedBy("processLock")
    @Override
    protected void releaseBuffers() {
        super.releaseBuffers();
        try {
            if (!originalBuffer.isReleased()) {
                PLog.i(TAG, "release buffer");
                originalBuffer.release();
            }
        } catch (Exception e) {
            PLog.e(TAG, "releaseBuffers - error : " + e);
        }
    }

    @Override
    protected String getTag() {
        return TAG;
    }
}
