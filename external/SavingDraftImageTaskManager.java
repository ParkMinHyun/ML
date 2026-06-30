package com.samsung.android.camera.core2.processor.draftSaving;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.SavingInfoContainer;
import com.samsung.android.camera.core2.ml.CaptureMetrics;
import com.samsung.android.camera.core2.ml.CaptureMetricsRepository;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionProfiler;
import com.samsung.android.camera.core2.processor.nodeController.DraftNodeChainAccessor;
import com.samsung.android.camera.core2.processor.postSaving.PostSavingStateManagerGroup;
import com.samsung.android.camera.core2.processor.request.ProcessRequest;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.ConditionChecker;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.PLog;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SavingDraftImageTaskManager {
    private static final String TAG = "SavingDraftImageTaskManager";
    private final Context context;
    private final Map</*ppSequenceId*/Integer, SavingDraftImageTask> savingDraftImageTaskMap = new ConcurrentHashMap<>();
    private final Set</*ppSequenceId*/Integer> reservedSkipSaveDraftImageIdSet = new HashSet<>();
    private final ScheduledExecutorService savingDraftImageThreadPool = Executors.newSingleThreadScheduledExecutor();
    private boolean waitForSavingDraftImageTaskMapDrain = false;
    private boolean isWatchdogDrainInCurrentSession = false;
    private boolean hasCaptureTimeoutInCurrentSession = false;

    /**
     * <div class="camera_en">
     * Constructor of SavingDraftImageTaskManager.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * SavingDraftImageTaskManager 생성자.
     * </div>
     *
     * @param context               context
     */
    public SavingDraftImageTaskManager(@NonNull Context context) {
        this.context = context;
    }

    /**
     * <div class="camera_en">
     * Receives a request related to Draft processing as input and creates and processes a task.
     * Depending on the number of totalDraftCount, other tasks ({@link SavingMultiDraftImageTask}, {@link SavingDraftImageTask}) can be created.
     * Processes tasks using SingleThreadPool, and if there are many tasks piled up in the task queue,
     * For multi-series tasks, some processing (dual bokeh, filter) steps may be omitted.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Draft 처리와 관련된 Request 를 입력 으로 받아 task 를 만들고 처리 한다.
     * totalDraftCount 의 개수에 따라 다른 task({@link SavingMultiDraftImageTask}, {@link SavingDraftImageTask})가 만들 어질 수 있다.
     * SingleThreadPool 을 이용해 task 를 처리 하며, task queue 에 task 가 많이 쌓여 있을 경우,
     * multi 계열 task 의 경우 일부 processing(dual bokeh, filter) 과정 은 생략 될수 있다.
     * </div>
     *
     * @param processRequest              processRequest
     * @param postSavingStateManagerGroup postSavingStateManagerGroup
     * @param draftJpegNodeChainAccessor  draftJpegNodeChainAccessor
     * @param savedDraftImageConsumer     savedDraftImageConsumer
     * @param skippedDraftImageConsumer   skippedDraftImageConsumer
     */
    public synchronized void addRequest(@NonNull ProcessRequest<ImageBuffer> processRequest,
                                        @NonNull PostSavingStateManagerGroup postSavingStateManagerGroup,
                                        @NonNull DraftNodeChainAccessor draftJpegNodeChainAccessor,
                                        @Nullable Consumer<SavingInfoContainer> savedDraftImageConsumer,
                                        @NonNull Consumer<Integer> skippedDraftImageConsumer) {
        final int ppSequenceId = processRequest.getPpSequenceId();
        final SavingDraftImageTask savingDraftImageTask = savingDraftImageTaskMap.computeIfAbsent(ppSequenceId, __ -> {
            final SavingDraftImageTask task;
            if (processRequest.getTotalDraftCount() == 1) {
                PLog.i(TAG, "addRequest(ppSequenceId:%d) - create SavingSingleDraftImageTask", ppSequenceId);
                task = new SavingSingleDraftImageTask(processRequest,
                        postSavingStateManagerGroup,
                        draftJpegNodeChainAccessor,
                        savedDraftImageConsumer,
                        skippedDraftImageConsumer,
                        this::onTaskFinished);
            } else {
                PLog.i(TAG, "addRequest(ppSequenceId:%d) - create SavingMultiDraftImageTask", ppSequenceId);
                task = new SavingMultiDraftImageTask(processRequest,
                        postSavingStateManagerGroup,
                        draftJpegNodeChainAccessor,
                        savedDraftImageConsumer,
                        skippedDraftImageConsumer,
                        this::onTaskFinished);
            }

            if (reservedSkipSaveDraftImageIdSet.contains(ppSequenceId)) {
                PLog.i(TAG, "addRequest(ppSequenceId:%d) - This task is reserved to skip save the draft image, so skip save draft image.", ppSequenceId);
                task.setSkipSaveDraftImage();
                reservedSkipSaveDraftImageIdSet.remove(ppSequenceId);
            }
            return task;
        });
        PLog.i(TAG, "addRequest(ppSequenceId:%d) - addOriginalBuffer(%d/%d)", ppSequenceId, processRequest.getCurrentDraftCount(), processRequest.getTotalDraftCount(), ppSequenceId);
        savingDraftImageTask.addOriginalBuffer(processRequest.getData(), processRequest.getExtraBundle());

        if (processRequest.getCurrentDraftCount() == processRequest.getTotalDraftCount()) {
            if (waitForSavingDraftImageTaskMapDrain) {
                PLog.i(TAG, "[mhyun2.park] addRequest(ppSequenceId:%d) - save original draft image only until savingDraftImageTaskMap is drained", ppSequenceId);
                savingDraftImageTask.markSaveOriginalDraftImageOnly();
            }
            try {
                PLog.i(TAG, "addRequest(ppSequenceId:%d) - submit savingDraftImageTask", processRequest.getPpSequenceId());
                savingDraftImageThreadPool.submit(savingDraftImageTask);
            } catch (RejectedExecutionException e) {
                PLog.w(TAG, "addRequest(ppSequenceId:%d) - error occurred in submitting task: %s", processRequest.getPpSequenceId(), e);
                CompletableFuture.runAsync(savingDraftImageTask); // using ForkJoinPool.commonPool() which does not need to be shut down
            }
        } else {
            PLog.i(TAG, "addRequest - need more requests for the draft");
        }
    }

    /**
     * <div class="camera_en">
     * Does not save DraftImage.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * DraftImage 저장을 하지 않는다.
     * </div>
     *
     * @param ppSequenceId           ppSequenceId that is the target of skipping DraftImage saving
     */
    public synchronized void setSkipSaveDraftImage(int ppSequenceId) {
        final SavingDraftImageTask savingDraftImageTask = savingDraftImageTaskMap.get(ppSequenceId);
        if (null != savingDraftImageTask) {
            PLog.i(TAG, "setSkipSaveDraftImage(ppSequenceId:%d) - E", ppSequenceId);
            final boolean result = savingDraftImageTask.setSkipSaveDraftImage();
            PLog.i(TAG, "setSkipSaveDraftImage(ppSequenceId:%d) - X(success:%s)", ppSequenceId, result);
        } else {
            PLog.i(TAG, "setSkipSaveDraftImage(ppSequenceId:%d) - The task does not exist. Reserve skip save draft image.", ppSequenceId);
            reservedSkipSaveDraftImageIdSet.add(ppSequenceId);
        }
    }

    /**
     * <div class="camera_en">
     * Cleans up the threadPool and resources for task processing.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Task 처리를 위한 threadPool 및 자원 들을 정리 한다.
     * </div>
     */
    public synchronized void close() {
        shutDownThreadPool();
        savingDraftImageTaskMap.clear();
        reservedSkipSaveDraftImageIdSet.clear();
        waitForSavingDraftImageTaskMapDrain = false;
        isWatchdogDrainInCurrentSession = false;
        hasCaptureTimeoutInCurrentSession = false;
    }

    private synchronized void onTaskFinished(int ppSequenceId) {
        final SavingDraftImageTask savingDraftImageTask = savingDraftImageTaskMap.get(ppSequenceId);
        if (null == savingDraftImageTask) {
            return;
        }

        final ExtraBundle extraBundle = savingDraftImageTask.extraBundle;
        final boolean profilerError = Boolean.TRUE.equals(extraBundle.get(ExtraBundle.DATA_DRAFT_SEQUENCE_ERROR_PROFILER));
        if (profilerError) {
            waitForSavingDraftImageTaskMapDrain = true;
            isWatchdogDrainInCurrentSession |= hasWatchdogTimeout(extraBundle);
            markRemainingTasksToSaveOriginalDraftImageOnly(ppSequenceId);
        }

        try {
            final boolean recordDrainedTaskMetrics = waitForSavingDraftImageTaskMapDrain &&
                    isWatchdogDrainInCurrentSession && !profilerError;
            if (waitForSavingDraftImageTaskMapDrain && !profilerError && !recordDrainedTaskMetrics) {
                cancelDraftSequenceExecution(extraBundle);
                CLog.w(TAG, "[mhyun2.park] onTaskFinished : wait for savingDraftImageTaskMap drain - ppSequenceId=%d, remainingTaskCount=%d", ppSequenceId, savingDraftImageTaskMap.size() - 1);
                return;
            }
            handleCompletedTask(savingDraftImageTask, recordDrainedTaskMetrics);
        } finally {
            savingDraftImageTaskMap.remove(ppSequenceId);
            if (savingDraftImageTaskMap.isEmpty()) {
                waitForSavingDraftImageTaskMapDrain = false;
                isWatchdogDrainInCurrentSession = false;
                hasCaptureTimeoutInCurrentSession = false;
            }
        }
    }

    private void handleCompletedTask(@NonNull SavingDraftImageTask savingDraftImageTask, boolean recordDrainedTaskMetrics) {
        final ExtraBundle extraBundle = savingDraftImageTask.extraBundle;
        final boolean skipSaveDraftImage = savingDraftImageTask.skipSaveDraftImage;
        final boolean isDraftProcessing = Optional.ofNullable(extraBundle.get(ExtraBundle.PROCESSOR_INFO_IS_DRAFT_PROCESSING)).orElse(false);

        final DraftSequenceExecutionProfiler draftSequenceExecutionProfiler = extraBundle.get(ExtraBundle.DATA_DRAFT_SEQUENCE_EXECUTION_PROFILER);
        ConditionChecker.checkNotNull(draftSequenceExecutionProfiler, "draftSequenceExecutionProfiler");

        if (skipSaveDraftImage || (isDraftProcessing && !recordDrainedTaskMetrics)) {
            draftSequenceExecutionProfiler.cancelDraftSequenceExecution();
            CLog.w(TAG, "[mhyun2.park] onTaskFinished : skip insert captureMetric [skipSaveDraftImage: %s, isDraftProcessing: %s]", skipSaveDraftImage, isDraftProcessing);
            return;
        }

        final CaptureMetrics captureMetrics = extraBundle.get(ExtraBundle.DATA_CAPTURE_METRICS);
        ConditionChecker.checkNotNull(captureMetrics, "captureMetrics");

        final boolean hasWatchdogTimeout = hasWatchdogTimeout(captureMetrics);
        if (hasCaptureTimeoutInCurrentSession && !hasWatchdogTimeout) {
            draftSequenceExecutionProfiler.cancelDraftSequenceExecution();
            PLog.w(TAG, "[mhyun2.park] onTaskFinished : skip insert captureMetrics - capture timeout already recorded in current session");
            return;
        }

        // Complete Encoding and record whether this capture overran its timeout.
        final boolean isTimeout = draftSequenceExecutionProfiler.completeDraftSequenceExecution();

        if (isTimeout) {
            hasCaptureTimeoutInCurrentSession = true;
            PLog.e(TAG, "[mhyun2.park] onTaskFinished : capture timeout occurred");
        }
        insertCaptureMetrics(captureMetrics);
    }

    private void insertCaptureMetrics(@NonNull CaptureMetrics captureMetrics) {
        CLog.w(TAG, "[mhyun2.park] onTaskFinished : insert captureMetrics E");
        CaptureMetricsRepository.getInstance(context).insertAsync(captureMetrics);
        CLog.w(TAG, "[mhyun2.park] onTaskFinished : insert captureMetrics X - " + captureMetrics.getDraftSequenceMetrics());
    }

    private boolean hasWatchdogTimeout(@NonNull ExtraBundle extraBundle) {
        final CaptureMetrics captureMetrics = extraBundle.get(ExtraBundle.DATA_CAPTURE_METRICS);
        return captureMetrics != null && hasWatchdogTimeout(captureMetrics);
    }

    private boolean hasWatchdogTimeout(@NonNull CaptureMetrics captureMetrics) {
        return captureMetrics.getDraftSequenceMetrics() != null &&
                Boolean.TRUE.equals(captureMetrics.getDraftSequenceMetrics().getHasWatchdogTimeout());
    }

    private void cancelDraftSequenceExecution(@NonNull ExtraBundle extraBundle) {
        Optional.ofNullable(extraBundle.get(ExtraBundle.DATA_DRAFT_SEQUENCE_EXECUTION_PROFILER))
                .ifPresent(DraftSequenceExecutionProfiler::cancelDraftSequenceExecution);
    }

    private void markRemainingTasksToSaveOriginalDraftImageOnly(int timedOutPpSequenceId) {
        savingDraftImageTaskMap.forEach((ppSequenceId, savingDraftImageTask) -> {
            if (ppSequenceId != timedOutPpSequenceId) {
                savingDraftImageTask.markSaveOriginalDraftImageOnly();
            }
        });
    }

    private void shutDownThreadPool() {
        if (savingDraftImageThreadPool.isTerminated()) {
            return;
        }

        try {
            savingDraftImageThreadPool.shutdown();
            final long SHUTDOWN_THREAD_POOL_TIME_OUT_MILLIS = 5000L;
            if (!savingDraftImageThreadPool.awaitTermination(SHUTDOWN_THREAD_POOL_TIME_OUT_MILLIS, TimeUnit.MILLISECONDS)) {
                PLog.e(TAG, "shutDownThreadPool - failed : can't be terminated in %d millis, try to shutdown forcefully", 5000);
                savingDraftImageThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            PLog.e(TAG, "shutDownThreadPool - failed : getting interrupt during wait for shutdown, try to shutdown forcefully");
            savingDraftImageThreadPool.shutdownNow();
        } finally {
            isWatchdogDrainInCurrentSession = false;
            hasCaptureTimeoutInCurrentSession = false;
        }
    }
}