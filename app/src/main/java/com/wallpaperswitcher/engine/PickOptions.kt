package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.RecentShown
import com.wallpaperswitcher.data.SettingsDao
import com.wallpaperswitcher.data.SettingsKeys

/**
 * The two optional pick knobs, read the same way by every switch path (live
 * engine, static applier, 下一张预览) so all three agree:
 *
 *  - 收藏优先 ([SettingsKeys.FAVORITE_BOOST]): favourites draw
 *    [SettingsKeys.FAVORITE_WEIGHT]× as often in RANDOM / SHUFFLE;
 *  - 最近 N 张不重复 ([SettingsKeys.RECENT_NO_REPEAT]): RANDOM avoids the last
 *    N media of the screen, and every applied switch extends that window.
 */
internal object PickOptions {

    /**
     * 收藏优先 was removed on request: every media draws with weight 1 again,
     * whatever old value the setting still holds (the favourite UI is gone, so
     * nobody can turn it on any more).
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun favoriteWeight(dao: SettingsDao): Int = 1

    /** 最近 N 张不重复 was removed on request: the window is always off. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun recentWindow(dao: SettingsDao): Int = 0

    /** The ids RANDOM must avoid on [slot] (empty when the window is off). */
    suspend fun recentIds(db: AppDatabase, slot: String, window: Int): List<Long> {
        if (window <= 0) return emptyList()
        return try {
            db.recentDao().recentIds(slot, window)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Remember that [mediaId] is on screen now, trimming the history to
     * [RECENT_KEEP].
     *
     * **记录历史与"最近 N 张不重复"无关**（后者已按用户要求移除，见
     * [recentWindow]）：`recent_shown` 现在是「最近显示」回滚页的数据源 ——
     * "手滑切走一张好图"要能找回来，就必须每次切换都记一行。以前的写法是先看
     * `recentWindow()`，而它恒为 0，于是这里每次都直接 return，表永远是空的
     * （回滚页只能是空态）。
     */
    suspend fun recordShown(db: AppDatabase, slot: String, mediaId: Long) {
        if (mediaId <= 0L) return
        try {
            db.recentDao().record(RecentShown(slot = slot, mediaId = mediaId))
            db.recentDao().trim(slot, RECENT_KEEP)
        } catch (_: Exception) {
        }
    }

    /**
     * 历史保留条数：表按"每次切换一行"增长，必须封顶（200 行足够翻回去找
     * 任何一张，而一次 DELETE 只按索引删旧行）。
     */
    internal const val RECENT_KEEP = 200
}
