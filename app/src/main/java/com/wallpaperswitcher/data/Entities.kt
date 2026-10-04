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
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * This group's own switch interval, or 0 to follow the screen's global
     * interval. A per-group interval turns the timer into a small scheduler:
     * each group becomes due at its own `lastSwitchAt + interval`, and the next
     * switch is the group that is due first (see `engine/GroupPacing`).
     *
     * The screen-wide interval still exists (Settings → 切换间隔): it is what a
     * group follows while this is 0, and what keeps the default behaviour of the
     * app byte-for-byte the same until a group opts out.
     */
    val intervalMs: Long = 0L,
    /**
     * LEGACY / inert: a group used to be able to carry its own switch mode, but
     * that kept mixing with the global one (定时切换 honoured it, manual taps did
     * not, and a group on 跟随全局 silently ignored it), so the group screen no
     * longer offers one - the mode is a global setting. The column is kept only
     * so the Room schema still matches the table (dropping it would need a full
     * table rebuild, which would cascade-delete every media row through the FK);
     * MIGRATION_7_8 cleared the stored values and nothing reads it any more.
     */
    val switchMode: String = "",
    /**
     * 时间规则: minutes of day (0..1439) this group may be shown between, or -1
     * for "all day". A window may wrap around midnight ([activeFromMinute] >
     * [activeToMinute]), in which case the group is active from `from` through
     * midnight and from 00:00 until `to`.
     *
     * Both are -1 on upgraded installs and stay that way until the user sets a
     * window, so the default rotation is unchanged.
     */
    val activeFromMinute: Int = -1,
    val activeToMinute: Int = -1,
    /**
     * 时间规则: which weekdays this group may be shown on, as a bitmask with
     * bit 0 = Monday … bit 6 = Sunday. `0` (the default, and what every
     * existing row has) means "every day".
     */
    val activeDays: Int = 0,
    /**
     * 跟随深色模式: "" = 不限, "LIGHT" = 只在浅色模式, "DARK" = 只在深色模式.
     * Lets a user keep a day set and a night set without any timer.
     */
    val activeThemeMode: String = "",
    /**
     * 每分组的筛选: "" = 全部, "IMAGE" = 仅图片, "MOTION" = 仅视频/GIF,
     * "FAVORITE" = 仅收藏. Applied to the switching queries (and to the group
     * grid), so a group can be a "videos only" or "favourites only" rotation.
     */
    val filterMode: String = "",
    /**
     * 每分组的顺序: "" = 加入顺序（旧→新，引擎原本的行为）,
     * "NEWEST" = 新的先（与分组网格的显示顺序一致）.
     */
    val sortOrder: String = ""
)

/**
 * When [groupId] last provided a switch for [slot] ("HOME" / "LOCK").
 *
 * Only used by the per-group pacing (see [WallpaperGroup.intervalMs]): a group
 * with its own interval is due at `lastSwitchAt + itsInterval`, and the timer
 * wakes for whichever group is due first. Groups that follow the global
 * interval never read this - the screen-wide anchor schedules them as before.
 */
@Entity(
    tableName = "group_schedule",
    primaryKeys = ["groupId", "slot"],
    foreignKeys = [ForeignKey(
        entity = WallpaperGroup::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class GroupSchedule(
    val groupId: Long,
    val slot: String,
    val lastSwitchAt: Long = 0L,
    /**
     * The last media this group showed on [slot]. It is the per-group cursor
     * for the group-scoped switching (SEQUENTIAL continues after it, RANDOM and
     * SHUFFLE exclude it) - the screen-wide `LAST_IMAGE_ID` cursor cannot be
     * used there, because other groups advance it too.
     */
    val lastMediaId: Long = 0L
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
    val rotationDegrees: Int = 0,
    /**
     * 收藏: marked by the user (★). 随机/洗牌 can give favourites a higher
     * weight (see SettingsKeys.FAVORITE_BOOST), and a group can be filtered to
     * favourites only ([WallpaperGroup.filterMode]).
     */
    val isFavorite: Boolean = false
)

/**
 * The last media shown on [slot], for "最近 N 张不重复"
 * ([com.wallpaperswitcher.data.SettingsKeys.RECENT_NO_REPEAT]).
 *
 * One row per shown media (REPLACE on re-show), trimmed to the configured N, so
 * the RANDOM pick can exclude the last few pictures without keeping a growing
 * list in the settings blob.
 */
@Entity(tableName = "recent_shown", primaryKeys = ["slot", "mediaId"])
data class RecentShown(
    /** "HOME" or "LOCK" (see WallpaperTarget.SLOT_HOME / SLOT_LOCK). */
    val slot: String,
    val mediaId: Long,
    val shownAt: Long = System.currentTimeMillis()
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
@Entity(tableName = "shuffle_shown", primaryKeys = ["slot", "groupId", "mediaId"])
data class ShuffleShown(
    /** "HOME" or "LOCK" (see WallpaperTarget.SLOT_HOME / SLOT_LOCK). */
    val slot: String,
    /**
     * The group whose deck this row belongs to, or 0 for the screen-wide deck
     * (the default path, which picks across all groups). A group with its own
     * switch mode plays its own pass, and two groups must never consume each
     * other's cards.
     */
    val groupId: Long = 0L,
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
