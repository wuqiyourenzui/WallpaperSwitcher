package com.wallpaperswitcher.engine

import com.wallpaperswitcher.engine.GroupPacing.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPacingTest {

    private val now = 1_000_000_000L

    private fun group(
        id: Long,
        intervalMs: Long = 0L,
        lastSwitchAt: Long = 0L,
        activeNow: Boolean = true,
        hasMedia: Boolean = true,
    ) = Group(id, intervalMs, lastSwitchAt, activeNow, hasMedia)

    @Test
    fun groupWithNoIntervalFollowsTheGlobalOne() {
        assertEquals(60_000L, GroupPacing.effectiveIntervalMs(0L, 60_000L))
    }

    @Test
    fun groupWithItsOwnIntervalUsesIt() {
        assertEquals(10 * 60_000L, GroupPacing.effectiveIntervalMs(10 * 60_000L, 60_000L))
    }

    @Test
    fun effectiveIntervalIsClampedToTheMinimum() {
        assertEquals(SwitchSchedule.MIN_INTERVAL_MS, GroupPacing.effectiveIntervalMs(1_000L, 60_000L))
    }

    @Test
    fun groupThatNeverSwitchedIsDueImmediately() {
        val pick = GroupPacing.next(listOf(group(7, lastSwitchAt = 0L)), 60_000L, now)
        assertEquals(7L, pick?.groupId)
        assertEquals(0L, pick?.waitMs)
    }

    @Test
    fun groupThatIsNotYetDueWaitsTheRemainder() {
        val pick = GroupPacing.next(
            listOf(group(1, intervalMs = 60_000L, lastSwitchAt = now - 20_000L)),
            60_000L,
            now
        )
        assertEquals(40_000L, pick?.waitMs)
    }

    @Test
    fun overdueGroupIsDueNow() {
        val pick = GroupPacing.next(
            listOf(group(1, intervalMs = 60_000L, lastSwitchAt = now - 5 * 60_000L)),
            60_000L,
            now
        )
        assertEquals(0L, pick?.waitMs)
    }

    @Test
    fun earliestDueGroupWins() {
        val pick = GroupPacing.next(
            listOf(
                group(1, intervalMs = 60_000L, lastSwitchAt = now - 10_000L), // due in 50s
                group(2, intervalMs = 30_000L, lastSwitchAt = now - 25_000L), // due in 5s
                group(3, intervalMs = 60_000L, lastSwitchAt = now - 5_000L),  // due in 55s
            ),
            60_000L,
            now
        )
        assertEquals(2L, pick?.groupId)
        assertEquals(5_000L, pick?.waitMs)
    }

    @Test
    fun groupsFollowingTheGlobalIntervalStillCompete() {
        // Group 1 has its own 10s rhythm and just switched; group 2 follows the
        // 60s global interval and is nearly due, so it must win.
        val pick = GroupPacing.next(
            listOf(
                group(1, intervalMs = 10_000L, lastSwitchAt = now - 1_000L),
                group(2, intervalMs = 0L, lastSwitchAt = now - 55_000L),
            ),
            60_000L,
            now
        )
        assertEquals(2L, pick?.groupId)
    }

    @Test
    fun tiesResolveToTheSmallerGroupId() {
        val pick = GroupPacing.next(
            listOf(
                group(9, intervalMs = 60_000L, lastSwitchAt = now - 60_000L),
                group(3, intervalMs = 60_000L, lastSwitchAt = now - 60_000L),
            ),
            60_000L,
            now
        )
        assertEquals(3L, pick?.groupId)
    }

    @Test
    fun inactiveGroupIsNotACandidate() {
        val pick = GroupPacing.next(
            listOf(
                group(1, intervalMs = 60_000L, lastSwitchAt = 0L, activeNow = false),
                group(2, intervalMs = 60_000L, lastSwitchAt = now - 1_000L),
            ),
            60_000L,
            now
        )
        assertEquals(2L, pick?.groupId)
    }

    @Test
    fun mediaLessGroupIsNotACandidate() {
        val pick = GroupPacing.next(
            listOf(
                group(1, intervalMs = 60_000L, lastSwitchAt = 0L, hasMedia = false),
                group(2, intervalMs = 60_000L, lastSwitchAt = 0L),
            ),
            60_000L,
            now
        )
        assertEquals(2L, pick?.groupId)
    }

    @Test
    fun noCandidateReturnsNull() {
        assertNull(GroupPacing.next(listOf(group(1, hasMedia = false)), 60_000L, now))
        assertNull(GroupPacing.next(emptyList(), 60_000L, now))
    }

    @Test
    fun ancientAnchorMakesTheGroupDueNow() {
        val twoDays = 2L * 24 * 60 * 60 * 1000
        val pick = GroupPacing.next(
            listOf(group(1, intervalMs = 60 * 60_000L, lastSwitchAt = now - twoDays)),
            60_000L,
            now
        )
        assertEquals(0L, pick?.waitMs)
    }

    // --- 自定义（长）间隔：不能因为"看起来旧"就重新计时 ---

    @Test
    fun aLongIntervalIsNotStaleAfterADay() {
        // 7-day interval that switched 2 days ago: 5 days left, NOT "due now".
        // The fixed 24h staleness window used to re-anchor it, so the group
        // would keep restarting and never reach its own due time.
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        val twoDays = 2L * 24 * 60 * 60 * 1000
        val pick = GroupPacing.next(
            listOf(group(1, intervalMs = sevenDays, lastSwitchAt = now - twoDays)),
            60_000L,
            now
        )
        assertEquals(sevenDays - twoDays, pick?.waitMs)
    }

    @Test
    fun aMonthlyIntervalKeepsCounting() {
        val thirtyDays = 30L * 24 * 60 * 60 * 1000
        val tenDays = 10L * 24 * 60 * 60 * 1000
        val pick = GroupPacing.next(
            listOf(group(1, intervalMs = thirtyDays, lastSwitchAt = now - tenDays)),
            60_000L,
            now
        )
        assertEquals(20L * 24 * 60 * 60 * 1000, pick?.waitMs)
    }

    @Test
    fun staleWindowIsAtLeastADayAndGrowsWithTheInterval() {
        assertEquals(SwitchSchedule.MAX_CATCH_UP_AGE_MS, GroupPacing.staleAfterMs(60_000L))
        assertEquals(SwitchSchedule.MAX_CATCH_UP_AGE_MS, GroupPacing.staleAfterMs(6 * 60 * 60_000L))
        assertEquals(14L * 24 * 60 * 60 * 1000, GroupPacing.staleAfterMs(7L * 24 * 60 * 60 * 1000))
        // Absurd intervals must not overflow into a negative window.
        assertTrue(GroupPacing.staleAfterMs(Long.MAX_VALUE) > 0L)
    }

    @Test
    fun anAnchorOlderThanTwiceTheIntervalIsReanchored() {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        val twentyDays = 20L * 24 * 60 * 60 * 1000
        val pick = GroupPacing.next(
            listOf(group(1, intervalMs = sevenDays, lastSwitchAt = now - twentyDays)),
            60_000L,
            now
        )
        assertEquals(0L, pick?.waitMs)
    }

    @Test
    fun futureAnchorFromAClockChangeIsDueNow() {
        val pick = GroupPacing.next(
            listOf(group(1, intervalMs = 60_000L, lastSwitchAt = now + 30_000L)),
            60_000L,
            now
        )
        assertEquals(0L, pick?.waitMs)
    }
}
