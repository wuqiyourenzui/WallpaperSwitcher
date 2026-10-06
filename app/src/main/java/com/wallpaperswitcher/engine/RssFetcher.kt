package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and parses a subscription feed.
 *
 * A Legado source may carry a custom `header` string ("Name: Value" per line);
 * those headers are applied to the request. Everything else is the standard
 * feed body (RSS/Atom/JSON) parsed by [FeedParser].
 */
internal object RssFetcher {

    private const val MAX_BODY_BYTES = 6L * 1024 * 1024
    private const val USER_AGENT = RssHttp.USER_AGENT
    private const val TAG = "RssFetcher"

    private val client: OkHttpClient get() = RssHttp.client

    suspend fun fetch(
        source: RssSource,
        categoryIndex: Int = 0,
    ): List<FeedParser.ParsedArticle> =
        fetchPages(source, categoryIndex, startCursor = null).articles

    /**
     * One incremental fetch of a source's article list. Plain feeds have no
     * pagination (the cursor stays null); 阅读 rule sources follow their
     * `ruleNextPage` / category `|rule` (see [LegadoRss.fetchPages]).
     */
    suspend fun fetchPages(
        source: RssSource,
        categoryIndex: Int = 0,
        startCursor: String? = null,
        maxPages: Int = 3,
    ): com.wallpaperswitcher.engine.legado.LegadoRss.PageResult =
        withContext(Dispatchers.IO) {
            // 阅读 (Legado) rule-based source: parse the imported rules first.
            // JS 源（jsLib + getJs()）在这里把规则跑出来，拿不到才算不支持。
            com.wallpaperswitcher.engine.legado.LegadoRss.rulesFor(source)?.let { rules ->
                return@withContext com.wallpaperswitcher.engine.legado.LegadoRss
                    .fetchPages(source, rules, categoryIndex, startCursor, maxPages)
            }
            // 阅读 的 JS / 加密源：尝试过运行时仍拿不到规则（jsLib 下载失败、
            // 脚本报错、或用到白名单之外的类）—— 报明确原因，而不是
            // 「返回内容无法解析」。
            if (com.wallpaperswitcher.engine.legado.LegadoRss.requiresJsRuntime(source)) {
                throw FetchException("unsupported_js")
            }
            when (OnlineSourceRules.endpointPolicy(source.url, allowCleartext = true)) {
                OnlineSourceRules.EndpointPolicy.NEEDS_HTTPS ->
                    throw FetchException("https_required")
                OnlineSourceRules.EndpointPolicy.INVALID ->
                    throw FetchException("bad_url")
                OnlineSourceRules.EndpointPolicy.OK -> Unit
            }
            val builder = try {
                Request.Builder().url(source.url)
            } catch (t: Throwable) {
                throw FetchException("bad_url", cause = t)
            }
            builder.header("User-Agent", USER_AGENT)
            builder.header("Accept", "application/rss+xml, application/atom+xml, application/json, */*")
            for ((name, value) in customHeaders(source)) {
                try {
                    builder.header(name, value)
                } catch (_: Exception) {
                }
            }
            client.newCall(builder.build()).execute().use { response ->
                val status = response.code
                AppLog.d(TAG, "RSS ${response.request.url.host} -> $status")
                if (!response.isSuccessful) {
                    throw FetchException(HttpFailureReason.of(status), status)
                }
                val body = response.peekBody(MAX_BODY_BYTES).string()
                if (body.isBlank()) throw FetchException("empty", status)
                com.wallpaperswitcher.engine.legado.LegadoRss
                    .checkLoginScript(source, null, body, source.url)
                val articles = FeedParser.parse(body)
                if (articles.isEmpty()) throw FetchException("parse", status)
                com.wallpaperswitcher.engine.legado.LegadoRss.PageResult(articles, null)
            }
        }

    /**
     * Plain text download, used to resolve a `legado://…?src=<http url>` share
     * link whose payload is a remote JSON file.
     */
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        when (OnlineSourceRules.endpointPolicy(url, allowCleartext = true)) {
            OnlineSourceRules.EndpointPolicy.NEEDS_HTTPS ->
                throw FetchException("https_required")
            OnlineSourceRules.EndpointPolicy.INVALID ->
                throw FetchException("bad_url")
            OnlineSourceRules.EndpointPolicy.OK -> Unit
        }
        val request = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        } catch (t: Throwable) {
            throw FetchException("bad_url", cause = t)
        }
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw FetchException(
                    HttpFailureReason.of(response.code), response.code
                )
            }
            response.peekBody(4L * 1024 * 1024).string()
        }
    }

    /** 阅读 `header`: a JSON object (or a JS snippet producing one). */
    private fun customHeaders(source: RssSource): List<Pair<String, String>> {
        if (source.rawJson.isBlank()) return emptyList()
        // 对象或数组（`[{…}]`）两种导出形式都要能取到 header。
        val map = com.wallpaperswitcher.engine.legado.LegadoRss
            .sourceFields(source.rawJson) ?: return emptyList()
        val header = map["header"] as? String ?: return emptyList()
        return com.wallpaperswitcher.engine.legado.LegadoRss
            .parseHeaderMap(header, source.id, source.url)
            .toList()
    }

    fun classify(t: Throwable): String = HttpFailureReason.classify(t)
}
