package com.wallpaperswitcher.ui.screens

/**
 * Human-readable text for a switch interval.
 *
 * Lives in its own (Compose-free) file so the formatting is unit-testable: the
 * settings screen and the home card both show these strings.
 */
fun formatInterval(ms: Long): String {
    // Clamped: intervals are always >= 10s, but a corrupt/negative stored value
    // must still render as a sane label instead of "-1秒".
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        totalSeconds < 60 -> "${totalSeconds}秒"
        minutes < 60 -> "${minutes}分钟"
        hours < 24 -> "${hours}小时${if (minutes % 60 > 0) "${minutes % 60}分" else ""}"
        else -> "${days}天${if (hours % 24 > 0) "${hours % 24}小时" else ""}"
    }
}

/**
 * "3 分钟前 / 2 小时前 / 1 天前" style text for a past wall-clock timestamp
 * (0 = never). Used by Settings to show when the folder auto-scan last ran, so
 * "is it working?" can be answered at a glance.
 */
fun formatAgo(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs <= 0L) return "从未"
    val elapsed = (nowMs - timestampMs).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "${minutes} 分钟前"
        minutes < 60L * 24L -> "${minutes / 60L} 小时前"
        else -> "${minutes / (60L * 24L)} 天前"
    }
}
