package com.wallpaperswitcher.engine

/**
 * Small pure helpers shared by the live engine and the static applier so the
 * RANDOM / SEQUENTIAL / SHUFFLE switch modes behave identically on both paths.
 */
object SwitchPicking {

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
     * Random offset for the "random pick by OFFSET" DAO query. Both callers
     * guarantee [count] > 1 (single-item sets are handled before this is
     * invoked). The query EXCLUDES the currently shown media (id != lastId),
     * so the filtered set has count-1 rows and a valid offset is in
     * [0, count-2] — never landing past the last row, which would have forced
     * the slower ORDER BY RANDOM fallback ~1 out of every `count` picks.
     */
    fun randomOffset(count: Int): Int {
        require(count > 1) { "count must be > 1 (single-item sets are handled by the caller)" }
        return kotlin.random.Random.Default.nextInt(count - 1)
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
        excludeId: Long
    ): Long? {
        if (enabledIds.isEmpty()) return null
        val candidates = enabledIds.filter { it != excludeId && it !in shownIds }
        if (candidates.isEmpty()) return null
        return candidates[kotlin.random.Random.Default.nextInt(candidates.size)]
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
