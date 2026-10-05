package com.wallpaperswitcher.engine

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分享入库: a media stream another app handed us via ACTION_SEND.
 *
 * The URI grant of a share intent only lives as long as the receiving activity,
 * so the bytes are copied into `files/shared/<sha256>.<ext>` right away and the
 * row points at that app-private file - exactly like the subscription importer
 * does for `files/rss/<sourceId>/`. The directory is covered by
 * [OwnedMediaCleaner] (and therefore by the storage page / the TTL sweep), so
 * files whose rows were deleted do not leak.
 */
object SharedMediaImporter {

    private const val TAG = "SharedMediaImporter"

    /** Shared videos can be large; the app's own RssMediaImporter caps at 30MB. */
    private const val MAX_BYTES = 200L * 1024 * 1024

    data class Report(val added: Int, val failed: Int, val skipped: Int)

    suspend fun importMedia(
        context: Context,
        uris: List<Uri>,
        groupId: Long,
        favorite: Boolean,
    ): Report = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext Report(0, 0, 0)
        val db = AppDatabase.getInstance(context)
        val imageDao = db.wallpaperImageDao()
        val existing = try {
            imageDao.getUrisByGroup(groupId).toHashSet()
        } catch (_: Throwable) {
            HashSet()
        }
        val dir = File(context.filesDir, "shared")
        if (!dir.exists() && !dir.mkdirs()) return@withContext Report(0, uris.size, 0)

        var failed = 0
        var skipped = 0
        val rows = ArrayList<WallpaperImage>(uris.size)
        for (uri in uris.distinct()) {
            try {
                val mime = context.contentResolver.getType(uri).orEmpty()
                val name = queryDisplayName(context.contentResolver, uri)
                    ?: uri.lastPathSegment.orEmpty().ifBlank { "shared" }
                val supported = mime.startsWith("image/", ignoreCase = true) ||
                    mime.startsWith("video/", ignoreCase = true) ||
                    MediaTypes.isSupportedName(name)
                if (!supported) {
                    // Unknown MIME and a name without a known extension: refuse
                    // instead of storing something the engine cannot decode.
                    failed++
                    continue
                }
                val mediaType = MediaTypes.fromMimeOrName(mime, name)
                val copy = copyIntoShared(context, uri, name)
                if (copy == null) {
                    failed++
                    continue
                }
                val fileUri = "file://${copy.absolutePath}"
                if (!existing.add(fileUri)) {
                    skipped++
                    continue
                }
                val width: Int
                val height: Int
                if (mediaType == MediaTypes.IMAGE) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(copy.path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        copy.delete()
                        existing.remove(fileUri)
                        failed++
                        continue
                    }
                    width = bounds.outWidth
                    height = bounds.outHeight
                } else {
                    width = 0
                    height = 0
                }
                rows.add(
                    WallpaperImage(
                        groupId = groupId,
                        uri = fileUri,
                        displayName = name,
                        mediaType = mediaType,
                        isFromFolder = false,
                        folderPath = "shared",
                        width = width,
                        height = height,
                        isFavorite = favorite,
                    )
                )
            } catch (t: Throwable) {
                AppLog.w(
                    TAG,
                    "share import failed: ${t.javaClass.simpleName}: ${t.message?.take(120).orEmpty()}"
                )
                failed++
            }
        }

        var added = 0
        if (rows.isNotEmpty()) {
            try {
                imageDao.insertAll(rows)
                added = rows.size
            } catch (_: Throwable) {
                // One bad row must not lose the whole batch.
                for (row in rows) {
                    try {
                        imageDao.insert(row)
                        added++
                    } catch (_: Throwable) {
                        failed++
                    }
                }
            }
        }
        if (added > 0) MediaPick.invalidateEnabledIds()
        AppLog.d(TAG, "share import done: added=$added failed=$failed skipped=$skipped")
        Report(added, failed, skipped)
    }

    /**
     * Streams [uri] into `files/shared/<sha256>.<ext>` (content-hash named, so
     * sharing the same file twice reuses one copy) and returns the file.
     */
    private fun copyIntoShared(context: Context, uri: Uri, displayName: String): File? {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extension = extensionFor(mime, displayName)
        val temp = File(context.filesDir, "shared/share_${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_BYTES) throw IllegalStateException("too large")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return null
        } catch (t: Throwable) {
            temp.delete()
            AppLog.w(TAG, "copy failed: ${t.javaClass.simpleName}")
            return null
        }
        if (copied <= 0L) {
            temp.delete()
            return null
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val target = File(context.filesDir, "shared/$hash.$extension")
        if (temp.renameTo(target)) return target
        return try {
            temp.copyTo(target, overwrite = true)
            temp.delete()
            target
        } catch (t: Throwable) {
            temp.delete()
            AppLog.w(TAG, "publish failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** File extension for the private copy (mime first, then the given name). */
    private fun extensionFor(mime: String, name: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (fromName.matches(Regex("[a-z0-9]{2,5}"))) return fromName
        return when (mime.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/heic", "image/heif" -> "heic"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            else -> when {
                mime.startsWith("image/", ignoreCase = true) ->
                    mime.substringAfter('/').lowercase().take(5)
                mime.startsWith("video/", ignoreCase = true) ->
                    mime.substringAfter('/').lowercase().take(5)
                else -> "bin"
            }
        }
    }
}
