package com.wallpaperswitcher.engine

/**
 * Small pure helpers shared by the live engine and the static applier so the
 * RANDOM / SEQUENTIAL / SHUFFLE switch modes behave identically on both paths.
 */
object SwitchPicking {

    /** 视频播完再切: 定时 tick 撞上视频时只能二选一。 */
    enum class TimerWithVideo {
        /** 照常切换（选项关着，或屏幕上不是视频，或是兜底放行）。 */
        SWITCH_NOW,
        /** 丢掉这次 tick：视频的切换交给播完计数。 */
        DROP,
    }

    /**
     * 视频播完再切: 定时 tick 撞上视频时怎么办。
     *
     * 规则（用户要求）：选项开着时**视频的切换只由"播完 N 次"决定**，定时切换
     * 不得插手——不是"等这一段播完再切"（那会让视频只播一遍就被换掉，次数
     * 设置等于失效），而是整次 tick 丢掉；切到图片之后，图片照常按定时切。
     *
     * [consecutiveDrops] 是连续被丢掉的 tick 数，每完成一个 pass 由引擎清零。
     * 超过 [dropLimit] 时放行一次：视频起不来/一直播不完时不能把定时切换
     * 永久挡住（旧实现里"hold 泄漏"正是这么卡死的）。
     *
     * 手动 / 解锁 / 双击 / 悬浮按钮切换从不调用这里，始终立即生效。
     */
    fun timerWithVideo(
        optionEnabled: Boolean,
        videoActive: Boolean,
        consecutiveDrops: Int,
        dropLimit: Int,
    ): TimerWithVideo = when {
        !optionEnabled -> TimerWithVideo.SWITCH_NOW
        !videoActive -> TimerWithVideo.SWITCH_NOW
        consecutiveDrops >= dropLimit.coerceAtLeast(1) -> TimerWithVideo.SWITCH_NOW
        else -> TimerWithVideo.DROP
    }

    /**
     * 视频播完再切（与定时切换解耦）: 这一段播完之后要不要换一张。
     *
     * 与 [timerWithVideo] 的分工：那个只管「定时 tick 要不要丢掉」，
     * 这个管「视频自己播完就切」——所以**不看定时开关**，只要求选项开着且
     * 这一段已经播够 [repeats] 遍。手动/解锁/双击等其它切换方式不经过这里，
     * 依旧立即生效。
     *
     * @param passesThisClip 当前这段视频已经完整播完的次数（不含本次）。
     */
    fun videoEndSwitchDue(
        optionEnabled: Boolean,
        repeats: Int,
        passesThisClip: Int,
    ): Boolean {
        if (!optionEnabled) return false
        // 次数由用户自定义，没有上限：只把「0 / 负数」夹回 1（那等于每段都切）。
        val target = repeats.coerceAtLeast(1)
        return passesThisClip + 1 >= target
    }

    /**
     * Whether the shuffle pass is over and a new one may start: every enabled
     * media has already been shown.
     *
     * A change of the enabled-set SIZE must NOT reset the pass any more: importing
     * a folder or toggling a group used to clear the "already shown" set, so media
     * that had been shown were dealt again before the pass finished (the tablet
     * log had four such count changes within five minutes). The deck itself is
     * rebuilt whenever the size changes - from the enabled ids *minus* the shown
     * ones - so a growing or shrinking set continues the same pass.
     *
     * Kept pure so both callers use exactly the same rule.
     */
    fun shouldResetShuffleDeck(shownSize: Int, totalCount: Int): Boolean =
        shownSize >= totalCount

    /**
     * 顺序模式「本组走完」判据: the sequential walk fell back to the group's edge
     * (its first media when ascending, its newest when descending), i.e. the
     * picked id did not advance strictly past the cursor. Only meaningful for
     * cursor > 0 (a group that never switched has no "walk" to exhaust).
     *
     * The comparison must follow the walk direction: the old `picked.id <=
     * cursor` test only held for id-ascending groups, so a NEWEST group would
     * look "exhausted" on its second media and jump to the next group.
     */
    fun sequentialWrapped(
        pickedId: Long,
        cursorId: Long,
        newestFirst: Boolean,
    ): Boolean = if (newestFirst) pickedId >= cursorId else pickedId <= cursorId

    /**
     * 顺序模式的参考点该用哪个 id：**接着屏幕上那张往下走**。
     *
     * 分组游标 `group_schedule.lastMediaId` 只在**分组级 tick**里推进 —— 也就是顺序
     * 模式。随机 / 洗牌的 tick 是屏幕级（`groupId = 0`，见
     * `WallpaperSwitchService.nextScreenTick`），期间游标原地不动。于是从随机 / 洗牌
     * 切回顺序时，游标还停在**上一次顺序模式的旧位置**：取到的是那张的下一张，
     * 而不是屏幕上这张的下一张（用户反馈：切换后应该接当前这张）。
     *
     * 屏幕上的那张属于这次要走的分组时，以它为准 —— 无论游标落后多少，顺序都从
     * 眼睛看到的那张继续（自愈）。不属于时保留游标：本组已经走完、推进到下一个分组，
     * 或者屏幕上那张根本是别组的图，这时游标才是唯一有意义的参考。
     *
     * @param displayedId 屏幕上正在显示的那张 id（0 = 未知 / 没有）
     * @param displayedGroupId 那张图所属的分组（[displayedId] 查不到时为 0）
     * @param walkGroupId 这一次要走的分组
     * @param cursor 分组游标（`group_schedule.lastMediaId`）
     */
    fun sequentialAnchor(
        displayedId: Long,
        displayedGroupId: Long,
        walkGroupId: Long,
        cursor: Long,
    ): Long =
        if (displayedId > 0L && walkGroupId > 0L && displayedGroupId == walkGroupId) {
            displayedId
        } else {
            cursor
        }

    /**
     * 64-bit mix (splitmix64 finalizer), then modulo: a **deterministic**
     * "random-looking" index in `0 until count`.
     *
     * 下一张预览 must show the media the next switch will really display, and
     * clicking it twice must not re-roll. True `Random.nextInt()` cannot do
     * that: every call (preview + switch) rolled again, so RANDOM/SHUFFLE
     * previews disagreed with the switch and with themselves. Deriving the
     * index from the picking state (the cursor / the deck) instead makes the
     * pick reproducible: preview and switch compute the same value, and the
     * next switch computes a different one because its state moved on
     * ([pickSeed]).
     */
    fun stableIndex(count: Int, seed: Long): Int {
        require(count > 0) { "count must be > 0" }
        if (count == 1) return 0
        // A seeded generator: the same seed always yields the same index, which
        // is what keeps the preview and the switch in agreement.
        return kotlin.random.Random(seed).nextInt(count)
    }

    /**
     * Seed for one pick. The parts are the picking state that both the preview
     * and the switch can see: the cursor, the deck size, the size of the media
     * set and [seq] - the counter of APPLIED switches
     * ([com.wallpaperswitcher.data.SettingsKeys.PICK_SEQ]).
     *
     * Same state -> same media, so the preview names the media the next switch
     * will really show. [seq] is what keeps the walk from settling into a short
     * cycle (the cursor alone is a fixed function, and a random mapping on N
     * nodes repeats after ~0.6*sqrt(N) steps) - it only moves once a switch has
     * been applied, so it never breaks the preview/switch agreement.
     */
    fun pickSeed(cursor: Long, deckSize: Int, universeSize: Int, seq: Long = 0L): Long =
        cursor + 31L * deckSize + 1_000_003L * universeSize + 2_654_435_761L * seq

    /**
     * Offset for the "random pick by OFFSET" DAO query, derived from [seed]
     * (see [stableIndex]). Both callers guarantee [count] > 1 (single-item sets
     * are handled before this is invoked). The query EXCLUDES the currently
     * shown media (id != lastId), so the filtered set has count-1 rows and a
     * valid offset is in [0, count-2] — never landing past the last row, which
     * would have forced the slower ORDER BY RANDOM fallback.
     */
    fun randomOffset(count: Int, seed: Long): Int {
        require(count > 1) { "count must be > 1 (single-item sets are handled by the caller)" }
        return stableIndex(count - 1, seed)
    }

    /**
     * SHUFFLE deck pick: the id of a random media that has not been shown yet in
     * the current pass (and is not the media currently on screen).
     *
     * Restored to the pre-review implementation on request: the caller passes the
     * slot's whole enabled id list (one id-only query per switch) and the unseen
     * set is filtered here in memory. The in-memory filter (instead of an
     * `id NOT IN (...)` list) is what keeps a group with more media than SQLite
     * allows bound variables (999 on older builds) from failing the whole switch.
     *
     * @param enabledIds every id this screen may show (see the DAO's
     *   `getEnabledIds`).
     * @param shownIds ids already displayed in the current pass. Pass a Set for
     *   O(1) lookups; any other Collection still works.
     * @param excludeId the media currently on screen, never re-picked right away.
     * @return a random unseen id, or null when nothing is left to show (the
     *   caller then starts a new pass), or the screen has no media at all.
     */
    fun pickUnseen(
        enabledIds: List<Long>,
        shownIds: Collection<Long>,
        excludeId: Long,
        seed: Long = 0L,
        /** 收藏优先: ids that draw [favoriteWeight]× as often. */
        favoriteIds: Set<Long> = emptySet(),
        favoriteWeight: Int = 1,
    ): Long? {
        if (enabledIds.isEmpty()) return null
        val candidates = enabledIds.filter { it != excludeId && it !in shownIds }
        if (candidates.isEmpty()) return null
        if (favoriteIds.isEmpty() || favoriteWeight <= 1) {
            return candidates[stableIndex(candidates.size, seed)]
        }
        // 收藏优先: weighted draw. Deterministic for a given seed, so the preview
        // and the switch that follows it still agree.
        var total = 0L
        for (id in candidates) total += if (id in favoriteIds) favoriteWeight else 1
        if (total <= 0L) return candidates[stableIndex(candidates.size, seed)]
        var draw = kotlin.random.Random(seed).nextLong(total)
        for (id in candidates) {
            val weight = if (id in favoriteIds) favoriteWeight else 1
            if (draw < weight) return id
            draw -= weight
        }
        return candidates.last()
    }

    /**
     * Desktop timer policy while OUR OWN app is in the foreground (user request,
     * final form): the timer must **not switch at all** - not once, not per
     * interval.
     *
     * The desktop wallpaper cannot be seen behind our app, so every tick would be
     * a full-resolution decode (plus, in static mode, a JPEG encode and a
     * wallpaper write) for pixels nobody can look at. The tick is therefore never
     * consumed while the app is in the foreground: leaving the app (MainActivity
     * onStop pokes the service) makes the overdue tick switch **immediately**, so
     * the user returns to a freshly switched wallpaper and the normal interval
     * starts from there.
     *
     * The lock timer has its own, different policy for a covered lock screen
     * (`lockHiddenSwitchDone`: one switch per screen-on) and is not affected.
     *
     * Kept pure so the rule is testable.
     *
     * @param appInForeground whether our activity is in the front (see
     *   `LiveWallpaperService.isAppForeground()`).
     */
    fun shouldIdleWhileAppInForeground(appInForeground: Boolean): Boolean = appInForeground

}
