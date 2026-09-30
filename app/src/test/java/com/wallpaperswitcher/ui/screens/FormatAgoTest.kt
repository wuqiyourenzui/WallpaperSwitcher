package com.wallpaperswitcher.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings shows "上次扫描：<formatAgo>" so the user can tell whether the folder
 * auto-scan really ran (the periodic job may be deferred by the ROM).
 */
class FormatAgoTest {

    private val now = 1_700_000_000_000L

    @Test
    fun neverRan() {
        assertEquals("从未", formatAgo(0L, now))
        assertEquals("从未", formatAgo(-5L, now))
    }

    @Test
    fun justNow() {
        assertEquals("刚刚", formatAgo(now - 30_000L, now))
    }

    @Test
    fun minutesHoursDays() {
        assertEquals("5 分钟前", formatAgo(now - 5 * 60_000L, now))
        assertEquals("59 分钟前", formatAgo(now - 59 * 60_000L, now))
        assertEquals("1 小时前", formatAgo(now - 60 * 60_000L, now))
        assertEquals("23 小时前", formatAgo(now - 23 * 3_600_000L, now))
        assertEquals("1 天前", formatAgo(now - 24 * 3_600_000L, now))
        assertEquals("3 天前", formatAgo(now - 3 * 24 * 3_600_000L, now))
    }

    @Test
    fun futureTimestampIsClamped() {
        // Clock moved backwards: never report a negative age.
        assertEquals("刚刚", formatAgo(now + 60_000L, now))
    }
}
