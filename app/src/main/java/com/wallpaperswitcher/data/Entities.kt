package com.wallpaperswitcher.data

import androidx.room.*

/**
 * 壁纸分组
 */
@Entity(tableName = "wallpaper_groups")
data class WallpaperGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true,
    val switchIntervalMs: Long = 60_000L, // 默认1分钟
    val switchMode: SwitchMode = SwitchMode.RANDOM,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 壁纸图片
 */
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
    val uri: String,            // 图片 URI
    val displayName: String = "",
    val isVideo: Boolean = false,
    val isFromFolder: Boolean = false,  // 是否来自文件夹批量添加
    val folderPath: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * 应用设置
 */
@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val key: String,
    val value: String
)

/**
 * 壁纸来源类型
 */
enum class WallpaperSourceType {
    SINGLE_IMAGE,   // 单张图片
    FOLDER          // 文件夹
}

/**
 * 切换模式
 */
enum class SwitchMode {
    RANDOM,         // 随机
    SEQUENTIAL,     // 顺序
    SHUFFLE         // 洗牌（随机不重复直到全部轮完）
}

/**
 * 缩放模式
 */
enum class ScaleMode {
    FILL,    // 填充 - 裁剪填满屏幕
    FIT,     // 适应 - 完整显示，可能有黑边
    STRETCH  // 拉伸 - 强制拉伸填满屏幕
}
