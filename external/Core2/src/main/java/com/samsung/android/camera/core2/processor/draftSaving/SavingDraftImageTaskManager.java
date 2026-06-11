/*
 * PATCH-0608 excerpt of Core2/src/main/java/com/samsung/android/camera/core2/processor/
 * draftSaving/SavingDraftImageTaskManager.java (base revision 7b69ae1162b6bf75f42d393eb558603e23b60fda),
 * updated to the current ML API.
 *
 * Only patch-affected members are shown; unchanged Core2 members are elided.
 *
 * ML API drift applied (PATCH-0608 -> current):
 *  - extraBundle.put(DATA_DEVICE_STATE_SNAPSHOT, deviceStateReader::read)
 *      -> extraBundle.put(DATA_DEVICE_STATE_READER, deviceStateReader)
 *    Node-side DraftSequenceExecutionProfiler now takes the DeviceStateReader itself.
 *  - SavingExecutionMetrics(pre, post)
 *      -> SavingExecutionMetrics(isPendingRequest, resultImageSize, resultImageFormat, pre, post)
 *    resultImageSize moved out of PreExecutionMetrics onto SavingExecutionMetrics;
 *    isPendingRequest / resultImageFormat are new. isPendingRequest is fed from the
 *    "previous task not completed" branch in addRequest (where handleIsDraftProcessing()
 *    used to be called) via pendingRequestIdSet.
 *  - PreExecutionMetrics(budget, Size, mem, powerThermal, storage)  (5 args)
 *      -> PreExecutionMetrics(budgetMs, memorySnapshot, thermalSnapshot, storageSnapshot)  (4 args)
 *  - deviceStateSnapshot.getPowerThermalSnapshot() -> getThermalSnapshot()
 *  - PostExecutionMetrics(null, null, null, durationMs)  (4 args)
 *      -> PostExecutionMetrics(gcSnapshot, cpuProcessingSnapshot, durationMs)  (3 args)
 *
 * Note: the saving-step metrics below are still hand-assembled (budget arithmetic) because
 * this manager only has hooks at submit time and finish time. Once a hook exists right
 * before the actual save (inside SavingDraftImageTask), the idiomatic current API is
 * DraftSequenceExecutionProfiler.predictSavingExecution(timeoutMs, isPendingRequest,
 * resultImageSize, resultImageFormat, draftMetrics) + session.complete(), which measures the
 * real elapsed time and GC/CPU deltas instead of approximating from budgets.
 */

package com.samsung.android.camera.core2.processor.draftSaving;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.container.SavingInfoContainer;
import com.samsung.android.camera.core2.ml.CaptureMetrics;
import com.samsung.android.camera.core2.ml.CaptureMetricsRepository;
import com.samsung.android.camera.core2.ml.DeviceStateReader;
import com.samsung.android.camera.core2.ml.DeviceStateSnapshot;
import com.samsung.android.camera.core2.ml.DraftSequenceMetrics;
import com.samsung.android.camera.core2.ml.NodeExecutionMetrics;
import com.samsung.android.camera.core2.ml.PostExecutionMetrics;
import com.samsung.android.camera.core2.ml.PreExecutionMetrics;
import com.samsung.android.camera.core2.ml.SavingExecutionMetrics;
import com.samsung.android.camera.core2.processor.request.ProcessRequest;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.PLog;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

public class SavingDraftImageTaskManager {
    private static final String TAG = "SavingDraftImageTaskManager";
    private final Context context;
    private final DeviceStateReader deviceStateReader;
    private final Map</*ppSequenceId*/Integer, SavingDraftImageTask> savingDraftImageTaskMap = new ConcurrentHashMap<>();
    private final Set</*ppSequenceId*/Integer> reservedSkipSaveDraftImageIdSet = new HashSet<>();
    // Tasks submitted while the previous task was still running; consumed in onTaskFinished
    // as SavingExecutionMetrics.isPendingRequest (new field on the current ML API).
    private final Set</*ppSequenceId*/Integer> pendingRequestIdSet = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService savingDraftImageThreadPool = Executors.newSingleThreadScheduledExecutor();
    private Future<?> savingDraftImageThreadFuture;

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
     * @param deviceStateReader     deviceStateReader
     */
    public SavingDraftImageTaskManager(@NonNull Context context, @NonNull DeviceStateReader deviceStateReader) {
        this.context = context;
        this.deviceStateReader = deviceStateReader;
    }

    public void addRequest(@NonNull ProcessRequest<ImageBuffer> processRequest,
                           /* ... unchanged leading parameters elided ... */
                           @Nullable Consumer<SavingInfoContainer> savedDraftImageConsumer,
                           @NonNull Consumer<Integer> skippedDraftImageConsumer) {
        final int ppSequenceId = processRequest.getPpSequenceId();
        final ExtraBundle extraBundle = processRequest.getExtraBundle();
        final SavingDraftImageTask savingDraftImageTask = savingDraftImageTaskMap.computeIfAbsent(ppSequenceId, __ -> {
            /* ... unchanged Core2 task-creation logic elided (single vs multi draft) ... */
            return task;
        });
        PLog.i(TAG, "addRequest(ppSequenceId:%d) - addOriginalBuffer(%d/%d)", ppSequenceId, processRequest.getCurrentDraftCount(), processRequest.getTotalDraftCount(), ppSequenceId);
        savingDraftImageTask.addOriginalBuffer(processRequest.getData(), extraBundle);

        if (processRequest.getCurrentDraftCount() == processRequest.getTotalDraftCount()) {
            if (null != savingDraftImageThreadFuture && !savingDraftImageThreadFuture.isDone()) {
                PLog.i(TAG, "addRequest(ppSequenceId:%d) - The previous task has not been completed, so it will save just original draft image", ppSequenceId);
//                savingDraftImageTask.handleIsDraftProcessing();
                pendingRequestIdSet.add(ppSequenceId);
            }
            // was: extraBundle.put(ExtraBundle.DATA_DEVICE_STATE_SNAPSHOT, deviceStateReader::read);
            extraBundle.put(ExtraBundle.DATA_DEVICE_STATE_READER, deviceStateReader);
            try {
                PLog.i(TAG, "addRequest(ppSequenceId:%d) - submit savingDraftImageTask", processRequest.getPpSequenceId());
                savingDraftImageThreadFuture = savingDraftImageThreadPool.submit(savingDraftImageTask);
            } catch (Exception e) {
                /* ... unchanged Core2 error handling elided ... */
            }
        }
    }

    /* ... unchanged Core2 members elided ... */

    private void onTaskFinished(int ppSequenceId) {
        final boolean isPendingRequest = pendingRequestIdSet.remove(ppSequenceId);
        final SavingDraftImageTask savingDraftImageTask = savingDraftImageTaskMap.get(ppSequenceId);
        if (null != savingDraftImageTask) {
            final boolean skipSaveDraftImage = savingDraftImageTask.skipSaveDraftImage;
            final boolean isDraftProcessing = Optional.ofNullable(savingDraftImageTask.extraBundle.get(ExtraBundle.PROCESSOR_INFO_IS_DRAFT_PROCESSING)).orElse(false);
            if (skipSaveDraftImage || isDraftProcessing) {
                CLog.w(TAG, "[mhyun2.park] onDraftPictureSaved : skip insert captureMetric [skipSaveDraftImage: %s, isDraftProcessing: %s]", skipSaveDraftImage, isDraftProcessing);
            } else {
                final ExtraBundle extraBundle = savingDraftImageTask.extraBundle;
                final CaptureMetrics captureMetrics = Objects.requireNonNull(extraBundle.get(ExtraBundle.DATA_CAPTURE_METRICS), "captureMetrics");
                final DraftSequenceMetrics draftSequenceMetrics = captureMetrics.getDraftSequenceMetrics();
                if (null != draftSequenceMetrics) {
                    final long startTime = System.currentTimeMillis();
                    final long budget = Optional.ofNullable(extraBundle.get(ExtraBundle.DATA_CAPTURE_TIMEOUT_TIMESTAMP)).map(timeoutTimestamp -> timeoutTimestamp - startTime).orElse(7000L);
                    final NodeExecutionMetrics lastNode = draftSequenceMetrics.getNodeExecutionMetricsList().getLast();
                    final DeviceStateSnapshot deviceStateSnapshot = deviceStateReader.read();
                    final SavingExecutionMetrics savingStateProfile = new SavingExecutionMetrics(
                            isPendingRequest,                                       // new on current API
                            extraBundle.get(ExtraBundle.INFO_RESULT_CAPTURE_SIZE),  // was: inside PreExecutionMetrics
                            captureMetrics.getResultImageFormat(),                  // new on current API
                            new PreExecutionMetrics(
                                    budget - lastNode.getPostExecutionMetrics().getDurationMs(),
                                    deviceStateSnapshot.getMemorySnapshot(),
                                    deviceStateSnapshot.getThermalSnapshot(),       // was: getPowerThermalSnapshot()
                                    deviceStateSnapshot.getStorageSnapshot()),
                            new PostExecutionMetrics(null, null,                    // was: 4-arg (extra leading null)
                                    lastNode.getPreExecutionMetrics().getBudgetMs() - budget));
                    draftSequenceMetrics.setSavingExecutionMetrics(savingStateProfile);
                    CLog.w(TAG, "[mhyun2.park] onDraftPictureSaved : set savingStateProfile - " + savingStateProfile);
                    final boolean isTimeout = Optional.ofNullable(extraBundle.get(ExtraBundle.DATA_CAPTURE_TIMEOUT_TIMESTAMP)).map(timeoutTimestamp -> timeoutTimestamp < System.currentTimeMillis()).orElse(false);
                    draftSequenceMetrics.setTimeout(isTimeout);
                    CLog.w(TAG, "[mhyun2.park] onDraftPictureSaved : insert captureMetrics E");
                    CaptureMetricsRepository.getInstance(context).insertAsync(captureMetrics);
                    CLog.w(TAG, "[mhyun2.park] onDraftPictureSaved : insert captureMetrics X - " + captureMetrics.getDraftSequenceMetrics());
                }
            }
        }
        savingDraftImageTaskMap.remove(ppSequenceId);
    }

    /* ... rest of Core2 SavingDraftImageTaskManager elided ... */
}
