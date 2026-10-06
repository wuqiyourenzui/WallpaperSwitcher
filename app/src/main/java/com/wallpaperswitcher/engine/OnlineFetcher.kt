package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.OnlineSource
import com.wallpaperswitcher.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Network layer of the online wallpaper sources, built on OkHttp (already on
 * the classpath through Coil; a plain HttpURLConnection rejects the WebDAV
 * PROPFIND method on Android).
 *
 * Power / privacy choices, deliberately conservative:
 *
 *  - one request at a time (the sync is serialized), short timeouts, a hard cap
 *    of [MAX_DOWNLOAD_BYTES] per file and [MAX_NEW_PER_RUN] new files per run,
 *    so a scheduled sync cannot drain the battery or the user's data plan;
 *  - no idle sockets are kept (`ConnectionPool(0, …)`): once a sync is done its
 *    radio/CPU wakeups are over, which is what a background wallpaper fetch
 *    should cost;
 *  - the runtime log records the source type, host and HTTP status - never the
 *    full URL (it may carry a token) and never a credential;
 *  - OkHttp strips the Authorization header on a cross-host redirect, so the
 *    WebDAV password cannot be forwarded to another server.
 */
internal object OnlineFetcher {

    const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024
    const val MAX_NEW_PER_RUN = 8
    private const val MAX_BING_ITEMS = 8
    private const val MAX_LISTING_BYTES = 4L * 1024 * 1024
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val CALL_TIMEOUT_SECONDS = 90L
    private const val USER_AGENT = "WallpaperSwitcher/1.1 (Android)"
    private const val TAG = "OnlineFetcher"

    private const val BING_ARCHIVE_URL =
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8"

    // --- 内置在线壁纸源（设置里的选项） -------------------------------------
    private const val APOD_PAGE_URL = "https://science.nasa.gov/apod/"
    private const val WIKIMEDIA_FEED_URL =
        "https://commons.wikimedia.org/w/api.php?action=featuredfeed&feed=potd"
    private const val NETBIAN_ORIGIN = "https://pic.netbian.com"
    private val NETBIAN_CATEGORIES = listOf(
        "/4kfengjing/", "/4kdongman/", "/4kmeinv/", "/4kziran/", "/4kyouxi/", "/4kqiche/",
    )
    private const val IOLIU_HOME = "https://bing.ioliu.cn/"

    private val PROPFIND_BODY = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="DAV:"><D:prop>
        <D:getlastmodified/><D:getcontentlength/><D:resourcetype/>
        </D:prop></D:propfind>
    """.trimIndent()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // No keep-alive sockets between syncs: an online source runs at most
            // a few times a day, so holding an idle connection only keeps the
            // radio awake for nothing.
            .connectionPool(ConnectionPool(0, 1L, TimeUnit.MILLISECONDS))
            .build()
    }

    /** A remote file that can be downloaded. */
    data class RemoteItem(
        val remoteKey: String,
        val url: String,
        val displayName: String,
        val lastModifiedMs: Long = 0L,
    )

    sealed class Download {
        data class Success(
            val file: File,
            val contentHash: String,
            val extension: String,
            val etag: String,
            val lastModified: String,
        ) : Download()

        /** HTTP 304: the URL source still serves the media we already have. */
        data object NotModified : Download()

        data class Failure(val reason: String) : Download()
    }

    /** Thrown with a stable, language-neutral reason code (see OnlineResult). */
    class FetchException(
        val reason: String,
        val httpStatus: Int = 0,
        cause: Throwable? = null,
    ) : Exception(reason, cause)

    /**
     * 美人图 listing: the new images of the newest unprocessed albums plus the
     * albums that were fully processed (their `album:<id>` marker may be
     * written so the next sync moves on to the following albums).
     */
    data class MeirentuListing(
        val items: List<RemoteItem>,
        val completedAlbums: List<Long>,
    )

    /** Bing 每日图: the API lists the last days; the caller de-dupes by date. */
    suspend fun listBing(): List<RemoteItem> = withContext(Dispatchers.IO) {
        val request = requestBuilder(BING_ARCHIVE_URL).get().build()
        client.newCall(request).execute().use { response ->
            checkStatus(response, "BING")
            val body = response.peekBody(512 * 1024).string()
            val images = OnlineSourceRules.parseBingPayload(body)
            if (images.isEmpty()) throw FetchException("parse")
            images.take(MAX_BING_ITEMS).map { image ->
                RemoteItem(
                    remoteKey = image.remoteKey,
                    url = image.url,
                    displayName = "Bing_${image.remoteKey}.jpg",
                )
            }
        }
    }

    /** Plain GET of an HTML/JSON page with the caller's referer and a size cap. */
    private suspend fun getText(url: String, referer: String? = null): String =
        withContext(Dispatchers.IO) {
            val request = requestBuilder(url, credentials = null, referer = referer)
                // 部分站点（彼岸图网等）会按 UA/缺省头拒绝非浏览器请求。
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                checkStatus(response, "ONLINE")
                response.peekBody(MAX_LISTING_BYTES).string()
            }
        }

    /** NASA APOD: today's hero image (1 item; video days yield a parse error). */
    suspend fun listNasaApod(): List<RemoteItem> {
        val image = OnlineSourceRules.parseApodPage(getText(APOD_PAGE_URL))
            ?: throw FetchException("parse")
        return listOf(RemoteItem(image.remoteKey, image.url, image.displayName))
    }

    /** Wikimedia Commons picture of the day (Atom feed, newest first). */
    suspend fun listWikimedia(): List<RemoteItem> {
        val images = OnlineSourceRules.parseWikimediaFeed(getText(WIKIMEDIA_FEED_URL))
        if (images.isEmpty()) throw FetchException("parse")
        return images.take(MAX_NEW_PER_RUN)
            .map { RemoteItem(it.remoteKey, it.url, it.displayName) }
    }

    /** 彼岸图网: one random 4K category; each item's detail page holds the original. */
    suspend fun listNetbian(maxItems: Int): List<RemoteItem> = withContext(Dispatchers.IO) {
        var links: List<String> = emptyList()
        for (category in NETBIAN_CATEGORIES.shuffled()) {
            links = try {
                OnlineSourceRules.parseNetbianListing(getText(NETBIAN_ORIGIN + category))
            } catch (_: Throwable) {
                emptyList()
            }
            if (links.isNotEmpty()) break
        }
        if (links.isEmpty()) throw FetchException("parse")
        val out = ArrayList<RemoteItem>()
        for (link in links.take(maxItems)) {
            val image = try {
                // 详情页逐页抓取，放慢一点避免被站点限流（405）。
                kotlinx.coroutines.delay(350L)
                OnlineSourceRules.parseNetbianDetail(
                    getText(link, referer = "$NETBIAN_ORIGIN/"),
                    link,
                )
            } catch (_: Throwable) {
                null
            }
            if (image != null) out.add(RemoteItem(image.remoteKey, image.url, image.displayName))
        }
        if (out.isEmpty()) throw FetchException("parse")
        out
    }

    /** 必应壁纸站: home page cards -> detail pages -> full-resolution Bing URL. */
    suspend fun listIoliu(maxItems: Int): List<RemoteItem> = withContext(Dispatchers.IO) {
        val links = OnlineSourceRules.parseIoliuListing(getText(IOLIU_HOME)).take(maxItems)
        if (links.isEmpty()) throw FetchException("parse")
        val out = ArrayList<RemoteItem>()
        for (link in links) {
            val image = try {
                OnlineSourceRules.parseIoliuDetail(getText(link, referer = IOLIU_HOME))
            } catch (_: Throwable) {
                null
            }
            if (image != null) out.add(RemoteItem(image.remoteKey, image.url, image.displayName))
        }
        if (out.isEmpty()) throw FetchException("parse")
        out
    }

    /** WebDAV PROPFIND Depth:1 on the configured collection. */
    suspend fun listWebDav(
        source: OnlineSource,
        password: String,
    ): List<RemoteItem> = withContext(Dispatchers.IO) {
        val collection = webDavCollectionUrl(source)
        val credentials = OnlineSourceRules.basicAuthHeader(source.username, password)
        val request = requestBuilder(collection, credentials)
            .header("Depth", "1")
            .method(
                "PROPFIND",
                PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()),
            )
            .build()
        client.newCall(request).execute().use { response ->
            checkStatus(response, "WEBDAV")
            val xml = response.peekBody(MAX_LISTING_BYTES).string()
            val entries = try {
                OnlineSourceRules.parseWebDavListingStrict(xml)
            } catch (t: Throwable) {
                AppLog.w(
                    TAG,
                    "WEBDAV listing parse failed (${xml.length} chars): " +
                        "${t.javaClass.simpleName}: ${t.message}"
                )
                throw FetchException("parse")
            }
            if (entries.isEmpty()) throw FetchException("parse")
            val base = URI(collection)
            entries
                .filter { entry ->
                    !OnlineSourceRules.looksLikeWebDavDirectory(entry.href) &&
                        OnlineSourceRules.imageExtension(entry.href, null) != null
                }
                .sortedByDescending { it.lastModifiedMs }
                .map { entry ->
                    RemoteItem(
                        remoteKey = entry.href,
                        url = resolveHref(base, entry.href),
                        displayName = displayNameOf(entry.href),
                        lastModifiedMs = entry.lastModifiedMs,
                    )
                }
        }
    }

    /**
     * 美人图 (meirentu.club): scrape a listing page for album links and walk
     * the first [MAX_ALBUM_PAGES] pages of the newest albums that do not have
     * an `album:<id>` marker yet.
     *
     * One album page shows three full-size photos (the covers in the listing
     * are only 560x850 thumbnails), so an album contributes up to
     * `MAX_ALBUM_PAGES * 3` images; [maxItems] caps one sync.
     */
    suspend fun listMeirentu(
        source: OnlineSource,
        knownKeys: Set<String>,
        maxItems: Int,
    ): MeirentuListing = withContext(Dispatchers.IO) {
        val listingUrl = source.url
        val origin = OnlineSourceRules.originOf(listingUrl) ?: throw FetchException("bad_url")
        val pagesPerAlbum = OnlineSourceRules.normalizePagesPerAlbum(source.pagesPerAlbum)
        val listingHtml = httpGetText(listingUrl, origin, 2L * 1024 * 1024)
        val albumIds = OnlineSourceRules.parseMeirentuAlbumIds(listingHtml)
        if (albumIds.isEmpty()) throw FetchException("parse")
        val items = ArrayList<RemoteItem>()
        val completed = ArrayList<Long>()
        for (albumId in albumIds) {
            if (items.size >= maxItems) break
            if ("album:$albumId" in knownKeys) continue
            var albumDone = true
            var pageLoadFailed = false
            for (page in 1..pagesPerAlbum) {
                val pageUrl = OnlineSourceRules.meirentuAlbumPageUrl(listingUrl, albumId, page)
                val pageHtml = try {
                    httpGetText(pageUrl, origin, MAX_LISTING_BYTES)
                } catch (t: FetchException) {
                    if (t.reason == "not_found") {
                        // A missing page 1 is a stale album link (mark it done);
                        // a missing later page just means "no more pages".
                        albumDone = true
                        pageLoadFailed = page == 1
                        break
                    }
                    throw t
                }
                for (url in OnlineSourceRules.parseMeirentuImageUrls(pageHtml, albumId, pageUrl)) {
                    if (url in knownKeys) continue
                    if (items.any { it.remoteKey == url }) continue
                    if (items.size >= maxItems) {
                        // The album is only half consumed: do NOT mark it, so
                        // the next sync continues with its remaining images.
                        albumDone = false
                        break
                    }
                    items.add(
                        RemoteItem(
                            remoteKey = url,
                            url = url,
                            displayName = displayNameOf(url),
                        )
                    )
                }
                if (!albumDone) break
            }
            if (albumDone) completed.add(albumId)
            if (pageLoadFailed) continue
        }
        MeirentuListing(items = items, completedAlbums = completed)
    }

    /**
     * 美人图 manual mode: the images the user ticked in the picker, in their
     * order. [maxItems] still bounds one sync; the rest are picked up by the
     * next run (the pick list itself stays stored on the source).
     */
    fun selectedMeirentuItems(
        selectedUrls: List<String>,
        knownKeys: Set<String>,
        maxItems: Int,
    ): List<RemoteItem> =
        selectedUrls.asSequence()
            .filter { it !in knownKeys }
            .take(maxItems)
            .map { url ->
                RemoteItem(remoteKey = url, url = url, displayName = displayNameOf(url))
            }
            .toList()

    /** Picker: album cards of a listing page (cover + label). */
    suspend fun listMeirentuAlbums(listingUrl: String): List<OnlineSourceRules.MeirentuAlbum> =
        withContext(Dispatchers.IO) {
            val origin = OnlineSourceRules.originOf(listingUrl) ?: throw FetchException("bad_url")
            val html = httpGetText(listingUrl, origin, 2L * 1024 * 1024)
            val albums = OnlineSourceRules.parseMeirentuAlbums(html, listingUrl)
            if (albums.isEmpty()) throw FetchException("parse")
            albums
        }

    /** Picker: the full-size image URLs of one album page. */
    suspend fun listMeirentuAlbumImages(
        listingUrl: String,
        albumId: Long,
        page: Int,
    ): List<String> = withContext(Dispatchers.IO) {
        val origin = OnlineSourceRules.originOf(listingUrl) ?: throw FetchException("bad_url")
        val pageUrl = OnlineSourceRules.meirentuAlbumPageUrl(listingUrl, albumId, page)
        val html = httpGetText(pageUrl, origin, MAX_LISTING_BYTES)
        OnlineSourceRules.parseMeirentuImageUrls(html, albumId, pageUrl)
    }

    /**
     * Download one item into [targetDir]. For a URL source ([useValidators])
     * the previous ETag / Last-Modified is sent, so an unchanged endpoint
     * answers 304 and nothing is transferred.
     */
    suspend fun download(
        source: OnlineSource,
        password: String,
        item: RemoteItem,
        targetDir: File,
        useValidators: Boolean,
        referer: String? = null,
    ): Download = withContext(Dispatchers.IO) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return@withContext Download.Failure("storage")
        }
        val credentials = OnlineSourceRules.basicAuthHeader(source.username, password)
        try {
            val builder = requestBuilder(item.url, credentials, referer).get()
            if (useValidators) {
                if (source.etag.isNotEmpty()) builder.header("If-None-Match", source.etag)
                if (source.lastModified.isNotEmpty()) {
                    builder.header("If-Modified-Since", source.lastModified)
                }
            }
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 304) return@withContext Download.NotModified
                if (!response.isSuccessful) {
                    throw FetchException(httpReason(response.code), response.code)
                }
                val body = response.body ?: throw FetchException("empty", response.code)
                val extension =
                    OnlineSourceRules.imageExtension(item.url, response.header("Content-Type"))
                        ?: throw FetchException("not_image", response.code)
                if (body.contentLength() > MAX_DOWNLOAD_BYTES) {
                    throw FetchException("too_large", response.code)
                }
                val temp = File.createTempFile("dl_", ".tmp", targetDir)
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    body.byteStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_DOWNLOAD_BYTES) {
                                    throw FetchException("too_large", response.code)
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    temp.delete()
                    throw t
                }
                if (total <= 0L) {
                    temp.delete()
                    throw FetchException("empty", response.code)
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                Download.Success(
                    file = temp,
                    contentHash = hash,
                    extension = extension,
                    etag = response.header("ETag").orEmpty(),
                    lastModified = response.header("Last-Modified").orEmpty(),
                )
            }
        } catch (t: FetchException) {
            Download.Failure(t.reason)
        } catch (t: Throwable) {
            Download.Failure(classify(t))
        }
    }

    /** URL of a TYPE_URL source with its validators, for tests/diagnostics. */
    fun webDavCollectionUrl(source: OnlineSource): String {
        val base = source.webdavUrl.trim().trimEnd('/')
        val path = source.webdavPath.trim().trim('/')
        val suffix = if (path.isEmpty()) "" else "/$path"
        return "$base$suffix/"
    }

    private fun requestBuilder(
        url: String,
        credentials: String? = null,
        referer: String? = null,
    ): Request.Builder {
        when (OnlineSourceRules.endpointPolicy(url)) {
            OnlineSourceRules.EndpointPolicy.NEEDS_HTTPS -> throw FetchException("https_required")
            OnlineSourceRules.EndpointPolicy.INVALID -> throw FetchException("bad_url")
            OnlineSourceRules.EndpointPolicy.OK -> Unit
        }
        val builder = try {
            Request.Builder().url(url)
        } catch (t: Throwable) {
            throw FetchException("bad_url", cause = t)
        }
        builder.header("User-Agent", USER_AGENT)
        if (credentials != null) builder.header("Authorization", credentials)
        if (referer != null) builder.header("Referer", referer)
        return builder
    }

    /** Plain GET of an HTML/JSON page with a hard size cap. */
    private fun httpGetText(url: String, referer: String, maxBytes: Long): String {
        val request = requestBuilder(url, credentials = null, referer = referer).get().build()
        client.newCall(request).execute().use { response ->
            checkStatus(response, "GET")
            return response.peekBody(maxBytes).string()
        }
    }

    private fun checkStatus(response: Response, label: String) {
        val status = response.code
        AppLog.d(TAG, "$label ${response.request.url.host} -> $status")
        if (!response.isSuccessful) throw FetchException(httpReason(status), status)
    }

    private fun resolveHref(base: URI, href: String): String =
        try {
            base.resolve(href).toString()
        } catch (_: Exception) {
            href
        }

    private fun displayNameOf(href: String): String {
        val raw = href.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return try {
            URLDecoder.decode(raw, "UTF-8").ifBlank { "online.jpg" }
        } catch (_: Exception) {
            raw.ifBlank { "online.jpg" }
        }
    }

    private fun httpReason(status: Int): String = when (status) {
        401 -> "auth"
        403 -> "forbidden"
        404 -> "not_found"
        429 -> "rate_limited"
        in 500..599 -> "server"
        else -> "http_$status"
    }

    fun classify(t: Throwable): String = when (t) {
        is FetchException -> t.reason
        is UnknownHostException -> "network"
        is SocketTimeoutException -> "timeout"
        is SSLException -> "ssl"
        is IOException -> "network"
        else -> "unknown"
    }
}
