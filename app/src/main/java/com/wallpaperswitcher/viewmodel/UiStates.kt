package com.wallpaperswitcher.viewmodel

import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.data.WallpaperGroup

/** Single combined state for the home screen (see WallpaperViewModel.homeUiState). */
data class HomeUiState(
    val groups: List<WallpaperGroup> = emptyList(),
    val mediaCounts: Map<Long, Int> = emptyMap(),
    val serviceEnabled: Boolean = false,
    /** The lock timer is independent from the home one; both keep the service alive. */
    val lockTimerEnabled: Boolean = true,
    /**
     * Media rows that need READ_MEDIA_* (`content://media/...`). 0 means the
     * library is SAF-only, where a missing permission is harmless.
     */
    val mediaStoreRowCount: Int = 0
)

/** Single combined state for the settings screen (see WallpaperViewModel.settingsUiState). */
data class SettingsUiState(
    val serviceEnabled: Boolean = false,
    val doubleTapEnabled: Boolean = true,
    val unlockSwitchEnabled: Boolean = false,
    val floatingButtonEnabled: Boolean = false,
    val floatingButtonColor: String = SettingsKeys.FLOATING_BUTTON_COLOR_DEFAULT,
    val floatingButtonAlpha: Int = SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT,
    val floatingButtonText: String = SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT,
    /** Persisted content URI of the custom picture; empty = draw the label. */
    val floatingButtonImageUri: String = "",
    val globalIntervalMs: Long = 60_000L,
    val globalSwitchMode: SwitchMode = SwitchMode.RANDOM,
    val globalScaleMode: ScaleMode = ScaleMode.FIT,
    val clarityMode: String = "auto",
    val switchFadeEnabled: Boolean = true,
    val themeColor: String = "",
    /** "system" (follow the phone) / "light" / "dark". */
    val themeMode: String = SettingsKeys.THEME_MODE_SYSTEM,
    val autoScanEnabled: Boolean = false,
    val autoScanIntervalMs: Long = 24L * 60 * 60 * 1000,
    /** Wall-clock ms of the last auto-scan run; 0 = never. */
    val autoScanLastRunAt: Long = 0L,
    val rotateMismatchEnabled: Boolean = true,
    val rotateMismatchClockwise: Boolean = true,
    // Lock-screen timed switch, independent from the home-screen one.
    val lockTimerEnabled: Boolean = true,
    val lockIntervalMs: Long = 60_000L,
    /** Play the video wallpaper's audio while the wallpaper is visible. */
    val videoSoundEnabled: Boolean = false,
    /** 过渡动画: "fade" / "slide" / "zoom" / "none" (see SettingsKeys). */
    val switchTransition: String = SettingsKeys.SWITCH_TRANSITION_DEFAULT,
    /** 视频播完再切: a timed switch waits for the current clip's pass to end. */
    val videoPlayToEnd: Boolean = false,
    /** 收藏优先: favourites get a higher weight in RANDOM / SHUFFLE. */
    val favoriteBoost: Boolean = true,
    /** 最近 N 张不重复 (0 = off) for RANDOM. */
    val recentNoRepeat: Int = 0,
    /** 静态图微动效 (Ken Burns): still images slowly zoom in and out. */
    val kenBurnsEnabled: Boolean = false
)

/**
 * Builds [SettingsUiState] from ONE read of the `app_settings` table
 * (see `WallpaperViewModel.settingsUiState` and `SettingsDao.getAllFlow`).
 *
 * This replaces the old per-key projection, where each field came from its own
 * `getValueFlow(key)` and an array indexed by constants: 28 queries per state,
 * all re-run and re-read by reflection on every settings write, and a screen
 * that could briefly show a mix of loaded and still-default fields.
 *
 * The parse + fallback of every field below is the same expression as the
 * matching single-key flow in WallpaperViewModel, so `settingsUiState.X` stays
 * equal to the value of the `viewModel.X` flow for the same field.
 */
internal object SettingsProjection {

    fun of(values: Map<String, String?>): SettingsUiState = SettingsUiState(
        serviceEnabled = values.flag(SettingsKeys.SERVICE_ENABLED, false),
        doubleTapEnabled = values.flag(SettingsKeys.DOUBLE_TAP_ENABLED, true),
        unlockSwitchEnabled = values.flag(SettingsKeys.UNLOCK_SWITCH_ENABLED, false),
        floatingButtonEnabled = values.flag(SettingsKeys.FLOATING_BUTTON_ENABLED, false),
        floatingButtonColor = values.text(
            SettingsKeys.FLOATING_BUTTON_COLOR,
            SettingsKeys.FLOATING_BUTTON_COLOR_DEFAULT
        ),
        floatingButtonAlpha = values[SettingsKeys.FLOATING_BUTTON_ALPHA]
            ?.toIntOrNull()
            ?.coerceIn(SettingsKeys.FLOATING_BUTTON_ALPHA_MIN, 100)
            ?: SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT,
        floatingButtonText = values.text(
            SettingsKeys.FLOATING_BUTTON_TEXT,
            SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT
        ),
        floatingButtonImageUri = values[SettingsKeys.FLOATING_BUTTON_IMAGE_URI].orEmpty(),
        globalIntervalMs = values.millis(SettingsKeys.GLOBAL_INTERVAL_MS, 60_000L),
        globalSwitchMode = values.enumName(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM),
        globalScaleMode = values.enumName(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT),
        clarityMode = values.text(SettingsKeys.CLARITY_MODE, "auto"),
        switchFadeEnabled = values.flag(SettingsKeys.SWITCH_FADE_ENABLED, true),
        themeColor = values.text(SettingsKeys.THEME_COLOR, ""),
        themeMode = values.text(SettingsKeys.THEME_MODE, SettingsKeys.THEME_MODE_SYSTEM),
        autoScanEnabled = values.flag(SettingsKeys.AUTO_SCAN_ENABLED, false),
        autoScanIntervalMs = values.millis(
            SettingsKeys.AUTO_SCAN_INTERVAL_MS,
            24L * 60 * 60 * 1000
        ),
        autoScanLastRunAt = values.millis(SettingsKeys.AUTO_SCAN_LAST_RUN_AT, 0L),
        rotateMismatchEnabled = values.flag(SettingsKeys.ROTATE_MISMATCH_ENABLED, true),
        rotateMismatchClockwise = values.flag(SettingsKeys.ROTATE_MISMATCH_CW, true),
        lockTimerEnabled = values.flag(SettingsKeys.LOCK_TIMER_ENABLED, true),
        lockIntervalMs = values.millis(SettingsKeys.LOCK_INTERVAL_MS, 60_000L),
        videoSoundEnabled = values.flag(SettingsKeys.VIDEO_SOUND_ENABLED, false),
        switchTransition = values.text(
            SettingsKeys.SWITCH_TRANSITION,
            SettingsKeys.SWITCH_TRANSITION_DEFAULT
        ),
        videoPlayToEnd = values.flag(SettingsKeys.VIDEO_PLAY_TO_END, false),
        favoriteBoost = values.flag(SettingsKeys.FAVORITE_BOOST, true),
        recentNoRepeat = values[SettingsKeys.RECENT_NO_REPEAT]
            ?.toIntOrNull()
            ?.coerceIn(0, WallpaperViewModel.MAX_RECENT_NO_REPEAT)
            ?: 0,
        kenBurnsEnabled = values.flag(SettingsKeys.KEN_BURNS_ENABLED, false),
    )

    /** The flows parse booleans as `it?.toBooleanStrictOrNull() ?: default`. */
    private fun Map<String, String?>.flag(key: String, default: Boolean): Boolean =
        get(key)?.toBooleanStrictOrNull() ?: default

    /** The flows parse numbers as `it?.toLongOrNull() ?: default`. */
    private fun Map<String, String?>.millis(key: String, default: Long): Long =
        get(key)?.toLongOrNull() ?: default

    /** The flows read strings as `it ?: default`. */
    private fun Map<String, String?>.text(key: String, default: String): String =
        get(key) ?: default

    /**
     * The flows parse enums as `SwitchMode.valueOf(name ?: SwitchMode.RANDOM.name)`
     * inside a `catch (_: Exception)`, i.e. an unknown name falls back to [default].
     */
    private inline fun <reified T : Enum<T>> Map<String, String?>.enumName(
        key: String,
        default: T,
    ): T = try {
        enumValueOf(get(key) ?: default.name)
    } catch (_: Exception) {
        default
    }
}
