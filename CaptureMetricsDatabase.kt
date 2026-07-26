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
    // Bumped with the pacing column rename: Room verifies the stored schema hash, so a changed schema on the same
    // version aborts at open. No migration is written here - fallbackToDestructiveMigration recreates the database,
    // which is what these throwaway measurement runs want.
    version = 3,
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
