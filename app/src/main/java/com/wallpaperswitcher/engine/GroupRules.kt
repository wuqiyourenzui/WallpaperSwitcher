package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.WallpaperGroup

/**
 * 时间规则: pure rules that decide whether a group may be shown right now.
 *
 * 时间规则 (per group): an optional window of the day. `-1` means "all day"
 * (the default, so nothing changes until the user sets a window). A window may
 * wrap around midnight: with `from = 22:00` and `to = 06:00` the group is
 * active from 22:00 to midnight AND from 00:00 to 06:00.
 *
 * Kept free of Android types so it is unit-testable.
 */
object GroupRules {

    /** Minute-of-day of [wallClockMs] in the phone's current time zone. */
    fun minuteOfDay(wallClockMs: Long): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = wallClockMs
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    /**
     * Whether the [fromMinute]..[toMinute] window contains [minuteOfDay].
     * Either bound being outside 0..1439 (i.e. -1, the "all day" default) makes
     * the window open. `from == to` is treated as all day as well: a zero-length
     * window would silently lock the group out forever, which no user wants.
     */
    fun windowContains(minuteOfDay: Int, fromMinute: Int, toMinute: Int): Boolean {
        if (fromMinute !in 0..1439 || toMinute !in 0..1439) return true
        if (fromMinute == toMinute) return true
        val minute = minuteOfDay.coerceIn(0, 1439)
        return if (fromMinute < toMinute) {
            minute in fromMinute until toMinute
        } else {
            // Wraps midnight: 22:00 -> 06:00 is active at 23:00 and at 05:00.
            minute >= fromMinute || minute < toMinute
        }
    }

    /**
     * Whether [group]'s 时间规则 allows it to be shown at [wallClockMs]:
     * the time-of-day window ([activeFromMinute] / [activeToMinute]) and the
     * weekdays ([WallpaperGroup.activeDays]), both defaulting to "always".
     *
     * (跟随深色模式 was removed on request - the group screen only carries the
     * interval and the time rules again; `activeThemeMode` stays in the schema
     * but nothing reads it.)
     */
    fun isActiveAt(group: WallpaperGroup, wallClockMs: Long): Boolean {
        if (!windowContains(minuteOfDay(wallClockMs), group.activeFromMinute, group.activeToMinute)) {
            return false
        }
        return dayAllowed(group.activeDays, wallClockMs)
    }

    /**
     * Whether this group carries a 时间规则 at all (a real time-of-day window or
     * a weekday mask).
     *
     * The screen-wide tick needs it to decide whether it has to filter the
     * candidate groups: when no group restricts itself, every enabled group
     * qualifies and the extra per-group media-count query can be skipped.
     */
    fun hasTimeRules(group: WallpaperGroup): Boolean =
        windowRestricted(group) || (group.activeDays > 0 && (group.activeDays and ALL_DAYS) != ALL_DAYS)

    /**
     * True only when BOTH bounds are real minutes and they differ - exactly the
     * case [windowContains] narrows to a part of the day. Either bound being -1
     * (or both being equal) leaves the window open, so it is not a restriction.
     */
    private fun windowRestricted(group: WallpaperGroup): Boolean =
        group.activeFromMinute in 0..1439 &&
            group.activeToMinute in 0..1439 &&
            group.activeFromMinute != group.activeToMinute

    /**
     * The groups a SCREEN-WIDE tick may pick from: they must have media for
     * [slot] (`counts`) and their 时间规则 must allow them right now.
     *
     * Used by the screen-wide branch of the switch timer, which otherwise
     * bypassed [isActiveAt] completely: a group with a 22:00-06:00 window was
     * still switched to during the day as long as no OTHER group carried its
     * own interval (see GroupSchedulePlan for the per-group branch).
     *
     * @param counts per-group media count for this slot, from
     *   `groupPickDao().countsForSlot`; a group missing from the map counts as
     *   "no media" (that is what the per-group branch does too).
     */
    fun screenTickCandidates(
        groups: List<WallpaperGroup>,
        counts: Map<Long, Int>,
        slot: String,
        wallClockMs: Long,
    ): List<WallpaperGroup> = groups.filter { group ->
        if (!WallpaperTarget.fromName(group.target).suitsSlot(slot)) {
            return@filter false
        }
        if ((counts[group.id] ?: 0) <= 0) return@filter false
        isActiveAt(group, wallClockMs)
    }

    /** Bit index used by [WallpaperGroup.activeDays]: 0 = Monday … 6 = Sunday. */
    fun weekdayIndex(wallClockMs: Long): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = wallClockMs
        // Calendar.MONDAY == 2 … SUNDAY == 1, so shift Monday to index 0.
        return (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    }

    /**
     * Whether [mask] (bit 0 = Monday … bit 6 = Sunday) contains the day of
     * [wallClockMs]. `0` and the full `0x7F` both mean "every day", which is
     * what every existing row has.
     */
    fun dayAllowed(mask: Int, wallClockMs: Long): Boolean {
        if (mask <= 0 || (mask and ALL_DAYS) == ALL_DAYS) return true
        return (mask shr weekdayIndex(wallClockMs)) and 1 == 1
    }

    const val ALL_DAYS = 0x7F

    /**
     * Whether this group opts out of the screen-wide pick: it carries its own
     * interval.
     *
     * The switch MODE is deliberately global-only: a per-group mode kept
     * producing mixed behaviour (定时切换 honoured it, manual taps did not, and
     * a group on 跟随全局 silently ignored it), so the group screen no longer
     * offers one and the stored `switchMode` column is inert.
     */
    fun drivesOwnRhythm(group: WallpaperGroup): Boolean =
        group.intervalMs > 0L || mediaFilter(group).isNotEmpty()

    /** 仅图片 / 仅视频（GIF 视为视频类动图）的取值，见 [WallpaperGroup.filterMode]。 */
    const val MEDIA_IMAGE = "IMAGE"
    const val MEDIA_VIDEO = "MOTION"

    /**
     * The group's media-type filter for the pick queries: "" = 两者都切换,
     * [MEDIA_IMAGE] = 仅图片, [MEDIA_VIDEO] = 仅视频（含 GIF）.
     * Anything else (a value left over from an older build / a disabled feature)
     * is treated as 两者, so no group can be silently locked out.
     */
    fun mediaFilter(group: WallpaperGroup): String = when (group.filterMode) {
        MEDIA_IMAGE -> MEDIA_IMAGE
        MEDIA_VIDEO -> MEDIA_VIDEO
        else -> ""
    }

    /**
     * Whether a media of [mediaType] may be shown by a group whose normalized
     * filter is [filter] (see [mediaFilter]): "" = both, [MEDIA_IMAGE] = still
     * images only, [MEDIA_VIDEO] = videos and GIFs.
     *
     * The pick queries already carry the filter, but a value cached earlier
     * (prefetch) or a fallback can outlive a settings change, so the caller
     * re-checks it before showing anything.
     */
    fun allowsMedia(filter: String, mediaType: String): Boolean = when (filter) {
        MEDIA_IMAGE -> mediaType == MediaTypes.IMAGE
        MEDIA_VIDEO -> mediaType != MediaTypes.IMAGE
        else -> true
    }

    /** `"HH:mm"` for a minute-of-day, or null for the "all day" value. */
    fun formatMinuteOfDay(minute: Int): String? {
        if (minute !in 0..1439) return null
        return "%02d:%02d".format(minute / 60, minute % 60)
    }

    /** Parse `"HH:mm"` back to a minute-of-day, or null when it is malformed. */
    fun parseMinuteOfDay(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
}
