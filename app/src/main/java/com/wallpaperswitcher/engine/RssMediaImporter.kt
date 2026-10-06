package com.wallpaperswitcher.engine

import android.content.Context
import android.graphics.BitmapFactory
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 订阅文章图片 → 壁纸分组: downloads the images the user ticked in the reader
 * and inserts them as normal media rows, so they take part in the wallpaper
 * rotation exactly like imported photos.
 *
 * Files stay app-private (`files/rss/<sourceId>/`), are validated with a bounds
 * decode and de-duplicated by content hash + URI.
 */
object RssMediaImporter {

    private const val TAG = "RssMediaImporter"
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val USER_AGENT = RssHttp.USER_AGENT
    private const val AUTO_GROUP_FALLBACK = "在线壁纸"
    /**
     * Parallel image downloads. Each job now also validates + publishes (SAF
     * copy) its own file, so the per-file work that used to run serially after
     * the whole batch overlaps other downloads instead of queueing behind them.
     */
    private const val DOWNLOAD_PARALLELISM = 6
    /**
     * Ceiling for the body copy of one download attempt. The socket read
     * timeout (120s) only fires when a read returns nothing; a trickle resets
     * it byte by byte. Partial temp bytes survive a timeout + resume, so the
     * retry continues instead of restarting.
     */
    private const val BODY_COPY_TIMEOUT_MS = 5 * 60 * 1000L

    /**
     * Same client as the rest of the subscription traffic: shared connection
     * pool (keep-alive reuse across the whole import) and Legado-compatible TLS.
     */
    private val client: OkHttpClient get() = RssHttp.client

    /**
     * Downloads go through a dedicated HTTP/1.1 client: several media CDNs
     * answer HTTP/2 with a stream reset / "unexpected end of stream", which
     * OkHttp surfaces as a ProtocolException (`download failed: ProtocolException`
     * in the logs) and the file never lands.
     */
    private val downloadClient: OkHttpClient by lazy {
        RssHttp.client.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            // Media files are large and several CDNs throttle hard: give them a
            // long read window and no total-call cap (the old 60s callTimeout
            // aborted HLS segment downloads with InterruptedIOException).
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * [blocked] is a machine-readable policy reason ("wifi" / "limit") when the
     * download never started; null means it ran normally.
     */
    data class Report(val added: Int, val failed: Int, val blocked: String? = null)

    suspend fun importImages(
        context: Context,
        source: RssSource?,
        urls: List<String>,
        groupId: Long,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Report = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext Report(0, 0)
        // 订阅下载策略 (仅 Wi-Fi / 每日上限): refuse BEFORE any network traffic.
        val policy = RssDownloadPolicy.check(context)
        if (!policy.allowed) {
            AppLog.d(TAG, "import blocked by download policy: ${policy.reason}")
            return@withContext Report(
                added = 0,
                failed = urls.size,
                blocked = when (policy.reason) {
                    RssDownloadPolicy.Reason.WIFI_ONLY -> "wifi"
                    RssDownloadPolicy.Reason.DAILY_LIMIT -> "limit"
                    RssDownloadPolicy.Reason.OK -> null
                },
            )
        }
        AppLog.d(
            TAG,
            "import start: ${urls.size} url(s) -> group $groupId " +
                urls.take(3).joinToString(" | ") { it.take(110) },
        )
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val db = AppDatabase.getInstance(context)
        val targetGroup = resolveGroup(context, db, groupId)
        val imageDao = db.wallpaperImageDao()
        val existing = try {
            imageDao.getUrisByGroup(targetGroup).toHashSet()
        } catch (_: Throwable) {
            HashSet()
        }
        // 阅读 hands the source's own headers (Referer/UA/…) to every image
        // request; without them many sites 403 or stall and the import crawls.
        val sourceHeaders = source?.let {
            com.wallpaperswitcher.engine.legado.LegadoRss.parseHeaderMap(
                com.wallpaperswitcher.engine.legado.RssSourceEditor.fieldValue(it.rawJson, "header"),
                it.id,
                it.url,
            )
        }.orEmpty()
        // Headers the full-screen browser saw the site's own player use (signed
        // CDNs 403 without them) win over the rule's static header.
        val headers = sourceHeaders + extraHeaders
        val referer = headers.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
            ?: source?.url?.let { OnlineSourceRules.originOf(it) }
        val dir = File(context.filesDir, "rss/${source?.id ?: 0}")
        if (!dir.exists() && !dir.mkdirs()) return@withContext Report(0, urls.size)
        // Remove temp files left behind by an interrupted sync (older than 1h).
        try {
            val cutoff = System.currentTimeMillis() - 60L * 60 * 1000
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("rss_") && file.name.endsWith(".tmp") &&
                    file.lastModified() < cutoff
                ) {
                    file.delete()
                }
            }
        } catch (_: Throwable) {
        }

        // 1) Download + validate + publish in parallel (bounded), remembering
        //    which URL produced which file so failures can be reported per URL.
        //
        // 原图优先: the page's URL is often a resized variant (`-300x200.jpg`,
        // `?w=300`). Download the upgraded original instead, falling back to
        // the page URL when the guess does not exist. Two URLs of the same
        // image collapse into one download after the upgrade.
        //
        // 速度: 校验（bounds 解码）与发布（拷进用户选的 SAF 目录）以前是"全部
        // 下完再一张张串行做"，几十张图时后半段纯排队；现在每个 URL 的下载 →
        // 校验 → 发布在同一个任务里完成，和别的下载重叠。
        val distinctUrls = urls.distinct()
        val candidates = ArrayList<Pair<String, String>>(distinctUrls.size)
        val failedCount = AtomicInteger(0)
        for (original in distinctUrls) {
            val request = OriginalImageUrl.upgrade(original)
            if (OnlineSourceRules.endpointPolicy(request, allowCleartext = true) ==
                OnlineSourceRules.EndpointPolicy.INVALID
            ) {
                AppLog.w(TAG, "invalid endpoint: ${original.take(80)}")
                failedCount.incrementAndGet()
                continue
            }
            candidates.add(original to request)
        }
        val targets = candidates.distinctBy { it.second }
        if (targets.size != candidates.size) {
            AppLog.d(TAG, "原图优先: ${candidates.size - targets.size} duplicate URL(s) collapsed")
        }
        val prepared = ArrayList<Prepared>(targets.size)
        val downloadedBytes = AtomicLong(0L)
        val publishedToTree = AtomicInteger(0)
        // 用户可以在设置里指定下载目录（SAF）；没指定就留在应用私有目录。
        val downloadTree = RssDownloadDir.load(context)
        coroutineScope {
            val gate = Semaphore(DOWNLOAD_PARALLELISM)
            val jobs = targets.map { (original, request) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val file = downloadWithFallback(
                            request, original, headers, referer, dir
                        ) ?: return@withPermit null
                        downloadedBytes.addAndGet(file.length())
                        // Validate + publish right here: the next image is
                        // already downloading on another slot while this one
                        // gets decoded/copied.
                        prepareDownloadedMedia(
                            context, downloadTree, file, existing, publishedToTree
                        )
                    }
                }
            }
            for (job in jobs) {
                val item = job.await()
                if (item == null) failedCount.incrementAndGet() else prepared.add(item)
            }
        }
        // Charge today's counter with everything pulled from the network - the
        // files that fail validation below still cost the user traffic.
        RssDownloadPolicy.record(context, downloadedBytes.get())

        // 2) Batch de-duplication (the parallel jobs above only READ the
        //    existing set), then insert the whole batch in one call.
        val rows = ArrayList<WallpaperImage>(prepared.size)
        for (item in prepared) {
            // 同一批里两条 URL 落到同一个文件（内容相同）时只留一行。
            if (!existing.add(item.uri)) continue
            rows.add(
                WallpaperImage(
                    groupId = targetGroup,
                    uri = item.uri,
                    displayName = item.displayName,
                    mediaType = item.mediaType,
                    isFromFolder = false,
                    folderPath = "rss/${source?.id ?: 0}",
                    width = item.width,
                    height = item.height,
                )
            )
        }
        var added = 0
        var failed = failedCount.get()
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
        if (publishedToTree.get() > 0) {
            AppLog.d(TAG, "published ${publishedToTree.get()} file(s) into $downloadTree")
        }
        AppLog.d(
            TAG,
            "import done: added=$added failed=$failed in " +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms"
        )
        Report(added, failed)
    }

    /** One validated download, ready to become a media row. */
    private class Prepared(
        val uri: String,
        val displayName: String,
        val mediaType: String,
        val width: Int,
        val height: Int,
    )

    /**
     * Validate one downloaded file and (when the user configured a download
     * folder) copy it into that tree, returning the row data - or null when the
     * file is not usable media.
     *
     * [knownUris] is only read here: the batch-level de-duplication runs after
     * every job finished, so this can run on several threads at once.
     */
    private fun prepareDownloadedMedia(
        context: Context,
        downloadTree: String,
        file: File,
        knownUris: Set<String>,
        publishedToTree: AtomicInteger,
    ): Prepared? {
        val isVideo = VIDEO_EXT.containsMatchIn(file.name.lowercase())
        if (isVideo) {
            // Videos cannot be bounds-decoded; the player probes them at
            // playback time, so width/height stay 0 here.
            if (!looksLikeVideoFile(file)) {
                // An HTML error page / m3u8 playlist saved as .mp4 would be
                // unplayable - reject it instead of polluting the group.
                file.delete()
                return null
            }
            var uri = "file://${file.path}"
            if (uri !in knownUris) {
                val published = publishToTree(context, downloadTree, file, isVideo = true)
                if (published != null) {
                    uri = published
                    file.delete()
                    publishedToTree.incrementAndGet()
                }
            }
            return Prepared(uri, file.name, MediaTypes.VIDEO, 0, 0)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            return null
        }
        var uri = "file://${file.path}"
        if (uri !in knownUris) {
            val published = publishToTree(context, downloadTree, file, isVideo = false)
            if (published != null) {
                uri = published
                file.delete()
                publishedToTree.incrementAndGet()
            }
        }
        return Prepared(uri, file.name, MediaTypes.IMAGE, bounds.outWidth, bounds.outHeight)
    }

    /**
     * Copies a validated download into the user-chosen folder and returns its
     * `content://` URI, or null when no folder is configured / the write failed
     * (the caller then keeps the app-private file).
     */
    private fun publishToTree(
        context: Context,
        treeUri: String,
        source: File,
        isVideo: Boolean,
    ): String? {
        if (treeUri.isBlank()) return null
        return try {
            val tree = androidx.documentfile.provider.DocumentFile
                .fromTreeUri(context, android.net.Uri.parse(treeUri)) ?: return null
            if (!tree.canWrite()) return null
            val existingDoc = tree.findFile(source.name)
            val doc = existingDoc ?: tree.createFile(mimeOf(source.name, isVideo), source.name)
            doc ?: return null
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return null
            doc.uri.toString()
        } catch (t: Throwable) {
            AppLog.w(TAG, "publish failed: ${t.javaClass.simpleName}: ${t.message?.take(80)}")
            null
        }
    }

    private fun mimeOf(name: String, isVideo: Boolean): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "ts" -> "video/mp2t"
            "gif" -> "image/gif"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            else -> if (isVideo) "video/*" else "image/jpeg"
        }
    }

    /** The target group, or the shared auto-created 在线壁纸 group. */
    private suspend fun resolveGroup(
        context: Context,
        db: AppDatabase,
        groupId: Long,
    ): Long {
        if (groupId > 0L) {
            db.wallpaperGroupDao().getGroupById(groupId)?.let { return it.id }
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

    /**
     * Why a download attempt failed decides whether retrying is worth it:
     * a 404/403 (or an oversized/non-media response) will fail the same way
     * three times, and the original-URL guess plus its fallback used to burn up
     * to 5 requests per image. Permanent failures return immediately so the
     * caller can fall back or move on; only transient ones (5xx, I/O, empty
     * body) use the retry budget.
     */
    private sealed class DownloadOutcome {
        class Ok(val file: File) : DownloadOutcome()
        object Permanent : DownloadOutcome()
        object Transient : DownloadOutcome()
    }

    /**
     * Download [request], falling back to the page's own [original] URL when the
     * "原图优先" guess does not exist - a wrong guess must never lose an image.
     * Permanent failures skip their remaining retries (see [DownloadOutcome]).
     */
    private fun downloadWithFallback(
        request: String,
        original: String,
        headers: Map<String, String>,
        referer: String?,
        dir: File,
    ): File? {
        // CDNs sometimes drop a connection mid-body; a fresh attempt usually
        // succeeds, so transient failures retry before we report them.
        attemptDownload(request, headers, referer, dir, attempts = 3)?.let { return it }
        if (request == original) return null
        AppLog.d(TAG, "original url failed, falling back to the page url")
        return attemptDownload(original, headers, referer, dir, attempts = 2)
    }

    private fun attemptDownload(
        url: String,
        headers: Map<String, String>,
        referer: String?,
        dir: File,
        attempts: Int,
    ): File? {
        for (attempt in 1..attempts) {
            when (val outcome = download(url, headers, referer, dir)) {
                is DownloadOutcome.Ok -> return outcome.file
                // 404/403/太大/不是媒体: 再试也是一样的结果。
                DownloadOutcome.Permanent -> return null
                DownloadOutcome.Transient -> Unit
            }
        }
        AppLog.w(TAG, "download gave up after $attempts attempt(s): ${url.takeLast(70)}")
        return null
    }

    private fun download(
        url: String,
        headers: Map<String, String>,
        referer: String?,
        dir: File,
    ): DownloadOutcome {
        AppLog.d(TAG, "GET $url")
        if (url.lowercase().contains(".m3u8")) {
            // HLS goes through its own playlist/segment path; a missing
            // playlist is permanent, a segment failure is worth retrying.
            val file = downloadHls(url, headers, referer, dir)
            return if (file != null) DownloadOutcome.Ok(file) else DownloadOutcome.Transient
        }
        val builder = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT)
        } catch (t: Throwable) {
            AppLog.w(TAG, "bad url: ${t.javaClass.simpleName} ${url.take(80)}")
            return DownloadOutcome.Permanent
        }
        var hasReferer = false
        for ((name, value) in headers) {
            if (name.equals("Referer", ignoreCase = true)) hasReferer = true
            try {
                builder.header(name, value)
            } catch (_: Exception) {
            }
        }
        if (!hasReferer && referer != null) builder.header("Referer", referer)
        // Stable temp name + Range resume: media CDNs frequently truncate a
        // response ("unexpected end of stream"); each retry continues where the
        // previous one stopped instead of starting over. The Range header has to
        // be on the request built below - adding it to `builder` after execute()
        // (the old code) never reached the server, so every retry restarted at 0.
        val temp = File(dir, "rss_" + urlDigest(url) + ".tmp")
        val already = if (temp.exists()) temp.length() else 0L
        val validator = resumeValidators[url]
        if (already > 0L && validator != null) {
            // Ask the server to treat the resume as valid ONLY while the object
            // is unchanged: an ETag mismatch answers 200 (whole file) instead of
            // concatenating two different versions into one broken file.
            builder.header("If-Range", validator)
        }
        if (already > 0L) builder.header("Range", "bytes=$already-")
        downloadClient.newCall(builder.get().build()).execute().use { response ->
            if (response.code == 416) {
                // The temp file is already at/after the remote length: drop it
                // so the next attempt starts clean instead of looping.
                AppLog.w(TAG, "range not satisfiable, restarting: ${url.takeLast(50)}")
                temp.delete()
                return DownloadOutcome.Transient
            }
            if (!response.isSuccessful) {
                AppLog.w(TAG, "http ${response.code} for ${url.takeLast(60)}")
                // 4xx 再试也是一样（408/429 是"稍后再来"，仍算可重试）。
                val code = response.code
                val retryable = code >= 500 || code == 408 || code == 429
                return if (retryable) DownloadOutcome.Transient else DownloadOutcome.Permanent
            }
            val body = response.body ?: return DownloadOutcome.Transient
            // Remember (or refresh) the validator of the object these bytes come
            // from, so the NEXT resume can prove it is still the same object.
            response.header("ETag")?.let { resumeValidators[url] = it }
                ?: response.header("Last-Modified")?.let { resumeValidators[url] = it }
            val type = response.header("Content-Type").orEmpty()
            val extension = OnlineSourceRules.imageExtension(url, type)
                ?: videoExtension(url, type)
                ?: run {
                    AppLog.w(TAG, "unknown media type '$type': ${url.takeLast(50)}")
                    resumeValidators.remove(url)
                    return DownloadOutcome.Permanent
                }
            // 200 here means the server ignored/refused the range (the file
            // changed - see If-Range above): the writer below reopens the temp
            // file with `append=false`, i.e. starts over instead of splicing.
            val resume = already > 0L && response.code == 206
            if (already > 0L && response.code == 200 && validator != null) {
                AppLog.d(
                    TAG,
                    "remote object changed (If-Range rejected); restarting download: " +
                        url.takeLast(40)
                )
                resumeValidators.remove(url)
            }
            val limit = if (resume) MAX_BYTES - already else MAX_BYTES
            if (limit <= 0L || body.contentLength() > limit) {
                AppLog.w(TAG, "too large (${body.contentLength()}): ${url.takeLast(50)}")
                if (limit <= 0L) {
                    temp.delete()
                    resumeValidators.remove(url)
                }
                return DownloadOutcome.Permanent
            }
            // Hash while writing: the final file name is the content hash, and
            // re-reading the whole file just to hash it cost another pass over
            // every (up to 30MB) video. A resumed download seeds the digest
            // with the bytes already on disk.
            val copyDeadlineAt =
                android.os.SystemClock.elapsedRealtime() + BODY_COPY_TIMEOUT_MS
            val digest = MessageDigest.getInstance("SHA-256")
            if (resume) {
                temp.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
            var written = 0L
            body.byteStream().use { input ->
                FileOutputStream(temp, resume).use { output ->
                    // Bound each attempt: a trickling CDN resets the socket
                    // read timeout on every byte, so without a deadline one
                    // download could hold a parallelism permit forever. The
                    // partial temp survives a timeout (resume), so the retry
                    // continues where this one stopped instead of starting over.
                    val copied = StreamCopy.copy(
                        input = input,
                        output = output,
                        maxBytes = limit,
                        deadlineAtMs = copyDeadlineAt,
                        nowMs = { android.os.SystemClock.elapsedRealtime() },
                        onChunk = { buffer, read -> digest.update(buffer, 0, read) },
                    )
                    written = copied.bytes
                    if (copied.timedOut) {
                        val slow = "download too slow after ${BODY_COPY_TIMEOUT_MS / 1000}s"
                        AppLog.w(TAG, "$slow: ${url.takeLast(50)}")
                        if (!resume) temp.delete()
                        return DownloadOutcome.Transient
                    }
                    if (copied.cancelled) {
                        if (!resume) temp.delete()
                        return DownloadOutcome.Transient
                    }
                    if (copied.exceededLimit) {
                        temp.delete()
                        return DownloadOutcome.Permanent
                    }
                }
            }
            if (written == 0L) {
                // No progress this attempt. A 200 truncated the temp file, so
                // it must go; a 206 leaves the existing bytes for the retry.
                if (!resume) {
                    AppLog.w(TAG, "empty body: ${url.takeLast(60)}")
                    temp.delete()
                }
                return DownloadOutcome.Transient
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(dir, "$hash.$extension")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            // The download is finished: the validator was only needed to prove
            // the resume was the same object. Dropping it keeps the process-wide
            // map from growing by one entry per downloaded URL forever.
            resumeValidators.remove(url)
            return DownloadOutcome.Ok(target)
        }
    }

    /**
     * Stable 16-hex-character digest of [url], used for temp/HLS file names.
     *
     * `url.hashCode()` was a 32-bit value: two different media URLs could share
     * it, and a collision made a resumed download append the OTHER URL's bytes
     * into the same temp file (the merged file then still passed the 16-byte
     * header sniff and was published). SHA-256 truncated to 64 bits keeps the
     * names short without ever colliding in practice.
     */
    private fun urlDigest(url: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append("%02x".format(bytes[i]))
        return sb.toString()
    }

    /**
     * Resume validators of the downloads in flight (url -> ETag/Last-Modified).
     *
     * Only two things are needed from it: the `If-Range` value of a resume, and
     * knowing that the temp bytes belong to the same remote object. Process-local
     * on purpose - after a restart the temp file is simply resumed with a plain
     * Range again, which is the old behaviour.
     */
    private val resumeValidators = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Video download support: 阅读 video sources feed `.mp4`/`.m3u8` links. */
    private fun videoExtension(url: String, contentType: String): String? {
        val lowerType = contentType.lowercase()
        if (lowerType.startsWith("video/")) {
            return when {
                lowerType.contains("webm") -> "webm"
                lowerType.contains("quicktime") -> "mov"
                else -> "mp4"
            }
        }
        val lower = url.lowercase().substringBefore('?')
        return VIDEO_EXT.find(lower)?.groupValues?.get(1)
    }

    private val VIDEO_EXT = Regex("""\.(mp4|webm|mov|m4v|mkv|ts|m3u8)$""")

    /** Hard cap for one merged HLS "video" (see downloadHls). */
    private const val MAX_HLS_BYTES = 512L * 1024 * 1024

    /**
     * HLS: fetch the playlist, download every segment and concatenate them into
     * one MPEG-TS file the wallpaper engine can play (no new dependency).
     */
    private fun downloadHls(
        url: String,
        headers: Map<String, String>,
        referer: String?,
        dir: File,
        depth: Int = 0,
    ): File? {
        val playlist = requestBytes(url, headers, referer)?.toString(Charsets.UTF_8)
        if (playlist == null) {
            AppLog.w(TAG, "hls playlist failed: ${url.takeLast(60)}")
            return null
        }
        // Master playlist (多码率): follow the highest bandwidth variant.
        if (depth < 3 && playlist.contains("#EXT-X-STREAM-INF")) {
            val variant = bestHlsVariant(playlist, url)
            if (variant == null) {
                AppLog.w(TAG, "hls master playlist without a usable variant")
                return null
            }
            AppLog.d(TAG, "hls master -> ${variant.takeLast(50)}")
            return downloadHls(variant, headers, referer, dir, depth + 1)
        }
        var initUri: String? = null
        var encrypted = false
        val segments = ArrayList<String>()
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MAP:") -> initUri = hlsAttribute(line, "URI")
                line.startsWith("#EXT-X-KEY:") &&
                    !line.contains("METHOD=NONE", ignoreCase = true) -> encrypted = true
                line.startsWith("#") -> Unit
                else -> segments.add(resolveHlsUrl(line, url))
            }
        }
        if (segments.isEmpty()) {
            AppLog.w(TAG, "hls playlist has no segments (${playlist.length} bytes)")
            return null
        }
        if (encrypted) {
            // AES-128 playlists need a key fetch + per-segment decryption.
            AppLog.w(TAG, "hls playlist is encrypted - skipped")
            return null
        }
        // fMP4 streams need their init segment in front of the fragments;
        // without it the merged file has no moov and will not play.
        val init = initUri?.let { requestBytes(resolveHlsUrl(it, url), headers, referer) }
        val fmp4 = init != null || segments.first().lowercase().let {
            it.endsWith(".mp4") || it.endsWith(".m4s")
        }
        val target = File(dir, "rss_${urlDigest(url)}.${if (fmp4) "mp4" else "ts"}")
        var done = 0
        var totalBytes = 0L
        try {
            target.outputStream().use { out ->
                init?.let { out.write(it) }
                for (segment in segments.take(MAX_HLS_SEGMENTS)) {
                    // One stalled/throttled segment must not kill the download:
                    // retry it twice before giving up (and say which one).
                    var bytes: ByteArray? = null
                    for (attempt in 1..3) {
                        bytes = requestBytes(segment, headers, referer)
                        if (bytes != null) break
                    }
                    val segmentBytes = bytes
                    if (segmentBytes == null) {
                        AppLog.w(TAG, "hls segment failed: ${segment.takeLast(60)}")
                        target.delete()
                        return null
                    }
                    out.write(segmentBytes)
                    done++
                    totalBytes += segmentBytes.size
                    if (totalBytes > MAX_HLS_BYTES) {
                        // MAX_HLS_SEGMENTS bounds the COUNT, not the size: an
                        // endless/adversarial playlist could otherwise fill the
                        // data partition with one "video".
                        AppLog.w(TAG, "hls too large (>${MAX_HLS_BYTES / (1024 * 1024)}MB) - aborted")
                        target.delete()
                        return null
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "hls write failed: ${t.javaClass.simpleName}")
            target.delete()
            return null
        }
        AppLog.d(TAG, "hls merged $done segments -> ${target.name} (${target.length() / 1024}KB)")
        return target.takeIf { it.length() > 0L }
    }

    /** Highest-bandwidth entry of a master playlist (`#EXT-X-STREAM-INF`). */
    private fun bestHlsVariant(playlist: String, baseUrl: String): String? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        var best: Pair<Long, String>? = null
        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val bandwidth = hlsAttribute(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
            val uri = lines.drop(index + 1).firstOrNull {
                it.isNotEmpty() && !it.startsWith("#")
            } ?: return@forEachIndexed
            if (best == null || bandwidth > best!!.first) {
                best = bandwidth to resolveHlsUrl(uri, baseUrl)
            }
        }
        return best?.second
    }

    /** `URI="…"` / `BANDWIDTH=1234` out of an HLS tag. */
    private fun hlsAttribute(line: String, name: String): String? {
        val match = Regex("""$name=(?:"([^"]*)"|([^,]*))""").find(line) ?: return null
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim().ifEmpty { null }
    }

    /** Relative playlists/segments resolve against the playlist URL. */
    private fun resolveHlsUrl(entry: String, playlistUrl: String): String = when {
        entry.startsWith("http://") || entry.startsWith("https://") -> entry
        entry.startsWith("/") ->
            Regex("""^(https?://[^/]+)""").find(playlistUrl)?.groupValues?.get(1) + entry
        else -> playlistUrl.substringBeforeLast('/') + "/" + entry
    }

    /** Plain GET with the source's headers (playlists + HLS segments). */
    private fun requestBytes(
        url: String,
        headers: Map<String, String>,
        referer: String?,
    ): ByteArray? {
        val builder = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT)
        } catch (_: Throwable) {
            return null
        }
        for ((name, value) in headers) {
            if (name.equals("Referer", ignoreCase = true)) continue
            try {
                builder.header(name, value)
            } catch (_: Exception) {
            }
        }
        val effectiveReferer = headers.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value ?: referer
        if (!effectiveReferer.isNullOrBlank()) builder.header("Referer", effectiveReferer)
        return try {
            downloadClient.newCall(builder.get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "http ${response.code} (playlist/segment): ${url.takeLast(50)}")
                    return null
                }
                val body = response.body ?: return null
                if (body.contentLength() > MAX_BYTES) return null
                val bytes = body.bytes()
                if (bytes.size.toLong() > MAX_BYTES) null else bytes
            }
        } catch (t: Throwable) {
            AppLog.w(
                TAG,
                "request failed: ${t.javaClass.simpleName}: " +
                    t.message?.take(90).orEmpty() + " " + url.takeLast(45),
            )
            null
        }
    }

    private const val MAX_HLS_SEGMENTS = 4000

    /** First bytes decide: MP4/MOV (`ftyp`), WebM/MKV (`EBML`), never HTML/HLS. */
    private fun looksLikeVideoFile(file: File): Boolean = try {
        val head = ByteArray(16)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) {
            false
        } else {
            val text = String(head, 0, read, Charsets.US_ASCII).lowercase()
            val ebml = head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
            // fMP4 fragments start with styp/moof when the init segment is
            // missing; accept them so a merged HLS file is not thrown away.
            !text.startsWith("<!doctype") && !text.startsWith("<html") &&
                !text.startsWith("#extm3u") &&
                (ebml || head[0] == 0x47.toByte() || text.contains("ftyp") ||
                    text.contains("moov") || text.contains("styp") || text.contains("moof"))
        }
    } catch (_: Throwable) {
        false
    }
}


