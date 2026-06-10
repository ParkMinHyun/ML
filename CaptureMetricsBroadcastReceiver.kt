package com.samsung.android.camera.core2.ml

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.samsung.android.camera.core2.util.CLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CaptureMetricsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_EXPORT_METRICS_EXCEL) {
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = CaptureMetricsRepository.getInstance(appContext)
                val file = when (action) {
                    ACTION_EXPORT_METRICS_EXCEL -> {
                        CaptureMetricsExcelExporter(appContext, repository).export()
                    }
                    else -> {
                        return@launch
                    }
                }
                CLog.i(TAG, "[mhyun2.park] Exported metrics to: ${file.absolutePath}")
            } catch (t: Throwable) {
                CLog.e(TAG, "[mhyun2.park] Failed to export metrics ($action)", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_EXPORT_METRICS_EXCEL = "com.action.EXPORT_METRICS_EXCEL"
        private const val TAG = "CaptureMetricsBroadcastReceiver"
    }
}