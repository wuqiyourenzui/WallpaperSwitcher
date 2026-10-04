package com.wallpaperswitcher.ui.screens

import com.wallpaperswitcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Interval labels are shown on the home card and on every interval row of the
 * settings screen, so the rounding rules are pinned here (a wrong unit made a
 * "1 小时" interval read as "0 分钟" before).
 *
 * The unit selection is pure ([intervalParts]); the labels come from resources
 * ([IntervalUnit.labelRes]) so the same numbers render in every language.
 */
class FormatIntervalTest {

    @Test
    fun secondsBelowAMinute() {
        assertEquals(IntervalParts(10, IntervalUnit.SECONDS), intervalParts(10_000L))
        assertEquals(IntervalParts(59, IntervalUnit.SECONDS), intervalParts(59_999L))
    }

    @Test
    fun minutesBelowAnHour() {
        assertEquals(IntervalParts(1, IntervalUnit.MINUTES), intervalParts(60_000L))
        assertEquals(
            IntervalParts(30, IntervalUnit.MINUTES),
            intervalParts(30 * 60_000L)
        )
    }

    @Test
    fun hoursIncludeTheRemainingMinutes() {
        assertEquals(IntervalParts(1, IntervalUnit.HOURS), intervalParts(3_600_000L))
        // 2 小时 30 分: the tail uses the compact unit so "2小时30分" / "2h 30m".
        assertEquals(
            IntervalParts(2, IntervalUnit.HOURS, 30, IntervalUnit.MINUTES_SHORT),
            intervalParts(150 * 60_000L)
        )
    }

    @Test
    fun daysIncludeTheRemainingHours() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(IntervalParts(1, IntervalUnit.DAYS), intervalParts(day))
        assertEquals(
            IntervalParts(2, IntervalUnit.DAYS, 6, IntervalUnit.HOURS_SHORT),
            intervalParts(2 * day + 6 * 3_600_000L)
        )
    }

    @Test
    fun zeroAndNegativeValuesStayReadable() {
        assertEquals(IntervalParts(0, IntervalUnit.SECONDS), intervalParts(0L))
        assertEquals(IntervalParts(0, IntervalUnit.SECONDS), intervalParts(-1_000L))
    }

    @Test
    fun everyUnitHasItsOwnLabelAndTheTailHasNoDuplicate() {
        // A wiring mistake (two units pointing at the same string, or a tail
        // unit reused as a primary one) would silently mis-render every row.
        val primaries = listOf(
            IntervalUnit.SECONDS,
            IntervalUnit.MINUTES,
            IntervalUnit.HOURS,
            IntervalUnit.DAYS
        )
        assertEquals(
            primaries.size,
            primaries.map { it.labelRes }.toSet().size
        )
        assertEquals(R.string.interval_minutes_short, IntervalUnit.MINUTES_SHORT.labelRes)
        assertEquals(R.string.interval_hours_short, IntervalUnit.HOURS_SHORT.labelRes)

        // An exact interval never carries a tail.
        assertNull(intervalParts(60 * 60_000L).secondaryUnit)
        assertNull(intervalParts(24L * 60 * 60 * 1000).secondaryUnit)
    }
}
