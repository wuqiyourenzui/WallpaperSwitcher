package com.wallpaperswitcher.engine

/**
 * Small pure helpers shared by the live engine and the static applier so the
 * RANDOM / SEQUENTIAL / SHUFFLE switch modes behave identically on both paths.
 */
object SwitchPicking {

    /** 视频播完再切: what a TIMED tick must do while the option is on. */
    enum class VideoEndHold {
        /** No hold needed (option off, or no video on screen): switch now. */
        SWITCH_NOW,
        /** First tick of this clip: remember it and wait for the pass to end. */
        HOLD_PENDING,
        /** A hold is already waiting: drop every later tick until the pass ends. */
        DROP_TICK,
    }

    /**
     * 视频播完再切: decide what a timed tick does while [videoPlaying].
     *
     * [holdPending] must *drop* the tick rather than let it through while the
     * clip is genuinely on screen. The first implementation returned early only
     * while no hold was pending, so the SECOND interval fell through and cut
     * the clip off mid-pass - exactly the "视频播完再切还是会被定时切换" report.
     * Manual / unlock switches never call this (they must act immediately).
     *
     * A pending hold is only meaningful while a video is actually playing: it
     * is released by the engine's onVideoPassCompleted when the pass ends. If the clip
     * left the screen without completing a pass - it failed to start, the
     * decode thread gave up, or a manual switch replaced it - no completion
     * will ever arrive, so this tick must *release* the stale hold and switch
     * normally instead of dropping every future tick forever (the emulator
     * deadlock where timed switching stopped until the option was toggled).
     * The "isVideoPlaying blinks between passes" case is not lost: the
     * completion callback clears the hold at the pass boundary before the
     * next pass starts, so a blink can only be seen here when the pass really
     * never completed.
     */
    fun videoEndHold(
        optionEnabled: Boolean,
        videoPlaying: Boolean,
        holdPending: Boolean,
    ): VideoEndHold = when {
        // Turning the option off releases a hold that was already waiting.
        !optionEnabled -> VideoEndHold.SWITCH_NOW
        // A hold waits for the pass to end only while the video is on screen;
        // a hold with no video playing is stale (failed / superseded clip) and
        // must be released, or timed switching stops for good.
        holdPending && videoPlaying -> VideoEndHold.DROP_TICK
        videoPlaying -> VideoEndHold.HOLD_PENDING
        else -> VideoEndHold.SWITCH_NOW
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
