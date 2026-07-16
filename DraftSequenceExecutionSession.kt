package com.samsung.android.camera.core2.ml

import android.os.SystemClock
import com.samsung.android.camera.core2.util.CLog
import com.samsung.android.camera.core2.util.DirectBuffer
import com.samsung.android.camera.core2.util.PLog
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DraftSequenceExecutionSession"

/**
 * Handle returned by [DraftSequenceExecutionProfiler.profileNodeExecution]. It owns admission skip,
 * optional worker timeout, delayed completion, and metric completion. Which of those behaviors a session
 * gets is defined by the per-[WorkloadPolicy] factories in the companion.
 */
class DraftSequenceExecutionSession private constructor(
    private val shouldRun: Boolean = true,
    private val watchdogTimeoutMs: Long? = null,
    private val completeOnReturn: Boolean = true,
    private val onCancel: () -> Unit = {},
    private val onComplete: (PostExecutionMetrics) -> Unit = {},
    private val onTimedOutTask: (CompletableFuture<*>) -> Unit = {},
) {

    private val startedAtMs = SystemClock.uptimeMillis()
    private val gcTracker = GcTracker()
    private val cpuProcessingTracker = CpuProcessingTracker()
    private var completed = false

    @Throws(Exception::class)
    fun <T> execute(skippedTask: Callable<T>, task: Callable<T>): T? {
        if (!shouldRun) {
            PLog.e(TAG, "[mhyun2.park] skip the execute")
            return skippedTask.call()
        }

        return try {
            val result = watchdogTimeoutMs?.let { timeoutMs ->
                executeOnWorker(task, timeoutMs)
            } ?: task.call()

            if (result == null) {
                cancel()
            } else if (completeOnReturn) {
                complete()
            }
            result
        } catch (e: Exception) {
            cancel()
            throw e
        }
    }

    private fun <T> executeOnWorker(task: Callable<T>, timeoutMs: Long): T? {
        val executor = Executors.newSingleThreadExecutor()
        val result = CompletableFuture<T?>()
        val workerStarted = AtomicBoolean(false)
        val future = executor.submit<T?> {
            workerStarted.set(true)
            try {
                task.call().also(result::complete)
            } catch (t: Throwable) {
                result.completeExceptionally(t)
                throw t
            }
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // The worker keeps running detached: release its late result, and hand its future to the
            // node chain so it defers deinit until the worker actually finishes.
            result.thenAccept(::releaseTimedOutResult)
            onTimedOutTask(result)
            throw e
        } finally {
            if (future.cancel(true) && !workerStarted.get()) {
                // A zero watchdog can cancel before the executor runs the lambda; complete the lifecycle future.
                result.cancel(false)
            }
            executor.shutdownNow()
        }
    }

    private fun releaseTimedOutResult(result: Any?) {
        if (result !is DirectBuffer) {
            return
        }

        try {
            CLog.w(TAG, "[mhyun2.park] releaseTimedOutResult")
            result.release()
        } catch (t: Throwable) {
            CLog.e(TAG, "releaseTimedOutResult error", t)
        }
    }

    /** Feeds the workload measurement to [onComplete] and returns it. */
    @Synchronized
    fun complete(): PostExecutionMetrics {
        val postExecutionMetrics = PostExecutionMetrics(
            gcSnapshot = gcTracker.delta(),
            cpuProcessingSnapshot = cpuProcessingTracker.delta(),
            durationMs = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L),
        )
        if (!completed) {
            completed = true
            onComplete(postExecutionMetrics)
        }
        return postExecutionMetrics
    }

    /** Cancels the session without feeding it to [onComplete]. */
    @Synchronized
    fun cancel() {
        if (!completed) {
            completed = true
            onCancel()
        }
    }

    companion object {
        /** [WorkloadPolicy.OPTIONAL]: skippable by the admission decision and guarded by a watchdog. */
        internal fun createForOptionalWorkload(
            shouldRun: Boolean,
            watchdogTimeoutMs: Long,
            onTimedOutTask: (CompletableFuture<*>) -> Unit,
            onComplete: (PostExecutionMetrics) -> Unit,
        ): DraftSequenceExecutionSession = DraftSequenceExecutionSession(
            shouldRun = shouldRun,
            watchdogTimeoutMs = watchdogTimeoutMs,
            onTimedOutTask = onTimedOutTask,
            onComplete = onComplete,
        )

        /** [WorkloadPolicy.REQUIRED]: always runs, measured only. */
        internal fun createForRequiredWorkload(
            onComplete: (PostExecutionMetrics) -> Unit,
        ): DraftSequenceExecutionSession = DraftSequenceExecutionSession(onComplete = onComplete)

        /** [WorkloadPolicy.RESERVED]: runs now; completion is deferred to the profiler's capture-end call. */
        internal fun createForReservedWorkload(
            onCancel: () -> Unit,
            onComplete: (PostExecutionMetrics) -> Unit,
        ): DraftSequenceExecutionSession = DraftSequenceExecutionSession(
            completeOnReturn = false,
            onCancel = onCancel,
            onComplete = onComplete,
        )
    }
}
