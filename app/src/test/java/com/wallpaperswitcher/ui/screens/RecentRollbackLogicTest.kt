package com.wallpaperswitcher.ui.screens

import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.RecentShownEntry
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.engine.WallpaperTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最近显示」回滚页的纯逻辑：推回写哪块屏、两屏合并怎么去重、推回之后怎么置顶，
 * 以及相对时间的边界。
 *
 * 这一页的价值全在"点下去会不会写错屏"和"时间显示对不对"这两件事上，两者都被抽成
 * 纯函数（见 RecentScreen.kt 的纯逻辑段），所以不需要 Compose / Room / Android 框架
 * 就能把边界钉死。
 */
class RecentRollbackLogicTest {

    private val now = 1_700_000_000_000L

    private fun image(id: Long, name: String = "img$id", groupId: Long = 1L) = WallpaperImage(
        id = id,
        groupId = groupId,
        uri = "content://media/external/images/media/$id",
        displayName = name,
    )

    private fun row(
        id: Long,
        slot: String,
        shownAt: Long,
        groupId: Long = 1L,
    ) = RecentShownEntry(slot = slot, shownAt = shownAt, image = image(id, groupId = groupId))

    // --- slot -> forceSlot ---------------------------------------------------

    @Test
    fun lockRowIsPushedToTheLockScreenOnly() {
        // 显式传 LOCK：即使分组目标是 BOTH 也只写锁屏，桌面那张不会被顺手换掉。
        assertEquals(WallpaperTarget.SLOT_LOCK, recentForceSlot(WallpaperTarget.SLOT_LOCK))
    }

    @Test
    fun homeRowFollowsTheGroupTarget() {
        assertNull(recentForceSlot(WallpaperTarget.SLOT_HOME))
    }

    @Test
    fun anUnknownSlotNeverForcesAScreen() {
        // 库里理论上只会有 HOME/LOCK；一行脏数据不该让整页点错屏（或点不动）。
        assertNull(recentForceSlot("BOTH"))
        assertNull(recentForceSlot(""))
        assertNull(recentForceSlot("home"))
    }

    // --- 合并 / 去重 ---------------------------------------------------------

    @Test
    fun mergedHistoryKeepsOnlyTheNewestRowPerMedia() {
        val rows = listOf(
            row(id = 7, slot = WallpaperTarget.SLOT_LOCK, shownAt = now - 1_000L),
            row(id = 7, slot = WallpaperTarget.SLOT_HOME, shownAt = now - 5_000L),
            row(id = 9, slot = WallpaperTarget.SLOT_HOME, shownAt = now - 3_000L),
        )
        val merged = mergeRecentEntries(rows)

        // 同一张图两行 -> 只剩一行。
        assertEquals(listOf(7L, 9L), merged.map { it.image.id })
        // 留下的是**最近**那行：它决定推回写哪块屏。
        assertEquals(WallpaperTarget.SLOT_LOCK, merged.first().slot)
        assertEquals(now - 1_000L, merged.first().shownAt)
    }

    @Test
    fun mergingDoesNotDependOnTheInputOrder() {
        val rows = listOf(
            row(id = 1, slot = WallpaperTarget.SLOT_HOME, shownAt = now - 90_000L),
            row(id = 2, slot = WallpaperTarget.SLOT_LOCK, shownAt = now - 10_000L),
            row(id = 3, slot = WallpaperTarget.SLOT_HOME, shownAt = now - 50_000L),
        )
        assertEquals(listOf(2L, 3L, 1L), mergeRecentEntries(rows).map { it.image.id })
    }

    @Test
    fun mergedHistoryIsCapped() {
        val rows = (1L..5L).map {
            row(id = it, slot = WallpaperTarget.SLOT_HOME, shownAt = now - it * 1_000L)
        }
        assertEquals(
            listOf(1L, 2L, 3L),
            mergeRecentEntries(rows, keep = 3).map { it.image.id },
        )
    }

    @Test
    fun emptyInputAndNonPositiveKeepGiveAnEmptyList() {
        assertTrue(mergeRecentEntries(emptyList()).isEmpty())
        assertTrue(mergeRecentEntries(listOf(row(1, WallpaperTarget.SLOT_HOME, now)), keep = 0).isEmpty())
    }

    @Test
    fun fetchAsksForMoreRowsThanItShows() {
        // 去重会吃掉行（同一张图两行）：拉取必须比显示多，否则列表会被截短。
        assertTrue(RECENT_FETCH_LIMIT > RECENT_LIST_LIMIT)
    }

    // --- 推回后置顶 ----------------------------------------------------------

    @Test
    fun pushingAnItemMovesItToTheFrontAsJustNow() {
        val entries = listOf(
            row(1, WallpaperTarget.SLOT_HOME, now - 60_000L),
            row(2, WallpaperTarget.SLOT_LOCK, now - 120_000L),
            row(3, WallpaperTarget.SLOT_HOME, now - 180_000L),
        )
        val promoted = promoteRecentEntry(entries, mediaId = 3, nowMs = now)

        assertEquals(listOf(3L, 1L, 2L), promoted.map { it.image.id })
        assertEquals(entries.size, promoted.size)
        // 时间也要变成"刚刚"，否则置顶的那一行会写着"3 分钟前"，和自己排第一矛盾。
        assertEquals(now, promoted.first().shownAt)
        assertEquals(RecentAgoLabel(R.string.ago_just_now, 0L), recentAgoLabel(promoted.first().shownAt, now))
        // 其余项原样保留它自己的屏（forceSlot 不受置顶影响）。
        assertEquals(WallpaperTarget.SLOT_LOCK, promoted[2].slot)
    }

    @Test
    fun pushingAnItemKeepsItsScreen() {
        val entries = listOf(
            row(1, WallpaperTarget.SLOT_HOME, now - 60_000L),
            row(2, WallpaperTarget.SLOT_LOCK, now - 10_000L),
        )
        val promoted = promoteRecentEntry(entries, mediaId = 2, nowMs = now)
        assertEquals(WallpaperTarget.SLOT_LOCK, promoted.first().slot)
        assertEquals(WallpaperTarget.SLOT_LOCK, recentForceSlot(promoted.first().slot))
    }

    @Test
    fun promotingAnUnknownMediaChangesNothing() {
        val entries = listOf(row(1, WallpaperTarget.SLOT_HOME, now - 60_000L))
        assertEquals(entries, promoteRecentEntry(entries, mediaId = 42L, nowMs = now))
    }

    @Test
    fun promotingAnEmptyListChangesNothing() {
        assertTrue(promoteRecentEntry(emptyList(), mediaId = 1L, nowMs = now).isEmpty())
    }

    // --- 相对时间 ------------------------------------------------------------

    /** [elapsedMs] 毫秒之前显示的那一行，现在该显示成什么。 */
    private fun ago(elapsedMs: Long) = recentAgoLabel(now - elapsedMs, now)

    @Test
    fun theFirstMinuteIsJustNowButSixtySecondsIsOneMinute() {
        assertEquals(RecentAgoLabel(R.string.ago_just_now, 0L), ago(0L))
        assertEquals(RecentAgoLabel(R.string.ago_just_now, 0L), ago(59_000L))
        // 60 秒整是"刚刚"和"1 分钟前"的分界。
        assertEquals(RecentAgoLabel(R.string.ago_minutes, 1L), ago(60_000L))
    }

    @Test
    fun minutesHoursAndDays() {
        assertEquals(RecentAgoLabel(R.string.ago_minutes, 59L), ago(59 * 60_000L))
        assertEquals(RecentAgoLabel(R.string.ago_hours, 1L), ago(60 * 60_000L))
        assertEquals(RecentAgoLabel(R.string.ago_hours, 23L), ago(23 * 3_600_000L))
        assertEquals(RecentAgoLabel(R.string.ago_days, 1L), ago(24 * 3_600_000L))
        assertEquals(RecentAgoLabel(R.string.ago_days, 3L), ago(3 * 24 * 3_600_000L))
    }

    @Test
    fun aFutureTimestampNeverReadsAsANegativeAge() {
        // 时钟回拨 / 时区跳变（真机上真的会发生）：不能出现"-3 分钟前"。
        assertEquals(RecentAgoLabel(R.string.ago_just_now, 0L), ago(-60_000L))
        assertEquals(RecentAgoLabel(R.string.ago_just_now, 0L), ago(-10 * 24 * 3_600_000L))
    }

    @Test
    fun aMissingTimestampReadsAsNever() {
        assertEquals(RecentAgoLabel(R.string.ago_never, 0L), recentAgoLabel(0L, now))
    }
}
