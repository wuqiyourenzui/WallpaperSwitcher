package com.wallpaperswitcher.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A folder on device that contains images and/or videos (from MediaStore).
 */
data class ScannedFolder(
    val path: String,
    val name: String,
    val imageCount: Int,
    val videoCount: Int = 0,
    val sampleUris: List<String> = emptyList()
) {
    val totalCount: Int get() = imageCount + videoCount
}

data class FolderMedia(
    val uri: String,
    val displayName: String,
    val mediaType: String
)

/**
 * MediaStore folder scanning shared by the folder picker UI and the periodic
 * auto-scan worker. Scans both images and videos.
 */
object MediaScanner {

    private const val TAG = "MediaScanner"
    private val blockedFolders = setOf("android", ".thumbnails", ".cache", ".trash", "obb")

    suspend fun scanFolders(context: Context): List<ScannedFolder> = withContext(Dispatchers.IO) {
        try {
            val counts = mutableMapOf<String, IntArray>() // path -> [image, video]
            val names = mutableMapOf<String, String>()
            val samples = mutableMapOf<String, MutableList<String>>()

            // Index each MediaStore table and aggregate per folder on the fly:
            // never build a full list of every media row, which can be huge and
            // OOM on devices with large libraries.
            fun index(isVideo: Boolean) {
                val contentResolver = context.contentResolver
                val collectionUri = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                val projection = if (useRelativePath) {
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
                }
                contentResolver.query(collectionUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val pathCol = cursor.getColumnIndex(
                        if (useRelativePath) MediaStore.Images.Media.RELATIVE_PATH
                        else MediaStore.Images.Media.DATA
                    )
                    while (cursor.moveToNext()) {
                        try {
                            val id = cursor.getLong(idCol)
                            val rawPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                            if (rawPath.isNullOrBlank()) continue
                            val folderKey = if (useRelativePath) {
                                rawPath.trimEnd('/')
                            } else {
                                @Suppress("DEPRECATION")
                                rawPath.substringBeforeLast('/')
                            }
                            if (folderKey.isEmpty()) continue
                            val c = counts.getOrPut(folderKey) { IntArray(2) }
                            c[if (isVideo) 1 else 0]++
                            names.putIfAbsent(folderKey, folderKey.substringAfterLast('/').ifEmpty { "Root" })
                            val list = samples.getOrPut(folderKey) { mutableListOf() }
                            if (list.size < 3) {
                                list.add(Uri.withAppendedPath(collectionUri, id.toString()).toString())
                            }
                        } catch (_: Exception) { continue }
                    }
                }
            }

            index(false)
            index(true)

            counts.map { (path, c) ->
                ScannedFolder(
                    path = path,
                    name = names[path] ?: path,
                    imageCount = c[0],
                    videoCount = c[1],
                    sampleUris = samples[path] ?: emptyList()
                )
            }
                .filter { it.totalCount >= 1 }
                .filter { f -> f.path.split("/").none { it.lowercase() in blockedFolders } }
                .sortedByDescending { it.totalCount }
        } catch (e: Throwable) {
            Log.e(TAG, "scanFolders failed", e)
            emptyList()
        }
    }

    /** All images + videos inside a MediaStore folder (images first, then videos). */
    suspend fun queryFolderMedia(context: Context, folderPath: String): List<FolderMedia> =
        withContext(Dispatchers.IO) {
            queryByFolder(context, folderPath, isVideo = false) +
                queryByFolder(context, folderPath, isVideo = true)
        }

    /**
     * Recursively scan a SAF DocumentFile tree (the URI recorded by the system
     * folder picker) for supported images/videos/GIFs.
     */
    suspend fun queryDocumentFolder(context: Context, treeUri: String): List<FolderMedia> =
        withContext(Dispatchers.IO) {
            try {
                val docFile = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext emptyList()
                if (!docFile.isDirectory) return@withContext emptyList()
                val result = mutableListOf<FolderMedia>()
                fun scanDir(dir: DocumentFile) {
                    val files = try { dir.listFiles() } catch (_: Exception) { emptyArray() }
                    for (f in files) {
                        try {
                            if (f.isDirectory) {
                                scanDir(f)
                            } else if (f.isFile && isSupportedMedia(f.name ?: "")) {
                                result.add(
                                    FolderMedia(
                                        uri = f.uri.toString(),
                                        displayName = f.name ?: "untitled",
                                        mediaType = detectMediaType(f.name ?: "")
                                    )
                                )
                            }
                        } catch (_: Exception) { continue }
                    }
                }
                scanDir(docFile)
                result
            } catch (e: Throwable) {
                Log.e(TAG, "queryDocumentFolder failed: $treeUri", e)
                emptyList()
            }
        }

    fun isSupportedMedia(name: String): Boolean {
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "mp4", "mkv", "webm", "avi", "mov", "3gp")
    }

    fun detectMediaType(name: String): String {
        val ext = name.lowercase().substringAfterLast('.', "")
        return when (ext) {
            "gif" -> "GIF"
            "mp4", "mkv", "webm", "avi", "mov", "3gp" -> "VIDEO"
            else -> "IMAGE"
        }
    }

    private fun queryByFolder(context: Context, folderPath: String, isVideo: Boolean): List<FolderMedia> {
        val contentResolver = context.contentResolver
        val collectionUri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.SIZE} > 0"
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.SIZE} > 0"
        }
        val result = mutableListOf<FolderMedia>()
        contentResolver.query(
            collectionUri, projection, selection, arrayOf("$folderPath%"), null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                try {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "untitled"
                    val uri = Uri.withAppendedPath(collectionUri, id.toString()).toString()
                    val mediaType = if (isVideo) "VIDEO" else "IMAGE"
                    result.add(FolderMedia(uri, name, mediaType))
                } catch (_: Exception) { continue }
            }
        }
        return result
    }
}
