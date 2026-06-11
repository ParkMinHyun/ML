package com.samsung.android.camera.core2.ml

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class CaptureMetricsDao {

    @Insert
    protected abstract suspend fun insertCaptureMetrics(entity: CaptureMetricsEntity): Long

    @Insert
    protected abstract suspend fun insertDraftSequenceMetrics(entity: DraftSequenceMetricsEntity)

    @Insert
    protected abstract suspend fun insertNodeExecutionMetrics(entities: List<NodeExecutionMetricsEntity>)

    @Insert
    protected abstract suspend fun insertExecutionPredictions(entities: List<ExecutionPredictionEntity>)

    @Insert
    protected abstract suspend fun insertSavingExecutionMetrics(entity: SavingExecutionMetricsEntity)

    @Transaction
    open suspend fun insertMetrics(metrics: CaptureMetrics): Int {
        val captureMetricsId = insertCaptureMetrics(metrics.toCaptureEntity()).toInt()

        metrics.draftSequenceMetrics?.let { draft ->
            insertDraftSequenceMetrics(draft.toEntity(captureMetricsId))

            val nodeExecutionMetricsEntities = draft.nodeExecutionMetricsList
                .toNodeExecutionMetricsEntities(captureMetricsId)
            if (nodeExecutionMetricsEntities.isNotEmpty()) {
                insertNodeExecutionMetrics(nodeExecutionMetricsEntities)
            }

            val predictionEntities = buildList {
                addAll(draft.nodeExecutionPredictionList.toNodePredictionEntities(captureMetricsId))
                addAll(draft.savingExecutionPredictionList.toSavingPredictionEntities(captureMetricsId))
            }
            if (predictionEntities.isNotEmpty()) {
                insertExecutionPredictions(predictionEntities)
            }

            draft.savingExecutionMetrics?.let { saving ->
                insertSavingExecutionMetrics(saving.toEntity(captureMetricsId))
            }
        }

        return captureMetricsId
    }

    @Transaction
    @Query("SELECT * FROM capture_metrics ORDER BY capture_metrics_id ASC")
    abstract suspend fun getAll(): List<CaptureMetricsAggregate>

    @Query("SELECT COUNT(*) FROM capture_metrics")
    abstract suspend fun count(): Int

    @Transaction
    open suspend fun deleteAll(): Int {
        return deleteAllInternal()
    }

    @Query("DELETE FROM capture_metrics")
    protected abstract suspend fun deleteAllInternal(): Int
}
