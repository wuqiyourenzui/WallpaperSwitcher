package com.wallpaperswitcher.data

import androidx.room.*

/*
 * NOTE: `type` is a legacy column from the "one group = one media kind" design.
 * The app now stores images and videos in the same group, and nothing reads the
 * value any more - it is kept only so the Room schema keeps matching the
 * existing table (dropping a column would need a full table rebuild, which
 * would cascade-delete every media row through the FK).
 */
@Entity(tableName = "wallpaper_groups")
data class WallpaperGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true,
    val type: String = "IMAGE", // legacy, see the note above
    // Where this group's media may be shown (Paperize-style "dual screen"
    // support): "HOME", "LOCK" or "BOTH". The home screen and the lock screen
    // are switched independently, each from the groups that target it.
    val target: String = "BOTH",
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
    val addedAt: Long = System.currentTimeMillis(),
    // Decode metadata, so re-decoding this media costs ONE media-library read
    // instead of three (bounds pass + decode + EXIF). Filled for free by the
    // MediaStore folder scan (WIDTH/HEIGHT/ORIENTATION columns) and learned on
    // the first decode otherwise (0 = not known yet).
    val width: Int = 0,
    val height: Int = 0,
    /** EXIF rotation in degrees (0/90/180/270); 0 = none or unknown. */
    val rotationDegrees: Int = 0
)

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val key: String,
    val value: String
)

/**
 * One media id that the SHUFFLE deck of [slot] has already shown.
 *
 * Kept as one row per shown media instead of a comma-separated id list in the
 * `app_settings` blob: that list grew to the whole deck (a 38k library is a
 * ~230KB string) and every switch re-read, rebuilt and re-wrote it entirely - a
 * pure flash-wear + CPU cost that scaled with the library. A row insert is
 * constant size, and deleting a deck (`clearSlot`) is one indexed DELETE.
 *
 * Outlives the process on purpose: the deck must not restart (and repeat media)
 * because the wallpaper engine was killed in the background.
 */
@Entity(tableName = "shuffle_shown", primaryKeys = ["slot", "mediaId"])
data class ShuffleShown(
    /** "HOME" or "LOCK" (see WallpaperTarget.SLOT_HOME / SLOT_LOCK). */
    val slot: String,
    val mediaId: Long
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

