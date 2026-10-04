package com.wallpaperswitcher.engine.legado

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setString
import com.wallpaperswitcher.engine.Json
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong

/**
 * 阅读 sources usually carry several categories in `sortUrl`
 * (`最新::/new.html&&Cosplay::/cos/…`). The app fetches one of them at a time;
 * the user's choice is remembered per source in the settings table, so a
 * refresh from the scheduler or the widget keeps the same category.
 */
object RssCategories {

    private const val KEY_PREFIX = "rss_category_"

    private fun cacheKey(sourceId: Long) = "rss_categories_$sourceId"

    /**
     * Last successfully parsed category list, so chips render instantly even
     * when the source's `<js>` sortUrl is slow (or fails) this time.
     */
    suspend fun cachedNames(context: Context, source: RssSource): List<String> {
        val raw = try {
            AppDatabase.getInstance(context).settingsDao().getString(cacheKey(source.id), "")
        } catch (_: Throwable) {
            ""
        }
        if (raw.isNotBlank()) {
            try {
                (Json.parse(raw) as? List<*>)?.mapNotNull { it as? String }?.let { cached ->
                    if (cached.isNotEmpty()) return cached
                }
            } catch (_: Throwable) {
            }
        }
        return names(source)
    }

    /** Recompute and re-cache; keeps the previous list when the parse fails. */
    suspend fun refreshNames(context: Context, source: RssSource): List<String> {
        // JS 源（jsLib + getJs）也要先把规则跑出来才谈得上分类。
        val computed = namesFor(source)
        if (computed.isEmpty()) return cachedOnly(context, source)
        try {
            AppDatabase.getInstance(context).settingsDao()
                .setString(cacheKey(source.id), Json.encode(computed))
        } catch (_: Throwable) {
        }
        return computed
    }

    /** Cache-only read (never evaluates the source's `<js>` sortUrl). */
    suspend fun cachedOnly(context: Context, source: RssSource): List<String> {
        val raw = try {
            AppDatabase.getInstance(context).settingsDao().getString(cacheKey(source.id), "")
        } catch (_: Throwable) {
            ""
        }
        return try {
            (Json.parse(raw) as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Category names of a source; empty for plain feeds / single-URL lists. */
    fun names(source: RssSource): List<String> {
        return categoryNames(source, LegadoRss.parseRules(source) ?: return emptyList())
    }

    /** suspend 版本：JS 源在这里解析规则（脚本 + 下载都要 IO）。 */
    suspend fun namesFor(source: RssSource): List<String> {
        val rules = LegadoRss.rulesFor(source) ?: return emptyList()
        return categoryNames(source, rules)
    }

    private fun categoryNames(source: RssSource, rules: LegadoRss.Rules): List<String> {
        return try {
            LegadoRss.sortUrls(source, rules)
                .map { it.name }
                .filter { it.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Selected category index of a source (0 when unset). */
    suspend fun selectedIndex(context: Context, sourceId: Long): Int {
        return try {
            AppDatabase.getInstance(context).settingsDao()
                .getLong(KEY_PREFIX + sourceId, 0L)
                .toInt()
                .coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
    }

    suspend fun setSelectedIndex(context: Context, sourceId: Long, index: Int) {
        try {
            AppDatabase.getInstance(context).settingsDao()
                .setLong(KEY_PREFIX + sourceId, index.coerceAtLeast(0).toLong())
        } catch (_: Throwable) {
        }
    }

    /** The category name currently selected for a source ("" when none). */
    suspend fun selectedName(context: Context, source: RssSource): String {
        val list = names(source)
        if (list.isEmpty()) return ""
        return list.getOrNull(selectedIndex(context, source.id)).orEmpty()
    }
}
