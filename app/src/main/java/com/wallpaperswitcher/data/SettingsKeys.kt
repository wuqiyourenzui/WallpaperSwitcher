package com.wallpaperswitcher.data

object SettingsKeys {
    const val SERVICE_ENABLED = "service_enabled"
    const val DOUBLE_TAP_ENABLED = "double_tap_enabled"
    /**
     * Switch on every unlock (ACTION_USER_PRESENT). Works in both modes: with
     * the live wallpaper engine the engine shows the next media, without it the
     * receiver writes a static home wallpaper. The lock screen is never touched
     * here - it only has its own timer.
     */
    const val UNLOCK_SWITCH_ENABLED = "unlock_switch_enabled"
    // Floating double-tap button fallback for launchers that do not forward
    // touches to the live wallpaper window (Android 16/17 devices).
    const val FLOATING_BUTTON_ENABLED = "floating_button_enabled"
    // Floating button appearance: base color as "#RRGGBB" and opacity as a
    // percentage (5..100, default 10 = the original 90%-transparent look).
    const val FLOATING_BUTTON_COLOR = "floating_button_color"
    const val FLOATING_BUTTON_COLOR_DEFAULT = "#1E88E5"
    const val FLOATING_BUTTON_ALPHA = "floating_button_alpha"
    const val FLOATING_BUTTON_ALPHA_DEFAULT = 10
    const val FLOATING_BUTTON_ALPHA_MIN = 5
    // Floating button content: a short custom label, or a custom picture that
    // replaces the label entirely (see FloatingButtonContentPolicy).
    const val FLOATING_BUTTON_TEXT = "floating_button_text"
    const val FLOATING_BUTTON_TEXT_DEFAULT = "切"
    /** Content URI of the user's picture; empty = draw the text label. */
    const val FLOATING_BUTTON_IMAGE_URI = "floating_button_image_uri"
    const val LAST_IMAGE_ID = "last_image_id"
    // Cursor of the LOCK-slot switching. The home slot keeps using
    // LAST_IMAGE_ID (shared with the live wallpaper engine); the lock screen is
    // switched independently (see WallpaperTarget), so it needs its own cursor
    // and its own shuffle deck.
    const val LAST_IMAGE_ID_LOCK = "last_image_id_lock"
    /**
     * While the user is explicitly setting a HOME wallpaper (tapping an image,
     * confirming in the system live-wallpaper screen), the home timer must not
     * override that pick. Wall-clock ms until which the home timer is postponed.
     *
     * The LOCK timer deliberately ignores this hold: a lock pick only re-anchors
     * the lock schedule, otherwise setting a few lock images in a row would keep
     * renewing the hold and make the lock timer look broken.
     */
    const val MANUAL_PICK_HOLD_UNTIL = "manual_pick_hold_until"
    // The media the user just tapped and confirmed in the system live-wallpaper
    // dialog, plus the wall-clock ms of the tap. The slot enforcement runs a few
    // seconds later (when the real engine is created) and must write THIS media
    // to the lock screen - reading the HOME cursor instead was wrong, because a
    // home tick between the tap and the confirmation moved it (the lock screen
    // then kept showing an older image instead of the one the user picked).
    const val MANUAL_PICK_MEDIA_ID = "manual_pick_media_id"
    const val MANUAL_PICK_AT = "manual_pick_at"
    // Last media written to the LOCK slot + when (wall clock ms). Used to skip
    // redundant lock writes: without it every live-wallpaper apply (and every
    // engine recreation) rewrote the same lock image again and again.
    const val LAST_LOCK_WRITE_ID = "last_lock_write_id"
    const val LAST_LOCK_WRITE_AT = "last_lock_write_at"
    // Same memo for the HOME slot: the static home timer used to re-decode and
    // re-apply the identical image every interval when a group (or a shuffle
    // deck) only offered one item.
    const val LAST_HOME_WRITE_ID = "last_home_write_id"
    const val LAST_HOME_WRITE_AT = "last_home_write_at"
    // --- Lock screen (independent from the home screen triggers) ---
    // The lock screen has its own timed switch (own toggle, interval + anchor),
    // so it can rotate on a completely different rhythm than the home screen.
    const val LOCK_TIMER_ENABLED = "lock_timer_enabled"
    const val LOCK_INTERVAL_MS = "lock_interval_ms"
    // Wall-clock anchor of the lock timer schedule (same idea as the home one).
    const val LOCK_TIMER_LAST_SWITCH_WALL_MS = "lock_timer_last_switch_wall_ms"
    // Global wallpaper settings
    const val GLOBAL_INTERVAL_MS = "global_interval_ms"
    // Wall-clock (ms) the current switch interval counts from: the last timed
    // switch. Kept across a lock/unlock pause (and across service restarts) so
    // an overdue tick catches up as soon as the screen comes back instead of
    // restarting a whole interval.
    const val TIMER_LAST_SWITCH_WALL_MS = "timer_last_switch_wall_ms"
    const val GLOBAL_SWITCH_MODE = "global_switch_mode"
    const val GLOBAL_SCALE_MODE = "global_scale_mode"
    // Low-res media clarity enhancement: "auto" (default) | "off" | "strong"
    const val CLARITY_MODE = "clarity_mode"
    // FILL/STRETCH: rotate media whose orientation mismatches the screen 90° so
    // more of it shows (instead of a thin perpendicular strip). On by default.
    const val ROTATE_MISMATCH_ENABLED = "rotate_mismatch_enabled"
    // Direction used by the auto-rotate feature: "cw" (default) | "ccw".
    const val ROTATE_MISMATCH_CW = "rotate_mismatch_cw"
    // Fade-in-from-black transition after each switch (default on).
    const val SWITCH_FADE_ENABLED = "switch_fade_enabled"
    // Play the video wallpaper's own audio track while the wallpaper is
    // visible (default OFF: a silent wallpaper is what users expect, and the
    // first switch to a video must not suddenly make noise).
    const val VIDEO_SOUND_ENABLED = "video_sound_enabled"
    // Periodic folder auto-scan
    const val AUTO_SCAN_ENABLED = "auto_scan_enabled"
    const val AUTO_SCAN_INTERVAL_MS = "auto_scan_interval_ms"
    // Wall-clock ms of the last auto-scan run (by the periodic worker or by the
    // catch-up run when the app is opened). Settings shows it, and it is what
    // the catch-up compares against the configured interval.
    const val AUTO_SCAN_LAST_RUN_AT = "auto_scan_last_run_at"
    /**
     * MediaStore generation of the last completed auto-scan run. When the store
     * has not changed since then, re-querying every imported folder cannot find
     * anything new, so the whole run is skipped (see FolderAutoScanWorker).
     */
    const val AUTO_SCAN_LAST_GENERATION = "auto_scan_last_generation"
    /**
     * Size of the pass the SHUFFLE deck is playing through, as the live engine
     * last saw it (its "Shuffle pick" log line reports `deck/saved`). The shown
     * ids themselves live in the `shuffle_shown` table since schema v6 - the old
     * `shuffle_shown_ids` / `shuffle_shown_ids_lock` keys (a comma-separated id
     * list re-written on every switch) are deleted by MIGRATION_5_6.
     */
    const val SHUFFLE_ALL_COUNT = "shuffle_all_count"
    // Theme
    const val THEME_COLOR = "theme_color"
}
