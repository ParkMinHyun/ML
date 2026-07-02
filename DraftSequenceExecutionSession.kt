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
    fun <T> execute(skippedValue: T, task: Callable<T>): T? {
        if (!shouldRun) {
            PLog.e(TAG, "[mhyun2.park] skip the execute")
            return skippedValue
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

    companion object {
        /** [WorkloadPolicy.ADMIT]: skippable by the admission decision and guarded by a watchdog. */
        internal fun forAdmitWorkload(
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

        /** [WorkloadPolicy.OBSERVE]: always runs, measured only. */
        internal fun forObserveWorkload(
            onComplete: (PostExecutionMetrics) -> Unit,
        ): DraftSequenceExecutionSession = DraftSequenceExecutionSession(onComplete = onComplete)

        /** [WorkloadPolicy.COMPLETE]: runs now; completion is deferred to the profiler's capture-end call. */
        internal fun forCompleteWorkload(
            onCancel: () -> Unit,
            onComplete: (PostExecutionMetrics) -> Unit,
        ): DraftSequenceExecutionSession = DraftSequenceExecutionSession(
            completeOnReturn = false,
            onCancel = onCancel,
            onComplete = onComplete,
        )
    }
}
