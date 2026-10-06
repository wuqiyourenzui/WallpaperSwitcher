package com.wallpaperswitcher.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 在线壁纸源: Bing 每日图 / 指定 URL / WebDAV 目录（功能已下线，见技术文档 §4.9.93）。
 *
 * 数据表与已下载的文件保留，避免升级时误删用户已有壁纸；调度、同步与密码
 * 读写逻辑随功能一并删除，历史 [passwordCipher] 密文不再被任何代码读取。
 */
@Entity(tableName = "online_sources")
data class OnlineSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [TYPE_BING], [TYPE_URL] or [TYPE_WEBDAV]. */
    val type: String = TYPE_BING,
    val name: String = "",
    val groupId: Long = 0L,
    /** TYPE_URL: the direct image URL (may point at a "latest image" endpoint). */
    val url: String = "",
    /** TYPE_WEBDAV: the collection URL, e.g. `https://nas.example.com/dav/photos`. */
    val webdavUrl: String = "",
    /** TYPE_WEBDAV: optional sub-directory appended to [webdavUrl]. */
    val webdavPath: String = "",
    /** TYPE_WEBDAV: optional HTTP Basic user. */
    val username: String = "",
    /** TYPE_WEBDAV: AES/GCM ciphertext of the password (empty = none). */
    val passwordCipher: String = "",
    val enabled: Boolean = true,
    /** Fetch interval in minutes, clamped to [MIN_INTERVAL_MINUTES]..[MAX_INTERVAL_MINUTES]. */
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /** Only fetch on unmetered (Wi-Fi / Ethernet) networks. */
    val wifiOnly: Boolean = true,
    /** Only fetch while the device is charging. */
    val chargingOnly: Boolean = false,
    /** Keep at most this many downloaded items (0 = keep everything). */
    val keepCount: Int = DEFAULT_KEEP_COUNT,
    val lastFetchAt: Long = 0L,
    /** Human-readable result of the last run (shown in the source list). */
    val lastResult: String = "",
    val lastErrorAt: Long = 0L,
    /** TYPE_URL: HTTP validators so an unchanged URL is not downloaded again. */
    val etag: String = "",
    val lastModified: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 美人图 auto mode: how many album pages to walk per album (3 images each).
     * Clamped to [MIN_PAGES_PER_ALBUM]..[MAX_PAGES_PER_ALBUM].
     */
    val pagesPerAlbum: Int = DEFAULT_PAGES_PER_ALBUM,
    /** New images one sync may download, for every source type. */
    val maxPerRun: Int = DEFAULT_MAX_PER_RUN,
    /**
     * 美人图 manual mode: '\n'-separated full-size image URLs the user ticked
     * in the picker. Non-empty = download exactly these (up to [maxPerRun] per
     * sync) instead of scraping the newest albums; empty = auto mode.
     */
    val selectedImages: String = "",
) {
    companion object {
        const val TYPE_BING = "BING"
        const val TYPE_URL = "URL"
        const val TYPE_WEBDAV = "WEBDAV"
        /** 美人图 (meirentu.club): a listing page whose albums are scraped. */
        const val TYPE_MEIRENTU = "MEIRENTU"
        /** 设置里的内置在线壁纸源（见 engine.OnlineBuiltins）。 */
        const val TYPE_NASA_APOD = "NASA_APOD"
        const val TYPE_WIKIMEDIA = "WIKIMEDIA"
        const val TYPE_NETBIAN = "NETBIAN"
        const val TYPE_IOLIU = "IOLIU"

        /** Bing changes once a day, so the default is one fetch per day. */
        const val DEFAULT_INTERVAL_MINUTES = 24 * 60
        /** WorkManager's minimum periodic interval; also the user-facing floor. */
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 7 * 24 * 60
        const val DEFAULT_KEEP_COUNT = 30
        const val MAX_KEEP_COUNT = 2000
        const val DEFAULT_PAGES_PER_ALBUM = 2
        const val MIN_PAGES_PER_ALBUM = 1
        const val MAX_PAGES_PER_ALBUM = 10
        const val DEFAULT_MAX_PER_RUN = 8
        const val MIN_MAX_PER_RUN = 1
        const val MAX_MAX_PER_RUN = 50
    }
}

/**
 * One downloaded item of a source. The composite key is
 * `(sourceId, remoteKey)` where remoteKey is the remote identity:
 *
 *  - Bing: the image date (`enddate`, e.g. `20261002`);
 *  - URL: `sha256:<content hash>` (the same URL may serve changing content);
 *  - WebDAV: the file's `href`.
 *
 * The row also survives the user deleting the media row: keeping the remoteKey
 * means the next sync does NOT download the same remote file again.
 */
@Entity(
    tableName = "online_items",
    primaryKeys = ["sourceId", "remoteKey"],
    foreignKeys = [ForeignKey(
        entity = OnlineSource::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class OnlineItem(
    val sourceId: Long,
    val remoteKey: String,
    val contentHash: String = "",
    /** `wallpaper_images.id` of the inserted media (0 = media row gone). */
    val imageId: Long = 0L,
    /** Absolute path of the downloaded file (empty = file already removed). */
    val filePath: String = "",
    val fetchedAt: Long = System.currentTimeMillis(),
)
