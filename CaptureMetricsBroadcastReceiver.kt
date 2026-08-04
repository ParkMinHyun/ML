package com.samsung.android.camera.core2.ml

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.samsung.android.camera.core2.util.CLog

class CaptureMetricsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_EXPORT_METRICS_EXCEL) {
            return
        }
        val workRequest = OneTimeWorkRequest.Builder(CaptureMetricsExportWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest,
        )
        CLog.i(TAG, "[mhyun2.park] Metrics Excel export queued")
    }

    companion object {
        const val ACTION_EXPORT_METRICS_EXCEL = "com.action.EXPORT_METRICS_EXCEL"
        private const val TAG = "CaptureMetricsBroadcastReceiver"
        private const val UNIQUE_WORK_NAME = "Camera.CaptureMetrics.ExcelExport"
    }
}
