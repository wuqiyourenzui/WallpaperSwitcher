package com.wallpaperswitcher.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchPickingTest {

    @Test
    fun passContinuesWhenTheEnabledCountChanged() {
        // 3 of 10 shown, and the set grew/shrunk (it was 8 when the pass began):
        // the pass must continue - clearing the shown set here repeated media
        // that had already been shown before the pass finished.
        assertFalse(SwitchPicking.shouldResetShuffleDeck(shownSize = 3, totalCount = 10))
        assertFalse(SwitchPicking.shouldResetShuffleDeck(shownSize = 3, totalCount = 8))
    }

    @Test
    fun deckResetsWhenExhausted() {
        assertTrue(SwitchPicking.shouldResetShuffleDeck(shownSize = 10, totalCount = 10))
    }

    @Test
    fun deckKeptWhileHealthy() {
        assertFalse(SwitchPicking.shouldResetShuffleDeck(shownSize = 3, totalCount = 10))
    }

    @Test
    fun emptyPassIsNotReset() {
        assertFalse(SwitchPicking.shouldResetShuffleDeck(shownSize = 0, totalCount = 10))
    }

    @Test
    fun shrinkingSetBelowWhatWasShownResets() {
        // 7 media were shown from a 20-item set; the user then disabled groups so
        // only 5 media are enabled. The old pass cannot continue - start over.
        assertTrue(SwitchPicking.shouldResetShuffleDeck(shownSize = 7, totalCount = 5))
    }

    @Test
    fun randomOffsetStaysWithinBounds() {
        repeat(500) {
            val offset = SwitchPicking.randomOffset(7)
            assertTrue(offset in 0..6)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun randomOffsetRejectsZeroCount() {
        SwitchPicking.randomOffset(0)
    }

    // --- SHUFFLE pass pick (see MediaPick.shuffleUnseen) ---

    @Test
    fun pickUnseenSkipsShownAndCurrentMedia() {
        assertEquals(5L, SwitchPicking.pickUnseen(listOf(1L, 2L, 3L, 4L, 5L), setOf(1L, 2L, 3L), 4L))
    }

    @Test
    fun pickUnseenReturnsNullWhenThePassIsOver() {
        assertNull(SwitchPicking.pickUnseen(listOf(1L, 2L), setOf(1L, 2L), 0L))
    }

    @Test
    fun pickUnseenReturnsNullForAnEmptySlot() {
        assertNull(SwitchPicking.pickUnseen(emptyList(), emptySet(), 0L))
    }

    @Test
    fun pickUnseenOnlyReturnsIdsOfTheSlot() {
        val enabled = listOf(11L, 22L, 33L)
        repeat(200) {
            val picked = SwitchPicking.pickUnseen(enabled, emptySet(), 0L)
            assertTrue(picked in enabled)
        }
    }

    @Test
    fun pickUnseenScalesPastTheSqlVariableLimit() {
        val enabled = (1L..1500L).toList()
        val shown = (1L..1499L).toSet()
        assertEquals(1500L, SwitchPicking.pickUnseen(enabled, shown, 0L))
    }

    @Test
    fun pickUnseenIgnoresAnExcludeIdThatIsNotInTheSlot() {
        // The media on screen can belong to the other screen (a manual lock pick)
        // or to a row that was just deleted; it simply matches nothing.
        assertEquals(1L, SwitchPicking.pickUnseen(listOf(1L), emptySet(), 99L))
    }

    // --- Desktop timer policy while our app is in the foreground (user request) ---

    @Test
    fun homeTimerNeverSwitchesWhileTheAppIsOpen() {
        // "应用在前台完全不切": not even the first due tick runs - the wallpaper
        // behind the app cannot be seen, so every tick would be a full decode
        // (plus an encode + wallpaper write in static mode) for nothing.
        assertTrue(
            SwitchPicking.shouldIdleWhileAppInForeground(appInForeground = true)
        )
    }

    @Test
    fun desktopKeepsSwitchingNormallyWhenTheAppIsNotInFront() {
        // Back on the desktop (or any non-foreground session): the policy must
        // never hold the timer back. The tick was NOT consumed while the app was
        // open, so it is overdue and switches immediately (catch-up).
        assertFalse(
            SwitchPicking.shouldIdleWhileAppInForeground(appInForeground = false)
        )
    }
}
