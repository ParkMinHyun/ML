/*
 * Copyright (C) 2026 Samsung Electronics Co., Ltd. All rights reserved.
 *
 * Mobile eXperience Business,
 * Device eXperience, Samsung Electronics Co., Ltd.
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

package com.samsung.android.camera.core2.apm.util;

import androidx.annotation.NonNull;

import com.samsung.android.camera.core2.util.PLog;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <div class="camera_en">
 * Schedules tasks to be executed after a specified delay on a dedicated single thread.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 지정된 시간 후에 작업을 실행하도록 전용 단일 스레드에서 스케줄링합니다.
 * </div>
 */
public final class SingleThreadDelayedScheduler {
    private static final String TAG = "SingleThreadDelayedScheduler";

    // On graceful shutdown the worker is not interrupted, so poll with a timeout
    // instead of blocking forever in take(): this lets the loop re-check its
    // termination condition and drain any pending tasks before exiting.
    private static final long POLL_TIMEOUT_MILLIS = 200L;

    private final BlockingQueue<ScheduledTask> queue = new LinkedBlockingQueue<>();

    private final Thread thread;

    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    /**
     * <div class="camera_en">
     * Creates a new scheduler with a dedicated thread.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 전용 스레드를 사용하여 새로운 스케줄러를 생성합니다.
     * </div>
     *
     * @param name Identifier for the scheduler thread.
     */
    public SingleThreadDelayedScheduler(@NonNull String name) {
        thread = new Thread(this::run, "SingleThreadDelayedScheduler-" + name);
        thread.start();
    }

    private void run() {
        while (isRunning.get() || !queue.isEmpty()) {
            try {
                ScheduledTask task = queue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (task == null) {
                    // Timed out with nothing to do; re-check the loop condition.
                    continue;
                }

                long waitNanos = task.targetNanos - System.nanoTime();
                if (waitNanos > 0) {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                }

                try {
                    PLog.i(TAG, "Executing scheduled task");
                    task.runnable.run();
                    PLog.i(TAG, "Scheduled task completed");
                } catch (Throwable t) {
                    PLog.e(TAG, "run is failed");
                }
            } catch (InterruptedException e) {
                // shutdownNow() interrupts to abort immediately; pending tasks are dropped.
                PLog.i(TAG, "SingleThreadDelayedScheduler interrupted, aborting");
                isRunning.set(false);
                break;
            }
        }
        PLog.i(TAG, "SingleThreadDelayedScheduler stopped");
    }

    /**
     * <div class="camera_en">
     * Schedules a {@code Runnable} to be executed after the given delay.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 지정된 지연 시간 후에 {@code Runnable} 을 실행하도록 스케줄링합니다.
     * </div>
     *
     * @param runnable The task to execute.
     * @param delayMs  Delay in milliseconds before execution.
     * @return {@code true} if the task was successfully scheduled, {@code false} otherwise.
     */
    public boolean schedule(Runnable runnable, long delayMs) {
        if (!isRunning.get()) {
            PLog.e(TAG, "schedule is shut down");
            return false;
        }
        if (delayMs < 0) delayMs = 0;

        PLog.i(TAG, "schedule delayMs: " + delayMs);
        return queue.offer(new ScheduledTask(runnable, delayMs));
    }

    /**
     * <div class="camera_en">
     * Clears all pending scheduled tasks.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 대기 중인 모든 스케줄된 작업을 삭제합니다.
     * </div>
     */
    public void clear() {
        queue.clear();
    }

    /**
     * <div class="camera_en">
     * Shuts down the scheduler, stopping the thread after pending tasks finish.
     * New tasks are rejected; already-queued tasks are still executed (honoring
     * their delay) before the worker thread terminates. Use {@link #shutdownNow()}
     * to drop pending tasks and stop immediately.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 스케줄러를 종료하고, 대기 중인 작업이 모두 끝난 후 스레드를 중지합니다.
     * 새 작업은 거부되지만 이미 큐에 있는 작업은 (지연 시간을 지킨 뒤) 실행됩니다.
     * 즉시 중단하려면 {@link #shutdownNow()} 를 사용하세요.
     * </div>
     */
    public void shutdown() {
        // Do NOT interrupt: interrupting would abort poll()/sleep() and drop
        // pending tasks. Clearing isRunning lets the worker drain and exit.
        isRunning.set(false);
    }

    /**
     * <div class="camera_en">
     * Immediately shuts down the scheduler, clearing pending tasks.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 즉시 스케줄러를 종료하고 대기 중인 작업을 모두 삭제합니다.
     * </div>
     */
    public void shutdownNow() {
        isRunning.set(false);
        queue.clear();
        thread.interrupt();
    }

    private record ScheduledTask(Runnable runnable, long targetNanos) {
            private ScheduledTask(Runnable runnable, long targetNanos) {
                this.runnable = runnable;
                this.targetNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(targetNanos);
            }
        }
}