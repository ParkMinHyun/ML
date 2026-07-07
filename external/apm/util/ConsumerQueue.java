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

import com.samsung.android.camera.core2.util.CLog;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * <div class="camera_en">
 * A thread-safe queue that consumes items using a provided {@link Consumer}.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 제공된 {@link Consumer} 를 사용하여 항목을 소비하는 thread-safe queue입니다.
 * </div>
 */
public final class ConsumerQueue<T> {
    private static final String TAG = "ConsumerQueue";

    private static final long SHUTDOWN_TIME_OUT_MILLIS = 5000L;
    private static final long POLL_TIMEOUT_MILLIS = 5000L;
    private final BlockingQueue<T> queue;
    private final Thread thread;
    private final AtomicBoolean isRunning;
    private final Consumer<T> itemConsumer;

    /**
     * <div class="camera_en">
     * Creates a new {@code ConsumerQueue} with a dedicated worker thread.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 전용 스레드를 사용하여 {@code ConsumerQueue} 를 생성합니다.
     * </div>
     *
     * @param name     Identifier for the worker thread.
     * @param itemConsumer Consumer that processes queued items.
     */
    public ConsumerQueue(@NonNull String name, @NonNull Consumer<T> itemConsumer) {
        this.queue = new LinkedBlockingQueue<>();
        this.isRunning = new AtomicBoolean(true);
        this.itemConsumer = itemConsumer;
        this.thread = new Thread(this::run, "ConsumerQueue-" + name);
        this.thread.start();
    }

    /**
     * <div class="camera_en">
     * Adds an item to the queue for processing.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 처리할 항목을 큐에 추가합니다.
     * </div>
     *
     * @param item The item to be queued.
     * @return {@code true} if the item was successfully added, {@code false} otherwise.
     */
    public boolean update(@NonNull T item) {
        if (!isRunning.get()) {
            CLog.e(TAG, "ConsumerQueue is shut down, cannot add item");
            return false;
        }
        return queue.offer(item);
    }

    /**
     * <div class="camera_en">
     * Clears all pending items from the queue.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 큐에 남아 있는 모든 항목을 삭제합니다.
     * </div>
     */
    public void clear() {
        queue.clear();
    }

    /**
     * <div class="camera_en">
     * Gracefully shuts down the worker thread, waiting for pending tasks to finish.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 작업 스레드를 안전하게 종료하고, 남은 작업이 완료될 때까지 대기합니다.
     * </div>
     */
    public void shutdownSafely() {
        isRunning.set(false);
        thread.interrupt();
        try {
            thread.join(SHUTDOWN_TIME_OUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * <div class="camera_en">
     * Checks whether the queue's worker thread is still running.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 워커 스레드가 현재 실행 중인지 확인합니다.
     * </div>
     *
     * @return {@code true} if the worker thread is running, {@code false} otherwise.
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    private void run() {
        try {
            while (isRunning.get() || !queue.isEmpty()) {
                T item = queue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (item == null) {
                    continue;
                }

                try {
                    CLog.d(TAG, "Consuming item");
                    itemConsumer.accept(item);
                } catch (Throwable t) {
                    CLog.e(TAG, "Failed to consume item: " + t.getMessage(), t);
                }
            }
        } catch (InterruptedException e) {
            CLog.d(TAG, "ConsumerQueue thread interrupted: " + e.getMessage());
            processRemainingItems();
        }
        CLog.d(TAG, "ConsumerQueue stopped");
    }

    private void processRemainingItems() {
        T item;
        while ((item = queue.poll()) != null) {
            try {
                CLog.d(TAG, "Processing remaining item");
                itemConsumer.accept(item);
            } catch (Throwable t) {
                CLog.e(TAG, "Failed to consume remaining item: " + t.getMessage(), t);
            }
        }
    }
}
