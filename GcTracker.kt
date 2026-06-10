package com.samsung.android.camera.core2.ml

import android.os.Debug

/**
 * ?앹꽦 ?쒖젏??GC stat??湲곗??쇰줈 ?↔퀬, [delta] ?몄텧 ?쒖젏源뚯???蹂?붾웾??諛섑솚?⑸땲??
 */
class GcTracker {

    private val baseGcSnapshot: GcSnapshot = snapshot()

    fun delta(): GcSnapshot {
        val currentGcSnapshot = snapshot()
        return GcSnapshot(
            blockingGcCount = (currentGcSnapshot.blockingGcCount - baseGcSnapshot.blockingGcCount).coerceAtLeast(0),
            blockingGcTimeMs = (currentGcSnapshot.blockingGcTimeMs - baseGcSnapshot.blockingGcTimeMs).coerceAtLeast(0L),
        )
    }

    private fun snapshot(): GcSnapshot {
        return GcSnapshot(
            blockingGcCount = readRuntimeStatInt(RUNTIME_STAT_BLOCKING_GC_COUNT),
            blockingGcTimeMs = readRuntimeStatLong(RUNTIME_STAT_BLOCKING_GC_TIME),
        )
    }

    private fun readRuntimeStatInt(name: String): Int {
        return Debug.getRuntimeStat(name)?.toIntOrNull() ?: 0
    }

    private fun readRuntimeStatLong(name: String): Long {
        return Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L
    }

    private companion object {
        private const val RUNTIME_STAT_BLOCKING_GC_COUNT = "art.gc.blocking-gc-count"
        private const val RUNTIME_STAT_BLOCKING_GC_TIME = "art.gc.blocking-gc-time"
    }
}

data class GcSnapshot(
    val blockingGcCount: Int,
    val blockingGcTimeMs: Long,
)