package com.wallpaperswitcher.engine

import android.content.Context
import androidx.work.WorkManager

/**
 * 订阅源不再后台定时刷新：列表是"进源实时抓、退出即清空"，定时任务只会白占
 * 存储和流量。这里保留的唯一职责是取消老版本已经排进 WorkManager 的周期任务。
 */
object RssScheduler {

    private const val UNIQUE = "rss_refresh"

    suspend fun ensureScheduled(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        } catch (_: Throwable) {
        }
    }
}
