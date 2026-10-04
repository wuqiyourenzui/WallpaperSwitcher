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
