package com.samsung.android.camera.core2.ml

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class CaptureMetricsDao {

    @Insert
    protected abstract suspend fun insert(entity: CaptureMetricsEntity): Long

    @Insert
    protected abstract suspend fun insert(entity: DraftSequenceMetricsEntity)

    @Insert
    protected abstract suspend fun insertNodeExecutionMetrics(entities: List<NodeExecutionMetricsEntity>)

    @Insert
    protected abstract suspend fun insertExecutionPredictions(entities: List<ExecutionPredictionEntity>)

    @Insert
    protected abstract suspend fun insert(entity: SavingExecutionMetricsEntity)

    @Transaction
    open suspend fun insertMetrics(metrics: CaptureMetrics): Int {
        val captureMetricsId = insert(metrics.toCaptureEntity()).toInt()

        metrics.draftSequenceMetrics?.let { draft ->
            insert(draft.toEntity(captureMetricsId))

            val nodeExecutionMetricsEntities = draft.nodeExecutionMetricsList
                .toNodeExecutionMetricsEntities(captureMetricsId)
            if (nodeExecutionMetricsEntities.isNotEmpty()) {
                insertNodeExecutionMetrics(nodeExecutionMetricsEntities)
            }

            val predictionEntities = buildList {
                addAll(draft.executionPredictionList.toNodePredictionEntities(captureMetricsId))
                draft.savingExecutionPrediction?.let {
                    add(it.toSavingPredictionEntity(captureMetricsId))
                }
            }
            if (predictionEntities.isNotEmpty()) {
                insertExecutionPredictions(predictionEntities)
            }

            draft.savingExecutionMetrics?.let { saving ->
                insert(saving.toEntity(captureMetricsId))
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
