package com.samsung.android.camera.core2.ml

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CaptureMetricsEntity::class,
        DraftSequenceMetricsEntity::class,
        NodeExecutionMetricsEntity::class,
        ExecutionPredictionEntity::class,
    ],
    // Do not change version.
    version = 1,
    exportSchema = true,
)
abstract class CaptureMetricsDatabase : RoomDatabase() {

    abstract fun captureMetricsDao(): CaptureMetricsDao

    companion object {
        private const val DATABASE_NAME = "capture_metrics.db"

        @Volatile
        private var INSTANCE: CaptureMetricsDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): CaptureMetricsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context,
                    CaptureMetricsDatabase::class.java,
                    DATABASE_NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
