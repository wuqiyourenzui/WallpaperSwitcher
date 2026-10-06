package com.wallpaperswitcher.viewmodel

import android.app.Application
import android.net.Uri
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsDao
import com.wallpaperswitcher.data.WallpaperGroupDao
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.WallpaperImageDao
import com.wallpaperswitcher.engine.MediaPick
import com.wallpaperswitcher.engine.MediaScanner
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.ScannedFolder
import com.wallpaperswitcher.engine.clearMediaCursors
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.LogText
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * 媒体库的导入/删除/扫描编排，从 `WallpaperViewModel` 拆分出来（逻辑逐字搬移）。
 * 只有 UI 状态与分组窗口留在 ViewModel，这里通过 [onLibraryChanged] 通知它刷新。
 */
internal class MediaLibraryController(
    private val app: Application,
    private val db: AppDatabase,
    private val imageDao: WallpaperImageDao,
    private val groupDao: WallpaperGroupDao,
    private val settingsDao: SettingsDao,
    private val storage: StorageController,
    private val scope: CoroutineScope,
    private val onToast: suspend (String) -> Unit,
    private val currentGroupId: () -> Long?,
    private val onLibraryChanged: suspend (Long) -> Unit,
) {

    private val tag = "MediaLibrary"

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun guardedWrite(errorMessage: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(tag, errorMessage, e)
                onToast(str(R.string.toast_save_failed, e.message.orEmpty()))
            }
        }
    }

    // Scan progress
    private val _scanProgress = MutableStateFlow("")
    val scanProgress: StateFlow<String> = _scanProgress

    /** Wall-clock of the last non-empty scan-progress publish (see below). */
    private var lastScanProgressAt = 0L

    /**
     * Publish a scan-progress line.
     *
     * Throttled to one update per [SCAN_PROGRESS_MIN_INTERVAL_MS]: a folder
     * import writes progress every 50/100 media, and each write used to reach the
     * UI immediately (the card is the only consumer, but the count text is what
     * users watch). An EMPTY line - "the import finished" - is always published,
     * so the card disappears without waiting for the throttle window.
     */
    private fun publishScanProgress(text: String) {
        if (text.isNotEmpty()) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastScanProgressAt < SCAN_PROGRESS_MIN_INTERVAL_MS) return
            lastScanProgressAt = now
        }
        _scanProgress.value = text
    }

    fun addImage(groupId: Long, uri: Uri, displayName: String) {
        guardedWrite("添加图片失败") {
            if (groupDao.getGroupById(groupId) == null) return@guardedWrite
            // Deduplicate by URI like addImages / the folder import / the
            // auto-scan worker: picking the same file twice used to add a second
            // row, and the shuffle pass then showed it twice.
            val uriStr = uri.toString()
            // The dedupe read AND the name/MIME resolution run on the IO
            // dispatcher: resolveDisplayName()/mimeOf() are synchronous provider
            // queries (plus a DocumentFile fallback), and guardedWrite() runs on
            // the main dispatcher - a multi-select of a few hundred files used to
            // do every one of those binder calls on the UI thread, which froze
            // the picker's return and could ANR.
            //
            // The system picker on many non-Xiaomi devices returns a generic
            // last path segment like "msf:1000000024" (no extension), which
            // used to be stored as-is and misclassified every video as IMAGE
            // (black playback). Resolve the real name + MIME instead.
            val row = withContext(Dispatchers.IO) {
                val alreadyThere = try {
                    imageDao.getUrisByGroup(groupId).contains(uriStr)
                } catch (_: Exception) {
                    false
                }
                if (alreadyThere) {
                    null
                } else {
                    val name = resolveDisplayName(uri, displayName)
                    WallpaperImage(
                        groupId = groupId,
                        uri = uriStr,
                        displayName = name,
                        mediaType = resolveMediaType(uri, name)
                    )
                }
            }
            if (row == null) {
                onToast(str(R.string.toast_media_already_in_group))
                return@guardedWrite
            }
            imageDao.insert(row)
            onLibraryChanged(groupId)
            // New media changes what a screen may show: wake the timers now
            // instead of waiting for the next interval (the idle waits inside the
            // service are long on purpose).
            WallpaperSwitchService.poke(app)
        }
    }

    fun addImages(groupId: Long, uris: List<Uri>, names: List<String>) {
        guardedWrite("添加图片失败") {
            if (groupDao.getGroupById(groupId) == null) return@guardedWrite
            // Deduplicate by URI, both against what the group already holds and
            // within this batch: the folder-import path and the auto-scan worker
            // do this, the pickers did not, so re-adding the same file produced a
            // second row and the shuffle pass showed it twice.
            val known = try {
                imageDao.getUrisByGroup(groupId).toHashSet()
            } catch (_: Exception) {
                HashSet()
            }
            // Name + MIME resolution is synchronous provider work (one getType()
            // and one DISPLAY_NAME query per URI, plus a DocumentFile fallback):
            // on the UI thread a few-hundred-file multi-select froze the app right
            // after the picker closed. Same reason addFolder() already uses IO.
            // The provider MIME is fetched ONCE per URI and reused for both the
            // media type and the "is it supported" test (it used to be read
            // twice per file).
            val (images, duplicates) = withContext(Dispatchers.IO) {
                var dups = 0
                val built = uris.mapIndexedNotNull { index, uri ->
                    val fallback = names.getOrNull(index).orEmpty()
                    val mime = mimeOf(uri)
                    val name = resolveDisplayName(uri, fallback)
                    val mediaType = MediaTypes.fromMimeOrName(mime, name)
                    // Accept when the resolved name has a supported extension OR
                    // the provider reports a media MIME type (SAF names can lack
                    // an extension entirely).
                    val supported = isSupportedMedia(name) || mime != null
                    if (!supported) {
                        null
                    } else if (!known.add(uri.toString())) {
                        dups++
                        null
                    } else {
                        WallpaperImage(
                            groupId = groupId,
                            uri = uri.toString(),
                            displayName = name,
                            mediaType = mediaType
                        )
                    }
                }
                built to dups
            }
            if (images.isNotEmpty()) {
                // Chunk large multi-select imports: 100 rows per INSERT stays
                // under the 999 bound-variable limit of older SQLite builds.
                images.chunked(100).forEach { chunk -> imageDao.insertAll(chunk) }
                onLibraryChanged(groupId)
                WallpaperSwitchService.poke(app)
                // Media, not images: video and GIF files are added here too.
                onToast(
                    if (duplicates > 0) {
                        str(R.string.toast_added_media_skipped, images.size, duplicates)
                    } else {
                        str(R.string.toast_added_media, images.size)
                    }
                )
            } else {
                onToast(
                    str(
                        if (duplicates > 0) R.string.toast_media_all_present
                        else R.string.toast_no_addable_media
                    )
                )
            }
        }
    }

    /**
     * Add folder via DocumentFile (SAF).
     * Optimized for large folders: batch insert, progress updates, yield for UI responsiveness.
     */
    private var addFolderJob: Job? = null

    fun addFolder(groupId: Long, folderUri: Uri) {
        addFolderJob?.cancel()
        addFolderJob = scope.launch {
            try {
                if (groupDao.getGroupById(groupId) == null) return@launch
                onToast(str(R.string.state_scanning_folders))
                var total = 0
                var alreadyThere = 0
                withContext(Dispatchers.IO) {
                    val docFile = try {
                        androidx.documentfile.provider.DocumentFile
                            .fromTreeUri(app, folderUri)
                    } catch (e: Exception) {
                        AppLog.e(tag, "fromTreeUri failed", e)
                        null
                    } ?: return@withContext

                    if (!docFile.isDirectory) return@withContext

                    // Collect every media first (the recursive scan), then
                    // insert in ONE transaction: the old code committed one
                    // transaction per 100-row batch, i.e. ~100 fsyncs for a
                    // 10k-file folder.
                    val collected = mutableListOf<WallpaperImage>()
                    // Same dedupe rule as addImages / the auto-scan worker:
                    // re-importing a folder must not duplicate its media.
                    val known = try {
                        imageDao.getUrisByGroup(groupId).toHashSet()
                    } catch (_: Exception) {
                        HashSet()
                    }
                    suspend fun scanDir(
                        dir: androidx.documentfile.provider.DocumentFile,
                        depth: Int
                    ) {
                        if (!isActive || depth > MAX_IMPORT_DEPTH) return
                        val files = try {
                            dir.listFiles()
                        } catch (e: Exception) {
                            AppLog.e(tag, "listFiles failed", e)
                            emptyArray()
                        }
                        for (file in files) {
                            if (!isActive) return
                            if (collected.size % 100 == 0) yield()
                            try {
                                if (file.isDirectory) {
                                    scanDir(file, depth + 1)
                                } else if (file.isFile && isSupportedMedia(file.name ?: "")) {
                                    val uriStr = file.uri.toString()
                                    if (known.add(uriStr)) {
                                        collected.add(
                                            WallpaperImage(
                                                groupId = groupId,
                                                uri = uriStr,
                                                displayName = file.name ?: "untitled",
                                                mediaType = detectMediaType(file.name ?: ""),
                                                isFromFolder = true,
                                                folderPath = folderUri.toString()
                                            )
                                        )
                                    } else {
                                        alreadyThere++
                                    }
                                }
                            } catch (_: Exception) {
                                continue
                            }
                        }
                    }

                    scanDir(docFile, 0)
                    if (collected.isNotEmpty() && isActive) {
                        db.withTransaction {
                            // 100 rows per INSERT stays under the 999
                            // bound-variable limit of older SQLite builds.
                            collected.chunked(100).forEach { chunk ->
                                imageDao.insertAll(chunk)
                                total += chunk.size
                            }
                        }
                    }
                }
                if (total > 0) {
                    onLibraryChanged(groupId)
                    WallpaperSwitchService.poke(app)
                    onToast(str(R.string.toast_added_media, total))
                } else {
                    onToast(
                        str(
                            if (alreadyThere > 0) R.string.toast_folder_media_present
                            else R.string.toast_no_media_found
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(tag, "addFolder failed", e)
                onToast(str(R.string.toast_import_failed, e.message.orEmpty()))
            }
        }
    }

    fun deleteImage(image: WallpaperImage) {
        guardedWrite("删除图片失败") {
            imageDao.delete(image)
            storage.deleteOwnedFiles(listOf(image.uri))
            MediaPick.invalidateEnabledIds()
            clearMediaCursors(settingsDao, setOf(image.id))
            currentGroupId()?.let { onLibraryChanged(it) }
        }
    }

    fun deleteImages(images: List<WallpaperImage>) {
        guardedWrite("删除图片失败") {
            val ids = images.map { it.id }
            val uris = images.map { it.uri }
            MediaPick.invalidateEnabledIds()
            // Chunk the DELETE: older SQLite builds cap a statement at 999
            // bound variables, and a select-all delete can pass thousands of
            // ids (would throw "too many SQL variables").
            ids.chunked(500).forEach { chunk ->
                imageDao.deleteByIds(chunk)
            }
            storage.deleteOwnedFiles(uris)
            clearMediaCursors(settingsDao, ids)
            currentGroupId()?.let { onLibraryChanged(it) }
        }
    }

    /**
     * Delete images by IDs directly — works across all pages, not just loaded ones.
     */
    fun deleteImagesByIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        guardedWrite("删除图片失败") {
            // URIs first: a subscription import lives in the app's own storage
            // and its file must go together with the row.
            // 分片查询：SQLite 的绑定变量上限在旧设备上是 999，一次 select-all
            // 删除几千张时会把整条语句撑爆（和下面分片 DELETE 同样的原因）。
            val uris = ArrayList<String>(ids.size)
            try {
                ids.toList().chunked(500).forEach { chunk ->
                    uris.addAll(imageDao.getUrisByIds(chunk))
                }
            } catch (_: Throwable) {
            }
            MediaPick.invalidateEnabledIds()
            ids.toList().chunked(500).forEach { chunk ->
                imageDao.deleteByIds(chunk)
            }
            storage.deleteOwnedFiles(uris)
            clearMediaCursors(settingsDao, ids)
            currentGroupId()?.let { onLibraryChanged(it) }
        }
    }

    /**
     * Get ALL image IDs in a group (across all pages) for select-all + batch delete.
     */
    suspend fun getAllImageIds(groupId: Long): List<Long> {
        return imageDao.getImageIdsByGroup(groupId)
    }

    /**
     * Scan every media entry in a group and return the ones whose files can no
     * longer be opened (deleted / moved / unreadable). Progress is reported
     * through [scanProgress]; runs on the IO dispatcher.
     */
    suspend fun scanBrokenMedia(groupId: Long): List<WallpaperImage> {
        return withContext(Dispatchers.IO) {
            val resolver = app.contentResolver
            val all = imageDao.getImagesByGroupSync(groupId)
            val broken = mutableListOf<WallpaperImage>()
            var checked = 0
            for (image in all) {
                if (!isActive) return@withContext broken
                val ok = try {
                    resolver.openInputStream(Uri.parse(image.uri))?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }
                if (!ok) broken.add(image)
                checked++
                if (checked % 50 == 0 || checked == all.size) {
                    publishScanProgress(
                        str(R.string.scan_progress_checking, checked, all.size)
                    )
                }
                if (checked % 100 == 0) yield()
            }
            publishScanProgress("")
            broken
        }
    }

    // ======== Folder scanning (background) ========

    // Cache of the last MediaStore folder scan. The folder picker dialog used
    // to re-scan the whole media library on EVERY open (a noticeable
    // "正在扫描文件夹..." wait on large libraries). Like the group
    // thumbnails, the list is now loaded once and reused, so the dialog opens
    // instantly. The cache is in-memory: a fresh scan happens automatically
    // after the process restarts.
    @Volatile
    private var cachedScannedFolders: List<ScannedFolder>? = null

    /**
     * Scan device folders that contain images and/or videos (MediaStore).
     * Cached: the first call scans MediaStore, later calls return the cached
     * list immediately (the picker dialog opens without re-scanning).
     */
    suspend fun loadScannedFolders(): List<ScannedFolder> {
        cachedScannedFolders?.let { return it }
        val scanned = MediaScanner.scanFolders(app)
        // Cache only successful NON-EMPTY scans. An empty result usually means
        // the read-media permission was just granted/denied or the provider
        // hiccuped; caching it would make the folder dialog look permanently
        // incomplete until the process restarts. Empty devices simply rescan
        // on the next dialog open (a cheap MediaStore query).
        if (scanned.isNotEmpty()) cachedScannedFolders = scanned
        return scanned
    }

    /**
     * Force a fresh MediaStore folder scan, bypassing and refreshing the
     * in-memory cache. Used by the folder picker's 重新扫描 action — the
     * cached list from loadScannedFolders() would otherwise stay stale until
     * the process restarts.
     */
    suspend fun rescanFolders(): List<ScannedFolder> {
        cachedScannedFolders = null
        val scanned = MediaScanner.scanFolders(app)
        if (scanned.isNotEmpty()) cachedScannedFolders = scanned
        // Also record the outcome in the runtime log: the dialog shows a toast,
        // and an exported log should explain the same thing.
        AppLog.d(
            tag,
            "rescanFolders: ${scanned.size} folders / " +
                "${scanned.sumOf { it.totalCount }} media"
        )
        return scanned
    }

    /**
     * Import several scanned folders into a group (images + videos, deduped).
     */
    fun importScannedFolders(groupId: Long, folders: List<ScannedFolder>) {
        if (folders.isEmpty()) return
        scope.launch {
            try {
                onToast(str(R.string.toast_importing_folders, folders.size))
                AppLog.d(
                    tag,
                    "importScannedFolders: group=$groupId folders=" +
                        folders.map { LogText.folder(it.path) }
                )
                publishScanProgress(str(R.string.scan_progress_querying))
                var total = 0
                withContext(Dispatchers.IO) {
                    // Collect every new media first, then insert everything in
                    // ONE transaction: the old code committed a transaction per
                    // 100-row batch (dozens of fsyncs for large imports).
                    val collected = mutableListOf<WallpaperImage>()
                    val existing = imageDao.getUrisByGroup(groupId).toHashSet()
                    for (folder in folders) {
                        if (!isActive) return@withContext
                        val media = MediaScanner.queryFolderMedia(app, folder.path)
                        for (m in media) {
                            if (m.uri in existing) continue
                            existing.add(m.uri)
                            collected.add(
                                WallpaperImage(
                                    groupId = groupId,
                                    uri = m.uri,
                                    displayName = m.displayName,
                                    mediaType = m.mediaType,
                                    isFromFolder = true,
                                    folderPath = folder.path,
                                    // Free decode metadata from the MediaStore
                                    // projection: later switches need one media read.
                                    width = m.width,
                                    height = m.height,
                                    rotationDegrees = m.rotationDegrees
                                )
                            )
                            // Progress updates come from the IO thread directly:
                            // MutableStateFlow is thread-safe, so no main-thread
                            // hop is needed inside the transaction.
                            if (collected.size % 100 == 0) {
                                publishScanProgress(
                                    str(R.string.scan_progress_querying_media, collected.size)
                                )
                            }
                        }
                        publishScanProgress(
                            str(R.string.scan_progress_querying_media, collected.size)
                        )
                        yield()
                    }
                    if (collected.isNotEmpty() && isActive) {
                        db.withTransaction {
                            // 100 rows per INSERT keeps the bound-variable count
                            // well under the 999 limit of older SQLite builds.
                            collected.chunked(100).forEach { chunk ->
                                imageDao.insertAll(chunk)
                                total += chunk.size
                            }
                        }
                    }
                }
                publishScanProgress("")
                onLibraryChanged(groupId)
                WallpaperSwitchService.poke(app)
                if (total > 0) {
                    onToast(str(R.string.toast_imported_media, total))
                } else {
                    onToast(str(R.string.toast_no_new_media))
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(tag, "importScannedFolders failed", e)
                onToast(str(R.string.toast_import_failed, e.message.orEmpty()))
                publishScanProgress("")
            }
        }
    }

    private fun isSupportedMedia(name: String): Boolean = MediaTypes.isSupportedName(name)

    private fun detectMediaType(name: String): String = MediaTypes.fromName(name)

    /**
     * Real display name for a picked URI: providers hand out generic segments
     * ("msf:123", "document/123") that carry no extension, so ask the resolver
     * (and DocumentFile as a fallback) before falling back to [fallback].
     */
    private fun resolveDisplayName(uri: Uri, fallback: String): String {
        try {
            app.contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Exception) {
        }
        try {
            androidx.documentfile.provider.DocumentFile
                .fromSingleUri(app, uri)
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        } catch (_: Exception) {
        }
        return fallback
    }

    /** Media type from the provider MIME type, falling back to the extension. */
    private fun resolveMediaType(uri: Uri, name: String): String =
        MediaTypes.fromMimeOrName(mimeOf(uri), name)

    /** Provider MIME type for [uri], or null when it cannot be resolved. */
    private fun mimeOf(uri: Uri): String? = MediaTypes.mimeOf(app, uri)

    private companion object {
        private const val MAX_IMPORT_DEPTH = 24
        private const val SCAN_PROGRESS_MIN_INTERVAL_MS = 200L
    }
}
