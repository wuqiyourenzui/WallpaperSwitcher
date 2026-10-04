package com.wallpaperswitcher.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大图浏览的纯逻辑：索引环绕、落点分区、双击判定、相邻分组跳转。
 *
 * [MediaBrowseScreen] 的组合层只负责把指针事件喂给这些函数并把结果动作执行掉，
 * 所以这里不碰 Compose、不碰 Room、也不碰 Android 框架 —— 全是 JVM 单测。
 */
class MediaBrowseLogicTest {

    // ---------- 索引环绕 ----------

    @Test
    fun nextIndexAdvancesAndWrapsAtTheEnd() {
        assertEquals(1, nextBrowseIndex(0, 3))
        assertEquals(2, nextBrowseIndex(1, 3))
        assertEquals(0, nextBrowseIndex(2, 3))
    }

    @Test
    fun previousIndexStepsBackAndWrapsToTheLast() {
        assertEquals(2, previousBrowseIndex(0, 3))
        assertEquals(1, previousBrowseIndex(2, 3))
        assertEquals(0, previousBrowseIndex(1, 3))
    }

    @Test
    fun onlyOneItemWrapsOnItself() {
        assertEquals(0, nextBrowseIndex(0, 1))
        assertEquals(0, previousBrowseIndex(0, 1))
    }

    @Test
    fun emptyListNeverProducesAnOutOfRangeIndex() {
        assertEquals(0, nextBrowseIndex(0, 0))
        assertEquals(0, previousBrowseIndex(0, 0))
        assertEquals(0, clampBrowseIndex(7, 0))
        assertEquals(0, wrapBrowseIndex(-3, 0))
        assertEquals(0, wrapBrowseIndex(9, 0))
    }

    @Test
    fun staleIndexIsClampedAfterTheListShrinks() {
        // 删掉几张之后列表变短：光标停在"同一个位置"，由显示层夹到最后一页。
        assertEquals(2, clampBrowseIndex(5, 3))
        assertEquals(0, clampBrowseIndex(-2, 3))
        assertEquals(1, clampBrowseIndex(1, 3))
        // 5 夹到 2（最后一张），下一张环绕回第一张。
        assertEquals(0, nextBrowseIndex(5, 3))
        assertEquals(1, previousBrowseIndex(5, 3))
    }

    @Test
    fun multiStepMovesSkipSeveralItems() {
        assertEquals(3, nextBrowseIndex(0, 4, step = 3))
        assertEquals(1, nextBrowseIndex(3, 4, step = 2))
        assertEquals(2, previousBrowseIndex(1, 4, step = 3))
    }

    // ---------- 点按分区 ----------

    @Test
    fun tapZonesSplitSidesAndCenter() {
        // 1000px 宽：左右各 300px 是"抬手即翻页"区，中间 400px 留给双击。
        assertEquals(BrowseZone.Left, browseZoneOf(0f, 1000f))
        assertEquals(BrowseZone.Left, browseZoneOf(299f, 1000f))
        assertEquals(BrowseZone.Center, browseZoneOf(300f, 1000f))
        assertEquals(BrowseZone.Center, browseZoneOf(500f, 1000f))
        assertEquals(BrowseZone.Center, browseZoneOf(700f, 1000f))
        assertEquals(BrowseZone.Right, browseZoneOf(701f, 1000f))
        assertEquals(BrowseZone.Right, browseZoneOf(1000f, 1000f))
    }

    @Test
    fun unmeasuredWidthFallsBackToTheCenter() {
        // 中间区没有单击动作，测量还没完成时落在这里最安全（不会误翻页）。
        assertEquals(BrowseZone.Center, browseZoneOf(0f, 0f))
        assertEquals(BrowseZone.Center, browseZoneOf(50f, -10f))
    }

    @Test
    fun sideFractionIsClampedToHalfTheWidth() {
        // 传入越界的比例也不能让左右两区重叠。
        assertEquals(BrowseZone.Left, browseZoneOf(10f, 100f, sideFraction = 0.9f))
        assertEquals(BrowseZone.Center, browseZoneOf(50f, 100f, sideFraction = 0.9f))
    }

    // ---------- 双击判定 ----------

    @Test
    fun aSingleCompletedTapIsNotADoubleTap() {
        val matcher = BrowseDoubleTapMatcher()
        assertFalse(matcher.onCompletedTap(100f, 100f, 1_000L))
    }

    @Test
    fun secondTapInsideTheWindowIsADoubleTap() {
        val matcher = BrowseDoubleTapMatcher()
        assertFalse(matcher.onCompletedTap(100f, 100f, 1_000L))
        assertTrue(matcher.onCompletedTap(110f, 105f, 1_200L))
    }

    @Test
    fun windowBoundaryIsInclusiveAndTheNextMillisecondIsNot() {
        val matcher = BrowseDoubleTapMatcher(windowMs = 280L)
        assertFalse(matcher.onCompletedTap(0f, 0f, 1_000L))
        assertTrue(matcher.onCompletedTap(0f, 0f, 1_280L))

        val other = BrowseDoubleTapMatcher(windowMs = 280L)
        assertFalse(other.onCompletedTap(0f, 0f, 1_000L))
        assertFalse(other.onCompletedTap(0f, 0f, 1_281L))
    }

    @Test
    fun defaultWindowMatchesTheDocumentedThreshold() {
        val matcher = BrowseDoubleTapMatcher()
        assertFalse(matcher.onCompletedTap(0f, 0f, 0L))
        assertTrue(matcher.onCompletedTap(0f, 0f, BROWSE_DOUBLE_TAP_MS))
    }

    @Test
    fun tapsTooFarApartOnScreenAreNotADoubleTap() {
        // 连点两下左边想翻页，不该被当成收藏。
        val matcher = BrowseDoubleTapMatcher(slopPx = 56f)
        assertFalse(matcher.onCompletedTap(100f, 100f, 1_000L))
        assertFalse(matcher.onCompletedTap(100f + 57f, 100f, 1_100L))
    }

    @Test
    fun tapsExactlyAtTheSlopLimitStillCount() {
        val matcher = BrowseDoubleTapMatcher(slopPx = 56f)
        assertFalse(matcher.onCompletedTap(100f, 100f, 1_000L))
        assertTrue(matcher.onCompletedTap(100f, 100f + 56f, 1_100L))
    }

    @Test
    fun aThirdTapDoesNotFireASecondDoubleTap() {
        // 三连击只算一次双击：命中之后状态清空，第三下是新一轮的第一下。
        val matcher = BrowseDoubleTapMatcher()
        assertFalse(matcher.onCompletedTap(0f, 0f, 1_000L))
        assertTrue(matcher.onCompletedTap(0f, 0f, 1_100L))
        assertFalse(matcher.onCompletedTap(0f, 0f, 1_200L))
    }

    @Test
    fun resetForgetsThePendingTap() {
        val matcher = BrowseDoubleTapMatcher()
        assertFalse(matcher.onCompletedTap(0f, 0f, 1_000L))
        matcher.reset()
        assertFalse(matcher.onCompletedTap(0f, 0f, 1_100L))
    }

    @Test
    fun aTapFromTheFutureIsTreatedAsAFreshTap() {
        // 时钟回拨（或乱序事件）：绝不因为负的时间差就判成双击。
        val matcher = BrowseDoubleTapMatcher()
        assertFalse(matcher.onCompletedTap(0f, 0f, 5_000L))
        assertFalse(matcher.onCompletedTap(0f, 0f, 1_000L))
        // 上一拍已经被记成 1000ms，之后照常判定。
        assertTrue(matcher.onCompletedTap(0f, 0f, 1_100L))
    }

    // ---------- 相邻分组 ----------

    @Test
    fun groupStepMovesOneStepAndWraps() {
        assertEquals(1, steppedGroupIndex(0, 1, 3))
        assertEquals(2, steppedGroupIndex(0, -1, 3))
        assertEquals(0, steppedGroupIndex(2, 1, 3))
    }

    @Test
    fun groupStepSkipsGroupsWithoutMedia() {
        val empty = setOf(1)
        val isEmpty: (Int) -> Boolean = { it in empty }
        assertEquals(2, steppedGroupIndex(0, 1, 3, isEmpty))
        assertEquals(2, steppedGroupIndex(1, 1, 3, isEmpty))
        assertEquals(0, steppedGroupIndex(2, 1, 3, isEmpty))
        assertEquals(2, steppedGroupIndex(0, -1, 3, isEmpty))
        assertEquals(0, steppedGroupIndex(2, -1, 3, isEmpty))
    }

    @Test
    fun groupStepSkipsSeveralEmptyGroupsInARow() {
        // 下标 3 是唯一有素材的分组。
        val isEmpty: (Int) -> Boolean = { it != 3 }
        assertEquals(3, steppedGroupIndex(0, 1, 5, isEmpty))
        assertEquals(3, steppedGroupIndex(4, 1, 5, isEmpty))
    }

    @Test
    fun groupStepStaysPutWhenEverythingIsEmpty() {
        // 全空时不能原地打转找出口（会死循环），直接停住。
        assertEquals(1, steppedGroupIndex(1, 1, 3) { true })
        assertEquals(0, steppedGroupIndex(0, -1, 3) { true })
    }

    @Test
    fun groupStepWithASingleOrNoGroupStaysPut() {
        assertEquals(0, steppedGroupIndex(0, 1, 1))
        assertEquals(0, steppedGroupIndex(0, -1, 0))
    }

    @Test
    fun outOfRangeGroupIndexIsWrappedFirst() {
        // 分组被删掉之后本地下标可能越界：先用环绕归一化，再走一步。
        assertEquals(1, steppedGroupIndex(9, 1, 3))
        assertEquals(0, steppedGroupIndex(-1, 1, 3))
    }
}
