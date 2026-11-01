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
        // 只调度 WebDAV：内置「在线壁纸源」已下线，别再定时下载图片。
        if (source.type != OnlineSource.TYPE_WEBDAV) {
            cancel(context, source.id)
            return
        }
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
        // 不再重建内置源行：它们已由 MIGRATION_16_17 从库里删除，也不该再出现。
        val sources = try {
            AppDatabase.getInstance(context).onlineSourceDao().getAll()
        } catch (_: Throwable) {
            return
        }
        // 只认 WebDAV（内置源已下线；旧行只取消排期，不删已下载内容）。
        val scheduledIds = sources
            .filter { it.enabled && it.type == OnlineSource.TYPE_WEBDAV }
            .map { it.id }
            .toHashSet()
        for (source in sources) {
            // 只调度 WebDAV 源；其它旧行（内置源/URL/美人图）一律取消排期，
            // 但不删除它们已经下载的内容。
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
