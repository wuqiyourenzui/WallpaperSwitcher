package com.wallpaperswitcher.engine

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.OnlineSource
import com.wallpaperswitcher.worker.OnlineSourceWorker
import java.util.concurrent.TimeUnit

/**
 * WorkManager plumbing for the online sources.
 *
 * Power policy: one unique periodic work per source, updated in place when the
 * user edits it, with the network / charging constraints the source carries.
 * WorkManager itself holds the wake lock only while the worker runs and defers
 * the run when the device is dozing, so a daily Bing fetch is a few seconds of
 * radio and CPU per day - not a permanent network listener.
 */
object OnlineSourceScheduler {

    private const val PREFIX = "online_source_"
    private const val REFRESH_PREFIX = "online_refresh_"

    fun uniqueName(id: Long): String = "$PREFIX$id"

    fun schedule(context: Context, source: OnlineSource) {
        if (!source.enabled) {
            cancel(context, source.id)
            return
        }
        val intervalMinutes = OnlineSourceRules.normalizeIntervalMinutes(source.intervalMinutes)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (source.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(source.chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<OnlineSourceWorker>(
            intervalMinutes.toLong(), TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(workDataOf(OnlineSourceWorker.KEY_SOURCE_ID to source.id))
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueName(source.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } catch (_: Throwable) {
            // WorkManager is not allowed to take the app down (OEM policy).
        }
    }

    fun cancel(context: Context, id: Long) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(id))
        } catch (_: Throwable) {
        }
    }

    /** 立即更新: a one-shot run that ignores the periodic interval. */
    fun refreshNow(context: Context, id: Long) {
        val request = OneTimeWorkRequestBuilder<OnlineSourceWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(workDataOf(OnlineSourceWorker.KEY_SOURCE_ID to id))
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$REFRESH_PREFIX$id",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * App start / boot self-heal: make sure the built-in rows exist, then
     * schedule every enabled source and cancel the work of the disabled ones.
     * WorkManager persists its queue, but an edit that happened while the
     * process was dead (a restore, an OEM cleanup) must still end up with the
     * right constraints.
     */
    suspend fun ensureScheduled(context: Context) {
        OnlineBuiltins.ensureSources(context)
        val sources = try {
            AppDatabase.getInstance(context).onlineSourceDao().getAll()
        } catch (_: Throwable) {
            return
        }
        val scheduledIds = OnlineBuiltins.builtinEnabled(sources).map { it.id }.toHashSet()
        for (source in sources) {
            // 只调度「设置 → 在线壁纸源」里的内置源；旧版遗留的 URL/WebDAV/
            // 美人图行保持原样（不排期，也不删除已下载的内容）。
            if (source.id in scheduledIds) {
                schedule(context, source)
            } else {
                cancel(context, source.id)
                try {
                    WorkManager.getInstance(context)
                        .cancelUniqueWork("$REFRESH_PREFIX${source.id}")
                } catch (_: Throwable) {
                }
            }
        }
    }
}
