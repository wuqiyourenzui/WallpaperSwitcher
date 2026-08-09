package com.wallpaperswitcher.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
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

            fun add(folderKey: String, folderName: String, mediaUri: String, isVideo: Boolean) {
                val c = counts.getOrPut(folderKey) { IntArray(2) }
                c[if (isVideo) 1 else 0]++
                names.putIfAbsent(folderKey, folderName)
                val list = samples.getOrPut(folderKey) { mutableListOf() }
                if (list.size < 3) list.add(mediaUri)
            }

            indexFolders(context, isVideo = false).forEach { (key, name, uri) -> add(key, name, uri, false) }
            indexFolders(context, isVideo = true).forEach { (key, name, uri) -> add(key, name, uri, true) }

            counts.map { (path, c) ->
                ScannedFolder(
                    path = path,
                    name = names[path] ?: path,
                    imageCount = c[0],
                    videoCount = c[1],
                    sampleUris = samples[path] ?: emptyList()
                )
            }
                .filter { it.totalCount >= 2 }
                .filter { f -> f.path.split("/").none { it.lowercase() in blockedFolders } }
                .sortedByDescending { it.totalCount }
        } catch (e: Exception) {
            Log.e(TAG, "scanFolders failed", e)
            emptyList()
        }
    }

    /** Returns (folderKey, folderName, mediaUri) for every media item. */
    private fun indexFolders(context: Context, isVideo: Boolean): List<Triple<String, String, String>> {
        val contentResolver = context.contentResolver
        val collectionUri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = if (useRelativePath) {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DISPLAY_NAME
            )
        } else {
            @Suppress("DEPRECATION")
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME
            )
        }
        val result = mutableListOf<Triple<String, String, String>>()
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
                    val uri = Uri.withAppendedPath(collectionUri, id.toString()).toString()
                    result.add(Triple(folderKey, folderKey.substringAfterLast('/').ifEmpty { "Root" }, uri))
                } catch (_: Exception) { continue }
            }
        }
        return result
    }

    /** All images + videos inside a MediaStore folder (images first, then videos). */
    suspend fun queryFolderMedia(context: Context, folderPath: String): List<FolderMedia> =
        withContext(Dispatchers.IO) {
            queryByFolder(context, folderPath, isVideo = false) +
                queryByFolder(context, folderPath, isVideo = true)
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
