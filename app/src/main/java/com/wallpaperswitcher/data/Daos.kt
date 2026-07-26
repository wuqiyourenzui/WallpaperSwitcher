package com.wallpaperswitcher.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WallpaperGroupDao {

    @Query("SELECT * FROM wallpaper_groups ORDER BY createdAt DESC")
    fun getAllGroups(): Flow<List<WallpaperGroup>>

    @Query("SELECT * FROM wallpaper_groups WHERE isEnabled = 1")
    fun getEnabledGroups(): Flow<List<WallpaperGroup>>

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

    @Delete
    suspend fun delete(group: WallpaperGroup)

    @Query("DELETE FROM wallpaper_groups WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface WallpaperImageDao {

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId ORDER BY addedAt DESC")
    fun getImagesByGroup(groupId: Long): Flow<List<WallpaperImage>>

    @Query("SELECT * FROM wallpaper_images WHERE groupId = :groupId ORDER BY addedAt DESC")
    suspend fun getImagesByGroupSync(groupId: Long): List<WallpaperImage>

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomImage(): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
        AND id != :excludeId
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomImageExcluding(excludeId: Long): WallpaperImage?

    @Query("""
        SELECT * FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
        ORDER BY addedAt ASC
    """)
    suspend fun getSequentialImages(): List<WallpaperImage>

    @Query("SELECT COUNT(*) FROM wallpaper_images WHERE groupId = :groupId")
    suspend fun getImageCount(groupId: Long): Int

    @Query("""
        SELECT COUNT(*) FROM wallpaper_images
        WHERE groupId IN (SELECT id FROM wallpaper_groups WHERE isEnabled = 1)
    """)
    suspend fun getEnabledImageCount(): Int

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
}

@Dao
interface SettingsDao {

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun getValueFlow(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(setting: AppSettings)

    suspend fun getString(key: String, default: String = ""): String {
        return getValue(key) ?: default
    }

    suspend fun getBool(key: String, default: Boolean = false): Boolean {
        return getValue(key)?.toBooleanStrictOrNull() ?: default
    }

    suspend fun getLong(key: String, default: Long = 0L): Long {
        return getValue(key)?.toLongOrNull() ?: default
    }

    suspend fun setString(key: String, value: String) {
        setSetting(AppSettings(key, value))
    }

    suspend fun setBool(key: String, value: Boolean) {
        setSetting(AppSettings(key, value.toString()))
    }

    suspend fun setLong(key: String, value: Long) {
        setSetting(AppSettings(key, value.toString()))
    }
}
