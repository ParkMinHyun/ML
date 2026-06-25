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

private const val TAG = "DraftSequenceExecutionSession"

/**
 * Handle returned by [DraftSequenceExecutionProfiler.profileNodeExecution]. It owns admission skip,
 * optional worker timeout, delayed completion, and metric completion.
 */
class DraftSequenceExecutionSession internal constructor(
    private val shouldRun: Boolean = true,
    private val processTimeoutMs: Long? = null,
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
    fun <T> execute(skippedValue: T, task: Callable<T>): T? {
        if (!shouldRun) {
            PLog.e(TAG, "[mhyun2.park] skip the execute")
            return skippedValue
        }

        return try {
            val result = processTimeoutMs?.let { timeoutMs ->
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
        val future = executor.submit<T?> {
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
            future.cancel(true)
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
}
