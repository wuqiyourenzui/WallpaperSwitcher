package com.wallpaperswitcher.engine

import java.io.StringReader
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Pure rules of the online wallpaper sources: interval limits, endpoint
 * policy, Bing/WebDAV parsing and the small string helpers. Kept free of
 * Android types so every rule is unit-testable on the JVM.
 *
 * Privacy model (see [endpointPolicy]): HTTPS is always allowed; cleartext
 * HTTP is only allowed for a private / local address (a NAS on the same LAN),
 * where the traffic never leaves the user's network. A public `http://` URL is
 * rejected instead of silently sending the user's request in the clear.
 */
object OnlineSourceRules {

    /** Bing image archive entry (one calendar day). */
    data class BingImage(val remoteKey: String, val url: String, val title: String)

    /** WebDAV PROPFIND entry. */
    data class WebDavEntry(
        val href: String,
        val lastModifiedMs: Long,
        val sizeBytes: Long,
    )

    /** 美人图 listing card: album id + its small cover + the model label. */
    data class MeirentuAlbum(
        val id: Long,
        val coverUrl: String,
        val label: String,
    )

    enum class EndpointPolicy { OK, NEEDS_HTTPS, INVALID }

    /**
     * Stable, language-neutral result code stored in
     * [com.wallpaperswitcher.data.OnlineSource.lastResult]. The UI localizes it
     * (so the stored text never depends on the language at fetch time).
     */
    data class ResultInfo(
        val isError: Boolean,
        val added: Int,
        val skipped: Int,
        val reason: String,
    )

    fun encodeOk(added: Int, skipped: Int): String = "ok:$added:$skipped"

    fun encodeError(reason: String): String = "err:$reason"

    fun decodeResult(raw: String): ResultInfo? {
        if (raw.startsWith("ok:")) {
            val parts = raw.split(':')
            if (parts.size != 3) return null
            return ResultInfo(
                isError = false,
                added = parts[1].toIntOrNull() ?: 0,
                skipped = parts[2].toIntOrNull() ?: 0,
                reason = "",
            )
        }
        if (raw.startsWith("err:")) {
            return ResultInfo(true, 0, 0, raw.removePrefix("err:"))
        }
        return null
    }

    /** WorkManager's periodic minimum is 15 minutes, so that is the floor. */
    fun normalizeIntervalMinutes(requested: Int): Int {
        val fallback = com.wallpaperswitcher.data.OnlineSource.DEFAULT_INTERVAL_MINUTES
        val value = if (requested <= 0) fallback else requested
        return value.coerceIn(
            com.wallpaperswitcher.data.OnlineSource.MIN_INTERVAL_MINUTES,
            com.wallpaperswitcher.data.OnlineSource.MAX_INTERVAL_MINUTES,
        )
    }

    fun normalizeKeepCount(requested: Int): Int =
        requested.coerceIn(0, com.wallpaperswitcher.data.OnlineSource.MAX_KEEP_COUNT)

    fun normalizePagesPerAlbum(requested: Int): Int {
        val value = if (requested <= 0) {
            com.wallpaperswitcher.data.OnlineSource.DEFAULT_PAGES_PER_ALBUM
        } else {
            requested
        }
        return value.coerceIn(
            com.wallpaperswitcher.data.OnlineSource.MIN_PAGES_PER_ALBUM,
            com.wallpaperswitcher.data.OnlineSource.MAX_PAGES_PER_ALBUM,
        )
    }

    fun normalizeMaxPerRun(requested: Int): Int {
        val value = if (requested <= 0) {
            com.wallpaperswitcher.data.OnlineSource.DEFAULT_MAX_PER_RUN
        } else {
            requested
        }
        return value.coerceIn(
            com.wallpaperswitcher.data.OnlineSource.MIN_MAX_PER_RUN,
            com.wallpaperswitcher.data.OnlineSource.MAX_MAX_PER_RUN,
        )
    }

    /** 美人图 manual pick list: one URL per line, blanks and duplicates removed. */
    fun parseSelectedImages(raw: String): List<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    fun encodeSelectedImages(urls: Collection<String>): String =
        urls.joinToString("\n")

    /**
     * https is always fine. http is only fine when [host] is private / local
     * (LAN NAS, localhost), because that traffic cannot leave the user's own
     * network; anything else must be upgraded to https.
     */
    /**
     * @param allowCleartext when true (imported 阅读 subscription sources) a
     *   public `http://` endpoint is accepted as well - Legado itself allows
     *   it, and the user explicitly imported that source. Online wallpaper
     *   sources keep the stricter HTTPS-only default.
     */
    fun endpointPolicy(raw: String, allowCleartext: Boolean = false): EndpointPolicy {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return EndpointPolicy.INVALID
        }
        val scheme = uri.scheme?.lowercase() ?: return EndpointPolicy.INVALID
        val host = uri.host?.lowercase() ?: return EndpointPolicy.INVALID
        return when (scheme) {
            "https" -> EndpointPolicy.OK
            "http" -> if (allowCleartext || isPrivateHost(host)) EndpointPolicy.OK
            else EndpointPolicy.NEEDS_HTTPS
            else -> EndpointPolicy.INVALID
        }
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when {
            a == 127 -> true
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    /** `https://www.bing.com` + the API's relative `url` field. */
    fun resolveBingUrl(relative: String): String {
        val value = relative.trim()
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return "https://www.bing.com" + if (value.startsWith("/")) value else "/$value"
    }

    /**
     * Parse the Bing HPImageArchive response:
     * `{"images":[{"url":"/th?id=...", "enddate":"20261002", "title":"..."}]}`.
     * Uses the project's own [Json] reader (`org.json` is a stub in unit tests).
     * Malformed input yields an empty list instead of throwing.
     */
    fun parseBingPayload(json: String): List<BingImage> {
        val root = try {
            Json.parse(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val images = (root as? Map<*, *>)?.get("images") as? List<*> ?: return emptyList()
        return images.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val url = map["url"] as? String ?: return@mapNotNull null
            val endDate = map["enddate"] as? String ?: return@mapNotNull null
            if (url.isBlank() || endDate.isBlank()) return@mapNotNull null
            BingImage(
                remoteKey = endDate,
                url = resolveBingUrl(url),
                title = map["title"] as? String ?: "",
            )
        }
    }

    /**
     * Parse a WebDAV PROPFIND listing. Namespace prefixes vary between servers
     * (`D:`, `d:`, `lp1:`), so the walker matches by local name only. External
     * entities are disabled: the XML comes from the network and must never be
     * able to read a local file or make a request.
     */
    fun parseWebDavListing(xml: String): List<WebDavEntry> =
        try {
            parseWebDavListingStrict(xml)
        } catch (_: Throwable) {
            emptyList()
        }

    /**
     * Same as [parseWebDavListing] but propagates a malformed document, so the
     * fetcher can log *why* a listing failed instead of a generic "parse".
     */
    fun parseWebDavListingStrict(xml: String): List<WebDavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's default factory throws UnsupportedOperationException for
            // XInclude / entity-expansion toggles; the features that matter are
            // attempted separately below.
            try {
                isExpandEntityReferences = false
            } catch (_: Throwable) {
            }
            try {
                isXIncludeAware = false
            } catch (_: Throwable) {
            }
            setFeatureQuietly(
                "http://apache.org/xml/features/disallow-doctype-decl", true
            )
            setFeatureQuietly(
                "http://xml.org/sax/features/external-general-entities", false
            )
            setFeatureQuietly(
                "http://xml.org/sax/features/external-parameter-entities", false
            )
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val out = ArrayList<WebDavEntry>()
        walk(doc.documentElement, out)
        return out
    }

    /** HTTP Basic header, or null when the source has no credentials. */
    fun basicAuthHeader(user: String, password: String): String? =
        if (user.isEmpty() && password.isEmpty()) null
        else "Basic " + Base64.getEncoder()
            .encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    /**
     * Image extension from the Content-Type first, then the URL path. Returns
     * null when the payload is not one of the image types the app can show
     * (an HTML error page served with 200 must never be stored as a wallpaper).
     */
    fun imageExtension(url: String, contentType: String?): String? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase()
        val fromContentType = when (ct) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> null
        }
        if (fromContentType != null) return fromContentType
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in SUPPORTED_IMAGE_EXTENSIONS }
    }

    /**
     * Host only, for the runtime log: never log a full URL, because a URL may
     * carry a token in its query string or userinfo.
     */
    fun logSafeHost(raw: String): String =
        try {
            URI(raw.trim()).host ?: "?"
        } catch (_: Exception) {
            "?"
        }

    /** Reject the URL if it is not an accepted image endpoint of this source. */
    fun looksLikeWebDavDirectory(href: String): Boolean =
        href.endsWith("/") || href.substringAfterLast('/', "").isEmpty()

    // --- 美人图 (meirentu.club) HTML scraping -------------------------------------

    /**
     * Album ids on a listing page, in document order (newest first on the
     * homepage). The hrefs look like `/pic/441354166995.html` or
     * `/pic/441354166995-2.html`; both belong to the same album.
     */
    fun parseMeirentuAlbumIds(html: String): List<Long> {
        val out = LinkedHashSet<Long>()
        for (match in MEIRENTU_ALBUM_LINK.findAll(html)) {
            match.groupValues[1].toLongOrNull()?.let { out.add(it) }
        }
        return out.toList()
    }

    /**
     * Album cards with their cover and label, used by the in-app picker.
     *
     * A card looks like
     * `<a href="/pic/<id>.html"><img data-src=".../<id>/0.jpg" alt="model">`.
     * The cover is validated by `/<id>/` so a card whose image belongs to a
     * neighbouring album cannot leak into this entry.
     */
    fun parseMeirentuAlbums(html: String, pageUrl: String): List<MeirentuAlbum> {
        val out = LinkedHashMap<Long, MeirentuAlbum>()
        for (match in MEIRENTU_ALBUM_LINK.findAll(html)) {
            val id = match.groupValues[1].toLongOrNull() ?: continue
            if (id in out) continue
            val start = match.range.first
            val end = minOf(html.length, start + ALBUM_CARD_WINDOW)
            val window = html.substring(start, end)
            val cover = IMG_URL_ATTRIBUTE.findAll(window)
                .map { it.groupValues[1].trim() }
                .firstOrNull { it.contains("/$id/") }
                ?.let { resolveUrl(pageUrl, it) }
                ?: continue
            val label = decodeHtmlEntities(
                ALT_ATTRIBUTE.find(window)?.groupValues?.get(1)?.trim().orEmpty()
            )
            out[id] = MeirentuAlbum(id = id, coverUrl = cover, label = label)
        }
        return out.values.toList()
    }

    /**
     * Decode the handful of HTML entities an `alt` / title may contain.
     * `&amp;` is replaced LAST, so `&amp;lt;` becomes `&lt;` and not `<`.
     */
    fun decodeHtmlEntities(raw: String): String {
        if ('&' !in raw) return raw
        var text = raw
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
        text = NUMERIC_ENTITY.replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull()
                ?: match.groupValues[2].toIntOrNull(16)
                ?: return@replace match.value
            try {
                String(Character.toChars(code))
            } catch (_: Exception) {
                match.value
            }
        }
        return text.replace("&amp;", "&", ignoreCase = true)
    }

    /**
     * Full-size image URLs of one album page.
     *
     * The page also renders "recommended" albums (other album ids) as lazy
     * images, so a candidate is only accepted when its path contains
     * `/<albumId>/`. Relative and protocol-relative URLs are resolved against
     * [pageUrl].
     */
    fun parseMeirentuImageUrls(
        html: String,
        albumId: Long,
        pageUrl: String,
    ): List<String> {
        val out = LinkedHashSet<String>()
        for (match in IMG_URL_ATTRIBUTE.findAll(html)) {
            val raw = match.groupValues[1].trim()
            if (raw.isEmpty() || !raw.contains("/$albumId/")) continue
            val absolute = resolveUrl(pageUrl, raw) ?: continue
            val ext = absolute.substringBefore('?').substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED_IMAGE_EXTENSIONS) continue
            out.add(absolute)
        }
        return out.toList()
    }

    /** `/pic/<id>.html` for page 1, `/pic/<id>-N.html` afterwards. */
    fun meirentuAlbumPageUrl(listingUrl: String, albumId: Long, page: Int): String {
        val origin = originOf(listingUrl) ?: "https://meirentu.club"
        return if (page <= 1) {
            "$origin/pic/$albumId.html"
        } else {
            "$origin/pic/$albumId-$page.html"
        }
    }

    /**
     * `scheme://host[:port]` of [raw]; used as the Referer the image CDN
     * requires (a request without it answers 403).
     */
    fun originOf(raw: String): String? {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    /** Absolute URL for a `src` / `data-src` value. */
    fun resolveUrl(base: String, raw: String): String? {
        val value = raw.trim()
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) {
            val scheme = try {
                URI(base).scheme
            } catch (_: Exception) {
                null
            } ?: return null
            return "$scheme:$value"
        }
        return try {
            URI(base).resolve(value).toString()
        } catch (_: Exception) {
            null
        }
    }

    private val MEIRENTU_ALBUM_LINK = Regex("""/pic/(\d+)(?:-\d+)?\.html""")

    private val IMG_URL_ATTRIBUTE =
        Regex("""(?:src|data-src)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

    private val ALT_ATTRIBUTE = Regex("""alt\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    private val NUMERIC_ENTITY = Regex("""&#(?:x([0-9a-fA-F]+)|(\d+));""")

    /** One listing card is well under 800 chars; the next link is beyond it. */
    private const val ALBUM_CARD_WINDOW = 800

    private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"
    )

    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Not every platform parser knows every feature; the three that
            // matter (doctype / external entities) are attempted and the
            // parser is only used for a listing we control the shape of.
        }
    }

    private fun walk(element: Element?, out: MutableList<WebDavEntry>) {
        if (element == null) return
        if (localName(element) == "response") {
            val href = firstDescendantText(element, "href") ?: return
            out.add(
                WebDavEntry(
                    href = href,
                    lastModifiedMs = firstDescendantText(element, "getlastmodified")
                        ?.let { parseHttpDate(it) } ?: 0L,
                    sizeBytes = firstDescendantText(element, "getcontentlength")
                        ?.toLongOrNull() ?: 0L,
                )
            )
            return
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            walk(children.item(i) as? Element, out)
        }
    }

    private fun firstDescendantText(parent: Element, local: String): String? {
        if (localName(parent) == local) return parent.textContent?.trim()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            firstDescendantText(child, local)?.let { return it }
        }
        return null
    }

    private fun localName(element: Element): String =
        element.localName ?: element.nodeName.substringAfterLast(':')

    private fun parseHttpDate(value: String): Long = try {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    // --- 内置在线壁纸源：NASA APOD / Wikimedia / 彼岸图网 / 必应壁纸 --------

    /** 一个可下载的远程图片（内置在线源的统一形态）。 */
    data class FetchedImage(val remoteKey: String, val url: String, val displayName: String)

    /** NASA APOD（science.nasa.gov/apod/）：取当天主图，去掉缩放参数用原图。 */
    fun parseApodPage(html: String): FetchedImage? {
        val doc = org.jsoup.Jsoup.parse(html)
        val img = doc.selectFirst(".media-detail-hero__media img")
            ?: doc.selectFirst(".media-detail-hero img")
            ?: return null
        val raw = img.attr("src").trim().takeIf { it.isNotEmpty() } ?: return null
        val url = stripUrlParams(absoluteHttps(raw))
        if (!url.contains("/apod/")) return null
        return FetchedImage(remoteKey = url, url = url, displayName = fileNameOf(url, "APOD"))
    }

    /**
     * Wikimedia Commons「每日图片」feed：现为 RSS 2.0（`<item>` 的 description
     * 里是转义后的 `<img ...>`），也兼容 Atom 形态的 `<entry><content><img>`。
     * 缩略图地址升级为原始分辨率。
     */
    fun parseWikimediaFeed(xml: String): List<FetchedImage> {
        val doc = org.jsoup.Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        val out = ArrayList<FetchedImage>()
        for (item in doc.select("item")) {
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            val description = item.selectFirst("description")?.text().orEmpty()
            val src = IMG_SRC.find(description)?.groupValues?.get(1)
                ?: item.selectFirst("img")?.attr("src")
                ?: continue
            val url = upscaleWikimedia(absoluteHttps(src)) ?: continue
            out.add(FetchedImage(link.ifEmpty { url }, url, fileNameOf(url, "POTD")))
        }
        if (out.isNotEmpty()) return out
        for (entry in doc.select("entry")) {
            val id = entry.selectFirst("id")?.text()?.trim().orEmpty()
            val src = entry.selectFirst("content img")?.attr("src")?.trim().orEmpty()
            if (src.isEmpty()) continue
            val url = upscaleWikimedia(absoluteHttps(src)) ?: continue
            out.add(FetchedImage(id.ifEmpty { url }, url, fileNameOf(url, "POTD")))
        }
        return out
    }

    private val IMG_SRC = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** 彼岸图网列表页：条目详情页链接。 */
    fun parseNetbianListing(html: String): List<String> {
        val doc = org.jsoup.Jsoup.parse(html, "https://pic.netbian.com/")
        return doc.select("a[href]").mapNotNull { a ->
            val abs = a.absUrl("href").ifBlank { a.attr("href") }
            abs.takeIf {
                it.startsWith("https://pic.netbian.com/tupian/") && it.endsWith(".html")
            }
        }.distinct()
    }

    /** 彼岸图网详情页：`#img img` 就是原图。 */
    fun parseNetbianDetail(html: String, pageUrl: String): FetchedImage? {
        val doc = org.jsoup.Jsoup.parse(html, pageUrl)
        val img = doc.selectFirst("#img img")
            ?: doc.selectFirst("div.photo-pic img")
            ?: return null
        val src = img.absUrl("src").ifBlank { img.attr("src") }.trim()
        if (src.isEmpty()) return null
        val url = absoluteHttps(src)
        val name = img.attr("alt").trim().ifBlank { fileNameOf(url, "Netbian") }
        return FetchedImage(remoteKey = url, url = url, displayName = name)
    }

    /** 必应壁纸站（bing.ioliu.cn）首页：壁纸详情页链接。 */
    fun parseIoliuListing(html: String): List<String> {
        val doc = org.jsoup.Jsoup.parse(html, "https://bing.ioliu.cn/")
        return doc.select("a[href]").mapNotNull { a ->
            val abs = a.absUrl("href").ifBlank { a.attr("href") }
            abs.takeIf { it.startsWith("https://bing.ioliu.cn/wallpapers/") }
        }.distinct()
    }

    private val IOLIU_FULL = Regex(
        """https://(?:cn|global)\.bing\.com/th\?id=[^"'\s&]*_1920x(?:1080|1200)\.jpg"""
    )
    private val IOLIU_ANY = Regex("""https://(?:cn|global)\.bing\.com/th\?id=[^"'\s&]+""")

    /** 必应壁纸站详情页：优先 1920 宽的原图，其次任意 `th?id=` 原图地址。 */
    fun parseIoliuDetail(html: String): FetchedImage? {
        val clean = html.replace("&amp;", "&")
        val url = IOLIU_FULL.find(clean)?.value
            ?: IOLIU_ANY.findAll(clean).map { it.value }
                .firstOrNull { it.contains("_1920x") || it.contains("_UHD") }
            ?: return null
        return FetchedImage(remoteKey = url, url = url, displayName = fileNameOf(url, "Bing"))
    }

    /** Wikimedia 缩略图地址 → 原始文件地址；不是缩略图/不匹配时原样返回。 */
    fun upscaleWikimedia(url: String): String? {
        if (!url.contains("upload.wikimedia.org")) return url
        val marker = "/thumb/"
        val index = url.indexOf(marker)
        if (index < 0) return url
        val prefix = url.substring(0, index)
        val parts = url.substring(index + marker.length).split('/')
        if (parts.size < 3) return url
        return prefix + "/" + parts.dropLast(1).joinToString("/")
    }

    private fun absoluteHttps(url: String): String {
        val value = url.replace("&amp;", "&").trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            else -> value
        }
    }

    private fun stripUrlParams(url: String): String =
        url.replace("&amp;", "&").substringBefore('?').substringBefore('&').trim()

    private fun fileNameOf(url: String, fallback: String): String {
        val name = url.substringAfterLast('/').substringBefore('?')
        return name.takeIf { it.isNotBlank() && it.length <= 80 } ?: fallback
    }
}
