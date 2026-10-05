package com.wallpaperswitcher.engine

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.util.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 订阅源 / 在线壁纸导入的图片与视频都下载在应用私有目录
 * （`files/rss/<源 id>/`、`files/online/<源 id>/`），分享入库的副本在
 * `files/shared/`。删图片、删分组或换源时如果漏掉文件，这些"孤儿文件"会一直
 * 占着存储（实测一台平板上积了 959 个文件 / 280MB，其中只有 111 个还被分组引用）。
 *
 * 这里做一次启动清理：扫这两个目录，把数据库里已经不存在的文件删掉。
 * 相册 / 文件夹来源的媒体不在这些目录里，永远不会被删。
 */
object OwnedMediaCleaner {

    private const val TAG = "OwnedMediaCleaner"
    private const val MIN_AGE_MS = 10 * 60 * 1000L

    /** 一次扫描的结果：多少个文件、多少字节。 */
    data class OrphanScan(val files: Int, val bytes: Long)

    /**
     * 只统计、不删除：`files/rss`、`files/online` 里数据库已不再引用的文件。
     *
     * 给「存储与流量守门」页用 —— 先让用户看到"能清掉多少"，再决定要不要清；
     * 判断条件与 [sweep] 完全一致（同一套引用集合 + 同一套 10 分钟保护期），
     * 所以"显示可清 280MB"和"实际清掉 280MB"不会对不上。
     */
    suspend fun measureOrphans(
        context: Context,
        /** Files younger than this are never counted (see [MIN_AGE_MS]). */
        minAgeMs: Long = MIN_AGE_MS,
    ): OrphanScan = withContext(Dispatchers.IO) {
        var files = 0
        var bytes = 0L
        try {
            val dao = AppDatabase.getInstance(context).wallpaperImageDao()
            val now = System.currentTimeMillis()
            for (sub in MANAGED_DIRS) {
                val root = File(context.filesDir, sub)
                if (!root.isDirectory) continue
                val referenced = try {
                    dao.getUrisLike("file://${root.absolutePath}%").toHashSet()
                } catch (_: Throwable) {
                    HashSet<String>()
                }
                root.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        if (now - file.lastModified() < minAgeMs) return@forEach
                        if ("file://${file.absolutePath}" in referenced) return@forEach
                        files++
                        bytes += file.length()
                    }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "measureOrphans failed: ${e.javaClass.simpleName}")
        }
        OrphanScan(files, bytes)
    }

    suspend fun sweep(
        context: Context,
        /** Files younger than this are never deleted (see [MIN_AGE_MS]). */
        minAgeMs: Long = MIN_AGE_MS,
    ): OrphanScan = withContext(Dispatchers.IO) {
        var files = 0
        var bytes = 0L
        try {
            val dao = AppDatabase.getInstance(context).wallpaperImageDao()
            for (sub in MANAGED_DIRS) {
                val root = File(context.filesDir, sub)
                if (!root.isDirectory) continue
                val referenced = try {
                    dao.getUrisLike("file://${root.absolutePath}%").toHashSet()
                } catch (_: Throwable) {
                    HashSet<String>()
                }
                var removed = 0
                var freed = 0L
                val now = System.currentTimeMillis()
                root.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        // A download that started moments ago has no row yet.
                        if (now - file.lastModified() < minAgeMs) return@forEach
                        if ("file://${file.absolutePath}" in referenced) return@forEach
                        val length = file.length()
                        if (file.delete()) {
                            removed++
                            freed += length
                        }
                    }
                files += removed
                bytes += freed
                // Drop the now-empty per-source folders.
                root.walkBottomUp()
                    .filter { it.isDirectory && it != root }
                    .forEach { dir ->
                        if (dir.listFiles()?.isEmpty() == true) dir.delete()
                    }
                if (removed > 0) {
                    AppLog.d(
                        TAG,
                        "swept $removed orphan file(s) under files/$sub " +
                            "(${freed / 1024 / 1024}MB freed)",
                    )
                }
            }
            files += sweepDownloadTree(context, dao, minAgeMs)
        } catch (t: Throwable) {
            AppLog.w(TAG, "sweep failed: ${t.javaClass.simpleName}")
        }
        OrphanScan(files, bytes)
    }

    /**
     * 缓存 TTL (设置 → 存储与流量): delete unreferenced downloads that are older
     * than [ttlDays]. Only files this app created are ever touched, and the
     * 10-minute protection window still applies on top.
     */
    suspend fun sweepExpired(context: Context, ttlDays: Int): OrphanScan {
        if (ttlDays <= 0) return OrphanScan(0, 0)
        val age = maxOf(MIN_AGE_MS, ttlDays * 24L * 60 * 60 * 1000)
        return sweep(context, age)
    }

    /**
     * 用户把订阅下载目录设成自选文件夹（SAF）时，也要清掉"行已删、文件还在"的
     * 那批：只认我们自己命名的文件（sha256 十六进制 + 扩展名），用户自己的文件不碰。
     */
    private suspend fun sweepDownloadTree(
        context: Context,
        dao: com.wallpaperswitcher.data.WallpaperImageDao,
        minAgeMs: Long,
    ): Int {
        val treeUri = RssDownloadDir.load(context)
        if (treeUri.isBlank()) return 0
        val tree = try {
            androidx.documentfile.provider.DocumentFile
                .fromTreeUri(context, android.net.Uri.parse(treeUri))
        } catch (_: Throwable) {
            null
        } ?: return 0
        val referenced = try {
            dao.getUrisLike("content://%").toHashSet()
        } catch (_: Throwable) {
            HashSet<String>()
        }
        var removed = 0
        val now = System.currentTimeMillis()
        try {
            for (doc in tree.listFiles()) {
                if (!doc.isFile) continue
                val name = doc.name.orEmpty()
                if (!OWNED_NAME.matches(name)) continue
                if (doc.uri.toString() in referenced) continue
                // A file written moments ago may not have its row yet.
                if (now - doc.lastModified() < minAgeMs) continue
                if (doc.delete()) removed++
            }
        } catch (_: Throwable) {
        }
        if (removed > 0) {
            AppLog.d(TAG, "swept $removed orphan file(s) from the chosen download folder")
        }
        return removed
    }

    /** `<sha256>.<ext>` - the names this app gives its downloads. */
    private val OWNED_NAME = Regex("""[0-9a-f]{64}\.[A-Za-z0-9]{2,5}""")

    /** App-private media directories this cleaner owns (never user files). */
    /**
     * `nn` 是已取消的离线 NN 超分留下的目录（见 §4.9.156）：功能删掉了，但目录
     * 仍在清理范围内，这样设备上已经下载的模型（约 5MB，没有任何媒体行引用）和
     * 已生成但被删行的副本都能被回收。
     */
    private val MANAGED_DIRS = listOf("rss", "online", "shared", "nn")
}
