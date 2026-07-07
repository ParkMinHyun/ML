package com.samsung.android.camera.core2.ml

import android.content.Context
import com.samsung.android.camera.core2.util.CLog
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CaptureMetricsRepository(
    private val dao: CaptureMetricsDao,
) {
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun insert(metrics: CaptureMetrics): Int {
        return dao.insertMetrics(metrics)
    }

    fun insertBlocking(metrics: CaptureMetrics): Int {
        return runBlocking(Dispatchers.IO) {
            insert(metrics)
        }
    }

    @JvmOverloads
    fun insertAsync(metrics: CaptureMetrics, callback: Consumer<Int>? = null) {
        asyncScope.launch {
            try {
                val captureMetricsId = insert(metrics)
                callback?.accept(captureMetricsId)
            } catch (t: Throwable) {
                CLog.e(TAG, "[mhyun2.park] insertAsync failed", t)
            }
        }
    }

    suspend fun getAll(): List<CaptureMetrics> {
        return dao.getAll().map { it.toModel() }
    }

    fun getAllBlocking(): List<CaptureMetrics> {
        return runBlocking(Dispatchers.IO) {
            getAll()
        }
    }

    @JvmOverloads
    fun getAllAsync(callback: Consumer<List<CaptureMetrics>>? = null) {
        asyncScope.launch {
            try {
                val result = getAll()
                callback?.accept(result)
            } catch (t: Throwable) {
                CLog.e(TAG, "[mhyun2.park] getAllAsync failed", t)
            }
        }
    }

    suspend fun count(): Int {
        return dao.count()
    }

    fun countBlocking(): Int {
        return runBlocking(Dispatchers.IO) {
            count()
        }
    }

    @JvmOverloads
    fun countAsync(callback: Consumer<Int>? = null) {
        asyncScope.launch {
            try {
                val result = count()
                callback?.accept(result)
            } catch (t: Throwable) {
                CLog.e(TAG, "[mhyun2.park] countAsync failed", t)
            }
        }
    }

    suspend fun deleteAll(): Int {
        return dao.deleteAll()
    }

    fun deleteAllBlocking(): Int {
        return runBlocking(Dispatchers.IO) {
            deleteAll()
        }
    }

    @JvmOverloads
    fun deleteAllAsync(callback: Consumer<Int>? = null) {
        asyncScope.launch {
            try {
                val deletedCount = deleteAll()
                callback?.accept(deletedCount)
            } catch (t: Throwable) {
                CLog.e(TAG, "[mhyun2.park] deleteAllAsync failed", t)
            }
        }
    }

    suspend fun deleteFromId(captureMetricsId: Int): Int {
        return dao.deleteFromId(captureMetricsId)
    }

    fun deleteFromIdBlocking(captureMetricsId: Int): Int {
        return runBlocking(Dispatchers.IO) {
            deleteFromId(captureMetricsId)
        }
    }

    @JvmOverloads
    fun deleteFromIdAsync(captureMetricsId: Int, callback: Consumer<Int>? = null) {
        asyncScope.launch {
            try {
                val deletedCount = deleteFromId(captureMetricsId)
                callback?.accept(deletedCount)
            } catch (t: Throwable) {
                CLog.e(TAG, "[mhyun2.park] deleteFromIdAsync failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "CaptureMetricsRepository"

        @Volatile
        private var INSTANCE: CaptureMetricsRepository? = null

        @JvmStatic
        fun getInstance(context: Context): CaptureMetricsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val database = CaptureMetricsDatabase.getInstance(context)
                    CaptureMetricsRepository(database.captureMetricsDao()).also { INSTANCE = it }
                }
            }
        }
    }
}
