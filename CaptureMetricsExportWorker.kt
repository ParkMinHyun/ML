package com.samsung.android.camera.core2.ml

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.samsung.android.camera.core2.util.CLog
import kotlinx.coroutines.CancellationException

class CaptureMetricsExportWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val repository = CaptureMetricsRepository.getInstance(applicationContext)
            val file = CaptureMetricsExcelExporter(applicationContext, repository).export()
            CLog.i(TAG, "[mhyun2.park] Exported metrics to: ${file.absolutePath}")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            CLog.e(TAG, "[mhyun2.park] Failed to export metrics", t)
            Result.failure()
        }
    }

    private companion object {
        private const val TAG = "CaptureMetricsExportWorker"
    }
}
