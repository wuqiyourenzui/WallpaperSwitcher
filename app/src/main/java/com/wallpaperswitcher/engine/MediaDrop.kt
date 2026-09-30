package com.wallpaperswitcher.engine

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsDao
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.util.AppLog

/**
 * "This media's file is really gone: remove the row and forget every cursor and
 * write memo that pointed at it."
 *
 * One implementation for all the places that remove media automatically (the
 * live engine's self-heal, the static applier's failed tick) and for the UI's
 * delete paths, because they used to clear different subsets of those keys: the
 * engine reset only the home cursor, the static applier reset nothing, and the UI
 * reset all of them. A cursor pointing at a row that no longer exists is mostly
 * harmless (the pick queries simply never match it, and SQLite's AUTOINCREMENT
 * never reuses the id), but three different rules for the same event is exactly
 * how the next stale-id bug gets introduced.
 */
internal suspend fun dropGoneMedia(context: Context, image: WallpaperImage, tag: String): Boolean {
    return try {
        if (!MediaProbe.isGone(context, image.uri)) return false
        val db = AppDatabase.getInstance(context)
        db.wallpaperImageDao().delete(image)
        clearMediaCursors(db.settingsDao(), listOf(image.id))
        AppLog.w(tag, "Dropped unreadable media ${image.displayName} id=${image.id} (file is gone)")
        true
    } catch (t: Throwable) {
        AppLog.e(tag, "Failed to drop unreadable media id=${image.id}", t)
        false
    }
}

/**
 * Forget every per-screen cursor / write memo that points at a deleted row.
 *
 * The keys are the ones the switch paths write: the home and lock cursors, the
 * two "already written" memos the static applier uses and the pending manual
 * pick that the slot enforcement reads back. Callers that delete media rows
 * (UI deletes, the automatic drop above, a group delete) all go through here.
 */
internal suspend fun clearMediaCursors(dao: SettingsDao, deletedIds: Collection<Long>) {
    if (deletedIds.isEmpty()) return
    for (key in CURSOR_KEYS) {
        val value = dao.getLong(key, 0L)
        if (value != 0L && value in deletedIds) dao.setLong(key, 0L)
    }
    val manualPick = dao.getLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, 0L)
    if (manualPick != 0L && manualPick in deletedIds) {
        dao.setLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, 0L)
    }
}

private val CURSOR_KEYS = listOf(
    SettingsKeys.LAST_IMAGE_ID,
    SettingsKeys.LAST_IMAGE_ID_LOCK,
    SettingsKeys.LAST_LOCK_WRITE_ID,
    SettingsKeys.LAST_HOME_WRITE_ID
)
