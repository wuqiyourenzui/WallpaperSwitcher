package com.wallpaperswitcher.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wallpaperswitcher.engine.OnlineSync

/**
 * Periodic fetch of one online source (Bing / URL / WebDAV). The task only
 * runs when its [androidx.work.Constraints] are met, so a Wi-Fi-only or
 * charging-only source never wakes the radio or the CPU at the wrong time.
 *
 * Transient failures retry with WorkManager's backoff, bounded to
 * [MAX_RETRIES]; a permanent failure (bad URL, auth, not-an-image) is recorded
 * on the source and simply waits for the next interval instead of hammering
 * the server.
 */
class OnlineSourceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, 0L)
        if (sourceId <= 0L) return Result.success()
        val report = OnlineSync.sync(applicationContext, sourceId)
        return when {
            report.ok -> Result.success()
            report.reason in RETRYABLE && runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_SOURCE_ID = "online_source_id"
        private const val MAX_RETRIES = 3
        private val RETRYABLE = setOf("network", "timeout", "server", "rate_limited")
    }
}
