package com.wallpaperswitcher.tile

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.logCoroutineFailures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WallpaperTile"

/**
 * 快速设置磁贴：切换壁纸.
 *
 * One tap behaves exactly like the "立即切换壁纸" button on the home screen:
 * [WallpaperSwitchService.switchNow] routes to the live engine or writes a
 * static wallpaper, whichever owns the home screen right now.
 */
class SwitchWallpaperTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        AppLog.d(TAG, "Tile: switch now")
        WallpaperSwitchService.switchNow(applicationContext)
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        try {
            tile.state = Tile.STATE_INACTIVE
            tile.label = localized(applicationContext).getString(R.string.tile_switch_label)
            tile.updateTile()
        } catch (t: Throwable) {
            AppLog.e(TAG, "refreshTile failed", t)
        }
    }
}

/**
 * 快速设置磁贴：暂停 / 继续切换.
 *
 * Tap = pause for a day (the tile then reads 继续切换); tap again = resume
 * immediately. Uses the same `pause_until` setting as the home screen's
 * "稍后切换", so both entry points always agree.
 */
class PauseWallpaperTileService : TileService() {

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + logCoroutineFailures(TAG)
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refresh() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            try {
                val dao = AppDatabase.getInstance(applicationContext).settingsDao()
                val now = System.currentTimeMillis()
                val paused = dao.getLong(SettingsKeys.PAUSE_UNTIL) > now
                if (paused) {
                    dao.setLong(SettingsKeys.PAUSE_UNTIL, 0L)
                    AppLog.d(TAG, "Tile: resume")
                } else {
                    dao.setLong(SettingsKeys.PAUSE_STARTED_AT, now)
                    dao.setLong(SettingsKeys.PAUSE_UNTIL, now + PAUSE_TILE_DURATION_MS)
                    AppLog.d(TAG, "Tile: pause for ${PAUSE_TILE_DURATION_MS}ms")
                }
                WallpaperSwitchService.poke(applicationContext)
            } catch (t: Throwable) {
                AppLog.e(TAG, "toggle pause failed", t)
            }
            refresh()
        }
    }

    private suspend fun refresh() {
        val paused = try {
            val dao = AppDatabase.getInstance(applicationContext).settingsDao()
            dao.getLong(SettingsKeys.PAUSE_UNTIL) > System.currentTimeMillis()
        } catch (_: Exception) {
            false
        }
        withContext(Dispatchers.Main) {
            val tile = qsTile ?: return@withContext
            try {
                tile.state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = localized(applicationContext).getString(
                    if (paused) R.string.tile_resume_label else R.string.tile_pause_label
                )
                tile.updateTile()
            } catch (t: Throwable) {
                AppLog.e(TAG, "refresh failed", t)
            }
        }
    }

    companion object {
        /** The tile pauses for a day; tapping again resumes earlier. */
        private const val PAUSE_TILE_DURATION_MS = 24L * 60 * 60 * 1000
    }
}

/** Strings follow the in-app language, not only the system locale. */
private fun localized(context: Context) = AppLocale.localized(context)
