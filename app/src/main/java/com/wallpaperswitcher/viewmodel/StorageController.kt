package com.wallpaperswitcher.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import com.wallpaperswitcher.data.SettingsDao
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setString
import com.wallpaperswitcher.engine.ClarityMode
import com.wallpaperswitcher.engine.EnhanceMode
import com.wallpaperswitcher.engine.MediaDedupe
import com.wallpaperswitcher.engine.OwnedMediaCleaner
import com.wallpaperswitcher.engine.RssDownloadDir
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.util.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Storage accounting and app-owned media cleanup, split out of
 * `WallpaperViewModel` verbatim. Owns the "存储与流量守门" page's scans, the
 * orphan/TTL sweeps, dedupe, the one-time clarity migration and the file
 * deletion used when downloaded subscription media is removed.
 */
internal class StorageController(
    private val app: Application,
    private val settingsDao: SettingsDao,
) {

    private val tag = "Storage"

    /**
     * 存储占用统计（挂起，进页面时算一次）。
     *
     * 分三类是因为它们的清理方式完全不同：
     *  - `rss` / `online`：应用私有目录里下载的订阅源 / 在线源媒体，**可以**被清；
     *  - 相册 / 文件夹来源：只登记 uri，清不动也不该动。
     */
    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val filesDir = app.filesDir
        fun scan(sub: String): StorageDirUsage {
            val root = File(filesDir, sub)
            if (!root.isDirectory) return StorageDirUsage(sub, 0, 0L)
            var count = 0
            var bytes = 0L
            root.walkTopDown().filter { it.isFile }.forEach {
                count++
                bytes += it.length()
            }
            return StorageDirUsage(sub, count, bytes)
        }
        try {
            StorageUsage(
                rss = scan("rss"),
                online = scan("online"),
                shared = scan("shared"),
                nn = scan("nn"),
            )
        } catch (e: Exception) {
            AppLog.w(tag, "storageUsage failed: ${e.javaClass.simpleName}")
            StorageUsage()
        }
    }

    /**
     * 只统计、不删除：`files/rss`、`files/online` 里数据库已不再引用的文件有多少。
     *
     * 与 [cleanOrphans] 共用同一套判断（引用集合 + 10 分钟保护期），所以界面上
     * 「可清理 280MB」和实际释放量不会对不上。
     */
    suspend fun measureOrphans(): StorageCleanResult = withContext(Dispatchers.IO) {
        try {
            val scan = OwnedMediaCleaner.measureOrphans(app)
            StorageCleanResult(scan.files, scan.bytes)
        } catch (e: Exception) {
            AppLog.w(tag, "measureOrphanMedia failed: ${e.javaClass.simpleName}")
            StorageCleanResult(0, 0L)
        }
    }

    /**
     * 清理"孤儿文件"：数据库里已经没有引用的订阅源 / 在线源下载文件。
     *
     * 复用启动时那次扫描的同一套判断（见 [OwnedMediaCleaner]），先算大小再删，
     * 这样界面能告诉用户"释放了多少"，而不是只报"清理完成"。
     */
    suspend fun cleanOrphans(): StorageCleanResult = withContext(Dispatchers.IO) {
        val before = try {
            OwnedMediaCleaner.measureOrphans(app)
        } catch (e: Exception) {
            AppLog.w(tag, "measureOrphans failed: ${e.javaClass.simpleName}")
            OwnedMediaCleaner.OrphanScan(0, 0L)
        }
        try {
            OwnedMediaCleaner.sweep(app)
        } catch (e: Exception) {
            AppLog.w(tag, "cleanOrphanMedia failed: ${e.javaClass.simpleName}")
        }
        StorageCleanResult(before.files, before.bytes)
    }

    /**
     * 缓存 TTL: run the expired-orphan sweep (no-op while 残留自动清理 = 关闭).
     * Called once when the app comes to the foreground; failures only log.
     */
    suspend fun sweepExpiredDownloads(): Int = withContext(Dispatchers.IO) {
        try {
            val days = settingsDao.getLong(SettingsKeys.RSS_ORPHAN_TTL_DAYS, 0L).toInt()
            if (days <= 0) return@withContext 0
            OwnedMediaCleaner.sweepExpired(app, days).files
        } catch (e: Exception) {
            AppLog.w(tag, "sweepExpiredDownloads failed: ${e.javaClass.simpleName}")
            0
        }
    }

    /**
     * 去重: delete app-owned images whose perceptual hash matches a larger copy
     * in the same group (subscription thumbnails vs originals, repeated shares).
     * The storage page calls this from its own coroutine and renders the result.
     */
    suspend fun dedupeOwnedImages(): MediaDedupe.Result = withContext(Dispatchers.IO) {
        val result = MediaDedupe.sweep(app)
        if (result.removed > 0) {
            WallpaperSwitchService.poke(app)
        }
        result
    }

    /**
     * 清晰度增强的「增强」换成「画质增强（超分）」的一次性迁移：上一版独立开关
     * （4.9.151 之前短暂存在）如果开着、且清晰度还是"自动"，就把清晰度提升为
     * "超分"，然后删掉旧键（之后任何代码都不再读它）。
     */
    suspend fun migrateLegacyQualityEnhance() = withContext(Dispatchers.IO) {
        try {
            // 1) 清晰度：历史值 auto/super/strong/缺失 → 统一写成 "on"（保持开启）。
            val storedClarity = settingsDao.getString(SettingsKeys.CLARITY_MODE, "")
            if (storedClarity != ClarityMode.ON && storedClarity != ClarityMode.OFF) {
                settingsDao.setString(
                    SettingsKeys.CLARITY_MODE,
                    ClarityMode.normalize(storedClarity),
                )
            }
            // 2) 4.9.151 的独立开关（曾把 auto 提升为 super）：已并入清晰度开关。
            settingsDao.deleteKey(SettingsKeys.LEGACY_QUALITY_ENHANCE_ENABLED)
            // 3) 4.9.154 的两个算法开关 → enhance_algo；只在缺失时推导，再删旧键。
            if (settingsDao.getString(SettingsKeys.ENHANCE_ALGO, "").isBlank()) {
                settingsDao.setString(
                    SettingsKeys.ENHANCE_ALGO,
                    EnhanceMode.legacyKey(
                        fsr1 = settingsDao.getBool(
                            SettingsKeys.LEGACY_FSR1_ENHANCE_ENABLED,
                            false,
                        ),
                        anime4k = settingsDao.getBool(
                            SettingsKeys.LEGACY_ANIME4K_ENHANCE_ENABLED,
                            false,
                        ),
                    ),
                )
            }
            settingsDao.deleteKey(SettingsKeys.LEGACY_FSR1_ENHANCE_ENABLED)
            settingsDao.deleteKey(SettingsKeys.LEGACY_ANIME4K_ENHANCE_ENABLED)
        } catch (e: Exception) {
            AppLog.w(tag, "migrateLegacyQualityEnhance failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 订阅源导入的壁纸是下载到应用私有目录的（`files/rss/<源 id>/`、`files/online/`），
     * 删行时必须把文件一起删掉，否则存储会一直涨。相册 / 文件夹来源的 uri 指向用户
     * 自己的文件，**绝不能删**，所以这里只认应用私有目录下的路径。
     */
    suspend fun deleteOwnedFiles(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val root = app.filesDir.absolutePath.trimEnd('/')
        // 用户自选的订阅下载目录（SAF）里的文件也是我们创建的，同样要一起删。
        val tree = try {
            RssDownloadDir.load(app)
        } catch (_: Throwable) {
            ""
        }
        // 一定要在 IO 线程上删：一次选择上千张时，逐个删文件（尤其是 SAF 文档，
        // 每个都是 binder 调用）如果跑在主线程，界面就会卡住。批量并行 + 分批
        // yield，既快又不会把主线程堵死。
        val startedAt = System.currentTimeMillis()
        var removed = 0
        withContext(Dispatchers.IO) {
            val gate = Semaphore(8)
            coroutineScope {
                val jobs = uris.map { uri ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            if (deleteOneOwnedFile(root, tree, uri)) 1 else 0
                        }
                    }
                }
                for (job in jobs) removed += job.await()
            }
        }
        if (removed > 0) {
            AppLog.d(
                "MediaDelete",
                "deleted $removed file(s) in ${System.currentTimeMillis() - startedAt}ms",
            )
        }
    }

    /** 删除一个"应用自己的"文件；不属于应用目录的一律不动，返回是否真的删了。 */
    private fun deleteOneOwnedFile(
        root: String,
        tree: String,
        uri: String,
    ): Boolean {
        return try {
            if (uri.startsWith("content://")) {
                if (tree.isBlank() || !RssDownloadDir.isInside(tree, uri)) {
                    return false
                }
                DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(uri))
            } else {
                if (!uri.startsWith("file://")) return false
                val path = uri.removePrefix("file://")
                if (!path.startsWith("$root/rss/") && !path.startsWith("$root/online/")) {
                    return false
                }
                val file = File(path)
                file.exists() && file.delete()
            }
        } catch (_: Throwable) {
            false
        }
    }
}
