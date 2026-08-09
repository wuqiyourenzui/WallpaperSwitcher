package com.wallpaperswitcher.data

object SettingsKeys {
    const val SERVICE_ENABLED = "service_enabled"
    const val DOUBLE_TAP_ENABLED = "double_tap_enabled"
    const val UNLOCK_SWITCH_ENABLED = "unlock_switch_enabled"
    const val LAST_IMAGE_ID = "last_image_id"
    const val SEQUENTIAL_INDEX = "sequential_index"
    const val VIDEO_SEQ_INDEX = "video_seq_index"
    // Global wallpaper settings
    const val GLOBAL_INTERVAL_MS = "global_interval_ms"
    const val GLOBAL_SWITCH_MODE = "global_switch_mode"
    const val GLOBAL_SCALE_MODE = "global_scale_mode"
    // Low-res media clarity enhancement: "auto" (default) | "off" | "strong"
    const val CLARITY_MODE = "clarity_mode"
    // Fade-in-from-black transition after each switch (default on).
    const val SWITCH_FADE_ENABLED = "switch_fade_enabled"
    // Periodic folder auto-scan
    const val AUTO_SCAN_ENABLED = "auto_scan_enabled"
    const val AUTO_SCAN_INTERVAL_MS = "auto_scan_interval_ms"
    // Shuffle state persistence (comma-separated image IDs)
    const val SHUFFLE_SHOWN_IDS = "shuffle_shown_ids"
    const val SHUFFLE_ALL_COUNT = "shuffle_all_count"
    // Theme
    const val THEME_COLOR = "theme_color"
}
