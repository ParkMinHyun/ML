package com.samsung.android.camera.core2.ml

data class DeviceStateSnapshot(
    val memorySnapshot: MemorySnapshot,
    val thermalSnapshot: ThermalSnapshot,
    val storageSnapshot: StorageSnapshot,
)

data class MemorySnapshot(
    val isLowMemory: Boolean,
    val ramAvailablePercent: Int,
    val javaHeapUsedPercent: Int,
    val nativeHeapAllocatedPercent: Int,
)

data class ThermalSnapshot(
    val overheatLevel: Int,
    val thermalStatus: Int,
    val thermalHeadroom: Float,
)

data class StorageSnapshot(
    val storageUsedPercent: Int,
)
