package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperImage
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
        // 顺序模式：这一跳属于「当前图片所在分组」，预览因此调用与定时 / 手动 /
        // 静态 / 预取**完全相同**的 walker（含 本组走完 → 下一个分组），预览和
        // 真正的切换逐字一致。walker 在 SEQUENTIAL 下只做只读查询，不写状态。
        // 随机 / 洗牌不做这一步：它们的切换本来就是屏幕级的
        // （分组轮转会退化，见 WallpaperSwitchService.nextScreenTick）。
        if (globalMode == SwitchMode.SEQUENTIAL) {
            val groupId = try {
                GroupSchedulePlan.nextGroupId(db, slot, nowMs)
            } catch (_: Exception) {
                0L
            }
            if (groupId > 0L) {
                val lastId = try {
                    db.groupScheduleDao().get(groupId, slot)?.lastMediaId ?: 0L
                } catch (_: Exception) {
                    0L
                }
                val walk = GroupPick.pickSequential(
                    db, slot, groupId, lastId,
                    pickSeq = pickSeq,
                    favoriteWeight = favoriteWeight,
                    recentIds = recentIds,
                    // 预览必须和真正的切换落在同一张：顺序模式的参考点同样是
                    // 屏幕上那张（见 SwitchPicking.sequentialAnchor）。
                    displayedId = try {
                        db.settingsDao().getLong(
                            if (slot == WallpaperTarget.SLOT_LOCK) SettingsKeys.LAST_IMAGE_ID_LOCK
                            else SettingsKeys.LAST_IMAGE_ID
                        )
                    } catch (_: Exception) {
                        0L
                    },
                )
                if (walk != null) return walk.image
                // 兜底：本组一张都取不到时落到下面的屏幕级挑选
                // （它至少和"无分组可切"时的切换行为一致）。
            }
        }
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

}
