package com.wallpaperswitcher.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            val offset = SwitchPicking.randomOffset(7, it.toLong())
            assertTrue(offset in 0..6)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun randomOffsetRejectsZeroCount() {
        SwitchPicking.randomOffset(0, 0L)
    }

    // --- 下一张预览 must agree with the switch (see SwitchPicking.stableIndex) ---

    @Test
    fun theSameSeedAlwaysYieldsTheSameIndex() {
        // The preview and the switch that follows it compute the pick from the
        // same state; without this, RANDOM/SHUFFLE previews disagreed with the
        // switch AND changed on every click.
        repeat(50) { i ->
            val seed = SwitchPicking.pickSeed(cursor = i.toLong(), deckSize = 3, universeSize = 97)
            assertEquals(
                SwitchPicking.stableIndex(97, seed),
                SwitchPicking.stableIndex(97, seed)
            )
        }
    }

    @Test
    fun aMovedCursorChangesTheIndex() {
        // After a switch the cursor (and for SHUFFLE the deck) has moved, so the
        // next pick must differ - otherwise every switch would repeat the same
        // media.
        val seen = (1L..40L).map { cursor ->
            SwitchPicking.stableIndex(
                1000,
                SwitchPicking.pickSeed(cursor, deckSize = 0, universeSize = 1000)
            )
        }
        assertTrue("expected several distinct indexes, got $seen", seen.toSet().size > 5)
    }

    @Test
    fun theAppliedSwitchCounterBreaksShortCycles() {
        // Seeding on the cursor alone makes the walk a fixed function: on a
        // small group a random mapping repeats after ~0.6*sqrt(N) steps, so a
        // 20-image group would loop through the same 2-3 pictures. The
        // applied-switch counter is part of the seed, so a long run must keep
        // producing fresh indexes.
        val universe = 20
        val indexes = (0L until 400L).map { seq ->
            SwitchPicking.stableIndex(
                universe,
                SwitchPicking.pickSeed(cursor = 7L, deckSize = 0, universeSize = universe, seq = seq)
            )
        }
        // The last 200 picks must not be a short repeating cycle: a 2-cycle
        // would produce at most 2 distinct values there.
        val tail = indexes.drop(200).toSet()
        assertTrue("expected a varied tail, got $tail", tail.size >= 10)
    }

    @Test
    fun pickSeedDependsOnEveryPart() {
        val base = SwitchPicking.pickSeed(cursor = 1L, deckSize = 2, universeSize = 3, seq = 4L)
        assertNotEquals(base, SwitchPicking.pickSeed(cursor = 2L, deckSize = 2, universeSize = 3, seq = 4L))
        assertNotEquals(base, SwitchPicking.pickSeed(cursor = 1L, deckSize = 5, universeSize = 3, seq = 4L))
        assertNotEquals(base, SwitchPicking.pickSeed(cursor = 1L, deckSize = 2, universeSize = 9, seq = 4L))
        assertNotEquals(base, SwitchPicking.pickSeed(cursor = 1L, deckSize = 2, universeSize = 3, seq = 5L))
    }

    @Test
    fun indexesStayInBounds() {
        repeat(200) { i ->
            val index = SwitchPicking.stableIndex(7, i.toLong() * 7919)
            assertTrue(index in 0..6)
        }
        assertEquals(0, SwitchPicking.stableIndex(1, 12345L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun stableIndexRejectsZeroCount() {
        SwitchPicking.stableIndex(0, 0L)
    }

    @Test
    fun shufflePickIsReproducibleForTheSameDeck() {
        // Same deck + same cursor -> same media, twice in a row (the preview
        // clicked twice must not re-roll)...
        val enabled = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        val shown = setOf(1L, 2L)
        val seed = SwitchPicking.pickSeed(cursor = 9L, deckSize = shown.size, universeSize = enabled.size)
        val first = SwitchPicking.pickUnseen(enabled, shown, 9L, seed)
        val second = SwitchPicking.pickUnseen(enabled, shown, 9L, seed)
        assertEquals(first, second)
        // ...and a deck that grew by one (a card really dealt) picks differently.
        val firstId = requireNotNull(first)
        val grown = shown + firstId
        val nextSeed = SwitchPicking.pickSeed(
            cursor = firstId, deckSize = grown.size, universeSize = enabled.size
        )
        val next = SwitchPicking.pickUnseen(enabled, grown, firstId, nextSeed)
        assertTrue(next != null && next !in grown)
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

    // --- 视频播完再切: timed ticks while a video is on screen ---

    @Test
    fun firstTimedTickHoldsUntilTheVideoPassEnds() {
        assertEquals(
            SwitchPicking.VideoEndHold.HOLD_PENDING,
            SwitchPicking.videoEndHold(
                optionEnabled = true, videoPlaying = true, holdPending = false
            )
        )
    }

    @Test
    fun laterTimedTicksAreDroppedWhileAHoldIsPending() {
        // The reported bug: the SECOND interval was let through (the guard
        // required !holdPending), so it cut the clip off mid-pass.
        assertEquals(
            SwitchPicking.VideoEndHold.DROP_TICK,
            SwitchPicking.videoEndHold(
                optionEnabled = true, videoPlaying = true, holdPending = true
            )
        )
    }

    @Test
    fun staleHoldIsReleasedOnceNoVideoIsOnScreen() {
        // The emulator deadlock: the held clip failed to start / was replaced,
        // so onVideoPassCompleted never ran and every later tick was dropped
        // forever ("still held" spam, timed switching dead). A hold with no
        // video on screen is stale and must be released by the tick itself.
        assertEquals(
            SwitchPicking.VideoEndHold.SWITCH_NOW,
            SwitchPicking.videoEndHold(
                optionEnabled = true, videoPlaying = false, holdPending = true
            )
        )
    }

    @Test
    fun disablingTheOptionReleasesAWaitingHold() {
        assertEquals(
            SwitchPicking.VideoEndHold.SWITCH_NOW,
            SwitchPicking.videoEndHold(
                optionEnabled = false, videoPlaying = true, holdPending = true
            )
        )
    }

    @Test
    fun ticksSwitchNormallyWhenNoVideoIsPlaying() {
        assertEquals(
            SwitchPicking.VideoEndHold.SWITCH_NOW,
            SwitchPicking.videoEndHold(
                optionEnabled = true, videoPlaying = false, holdPending = false
            )
        )
        assertEquals(
            SwitchPicking.VideoEndHold.SWITCH_NOW,
            SwitchPicking.videoEndHold(
                optionEnabled = false, videoPlaying = false, holdPending = false
            )
        )
    }
}
