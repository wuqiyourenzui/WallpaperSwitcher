package com.wallpaperswitcher.engine

/**
 * Per-group switch pacing: which group is due next when groups carry their own
 * interval (see [com.wallpaperswitcher.data.WallpaperGroup.intervalMs]).
 *
 * The default install has every group on `intervalMs = 0` ("跟随全局"), and then
 * the service never calls this: the screen-wide anchor schedules the switch
 * exactly as it always did. As soon as ONE group opts into its own interval the
 * timer becomes a small scheduler:
 *
 *  - every group has an effective interval: its own, or the screen's global one
 *    while it is 0;
 *  - a group is due at `lastSwitchAt(group, slot) + effectiveInterval`; a group
 *    that never switched on this screen (or whose record is implausibly old) is
 *    due immediately;
 *  - the next tick belongs to the group that is due first (ties resolve to the
 *    smaller group id, so the choice is deterministic);
 *  - a group that cannot switch right now - media-less for this screen, or
 *    outside its active time window - is not a candidate at all.
 *
 * Pure maths on purpose: no Android, no DAO, so the rule is unit-testable.
 */
object GroupPacing {

    /** One group's scheduling input for a single screen slot. */
    data class Group(
        val groupId: Long,
        /** The group's own interval, or 0 to follow [globalIntervalMs]. */
        val intervalMs: Long,
        /** Wall-clock ms this group last provided a switch for the slot (0 = never). */
        val lastSwitchAt: Long,
        /** False while the group's 时间规则 window excludes "now". */
        val activeNow: Boolean = true,
        /** False when the group has no media this screen could show. */
        val hasMedia: Boolean = true,
    )

    /** The winning group and when its tick is due. [waitMs] is 0 when overdue. */
    data class Pick(val groupId: Long, val dueAt: Long, val waitMs: Long)

    /** A group's effective interval: its own, or the global one while it has none. */
    fun effectiveIntervalMs(ownIntervalMs: Long, globalIntervalMs: Long): Long =
        (if (ownIntervalMs > 0L) ownIntervalMs else globalIntervalMs)
            .coerceAtLeast(SwitchSchedule.MIN_INTERVAL_MS)

    /**
     * The next group to switch for one screen, or null when no group can
     * produce a switch right now (every group is media-less or outside its
     * active window).
     */
    fun next(groups: List<Group>, globalIntervalMs: Long, nowMs: Long): Pick? {
        var best: Pick? = null
        for (group in groups) {
            if (!group.activeNow || !group.hasMedia) continue
            val dueAt = dueAt(group, globalIntervalMs, nowMs)
            if (best == null || dueAt < best.dueAt ||
                (dueAt == best.dueAt && group.groupId < best.groupId)
            ) {
                best = Pick(group.groupId, dueAt, (dueAt - nowMs).coerceAtLeast(0L))
            }
        }
        return best
    }

    /**
     * When [group] is next due. A missing (<= 0) or implausibly old anchor
     * counts as "due now" - the group has effectively never switched, so
     * waiting a whole interval first would leave it out of the rotation for
     * that long after the user just enabled its own rhythm.
     *
     * "Implausibly old" scales with the interval ([staleAfterMs]): the fixed
     * 24h window was fine while every interval fit in a day, but a group set to
     * 7 days would be re-anchored to "now" after 24h and then never reach its
     * own due time - it would switch roughly daily instead of weekly.
     */
    fun dueAt(group: Group, globalIntervalMs: Long, nowMs: Long): Long {
        val interval = effectiveIntervalMs(group.intervalMs, globalIntervalMs)
        if (group.lastSwitchAt <= 0L) return nowMs
        val age = nowMs - group.lastSwitchAt
        if (age < 0L || age > staleAfterMs(interval)) return nowMs
        return group.lastSwitchAt + interval
    }

    /**
     * Age after which a stored anchor is treated as "this never really
     * switched": at least [SwitchSchedule.MAX_CATCH_UP_AGE_MS] (a device that
     * was off for a day must not fire a backlog), but never less than two full
     * intervals, so a 3-day or 30-day interval keeps its schedule intact.
     */
    fun staleAfterMs(intervalMs: Long): Long = maxOf(
        SwitchSchedule.MAX_CATCH_UP_AGE_MS,
        intervalMs.coerceAtMost(Long.MAX_VALUE / 2) * 2
    )
}
