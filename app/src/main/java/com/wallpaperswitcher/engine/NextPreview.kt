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
        // The preview mirrors the switch itself: 随机 / 洗牌 draw from all
        // enabled groups, 顺序 walks them group by group - the same
        // screen-wide pick the timer and the taps now use (no per-tap group
        // resolution any more, see MediaPick.eligibleGroups).
        val lastId = try {
            db.settingsDao().getLong(
                if (slot == WallpaperTarget.SLOT_LOCK) SettingsKeys.LAST_IMAGE_ID_LOCK
                else SettingsKeys.LAST_IMAGE_ID
            )
        } catch (_: Exception) {
            0L
        }
        return peekScreenWide(
            db, slot, globalMode, lastId, pickSeq, favoriteWeight, recentIds, nowMs
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
        nowMs: Long = System.currentTimeMillis(),
    ): WallpaperImage? {
        val imageDao = db.wallpaperImageDao()
        return when (mode) {
            SwitchMode.RANDOM -> {
                val groups = MediaPick.eligibleGroups(db, slot, nowMs)
                val rows = MediaPick.poolFor(
                    imageDao, db.groupPickDao(), slot, groups, favoriteWeight
                )
                MediaPick.randomFromPool(imageDao, rows, lastId, pickSeq, recentIds)
            }
            SwitchMode.SEQUENTIAL ->
                // 顺序切换：和实时引擎 / 静态模式一致 —— 从当前媒体开始
                // 按分组逐个推进（见 [MediaPick.sequentialAcrossGroups]），
                // 预览才能和下一次真正的切换指向同一张。
                MediaPick.sequentialAcrossGroups(
                    imageDao,
                    db.groupPickDao(),
                    slot,
                    MediaPick.eligibleGroups(db, slot, nowMs),
                    lastId,
                )
            SwitchMode.SHUFFLE -> {
                val groups = MediaPick.eligibleGroups(db, slot, nowMs)
                val poolRows = MediaPick.poolFor(
                    imageDao, db.groupPickDao(), slot, groups, favoriteWeight
                )
                val total = poolRows.size
                if (total == 0) null else {
                    val shown = db.shuffleDao().getShownIds(slot).toMutableSet()
                    // A finished pass starts a new one in the real switch
                    // (it clears the deck first). Mirror that WITHOUT writing:
                    // an empty deck here produces exactly the id the engine
                    // will compute once it has cleared its own.
                    if (SwitchPicking.shouldResetShuffleDeck(shown.size, total)) {
                        shown.clear()
                    }
                    MediaPick.shuffleUnseenFromPool(
                        imageDao = imageDao,
                        poolRows = poolRows,
                        favoriteIds = MediaPick.poolFavorites(slot, favoriteWeight),
                        shownIds = shown,
                        excludeId = lastId,
                        pickSeq = pickSeq,
                        favoriteWeight = favoriteWeight,
                    ) ?: MediaPick.randomFromPool(
                        imageDao, poolRows, lastId, pickSeq, recentIds
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
