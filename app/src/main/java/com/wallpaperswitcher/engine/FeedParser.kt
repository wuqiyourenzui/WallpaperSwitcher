package com.wallpaperswitcher.engine

import java.io.StringReader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Feed reader for the 阅读订阅源: RSS 2.0 / RSS 1.0 (RDF) / Atom / JSON Feed.
 *
 * Legado rule-based sources (JS selectors in `ruleArticles`) are not executed
 * here - the vast majority of shared Legado subscription sources are plain
 * RSS/Atom/JSON feeds, and those need no rules. The HTML helpers are shared
 * with the UI (description preview, first image).
 */
object FeedParser {

    data class ParsedArticle(
        val guid: String,
        val title: String,
        val link: String,
        val description: String,
        val content: String,
        val imageUrl: String,
        val publishedAt: Long,
        /** 阅读 category (`sortUrl` entry) the article came from; "" for feeds. */
        val sort: String = "",
        /** 阅读 `URL,{…}` 选项里的请求头（JSON 对象），"" = 没有。 */
        val requestHeaders: String = "",
    )

    fun parse(body: String): List<ParsedArticle> {
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> parseJsonFeed(trimmed)
            else -> parseXml(trimmed)
        }
    }

    // --- XML (RSS / Atom / RDF) --------------------------------------------------

    private fun parseXml(xml: String): List<ParsedArticle> {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                hardenForUntrustedXml()
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (_: Throwable) {
            return emptyList()
        }
        val items = ArrayList<Element>()
        collect(doc.documentElement, items)
        return items.mapNotNull { articleFrom(it) }
    }

    private fun collect(element: Element?, out: MutableList<Element>) {
        if (element == null) return
        if (localName(element) == "item" || localName(element) == "entry") {
            out.add(element)
            return
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            collect(children.item(i) as? Element, out)
        }
    }

    private fun articleFrom(item: Element): ParsedArticle? {
        val title = childText(item, "title").orEmpty()
        val link = linkOf(item)
        val content = childText(item, "encoded")
            ?: childText(item, "content")
            ?: childText(item, "description")
            ?: childText(item, "summary")
            ?: ""
        val description = childText(item, "description")
            ?: childText(item, "summary")
            ?: content
        val published = parseDate(
            childText(item, "pubDate")
                ?: childText(item, "published")
                ?: childText(item, "updated")
                ?: childText(item, "date")
        )
        val image = imageOf(item, content, link)
        val guid = childText(item, "guid")
            ?: childText(item, "id")
            ?: link
        if (title.isBlank() && link.isBlank() && content.isBlank()) return null
        return ParsedArticle(
            guid = guid.ifBlank { "$title|$published" },
            title = title,
            link = link,
            description = description,
            content = content,
            imageUrl = image,
            publishedAt = published,
        )
    }

    private fun linkOf(item: Element): String {
        // Atom: <link rel="alternate" href="…"/>; RSS: <link>url</link>.
        val children = item.childNodes
        var fallback: String? = null
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (localName(child) != "link") continue
            val href = child.getAttribute("href").trim()
            if (href.isEmpty()) {
                val text = child.textContent?.trim().orEmpty()
                if (text.isNotEmpty()) return text
                continue
            }
            val rel = child.getAttribute("rel")
            if (rel.isEmpty() || rel == "alternate") return href
            if (fallback == null) fallback = href
        }
        return fallback ?: ""
    }

    private fun imageOf(item: Element, content: String, link: String): String {
        val candidates = listOf("enclosure", "content", "thumbnail")
        for (tag in candidates) {
            val el = firstDescendant(item, tag) ?: continue
            val url = el.getAttribute("url").trim().ifBlank {
                el.getAttribute("href").trim()
            }
            if (url.isNotEmpty() && looksLikeImage(url)) {
                return OnlineSourceRules.resolveUrl(link, url) ?: url
            }
        }
        return firstImageUrl(content)?.let {
            OnlineSourceRules.resolveUrl(link, it) ?: it
        } ?: ""
    }

    // --- JSON Feed ---------------------------------------------------------------

    private fun parseJsonFeed(body: String): List<ParsedArticle> {
        val root = try {
            Json.parse(body)
        } catch (_: Exception) {
            return emptyList()
        } as? Map<*, *> ?: return emptyList()
        val items = root["items"] as? List<*> ?: return emptyList()
        return items.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val title = map["title"] as? String ?: ""
            val link = ((map["url"] ?: map["external_url"]) as? String).orEmpty()
            val content = ((map["content_html"] ?: map["content_text"]) as? String).orEmpty()
            val summary = (map["summary"] as? String).orEmpty()
            val image = (map["image"] as? String).orEmpty()
            val published = parseDate(
                (map["date_published"] as? String) ?: (map["date_modified"] as? String)
            )
            val guid = (map["id"] as? String).orEmpty()
            if (title.isBlank() && link.isBlank() && content.isBlank()) return@mapNotNull null
            ParsedArticle(
                guid = guid.ifBlank { link.ifBlank { "$title|$published" } },
                title = title,
                link = link,
                description = summary.ifBlank { content },
                content = content,
                imageUrl = image.ifBlank { firstImageUrl(content).orEmpty() },
                publishedAt = published,
            )
        }
    }

    // --- HTML / text helpers -----------------------------------------------------

    /** Plain text of an HTML fragment (tags removed, entities decoded). */
    fun stripHtml(html: String): String {
        if (html.isEmpty()) return ""
        val noTags = html
            .replace(Regex("(?s)<script.*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?s)<style.*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
        return OnlineSourceRules.decodeHtmlEntities(noTags)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** First `<img src>` / `<img data-src>` of an HTML fragment. */
    fun firstImageUrl(html: String): String? {
        if (html.isEmpty()) return null
        for (match in IMG_TAG.findAll(html)) {
            imageUrlOfTag(match.value)?.let { return OnlineSourceRules.decodeHtmlEntities(it) }
        }
        return Css_BG.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Every image of an HTML fragment, in document order, duplicates removed. */
    fun allImageUrls(html: String): List<String> {
        if (html.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (match in IMG_TAG.findAll(html)) {
            imageUrlOfTag(match.value)?.let { out.add(OnlineSourceRules.decodeHtmlEntities(it)) }
        }
        // CSS backgrounds (gallery thumbnails are often `<div style="background-image:url(..)">`).
        for (match in Css_BG.findAll(html)) {
            val url = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (url.isNotEmpty() && !url.startsWith("data:", ignoreCase = true)) {
                out.add(OnlineSourceRules.decodeHtmlEntities(url))
            }
        }
        // Images that only exist in the page's data (`var imgList=["…jpg"]`,
        // `{"src":"…"}`, `"image":"…"`): the site builds the gallery from these,
        // so a static <img> scan alone would miss them.
        for (match in EMBEDDED_IMG.findAll(html)) {
            val url = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (url.isNotEmpty()) out.add(OnlineSourceRules.decodeHtmlEntities(url))
        }
        return out.toList()
    }

    /**
     * src/srcset/data-* of one `<img>` tag, both quote styles.
     *
     * 原图优先: a lazy-loading gallery keeps a tiny placeholder in `src` and the
     * real (often much larger) URL in `data-src` / `data-original` /
     * `data-lazy-src` / `data-echo` / `data-url`. The old scanner took whichever
     * attribute came FIRST in the tag, so a placeholder `src` won and the
     * library ended up with blurry thumbnails. The scan now collects every
     * candidate and ranks them: data-* attributes first (in the order above),
     * then the LARGEST srcset entry (see [OriginalImageUrl.largestFromSrcset] -
     * the list doubles as the download source, so a display-sized pick would
     * land in the group), then plain src.
     */
    private fun imageUrlOfTag(tag: String): String? {
        var dataUrl: String? = null
        var dataRank = Int.MAX_VALUE
        var srcsetUrl: String? = null
        var plainSrc: String? = null
        for (match in IMG_ATTR.findAll(tag)) {
            val name = match.groupValues.getOrNull(1).orEmpty().lowercase()
            val raw = (match.groupValues.getOrNull(2).orEmpty().ifBlank {
                match.groupValues.getOrNull(3).orEmpty()
            }).trim()
            if (raw.isEmpty() || raw.startsWith("data:", ignoreCase = true)) continue
            when (name) {
                "src" -> if (plainSrc == null) {
                    plainSrc = raw.substringBefore(',').substringBefore(' ').trim()
                }
                // 原图优先: 取 srcset 里**最大**的一项，而不是第一项 ——
                // 列表/选择器上的 URL 同时也是"加入分组"要下载的那个地址，
                // 站点把 srcset 从小到大排列，取第一项就等于下缩略图。
                "srcset", "data-srcset" -> if (srcsetUrl == null) {
                    srcsetUrl = OriginalImageUrl.largestFromSrcset(raw)
                }
                else -> {
                    val rank = DATA_ATTR_RANK[name] ?: DATA_ATTR_RANK.size
                    if (rank < dataRank) {
                        dataRank = rank
                        dataUrl = raw.substringBefore(' ').trim()
                    }
                }
            }
        }
        return (dataUrl ?: srcsetUrl ?: plainSrc)?.takeIf { it.isNotEmpty() }
    }

    private fun looksLikeImage(url: String): Boolean =
        OnlineSourceRules.imageExtension(url, null) != null

    private fun parseDate(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        for (formatter in DATE_FORMATS) {
            try {
                return ZonedDateTime.parse(text, formatter).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
            try {
                return OffsetDateTime.parse(text, formatter).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
            try {
                return LocalDateTime.parse(text, formatter)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun childText(parent: Element, local: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (localName(child) == local) return child.textContent?.trim()
        }
        return null
    }

    private fun firstDescendant(parent: Element, local: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (localName(child) == local) return child
            firstDescendant(child, local)?.let { return it }
        }
        return null
    }

    private fun localName(element: Element): String =
        element.localName ?: element.nodeName.substringAfterLast(':')

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_ZONED_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    )

    private val IMG_TAG = Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE)

    private val IMG_ATTR = Regex(
        """(data-original|data-src|data-lazy-src|data-echo|data-url|data-actualsrc|data-srcset|srcset|src)\s*=\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE
    )

    /** 懒加载真实地址的优先级（数字越小越优先）。 */
    private val DATA_ATTR_RANK = mapOf(
        "data-original" to 0,
        "data-src" to 1,
        "data-lazy-src" to 2,
        "data-echo" to 3,
        "data-url" to 4,
        "data-actualsrc" to 5,
    )

    private val Css_BG = Regex(
        """background-image\s*:\s*url\(\s*['"]?([^'")]+)""",
        RegexOption.IGNORE_CASE
    )

    /** Absolute image URLs quoted inside page scripts / JSON data. */
    private val EMBEDDED_IMG = Regex(
        """["']((?:https?://|/)[^"'\\\s]+?\.(?:jpe?g|png|webp|gif|bmp|avif)(?:\?[^"'\s]*)?)["']""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Video sources (阅读 `type = 2`) hide the playable URL behind an iframe or a
     * player config. This collects direct media URLs plus the iframe/player page
     * that probably contains them (the caller fetches those one level deeper).
     */
    fun allVideoUrls(html: String): List<String> {
        if (html.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (match in VIDEO_URL.findAll(html)) {
            val url = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (url.isNotEmpty()) out.add(OnlineSourceRules.decodeHtmlEntities(url))
        }
        for (match in IFRAME_SRC.findAll(html)) {
            val url = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (url.isNotEmpty()) out.add(OnlineSourceRules.decodeHtmlEntities(url))
        }
        return out.toList()
    }

    /** Direct media links, quoted or bare (also inside player JS). */
    private val VIDEO_URL = Regex(
        """["']?((?:https?://|/)[^"'\\\s<>]+?\.(?:mp4|m3u8|webm|mov|m4v|mkv|ts)(?:\?[^"'\s<>]*)?)["']?""",
        RegexOption.IGNORE_CASE
    )

    private val IFRAME_SRC = Regex(
        """<iframe[^>]+src\s*=\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE
    )
}
