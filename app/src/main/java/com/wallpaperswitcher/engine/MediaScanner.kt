package com.wallpaperswitcher.engine

import com.wallpaperswitcher.util.AppLog

import com.wallpaperswitcher.util.LogText

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
    val sampleUris: List<String> = emptyList(),
    // Newest DATE_ADDED among the folder's media (seconds since epoch), used
    // for the "时间排序" folder order. 0 when unknown.
    val newestAddedAt: Long = 0
) {
    val totalCount: Int get() = imageCount + videoCount
}

data class FolderMedia(
    val uri: String,
    val displayName: String,
    val mediaType: String,
    /** Stored pixel size (0 when the provider does not report it). */
    val width: Int = 0,
    val height: Int = 0,
    /** EXIF rotation in degrees; 0 = none/unknown. */
    val rotationDegrees: Int = 0
)

/**
 * MediaStore folder scanning shared by the folder picker UI and the periodic
 * auto-scan worker. Scans both images and videos.
 */
object MediaScanner {

    private const val TAG = "MediaScanner"
    private val blockedFolders = setOf("android", ".thumbnails", ".cache", ".trash", "obb")
    // Recursion bound for SAF tree scans (see queryDocumentFolder).
    private const val MAX_SCAN_DEPTH = 24
    /**
     * Result and MediaStore generation of the last completed scan (see
     * [scanFolders]).
     *
     * A folder scan reads one row per media file, so it is only repeated when
     * the media store really changed: [MediaStore.getGeneration] is bumped by
     * the provider on every insert/update/delete, which is exactly "a new file
     * may have appeared in one of the folders this list is built from".
     */
    @Volatile private var lastScanGeneration = Long.MIN_VALUE
    @Volatile private var lastScanFolders: List<ScannedFolder>? = null

    /**
     * MediaStore generation (a counter the provider bumps whenever media is
     * added, changed or removed), or [Long.MIN_VALUE] when the platform cannot
     * provide one (Android 10 and older).
     */
    internal fun currentGeneration(context: Context): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Long.MIN_VALUE
        return try {
            MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
        } catch (_: Exception) {
            Long.MIN_VALUE
        }
    }

    suspend fun scanFolders(context: Context): List<ScannedFolder> = withContext(Dispatchers.IO) {
        // Reuse the previous result while the media store has not changed: the
        // picker opening again - or "重新扫描" being pressed twice - must not walk
        // every media row for an answer that cannot have changed. The caller
        // (WallpaperViewModel.rescanFolders) still reports the count to the user,
        // and an actual insert/update/delete bumps the generation and does
        // trigger a fresh walk.
        val generation = currentGeneration(context)
        if (generation != Long.MIN_VALUE) {
            val cached = lastScanFolders
            if (cached != null && generation == lastScanGeneration) {
                AppLog.d(
                    TAG,
                    "scanFolders: media store unchanged (gen=$generation), " +
                        "reusing ${cached.size} folders"
                )
                return@withContext cached
            }
        }
        try {
            val counts = mutableMapOf<String, IntArray>() // path -> [image, video]
            val names = mutableMapOf<String, String>()
            val samples = mutableMapOf<String, MutableList<String>>()
            val newestAdded = mutableMapOf<String, Long>() // path -> latest date_added

            // Index each MediaStore table and aggregate per folder on the fly.
            fun index(isVideo: Boolean) {
                val contentResolver = context.contentResolver
                val collectionUri = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                // One pass per table: the rows cannot be aggregated by the
                // provider, so this walks them in a single cursor and keeps only
                // per-folder counters, a few sample ids and the newest timestamp
                // - never a list of every media row (huge/OOM on big libraries).
                //
                // A "COUNT(*) ... GROUP BY" short cut (one row per folder) is
                // deliberately NOT used: the MediaStore provider validates every
                // projection entry as a column name and rejects aggregate
                // expressions with `IllegalArgumentException: Invalid column
                // COUNT(*) AS c`. Verified on AOSP 14 and on MIUI with all three
                // spellings - positional query, QUERY_ARG_SQL_GROUP_BY and
                // QUERY_ARG_GROUP_COLUMNS - so the pass below is the only
                // portable implementation. (An OEM whose provider does allow it
                // would only be faster, not more correct.)
                val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.RELATIVE_PATH,
                        MediaStore.Images.Media.DATE_ADDED
                    )
                } else {
                    @Suppress("DEPRECATION")
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.DATE_ADDED
                    )
                }
                contentResolver.query(collectionUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val pathCol = cursor.getColumnIndex(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            MediaStore.Images.Media.RELATIVE_PATH
                        else MediaStore.Images.Media.DATA
                    )
                    val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        try {
                            val id = cursor.getLong(idCol)
                            val rawPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                            if (rawPath.isNullOrBlank()) continue
                            val folderKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                rawPath.trimEnd('/')
                            } else {
                                @Suppress("DEPRECATION")
                                rawPath.substringBeforeLast('/')
                            }
                            if (folderKey.isEmpty()) continue
                            val c = counts.getOrPut(folderKey) { IntArray(2) }
                            c[if (isVideo) 1 else 0]++
                            if (addedCol >= 0) {
                                val added = cursor.getLong(addedCol)
                                val old = newestAdded[folderKey]
                                if (old == null || added > old) newestAdded[folderKey] = added
                            }
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

            val result = counts.map { (path, c) ->
                ScannedFolder(
                    path = path,
                    name = names[path] ?: path,
                    imageCount = c[0],
                    videoCount = c[1],
                    sampleUris = samples[path] ?: emptyList(),
                    newestAddedAt = newestAdded[path] ?: 0L
                )
            }
                .filter { it.totalCount >= 1 }
                .filter { f -> f.path.split("/").none { it.lowercase() in blockedFolders } }
                .sortedByDescending { it.totalCount }
            AppLog.d(TAG, "scanFolders: found ${result.size} folders")
            // Remembered against the generation read BEFORE the walk: a change
            // that happened while scanning means the stored generation is older
            // than the data, so the next scan walks again instead of trusting a
            // list that may already be missing a file.
            if (result.isNotEmpty() && generation != Long.MIN_VALUE) {
                lastScanFolders = result
                lastScanGeneration = generation
            }
            result
        } catch (e: Throwable) {
            AppLog.e(TAG, "scanFolders failed", e)
            emptyList()
        }
    }

    /** All images + videos inside a MediaStore folder (images first, then videos). */
    suspend fun queryFolderMedia(context: Context, folderPath: String): List<FolderMedia> =
        withContext(Dispatchers.IO) {
            val media = queryByFolder(context, folderPath, isVideo = false) +
                queryByFolder(context, folderPath, isVideo = true)
            AppLog.d(TAG, "queryFolderMedia: ${LogText.folder(folderPath)} -> ${media.size} items")
            media
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
                // Bound the recursion depth: a pathological tree (1000+ levels
                // of nesting) would otherwise StackOverflow on the IO thread.
                fun scanDir(dir: DocumentFile, depth: Int) {
                    if (depth > MAX_SCAN_DEPTH) return
                    val files = try { dir.listFiles() } catch (_: Exception) { emptyArray() }
                    for (f in files) {
                        try {
                            if (f.isDirectory) {
                                scanDir(f, depth + 1)
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
                scanDir(docFile, 0)
                AppLog.d(TAG, "queryDocumentFolder: ${LogText.short(treeUri)} -> ${result.size} items")
                result
            } catch (e: Throwable) {
                AppLog.e(TAG, "queryDocumentFolder failed: ${LogText.short(treeUri)}", e)
                emptyList()
            }
        }

    /** See [MediaTypes.isSupportedName]; kept here for the scanner's callers. */
    fun isSupportedMedia(name: String): Boolean = MediaTypes.isSupportedName(name)

    /** See [MediaTypes.fromName]; kept here for the scanner's callers. */
    fun detectMediaType(name: String): String = MediaTypes.fromName(name)

    /**
     * Escape SQL LIKE wildcards so a folder path is matched literally.
     * Backslash must be escaped first (it is the ESCAPE character itself).
     */
    internal fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun queryByFolder(context: Context, folderPath: String, isVideo: Boolean): List<FolderMedia> {
        val contentResolver = context.contentResolver
        val collectionUri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            // Free decode metadata: the same query already touches these rows,
            // so storing them costs NOTHING extra and saves two media-library
            // reads per later decode (bounds pass + EXIF pass).
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? ESCAPE '\\' AND ${MediaStore.Images.Media.SIZE} > 0"
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE ? ESCAPE '\\' AND ${MediaStore.Images.Media.SIZE} > 0"
        }
        val result = mutableListOf<FolderMedia>()
        contentResolver.query(
            // "Folder/%" instead of "Folder%": the old prefix match also pulled
            // sibling folders whose names merely start with the same text
            // (e.g. importing "DCIM/Camera" also imported "DCIM/Camera2").
            // ESCAPE '\' + escapeLike() so a folder name containing LIKE
            // wildcards ('_' or '%') cannot match unrelated folders.
            collectionUri, projection, selection, arrayOf("${escapeLike(folderPath)}/%"), null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val orientationCol = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            while (cursor.moveToNext()) {
                try {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "untitled"
                    val uri = Uri.withAppendedPath(collectionUri, id.toString()).toString()
                    // MediaStore folders also contain GIFs: detect them by the
                    // display-name extension so they enter the animated-GIF
                    // path instead of being treated as static IMAGE rows.
                    val mediaType = if (isVideo) MediaTypes.VIDEO else detectMediaType(name)
                    result.add(
                        FolderMedia(
                            uri = uri,
                            displayName = name,
                            mediaType = mediaType,
                            width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                            height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                            rotationDegrees =
                                if (orientationCol >= 0) cursor.getInt(orientationCol) else 0
                        )
                    )
                } catch (_: Exception) { continue }
            }
        }
        return result
    }
}
