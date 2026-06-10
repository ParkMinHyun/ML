package com.samsung.android.camera.core2.ml

import android.app.ActivityManager
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.samsung.android.camera.core2.util.CLog
import java.util.function.Supplier
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class DeviceStateReader(
    private val activityManager: ActivityManager,
    private val powerManager: PowerManager,
    private val overheatLevelSupplier: Supplier<Int>,
) {

    fun read(): DeviceStateSnapshot {
        return runBlocking(Dispatchers.Default) {
            readInternal()
        }
    }

    private suspend fun readInternal(): DeviceStateSnapshot {
        return coroutineScope {
            val readingMemoryState = async(Dispatchers.IO) {
                readMemoryState()
            }

            val readingThermalState = async(Dispatchers.IO) {
                readThermalState()
            }

            val readingStorageState = async(Dispatchers.IO) {
                readStorageState()
            }

            DeviceStateSnapshot(
                memorySnapshot = readingMemoryState.await(),
                thermalSnapshot = readingThermalState.await(),
                storageSnapshot = readingStorageState.await(),
            )
        }
    }

    private fun readMemoryState(): MemorySnapshot {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()

        val ramAvailableMb = bytesToMb(memoryInfo.availMem)

        val javaHeapMaxBytes = runtime.maxMemory()
        val javaHeapAllocatedBytes = runtime.totalMemory()
        val javaHeapFreeBytes = runtime.freeMemory()
        val javaHeapUsedBytes = javaHeapAllocatedBytes - javaHeapFreeBytes

        val nativeHeapCapacityBytes = Debug.getNativeHeapSize()
        val nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize()

        return MemorySnapshot(
            isLowMemory = memoryInfo.lowMemory,
            ramAvailablePercent = percent(
                value = ramAvailableMb,
                total = bytesToMb(memoryInfo.totalMem),
            ),
            javaHeapUsedPercent = percent(
                value = javaHeapUsedBytes,
                total = javaHeapMaxBytes,
            ),
            nativeHeapAllocatedPercent = percent(
                value = nativeHeapAllocatedBytes,
                total = nativeHeapCapacityBytes,
            ),
        )
    }

    private fun readThermalState(): ThermalSnapshot {
        val thermalStatus = powerManager.currentThermalStatus

        val thermalHeadroom = powerManager.getThermalHeadroom(0)
            .takeIf { !it.isNaN() }
            ?.let { (round(it * 1000) / 1000f) }
            ?: -1f

        return ThermalSnapshot(
            overheatLevel = overheatLevelSupplier.get(),
            thermalStatus = thermalStatus,
            thermalHeadroom = thermalHeadroom,
        )
    }

    private fun readStorageState(): StorageSnapshot {
        return try {
            val statFs = StatFs(Environment.getDataDirectory().absolutePath)
            val totalBytes = statFs.totalBytes
            val availableBytes = statFs.availableBytes

            StorageSnapshot(
                storageUsedPercent = percent(
                    value = totalBytes - availableBytes,
                    total = totalBytes,
                ),
            )
        } catch (t: Throwable) {
            CLog.w(TAG, "[mhyun2.park] Failed to read /data storage state", t)
            StorageSnapshot(storageUsedPercent = -1)
        }
    }

    private fun bytesToMb(bytes: Long): Long {
        if (bytes <= 0L) {
            return 0L
        }
        return bytes / BYTES_PER_MB
    }

    private fun percent(
        value: Long,
        total: Long,
    ): Int {
        if (total <= 0L) {
            return -1
        }

        return ((value.toDouble() / total.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private companion object {
        private const val TAG = "DeviceStateReader"

        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
