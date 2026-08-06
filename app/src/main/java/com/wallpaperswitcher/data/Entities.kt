package com.wallpaperswitcher.data

import androidx.room.*

@Entity(tableName = "wallpaper_groups")
data class WallpaperGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true,
    val type: String = "IMAGE", // "IMAGE" or "VIDEO"
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "wallpaper_images",
    foreignKeys = [ForeignKey(
        entity = WallpaperGroup::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("groupId")]
)
data class WallpaperImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val uri: String,
    val displayName: String = "",
    val mediaType: String = "IMAGE",
    val isFromFolder: Boolean = false,
    val folderPath: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val key: String,
    val value: String
)

enum class SwitchMode {
    RANDOM,
    SEQUENTIAL,
    SHUFFLE
}

enum class ScaleMode {
    FILL,
    FIT,
    STRETCH
}

enum class MediaType {
    IMAGE,
    VIDEO,
    GIF
}
