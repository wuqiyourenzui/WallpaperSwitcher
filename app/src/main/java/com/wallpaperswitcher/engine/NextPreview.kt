package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.getString

/**
 * "下一张预览": compute the media the next automatic switch would show, WITHOUT
 * changing any state - no cursor move, no shuffle row, no wallpaper write.
 *
 * Mirrors the two scheduling shapes of [com.wallpaperswitcher.service.WallpaperSwitchService]:
 *  - every group follows the global interval -> the screen-wide pick (the same
 *    queries the live engine's picker uses);
 *  - some group has its own interval -> the group [GroupPacing] says is due
 *    first, and that group's next media.
 */
internal object NextPreview {

    /** The next media the HOME screen would show, or null when nothing can. */
    suspend fun nextHome(
        db: AppDatabase,
        nowMs: Long = System.currentTimeMillis(),
    ): WallpaperImage? = nextForSlot(db, WallpaperTarget.SLOT_HOME, nowMs)

    /**
     * The next media the LOCK screen would show. 悬浮按钮「长按预览」在两块屏上都要
     * 能用，锁屏那条独立的定时/选择路径因此也要能预览（和 HOME 同一套逻辑，
     * 只是 slot 不同：锁屏候选在 SQL 层已经排除视频/GIF）。
     */
    suspend fun nextLock(
        db: AppDatabase,
        nowMs: Long = System.currentTimeMillis(),
    ): WallpaperImage? = nextForSlot(db, WallpaperTarget.SLOT_LOCK, nowMs)

    /** The next media [slot] would show, or null when nothing can. */
    suspend fun nextForSlot(
        db: AppDatabase,
        slot: String,
        nowMs: Long = System.currentTimeMillis(),
    ): WallpaperImage? {
        val globalMode = globalMode(db)
        // The applied-switch counter: the preview and the switch that follows it
        // derive the pick from the same value (see SwitchPicking.pickSeed).
        val pickSeq = try {
            db.settingsDao().getLong(SettingsKeys.PICK_SEQ)
        } catch (_: Exception) {
            0L
        }
        // 收藏优先 / 最近 N 张不重复: the preview must name the same media the
        // switch will pick, so it reads the same options.
        val favoriteWeight = PickOptions.favoriteWeight(db.settingsDao())
        val recentIds = PickOptions.recentIds(
            db, slot, PickOptions.recentWindow(db.settingsDao())
        )
        // Same decision the service (and a manual tap) makes - see
        // GroupSchedulePlan.nextHomeGroupId. 0 = the screen-wide pick.
        val groupId = try {
            GroupSchedulePlan.nextGroupId(db, slot, nowMs)
        } catch (_: Exception) {
            0L
        }
        if (groupId <= 0L) {
            val lastId = try {
                db.settingsDao().getLong(SettingsKeys.LAST_IMAGE_ID)
            } catch (_: Exception) {
                0L
            }
            return peekScreenWide(db, slot, globalMode, lastId, pickSeq, favoriteWeight, recentIds)
        }
        val group = try {
            db.wallpaperGroupDao().getGroupById(groupId)
        } catch (_: Exception) {
            null
        } ?: return null
        val mode = globalMode
        val lastId = try {
            db.groupScheduleDao().get(group.id, slot)?.lastMediaId ?: 0L
        } catch (_: Exception) {
            0L
        }
        return peekInGroup(
            db, slot, group.id, mode, lastId, pickSeq,
            filter = GroupRules.mediaFilter(group),
            newestFirst = false,
            favoriteWeight = favoriteWeight,
            recentIds = recentIds,
        )
    }

    private suspend fun globalMode(db: AppDatabase): SwitchMode = try {
        SwitchMode.valueOf(
            db.settingsDao().getString(
                SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name
            )
        )
    } catch (_: Exception) {
        SwitchMode.RANDOM
    }

    /** Read-only screen-wide pick (peek, never consume). */
    private suspend fun peekScreenWide(
        db: AppDatabase,
        slot: String,
        mode: SwitchMode,
        lastId: Long,
        pickSeq: Long,
        favoriteWeight: Int,
        recentIds: Collection<Long>,
    ): WallpaperImage? {
        val imageDao = db.wallpaperImageDao()
        return when (mode) {
            SwitchMode.RANDOM -> MediaPick.random(
                imageDao, slot, lastId, pickSeq = pickSeq,
                favoriteWeight = favoriteWeight, recentIds = recentIds
            )
            SwitchMode.SEQUENTIAL ->
                if (imageDao.countByEnabledGroups(slot) == 0) null
                else if (lastId > 0L) {
                    imageDao.getSequentialImageFromEnabledGroupsAfter(slot, lastId)
                        ?: imageDao.getFirstFromEnabledGroups(slot)
                } else {
                    imageDao.getFirstFromEnabledGroups(slot)
                }
            SwitchMode.SHUFFLE -> {
                val total = imageDao.countByEnabledGroups(slot)
                if (total == 0) null else {
                    val shown = db.shuffleDao().getShownIds(slot).toMutableSet()
                    // A finished pass starts a new one in the real switch
                    // (it clears the deck first). Mirror that WITHOUT writing:
                    // an empty deck here produces exactly the id the engine
                    // will compute once it has cleared its own.
                    if (SwitchPicking.shouldResetShuffleDeck(shown.size, total)) {
                        shown.clear()
                    }
                    MediaPick.shuffleUnseen(
                        imageDao = imageDao,
                        slot = slot,
                        shownIds = shown,
                        excludeId = lastId,
                        knownCount = total,
                        pickSeq = pickSeq,
                        favoriteWeight = favoriteWeight
                    ) ?: MediaPick.random(
                        imageDao, slot, lastId, pickSeq = pickSeq,
                        favoriteWeight = favoriteWeight, recentIds = recentIds
                    )
                }
            }
        }
    }

    /** Read-only in-group pick. */
    private suspend fun peekInGroup(
        db: AppDatabase,
        slot: String,
        groupId: Long,
        mode: SwitchMode,
        lastId: Long,
        pickSeq: Long,
        filter: String,
        newestFirst: Boolean,
        favoriteWeight: Int,
        recentIds: Collection<Long>,
    ): WallpaperImage? {
        val pickDao = db.groupPickDao()
        val imageDao = db.wallpaperImageDao()
        return when (mode) {
            SwitchMode.RANDOM ->
                MediaPick.randomInGroup(
                    pickDao, slot, groupId, lastId, pickSeq,
                    filter, favoriteWeight, recentIds
                )
            SwitchMode.SEQUENTIAL ->
                if (pickDao.countInGroup(slot, groupId, filter) == 0) null
                else if (lastId > 0L) {
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
            SwitchMode.SHUFFLE -> {
                val total = pickDao.countInGroup(slot, groupId, filter)
                if (total == 0) null else {
                    val shown = db.shuffleDao().getShownIds(slot, groupId).toMutableSet()
                    if (SwitchPicking.shouldResetShuffleDeck(shown.size, total)) {
                        shown.clear()
                    }
                    MediaPick.shuffleUnseenInGroup(
                        pickDao, imageDao, slot, groupId, shown, lastId, pickSeq,
                        filter, favoriteWeight
                    ) ?: pickDao.getRandomInGroupExcluding(slot, groupId, lastId, filter)
                        ?: pickDao.getRandomInGroup(slot, groupId, filter)
                }
            }
        }
    }

    /** 顺序=新的在前 (see WallpaperGroup.sortOrder). */
    private const val SORT_NEWEST = "NEWEST"
}
