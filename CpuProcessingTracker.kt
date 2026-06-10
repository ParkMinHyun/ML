package com.samsung.android.camera.core2.ml

import android.os.Process
import android.os.SystemClock
import java.io.File
import kotlin.math.round

/**
 * Captures CPU usage and scheduling-contention baselines at construction time and returns the
 * delta at [delta]. The contention stats (runqueue wait, nonvoluntary context switches) are
 * thread-level and track the thread that constructed this tracker, so construct it on the
 * thread that runs the work.
 */
class CpuProcessingTracker {

    private val tid: Int = Process.myTid()
    private val baseCpuTimeMs: Long = Process.getElapsedCpuTime()
    private val baseWallTimeMs: Long = SystemClock.uptimeMillis()
    private val baseRunqueueWaitNs: Long? = readRunqueueWaitNs()
    private val baseNonvoluntaryCtxSwitches: Long? = readNonvoluntaryCtxSwitches()

    fun delta(): CpuProcessingSnapshot {
        val cpuTimeMs = (Process.getElapsedCpuTime() - baseCpuTimeMs).coerceAtLeast(0L)
        val wallTimeMs = (SystemClock.uptimeMillis() - baseWallTimeMs).coerceAtLeast(0L)
        val coreCount = Runtime.getRuntime().availableProcessors()
        val cpuUtilizationRatio = if (wallTimeMs > 0L && coreCount > 0) {
            val rawUsage = (cpuTimeMs.toFloat() / (wallTimeMs.toFloat() * coreCount))
            (round(rawUsage * 100) / 100f).coerceIn(0f, 1f)
        } else {
            0f
        }
        return CpuProcessingSnapshot(
            cpuTimeMs = cpuTimeMs,
            wallTimeMs = wallTimeMs,
            cpuUtilizationRatio = cpuUtilizationRatio,
            runqueueWaitMs = delta(baseRunqueueWaitNs, readRunqueueWaitNs())
                ?.let { it / NS_PER_MS }
                ?: UNAVAILABLE,
            nonvoluntaryCtxSwitches = delta(baseNonvoluntaryCtxSwitches, readNonvoluntaryCtxSwitches())
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: UNAVAILABLE.toInt(),
        )
    }

    private fun delta(base: Long?, current: Long?): Long? {
        if (base == null || current == null) {
            return null
        }
        return (current - base).coerceAtLeast(0L)
    }

    /** `/proc/self/task/<tid>/schedstat`: "&lt;on-cpu ns&gt; &lt;runqueue-wait ns&gt; &lt;timeslices&gt;". */
    private fun readRunqueueWaitNs(): Long? {
        return try {
            File("$PROC_SELF_TASK/$tid/$PROC_TASK_SCHEDSTAT")
                .readText()
                .trim()
                .split(' ')
                .getOrNull(SCHEDSTAT_RUNQUEUE_WAIT_INDEX)
                ?.toLongOrNull()
        } catch (t: Throwable) {
            null
        }
    }

    private fun readNonvoluntaryCtxSwitches(): Long? {
        return try {
            File("$PROC_SELF_TASK/$tid/$PROC_TASK_STATUS")
                .readLines()
                .firstOrNull { it.startsWith("$PROC_STATUS_NONVOLUNTARY_CTX_SWITCHES:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toLongOrNull()
        } catch (t: Throwable) {
            null
        }
    }

    private companion object {
        private const val NS_PER_MS = 1_000_000L

        /** Sentinel for stats the kernel did not expose (read failure). */
        private const val UNAVAILABLE = -1L

        private const val PROC_SELF_TASK = "/proc/self/task"
        private const val PROC_TASK_SCHEDSTAT = "schedstat"
        private const val PROC_TASK_STATUS = "status"
        private const val PROC_STATUS_NONVOLUNTARY_CTX_SWITCHES = "nonvoluntary_ctxt_switches"
        private const val SCHEDSTAT_RUNQUEUE_WAIT_INDEX = 1
    }
}

data class CpuProcessingSnapshot(
    val cpuTimeMs: Long,
    val wallTimeMs: Long,
    val cpuUtilizationRatio: Float,
    /** Time this thread sat runnable on the runqueue waiting for a core; -1 when unavailable. */
    val runqueueWaitMs: Long,
    /** Times this thread was preempted by the scheduler; -1 when unavailable. */
    val nonvoluntaryCtxSwitches: Int,
)
