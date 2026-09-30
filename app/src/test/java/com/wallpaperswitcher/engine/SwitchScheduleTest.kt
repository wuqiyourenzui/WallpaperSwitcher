package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchScheduleTest {

    private val now = 1_000_000_000L

    @Test
    fun firstRunWaitsAFullInterval() {
        assertEquals(60_000L, SwitchSchedule.waitMs(now, 60_000L, now))
    }

    @Test
    fun ticksWhenExactlyDue() {
        assertEquals(0L, SwitchSchedule.waitMs(now, 60_000L, now + 60_000L))
    }

    @Test
    fun overdueScheduleCatchesUpImmediately() {
        // Locked for 30 minutes with a 1 minute interval: switch now instead of
        // waiting another full interval after unlocking.
        assertEquals(0L, SwitchSchedule.waitMs(now, 60_000L, now + 30 * 60_000L))
    }

    @Test
    fun shortLockKeepsTheRemainderOfTheInterval() {
        // Locked for only 20s of a 60s interval: wait the remaining 40s.
        assertEquals(40_000L, SwitchSchedule.waitMs(now, 60_000L, now + 20_000L))
    }

    @Test
    fun intervalIsClampedToTheMinimum() {
        assertEquals(10_000L, SwitchSchedule.waitMs(now, 1_000L, now))
    }

    @Test
    fun normalTickIsNotACatchUp() {
        assertFalse(SwitchSchedule.isCatchUp(now, 60_000L, now + 60_000L))
        // A few milliseconds of scheduling jitter must not look like a pause.
        assertFalse(SwitchSchedule.isCatchUp(now, 60_000L, now + 60_050L))
    }

    @Test
    fun longPauseIsACatchUp() {
        assertTrue(SwitchSchedule.isCatchUp(now, 60_000L, now + 5 * 60_000L))
    }

    @Test
    fun shortIntervalUsesHalfTheIntervalAsSlack() {
        // 10s interval: 5s of slack. 4s late is a normal (slow) tick...
        assertFalse(SwitchSchedule.isCatchUp(now, 10_000L, now + 14_000L))
        // ...6s late means the timer must have been paused.
        assertTrue(SwitchSchedule.isCatchUp(now, 10_000L, now + 16_000L))
    }

    @Test
    fun longIntervalDoesNotGetAProportionalSlack() {
        // A 24h interval must not treat "12h late" as a normal tick, and a
        // restart only minutes after the due time must still catch up.
        val day = 24 * 60 * 60 * 1000L
        assertTrue(SwitchSchedule.isCatchUp(now, day, now + day + 5 * 60_000L))
    }

    @Test
    fun ancientAnchorRestartsTheSchedule() {
        val twoDays = 2L * 24 * 60 * 60 * 1000
        assertEquals(now, SwitchSchedule.resolveAnchor(now - twoDays, now))
    }

    @Test
    fun recentAnchorIsKept() {
        assertEquals(now - 30_000L, SwitchSchedule.resolveAnchor(now - 30_000L, now))
    }

    @Test
    fun missingAnchorFallsBackToNow() {
        assertEquals(now, SwitchSchedule.resolveAnchor(0L, now))
    }

    @Test
    fun futureAnchorFallsBackToNow() {
        // Clock moved backwards (NTP): do not wait "negative" time.
        assertEquals(now, SwitchSchedule.resolveAnchor(now + 5_000L, now))
    }
}
