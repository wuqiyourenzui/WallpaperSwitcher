package com.wallpaperswitcher.engine

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.OnlineItem
import com.wallpaperswitcher.data.OnlineSource
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setString
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.LogText
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
                    val scope = SyncScope(
                        context, db, source, groupId, password, targetDir,
                        knownKeys, knownHashes,
                    )
                    for (item in items) {
                        if (store(scope, item)) {
                            added++
                        } else {
                            skipped++
                        }
                    }
                }

                OnlineSource.TYPE_WEBDAV -> {
                    // 网盘来源连视频一起同步；**视频不设体积上限**（用户明确要求取消），
                    // 图片仍走 100MB 上限（防止"图床"塞超大文件）。
                    val includeVideos = onlineVideosEnabled(db)
                    val items = OnlineFetcher.listWebDav(source, password, includeVideos)
                        .filter { it.remoteKey !in knownKeys }
                        .take(maxPerRun)
                    val scope = SyncScope(
                        context, db, source, groupId, password, targetDir,
                        knownKeys, knownHashes,
                    )
                    for (item in items) {
                        val isVideo = OnlineSourceRules.isVideoExtension(
                            item.displayName.substringAfterLast('.', "").lowercase()
                        )
                        val cap = if (isVideo) {
                            Long.MAX_VALUE
                        } else {
                            OnlineFetcher.MAX_DOWNLOAD_BYTES
                        }
                        // 服务端已经给了大小：超限就先跳过，别把流量和磁盘浪费掉。
                        if (OnlineSourceRules.tooLargeToFetch(item.size, cap)) {
                            AppLog.d(
                                TAG,
                                "Skip ${item.displayName}: ${item.size / 1024 / 1024}MB > " +
                                    "${cap / 1024 / 1024}MB cap"
                            )
                            skipped++
                            continue
                        }
                        val cappedScope = SyncScope(
                            context, db, source, groupId, password, targetDir,
                            knownKeys, knownHashes,
                            maxBytes = cap, includeVideos = includeVideos,
                        )
                        if (store(cappedScope, item)) {
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
                    val scopeWithReferer = SyncScope(
                        context, db, source, groupId, password, targetDir,
                        knownKeys, knownHashes, referer,
                    )
                    for (item in listing.items) {
                        if (store(scopeWithReferer, item)) {
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
                    val scope = SyncScope(
                        context, db, source, groupId, password, targetDir,
                        knownKeys, knownHashes,
                    )
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
                            if (storeDownloaded(scope, remoteKey, pseudo.displayName, result)) {
                                added++
                            } else {
                                skipped++
                            }
                        }
                    }
                }

                // 设置里的内置在线壁纸源：取回图片列表后走同一套下载/入库/去重逻辑。
                OnlineSource.TYPE_NASA_APOD,
                OnlineSource.TYPE_WIKIMEDIA,
                OnlineSource.TYPE_NETBIAN,
                OnlineSource.TYPE_IOLIU -> {
                    val items = when (source.type) {
                        OnlineSource.TYPE_NASA_APOD -> OnlineFetcher.listNasaApod()
                        OnlineSource.TYPE_WIKIMEDIA -> OnlineFetcher.listWikimedia()
                        OnlineSource.TYPE_NETBIAN -> OnlineFetcher.listNetbian(maxPerRun)
                        else -> OnlineFetcher.listIoliu(maxPerRun)
                    }.filter { it.remoteKey !in knownKeys }.take(maxPerRun)
                    val referer = when (source.type) {
                        OnlineSource.TYPE_NETBIAN -> "https://pic.netbian.com/"
                        OnlineSource.TYPE_IOLIU -> "https://bing.ioliu.cn/"
                        else -> null
                    }
                    val scope = SyncScope(
                        context, db, source, groupId, password, targetDir,
                        knownKeys, knownHashes, referer,
                    )
                    for (item in items) {
                        // 多图源（彼岸/ioliu/Wikimedia）里单张图偶发 5xx 不应中断
                        // 整轮：记一条日志并继续下一张，其余图片照常入库。
                        try {
                            if (store(scope, item)) {
                                added++
                            } else {
                                skipped++
                            }
                        } catch (t: Throwable) {
                            skipped++
                            AppLog.w(
                                TAG,
                                "online item failed: type=${source.type} " +
                                    "reason=${OnlineFetcher.classify(t)} " +
                                    "ex=${t.javaClass.simpleName}"
                            )
                        }
                    }
                }

                else -> return@withContext finish(db, source, Report.error("bad_url"))
            }

            pruneDeletedMedia(context, db, source, targetDir)
            applyRetention(context, db, source)
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
                // filePath 可能是 content://（用户选的下载目录），必须由 resolver 删。
                deleteMediaFile(context, item.filePath)
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
    private suspend fun pruneDeletedMedia(
        context: Context,
        db: AppDatabase,
        source: OnlineSource,
        dir: File,
    ) {
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
                // filePath 可能是 content://（用户选的下载目录），必须由 resolver 删。
                deleteMediaFile(context, item.filePath)
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

    /**
     * 「浏览页」用：只下载用户在网盘里点中的那一项并入库，返回插入后的媒体行。
     *
     * 与定时同步共用 [store]，所以媒体类型判定、体积上限、去重、保留策略完全一致；
     * 区别只是"只此一项"、不做最大条数截断。
     */
    suspend fun importOne(
        context: Context,
        sourceId: Long,
        entry: OnlineBrowser.Entry,
        /** 目标分组；0 = 用来源的自动分组（同步用的那个）。 */
        targetGroupId: Long = 0L,
        /** 下载进度（已写字节, 总字节；总字节 0 = 服务端没给长度）。 */
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): WallpaperImage? = withContext(Dispatchers.IO) {
        migrateRemoteKeysIfNeeded(context)
        val db = AppDatabase.getInstance(context)
        val source = try {
            db.onlineSourceDao().getById(sourceId)
        } catch (_: Throwable) {
            null
        } ?: return@withContext null
        val password = OnlineSecretStore.decrypt(source.passwordCipher)
        // 去重：同一 remoteKey 已经下过且文件还在，就直接复用 —— 浏览页反复点同一张图
        // 不该每次重下一遍（3MB 的图点三次就是 9MB 流量）。
        try {
            val existing = db.onlineItemDao().getBySource(source.id)
                .firstOrNull { it.remoteKey == entry.path && it.imageId > 0L }
            if (existing != null) {
                val image = db.wallpaperImageDao().getImageById(existing.imageId)
                if (image != null) {
                    val file = File(image.uri.removePrefix("file://"))
                    if (file.exists() && file.length() > 0L) {
                        // 已经下过：先把文件挪进当前设置的下载目录（用户换了目录后，
                        // 老文件也要出现在那儿），再按分组需要复制一条记录，别重下。
                        val moved = moveIntoConfiguredDir(context, db, image, entry.displayName)
                        if (targetGroupId > 0L && moved.groupId != targetGroupId) {
                            return@withContext copyIntoGroup(db, moved, targetGroupId)
                        }
                        return@withContext moved
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "dedupe check failed: ${t.javaClass.simpleName}")
        }
        val url = entry.url ?: return@withContext null
        // 视频不再设体积上限（用户明确要求"取消下载视频上限"）；图片仍守 100MB，
        // 防止某个"图床"塞一个几百兆的文件进来。
        val isVideo = entry.mediaType == MediaTypes.VIDEO
        if (!isVideo && OnlineSourceRules.tooLargeToFetch(entry.size, OnlineFetcher.MAX_DOWNLOAD_BYTES)) {
            AppLog.d(TAG, "Skip ${entry.name}: image above 100MB")
            return@withContext null
        }
        val groupId = if (targetGroupId > 0L) {
            targetGroupId
        } else {
            resolveTargetGroup(context, db, source)
        }
        val targetDir = File(context.filesDir, "online/${source.id}")
        val item = OnlineFetcher.RemoteItem(
            remoteKey = entry.path,
            url = url,
            displayName = entry.name,
            lastModifiedMs = entry.modifiedMs,
            size = entry.size,
        )
        val ok = store(
            SyncScope(
                context, db, source, groupId, password, targetDir,
                HashSet(), HashSet(),
                // 视频不设上限；图片仍是 100MB（上面已经提前拦过超大图片）。
                maxBytes = if (isVideo) Long.MAX_VALUE else OnlineFetcher.MAX_DOWNLOAD_BYTES,
                includeVideos = true,
                onProgress = onProgress,
            ),
            item,
        )
        if (!ok) return@withContext null
        val row = db.onlineItemDao().getBySource(source.id)
            .firstOrNull { it.remoteKey == item.remoteKey } ?: return@withContext null
        if (row.imageId <= 0L) return@withContext null
        val stored = db.wallpaperImageDao().getImageById(row.imageId) ?: return@withContext null
        moveIntoConfiguredDir(context, db, stored, entry.displayName)
    }

    /**
     * 用户设置了「网盘下载位置」→ 把这条媒体的文件挪进那个 SAF 目录，并把 uri 换成
     * `content://`；挪不动（没权限/目录被删）就原样返回，绝不让已下好的文件丢失。
     */
    private suspend fun moveIntoConfiguredDir(
        context: Context,
        db: AppDatabase,
        image: WallpaperImage,
        displayName: String,
    ): WallpaperImage {
        val dirUri = OnlineDownloadDir.load(context)
        if (dirUri.isBlank()) return image
        val local = File(image.uri.removePrefix("file://"))
        if (!local.exists() || local.length() <= 0L) return image
        val mime = if (image.mediaType == MediaTypes.VIDEO) "video/*" else "image/*"
        val moved = OnlineDownloadDir.copyIntoTree(context, dirUri, local, displayName, mime)
            ?: return image
        db.wallpaperImageDao().updateUri(image.id, moved.toString())
        local.delete()
        return db.wallpaperImageDao().getImageById(image.id) ?: image
    }

    /**
     * 一次性迁移：旧版本把 WebDAV 的 `remoteKey` 存成 href（`/dav/%E5%AD%97/x.mp4`），
     * 新版本统一存文件名（`x.mp4`，与浏览页 `Entry.path` 同口径）。不迁移的话，
     * 升级后第一次同步会把每个文件重下一遍。
     *
     * 只是改 key：contentHash 与它指向的媒体行都不动，所以即使迁移失败也不会丢数据。
     */
    suspend fun migrateRemoteKeysIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val done = try {
            db.settingsDao().getString(SettingsKeys.ONLINE_KEY_MIGRATION, "") == "1"
        } catch (t: Throwable) {
            AppLog.w(TAG, "migration flag read failed: ${t.javaClass.simpleName}")
            true
        }
        if (done) return@withContext
        var renamed = 0
        try {
            for (row in db.onlineItemDao().getAllOnce()) {
                if (!row.remoteKey.contains('/')) continue
                val newKey = row.remoteKey.trimEnd('/').substringAfterLast('/')
                if (newKey.isBlank() || newKey == row.remoteKey) continue
                // 新 key 可能已经存在（同一个文件被新旧两套 key 各记过一次）：
                // 那就把旧行删掉，别让唯一约束报错。
                try {
                    db.onlineItemDao().renameKey(row.sourceId, row.remoteKey, newKey)
                    renamed++
                } catch (_: Throwable) {
                    db.onlineItemDao().delete(row.sourceId, row.remoteKey)
                }
            }
            db.settingsDao().setString(SettingsKeys.ONLINE_KEY_MIGRATION, "1")
            AppLog.d(TAG, "migrated $renamed remote keys to file-name form")
        } catch (t: Throwable) {
            AppLog.w(TAG, "remote key migration failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 删除一条媒体在磁盘上的文件。
     *
     * 落盘位置可能是应用私有文件（`file://`），也可能是用户选的 SAF 目录
     * （`content://`，见 [OnlineDownloadDir]）——后者必须走 ContentResolver：
     * `File(...).delete()` 对它永远失败，旧代码于是把用户目录里的文件留成垃圾。
     */
    private fun deleteMediaFile(context: Context, uriOrPath: String) {
        if (uriOrPath.isBlank()) return
        try {
            if (uriOrPath.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(uriOrPath), null, null)
            } else {
                File(uriOrPath.removePrefix("file://")).delete()
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "deleteMediaFile failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private suspend fun applyRetention(
        context: Context,
        db: AppDatabase,
        source: OnlineSource,
    ) {
        val keep = OnlineSourceRules.normalizeKeepCount(source.keepCount)
        if (keep <= 0) return
        // 正在当壁纸的那张/那条不能删：删掉后下一次切换/重绘会读不到文件。
        val protectedIds = displayedMediaIds(db)
        val victims = try {
            db.onlineItemDao().itemsBeyond(source.id, keep)
        } catch (_: Throwable) {
            return
        }
        for (item in victims) {
            if (item.imageId > 0L && item.imageId in protectedIds) continue
            if (item.imageId > 0L) {
                db.wallpaperImageDao().getImageById(item.imageId)?.let {
                    db.wallpaperImageDao().delete(it)
                }
            }
                // filePath 可能是 content://（用户选的下载目录），必须由 resolver 删。
                deleteMediaFile(context, item.filePath)
            db.onlineItemDao().delete(item.sourceId, item.remoteKey)
        }
    }

    /** 屏幕上正显示着的媒体 id（桌面/锁屏）：清理与保留策略都不能删它们。 */
    private suspend fun displayedMediaIds(db: AppDatabase): Set<Long> = try {
        val dao = db.settingsDao()
        buildSet {
            val home = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
            if (home > 0L) add(home)
            val lock = dao.getLong(SettingsKeys.LAST_IMAGE_ID_LOCK)
            if (lock > 0L) add(lock)
        }
    } catch (_: Throwable) {
        emptySet()
    }

    /** 网盘来源是否连视频一起同步（[SettingsKeys.ONLINE_VIDEO_ENABLED]，默认开）。 */
    private suspend fun onlineVideosEnabled(db: AppDatabase): Boolean =
        try {
            db.settingsDao().getBool(SettingsKeys.ONLINE_VIDEO_ENABLED, true)
        } catch (_: Throwable) {
            true
        }

    /**
     * 一轮同步/一次导入的"上下文"。
     *
     * 这些值（db/来源/分组/密码/目标目录/已见 key/已见 hash）在 store 与 storeDownloaded
     * 之间恒同进同出，以前是 9~11 个位置参数，读起来得靠数位置。
     */
    private class SyncScope(
        val context: Context,
        val db: AppDatabase,
        val source: OnlineSource,
        val groupId: Long,
        val password: String,
        val targetDir: File,
        val knownKeys: MutableSet<String>,
        val knownHashes: MutableSet<String>,
        val referer: String? = null,
        val maxBytes: Long = OnlineFetcher.MAX_DOWNLOAD_BYTES,
        val includeVideos: Boolean = false,
        val onProgress: (Long, Long) -> Unit = { _, _ -> },
    )

    private suspend fun store(
        scope: SyncScope,
        item: OnlineFetcher.RemoteItem,
    ): Boolean {
        return when (
            val result = OnlineFetcher.download(
                scope.source,
                scope.password,
                item,
                scope.targetDir,
                useValidators = false,
                referer = scope.referer,
                maxBytes = scope.maxBytes,
                includeVideos = scope.includeVideos,
                onProgress = scope.onProgress,
            )
        ) {
            OnlineFetcher.Download.NotModified -> false
            is OnlineFetcher.Download.Failure ->
                throw OnlineFetcher.FetchException(result.reason)
            is OnlineFetcher.Download.Success ->
                storeDownloaded(scope, item.remoteKey, item.displayName, result)
        }
    }

    private suspend fun storeDownloaded(
        scope: SyncScope,
        remoteKey: String,
        displayName: String,
        download: OnlineFetcher.Download.Success,
    ): Boolean {
        val db = scope.db
        val source = scope.source
        val groupId = scope.groupId
        val knownKeys = scope.knownKeys
        val knownHashes = scope.knownHashes
        val context = scope.context
        val hash = download.contentHash
        if (hash in knownHashes) {
            download.file.delete()
            // 同一份内容已经下过：
            // - 记一条指向同内容的别名 key（Bing/网盘可能换名字重新发布），但**不覆盖**
            //   原来的 owner 行，否则会丢掉文件链接；
            // - 若调用方要求放进另一个分组，就把已有媒体复制过去并**返回成功**。
            //   以前这里直接 return false，UI 把「已下过」当失败报给用户。
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
            val existing = db.onlineItemDao().getBySource(source.id)
                .firstOrNull { it.contentHash == hash && it.imageId > 0L }
            val reused = existing?.let { db.wallpaperImageDao().getImageById(it.imageId) }
                ?: return false
            if (reused.groupId == groupId) return true
            return copyIntoGroup(db, reused, groupId) != null
        }
        // 媒体类型：网盘可以下视频，图片仍是 IMAGE/GIF。
        val mediaType = MediaTypes.fromName(displayName)
        val isVideo = mediaType == MediaTypes.VIDEO
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (!isVideo) {
            BitmapFactory.decodeFile(download.file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                download.file.delete()
                return false
            }
        } else {
            // 视频别用 BitmapFactory 探尺寸（那会把整个文件当图片解，必然失败并把好文件
            // 删掉 —— 老代码对视频就是这个下场）。用 MediaMetadataRetriever 尽力取宽高，
            // 取不到就留 0（引擎自己会补，尺寸只影响首帧布局）。
            val meta = android.media.MediaMetadataRetriever()
            try {
                meta.setDataSource(download.file.absolutePath)
                bounds.outWidth =
                    meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                bounds.outHeight =
                    meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
            } catch (_: Throwable) {
            } finally {
                try { meta.release() } catch (_: Throwable) {}
            }
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
                mediaType = mediaType,
                isFromFolder = false,
                folderPath = "online/${source.id}",
                width = bounds.outWidth,
                height = bounds.outHeight,
            )
        )
        // 用户设了「网盘下载位置」→ 同步进来的文件也挪进那个 SAF 目录（与手动导入一致）。
        var storedUri = "file://${finalFile.absolutePath}"
        var storedPath = finalFile.absolutePath
        val configuredDir = OnlineDownloadDir.load(context)
        if (configuredDir.isNotBlank()) {
            val moved = OnlineDownloadDir.copyIntoTree(
                context,
                configuredDir,
                finalFile,
                displayName,
                if (isVideo) "video/*" else "image/*",
            )
            if (moved != null) {
                storedUri = moved.toString()
                storedPath = storedUri
                finalFile.delete()
                db.wallpaperImageDao().updateUri(imageId, storedUri)
            }
        }
        db.onlineItemDao().insert(
            OnlineItem(
                sourceId = source.id,
                remoteKey = remoteKey,
                contentHash = hash,
                imageId = imageId,
                filePath = storedPath,
            )
        )
        knownKeys.add(remoteKey)
        knownHashes.add(hash)
        return true
    }

    /** 把已入库的一条媒体复制进另一个分组（同一份文件，不重新下载）。 */
    private suspend fun copyIntoGroup(
        db: AppDatabase,
        image: WallpaperImage,
        groupId: Long,
    ): WallpaperImage? = try {
        val newId = db.wallpaperImageDao().insert(
            image.copy(id = 0L, groupId = groupId)
        )
        db.wallpaperImageDao().getImageById(newId)
    } catch (t: Throwable) {
        AppLog.w(TAG, "copyIntoGroup failed: ${t.javaClass.simpleName}")
        null
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
