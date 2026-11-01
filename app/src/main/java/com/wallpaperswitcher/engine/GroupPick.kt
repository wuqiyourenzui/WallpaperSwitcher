package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.ShuffleShown
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.data.WallpaperImage

/**
 * Group-scoped media picking: the switch path used when a group carries its own
 * interval or switch mode (see [com.wallpaperswitcher.data.WallpaperGroup]).
 *
 * The screen-wide pickers ([MediaPick]) choose from every enabled group that
 * targets the screen, which is exactly what the default (all-follow-global)
 * install needs. A group with its own rhythm, however, must rotate **only its
 * own media** - and its cursor and shuffle deck must not be shared with the
 * other groups:
 *
 *  - the cursor is `group_schedule.lastMediaId` (the screen-wide
 *    `LAST_IMAGE_ID` is advanced by other groups too);
 *  - the shuffle deck is keyed by `(slot, groupId)` in `shuffle_shown`.
 *
 * Kept DAO-based (not pure) because it wraps the same queries [MediaPick] uses;
 * the pure parts live in [SwitchPicking] / [GroupPacing].
 */
internal object GroupPick {

    /**
     * Pick the next media of [groupId] for [slot] in [mode], excluding
     * [lastId] (the group's own cursor) where the mode calls for it.
     *
     * @param forPrefetch when true, motion media (video/GIF) yield null - the
     *   prefetch cache can only hold a still bitmap.
     */
    suspend fun pick(
        db: AppDatabase,
        slot: String,
        groupId: Long,
        mode: SwitchMode,
        lastId: Long,
        forPrefetch: Boolean = false,
        /** [SettingsKeys.PICK_SEQ]: the applied-switch counter (see MediaPick). */
        pickSeq: Long = 0L,
        /** [WallpaperGroup.filterMode] of the group ("" = 全部). */
        filter: String = "",
        /** [WallpaperGroup.sortOrder] == "NEWEST": sequential runs newest → oldest. */
        newestFirst: Boolean = false,
        /** 收藏优先: favourites draw this much more often. */
        favoriteWeight: Int = 1,
        /** 最近 N 张不重复 window contents for this slot. */
        recentIds: Collection<Long> = emptyList(),
    ): WallpaperImage? {
        val pickDao = db.groupPickDao()
        val imageDao = db.wallpaperImageDao()
        return when (mode) {
            SwitchMode.RANDOM ->
                MediaPick.randomInGroup(
                    pickDao, slot, groupId, lastId, pickSeq, filter, favoriteWeight, recentIds
                )

            SwitchMode.SEQUENTIAL -> {
                if (pickDao.countInGroup(slot, groupId, filter) == 0) null
                else {
                    val img = if (lastId > 0L) {
                        if (newestFirst) {
                            pickDao.getSequentialInGroupBefore(slot, groupId, lastId, filter)
                                ?: pickDao.getNewestInGroup(slot, groupId, filter)
                        } else {
                            pickDao.getSequentialInGroupAfter(slot, groupId, lastId, filter)
                                ?: pickDao.getFirstInGroup(slot, groupId, filter)
                        }
                    } else {
                        if (newestFirst) pickDao.getNewestInGroup(slot, groupId, filter)
                        else pickDao.getFirstInGroup(slot, groupId, filter)
                    }
                    if (forPrefetch && img != null && MediaTypes.isMotion(img.mediaType)) null
                    else img
                }
            }

            SwitchMode.SHUFFLE -> {
                val total = pickDao.countInGroup(slot, groupId, filter)
                if (total == 0) null
                else {
                    val shuffleDao = db.shuffleDao()
                    val shown = shuffleDao.getShownIds(slot, groupId).toMutableSet()
                    // Only a finished pass starts a new one (same rule as the
                    // screen-wide deck): a changed group size keeps this pass
                    // going.
                    if (SwitchPicking.shouldResetShuffleDeck(shown.size, total)) {
                        shown.clear()
                        shuffleDao.clearSlot(slot, groupId)
                    }
                    var candidate = MediaPick.shuffleUnseenInGroup(
                        pickDao, imageDao, slot, groupId, shown, lastId, pickSeq,
                        filter, favoriteWeight
                    )
                    if (candidate == null) {
                        // Every media has been shown (or only the one already on
                        // screen is left): start a fresh pass.
                        shown.clear()
                        shuffleDao.clearSlot(slot, groupId)
                        candidate = pickDao.getRandomInGroupExcluding(slot, groupId, lastId, filter)
                            ?: pickDao.getRandomInGroup(slot, groupId, filter)
                    }
                    if (forPrefetch && candidate != null &&
                        MediaTypes.isMotion(candidate.mediaType)
                    ) null else candidate
                }
            }
        }
    }

    /**
     * Result of the SEQUENTIAL walk ([pickSequential]): which group actually
     * supplied the media, and that group's walk rules (the walk may have
     * advanced to the next group when the starting one was exhausted, so the
     * caller must write its cursor / apply its filter to THIS group).
     */
    data class SequentialPick(
        val groupId: Long,
        val filter: String,
        val newestFirst: Boolean,
        val image: WallpaperImage,
    )

    /**
     * 顺序模式取图（本组走完 → 下一个分组）: walk [startGroupId] from its own
     * cursor; when that group is exhausted (the pick wrapped back to its edge,
     * see [SwitchPicking.sequentialWrapped]) continue in the next usable group
     * (id ascending, [GroupSchedulePlan.nextGroupAfter]).
     *
     * Shared by the live engine ([com.wallpaperswitcher.wallpaper.SwitchExecutor]),
     * the static applier and the prefetch so every path advances at exactly the
     * same boundary; 下一张预览 mirrors it through the same call (it never
     * reaches a write: the SEQUENTIAL queries are read-only).
     *
     * @return null when the starting group is gone/disabled or has no media
     *   this screen may show right now.
     */
    suspend fun pickSequential(
        db: AppDatabase,
        slot: String,
        startGroupId: Long,
        lastId: Long,
        pickSeq: Long = 0L,
        favoriteWeight: Int = 1,
        recentIds: Collection<Long> = emptyList(),
        /**
         * 屏幕上正在显示的那张（本 slot 的 `LAST_IMAGE_ID`，实时引擎传
         * `lastDisplayedId()`）。顺序模式的参考点以它为准：分组游标在随机 / 洗牌
         * 期间不推进，直接拿游标会从**上一次顺序模式**的旧位置继续，而不是接当前
         * 这张（见 [SwitchPicking.sequentialAnchor]）。0 = 未知，退回游标。
         */
        displayedId: Long = 0L,
    ): SequentialPick? {
        val start = db.wallpaperGroupDao().getGroupById(startGroupId)
            ?.takeIf { it.isEnabled && WallpaperTarget.fromName(it.target).suitsSlot(slot) }
            ?: return null
        val startFilter = GroupRules.mediaFilter(start)
        val startNewest = GroupRules.newestFirst(start)
        // 顺序模式的锚点：屏幕上那张属于本组就以它为准（自愈），否则用分组游标。
        val anchor = if (displayedId <= 0L) {
            lastId
        } else {
            val shown = try {
                db.wallpaperImageDao().getImageById(displayedId)
            } catch (_: Exception) {
                null
            }
            SwitchPicking.sequentialAnchor(
                displayedId = displayedId,
                displayedGroupId = shown?.groupId ?: 0L,
                walkGroupId = startGroupId,
                cursor = lastId,
            )
        }
        val picked = pick(
            db, slot, startGroupId, SwitchMode.SEQUENTIAL, anchor,
            pickSeq = pickSeq,
            filter = startFilter,
            newestFirst = startNewest,
            favoriteWeight = favoriteWeight,
            recentIds = recentIds,
        ) ?: return null
        if (anchor <= 0L || !SwitchPicking.sequentialWrapped(picked.id, anchor, startNewest)) {
            return SequentialPick(startGroupId, startFilter, startNewest, picked)
        }
        // 本组走完（取回来了本组边缘那张）：按 id 升序进入下一个可用分组，而不是
        // 绕回本组第一张（用户明确要求）。下一个分组同样取它的第一张（lastId=0）。
        val nextGroupId = try {
            GroupSchedulePlan.nextGroupAfter(db, slot, startGroupId)
        } catch (_: Exception) {
            0L
        }
        if (nextGroupId <= 0L || nextGroupId == startGroupId) {
            return SequentialPick(startGroupId, startFilter, startNewest, picked)
        }
        val nextGroup = db.wallpaperGroupDao().getGroupById(nextGroupId)
            ?: return SequentialPick(startGroupId, startFilter, startNewest, picked)
        val nextFilter = GroupRules.mediaFilter(nextGroup)
        val nextNewest = GroupRules.newestFirst(nextGroup)
        val advanced = pick(
            db, slot, nextGroupId, SwitchMode.SEQUENTIAL, 0L,
            pickSeq = pickSeq,
            filter = nextFilter,
            newestFirst = nextNewest,
            favoriteWeight = favoriteWeight,
            recentIds = recentIds,
        ) ?: return SequentialPick(startGroupId, startFilter, startNewest, picked)
        return SequentialPick(nextGroupId, nextFilter, nextNewest, advanced)
    }

    /** Record one media as dealt by the group's deck (a no-op for other modes). */
    suspend fun recordShown(
        db: AppDatabase,
        slot: String,
        groupId: Long,
        mediaId: Long,
    ) {
        if (groupId <= 0L || mediaId <= 0L) return
        try {
            db.shuffleDao().insertShown(
                listOf(ShuffleShown(slot = slot, groupId = groupId, mediaId = mediaId))
            )
        } catch (_: Exception) {
        }
    }
}
