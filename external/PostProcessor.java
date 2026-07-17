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

package com.samsung.android.camera.core2.processor;

import static com.samsung.android.camera.core2.processor.gppm.GppmStateManager.GppmState;
import static com.samsung.android.camera.core2.processor.request.ProcessRequest.Usage.ERROR;
import static com.samsung.android.camera.core2.util.FileUtils.JSON_FILE_EXTENSION;
import static com.samsung.android.camera.core2.util.FileUtils.SECURE_PPP_DIRECTORY_PATH;

import android.app.ActivityManager;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.CamCapability;
import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.SavingInfoContainer;
import com.samsung.android.camera.core2.container.SavingInfoContainer.SavingInfo;
import com.samsung.android.camera.core2.exception.InvalidOperationException;
import com.samsung.android.camera.core2.ml.DeviceStateReader;
import com.samsung.android.camera.core2.node.NodeChain;
import com.samsung.android.camera.core2.processor.RecoveryProcessManager.RecoveryProcessSequence;
import com.samsung.android.camera.core2.processor.container.NodeChainKeyContainer;
import com.samsung.android.camera.core2.processor.draftSaving.SavingDraftImageTaskManager;
import com.samsung.android.camera.core2.processor.gppm.GppmStateManager;
import com.samsung.android.camera.core2.processor.gppm.IEventHandler;
import com.samsung.android.camera.core2.processor.gppm.NotificationMessageReader;
import com.samsung.android.camera.core2.processor.gppm.StateObserver;
import com.samsung.android.camera.core2.processor.nodeController.PppNodeController;
import com.samsung.android.camera.core2.processor.postProcessState.PostProcessState;
import com.samsung.android.camera.core2.processor.postProcessState.PostProcessStateCallback;
import com.samsung.android.camera.core2.processor.postProcessState.PostProcessStateManager;
import com.samsung.android.camera.core2.processor.postSaving.PostSavingStateManagerGroup;
import com.samsung.android.camera.core2.processor.postSaving.module.PostSavingState;
import com.samsung.android.camera.core2.processor.request.PostProcessRequest;
import com.samsung.android.camera.core2.processor.request.ProcessRequest;
import com.samsung.android.camera.core2.processor.util.RecoveryJsonDataWriter;
import com.samsung.android.camera.core2.processor.work.PostProcessorWork;
import com.samsung.android.camera.core2.processor.work.PostProcessorWorkManager;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.DirectBufferPool;
import com.samsung.android.camera.core2.util.DynamicShotUtils;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageFile;
import com.samsung.android.camera.core2.util.MemoryUtils;
import com.samsung.android.camera.core2.util.PLog;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Predicate;
import java.util.stream.IntStream;

public class PostProcessor extends ProcessorBase implements IEventHandler, PostProcessStateCallback {
    private static final String TAG = "PostProcessor";

    private static final String PROVIDER_AUTHORITY = "com.samsung.provider.gppm/ppapp_info";
    private static final Uri GPP_URI = Uri.parse("content://" + PROVIDER_AUTHORITY);

    private final Context mContext;
    private final ActivityManager mActivityManager;
    private final PowerManager mPowerManager;
    private final SequenceSet mSequenceSet = new SequenceSet();

    private final ProcessRequestCollectionTracker mProcessRequestCollectionTracker = new ProcessRequestCollectionTracker();
    private final ProcessCallbackSequencer mProcessCallbackSequencer = new ProcessCallbackSequencer();

    private final DirectBufferPool mProcessMemoryBufferPool;
    private final DirectBufferPool mProcessFileBufferPool;
    private final PostProcessThread mPostProcessThread;
    private final PostProcessStateManager mPostProcessStateManager;
    private final Timer mDraftImageFileDeleteTimer = new Timer();
    private final boolean mIsGPPMEnabled;

    @GuardedBy("mPostSavingExecutorManager")
    private final PostSavingStateManagerGroup mPostSavingStateManagerGroup;

    private final SavingDraftImageTaskManager mSavingDraftImageTaskManager;

    private final PostProcessorThreadCallback mPostProcessorThreadCallback = new PostProcessorThreadCallback() {
        NodeChain.Key<ImageBuffer, ImageBuffer> nodeChainKey = null;
        int nodeChainDsExtraInfo = 0;
        int nodeChainDsMode = 0;

        @Override
        public void onSequencePrepared(@NonNull PostProcessRequest request) {
            if (isDifferentRequestInfo(request)) {
                // Previous running instance and current request are different. So remove previous request from nodeChain
                PLog.i(TAG, "deinitializing nodeChain - request info is different");
                mNodeControllerStateManager.deinitialize(/*deinitAllNodeChain*/true);
            }
            savePreviousRequestInfo(request.getNodeChainKey(), request.getDsExtraInfo(), request.getDsMode());

            final ExtraBundle extraBundle = request.getExtraBundle();
            // for "normal" / "abort during recovery" cases.
            // Should not increase retry count.
            // extraBundle nonNull means it is "recovery" && "not aborted" case.
            if (null != extraBundle.get(ExtraBundle.PROCESSOR_INFO_PPP_RECOVERY_TRY_COUNT)) {
                updateRecoveryTryCountToRecoveryFile(request, extraBundle);
                extraBundle.remove(ExtraBundle.PROCESSOR_INFO_PPP_RECOVERY_TRY_COUNT);
            }

            extraBundle.put(ExtraBundle.IS_BACKGROUND_RESTRICTED, mActivityManager.isBackgroundRestricted());
            mNodeControllerStateManager.initialize(request);
        }

        @Override
        public ProcessResult<ImageBuffer> onSequenceProcessing(@NonNull PostProcessRequest processRequest) {
            return process(processRequest.getNodeChainKey(), processRequest);
        }

        @Override
        public void onSequenceCanceled(@NonNull PostProcessRequest request) {
            final int ppSequenceId = request.getPpSequenceId();
            final int dsMode = request.getDsMode();
            final int dsExtraInfo = request.getDsExtraInfo();
            if (mSequenceSet.remove(ppSequenceId, DynamicShotUtils.isPendingRequest(dsMode, dsExtraInfo))) {
                PLog.i(TAG, "onSequenceCanceled(ppSequence id %d) - activatedSequenceCount(%d), pendingSequenceCount(%d)", ppSequenceId,
                        mSequenceSet.getActivatedSequenceStackedCount(), mSequenceSet.getPendingSequenceStackedCount());
                onSequenceCountChanged();
            }
            endSequence();
            mPostSavingStateManagerGroup.runCancel(request.getPpSequenceId(), /*savingInfoContainerConsumer*/null);
        }

        public void onSequenceAborted(int ppSequenceId, int dsMode, int dsExtraInfo) {
            if (mSequenceSet.remove(ppSequenceId, DynamicShotUtils.isPendingRequest(dsMode, dsExtraInfo))) {
                PLog.i(TAG, "onSequenceAborted(ppSequence id %d) - activatedSequenceCount(%d), pendingSequenceCount(%d)", ppSequenceId,
                        mSequenceSet.getActivatedSequenceStackedCount(), mSequenceSet.getPendingSequenceStackedCount());
                onSequenceCountChanged();
            }
            endSequence();
            mPostProcessStateManager.endSequence();
            if (mPostProcessStateManager.getCurrentStateName() == PostProcessState.PostProcessStateName.PAUSED) {
                savePreviousRequestInfo( /*nodeChainKey*/null, /*nodeChainExtraInfo*/0, /*nodeChainDsMode*/ 0);
            }

            mNodeControllerStateManager.deinitialize(/*deinitAllNodeChain*/true);
        }

        @Override
        public void onSequenceCompleted(File resultFile, CamCapability camCapability, ProcessResult<ImageBuffer> processResult, int processedOption) {
            final int ppSequenceId = processResult.ppSequenceId();
            final ImageBuffer resultBuffer = processResult.data();
            final ExtraBundle resultExtraBundle = processResult.extraBundle();

            PLog.i(TAG, "PostProcessThread - onSequenceCompleted(sequenceId %d)", ppSequenceId);

            if (mPostSavingStateManagerGroup.runComplete(ppSequenceId, Objects.requireNonNull(resultBuffer), resultExtraBundle, PostProcessor.this::onDraftImageSaved)) {
                Optional.ofNullable(mPostSavingStateManagerGroup.getCurrentPostSavingStateName(ppSequenceId))
                        .filter(Predicate.isEqual(PostSavingState.StateType.PROCESSED.name()))
                        .ifPresent(__ -> mSavingDraftImageTaskManager.setSkipSaveDraftImage(ppSequenceId));
            }
            resultExtraBundle.release();

            final ProcessCallback processCallback = mProcessorCallback;
            if (null != processCallback) {
                processCallback.onProcessCompleted(processResult, resultFile);
            } else {
                PLog.w(TAG, "PostProcessThread - onSequenceCompleted : can't invoke onProcessCompleted, callback is null");
            }
        }

        @Override
        public void onSequenceEnded(int ppSequenceId, int dsMode, int dsExtraInfo) {
            if (mSequenceSet.remove(ppSequenceId, DynamicShotUtils.isPendingRequest(dsMode, dsExtraInfo))) {
                PLog.i(TAG, "onSequenceEnded(ppSequence id %d) - activatedSequenceCount(%d), pendingSequenceCount(%d)", ppSequenceId,
                        mSequenceSet.getActivatedSequenceStackedCount(), mSequenceSet.getPendingSequenceStackedCount());
                onSequenceCountChanged();
            }
            endSequence();
            mPostProcessStateManager.endSequence();
            MotionPhotoManager.getInstance().removeMotionPhotoInfoIfExist(ppSequenceId);
            if (mPostProcessStateManager.getCurrentStateName() == PostProcessState.PostProcessStateName.PAUSED) {
                savePreviousRequestInfo( /*nodeChainKey*/null, /*nodeChainExtraInfo*/0, /*nodeChainDsMode*/ 0);
            }
            mNodeControllerStateManager.deinitialize(/*deinitAllNodeChain*/false);
        }

        @Override
        public void onSequenceError(int ppSequenceId, int dsMode, int dsExtraInfo) {
            if (mSequenceSet.remove(ppSequenceId, DynamicShotUtils.isPendingRequest(dsMode, dsExtraInfo))) {
                PLog.i(TAG, "onSequenceError(ppSequence id %d) - activatedSequenceCount(%d), pendingSequenceCount(%d)", ppSequenceId,
                        mSequenceSet.getActivatedSequenceStackedCount(), mSequenceSet.getPendingSequenceStackedCount());
                onSequenceCountChanged();
            }
            endSequence();
            mPostSavingStateManagerGroup.recovery(ppSequenceId);
            if (!NodeChainKeyContainer.isSupportIncompleteMerge(dsMode)) {
                PLog.i(TAG, "deinitializing because of sequence error");
                mNodeControllerStateManager.deinitialize(NodeChainKeyContainer.getNodeChainKey(dsMode));
            }
        }

        @Override
        public void onRequestStackEmpty() {
            releaseBufferPool();
            if (MemoryUtils.isNeedDeinitSolution(mActivityManager)) {
                mNodeControllerStateManager.deinitialize(/*deinitAllNodeChain*/true);
            }
        }

        @Override
        public void onThreadStarted(@NonNull Map<Path, List<Path>> recoveryPathMap) {
            PostProcessorWorkManager.getInstance(mContext).cancel(PostProcessorWork.RECOVERY_DRAFT_IMAGE);
            startRecoveryProcess(recoveryPathMap);
            MotionPhotoManager.getInstance().onCreate();
        }

        @Override
        public void onThreadEnded() {
            savePreviousRequestInfo(/*nodeChainKey*/null, /*nodeChainExtraInfo*/0, /*nodeChainDsMode*/0);
            MotionPhotoManager.getInstance().onDestroy();

            releaseBufferPool();
            releaseNodeChain();

            mSavingDraftImageTaskManager.close();
            mDraftImageFileDeleteTimer.cancel();
            mDraftImageFileDeleteTimer.purge();

            if (mIsGPPMEnabled) {
                deInitializeStateObserver();
            }

            final ProcessorStatusCallback processorStatuscallback = mProcessorStatusCallback;
            if (null != processorStatuscallback) {
                processorStatuscallback.onPostProcessorEnded();
            }
        }

        private boolean isDifferentRequestInfo(PostProcessRequest request) {
            return !Objects.equals(nodeChainKey, request.getNodeChainKey())
                    || nodeChainDsExtraInfo != request.getDsExtraInfo()
                    || nodeChainDsMode != request.getDsMode();
        }

        private void savePreviousRequestInfo(@Nullable NodeChain.Key<ImageBuffer, ImageBuffer> nodeChainKey, int nodeChainExtraInfo, int nodeChainDsMode) {
            this.nodeChainKey = nodeChainKey;
            this.nodeChainDsExtraInfo = nodeChainExtraInfo;
            this.nodeChainDsMode = nodeChainDsMode;
        }

        private void updateRecoveryTryCountToRecoveryFile(@NonNull PostProcessRequest request, @NonNull ExtraBundle extraBundle) {
            final int ppSequenceId = request.getPpSequenceId();
            final int currentRecoveryTryCount = Objects.requireNonNull(extraBundle.get(ExtraBundle.PROCESSOR_INFO_PPP_RECOVERY_TRY_COUNT));
            final String recoveryDataFileName = Objects.requireNonNull(extraBundle.get(ExtraBundle.PROCESSOR_INFO_PPP_RECOVERY_DATA_FILE_NAME));
            final Path recoveryDataFilePath = SECURE_PPP_DIRECTORY_PATH.resolve(recoveryDataFileName + JSON_FILE_EXTENSION);

            RecoveryJsonDataWriter.append(ppSequenceId, recoveryDataFilePath, builder -> {
                PLog.i(TAG, "updateRecoveryTryCountToRecoveryFile(ppSequence id %d) - increase retry count(%d -> %d)",
                        ppSequenceId, currentRecoveryTryCount, currentRecoveryTryCount + 1);
                builder.recoveryTryCount = currentRecoveryTryCount + 1;
            });
        }
    };

    private ProcessCallback mProcessorCallback;
    private ProcessorStatusCallback mProcessorStatusCallback;
    private HandlerThread mStateObserverHandlerThread;
    private ContentObserver mStateObserver;

    /**
     * <div class="camera_en">
     * Constructor of post processor.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Post processor 생성자.
     * </div>
     *
     * @param context context.
     * @throws InvalidOperationException If binding service is failed.
     */
    public PostProcessor(Context context) {
        super(new PppNodeController(context));

        PLog.i(TAG, "PostProcess(context %s)", context);

        mContext = context;

        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        mPostProcessStateManager = new PostProcessStateManager(/*processorStatusCallback*/this);

        mPostSavingStateManagerGroup = new PostSavingStateManagerGroup(mContext);

        mProcessMemoryBufferPool = new DirectBufferPool(context);
        mProcessFileBufferPool = new DirectBufferPool(context, 2);

        mPostProcessThread = new PostProcessThread(context, mPostProcessStateManager, mPostSavingStateManagerGroup, mPostProcessorThreadCallback);
        mSavingDraftImageTaskManager = new SavingDraftImageTaskManager(mContext,
                new DeviceStateReader(mActivityManager, mPowerManager, mPostProcessThread::getOverHeatHint));
//        CaptureMetricsRepository.getInstance(mContext).deleteFromIdAsync(194);
//        CaptureMetricsRepository.getInstance(mContext).deleteByDsModeBlocking(0);
//        DraftSequenceExecutionPredictor.warmUp(mContext);

        mIsGPPMEnabled = GppmStateManager.isGPPMEnabled(mContext);
        if (mIsGPPMEnabled) {
            initializeStateObserver();
        }
        GppmStateManager.notifyForegroundApp(mContext);
    }

    /**
     * <div class="camera_en">
     * Start PostProcessThread.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PostProcessThread 를 start 한다.
     * </div>
     */
    public void startPostProcessThread() {
        mPostProcessThread.start();
    }

    /**
     * <div class="camera_en">
     * Pause post processor.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Post processor 를 pause 한다.
     * </div>
     *
     * @throws InvalidOperationException If postProcessThread waitForNextSequenceState is failed.
     */
    public void pause() {
        mPostProcessStateManager.pause();
        CLog.i(TAG, "PostProcessor paused");
    }

    /**
     * <div class="camera_en">
     * Resume post processor.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Post processor 를 resume 한다.
     * </div>
     */
    public void resume() {
        mPostProcessStateManager.resume(0);
        CLog.i(TAG, "PostProcessor resumed");
    }

    /**
     * <div class="camera_en">
     * Resume post processor after delay.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Post processor 를 delay 시간 이후 resume 한다.
     * </div>
     *
     * @param delay delay in milliseconds to notify that sequence resume.
     * @return if {@code delay} is greater than 0 return future object or null.
     */
    public ScheduledFuture<?> resumeAfter(long delay) {
        return mPostProcessStateManager.resume(delay);
    }

    /**
     * <div class="camera_en">
     * Try deinitialize post processor.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Post processor deinitialize 를 시도한다.
     * </div>
     *
     * @throws InvalidOperationException If connection service is failed.
     */
    public void tryDeinitialize() {
        PLog.i(TAG, "tryDeinitialize");
        mPostProcessThread.requestExit();
        GppmStateManager.notifyBackgroundApp(mContext);
    }

    /**
     * <div class="camera_en">
     * Try to recycle post processor.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Post processor 재활용 시도한다.
     * </div>
     * @param needAbortPendingRequest If the camera is re-entered, true.
     * @return if recycle post processor is success return true or false.
     * @throws InvalidOperationException If connection service is failed or if {@link PostProcessStateManager#cancelExit} is failed.
     */
    public boolean tryRecycle(boolean needAbortPendingRequest) {
        final boolean result = mPostProcessStateManager.cancelExit();
        PLog.i(TAG, "tryRecycle - " + result + ", needAbort : " + needAbortPendingRequest);
        if (result) {
            GppmStateManager.notifyForegroundApp(mContext);
            if (needAbortPendingRequest && mPostProcessThread.isPendingRequestEnabled()) {
                mPostProcessThread.abortCurrentSequence(processRequest -> {
                    if (!DynamicShotUtils.isPendingRequest(processRequest.getDsMode(), processRequest.getDsExtraInfo())) {
                        CLog.i(TAG, "abortCurrentSequence - abort skip, it is not PendingRequest");
                        return;
                    }
                    mNodeControllerStateManager.abort(processRequest.getPpSequenceId(), processRequest.getNodeChainKey());
                });
            } else {
                CLog.i(TAG, "abortCurrentSequence - abort skip, abort is not required.");
            }
        }
        return result;
    }

    /**
     * <div class="camera_en">
     * Start batch works which consist of recovery draft images, removing process temp files,
     * removing exceeded P log files and removing expired core2 DB records.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     *  Draft image 복구, process 임시파일 삭제, 오래된 P log 삭제, 만료된 core2 DB 데이터 삭제로 구성된 batch work 들을 시작한다.
     * </div>
     *
     * @param recoveryPathMap recoveryPathMap
     */
    private void startRecoveryProcess(@NonNull Map<Path, List<Path>> recoveryPathMap) {
        PLog.i(TAG, "startRecoveryProcess E");
        final Instant now = Instant.now();

        for (Map.Entry<Path, List<Path>> pathEntry : recoveryPathMap.entrySet()) {
            // step 1) delete extra images
            List<Path> pendingRecoveryFilePathList = RecoveryProcessManager.deleteExtraDraftImages(mContext, pathEntry.getValue());

            // step 2) recovering draft image without recovery data
            pendingRecoveryFilePathList = RecoveryProcessManager.recoveryDraftImageWithoutRecoveryData(mContext, pendingRecoveryFilePathList);

            // step 3) deleting dangling recovery files
            RecoveryProcessManager.deleteDanglingRecoveryFiles(pendingRecoveryFilePathList);

            // step 4) adding recovery process sequences
            RecoveryProcessManager.makeRecoveryProcessSequences(mContext, pendingRecoveryFilePathList)
                    .forEach(this::addRecoveryProcessSequenceToPppStack);
        }

        PLog.i(TAG, "startRecoveryProcess X (%d ms)", Duration.between(now, Instant.now()).toMillis());
    }

    /**
     * <div class="camera_en">
     * Put the sequence to be recovered into the ppp stack and process them.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     *  복구 예정 sequence 를 ppp stack 에 넣어서 처리 하도록 한다.
     * </div>
     *
     * @param recoveryProcessSequence recoveryProcessSequence
     */
    private void addRecoveryProcessSequenceToPppStack(@NonNull RecoveryProcessSequence recoveryProcessSequence) {
        final int ppSequenceId = recoveryProcessSequence.getPpSequenceId();
        final int dsCondition = recoveryProcessSequence.getDsCondition();
        final int dsMode = DynamicShotUtils.getDsMode(dsCondition);
        final int dsExtraInfo = recoveryProcessSequence.getDsExtraInfo();

        if (!mPostSavingStateManagerGroup.isSequenceRegistered(ppSequenceId)) {
            mSequenceSet.add(ppSequenceId, DynamicShotUtils.isPendingRequest(dsMode, dsExtraInfo));
            PLog.i(TAG, "addRecoveryProcessSequenceToPppStack(ppSequence id %d) - activatedSequenceCount(%d), pendingSequenceCount(%d)", ppSequenceId,
                    mSequenceSet.getActivatedSequenceStackedCount(), mSequenceSet.getPendingSequenceStackedCount());
            onSequenceCountChanged();
            final SavingInfoContainer savingInfoContainer = recoveryProcessSequence.createSavingInfoContainerForRecovery(mContext);
            mPostSavingStateManagerGroup.createDraftPostSavingState(ppSequenceId, savingInfoContainer,
                    Objects.requireNonNull(mNodeControllerStateManager.getDraftRecoveryNodeChainAccessor()));
        }

        for (ProcessRequest<ImageFile> processRequest : recoveryProcessSequence.getProcessRequests()) {
            final PostProcessRequest postProcessRequest = mPostProcessThread.asPostProcessFileRequest(
                    NodeChainKeyContainer.getNodeChainKey(processRequest.getDsMode()),
                    processRequest,
                    mProcessFileBufferPool);

            mPostProcessThread.addPostProcessRequestAndNotify(postProcessRequest);
        }
    }

    /**
     * <div class="camera_en">
     * Put ResourceRequest into the ppp stack to process it.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ResourceRequest 를 ppp stack 에 넣어서 처리 하도록 한다.
     * </div>
     *
     * @param postProcessRequest postProcessRequest
     */
    public void processResourceRequest(@NonNull PostProcessRequest postProcessRequest) {
        final int ppSequenceId = postProcessRequest.getPpSequenceId();
        final int dsMode = postProcessRequest.getDsMode();
        final int dsExtraInfo = postProcessRequest.getDsExtraInfo();

        trackAndCheckProcessRequestCollection(postProcessRequest);
        if (!mPostSavingStateManagerGroup.isSequenceRegistered(ppSequenceId)) {
            mSequenceSet.add(ppSequenceId, DynamicShotUtils.isPendingRequest(dsMode, dsExtraInfo));
            PLog.i(TAG, "processResourceRequest(ppSequence id %d) - sequenceSet activatedSequenceCount(%d), pendingSequenceCount(%d)", ppSequenceId,
                    mSequenceSet.getActivatedSequenceStackedCount(), mSequenceSet.getPendingSequenceStackedCount());
            onSequenceCountChanged();
            mPostSavingStateManagerGroup.createPostSavingState(ppSequenceId,
                    Objects.requireNonNull(postProcessRequest.getExtraBundle().get(ExtraBundle.MULTI_PICTURE_DATA_RESULT_FILE)),
                    postProcessRequest.getExtraBundle().get(ExtraBundle.MULTI_PICTURE_DATA_EXTRA_RESULT_FILES),
                    Objects.requireNonNull(mNodeControllerStateManager.getDraftRecoveryNodeChainAccessor()));
        }
        mPostProcessThread.addPostProcessRequestAndNotify(postProcessRequest);
    }

    /**
     * <div class="camera_en">
     * Release memory buffer resource.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Memory buffer 자원을 해제한다.
     * </div>
     */
    private void releaseBufferPool() {
        // release all resource
        mProcessMemoryBufferPool.releaseBuffers();
        mProcessFileBufferPool.releaseBuffers();
    }

    /**
     * <div class="camera_en">
     * Check details about ProcessRequest collection to be processed in PostProcessThread.
     * When collection is completed and the draft image is saved, an appropriate callback is called if collection is stopped.
     * Collection completed + save draft image: processCallback.onRequestCollectionCompleted
     * When collection is stopped: processCallback.onRequestCollectionStopped
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PostProcessThread 에서 처리할 ProcessRequest 수집에 대한 사항을 확인 한다.
     * 수집 완료 및 draft image 저장된 경우, 수집이 중단 됐을 경우 그에 알맞은 callback 을 호출 한다.
     * 수집 완료 + draft image 저장 : processCallback.onRequestCollectionCompleted
     * 수집 중단시 : processCallback.onRequestCollectionStopped
     * </div>
     *
     * @param processRequest processRequest to be processed by PostProcessThread
     */
    private void trackAndCheckProcessRequestCollection(@NonNull ProcessRequest<ImageBuffer> processRequest) {
        final int ppSequenceId = processRequest.getPpSequenceId();
        final int dsMode = processRequest.getDsMode();

        PLog.i(TAG, "trackAndCheckProcessRequestCollection(%s) ppSequenceId %d processCount %d/%d", processRequest.getUsage(),
                ppSequenceId, processRequest.getCurrentProcessCount(), processRequest.getTotalProcessCount());

        if (mProcessRequestCollectionTracker.trackAndCheckIfCollected(processRequest)) {
            mProcessCallbackSequencer.forwardCallbackByRequestCollectionCompleted(ppSequenceId, mProcessorCallback);
        } else if (NodeChainKeyContainer.isSupportIncompleteMerge(dsMode) && processRequest.getUsage() == ERROR) {
            // send onPostProcessingFrameCollectionStopped callback
            // to avoid timeout of onCaptureAvailable(if there is no CaptureAvailable from HAL) and FrameCollectionCompleted
            mProcessCallbackSequencer.forwardCallbackByRequestCollectionStopped(ppSequenceId, mProcessorCallback);
        }
    }

    /**
     * <div class="camera_en">
     * Convert processRequest as PostProcessRequest.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ProcessRequest 를 PostProcessRequest로 변환한다.
     * </div>
     *
     * @param processRequest processRequest
     */
    public PostProcessRequest asPostProcessRequest(@NonNull ProcessRequest<ImageBuffer> processRequest) {
        return mPostProcessThread.asPostProcessRequest(
                NodeChainKeyContainer.getNodeChainKey(processRequest.getDsMode()),
                processRequest,
                mProcessMemoryBufferPool,
                mProcessFileBufferPool);
    }

    /**
     * <div class="camera_en">
     * Save draft image.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Draft image 를 저장한다.
     * </div>
     *
     * @param processRequest object containing draft image.
     */
    public void processDraftRequest(@NonNull ProcessRequest<ImageBuffer> processRequest) {
        final int ppSequenceId = processRequest.getPpSequenceId();
        final int dsMode = processRequest.getDsMode();
        final int dsExtraInfo = processRequest.getDsExtraInfo();
        final ExtraBundle extraBundle = processRequest.getExtraBundle();
        final boolean isPendingRequest = DynamicShotUtils.isPendingRequest(dsMode, dsExtraInfo);

        trackAndCheckProcessRequestCollection(processRequest);
        if (!mPostSavingStateManagerGroup.isSequenceRegistered(ppSequenceId)) {
            mSequenceSet.add(ppSequenceId, isPendingRequest);
            PLog.i(TAG, "processDraftRequest(ppSequence id %d) - sequenceSet activatedSequenceCount(%d), pendingSequenceCount(%d)", ppSequenceId,
                    mSequenceSet.getActivatedSequenceStackedCount(), mSequenceSet.getPendingSequenceStackedCount());
            onSequenceCountChanged();
            mPostSavingStateManagerGroup.createPostSavingState(ppSequenceId,
                    Objects.requireNonNull(processRequest.getExtraBundle().get(ExtraBundle.MULTI_PICTURE_DATA_RESULT_FILE)),
                    extraBundle.get(ExtraBundle.MULTI_PICTURE_DATA_EXTRA_RESULT_FILES),
                    Objects.requireNonNull(mNodeControllerStateManager.getDraftRecoveryNodeChainAccessor()));
        }

        mSavingDraftImageTaskManager.addRequest(processRequest,
                mPostSavingStateManagerGroup,
                Objects.requireNonNull(mNodeControllerStateManager.getDraftJpegNodeChainAccessor()),
                PostProcessor.this::onDraftImageSaved,
                PostProcessor.this::onDraftImageSkipped);
    }

    /**
     * <div class="camera_en">
     * The callback function that notifies whether the sequence count of PostProcessor has changed. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PostProcessor 의 sequence count가 변경되었음을 알리는 callback 함수. <br>
     * </div>
     */
    private void onSequenceCountChanged() {
        final int activatedSequenceStackedCount = mSequenceSet.getActivatedSequenceStackedCount();
        final int pendingSequenceStackedCount = mSequenceSet.getPendingSequenceStackedCount();

        final ProcessorStatusCallback processorStatuscallback = mProcessorStatusCallback;
        if (null != processorStatuscallback) {
            processorStatuscallback.onPostProcessorSequenceCountChanged(activatedSequenceStackedCount, pendingSequenceStackedCount);
        }

        if (mPostProcessThread.isEnablePppLogging()) {
            PostProcessThread.sendLoggingMessage(PostProcessorLoggingService.MSG_PPP_ACTIVATED_SEQUENCE_COUNT_CHANGED, activatedSequenceStackedCount, 0, null);
            PostProcessThread.sendLoggingMessage(PostProcessorLoggingService.MSG_PPP_PENDING_SEQUENCE_COUNT_CHANGED, pendingSequenceStackedCount, 0, null);
        }
    }

    /**
     * <div class="camera_en">
     * Get current PostProcessor state. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PostProcessor 의 현재 State 를 가져온다. <br>
     * </div>
     *
     * @return current PostProcessor state.
     */
    public int getCurrentState() {
        return mPostProcessStateManager.getCurrentStateName().getId();
    }

    /**
     * <div class="camera_en">
     * get number Of Activated Sequences stacked in PostProcessor.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PostProcessor 에 쌓여있는 활성화된 Sequence 개수를 가져온다.
     * </div>
     *
     * @return Number Of Activated Sequence stacked in PostProcessor.
     */
    public int getActivatedSequenceStackedCount() {
        return mSequenceSet.getActivatedSequenceStackedCount();
    }

    /**
     * <div class="camera_en">
     * get number Of Pending Sequences stacked in PostProcessor.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PostProcessor 에 쌓여있는 보류된 Sequence 개수를 가져온다.
     * </div>
     *
     * @return Number Of Pending Sequence Stacked in PostProcessor
     */
    public int getPendingSequenceStackedCount() {
        return mSequenceSet.getPendingSequenceStackedCount();
    }

    /**
     * <div class="camera_en">
     * start to generate the motion photo video
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Motion photo video 생성을 시작한다.
     * </div>
     *
     * @param motionPhotoStoreInfo MotionPhotoStoreInfo
     */
    public void storeMotionPhotoPpp(@NonNull MotionPhotoManager.MotionPhotoStoreInfo motionPhotoStoreInfo) {
        MotionPhotoManager.getInstance().storeMotionPhotoPpp(motionPhotoStoreInfo);
    }

    /**
     * <div class="camera_en">
     * Set processor callback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Processor 을 등록한다.
     * </div>
     *
     * @param callback processor callback.
     */
    public void setProcessorCallback(@NonNull ProcessCallback callback) {
        mProcessorCallback = callback;
        mPostProcessThread.setProcessorCallback(callback);
    }

    /**
     * <div class="camera_en">
     * Set processor status callback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Processor status callback 을 등록한다.
     * </div>
     *
     * @param callback ProcessorStatusCallback.
     */
    public void setProcessorStatusCallback(@NonNull ProcessorStatusCallback callback) {
        mProcessorStatusCallback = callback;
    }

    /**
     * <div class="camera_en">
     * Send process error callback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Process error callback 을 보낸다.
     * </div>
     *
     * @param ppSequenceId ppSequenceId.
     */
    private void sendProcessErrorCallback(int ppSequenceId) {
        final ProcessCallback processorCallback = mProcessorCallback;
        if (null != processorCallback) {
            processorCallback.onProcessError(ppSequenceId);
        } else {
            PLog.w(TAG, "can't invoke onProcessError, callback is null");
        }
    }

    /**
     * <div class="camera_en">
     * enable PendingRequest. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PendingRequest를 활성화한다. <br>
     * </div>
     *
     * @param enable if value is true, pending request is enabled.
     */
    public void enablePendingRequest(boolean enable) {
        mPostProcessThread.enablePendingRequest(enable);
    }

    private void initializeStateObserver() {
        PLog.i(TAG, "initializeStateObserver");

        mStateObserverHandlerThread = new HandlerThread("ContentObserver");
        mStateObserverHandlerThread.start();
        mStateObserver = new StateObserver(new Handler(mStateObserverHandlerThread.getLooper()), this);
        mContext.getContentResolver().registerContentObserver(GPP_URI, true, mStateObserver);
    }

    private void deInitializeStateObserver() {
        PLog.i(TAG, "deInitializeStateObserver");

        if (null != mStateObserverHandlerThread) {
            mStateObserverHandlerThread.quitSafely();
            try {
                mStateObserverHandlerThread.join();
            } catch (InterruptedException e) {
                CLog.e(TAG, "InterruptedException : " + e.getMessage());
            }
            mStateObserverHandlerThread = null;
        }

        if (mContext != null && mStateObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mStateObserver);
            mStateObserver = null;
        }
    }

    @Override
    public void onStateChanged(@NonNull Uri uri) {
        final NotificationMessageReader reader = new NotificationMessageReader();
        if (reader.isRequestStartPermissionByNotify(uri, mContext.getPackageName())) {
            PLog.i(TAG, "StateObserver onStateChanged - Request Success, PostProcessThread.getSequenceState() = " + mPostProcessStateManager.getCurrentStateName());
            GppmStateManager.requestPermissionEnabled();
            this.resume();
        } else if (reader.isStopByNotify(uri, mContext.getPackageName())) {
            PLog.i(TAG, "StateObserver onStateChanged - Stop");
            GppmStateManager.updateStateTo(mContext, GppmState.STOPPING);
            this.pause();
        }
    }

    private void onDraftImageSaved(SavingInfoContainer savingInfoContainer) {
        PLog.i(TAG, "onDraftImageSaved");
        final List<SavingInfo> savingInfoList = savingInfoContainer.getSavingInfoList();
        final int ppSequence = savingInfoContainer.getPpSequenceId();
        final Uri[] secMpUris = new Uri[savingInfoList.size()];
        final File[] resultFiles = new File[savingInfoList.size()];

        IntStream.range(0, savingInfoList.size()).forEach(idx -> {
            secMpUris[idx] = savingInfoList.get(idx).getSecMpUri();
            resultFiles[idx] = savingInfoList.get(idx).getResultFile();
        });

        mProcessCallbackSequencer.forwardCallbackByDraftImageSaved(ppSequence, secMpUris, resultFiles, mProcessorCallback);
    }

    private void onDraftImageSkipped(int ppSequenceId) {
        PLog.i(TAG, "onDraftImageSkipped");
        mProcessCallbackSequencer.forwardCallbackByDraftImageSkipped(ppSequenceId, mProcessorCallback);
    }

    /**
     * <div class="camera_en">
     * Get tid of PostProcessorThread <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * PostProcessorThread 의 tid 를 가져온다. <br>
     * </div>
     *
     * @return PostProcessThread tid.
     */
    public int getPostProcessThreadTid() {
        return mPostProcessThread.getThreadId();
    }

    @Override
    @GuardedBy("mPostProcessStateLock")
    public void onResumed(@NonNull PostProcessState.PostProcessStateName currentPostProcessStateName) {
        CLog.i(TAG, "PostProcessor onResumed : " + currentPostProcessStateName);
    }

    @Override
    @GuardedBy("mPostProcessStateLock")
    public void onPaused(@NonNull PostProcessState.PostProcessStateName currentPostProcessStateName) {
        CLog.i(TAG, "PostProcessor onPaused : " + currentPostProcessStateName);
        mNodeControllerStateManager.deinitialize(/*deinitAllNodeChain*/true);
    }

    @Override
    @GuardedBy("mPostProcessStateLock")
    public void onAborted(@NonNull PostProcessState.PostProcessStateName currentPostProcessStateName) {
        CLog.i(TAG, "PostProcessor onAborted : " + currentPostProcessStateName);
        mPostProcessThread.abortCurrentSequence(processRequest -> mNodeControllerStateManager.abort(processRequest.getPpSequenceId(), processRequest.getNodeChainKey()));
    }

    /**
     * <div class="camera_en">
     * SequenceSet class, manage Sequences of PostProcessor.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * SequenceSet class, PostProcessor Sequence 를 관리하는 class 이다.
     * </div>
     */
    private static class SequenceSet {
        private final Set<Integer> mActivatedSequenceSet = Collections.synchronizedSet(new HashSet<>());
        private final Set<Integer> mPendingSequenceSet = Collections.synchronizedSet(new HashSet<>());

        /**
         * <div class="camera_en">
         * Add ppSequenceId to sequence set.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * sequence set 에 ppSequenceId 를 추가한다.
         * </div>
         *
         * @param ppSequenceId ppSequenceId.
         * @param isPending    pending request or not.
         */
        public boolean add(int ppSequenceId, boolean isPending) {
            if (isPending) {
                return mPendingSequenceSet.add(ppSequenceId);
            } else {
                return mActivatedSequenceSet.add(ppSequenceId);
            }
        }

        /**
         * <div class="camera_en">
         * Remove sequenceId from sequence set.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * sequence set 에 sequenceId 를 제거한다.
         * </div>
         *
         * @param sequenceId sequenceId.
         * @param isPending  pending request or not.
         */
        public boolean remove(int sequenceId, boolean isPending) {
            if (isPending) {
                return mPendingSequenceSet.remove(sequenceId);
            } else {
                return mActivatedSequenceSet.remove(sequenceId);
            }
        }

        /**
         * <div class="camera_en">
         * get number Of Activated Sequences stacked in PostProcessor.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * PostProcessor 에 쌓여있는 활성화된 Sequence 개수를 가져온다.
         * </div>
         */
        public int getActivatedSequenceStackedCount() {
            return mActivatedSequenceSet.size();
        }

        /**
         * <div class="camera_en">
         * get number Of Pending Sequences stacked in PostProcessor.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * PostProcessor 에 쌓여있는 보류된 Sequence 개수를 가져온다.
         * </div>
         */
        public int getPendingSequenceStackedCount() {
            return mPendingSequenceSet.size();
        }
    }
}
