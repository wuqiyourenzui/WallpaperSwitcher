package com.wallpaperswitcher.ui.screens

import com.wallpaperswitcher.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings shows "上次扫描：<formatAgo>" so the user can tell whether the folder
 * auto-scan really ran (the periodic job may be deferred by the ROM).
 *
 * As with [FormatIntervalTest] the unit selection is pure; the wording lives in
 * resources so the same boundaries read naturally in every language.
 */
class FormatAgoTest {

    private val now = 1_700_000_000_000L

    @Test
    fun neverRan() {
        assertEquals(AgoParts.Never, agoParts(0L, now))
        assertEquals(AgoParts.Never, agoParts(-5L, now))
    }

    @Test
    fun justNow() {
        assertEquals(AgoParts.JustNow, agoParts(now - 30_000L, now))
    }

    @Test
    fun minutesHoursDays() {
        assertEquals(AgoParts.Count(5, IntervalUnit.MINUTES), agoParts(now - 5 * 60_000L, now))
        assertEquals(AgoParts.Count(59, IntervalUnit.MINUTES), agoParts(now - 59 * 60_000L, now))
        assertEquals(AgoParts.Count(1, IntervalUnit.HOURS), agoParts(now - 60 * 60_000L, now))
        assertEquals(AgoParts.Count(23, IntervalUnit.HOURS), agoParts(now - 23 * 3_600_000L, now))
        assertEquals(AgoParts.Count(1, IntervalUnit.DAYS), agoParts(now - 24 * 3_600_000L, now))
        assertEquals(
            AgoParts.Count(3, IntervalUnit.DAYS),
            agoParts(now - 3 * 24 * 3_600_000L, now)
        )
    }

    @Test
    fun futureTimestampIsClamped() {
        // Clock moved backwards: never report a negative age.
        assertEquals(AgoParts.JustNow, agoParts(now + 60_000L, now))
    }

    @Test
    fun everyAgoUnitHasItsOwnLabel() {
        val labels = listOf(IntervalUnit.MINUTES, IntervalUnit.HOURS, IntervalUnit.DAYS)
            .map { it.agoLabelRes }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(R.string.ago_minutes, IntervalUnit.MINUTES.agoLabelRes)
        assertEquals(R.string.ago_hours, IntervalUnit.HOURS.agoLabelRes)
        assertEquals(R.string.ago_days, IntervalUnit.DAYS.agoLabelRes)
    }
}
