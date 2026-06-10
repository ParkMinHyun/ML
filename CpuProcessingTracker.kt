package com.samsung.android.camera.core2.ml

import android.os.Process
import android.os.SystemClock
import kotlin.math.round

class CpuProcessingTracker {

    private val baseCpuTimeMs: Long = Process.getElapsedCpuTime()
    private val baseWallTimeMs: Long = SystemClock.uptimeMillis()

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
        )
    }
}

data class CpuProcessingSnapshot(
    val cpuTimeMs: Long,
    val wallTimeMs: Long,
    val cpuUtilizationRatio: Float,
)
