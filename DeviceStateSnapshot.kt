package com.samsung.android.camera.core2.ml

data class DeviceStateSnapshot(
    val memorySnapshot: MemorySnapshot,
    val powerThermalSnapshot: PowerThermalSnapshot,
    val storageSnapshot: StorageSnapshot,
)

data class MemorySnapshot(
    val isLowMemory: Boolean,
    val ramAvailablePercent: Int,
    val javaHeapUsedPercent: Int,
    val nativeHeapAllocatedPercent: Int,
)

data class PowerThermalSnapshot(
    val isPowerSaveMode: Boolean,
    val isCharging: Boolean,
    val overheatLevel: Int,
    val thermalStatus: Int,
    val thermalHeadroom: Float,
)

data class StorageSnapshot(
    val storageUsedPercent: Int,
)
