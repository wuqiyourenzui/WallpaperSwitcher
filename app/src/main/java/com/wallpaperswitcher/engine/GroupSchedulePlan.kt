package com.wallpaperswitcher.engine

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.data.getLong

/**
 * "Which group switches next, and when" for one screen.
 *
 * Two clocks coexist once any group opts out of the screen-wide defaults:
 *
 *  - groups with their OWN interval ([WallpaperGroup.intervalMs] > 0) run on
 *    their own clock: due at `lastSwitchAt + interval`;
 *  - groups that follow the global interval (and/or only carry their own switch
 *    mode) share the SCREEN clock: one of them is due exactly when the screen
 *    anchor is due, and whichever is chosen advances that anchor. Giving each of
 *    them its own clock instead multiplied the switch rate by the number of
 *    groups (12 groups on a 10s interval switched the wallpaper every ~1s);
 *    sharing the anchor keeps the screen's original pace.
 *
 * The chosen group is returned with everything the caller needs to claim,
 * restore and pick: [usesScreenClock] tells whether the SCREEN anchor (instead
 * of the group's row) is what moves when the tick fires, and [lastMediaId]
 * cursor handling stays per group either way.
 *
 * Shared by the switch service and 下一张预览 so both always agree.
 */
internal object GroupSchedulePlan {

    data class Plan(
        val groupId: Long,
        /** Milliseconds until this tick is due (0 = now). */
        val waitMs: Long,
        /** True when this group's rhythm is the shared screen clock. */
        val usesScreenClock: Boolean,
        /** The timing anchor: the screen anchor, or the group's own. */
        val anchor: Long,
        /** Effective interval matched to [anchor]. */
        val intervalMs: Long,
        /** The group's own stored anchor before any claim (for the restore path). */
        val groupRawAnchor: Long,
    )

    /**
     * @param screenAnchor the screen's already-resolved anchor
     *   ([SwitchSchedule.resolveAnchor]); the caller owns persisting it.
     * @return null when no group can switch right now (no media / outside its
     *   时间规则 window).
     */
    suspend fun next(
        db: AppDatabase,
        slot: String,
        groups: List<WallpaperGroup>,
        globalIntervalMs: Long,
        screenAnchor: Long,
        nowMs: Long,
    ): Plan? {
        val counts = try {
            db.groupPickDao().countsForSlot(slot).associate { it.groupId to it.mediaCount }
        } catch (_: Exception) {
            emptyMap()
        }
        val rows = try {
            db.groupScheduleDao().getAllForSlot(slot).associateBy { it.groupId }
        } catch (_: Exception) {
            emptyMap()
        }
        val eligible = groups.filter {
            (counts[it.id] ?: 0) > 0 && GroupRules.isActiveAt(it, nowMs)
        }
        if (eligible.isEmpty()) return null

        // Groups on the global interval: one shared clock. The one that waited
        // longest (its own last switch is the oldest) goes first, so they take
        // turns instead of one group monopolising the rotation.
        val globalWait = SwitchSchedule.waitMs(screenAnchor, globalIntervalMs, nowMs)
        var best: Plan? = null
        eligible.filter { it.intervalMs <= 0L }
            .minByOrNull { rows[it.id]?.lastSwitchAt ?: 0L }
            ?.let { group ->
                best = Plan(
                    groupId = group.id,
                    waitMs = globalWait,
                    usesScreenClock = true,
                    anchor = screenAnchor,
                    intervalMs = globalIntervalMs,
                    groupRawAnchor = rows[group.id]?.lastSwitchAt ?: 0L,
                )
            }

        // Groups with their own interval run on their own clock.
        val ownPick = GroupPacing.next(
            eligible.filter { it.intervalMs > 0L }.map { group ->
                GroupPacing.Group(
                    groupId = group.id,
                    intervalMs = group.intervalMs,
                    lastSwitchAt = rows[group.id]?.lastSwitchAt ?: 0L,
                )
            },
            globalIntervalMs,
            nowMs,
        )
        if (ownPick != null) {
            val ownInterval = groups.firstOrNull { it.id == ownPick.groupId }?.intervalMs ?: 0L
            val raw = rows[ownPick.groupId]?.lastSwitchAt ?: 0L
            val plan = Plan(
                groupId = ownPick.groupId,
                waitMs = ownPick.waitMs,
                usesScreenClock = false,
                anchor = if (raw <= 0L) nowMs else raw,
                intervalMs = GroupPacing.effectiveIntervalMs(ownInterval, globalIntervalMs),
                groupRawAnchor = raw,
            )
            val current = best
            if (current == null || plan.waitMs < current.waitMs ||
                (plan.waitMs == current.waitMs && plan.groupId < current.groupId)
            ) {
                best = plan
            }
        }
        return best
    }

    /**
     * 这次轮到哪个分组：所有分组共用全局时钟时，挑「上次切换最早」的那个。
     *
     * 用分组自己的 `group_schedule.lastSwitchAt`（每次该分组真的显示了内容就更新），
     * 所以轮转是公平的：谁最久没露面就谁上，不会出现某个分组连着上好几次。
     * 从没显示过的分组时间戳是 0 → 最先被选中。
     */
    private suspend fun longestWaitingGroupId(
        db: AppDatabase,
        slot: String,
        groups: List<com.wallpaperswitcher.data.WallpaperGroup>,
        nowMs: Long,
    ): Long {
        val counts = try {
            db.groupPickDao().countsForSlot(slot).associate { it.groupId to it.mediaCount }
        } catch (_: Exception) {
            emptyMap()
        }
        val rows = try {
            db.groupScheduleDao().getAllForSlot(slot).associateBy { it.groupId }
        } catch (_: Exception) {
            emptyMap()
        }
        // 只考虑"这个屏真的能显示内容"的分组，且当前时段/星期允许。
        val eligible = groups.filter {
            (counts[it.id] ?: 0) > 0 && GroupRules.isActiveAt(it, nowMs)
        }
        if (eligible.isEmpty()) return 0L
        return eligible.minWithOrNull(
            compareBy({ rows[it.id]?.lastSwitchAt ?: 0L }, { it.id })
        )?.id ?: 0L
    }

    /**
     * The group the next switch of [slot] belongs to, or 0 when the screen-wide
     * pick applies (no group drives its own rhythm).
     *
     * Used by the *manual* triggers - 悬浮按钮 / 双击 / 立即切换 / 解锁切换 - so a
     * tap follows exactly the same group rhythm as the timer tick and
     * 下一张预览. Without it those paths always took the screen-wide pick, i.e.
     * the GLOBAL mode, and a group set to 顺序 kept being switched in the
     * global random/shuffle order.
     */
    suspend fun nextGroupId(
        db: AppDatabase,
        slot: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val groups = try {
            db.wallpaperGroupDao().getEnabledGroupsSync()
                .filter { WallpaperTarget.fromName(it.target).suitsSlot(slot) }
        } catch (_: Exception) {
            return 0L
        }
        // 分组自己的间隔设置已取消（统一由「设置 → 定时切换」调度）：没有哪个分组
        // 自带节奏时，也**必须**由这里挑出这次轮到哪个分组 —— 否则所有分组都会
        // 走屏幕级挑选，某个分组可能被反复选中、另一个永远轮不到。
        // 规则与全局时钟那一路一致：等得最久（上次切换最早）的那个先上。
        if (groups.none { GroupRules.drivesOwnRhythm(it) }) {
            return longestWaitingGroupId(db, slot, groups, nowMs)
        }
        val lockSlot = slot == WallpaperTarget.SLOT_LOCK
        val globalInterval = try {
            db.settingsDao().getLong(
                if (lockSlot) SettingsKeys.LOCK_INTERVAL_MS else SettingsKeys.GLOBAL_INTERVAL_MS,
                60_000L
            )
        } catch (_: Exception) {
            60_000L
        }
        val anchor = try {
            SwitchSchedule.resolveAnchor(
                db.settingsDao().getLong(
                    if (lockSlot) SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS
                    else SettingsKeys.TIMER_LAST_SWITCH_WALL_MS,
                    0L
                ),
                nowMs,
                GroupPacing.staleAfterMs(globalInterval)
            )
        } catch (_: Exception) {
            nowMs
        }
        return next(db, slot, groups, globalInterval, anchor, nowMs)?.groupId ?: 0L
    }

    /** [nextGroupId] for the home screen, for the callers that only have a Context. */
    suspend fun nextHomeGroupId(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Long = nextGroupId(
        AppDatabase.getInstance(context), WallpaperTarget.SLOT_HOME, nowMs
    )
}
