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
    fun getEnabledGroups(): Flow<List<WallpaperGroup>>

    @Query("SELECT * FROM wallpaper_groups WHERE isEnabled = 1")
    suspend fun getEnabledGroupsSync(): List<WallpaperGroup>

    @Query("SELECT * FROM wallpaper_groups WHERE isEnabled = 1 AND type = :type")
    suspend fun getEnabledGroupsByType(type: String): List<WallpaperGroup>

    @Query("SELECT * FROM wallpaper_groups WHERE id = :id")
    suspend fun getGroupById(id: Long): WallpaperGroup?

    @Query("SELECT * FROM wallpaper_groups WHERE id = :id")
    fun getGroupByIdFlow(id: Long): Flow<WallpaperGroup?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: WallpaperGroup): Long

    @Update
    suspend fun update(group: WallpaperGroup)

    @Delete
    suspend fun delete(group: WallpaperGroup)

    @Query("DELETE FROM wallpaper_groups WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface WallpaperImageDao {

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId ORDER BY addedAt DESC")
    fun getImagesByGroup(groupId: Long): Flow<List<WallpaperImage>>

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId ORDER BY addedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getImagesByGroupPaged(groupId: Long, limit: Int, offset: Int): List<WallpaperImage>

    @Query("SELECT COUNT(*) FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getImageCountByGroup(groupId: Long): Int

    @Query("SELECT id FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getImageIdsByGroup(groupId: Long): List<Long>

    @Query("SELECT uri FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getUrisByGroup(groupId: Long): List<String>

    @Query("SELECT DISTINCT groupId, folderPath FROM wallpaper_images WHERE isFromFolder = 1 AND folderPath != ''")
    suspend fun getScannedFolderPaths(): List<ScannedFolderPath>

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId ORDER BY addedAt DESC")
    suspend fun getImagesByGroupSync(groupId: Long): List<WallpaperImage>

    @Query("SELECT * FROM wallpaper_images WHERE id = :id")
    suspend fun getImageById(id: Long): WallpaperImage?

    @Query("SELECT * FROM wallpaper_images LIMIT 1")
    suspend fun getFirstImage(): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
        ORDER BY id ASC LIMIT 1
    """)
    suspend fun getFirstFromEnabledGroups(): WallpaperImage?

    // --- Group-specific queries ---

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomImageFromGroup(groupId: Long): WallpaperImage?

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId AND id != :excludeId ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomImageFromGroupExcluding(groupId: Long, excludeId: Long): WallpaperImage?

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId ORDER BY addedAt ASC LIMIT 1 OFFSET :offset")
    suspend fun getSequentialImageFromGroup(groupId: Long, offset: Int): WallpaperImage?

    @Query("SELECT COUNT(*) FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun countByGroup(groupId: Long): Int

    // --- Cross-group queries (for wallpaper switching) ---

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
        ORDER BY id ASC LIMIT 1 OFFSET :offset
    """)
    suspend fun getSequentialImageFromEnabledGroups(offset: Int): WallpaperImage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: WallpaperImage): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<WallpaperImage>)

    @Delete
    suspend fun delete(image: WallpaperImage)

    @Query("DELETE FROM wallpaper_images WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun deleteByGroup(groupId: Long)

    @Query("DELETE FROM wallpaper_images WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // --- Enabled-groups queries ---

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomImageFromEnabledGroups(): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
        AND id != :excludeId
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomImageFromEnabledGroupsExcluding(excludeId: Long): WallpaperImage?

    // Fast random picks: ORDER BY RANDOM() sorts the whole table on every
    // switch, which is slow and power-hungry on large libraries. These use a
    // random OFFSET instead (with the ORDER BY RANDOM() variants kept as a
    // fallback when the offset lands on a deleted row gap).
    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
        ORDER BY id LIMIT 1 OFFSET :offset
    """)
    suspend fun getRandomImageFromEnabledGroupsAt(offset: Int): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
        AND id != :excludeId
        ORDER BY id LIMIT 1 OFFSET :offset
    """)
    suspend fun getRandomImageFromEnabledGroupsExcludingAt(excludeId: Long, offset: Int): WallpaperImage?

    @Query("""
        SELECT COUNT(*) FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
    """)
    suspend fun countByEnabledGroups(): Int

    // --- Type-filtered queries (IMAGE groups only or VIDEO groups only) ---

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND type = :groupType)
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomFromEnabledGroupsByType(groupType: String): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND type = :groupType)
        AND id != :excludeId
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomFromEnabledGroupsByTypeExcluding(groupType: String, excludeId: Long): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND type = :groupType)
        ORDER BY id ASC LIMIT 1 OFFSET :offset
    """)
    suspend fun getSequentialFromEnabledGroupsByType(groupType: String, offset: Int): WallpaperImage?

    @Query("""
        SELECT COUNT(*) FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1 AND type = :groupType)
    """)
    suspend fun countByEnabledGroupsOfType(groupType: String): Int

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
