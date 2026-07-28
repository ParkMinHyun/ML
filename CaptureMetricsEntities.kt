package com.samsung.android.camera.core2.ml

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "capture_metrics")
data class CaptureMetricsEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "capture_metrics_id")
    val captureMetricsId: Int = 0,

    @ColumnInfo(name = "pp_sequence_id")
    val ppSequenceId: Int,

    @ColumnInfo(name = "ds_mode")
    val dsMode: Int,

    @ColumnInfo(name = "ds_extra_info")
    val dsExtraInfo: Int,

    @ColumnInfo(name = "result_image_width")
    val resultImageWidth: Int,

    @ColumnInfo(name = "result_image_height")
    val resultImageHeight: Int,

    @ColumnInfo(name = "result_image_format")
    val resultImageFormat: Int,

    @ColumnInfo(name = "result_image_file_name")
    val resultImageFileName: String,

    @ColumnInfo(name = "timeout_timestamp_ms")
    val timeoutTimestampMs: Long?,

    @ColumnInfo(name = "shot_to_shot_time_ms")
    val shotToShotTimeMs: Long?,
)

@Entity(
    tableName = "draft_sequence_metrics",
    foreignKeys = [
        ForeignKey(
            entity = CaptureMetricsEntity::class,
            parentColumns = ["capture_metrics_id"],
            childColumns = ["capture_metrics_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("capture_metrics_id")],
)
data class DraftSequenceMetricsEntity(
    @PrimaryKey
    @ColumnInfo(name = "capture_metrics_id")
    val captureMetricsId: Int,

    @ColumnInfo(name = "is_pending_request")
    val isPendingRequest: Boolean?,

    @ColumnInfo(name = "has_watchdog_timeout")
    val hasWatchdogTimeout: Boolean?,

    @ColumnInfo(name = "is_timeout")
    val isTimeout: Boolean?,

    @ColumnInfo(name = "draft_start_uptime_ms")
    val draftStartUptimeMs: Long?,

    @ColumnInfo(name = "draft_end_uptime_ms")
    val draftEndUptimeMs: Long?,

    @ColumnInfo(name = "pacer_session_id")
    val pacerSessionId: Long?,

    @Embedded(prefix = "pacing_")
    val pacingSnapshot: CaptureAvailablePacingMetricsEntity?,
)

data class CaptureAvailablePacingMetricsEntity(
    @ColumnInfo(name = "decision_uptime_ms")
    val decisionUptimeMs: Long,

    @ColumnInfo(name = "applied_delay_ms")
    val appliedDelayMs: Long,

    @ColumnInfo(name = "level_deficit_ms")
    val levelDeficitMs: Long,

    @ColumnInfo(name = "backlog_deficit_ms")
    val backlogDeficitMs: Long,

    @ColumnInfo(name = "backlog_ms")
    val backlogMs: Long,

    @ColumnInfo(name = "queued_draft_count")
    val queuedDraftCount: Int,

    @ColumnInfo(name = "queued_predicted_work_ms")
    val queuedPredictedWorkMs: Double,

    @ColumnInfo(name = "shutter_elapsed_ms")
    val shutterElapsedMs: Long?,

    @ColumnInfo(name = "max_draft_sequence_duration_ms")
    val maxDraftSequenceDurationMs: Long?,

    @ColumnInfo(name = "draft_sequence_budget_ms")
    val draftSequenceBudgetMs: Long,

    @ColumnInfo(name = "draft_sequence_predicted_duration_ms")
    val draftSequencePredictedDurationMs: Double,

    @ColumnInfo(name = "draft_sequence_overhead_duration_ms")
    val draftSequenceOverheadDurationMs: Double,

    @ColumnInfo(name = "draft_sequence_pacing_duration_ms")
    val draftSequencePacingDurationMs: Double,

    @ColumnInfo(name = "draft_sequence_key")
    val draftSequenceKey: String,
)

@Entity(
    tableName = "node_execution_metrics",
    foreignKeys = [
        ForeignKey(
            entity = CaptureMetricsEntity::class,
            parentColumns = ["capture_metrics_id"],
            childColumns = ["capture_metrics_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("capture_metrics_id"),
        Index(value = ["capture_metrics_id", "order"]),
    ],
)
data class NodeExecutionMetricsEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "node_execution_metrics_id")
    val nodeExecutionMetricsId: Long = 0L,

    @ColumnInfo(name = "capture_metrics_id")
    val captureMetricsId: Int,

    @ColumnInfo(name = "order")
    val order: Int,

    @ColumnInfo(name = "node_name")
    val nodeName: String,

    @ColumnInfo(name = "workload_key")
    val workloadKey: String?,

    @ColumnInfo(name = "budget_ms")
    val budgetMs: Long,

    @Embedded(prefix = "memory_")
    val memorySnapshot: MemorySnapshotEntity,

    @Embedded(prefix = "thermal_")
    val thermalSnapshot: ThermalSnapshotEntity,

    @Embedded(prefix = "storage_")
    val storageSnapshot: StorageSnapshotEntity,

    @Embedded(prefix = "gc_")
    val gcSnapshot: GcSnapshotEntity?,

    @Embedded(prefix = "cpu_processing_")
    val cpuProcessingSnapshot: CpuProcessingSnapshotEntity?,

    @ColumnInfo(name = "duration_ms")
    var durationMs: Long,

    @ColumnInfo(name = "watchdog_timeout_ms")
    var watchdogTimeoutMs: Long?,

    @ColumnInfo(name = "watchdog_timed_out")
    var watchdogTimedOut: Boolean?,

    @ColumnInfo(name = "start_uptime_ms")
    val startUptimeMs: Long?,
)

@Entity(
    tableName = "execution_predictions",
    foreignKeys = [
        ForeignKey(
            entity = CaptureMetricsEntity::class,
            parentColumns = ["capture_metrics_id"],
            childColumns = ["capture_metrics_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("capture_metrics_id"),
        Index(value = ["capture_metrics_id", "order"]),
    ],
)
data class ExecutionPredictionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "execution_prediction_id")
    val executionPredictionId: Long = 0L,

    @ColumnInfo(name = "capture_metrics_id")
    val captureMetricsId: Int,

    /** Node order within the draft sequence. */
    @ColumnInfo(name = "order")
    val order: Int,

    /** Decision-time planned workload-key suffix in replay-string format. */
    @ColumnInfo(name = "workload_sequence_key")
    val workloadSequenceKey: String?,

    @ColumnInfo(name = "sequence_predicted_duration_ms")
    val sequencePredictedDurationMs: Double,

    @ColumnInfo(name = "sequence_predicted_upper_bound_ms")
    val sequencePredictedUpperBoundMs: Double,

    @ColumnInfo(
        name = "admit",
        defaultValue = "0",
    )
    val admit: Boolean = false,
)

data class MemorySnapshotEntity(
    @ColumnInfo(name = "is_low_memory")
    val isLowMemory: Boolean,

    @ColumnInfo(name = "ram_available_percent")
    val ramAvailablePercent: Int,

    @ColumnInfo(name = "java_heap_used_percent")
    val javaHeapUsedPercent: Int,

    @ColumnInfo(name = "native_heap_allocated_percent")
    val nativeHeapAllocatedPercent: Int,
)

data class ThermalSnapshotEntity(
    @ColumnInfo(name = "overheat_level")
    val overheatLevel: Int,

    @ColumnInfo(name = "thermal_status")
    val thermalStatus: Int,

    @ColumnInfo(name = "thermal_headroom")
    val thermalHeadroom: Float,
)

data class StorageSnapshotEntity(
    @ColumnInfo(name = "storage_used_percent")
    val storageUsedPercent: Int,
)

data class GcSnapshotEntity(
    @ColumnInfo(name = "blocking_gc_count")
    val blockingGcCount: Int,

    @ColumnInfo(name = "blocking_gc_time_ms")
    val blockingGcTimeMs: Long,
)

data class CpuProcessingSnapshotEntity(
    @ColumnInfo(name = "time_ms")
    val cpuTimeMs: Long,

    @ColumnInfo(name = "wall_time_ms")
    val wallTimeMs: Long,

    @ColumnInfo(name = "utilization_ratio")
    val cpuUtilizationRatio: Float,

    @ColumnInfo(name = "runqueue_wait_ms")
    val runqueueWaitMs: Long,

    @ColumnInfo(name = "nonvoluntary_ctx_switches")
    val nonvoluntaryCtxSwitches: Int,
)
