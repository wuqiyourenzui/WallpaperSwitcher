package com.wallpaperswitcher.engine.legado

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setString

/**
 * The "next page" cursor of a subscription source's article list.
 *
 * 阅读 loads a source's pages lazily as the user scrolls; the cursor is what
 * lets "load more" continue exactly where the previous fetch stopped, instead
 * of re-downloading every page from the top. It is stored per source (the user
 * browses one category at a time) and cleared on refresh / category change.
 */
object RssPaging {

    private fun key(sourceId: Long) = "rss_next_$sourceId"

    /** The stored cursor, or null when the list reached its end. */
    suspend fun cursor(context: Context, sourceId: Long): String? {
        return try {
            AppDatabase.getInstance(context).settingsDao()
                .getString(key(sourceId), "")
                .takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun setCursor(context: Context, sourceId: Long, cursor: String?) {
        try {
            AppDatabase.getInstance(context).settingsDao()
                .setString(key(sourceId), cursor.orEmpty())
        } catch (_: Throwable) {
        }
    }

    suspend fun hasMore(context: Context, sourceId: Long): Boolean =
        cursor(context, sourceId) != null
}
