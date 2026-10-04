package com.wallpaperswitcher.engine

import android.content.Context
import android.graphics.BitmapFactory
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.OnlineItem
import com.wallpaperswitcher.data.OnlineSource
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.util.AppLog
import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One sync of one [OnlineSource]: fetch the remote list, download only what is
 * new, store the files app-privately, insert them into the target group and
 * apply the retention window.
 *
 * All entry points (the periodic worker, 立即更新, the app-open reschedule) run
 * through here, so the dedupe rules and the retention are identical.
 */
object OnlineSync {

    private const val TAG = "OnlineSync"
    /** Fallback when the localized string cannot be resolved. */
    private const val AUTO_GROUP_FALLBACK = "在线壁纸"
    /**
     * Serializes every sync: one download at a time (power / data), and two
     * sources can never race the auto-group creation.
     */
    private val syncMutex = Mutex()

    data class Report(
        val ok: Boolean,
        val added: Int,
        val skipped: Int,
        val reason: String,
    ) {
        companion object {
            fun error(reason: String) = Report(false, 0, 0, reason)
        }
    }

    /** Entry point of the worker / 立即更新. Never throws. */
    suspend fun sync(context: Context, sourceId: Long): Report = syncMutex.withLock {
        syncLocked(context, sourceId)
    }

    private suspend fun syncLocked(context: Context, sourceId: Long): Report =
        withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val source = try {
            db.onlineSourceDao().getById(sourceId)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Online source $sourceId could not be read", t)
            null
        } ?: return@withContext Report.error("missing")
        if (!source.enabled) return@withContext Report.error("disabled")

        val password = OnlineSecretStore.decrypt(source.passwordCipher)
        val groupId = try {
            resolveTargetGroup(context, db, source)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Online source $sourceId: no target group", t)
            return@withContext finish(db, source, Report.error("storage"))
        }

        val itemDao = db.onlineItemDao()
        val knownKeys = try {
            itemDao.remoteKeys(sourceId).toHashSet()
        } catch (_: Throwable) {
            HashSet()
        }
        val knownHashes = try {
            itemDao.contentHashes(sourceId).toHashSet()
        } catch (_: Throwable) {
            HashSet()
        }
        val targetDir = File(context.filesDir, "online/$sourceId")
        val maxPerRun = OnlineSourceRules.normalizeMaxPerRun(source.maxPerRun)
        var added = 0
        var skipped = 0

        try {
            when (source.type) {
                OnlineSource.TYPE_BING -> {
                    val items = OnlineFetcher.listBing()
                        .filter { it.remoteKey !in knownKeys }
                        .take(maxPerRun)
                    for (item in items) {
                        if (store(db, source, groupId, item, password, targetDir, knownKeys, knownHashes)) {
                            added++
                        } else {
                            skipped++
                        }
                    }
                }

                OnlineSource.TYPE_WEBDAV -> {
                    val items = OnlineFetcher.listWebDav(source, password)
                        .filter { it.remoteKey !in knownKeys }
                        .take(maxPerRun)
                    for (item in items) {
                        if (store(db, source, groupId, item, password, targetDir, knownKeys, knownHashes)) {
                            added++
                        } else {
                            skipped++
                        }
                    }
                }

                OnlineSource.TYPE_MEIRENTU -> {
                    // Manual mode: the user ticked exact images in the picker.
                    // Auto mode: scrape the newest albums whose `album:<id>`
                    // marker is missing, page by page.
                    val selected = OnlineSourceRules.parseSelectedImages(source.selectedImages)
                    val listing = if (selected.isNotEmpty()) {
                        OnlineFetcher.MeirentuListing(
                            items = OnlineFetcher.selectedMeirentuItems(
                                selected, knownKeys, maxPerRun
                            ),
                            completedAlbums = emptyList(),
                        )
                    } else {
                        OnlineFetcher.listMeirentu(source, knownKeys, maxPerRun)
                    }
                    val referer = OnlineSourceRules.originOf(source.url)
                    for (item in listing.items) {
                        if (
                            store(
                                db, source, groupId, item, password, targetDir,
                                knownKeys, knownHashes, referer
                            )
                        ) {
                            added++
                        } else {
                            skipped++
                        }
                    }
                    // Albums that were fully consumed are remembered with a
                    // marker, so the next sync moves on to the following ones.
                    // A marker is not a downloadable item and is excluded from
                    // the retention count (see OnlineItemDao.itemsBeyond).
                    for (albumId in listing.completedAlbums) {
                        val key = "album:$albumId"
                        if (key !in knownKeys) {
                            db.onlineItemDao().insert(
                                OnlineItem(
                                    sourceId = source.id,
                                    remoteKey = key,
                                    contentHash = "",
                                    imageId = 0L,
                                    filePath = "",
                                )
                            )
                            knownKeys.add(key)
                        }
                    }
                }

                OnlineSource.TYPE_URL -> {
                    val pseudo = OnlineFetcher.RemoteItem(
                        remoteKey = "",
                        url = source.url,
                        displayName = urlDisplayName(source.url),
                    )
                    when (
                        val result = OnlineFetcher.download(
                            source, password, pseudo, targetDir, useValidators = true
                        )
                    ) {
                        OnlineFetcher.Download.NotModified -> skipped++
                        is OnlineFetcher.Download.Failure ->
                            throw OnlineFetcher.FetchException(result.reason)
                        is OnlineFetcher.Download.Success -> {
                            // Remember the validators even when the bytes were
                            // already known: the next run can then get a 304.
                            db.onlineSourceDao().recordHttpValidators(
                                sourceId, result.etag, result.lastModified
                            )
                            val remoteKey = "sha256:${result.contentHash}"
                            if (storeDownloaded(
                                    db, source, groupId, remoteKey, pseudo.displayName,
                                    result, knownKeys, knownHashes
                                )
                            ) {
                                added++
                            } else {
                                skipped++
                            }
                        }
                    }
                }

                else -> return@withContext finish(db, source, Report.error("bad_url"))
            }

            pruneDeletedMedia(db, source, targetDir)
            applyRetention(db, source)
            if (added > 0) {
                // The screen-wide SHUFFLE/RANDOM id cache would otherwise keep
                // serving the pre-download list until the next settings change.
                MediaPick.invalidateEnabledIds()
            }
            val report = Report(true, added, skipped, "ok")
            finish(db, source, report)
            AppLog.d(
                TAG,
                "Online sync ok: source=$sourceId type=${source.type} added=$added skipped=$skipped"
            )
            report
        } catch (t: Throwable) {
            val reason = OnlineFetcher.classify(t)
            AppLog.w(
                TAG,
                "Online sync failed: source=$sourceId type=${source.type} reason=$reason " +
                    "ex=${t.javaClass.simpleName}"
            )
            finish(db, source, Report(false, added, skipped, reason))
        }
    }

    /** Delete a source completely: files, downloaded media rows and items. */
    suspend fun purgeSource(context: Context, sourceId: Long) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        try {
            val items = db.onlineItemDao().getBySource(sourceId)
            for (item in items) {
                if (item.imageId > 0L) {
                    db.wallpaperImageDao().getImageById(item.imageId)?.let {
                        db.wallpaperImageDao().delete(it)
                    }
                }
                if (item.filePath.isNotEmpty()) File(item.filePath).delete()
            }
            File(context.filesDir, "online/$sourceId").deleteRecursively()
        } catch (t: Throwable) {
            AppLog.w(TAG, "Purge of online source $sourceId failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Rows the user deleted from the group keep their `online_items` entry (so
     * the remote file is not downloaded again) but drop the media reference and
     * the file.
     */
    private suspend fun pruneDeletedMedia(db: AppDatabase, source: OnlineSource, dir: File) {
        val items = db.onlineItemDao().getBySource(source.id)
        if (items.none { it.imageId > 0L }) return
        val liveIds = try {
            db.wallpaperImageDao()
                .getIdsByFolder("online/${source.id}")
                .toHashSet()
        } catch (_: Throwable) {
            return
        }
        for (item in items) {
            if (item.imageId > 0L && item.imageId !in liveIds) {
                if (item.filePath.isNotEmpty()) File(item.filePath).delete()
                db.onlineItemDao().clearMedia(item.sourceId, item.remoteKey)
            }
        }
        // Also drop any stray file whose content hash is no longer referenced.
        val referenced = db.onlineItemDao()
            .getBySource(source.id)
            .mapNotNull { it.filePath.substringAfterLast('/').takeIf { name -> name.isNotEmpty() } }
            .toHashSet()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in referenced) file.delete()
        }
    }

    private suspend fun applyRetention(db: AppDatabase, source: OnlineSource) {
        val keep = OnlineSourceRules.normalizeKeepCount(source.keepCount)
        if (keep <= 0) return
        val victims = try {
            db.onlineItemDao().itemsBeyond(source.id, keep)
        } catch (_: Throwable) {
            return
        }
        for (item in victims) {
            if (item.imageId > 0L) {
                db.wallpaperImageDao().getImageById(item.imageId)?.let {
                    db.wallpaperImageDao().delete(it)
                }
            }
            if (item.filePath.isNotEmpty()) File(item.filePath).delete()
            db.onlineItemDao().delete(item.sourceId, item.remoteKey)
        }
    }

    private suspend fun store(
        db: AppDatabase,
        source: OnlineSource,
        groupId: Long,
        item: OnlineFetcher.RemoteItem,
        password: String,
        targetDir: File,
        knownKeys: MutableSet<String>,
        knownHashes: MutableSet<String>,
        referer: String? = null,
    ): Boolean {
        return when (
            val result = OnlineFetcher.download(
                source, password, item, targetDir, useValidators = false, referer = referer
            )
        ) {
            OnlineFetcher.Download.NotModified -> false
            is OnlineFetcher.Download.Failure ->
                throw OnlineFetcher.FetchException(result.reason)
            is OnlineFetcher.Download.Success ->
                storeDownloaded(
                    db, source, groupId, item.remoteKey, item.displayName,
                    result, knownKeys, knownHashes
                )
        }
    }

    private suspend fun storeDownloaded(
        db: AppDatabase,
        source: OnlineSource,
        groupId: Long,
        remoteKey: String,
        displayName: String,
        download: OnlineFetcher.Download.Success,
        knownKeys: MutableSet<String>,
        knownHashes: MutableSet<String>,
    ): Boolean {
        val hash = download.contentHash
        if (hash in knownHashes) {
            download.file.delete()
            // Remember a NEW remote key pointing at the same bytes (Bing/WebDAV
            // could re-publish the same image under a new name), but never
            // overwrite the existing owner row: that would lose its file link.
            if (remoteKey.isNotEmpty() && remoteKey !in knownKeys) {
                db.onlineItemDao().insert(
                    OnlineItem(
                        sourceId = source.id,
                        remoteKey = remoteKey,
                        contentHash = hash,
                        imageId = 0L,
                        filePath = "",
                    )
                )
                knownKeys.add(remoteKey)
            }
            return false
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(download.file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            download.file.delete()
            return false
        }

        val dir = download.file.parentFile ?: return false
        val finalFile = File(dir, "$hash.${download.extension}")
        if (finalFile.exists()) finalFile.delete()
        if (!download.file.renameTo(finalFile)) {
            download.file.copyTo(finalFile, overwrite = true)
            download.file.delete()
        }
        val imageId = db.wallpaperImageDao().insert(
            WallpaperImage(
                groupId = groupId,
                uri = "file://${finalFile.absolutePath}",
                displayName = displayName,
                mediaType = if (download.extension == "gif") MediaTypes.GIF else MediaTypes.IMAGE,
                isFromFolder = false,
                folderPath = "online/${source.id}",
                width = bounds.outWidth,
                height = bounds.outHeight,
            )
        )
        db.onlineItemDao().insert(
            OnlineItem(
                sourceId = source.id,
                remoteKey = remoteKey,
                contentHash = hash,
                imageId = imageId,
                filePath = finalFile.absolutePath,
            )
        )
        knownKeys.add(remoteKey)
        knownHashes.add(hash)
        return true
    }

    private suspend fun resolveTargetGroup(
        context: Context,
        db: AppDatabase,
        source: OnlineSource,
    ): Long {
        if (source.groupId > 0L) {
            db.wallpaperGroupDao().getGroupById(source.groupId)?.let { return it.id }
        }
        val name = try {
            AppLocale.localized(context).getString(R.string.online_auto_group_name)
        } catch (_: Throwable) {
            AUTO_GROUP_FALLBACK
        }
        db.wallpaperGroupDao().getGroupByName(name)?.let { return it.id }
        return db.wallpaperGroupDao().insert(
            WallpaperGroup(name = name, isEnabled = true, target = "BOTH")
        )
    }

    private suspend fun finish(db: AppDatabase, source: OnlineSource, report: Report): Report {
        val now = System.currentTimeMillis()
        val encoded = if (report.ok) {
            OnlineSourceRules.encodeOk(report.added, report.skipped)
        } else {
            OnlineSourceRules.encodeError(report.reason)
        }
        try {
            db.onlineSourceDao().recordResult(
                id = source.id,
                at = now,
                result = encoded,
                errorAt = if (report.ok) 0L else now,
            )
        } catch (_: Throwable) {
        }
        return report
    }

    private fun urlDisplayName(url: String): String {
        val raw = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return try {
            URLDecoder.decode(raw, "UTF-8").ifBlank { "online.jpg" }
        } catch (_: Exception) {
            raw.ifBlank { "online.jpg" }
        }
    }
}
