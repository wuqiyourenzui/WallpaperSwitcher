package com.wallpaperswitcher.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wallpaperswitcher.engine.RssSync

/**
 * Periodic refresh of every enabled subscription. One worker for all sources
 * (they are refreshed sequentially), constrained to a connected network, so a
 * background feed refresh costs one short wake-up per interval.
 */
class RssRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        RssSync.refreshAll(applicationContext)
        return Result.success()
    }
}
