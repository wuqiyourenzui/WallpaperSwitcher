package com.wallpaperswitcher.engine

import android.app.WallpaperManager
import androidx.annotation.StringRes
import com.wallpaperswitcher.R

/**
 * Which screen(s) a group's media may be applied to.
 *
 * Paperize-style "dual screen" support: the home screen and the lock screen
 * are switched independently, each from the enabled groups that target it.
 * The value is what [com.wallpaperswitcher.data.WallpaperGroup.target] stores.
 */
enum class WallpaperTarget(
    val nameValue: String,
    val flags: Int,
    @StringRes val labelRes: Int,
    /** Compact form for chips / cards where space is tight. */
    @StringRes val shortLabelRes: Int
) {
    HOME("HOME", WallpaperManager.FLAG_SYSTEM, R.string.target_home, R.string.target_home),
    LOCK("LOCK", WallpaperManager.FLAG_LOCK, R.string.target_lock, R.string.target_lock),
    BOTH(
        "BOTH",
        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
        R.string.target_both,
        R.string.target_both_short
    );

    val includesHome: Boolean get() = flags and WallpaperManager.FLAG_SYSTEM != 0
    val includesLock: Boolean get() = flags and WallpaperManager.FLAG_LOCK != 0

    /**
     * Whether media from a group with this target may be shown on [slot]
     * ([SLOT_HOME] or [SLOT_LOCK]). Used to keep the two screens from leaking
     * into each other: a lock-only group's media must never be rendered by the
     * home (live wallpaper) engine, and vice versa.
     */
    fun suitsSlot(slot: String): Boolean =
        if (slot == SLOT_LOCK) includesLock else includesHome

    companion object {
        /** Never throws: an unknown/legacy value falls back to [BOTH]. */
        fun fromName(value: String?): WallpaperTarget =
            entries.firstOrNull { it.nameValue == value } ?: BOTH

        /** The slot value used by the slot-filtered DAO queries. */
        const val SLOT_HOME = "HOME"
        const val SLOT_LOCK = "LOCK"
    }
}
