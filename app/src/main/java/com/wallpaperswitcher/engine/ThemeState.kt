package com.wallpaperswitcher.engine

import android.content.Context
import android.content.res.Configuration

/**
 * The phone's current light/dark mode, used by 分组"跟随深色模式"
 * ([com.wallpaperswitcher.data.WallpaperGroup.activeThemeMode]).
 *
 * Reads the SYSTEM configuration (not the app's own theme override), because
 * the rule is meant to follow the phone: 白天一套分组、夜晚一套.
 */
object ThemeState {

    fun isDark(context: Context): Boolean = try {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        mode == Configuration.UI_MODE_NIGHT_YES
    } catch (_: Exception) {
        false
    }
}
