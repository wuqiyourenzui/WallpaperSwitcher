package com.wallpaperswitcher.engine

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.getString

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
        // sortedBy(id): the "never switched" rows all carry lastSwitchAt = 0,
        // so the tie-break must not depend on SQLite's row order.
        val eligible = groups.filter {
            (counts[it.id] ?: 0) > 0 && GroupRules.isActiveAt(it, nowMs)
        }.sortedBy { it.id }
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
     * 顺序模式（SEQUENTIAL）的下一跳: **当前正在显示的那张图所在分组**
     * ([sequentialStartGroupId], the same walker the manual triggers and
     * 下一张预览 use), paced by that group's own clock.
     *
     * This used to be [next] (the "等得最久的分组先上" fairness rotation), which
     * made the timed switch hop between groups (A1→B1→A2→B2) instead of walking
     * the current one to its end, and disagree with 下一张预览 / manual taps -
     * both of which start from the current image's group.
     *
     * @return null when the current group cannot be resolved (no current media /
     *   deleted / disabled / outside its 时间规则); the caller then falls back to
     *   the screen-wide sequential pick, exactly like 下一张预览 does.
     */
    suspend fun sequential(
        db: AppDatabase,
        slot: String,
        globalIntervalMs: Long,
        screenAnchor: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Plan? {
        val groupId = try {
            sequentialStartGroupId(db, slot, nowMs)
        } catch (_: Exception) {
            0L
        }
        if (groupId <= 0L) return null
        val group = try {
            db.wallpaperGroupDao().getGroupById(groupId)
        } catch (_: Exception) {
            null
        } ?: return null
        return planForGroup(db, slot, group, globalIntervalMs, screenAnchor, nowMs)
    }

    /**
     * The tick plan of one specific group: its own interval when it has one,
     * otherwise the shared screen clock - with one guard: a group that switched
     * more recently than the screen anchor (the engine can advance INTO a group
     * mid-tick and writes only that group's row) anchors its next tick on that
     * more recent value, so the new group is not switched twice in a row.
     */
    private suspend fun planForGroup(
        db: AppDatabase,
        slot: String,
        group: WallpaperGroup,
        globalIntervalMs: Long,
        screenAnchor: Long,
        nowMs: Long,
    ): Plan {
        val raw = try {
            db.groupScheduleDao().get(group.id, slot)?.lastSwitchAt ?: 0L
        } catch (_: Exception) {
            0L
        }
        if (group.intervalMs <= 0L) {
            val anchor = if (raw > screenAnchor) raw else screenAnchor
            return Plan(
                groupId = group.id,
                waitMs = SwitchSchedule.waitMs(anchor, globalIntervalMs, nowMs),
                usesScreenClock = true,
                anchor = anchor,
                intervalMs = globalIntervalMs,
                groupRawAnchor = raw,
            )
        }
        val dueAt = GroupPacing.dueAt(
            GroupPacing.Group(
                groupId = group.id,
                intervalMs = group.intervalMs,
                lastSwitchAt = raw,
            ),
            globalIntervalMs,
            nowMs,
        )
        return Plan(
            groupId = group.id,
            waitMs = (dueAt - nowMs).coerceAtLeast(0L),
            usesScreenClock = false,
            anchor = if (raw <= 0L) nowMs else raw,
            intervalMs = GroupPacing.effectiveIntervalMs(group.intervalMs, globalIntervalMs),
            groupRawAnchor = raw,
        )
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
     * 顺序模式：**当前这一组走完之后**该进哪一组 —— 按**分组列表顺序（id 升序）**
     * 取下一个启用分组（用户明确要求）。找不到更大的就回到**最小 id** 的那组，
     * 形成闭环，而不是绕回本组第一张。
     *
     * 只考虑"这块屏真的能显示内容 + 时间规则允许"的分组，否则会卡在一个空组上。
     *
     * @param afterGroupId 刚走完的那一组
     * @return 下一个分组 id；没有任何可用分组时返回 0（调用方回落到屏幕级挑选）
     */
    suspend fun nextGroupAfter(
        db: AppDatabase,
        slot: String,
        afterGroupId: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val groups = try {
            db.wallpaperGroupDao().getEnabledGroupsSync()
                .filter { WallpaperTarget.fromName(it.target).suitsSlot(slot) }
                .sortedBy { it.id }
        } catch (_: Exception) {
            return 0L
        }
        if (groups.isEmpty()) return 0L
        // 只保留当前真的能切的组（有媒体 + 时间规则允许）。
        val usable = groups.filter { group ->
            if (!GroupRules.isActiveAt(group, nowMs)) return@filter false
            val count = try {
                db.groupPickDao().countInGroup(slot, group.id, GroupRules.mediaFilter(group))
            } catch (_: Exception) {
                0
            }
            count > 0
        }
        if (usable.isEmpty()) return 0L
        // id 升序的下一个；没有更大的就回到最小 id（闭环）。
        return usable.firstOrNull { it.id > afterGroupId }?.id ?: usable.first().id
    }

    /**
     * 顺序模式（SEQUENTIAL）下这一跳从**哪个分组**开始：**当前正在显示的那张图
     * 所在的分组**（用户明确要求「从这张图片所在分组开始顺序，顺序完后下一个分组
     * 再顺序」）。
     *
     * 不能复用 [nextGroupId]：那个挑的是「等得最久」的分组（上次切换最早），
     * 于是刚切过的那一组反而最先被跳过 —— 表现在外就是"顺序模式不接着当前这张
     * 往下走，而是跳到别的分组"。当前图的 `groupId` 才是唯一正确的起点。
     *
     * @return 当前图所属的**启用**分组；当前图不存在 / 其分组已停用 / 该分组在这块屏
     *   没有媒体时返回 0（调用方回落到屏幕级挑选）。
     */
    suspend fun sequentialStartGroupId(
        db: AppDatabase,
        slot: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val currentId = try {
            db.settingsDao().getLong(
                if (slot == WallpaperTarget.SLOT_LOCK) SettingsKeys.LAST_IMAGE_ID_LOCK
                else SettingsKeys.LAST_IMAGE_ID
            )
        } catch (_: Exception) {
            0L
        }
        if (currentId <= 0L) return 0L
        val image = try {
            db.wallpaperImageDao().getImageById(currentId)
        } catch (_: Exception) {
            null
        } ?: return 0L
        val group = try {
            db.wallpaperGroupDao().getGroupById(image.groupId)
        } catch (_: Exception) {
            null
        } ?: return 0L
        if (!group.isEnabled) return 0L
        if (!WallpaperTarget.fromName(group.target).suitsSlot(slot)) return 0L
        // 该分组在这块屏真的能显示内容，且当前时间规则允许 —— 否则这一跳交给
        // 屏幕级挑选，免得卡在一个空分组上。
        val count = try {
            db.groupPickDao().countInGroup(slot, group.id, GroupRules.mediaFilter(group))
        } catch (_: Exception) {
            0
        }
        if (count <= 0) return 0L
        if (!GroupRules.isActiveAt(group, nowMs)) return 0L
        return group.id
    }

    /**
     * The group the next switch of [slot] belongs to, or 0 when the screen-wide
     * pick applies.
     *
     * 顺序模式 returns the current image's group ([sequentialStartGroupId]); 0
     * (current group unresolvable) means "fall back to the screen-wide
     * sequential pick", exactly like the timer tick's fallback. 随机 / 洗牌
     * callers keep their own screen-wide rule (see
     * LiveWallpaperService.resolveUserTapGroup); the 解锁 switch path still
     * resolves a group here as before.
     *
     * Used by the *manual* triggers - 悬浮按钮 / 双击 / 立即切换 / 解锁切换 - so a
     * tap follows exactly the same group rhythm as the timer tick and
     * 下一张预览.
     */
    suspend fun nextGroupId(
        db: AppDatabase,
        slot: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        // 顺序模式：从当前图片所在分组开始（见 sequentialStartGroupId）。
        // 当前组不可解析时返回 0，让调用方回落到**屏幕级顺序取图**
        // (MediaPick.sequentialAcrossGroups) —— 与定时 tick 的兜底完全一致。
        // 顺序模式**不**按「等得最久」轮转（那会让顺序切换跳组），所以这里
        // 不落到下面的 longestWaitingGroupId 分支。
        val mode = try {
            com.wallpaperswitcher.data.SwitchMode.valueOf(
                db.settingsDao().getString(
                    SettingsKeys.GLOBAL_SWITCH_MODE,
                    com.wallpaperswitcher.data.SwitchMode.RANDOM.name,
                )
            )
        } catch (_: Exception) {
            com.wallpaperswitcher.data.SwitchMode.RANDOM
        }
        if (mode == com.wallpaperswitcher.data.SwitchMode.SEQUENTIAL) {
            return try {
                sequentialStartGroupId(db, slot, nowMs)
            } catch (_: Exception) {
                0L
            }
        }
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
