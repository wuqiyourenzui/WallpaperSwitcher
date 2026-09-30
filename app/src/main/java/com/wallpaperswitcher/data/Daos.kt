package com.wallpaperswitcher.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A (groupId, folderPath) pair recorded by a folder import, for auto-scan. */
data class ScannedFolderPath(val groupId: Long, val folderPath: String)

/** groupId -> media count, for the home screen group cards. */
data class GroupMediaCount(val groupId: Long, val mediaCount: Int)

@Dao
interface WallpaperGroupDao {

    @Query("SELECT * FROM wallpaper_groups ORDER BY createdAt DESC")
    fun getAllGroups(): Flow<List<WallpaperGroup>>

    @Query("SELECT * FROM wallpaper_groups WHERE isEnabled = 1")
    suspend fun getEnabledGroupsSync(): List<WallpaperGroup>

    @Query("SELECT * FROM wallpaper_groups WHERE id = :id")
    suspend fun getGroupById(id: Long): WallpaperGroup?

    @Query("SELECT * FROM wallpaper_groups WHERE id = :id")
    fun getGroupByIdFlow(id: Long): Flow<WallpaperGroup?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: WallpaperGroup): Long

    @Update
    suspend fun update(group: WallpaperGroup)

    /** Change where this group's media may be shown (HOME / LOCK / BOTH). */
    @Query("UPDATE wallpaper_groups SET target = :target WHERE id = :id")
    suspend fun updateTarget(id: Long, target: String)

    @Delete
    suspend fun delete(group: WallpaperGroup)
}

@Dao
interface WallpaperImageDao {

    @Query("SELECT COUNT(*) FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getImageCountByGroup(groupId: Long): Int

    @Query("SELECT id FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getImageIdsByGroup(groupId: Long): List<Long>

    @Query("SELECT uri FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getUrisByGroup(groupId: Long): List<String>

    @Query("SELECT DISTINCT groupId, folderPath FROM wallpaper_images WHERE isFromFolder = 1 AND folderPath != ''")
    suspend fun getScannedFolderPaths(): List<ScannedFolderPath>

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId ORDER BY addedAt DESC, id DESC")
    suspend fun getImagesByGroupSync(groupId: Long): List<WallpaperImage>

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

    @Delete
    suspend fun delete(image: WallpaperImage)

    /**
     * Repair the stored media type of one item (used by the engine's
     * self-heal: media added by an older build could be mis-typed as IMAGE
     * while the file is really a video/GIF, which made it display black).
     */
    @Query("UPDATE wallpaper_images SET mediaType = :mediaType WHERE id = :id")
    suspend fun updateMediaType(id: Long, mediaType: String)

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

    @Query("SELECT mediaId FROM shuffle_shown WHERE slot = :slot")
    suspend fun getShownIds(slot: String): List<Long>

    /**
     * Record one shown media. IGNORE, not REPLACE: the row already existing is
     * the normal case (a re-shown id inside one pass) and must stay a no-op.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShown(rows: List<ShuffleShown>)

    /** Start a new pass: every id shown on [slot] may be picked again. */
    @Query("DELETE FROM shuffle_shown WHERE slot = :slot")
    suspend fun clearSlot(slot: String)
}

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
