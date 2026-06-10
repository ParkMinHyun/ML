package com.samsung.android.camera.core2.ml

import androidx.room.Embedded
import androidx.room.Relation

data class CaptureMetricsAggregate(
    @Embedded
    val capture: CaptureMetricsEntity,

    @Relation(
        parentColumn = "capture_metrics_id",
        entityColumn = "capture_metrics_id",
    )
    val draftSequenceMetrics: DraftSequenceMetricsEntity?,

    @Relation(
        parentColumn = "capture_metrics_id",
        entityColumn = "capture_metrics_id",
    )
    val nodeExecutionMetrics: List<NodeExecutionMetricsEntity>,

    @Relation(
        parentColumn = "capture_metrics_id",
        entityColumn = "capture_metrics_id",
    )
    val savingExecutionMetrics: SavingExecutionMetricsEntity?,

    @Relation(
        parentColumn = "capture_metrics_id",
        entityColumn = "capture_metrics_id",
    )
    val executionPredictions: List<ExecutionPredictionEntity>,
)
