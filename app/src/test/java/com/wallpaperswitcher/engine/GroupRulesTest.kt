package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.WallpaperGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRulesTest {

    private fun group(from: Int, to: Int) =
        WallpaperGroup(name = "g", activeFromMinute = from, activeToMinute = to)

    @Test
    fun allDayWindowIsOpenAnyTime() {
        assertTrue(GroupRules.windowContains(0, -1, -1))
        assertTrue(GroupRules.windowContains(12 * 60, -1, 5))
        assertTrue(GroupRules.windowContains(23 * 60, 8 * 60, -1))
    }

    @Test
    fun zeroLengthWindowMeansAllDay() {
        // from == to would otherwise lock the group out forever.
        assertTrue(GroupRules.windowContains(9 * 60, 8 * 60, 8 * 60))
    }

    @Test
    fun plainWindowContainsItsHours() {
        // 09:00 - 18:00
        assertTrue(GroupRules.windowContains(9 * 60, 9 * 60, 18 * 60))
        assertTrue(GroupRules.windowContains(12 * 60, 9 * 60, 18 * 60))
        assertFalse(GroupRules.windowContains(18 * 60, 9 * 60, 18 * 60))
        assertFalse(GroupRules.windowContains(8 * 60 + 59, 9 * 60, 18 * 60))
        assertFalse(GroupRules.windowContains(23 * 60, 9 * 60, 18 * 60))
    }

    @Test
    fun windowWrappingMidnightIsActiveOnBothSides() {
        // 22:00 - 06:00
        assertTrue(GroupRules.windowContains(22 * 60, 22 * 60, 6 * 60))
        assertTrue(GroupRules.windowContains(23 * 60 + 59, 22 * 60, 6 * 60))
        assertTrue(GroupRules.windowContains(0, 22 * 60, 6 * 60))
        assertTrue(GroupRules.windowContains(5 * 60 + 59, 22 * 60, 6 * 60))
        assertFalse(GroupRules.windowContains(6 * 60, 22 * 60, 6 * 60))
        assertFalse(GroupRules.windowContains(12 * 60, 22 * 60, 6 * 60))
    }

    @Test
    fun groupActiveAtUsesTheStoredWindow() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 30)
        val late = cal.timeInMillis
        assertTrue(GroupRules.isActiveAt(group(22 * 60, 6 * 60), late))
        cal.set(java.util.Calendar.HOUR_OF_DAY, 12)
        assertFalse(
            GroupRules.isActiveAt(group(22 * 60, 6 * 60), cal.timeInMillis)
        )
        assertTrue(GroupRules.isActiveAt(group(-1, -1), cal.timeInMillis))
    }

    @Test
    fun minuteOfDayParsesAndFormats() {
        assertEquals(22 * 60, GroupRules.parseMinuteOfDay("22:00"))
        assertEquals(0, GroupRules.parseMinuteOfDay("0:00"))
        assertEquals(6 * 60 + 5, GroupRules.parseMinuteOfDay("06:05"))
        assertNull(GroupRules.parseMinuteOfDay("24:00"))
        assertNull(GroupRules.parseMinuteOfDay("12:60"))
        assertNull(GroupRules.parseMinuteOfDay("noon"))
        assertNull(GroupRules.parseMinuteOfDay("12"))
        assertEquals("22:00", GroupRules.formatMinuteOfDay(22 * 60))
        assertNull(GroupRules.formatMinuteOfDay(-1))
    }

    // --- 每分组调度/取图的触发条件（分组自己的间隔或模式） ---

    @Test
    fun onlyAnOwnIntervalDrivesPerGroupPicking() {
        // 分组级的切换模式已取消：模式是全局设置，分组只带自己的间隔。
        // A stored mode (from an older build) is inert and must NOT drag the
        // group into per-group scheduling on its own.
        assertTrue(GroupRules.drivesOwnRhythm(WallpaperGroup(name = "g", intervalMs = 30_000L)))
        assertFalse(GroupRules.drivesOwnRhythm(WallpaperGroup(name = "g", switchMode = "SEQUENTIAL")))
        assertFalse(GroupRules.drivesOwnRhythm(WallpaperGroup(name = "g", switchMode = "SHUFFLE")))
        assertFalse(
            GroupRules.drivesOwnRhythm(
                WallpaperGroup(name = "g", intervalMs = 0L, switchMode = "RANDOM")
            )
        )
    }

    @Test
    fun aPlainGroupFollowsTheScreenWidePick() {
        assertFalse(GroupRules.drivesOwnRhythm(WallpaperGroup(name = "g")))
        assertFalse(
            GroupRules.drivesOwnRhythm(
                WallpaperGroup(name = "g", intervalMs = 0L, switchMode = "")
            )
        )
    }

    @Test
    fun aMediaFilterAlsoDrivesPerGroupPicking() {
        // 分组素材类型 (仅图片 / 仅视频) is a group-scoped rule: the timer and a
        // manual tap both resolve the group from drivesOwnRhythm, so a filtered
        // group must be scheduled through the per-group pick even when it
        // follows the global interval. Otherwise the tap fell back to the
        // screen-wide pool and could show media the filter excluded.
        assertTrue(
            GroupRules.drivesOwnRhythm(
                WallpaperGroup(name = "g", intervalMs = 0L, filterMode = GroupRules.MEDIA_IMAGE)
            )
        )
        assertTrue(
            GroupRules.drivesOwnRhythm(
                WallpaperGroup(name = "g", intervalMs = 0L, filterMode = GroupRules.MEDIA_VIDEO)
            )
        )
        // Normalisation: only the two known values are filters; a stale value
        // from a removed feature means 两者, so the group keeps its rotation.
        assertEquals(
            GroupRules.MEDIA_IMAGE,
            GroupRules.mediaFilter(WallpaperGroup(name = "g", filterMode = "IMAGE"))
        )
        assertEquals(
            GroupRules.MEDIA_VIDEO,
            GroupRules.mediaFilter(WallpaperGroup(name = "g", filterMode = "MOTION"))
        )
        assertEquals("", GroupRules.mediaFilter(WallpaperGroup(name = "g", filterMode = "FAVORITE")))
        assertEquals("", GroupRules.mediaFilter(WallpaperGroup(name = "g")))
    }

    @Test
    fun mediaFilterClassifiesImagesVideosAndGifs() {
        // 仅图片 must reject both videos and GIFs; 仅视频 must accept both
        // (GIF counts as a motion media), and "" accepts everything.
        assertTrue(GroupRules.allowsMedia(GroupRules.MEDIA_IMAGE, MediaTypes.IMAGE))
        assertFalse(GroupRules.allowsMedia(GroupRules.MEDIA_IMAGE, MediaTypes.VIDEO))
        assertFalse(GroupRules.allowsMedia(GroupRules.MEDIA_IMAGE, MediaTypes.GIF))
        assertTrue(GroupRules.allowsMedia(GroupRules.MEDIA_VIDEO, MediaTypes.VIDEO))
        assertTrue(GroupRules.allowsMedia(GroupRules.MEDIA_VIDEO, MediaTypes.GIF))
        assertFalse(GroupRules.allowsMedia(GroupRules.MEDIA_VIDEO, MediaTypes.IMAGE))
        assertTrue(GroupRules.allowsMedia("", MediaTypes.IMAGE))
        assertTrue(GroupRules.allowsMedia("", MediaTypes.VIDEO))
    }

    // --- 时间规则扩展: 星期 + 跟随深色模式 ---

    @Test
    fun everyDayMaskHasNoEffect() {
        // 0 (and the full mask) is what every existing row has: no day filter.
        assertTrue(GroupRules.dayAllowed(0, System.currentTimeMillis()))
        assertTrue(GroupRules.dayAllowed(GroupRules.ALL_DAYS, System.currentTimeMillis()))
    }

    @Test
    fun theWeekdayMaskUsesMondayAsBitZero() {
        val cal = java.util.Calendar.getInstance()
        // Walk a full week and check each day against its own bit.
        repeat(7) { offset ->
            cal.add(java.util.Calendar.DAY_OF_YEAR, if (offset == 0) 0 else 1)
            val index = GroupRules.weekdayIndex(cal.timeInMillis)
            assertTrue("day $index not allowed", GroupRules.dayAllowed(1 shl index, cal.timeInMillis))
            val other = (index + 1) % 7
            assertFalse(GroupRules.dayAllowed(1 shl other, cal.timeInMillis))
        }
    }

    @Test
    fun weekendOnlyRulesOutWeekdays() {
        val saturday = java.util.Calendar.getInstance().apply {
            // 2026-10-03 is a Saturday.
            set(2026, java.util.Calendar.OCTOBER, 3, 12, 0, 0)
        }
        val weekendMask = (1 shl 5) or (1 shl 6) // Saturday + Sunday
        assertTrue(GroupRules.dayAllowed(weekendMask, saturday.timeInMillis))
        val monday = saturday.clone() as java.util.Calendar
        monday.add(java.util.Calendar.DAY_OF_YEAR, 2)
        assertFalse(GroupRules.dayAllowed(weekendMask, monday.timeInMillis))
    }

    // --- 时间规则 on the SCREEN-WIDE tick (see WallpaperSwitchService) ---

    private fun at(hour: Int, minute: Int = 0): Long =
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis

    @Test
    fun onlyRealWindowsAndDayMasksCountAsTimeRules() {
        // The default row (all day, every day) must NOT be treated as restricted:
        // that is what keeps the screen-wide pick unchanged for every existing
        // install.
        assertFalse(GroupRules.hasTimeRules(WallpaperGroup(name = "g")))
        assertFalse(GroupRules.hasTimeRules(WallpaperGroup(name = "g", activeFromMinute = -1, activeToMinute = -1)))
        // A zero-length window is all day (see windowContains), so it is no rule.
        assertFalse(GroupRules.hasTimeRules(WallpaperGroup(name = "g", activeFromMinute = 8 * 60, activeToMinute = 8 * 60)))
        // Either bound alone still means "open" -> not a restriction.
        assertFalse(GroupRules.hasTimeRules(WallpaperGroup(name = "g", activeFromMinute = 8 * 60, activeToMinute = -1)))
        assertFalse(GroupRules.hasTimeRules(WallpaperGroup(name = "g", activeFromMinute = -1, activeToMinute = 18 * 60)))
        // A real window, and a real day mask, both count.
        assertTrue(GroupRules.hasTimeRules(WallpaperGroup(name = "g", activeFromMinute = 22 * 60, activeToMinute = 6 * 60)))
        assertTrue(GroupRules.hasTimeRules(WallpaperGroup(name = "g", activeDays = 1 shl 0)))
        // The full mask is "every day" -> not a restriction.
        assertFalse(GroupRules.hasTimeRules(WallpaperGroup(name = "g", activeDays = GroupRules.ALL_DAYS)))
    }

    @Test
    fun screenTickCandidatesDropGroupsOutsideTheirWindow() {
        val night = WallpaperGroup(id = 1L, name = "night", activeFromMinute = 22 * 60, activeToMinute = 6 * 60)
        val plain = WallpaperGroup(id = 2L, name = "plain")
        val counts = mapOf(1L to 3, 2L to 5)
        val slot = WallpaperTarget.SLOT_HOME

        // 23:30: both may be shown.
        assertEquals(
            listOf(1L, 2L),
            GroupRules.screenTickCandidates(listOf(night, plain), counts, slot, at(23, 30)).map { it.id }
        )
        // 12:00: the night group is out, so the tick must not be scoped to it.
        assertEquals(
            listOf(2L),
            GroupRules.screenTickCandidates(listOf(night, plain), counts, slot, at(12)).map { it.id }
        )
        // 12:00 with ONLY the night group: nothing may switch -> the loop idles.
        assertTrue(GroupRules.screenTickCandidates(listOf(night), counts, slot, at(12)).isEmpty())
    }

    @Test
    fun screenTickCandidatesRequireMediaForThisSlotAndTarget() {
        val homeOnly = WallpaperGroup(id = 1L, name = "home", target = "HOME")
        val lockOnly = WallpaperGroup(id = 2L, name = "lock", target = "LOCK")
        val mediaLess = WallpaperGroup(id = 3L, name = "empty")
        val groups = listOf(homeOnly, lockOnly, mediaLess)
        val counts = mapOf(1L to 2, 2L to 2, 3L to 0)
        val home = GroupRules.screenTickCandidates(
            groups, counts, WallpaperTarget.SLOT_HOME, at(12)
        ).map { it.id }
        val lock = GroupRules.screenTickCandidates(
            groups, counts, WallpaperTarget.SLOT_LOCK, at(12)
        ).map { it.id }
        assertEquals(listOf(1L), home)
        assertEquals(listOf(2L), lock)
    }
}
