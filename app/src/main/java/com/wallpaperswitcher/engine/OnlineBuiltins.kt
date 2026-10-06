package com.wallpaperswitcher.engine

import android.content.Context
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.OnlineSource
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.util.AppLog

/**
 * 设置页「在线壁纸源」的内置源定义与开关。
 *
 * 每个内置源都是 `online_sources` 里的一行（`type` = provider），全部下载到同一个
 * 「在线壁纸」分组，由 [OnlineSourceScheduler] 按各自间隔在后台更新。它们只在
 * 设置页出现，不进订阅源列表。
 */
internal object OnlineBuiltins {

    private const val TAG = "OnlineBuiltins"
    private const val GROUP_ID_KEY = "online_wallpaper_group_id"
    private const val KEEP_COUNT = 30

    data class Provider(
        val type: String,
        /** 品牌名不进翻译：Bing / NASA / Wikimedia 等专有名词保持原样。 */
        val label: String,
        val intervalMinutes: Int = 24 * 60,
        val maxPerRun: Int = 8,
    )

    val PROVIDERS = listOf(
        Provider(OnlineSource.TYPE_BING, "Bing 每日", maxPerRun = 8),
        Provider(OnlineSource.TYPE_NASA_APOD, "NASA APOD", maxPerRun = 1),
        Provider(OnlineSource.TYPE_WIKIMEDIA, "Wikimedia 每日图片", maxPerRun = 8),
        Provider(OnlineSource.TYPE_NETBIAN, "彼岸图网", maxPerRun = 8),
        Provider(OnlineSource.TYPE_IOLIU, "必应壁纸（ioliu）", maxPerRun = 6),
    )

    fun providerOf(type: String): Provider? = PROVIDERS.firstOrNull { it.type == type }

    /** 内置源类型集合：调度只认这些，旧版遗留的其它类型不再被重新排期。 */
    val providerTypes: Set<String> = PROVIDERS.map { it.type }.toSet()

    /** 需要参与调度/立即更新的行：内置类型且已开启（纯函数，便于单测）。 */
    fun builtinEnabled(sources: List<OnlineSource>): List<OnlineSource> =
        sources.filter { it.enabled && it.type in providerTypes }

    /** 启动时补齐内置源行（默认关闭，等用户在设置里打开）。 */
    suspend fun ensureSources(context: Context) {
        val db = try {
            AppDatabase.getInstance(context)
        } catch (t: Throwable) {
            AppLog.w(TAG, "db unavailable: ${t.javaClass.simpleName}")
            return
        }
        val dao = db.onlineSourceDao()
        val existing = try {
            dao.getAll()
        } catch (t: Throwable) {
            AppLog.w(TAG, "read failed: ${t.javaClass.simpleName}")
            return
        }
        val groupId = try {
            ensureGroup(context, db)
        } catch (t: Throwable) {
            AppLog.w(TAG, "group unavailable: ${t.javaClass.simpleName}")
            return
        }
        for (provider in PROVIDERS) {
            val row = existing.firstOrNull { it.type == provider.type }
            try {
                if (row == null) {
                    dao.insert(
                        OnlineSource(
                            type = provider.type,
                            name = provider.label,
                            groupId = groupId,
                            enabled = false,
                            intervalMinutes = provider.intervalMinutes,
                            wifiOnly = true,
                            keepCount = KEEP_COUNT,
                            maxPerRun = provider.maxPerRun,
                        )
                    )
                } else if (row.groupId != groupId) {
                    dao.update(row.copy(groupId = groupId))
                } else if (row.name != provider.label || row.intervalMinutes != provider.intervalMinutes ||
                    row.maxPerRun != provider.maxPerRun || row.keepCount != KEEP_COUNT
                ) {
                    // 内置源的展示名/间隔由代码决定（旧版可能留下别的名字）。
                    dao.update(
                        row.copy(
                            name = provider.label,
                            intervalMinutes = provider.intervalMinutes,
                            maxPerRun = provider.maxPerRun,
                            keepCount = KEEP_COUNT,
                        )
                    )
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "ensure ${provider.type} failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /** 打开/关闭一个内置源；返回更新后的行（供调度器使用）。 */
    suspend fun setEnabled(context: Context, type: String, enabled: Boolean): OnlineSource? {
        val provider = providerOf(type) ?: return null
        ensureSources(context)
        val db = AppDatabase.getInstance(context)
        val dao = db.onlineSourceDao()
        val row = dao.getAll().firstOrNull { it.type == provider.type } ?: return null
        val updated = row.copy(
            enabled = enabled,
            name = provider.label,
            groupId = ensureGroup(context, db),
            intervalMinutes = provider.intervalMinutes,
            maxPerRun = provider.maxPerRun,
            keepCount = KEEP_COUNT,
        )
        dao.update(updated)
        return updated
    }

    /** 「在线壁纸」分组：优先复用设置里记录的 id，再按名字找，最后新建。 */
    private suspend fun ensureGroup(context: Context, db: AppDatabase): Long {
        val dao = db.wallpaperGroupDao()
        val stored = try {
            db.settingsDao().getLong(GROUP_ID_KEY, 0L)
        } catch (_: Exception) {
            0L
        }
        if (stored > 0L && dao.getGroupById(stored) != null) return stored
        val name = try {
            AppLocale.localized(context).getString(R.string.online_auto_group_name)
        } catch (_: Throwable) {
            "在线壁纸"
        }
        dao.getGroupByName(name)?.let { group ->
            try {
                db.settingsDao().setLong(GROUP_ID_KEY, group.id)
            } catch (_: Exception) {
            }
            return group.id
        }
        val id = dao.insert(WallpaperGroup(name = name))
        try {
            db.settingsDao().setLong(GROUP_ID_KEY, id)
        } catch (_: Exception) {
        }
        return id
    }
}
