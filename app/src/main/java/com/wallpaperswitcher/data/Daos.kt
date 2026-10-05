package com.wallpaperswitcher.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A (groupId, folderPath) pair recorded by a folder import, for auto-scan. */
data class ScannedFolderPath(val groupId: Long, val folderPath: String)

/** groupId -> media count, for the home screen group cards. */
data class GroupMediaCount(val groupId: Long, val mediaCount: Int)

/**
 * 一行"最近显示"记录：媒体本体 + 它显示在哪块屏、什么时候显示的。
 *
 * `WallpaperImage` 里没有 slot/shownAt（它描述的是素材，不是"显示历史"），
 * 所以历史页需要的这两列由 [RecentDao.recentMedia] 从 `recent_shown` 带出来。
 */
data class RecentShownEntry(
    val slot: String,
    val shownAt: Long,
    @Embedded val image: WallpaperImage,
)

@Dao
interface WallpaperGroupDao {

    @Query("SELECT * FROM wallpaper_groups ORDER BY createdAt DESC")
    fun getAllGroups(): Flow<List<WallpaperGroup>>

    /** One-shot read of every group (export, group lookups). */
    @Query("SELECT * FROM wallpaper_groups ORDER BY createdAt DESC")
    suspend fun getAllGroupsOnce(): List<WallpaperGroup>

    @Query("SELECT * FROM wallpaper_groups WHERE isEnabled = 1")
    suspend fun getEnabledGroupsSync(): List<WallpaperGroup>

    @Query("SELECT * FROM wallpaper_groups WHERE id = :id")
    suspend fun getGroupById(id: Long): WallpaperGroup?

    /** Used by the online sources to reuse their auto-created group. */
    @Query("SELECT * FROM wallpaper_groups WHERE name = :name LIMIT 1")
    suspend fun getGroupByName(name: String): WallpaperGroup?

    @Query("SELECT * FROM wallpaper_groups WHERE id = :id")
    fun getGroupByIdFlow(id: Long): Flow<WallpaperGroup?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: WallpaperGroup): Long

    @Update
    suspend fun update(group: WallpaperGroup)

    /** Change where this group's media may be shown (HOME / LOCK / BOTH). */
    @Query("UPDATE wallpaper_groups SET target = :target WHERE id = :id")
    suspend fun updateTarget(id: Long, target: String)

    /**
     * Give this group its own switch interval (0 = follow the screen's global
     * interval). See [WallpaperGroup.intervalMs].
     */
    @Query("UPDATE wallpaper_groups SET intervalMs = :intervalMs WHERE id = :id")
    suspend fun updateInterval(id: Long, intervalMs: Long)

    @Delete
    suspend fun delete(group: WallpaperGroup)
}

@Dao
interface WallpaperImageDao {

    @Query("SELECT COUNT(*) FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getImageCountByGroup(groupId: Long): Int

    /**
     * How many rows come from the media library (`content://media/...`) rather
     * than from a SAF folder import. Those rows need READ_MEDIA_* to be read at
     * all, so the home screen shows a permission hint only when this is > 0 - a
     * library that is exclusively SAF-imported keeps working without the
     * permission and must not be nagged about it.
     */
    @Query("SELECT COUNT(*) FROM wallpaper_images WHERE uri LIKE 'content://media/%'")
    fun getMediaStoreRowCount(): Flow<Int>

    @Query("SELECT id FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getImageIdsByGroup(groupId: Long): List<Long>

    @Query("SELECT uri FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getUrisByGroup(groupId: Long): List<String>

    /** URIs of the rows that live under one of the app's own media folders. */
    @Query("SELECT uri FROM wallpaper_images WHERE uri LIKE :pattern")
    suspend fun getUrisLike(pattern: String): List<String>

    @Query("SELECT uri FROM wallpaper_images WHERE id IN (:ids)")
    suspend fun getUrisByIds(ids: List<Long>): List<String>

    @Query("SELECT DISTINCT groupId, folderPath FROM wallpaper_images WHERE isFromFolder = 1 AND folderPath != ''")
    suspend fun getScannedFolderPaths(): List<ScannedFolderPath>

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId ORDER BY addedAt DESC, id DESC")
    suspend fun getImagesByGroupSync(groupId: Long): List<WallpaperImage>

    /** Every media row; the duplicate sweep filters it down itself. */
    @Query("SELECT * FROM wallpaper_images")
    suspend fun getAllImagesSync(): List<WallpaperImage>

    @Query("SELECT * FROM wallpaper_images WHERE id = :id")
    suspend fun getImageById(id: Long): WallpaperImage?

    // Every cross-group query takes the screen [slot] it is picking for
    // ("HOME" or "LOCK"): a group only contributes to the screen(s) it targets
    // (target = 'BOTH' always matches). This is what makes the home screen and
    // the lock screen switch independently from different groups.
    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (
            SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND target IN ('BOTH', :slot)
        )
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        ORDER BY id ASC LIMIT 1
    """)
    suspend fun getFirstFromEnabledGroups(slot: String): WallpaperImage?

    // --- Cross-group queries (for wallpaper switching) ---

    // Item-based SEQUENTIAL cursor: the next enabled media with an id strictly
    // greater than the last displayed id (ORDER BY id is the same order used
    // by getFirstFromEnabledGroups). Unlike an offset cursor, this never skips
    // an item when media is deleted or a group is disabled between switches —
    // the caller wraps to the first row when this returns null.
    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (
            SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND target IN ('BOTH', :slot)
        )
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND id > :lastId
        ORDER BY id ASC LIMIT 1
    """)
    suspend fun getSequentialImageFromEnabledGroupsAfter(slot: String, lastId: Long): WallpaperImage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: WallpaperImage): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<WallpaperImage>)

    /**
     * Media ids of one folder path. The online sources use it to prune items
     * whose media row the user deleted (see OnlineSync), without one query per
     * item.
     */
    @Query("SELECT id FROM wallpaper_images WHERE folderPath = :folderPath")
    suspend fun getIdsByFolder(folderPath: String): List<Long>

    @Delete
    suspend fun delete(image: WallpaperImage)

    /**
     * Repair the stored media type of one item (used by the engine's
     * self-heal: media added by an older build could be mis-typed as IMAGE
     * while the file is really a video/GIF, which made it display black).
     */
    @Query("UPDATE wallpaper_images SET mediaType = :mediaType WHERE id = :id")
    suspend fun updateMediaType(id: Long, mediaType: String)

    /** 收藏 (★): read by the picker's favourite weighting / 仅收藏 filter. */
    @Query("UPDATE wallpaper_images SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /**
     * 按 uri 批量改收藏。
     *
     * 同一个文件可以在多个分组里各有一行（导入只按分组去重），而收藏页/大图浏览
     * 是按 **uri** 判断星标的（[observeFavorites] 也是按 uri 去重）。只改一行的话，
     * 在 B 组里"取消收藏"改到的是本来就 =0 的那一行 —— 星标不动、提示却说改成功了。
     */
    @Query("UPDATE wallpaper_images SET isFavorite = :favorite WHERE uri = :uri")
    suspend fun setFavoriteByUri(uri: String, favorite: Boolean)

    /**
     * 收藏聚合（跨分组）：收藏页直接看这一份，不用把每个分组都拉一遍。
     * 同一张图被多个分组引用时只出现一次（按 uri 去重）。
     */
    @Query(
        """
        SELECT * FROM wallpaper_images
        WHERE isFavorite = 1
        GROUP BY uri
        ORDER BY addedAt DESC, id DESC
        """
    )
    fun observeFavorites(): Flow<List<WallpaperImage>>

    /** id + weight for the SCREEN-WIDE weighted random (favourites boosted). */
    @Query("""
        SELECT id AS id, (CASE WHEN isFavorite = 1 THEN :favoriteWeight ELSE 1 END) AS weight
        FROM wallpaper_images
        WHERE groupId IN (
            SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND target IN ('BOTH', :slot)
        )
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        ORDER BY id ASC
    """)
    suspend fun weightsForSlot(slot: String, favoriteWeight: Int): List<MediaWeight>

    /**
     * Remember the decode metadata of one media (see [WallpaperImage.width]).
     * Matched by URI because the same file can be listed in several groups and
     * the caller only knows the URI it just decoded.
     */
    @Query(
        "UPDATE wallpaper_images SET width = :width, height = :height, " +
            "rotationDegrees = :rotationDegrees WHERE uri = :uri"
    )
    suspend fun updateMediaMeta(uri: String, width: Int, height: Int, rotationDegrees: Int)

    @Query("DELETE FROM wallpaper_images WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // --- Enabled-groups queries ---

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (
            SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND target IN ('BOTH', :slot)
        )
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomImageFromEnabledGroups(slot: String): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (
            SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND target IN ('BOTH', :slot)
        )
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND id != :excludeId
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomImageFromEnabledGroupsExcluding(slot: String, excludeId: Long): WallpaperImage?

    /**
     * Every id that may be shown on [slot], in the same order as
     * [getFirstFromEnabledGroups] and the sequential cursor.
     *
     * Used by the SHUFFLE deck: the "already shown" set is filtered in memory
     * (see MediaPick.shuffleUnseen) instead of being passed to SQL as an
     * `id NOT IN (...)` list, which throws as soon as one group holds more
     * media than SQLite allows bound variables (999 on older builds) - the
     * switch then failed completely instead of showing a wallpaper.
     */
    @Query("""
        SELECT id FROM wallpaper_images
        WHERE groupId IN (
            SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND target IN ('BOTH', :slot)
        )
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        ORDER BY id ASC
    """)
    suspend fun getEnabledIds(slot: String): List<Long>

    // Fast random pick: ORDER BY RANDOM() sorts the whole table on every
    // switch, which is slow and power-hungry on large libraries. This uses a
    // random OFFSET instead (with the ORDER BY RANDOM() variant kept as a
    // fallback when the offset lands on a deleted row gap).
    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (
            SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND target IN ('BOTH', :slot)
        )
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND id != :excludeId
        ORDER BY id LIMIT 1 OFFSET :offset
    """)
    suspend fun getRandomImageFromEnabledGroupsExcludingAt(
        slot: String,
        excludeId: Long,
        offset: Int
    ): WallpaperImage?

    @Query("""
        SELECT COUNT(*) FROM wallpaper_images
        WHERE groupId IN (
            SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND target IN ('BOTH', :slot)
        )
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
    """)
    suspend fun countByEnabledGroups(slot: String): Int


    // --- Per-group media counts (home screen cards) ---

    @Query("SELECT groupId, COUNT(*) AS mediaCount FROM wallpaper_images GROUP BY groupId")
    fun getMediaCounts(): Flow<List<GroupMediaCount>>
}

@Dao
interface SettingsDao {

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun getValueFlow(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(setting: AppSettings)

    /** Drop a setting row that a removed feature used to write (housekeeping). */
    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun deleteKey(key: String): Int

    /**
     * Atomically add 1 to an integer setting; returns the number of rows
     * changed (0 = the key does not exist yet, see [incrementLong]).
     */
    @Query(
        "UPDATE app_settings SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) " +
            "WHERE `key` = :key"
    )
    suspend fun bumpLong(key: String): Int
}

/**
 * Advance a monotonic counter setting ([SettingsKeys.PICK_SEQ]).
 *
 * UPDATE-first (one statement in the steady state); the INSERT only happens the
 * very first time on a fresh install. Both switches run in this one process, so
 * a racing double insert would at worst skip a seed value.
 */
suspend fun SettingsDao.incrementLong(key: String) {
    if (bumpLong(key) == 0) setLong(key, 1L)
}

/**
 * Persisted SHUFFLE deck state (see [ShuffleShown]).
 *
 * Used by both switch paths: the live wallpaper engine keeps the deck in memory
 * and writes each newly shown id as it appears, the static applier reads/writes
 * per tick.
 */
@Dao
interface ShuffleDao {

    /**
     * The ids already shown on [slot] by the deck of [groupId] (0 = the
     * screen-wide deck that picks across all groups).
     */
    @Query("SELECT mediaId FROM shuffle_shown WHERE slot = :slot AND groupId = :groupId")
    suspend fun getShownIds(slot: String, groupId: Long = 0L): List<Long>

    /**
     * Record one shown media. IGNORE, not REPLACE: the row already existing is
     * the normal case (a re-shown id inside one pass) and must stay a no-op.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShown(rows: List<ShuffleShown>)

    /** Start a new pass: every id shown on [slot] may be picked again. */
    @Query("DELETE FROM shuffle_shown WHERE slot = :slot AND groupId = :groupId")
    suspend fun clearSlot(slot: String, groupId: Long = 0L)
}

/**
 * Per-group pacing state (see [GroupSchedule]): when each group last provided a
 * switch for a screen. Only groups that carry their own interval are read.
 */
@Dao
interface GroupScheduleDao {

    @Query("SELECT * FROM group_schedule")
    suspend fun getAll(): List<GroupSchedule>

    @Query("SELECT * FROM group_schedule WHERE slot = :slot")
    suspend fun getAllForSlot(slot: String): List<GroupSchedule>

    @Query("SELECT * FROM group_schedule WHERE groupId = :groupId AND slot = :slot")
    suspend fun get(groupId: Long, slot: String): GroupSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GroupSchedule)

    /** Called when a group is deleted (the FK cascade also covers this). */
    @Query("DELETE FROM group_schedule WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: Long)

    /**
     * Create the row for (groupId, slot) if it does not exist yet, so the two
     * follow-up UPDATEs (claim the tick / record the shown media) can never
     * silently do nothing.
     */
    @Query(
        "INSERT OR IGNORE INTO group_schedule (groupId, slot, lastSwitchAt, lastMediaId) " +
            "VALUES (:groupId, :slot, 0, 0)"
    )
    suspend fun ensureRow(groupId: Long, slot: String)

    /** Claim a tick: the timer dispatched a switch for this group. */
    @Query("UPDATE group_schedule SET lastSwitchAt = :at WHERE groupId = :groupId AND slot = :slot")
    suspend fun updateLastSwitchAt(groupId: Long, slot: String, at: Long)

    /** Record the media a group just put on screen (the per-group cursor). */
    @Query(
        "UPDATE group_schedule SET lastMediaId = :mediaId, lastSwitchAt = :at " +
            "WHERE groupId = :groupId AND slot = :slot"
    )
    suspend fun updateLastMedia(groupId: Long, slot: String, mediaId: Long, at: Long)

    /**
     * Screen-on re-anchor: the time the screen was off does not count towards
     * any per-group interval (see the screen-state receiver), so every group
     * that HAS a schedule restarts its interval from [at]. Rows that never
     * switched (lastSwitchAt = 0) stay "due now" instead of being postponed a
     * whole interval.
     */
    @Query("UPDATE group_schedule SET lastSwitchAt = :at WHERE lastSwitchAt > 0")
    suspend fun reanchorAll(at: Long)
}

/**
 * "最近 N 张不重复" history (see [RecentShown]).
 *
 * [trim] keeps the newest [keep] rows per slot, so the table never grows past
 * the configured window no matter how long the app runs.
 */
@Dao
interface RecentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(row: RecentShown)

    /** The most recently shown ids of [slot], newest first. */
    @Query(
        "SELECT mediaId FROM recent_shown WHERE slot = :slot " +
            "ORDER BY shownAt DESC LIMIT :limit"
    )
    suspend fun recentIds(slot: String, limit: Int): List<Long>

    /**
     * 「最近显示」回滚列表：这一屏最近显示过的媒体，新的在前。
     *
     * 回滚是壁纸应用最容易踩到的痛点（手滑切走一张好图就找不回来了），所以这里
     * 直接连表取出媒体本体；已被删除的媒体行不会出现在结果里（INNER JOIN）。
     * [slot] 传 null 表示两屏合并（历史页用），否则只看该屏。
     *
     * 除了媒体本体，还把 `recent_shown` 自己的两列带出来（[RecentShownEntry]）：
     * 历史页要显示"这块屏 / 多久以前"，而 `WallpaperImage` 里没有这两个字段。
     */
    @Query(
        """
        SELECT r.slot AS slot, r.shownAt AS shownAt, m.* FROM recent_shown r
        INNER JOIN wallpaper_images m ON m.id = r.mediaId
        WHERE (:slot IS NULL OR r.slot = :slot)
        ORDER BY r.shownAt DESC
        LIMIT :limit
        """
    )
    suspend fun recentMedia(slot: String?, limit: Int): List<RecentShownEntry>

    /** Drop everything older than the newest [keep] rows of [slot]. */
    @Query(
        "DELETE FROM recent_shown WHERE slot = :slot AND mediaId NOT IN (" +
            "SELECT mediaId FROM recent_shown WHERE slot = :slot " +
            "ORDER BY shownAt DESC LIMIT :keep)"
    )
    suspend fun trim(slot: String, keep: Int)
}

/**
 * Group-scoped picking: the per-group pacing path switches *within* one group,
 * so it needs the same queries as the screen-wide ones with an extra
 * `groupId = :groupId` filter. Kept as separate queries on purpose - the
 * screen-wide ones (used by the default path) stay untouched.
 */
@Dao
interface GroupPickDao {

    /**
     * groupId -> how many media of one screen this group can actually show
     * (the public group cards use their own query).
     *
     * The group's own 仅图片 / 仅视频 filter ([WallpaperGroup.filterMode]) is
     * part of the count: a group whose remaining media are all excluded by its
     * filter must not win a scheduler tick - it would then have nothing to
     * show and the whole rotation would stall until the next interval.
     */
    @Query(
        """
        SELECT g.id AS groupId, COUNT(i.id) AS mediaCount
        FROM wallpaper_groups g
        LEFT JOIN wallpaper_images i
          ON i.groupId = g.id
         AND (:slot != 'LOCK' OR i.mediaType = 'IMAGE')
         AND ((g.filterMode != 'IMAGE' AND g.filterMode != 'MOTION')
              OR (g.filterMode = 'IMAGE' AND i.mediaType = 'IMAGE')
              OR (g.filterMode = 'MOTION' AND i.mediaType != 'IMAGE'))
        GROUP BY g.id
        """
    )
    suspend fun countsForSlot(slot: String): List<GroupMediaCount>

    @Query("""
        SELECT COUNT(*) FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE')
             OR (:filter = 'FAVORITE' AND isFavorite = 1))
    """)
    suspend fun countInGroup(slot: String, groupId: Long, filter: String = ""): Int

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE'))
        ORDER BY id ASC LIMIT 1
    """)
    suspend fun getFirstInGroup(slot: String, groupId: Long, filter: String = ""): WallpaperImage?

    /** 顺序=新的在前: the group's newest media (see WallpaperGroup.sortOrder). */
    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE'))
        ORDER BY id DESC LIMIT 1
    """)
    suspend fun getNewestInGroup(slot: String, groupId: Long, filter: String = ""): WallpaperImage?

    /** One row of the group by id (weighted draws fetch the media they picked). */
    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId = :groupId AND id = :id
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE')
             OR (:filter = 'FAVORITE' AND isFavorite = 1))
    """)
    suspend fun getInGroupById(
        slot: String,
        groupId: Long,
        id: Long,
        filter: String = ""
    ): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE'))
        AND id > :lastId
        ORDER BY id ASC LIMIT 1
    """)
    suspend fun getSequentialInGroupAfter(
        slot: String,
        groupId: Long,
        lastId: Long,
        filter: String = ""
    ): WallpaperImage?

    /** 顺序=新的在前: the next media BELOW the cursor. */
    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE'))
        AND id < :lastId
        ORDER BY id DESC LIMIT 1
    """)
    suspend fun getSequentialInGroupBefore(
        slot: String,
        groupId: Long,
        lastId: Long,
        filter: String = ""
    ): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE'))
        AND id != :excludeId
        ORDER BY id LIMIT 1 OFFSET :offset
    """)
    suspend fun getRandomInGroupAt(
        slot: String,
        groupId: Long,
        excludeId: Long,
        offset: Int,
        filter: String = ""
    ): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE'))
        AND id != :excludeId
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomInGroupExcluding(
        slot: String,
        groupId: Long,
        excludeId: Long,
        filter: String = ""
    ): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE'))
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomInGroup(slot: String, groupId: Long, filter: String = ""): WallpaperImage?

    @Query("""
        SELECT id FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE'))
        ORDER BY id ASC
    """)
    suspend fun getEnabledIdsInGroup(
        slot: String,
        groupId: Long,
        filter: String = ""
    ): List<Long>

    /** id + 抽中权重（收藏按 :favoriteWeight 加权），供加权随机/洗牌使用。 */
    @Query("""
        SELECT id AS id, (CASE WHEN isFavorite = 1 THEN :favoriteWeight ELSE 1 END) AS weight
        FROM wallpaper_images
        WHERE groupId = :groupId
        AND (:slot != 'LOCK' OR mediaType = 'IMAGE')
        AND (:filter = ''
             OR (:filter = 'IMAGE' AND mediaType = 'IMAGE')
             OR (:filter = 'MOTION' AND mediaType != 'IMAGE')
             OR (:filter = 'FAVORITE' AND isFavorite = 1))
        ORDER BY id ASC
    """)
    suspend fun weightsInGroup(
        slot: String,
        groupId: Long,
        filter: String,
        favoriteWeight: Int
    ): List<MediaWeight>
}

/** One media id with its draw weight (see [GroupPickDao.weightsInGroup]). */
data class MediaWeight(val id: Long, val weight: Int)

suspend fun SettingsDao.getString(key: String, default: String = ""): String {
    return getValue(key) ?: default
}

suspend fun SettingsDao.getBool(key: String, default: Boolean = false): Boolean {
    return getValue(key)?.toBooleanStrictOrNull() ?: default
}

suspend fun SettingsDao.getLong(key: String, default: Long = 0L): Long {
    return getValue(key)?.toLongOrNull() ?: default
}

suspend fun SettingsDao.setString(key: String, value: String) {
    setSetting(AppSettings(key, value))
}

suspend fun SettingsDao.setBool(key: String, value: Boolean) {
    setSetting(AppSettings(key, value.toString()))
}

suspend fun SettingsDao.setLong(key: String, value: Long) {
    setSetting(AppSettings(key, value.toString()))
}
