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
 * Handle returned by [DraftSequenceExecutionProfiler.profileNodeExecution]. It owns the execution
 * policy for admission skip, worker timeout, deferred completion, and metric completion.
 */
class DraftSequenceExecutionSession private constructor(
    private val mode: ExecutionMode,
    private val shouldRun: Boolean = true,
    private val processTimeoutMs: Long = 0L,
    private val onCancel: () -> Unit = {},
    private val onTimedOutTask: (CompletableFuture<Void>) -> Unit = {},
    private val onComplete: (PostExecutionMetrics) -> Unit = {},
) {
    companion object {
        internal fun bounded(
            shouldRun: Boolean,
            processTimeoutMs: Long,
            onTimedOutTask: (CompletableFuture<Void>) -> Unit,
            onComplete: (PostExecutionMetrics) -> Unit,
        ): DraftSequenceExecutionSession {
            return DraftSequenceExecutionSession(
                mode = ExecutionMode.BOUNDED_WORKER,
                shouldRun = shouldRun,
                processTimeoutMs = processTimeoutMs,
                onTimedOutTask = onTimedOutTask,
                onComplete = onComplete,
            )
        }

        internal fun deferred(
            onCancel: () -> Unit,
            onComplete: (PostExecutionMetrics) -> Unit,
        ): DraftSequenceExecutionSession {
            return DraftSequenceExecutionSession(
                mode = ExecutionMode.DEFERRED,
                onCancel = onCancel,
                onComplete = onComplete,
            )
        }
    }

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
            val result = when (mode) {
                ExecutionMode.BOUNDED_WORKER -> executeOnWorker(task)
                ExecutionMode.DEFERRED -> task.call()
            }
            if (result == null) {
                cancel()
            } else if (mode.completeOnReturn) {
                complete()
            }
            result
        } catch (e: Exception) {
            cancel()
            throw e
        }
    }

    @Throws(Exception::class)
    private fun <T> executeOnWorker(task: Callable<T>): T? {
        val executorService = Executors.newSingleThreadExecutor()
        val taskCompletion = CompletableFuture<T?>()
        val taskFinished = CompletableFuture<Void>()
        val future = executorService.submit<T?> {
            try {
                task.call().also(taskCompletion::complete)
            } catch (t: Throwable) {
                taskCompletion.completeExceptionally(t)
                throw t
            } finally {
                taskFinished.complete(null)
            }
        }
        try {
            return future.get(processTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            taskCompletion.thenAccept(this::releaseTimedOutResult)
            onTimedOutTask(taskFinished)
            future.cancel(true)
            throw e
        } catch (e: Exception) {
            future.cancel(true)
            throw e
        } finally {
            executorService.shutdownNow()
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

    /** Feeds the stage measurement to [onComplete] and returns it. */
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

private enum class ExecutionMode(val completeOnReturn: Boolean) {
    BOUNDED_WORKER(completeOnReturn = true),
    DEFERRED(completeOnReturn = false),
}
