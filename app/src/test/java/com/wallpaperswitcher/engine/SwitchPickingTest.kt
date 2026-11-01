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

    // --- 顺序模式: 「本组走完」判据 (see SwitchPicking.sequentialWrapped) ---

    @Test
    fun ascendingWalkIsExhaustedOnlyWhenThePickWrapsBack() {
        assertFalse(SwitchPicking.sequentialWrapped(pickedId = 7, cursorId = 5, newestFirst = false))
        // getSequentialInGroupAfter found nothing and GroupPick fell back to
        // the group's first media: the walk is over.
        assertTrue(SwitchPicking.sequentialWrapped(pickedId = 2, cursorId = 5, newestFirst = false))
        // Single-item group: the pick is the cursor itself.
        assertTrue(SwitchPicking.sequentialWrapped(pickedId = 5, cursorId = 5, newestFirst = false))
    }

    @Test
    fun newestFirstWalkIsExhaustedOnlyWhenThePickWrapsForward() {
        // 顺序=新的在前 walks newest → oldest, so a smaller id is the normal
        // next step and only the wrap back to the newest media ends the group.
        assertFalse(SwitchPicking.sequentialWrapped(pickedId = 3, cursorId = 5, newestFirst = true))
        assertTrue(SwitchPicking.sequentialWrapped(pickedId = 9, cursorId = 5, newestFirst = true))
        assertTrue(SwitchPicking.sequentialWrapped(pickedId = 5, cursorId = 5, newestFirst = true))
    }

    // --- 顺序模式的参考点 = 屏幕上那张 (see SwitchPicking.sequentialAnchor) ---

    @Test
    fun sequentialContinuesAfterTheDisplayedImageNotTheStaleCursor() {
        // 用户报的那个场景：随机/洗牌期间分组游标停在 5（上一次顺序模式的旧位置），
        // 屏幕上显示的却是 9（同一分组）。切到顺序后下一张必须是 9 的下一张，
        // 也就是参考点要变成 9，而不是继续用 5。
        assertEquals(
            9L,
            SwitchPicking.sequentialAnchor(
                displayedId = 9, displayedGroupId = 3, walkGroupId = 3, cursor = 5
            )
        )
        // 正常顺序运行时两者本来就相等，锚点不改变任何行为。
        assertEquals(
            9L,
            SwitchPicking.sequentialAnchor(
                displayedId = 9, displayedGroupId = 3, walkGroupId = 3, cursor = 9
            )
        )
    }

    @Test
    fun theCursorStillWinsWhenTheDisplayedImageIsInAnotherGroup() {
        // 本组走完已推进到下一个分组（屏幕上是 2 组的图），或屏幕上那张是别组的：
        // 这时只有分组游标有意义，不能被屏幕上的图带跑。
        assertEquals(
            5L,
            SwitchPicking.sequentialAnchor(
                displayedId = 9, displayedGroupId = 2, walkGroupId = 3, cursor = 5
            )
        )
    }

    @Test
    fun nothingOnScreenOrUnknownGroupKeepsTheCursor() {
        // 冷启动 / 屏幕还没内容（displayedId = 0）：保持游标。
        assertEquals(
            5L,
            SwitchPicking.sequentialAnchor(
                displayedId = 0, displayedGroupId = 3, walkGroupId = 3, cursor = 5
            )
        )
        // 屏幕上那张已经从库里删掉（查不到分组 → 0）：同样保持游标。
        assertEquals(
            5L,
            SwitchPicking.sequentialAnchor(
                displayedId = 9, displayedGroupId = 0, walkGroupId = 3, cursor = 5
            )
        )
        // 没有要走的分组（屏幕级兜底路径）：保持游标。
        assertEquals(
            5L,
            SwitchPicking.sequentialAnchor(
                displayedId = 9, displayedGroupId = 3, walkGroupId = 0, cursor = 5
            )
        )
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

    // --- 视频播完再切: 定时 tick 撞上视频时只丢不切 ------------------------

    @Test
    fun timerTicksAreDroppedWhileAVideoIsOnScreen() {
        // 选项开着 + 屏幕上是视频 → 定时一律丢掉，视频只按"播完 N 次"切换。
        // （旧实现是"等这一段播完再切"，结果视频只播一遍就被换掉，次数失效。）
        assertEquals(
            SwitchPicking.TimerWithVideo.DROP,
            SwitchPicking.timerWithVideo(
                optionEnabled = true,
                videoActive = true,
                consecutiveDrops = 0,
                dropLimit = 12,
            )
        )
        assertEquals(
            SwitchPicking.TimerWithVideo.DROP,
            SwitchPicking.timerWithVideo(true, videoActive = true, consecutiveDrops = 11, dropLimit = 12)
        )
    }

    @Test
    fun timerSwitchesImagesNormally() {
        // 切到图片之后，定时切换照常生效。
        assertEquals(
            SwitchPicking.TimerWithVideo.SWITCH_NOW,
            SwitchPicking.timerWithVideo(true, videoActive = false, consecutiveDrops = 0, dropLimit = 12)
        )
    }

    @Test
    fun optionOffLetsTheTimerCutTheVideo() {
        assertEquals(
            SwitchPicking.TimerWithVideo.SWITCH_NOW,
            SwitchPicking.timerWithVideo(false, videoActive = true, consecutiveDrops = 0, dropLimit = 12)
        )
    }

    @Test
    fun brokenVideoDoesNotBlockTimedSwitchingForever() {
        // 视频起不来/一直播不完时，连续丢弃到上限就放行一次（兜底）。
        assertEquals(
            SwitchPicking.TimerWithVideo.SWITCH_NOW,
            SwitchPicking.timerWithVideo(true, videoActive = true, consecutiveDrops = 12, dropLimit = 12)
        )
    }

    // --- 视频播完再切（与定时切换解耦）------------------------------------

    @Test
    fun videoEndSwitchesOnTheFirstPassByDefault() {
        // 默认播放 1 次：第一段播完就要换，且**不看定时开关**（这个函数里没有
        // 定时参数，正因为如此）。
        assertTrue(SwitchPicking.videoEndSwitchDue(optionEnabled = true, repeats = 1, passesThisClip = 0))
    }

    @Test
    fun videoEndWaitsForTheConfiguredCount() {
        // 设成 3 次：第 1、2 段播完继续放着，第 3 段播完才换。
        assertFalse(SwitchPicking.videoEndSwitchDue(true, repeats = 3, passesThisClip = 0))
        assertFalse(SwitchPicking.videoEndSwitchDue(true, repeats = 3, passesThisClip = 1))
        assertTrue(SwitchPicking.videoEndSwitchDue(true, repeats = 3, passesThisClip = 2))
    }

    @Test
    fun videoEndRespectsTheOptionAndClampsTheCount() {
        // 选项关着就永不自动切；0 / 负数夹回 1；次数没有上限，42 就等 42 段。
        assertFalse(SwitchPicking.videoEndSwitchDue(false, repeats = 1, passesThisClip = 0))
        assertTrue(SwitchPicking.videoEndSwitchDue(true, repeats = 0, passesThisClip = 0))
        assertFalse(
            SwitchPicking.videoEndSwitchDue(
                true,
                repeats = 42,
                passesThisClip = 40,
            )
        )
        assertTrue(
            SwitchPicking.videoEndSwitchDue(
                true,
                repeats = 42,
                passesThisClip = 41,
            )
        )
    }
}
