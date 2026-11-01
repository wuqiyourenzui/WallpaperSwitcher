package com.wallpaperswitcher.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.wallpaperswitcher.util.AppLog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 网盘视频的**远端首帧缩略图**。
 *
 * 为什么不交给 Coil 的 `VideoFrameDecoder`：它需要把整段视频读完才能解帧，网盘里一个
 * 4K 视频就是几百 MB —— 滑过一屏就等于把整目录下了一遍。这里用
 * `MediaMetadataRetriever.setDataSource(url, headers)`，走 HTTP Range 只取解帧需要的
 * 那几个字节（服务端支持 Range 时），代价与文件大小基本无关。
 *
 * 缓存两级：内存 LRU（16 张）+ 磁盘 JPEG（`cache/online_thumbs/<sha1>.jpg`），所以来回
 * 滑动不重复解帧；磁盘在 cacheDir 里，系统空间紧张时会被回收，不影响壁纸库。
 *
 * 并发限 3：MediaMetadataRetriever 每实例带一个解码器，同时开太多在低端机上会直接
 * `MediaServer` 超时。
 */
object OnlineVideoThumbs {

    private const val TAG = "OnlineVideoThumbs"
    private const val MAX_CONCURRENT = 3
    private const val THUMB_MAX_PX = 512
    private const val DISK_DIR = "online_thumbs"

    private val permits = Semaphore(MAX_CONCURRENT)
    private val memory = object : LruCache<String, Bitmap>(16) {}

    /** 取首帧（约 10% 处，避开开头常见的黑场）；失败返回 null（UI 显示图标）。 */
    suspend fun load(
        context: Context,
        url: String,
        headers: Map<String, String>,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val key = sha1(url)
        memory.get(key)?.let { return@withContext it }
        val file = File(File(context.cacheDir, DISK_DIR), "$key.jpg")
        if (file.exists() && file.length() > 0L) {
            val cached = try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Throwable) {
                null
            }
            if (cached != null) {
                memory.put(key, cached)
                return@withContext cached
            }
        }
        permits.withPermit {
            val frame = try {
                extract(url, headers)
            } catch (t: Throwable) {
                AppLog.w(TAG, "extract failed: ${t.javaClass.simpleName}")
                null
            } ?: return@withPermit
            memory.put(key, frame)
            try {
                file.parentFile?.mkdirs()
                file.outputStream().use { out ->
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "cache write failed: ${t.javaClass.simpleName}")
            }
        }
        memory.get(key)
    }

    private fun extract(url: String, headers: Map<String, String>): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, headers)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // 10% 处：大部分片子开头是黑场或 logo，取首帧会得到一张全黑图。
            val atUs = if (durationMs > 0L) durationMs * 1000L / 10L else 0L
            val frame = retriever.getFrameAtTime(
                atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0L) ?: return null
            return scaleDown(frame)
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= THUMB_MAX_PX) return bitmap
        val ratio = THUMB_MAX_PX.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    private fun sha1(value: String): String = try {
        Hashes.sha1Hex(value)
    } catch (_: Throwable) {
        value.hashCode().toString()
    }
}
