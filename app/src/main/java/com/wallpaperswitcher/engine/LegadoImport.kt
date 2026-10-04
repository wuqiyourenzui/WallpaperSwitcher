package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.RssSource
import java.net.URLDecoder
import java.util.Base64

/**
 * Import 阅读 (Legado) subscription sources.
 *
 * Accepted inputs (whatever the user pastes / picks from a file):
 *  - a Legado source JSON array, e.g.
 *    `[{"name":"Example","sourceUrl":"https://…/feed","type":0,"enabled":true}]`;
 *  - a single object, or a `{"sources":[…]}` wrapper;
 *  - a `legado://import/rssSource?src=…` share link whose `src` is the
 *    URL-encoded JSON (a base64 payload is also accepted).
 *
 * The original object is kept in [RssSource.rawJson] so the rule fields
 * (`ruleArticles`, `ruleImage`, `header`, …) survive even though this version
 * only reads standard RSS / Atom / JSON feeds.
 */
object LegadoImport {

    data class Result(val sources: List<RssSource>, val skipped: Int)

    fun parse(text: String): Result {
        val json = extractJson(text.trim()) ?: return Result(emptyList(), 0)
        val root = try {
            Json.parse(json)
        } catch (_: Exception) {
            return Result(emptyList(), 0)
        }
        val items: List<*> = when (root) {
            is List<*> -> root
            is Map<*, *> -> {
                val nested = root["sources"] ?: root["rssSources"]
                if (nested is List<*>) nested else listOf(root)
            }
            else -> return Result(emptyList(), 0)
        }
        val out = ArrayList<RssSource>()
        var skipped = 0
        for (raw in items) {
            val map = raw as? Map<*, *>
            if (map == null) {
                skipped++
                continue
            }
            // 阅读's RSS source JSON calls the display name `sourceName`
            // (`name` is our own export); reading only `name` made every
            // imported source show its URL instead of its proper title.
            val name = ((map["name"] ?: map["sourceName"]) as? String)?.trim().orEmpty()
            val url = ((map["sourceUrl"] ?: map["sortUrl"] ?: map["url"]) as? String)
                ?.trim().orEmpty()
            if (url.isEmpty()) {
                skipped++
                continue
            }
            val type = (map["type"] as? Long)?.toInt() ?: 0
            val enabled = (map["enabled"] as? Boolean) ?: true
            out.add(
                RssSource(
                    name = name.ifBlank { url.substringAfterLast('/').ifBlank { url } },
                    url = url,
                    type = type,
                    enabled = enabled,
                    rawJson = Json.encode(map),
                )
            )
        }
        return Result(out, skipped)
    }

    /**
     * The `src` of a `legado://` share link when it is an http(s) URL whose
     * body still has to be downloaded; null for inline JSON / base64. The
     * caller fetches the URL and calls [parse] with the body.
     */
    fun extractRemoteSourceUrl(text: String): String? {
        val src = shareLinkSource(text) ?: return null
        val decoded = decodeShareSource(src)
        return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /**
     * The address whose body still has to be downloaded before parsing: either
     * the `src` of a `legado://` share link, or a bare `http(s)` subscription
     * address pasted by the user (阅读's 「导入网络文件」 accepts both; without
     * the second form the import rejected a plain source URL outright).
     */
    fun remoteUrlToFetch(text: String): String? {
        val trimmed = text.trim()
        extractRemoteSourceUrl(trimmed)?.let { return it }
        return trimmed.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun extractJson(text: String): String? {
        if (text.startsWith("{") || text.startsWith("[")) return text
        val src = shareLinkSource(text) ?: return null
        val decoded = decodeShareSource(src)
        if (decoded.trim().startsWith("{") || decoded.trim().startsWith("[")) {
            return decoded.trim()
        }
        val base64 = try {
            String(Base64.getDecoder().decode(decoded.trim()), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
        if (base64 != null && (base64.trim().startsWith("{") || base64.trim().startsWith("["))) {
            return base64.trim()
        }
        return null
    }

    private fun shareLinkSource(text: String): String? {
        val marker = text.indexOf("legado://")
        if (marker < 0) return null
        val link = text.substring(marker)
        val src = link.substringAfter("src=", "").substringBefore('&')
        return src.ifBlank { null }
    }

    private fun decodeShareSource(src: String): String =
        try {
            URLDecoder.decode(src, "UTF-8")
        } catch (_: Exception) {
            src
        }
}
