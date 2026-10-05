package com.wallpaperswitcher.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.util.AppLog
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 去重：同一张图的不同尺寸（订阅缩略图 vs 原图、分享两次的副本）只留最大的
 * 那一份。
 *
 * 判断用的是**感知哈希**（dHash，9x8 亮度差分 = 64 bit），只比较：
 *  - 同一个分组内（跨分组的同一张图是合法的：桌面组和锁屏组各留一份）；
 *  - 宽高比接近（2% 容差）；
 *  - 哈希距离 <= [MAX_DISTANCE] 且两份面积不同（面积相同又只有"完全相同的哈希"
 *    才会删，避免把两张相似的图误判成重复）。
 *
 * 只处理应用自己下载的文件（`files/rss`、`files/online`、`files/shared` 下的
 * 图片）：相册 / SAF 的原图不动。纯逻辑部分（dHash 与配对计划）可单测，IO 部分
 * 在 [sweep] 里。
 */
object MediaDedupe {

    private const val TAG = "MediaDedupe"

    /** 感知哈希的比较阈值（64 bit 里允许不同的位数）。 */
    internal const val MAX_DISTANCE = 5

    /** dHash 的采样尺寸（9x8 亮度差分 = 64 bit）。 */
    internal const val HASH_WIDTH = 9
    internal const val HASH_HEIGHT = 8

    data class Candidate(
        val id: Long,
        val groupId: Long,
        val uri: String,
        val width: Int,
        val height: Int,
        val hash: Long,
    ) {
        val area: Long get() = width.toLong() * height.toLong()
    }

    data class Result(val removed: Int, val freedBytes: Long, val failed: Int = 0)

    /** 64 bit dHash：每行比较相邻像素的亮度（左 > 右 = 1）。 */
    internal fun dHash(pixels: IntArray, width: Int, height: Int): Long {
        if (width < 2 || height < 1 || pixels.size < width * height) return 0L
        var hash = 0L
        var bit = 0
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val left = luma(pixels[y * width + x])
                val right = luma(pixels[y * width + x + 1])
                if (left > right) hash = hash or (1L shl bit)
                bit++
                if (bit >= 64) return hash
            }
        }
        return hash
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * 纯配对计划：返回应删掉的候选项（保留面积更大的那份）。
     *
     * 输入顺序无关；同组内按面积从大到小遍历，每个候选项只和已经保留下来的
     * 更大项比较，避免"链式误删"（A≈B、B≈C 不代表 A≈C）。
     */
    internal fun plan(candidates: List<Candidate>): List<Candidate> {
        val drops = ArrayList<Candidate>()
        for ((_, group) in candidates.groupBy { it.groupId }) {
            val kept = ArrayList<Candidate>()
            for (candidate in group.sortedByDescending { it.area }) {
                val keeper = kept.firstOrNull { existing ->
                    sameAspect(existing, candidate) &&
                        hamming(existing.hash, candidate.hash) <= MAX_DISTANCE &&
                        (existing.area > candidate.area ||
                            hamming(existing.hash, candidate.hash) == 0)
                }
                if (keeper != null) drops.add(candidate) else kept.add(candidate)
            }
        }
        return drops
    }

    private fun sameAspect(a: Candidate, b: Candidate): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return false
        val ratioA = a.width.toDouble() / a.height
        val ratioB = b.width.toDouble() / b.height
        return abs(ratioA - ratioB) <= 0.02 * maxOf(ratioA, ratioB)
    }

    internal fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * 扫描应用自己下载的图片并删除重复项。
     *
     * 只解析小尺寸（约 64px）来算哈希，所以几百张图的扫描是秒级；任何一张解不开
     * 就跳过它，绝不因为一张坏图中断整次清理。
     */
    suspend fun sweep(context: Context): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val dao = db.wallpaperImageDao()
            val filesRoot = context.filesDir.absolutePath
            val candidates = ArrayList<Candidate>()
            var failed = 0
            for (row in dao.getAllImagesSync()) {
                if (row.mediaType != MediaTypes.IMAGE) continue
                val path = ownedFilePath(row.uri, filesRoot) ?: continue
                val file = File(path)
                if (!file.isFile) continue
                val hash = hashOf(file)
                if (hash == null) {
                    failed++
                    continue
                }
                candidates.add(
                    Candidate(
                        id = row.id,
                        groupId = row.groupId,
                        uri = row.uri,
                        width = row.width,
                        height = row.height,
                        hash = hash,
                    )
                )
            }
            val drops = plan(candidates)
            if (drops.isEmpty()) return@withContext Result(0, 0L, failed)

            var freed = 0L
            var removed = 0
            try {
                dao.deleteByIds(drops.map { it.id })
                removed = drops.size
            } catch (t: Throwable) {
                // 一次失败就逐条删，绝不让一行坏数据挡住整批。
                for (drop in drops) {
                    try {
                        dao.deleteByIds(listOf(drop.id))
                        removed++
                    } catch (_: Throwable) {
                    }
                }
                AppLog.w(TAG, "batch delete failed: ${t.javaClass.simpleName}")
            }
            // 行删掉之后再删文件：只有当没有任何行还引用它时才删。
            val stillReferenced = try {
                dao.getUrisLike("file://$filesRoot/%").toHashSet()
            } catch (_: Throwable) {
                HashSet()
            }
            for (drop in drops) {
                if (drop.uri in stillReferenced) continue
                val file = File(ownedFilePath(drop.uri, filesRoot) ?: continue)
                val length = file.length()
                if (file.isFile && file.delete()) freed += length
            }
            MediaPick.invalidateEnabledIds()
            AppLog.d(TAG, "dedupe done: removed=$removed freed=${freed / 1024}KB failed=$failed")
            Result(removed, freed, failed)
        } catch (t: Throwable) {
            AppLog.w(TAG, "sweep failed: ${t.javaClass.simpleName}")
            Result(0, 0L, 0)
        }
    }

    /** `file:///…/files/rss/…` -> path, only under the app's own media dirs. */
    private fun ownedFilePath(uri: String, filesRoot: String): String? {
        if (!uri.startsWith("file://")) return null
        val path = uri.removePrefix("file://")
        if (!path.startsWith("$filesRoot/")) return null
        val sub = path.removePrefix("$filesRoot/").substringBefore('/')
        if (sub !in setOf("rss", "online", "shared")) return null
        return path
    }

    /** 解码到 ~64px 再缩成 9x8 求 dHash；解不开返回 null。 */
    private fun hashOf(file: File): Long? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 64 &&
                    bounds.outHeight / (sample * 2) >= 64
                ) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
                val tiny = Bitmap.createScaledBitmap(decoded, HASH_WIDTH, HASH_HEIGHT, true)
                val pixels = IntArray(HASH_WIDTH * HASH_HEIGHT)
                tiny.getPixels(pixels, 0, HASH_WIDTH, 0, 0, HASH_WIDTH, HASH_HEIGHT)
                if (tiny !== decoded) tiny.recycle()
                decoded.recycle()
                dHash(pixels, HASH_WIDTH, HASH_HEIGHT)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
