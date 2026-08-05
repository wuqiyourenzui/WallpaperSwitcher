package com.wallpaperswitcher.data

object SettingsKeys {
    const val SERVICE_ENABLED = "service_enabled"
    const val DOUBLE_TAP_ENABLED = "double_tap_enabled"
    const val UNLOCK_SWITCH_ENABLED = "unlock_switch_enabled"
    const val LAST_IMAGE_ID = "last_image_id"
    const val SEQUENTIAL_INDEX = "sequential_index"
    // Global wallpaper settings
    const val GLOBAL_INTERVAL_MS = "global_interval_ms"
    const val GLOBAL_SWITCH_MODE = "global_switch_mode"
    const val GLOBAL_SCALE_MODE = "global_scale_mode"
    // Shuffle state persistence (comma-separated image IDs)
    const val SHUFFLE_SHOWN_IDS = "shuffle_shown_ids"
    const val SHUFFLE_ALL_COUNT = "shuffle_all_count"
}
