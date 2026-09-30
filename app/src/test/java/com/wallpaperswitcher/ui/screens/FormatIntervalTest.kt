package com.wallpaperswitcher.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Interval labels are shown on the home card and on every interval row of the
 * settings screen, so the rounding rules are pinned here (a wrong unit made a
 * "1 小时" interval read as "0 分钟" before).
 */
class FormatIntervalTest {

    @Test
    fun secondsBelowAMinute() {
        assertEquals("10秒", formatInterval(10_000L))
        assertEquals("59秒", formatInterval(59_999L))
    }

    @Test
    fun minutesBelowAnHour() {
        assertEquals("1分钟", formatInterval(60_000L))
        assertEquals("30分钟", formatInterval(30 * 60_000L))
    }

    @Test
    fun hoursIncludeTheRemainingMinutes() {
        assertEquals("1小时", formatInterval(3_600_000L))
        assertEquals("2小时30分", formatInterval(150 * 60_000L))
    }

    @Test
    fun daysIncludeTheRemainingHours() {
        val day = 24L * 60 * 60 * 1000
        assertEquals("1天", formatInterval(day))
        assertEquals("2天6小时", formatInterval(2 * day + 6 * 3_600_000L))
    }

    @Test
    fun zeroAndNegativeValuesStayReadable() {
        assertEquals("0秒", formatInterval(0L))
        assertEquals("0秒", formatInterval(-1_000L))
    }
}
