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
    /** Parallel image downloads; the sites are small and this is the main win. */
    private const val DOWNLOAD_PARALLELISM = 4

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

    data class Report(val added: Int, val failed: Int)

    suspend fun importImages(
        context: Context,
        source: RssSource?,
        urls: List<String>,
        groupId: Long,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Report = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext Report(0, 0)
        AppLog.d(
            TAG,
            "import start: ${urls.size} url(s) -> group $groupId " +
                urls.take(3).joinToString(" | ") { it.take(110) },
        )
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

        // 1) Download in parallel (bounded), remembering which URL produced
        //    which file so failures can be reported per URL.
        val targets = urls.distinct().filter { url ->
            OnlineSourceRules.endpointPolicy(url, allowCleartext = true) !=
                OnlineSourceRules.EndpointPolicy.INVALID
        }
        var failed = 0
        if (targets.size != urls.distinct().size) {
            AppLog.w(
                TAG,
                "invalid endpoint(s): " + urls.distinct().filterNot { it in targets }
                    .joinToString(" | ") { it.take(80) },
            )
        }
        failed += urls.size - targets.size
        val downloaded = ArrayList<Pair<String, File>>(targets.size)
        coroutineScope {
            val gate = Semaphore(DOWNLOAD_PARALLELISM)
            val jobs = targets.map { url ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        // CDNs sometimes drop a connection mid-body; a fresh
                        // attempt usually succeeds, so retry twice before
                        // reporting the failure (with the real message).
                        var file: File? = null
                        var lastError: Throwable? = null
                        for (attempt in 1..3) {
                            try {
                                file = download(url, headers, referer, dir)
                                lastError = null
                                break
                            } catch (t: Throwable) {
                                lastError = t
                            }
                        }
                        if (file == null && lastError != null) {
                            AppLog.w(
                                TAG,
                                "download failed: ${lastError.javaClass.simpleName}: " +
                                    lastError.message?.take(160).orEmpty()
                            )
                        } else if (file == null) {
                            AppLog.w(TAG, "download produced nothing: ${url.takeLast(70)}")
                        }
                        url to file
                    }
                }
            }
            for (job in jobs) {
                val (url, file) = job.await()
                if (file == null) failed++ else downloaded.add(url to file)
            }
        }

        // 2) Validate + de-duplicate, then insert the whole batch in one call.
        // 用户可以在设置里指定下载目录（SAF）；没指定就留在应用私有目录。
        val downloadTree = RssDownloadDir.load(context)
        var publishedToTree = 0
        val rows = ArrayList<WallpaperImage>(downloaded.size)
        for ((_, file) in downloaded) {
            var uri = "file://${file.path}"
            if (uri in existing) {
                file.delete()
                continue
            }
            val isVideo = VIDEO_EXT.containsMatchIn(file.name.lowercase())
            if (isVideo) {
                // Videos cannot be bounds-decoded; the player probes them at
                // playback time, so width/height stay 0 here.
                if (!looksLikeVideoFile(file)) {
                    // An HTML error page / m3u8 playlist saved as .mp4 would be
                    // unplayable - reject it instead of polluting the group.
                    file.delete()
                    failed++
                    continue
                }
                val published = publishToTree(context, downloadTree, file, isVideo = true)
                if (published != null) {
                    uri = published
                    file.delete()
                    publishedToTree++
                }
                existing.add(uri)
                rows.add(
                    WallpaperImage(
                        groupId = targetGroup,
                        uri = uri,
                        displayName = file.name,
                        mediaType = MediaTypes.VIDEO,
                        isFromFolder = false,
                        folderPath = "rss/${source?.id ?: 0}",
                        width = 0,
                        height = 0,
                    )
                )
                continue
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                file.delete()
                failed++
                continue
            }
            val published = publishToTree(context, downloadTree, file, isVideo = false)
            if (published != null) {
                uri = published
                file.delete()
                publishedToTree++
            }
            existing.add(uri)
            rows.add(
                WallpaperImage(
                    groupId = targetGroup,
                    uri = uri,
                    displayName = file.name,
                    mediaType = MediaTypes.IMAGE,
                    isFromFolder = false,
                    folderPath = "rss/${source?.id ?: 0}",
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                )
            )
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
        if (publishedToTree > 0) {
            AppLog.d(TAG, "published $publishedToTree file(s) into $downloadTree")
        }
        AppLog.d(TAG, "import done: added=$added failed=$failed")
        Report(added, failed)
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

    private fun download(
        url: String,
        headers: Map<String, String>,
        referer: String?,
        dir: File,
    ): File? {
        AppLog.d(TAG, "GET $url")
        if (url.lowercase().contains(".m3u8")) return downloadHls(url, headers, referer, dir)
        val builder = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT)
        } catch (t: Throwable) {
            AppLog.w(TAG, "bad url: ${t.javaClass.simpleName} ${url.take(80)}")
            return null
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
        downloadClient.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.w(TAG, "http ${response.code} for ${url.takeLast(60)}")
                return null
            }
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) {
                AppLog.w(TAG, "too large (${body.contentLength()}): ${url.takeLast(50)}")
                return null
            }
            val type = response.header("Content-Type").orEmpty()
            val extension = OnlineSourceRules.imageExtension(url, type)
                ?: videoExtension(url, type)
                ?: run {
                    AppLog.w(TAG, "unknown media type '$type': ${url.takeLast(50)}")
                    return null
                }
            // Stable temp name + Range resume: media CDNs frequently truncate a
            // response ("unexpected end of stream"); each retry continues where
            // the previous one stopped instead of starting over.
            val temp = File(dir, "rss_" + Integer.toHexString(url.hashCode()) + ".tmp")
            val already = if (temp.exists()) temp.length() else 0L
            if (already > 0L) builder.header("Range", "bytes=$already-")
            var total = 0L
            val resume = already > 0L && response.code == 206
            body.byteStream().use { input ->
                FileOutputStream(temp, resume).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            temp.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total <= 0L && !resume) {
                AppLog.w(TAG, "empty body: ${url.takeLast(60)}")
                temp.delete()
                return null
            }
            val digest = MessageDigest.getInstance("SHA-256")
            temp.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(dir, "$hash.$extension")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target
        }
    }

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
        val target = File(dir, "rss_${Integer.toHexString(url.hashCode())}.${if (fmp4) "mp4" else "ts"}")
        var done = 0
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
                    if (bytes == null) {
                        AppLog.w(TAG, "hls segment failed: ${segment.takeLast(60)}")
                        target.delete()
                        return null
                    }
                    out.write(bytes)
                    done++
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


