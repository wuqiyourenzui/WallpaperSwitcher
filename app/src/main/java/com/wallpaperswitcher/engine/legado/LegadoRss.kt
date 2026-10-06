package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.FetchException
import com.wallpaperswitcher.engine.FeedParser
import com.wallpaperswitcher.engine.HttpFailureReason
import com.wallpaperswitcher.engine.Json
import com.wallpaperswitcher.engine.OnlineSourceRules
import com.wallpaperswitcher.util.AppLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 阅读 (Legado) 订阅源规则抓取（P1）: parses a `sortUrl`/`sourceUrl` page with
 * `ruleArticles`, then every item with `ruleTitle` / `ruleLink` / `ruleImage` /
 * `ruleDescription` / `ruleContent` / `rulePubDate`.
 *
 * Rules are evaluated by [LegadoRuleEngine] (CSS / JSONPath / XPath). JS rules
 * (`@js:` / `<js>` / `{{…}}` with JS) are reported as unsupported until the
 * Rhino stage (P2) lands.
 */
internal object LegadoRss {

    private const val TAG = "LegadoRss"
    private const val MAX_BODY_BYTES = 6L * 1024 * 1024
    /** Pages fetched per refresh, so one sync stays cheap. */
    private const val MAX_PAGES_PER_REFRESH = 3
    private const val USER_AGENT = com.wallpaperswitcher.engine.RssHttp.USER_AGENT

    /** Resolved per request so a proxy change takes effect immediately. */
    private val client: OkHttpClient get() = com.wallpaperswitcher.engine.RssHttp.client

    data class Rules(
        val sourceUrl: String,
        val sortUrl: String?,
        val ruleArticles: String,
        val ruleNextPage: String?,
        val ruleTitle: String?,
        val ruleLink: String?,
        val ruleImage: String?,
        val ruleDescription: String?,
        val ruleContent: String?,
        val rulePubDate: String?,
        val header: String?,
        val loginUrl: String?,
        val loginUi: String?,
        val loginCheckJs: String?,
        val jsLib: String?,
        /** 阅读 `concurrentRate`: 源级限流（`1000` 或 `3/1000`）。 */
        val concurrentRate: String?,
        /** 阅读的阅读器字段（正文页排版 / 拦截）。 */
        val style: String?,
        val injectJs: String?,
        val contentWhitelist: String?,
        val contentBlacklist: String?,
        val shouldOverrideUrlLoading: String?,
        val loadWithBaseUrl: Boolean,
        val enabledCookieJar: Boolean,
        val variables: Map<String, String>,
        val singleUrl: Boolean,
        val type: Int,
    )

    /**
     * One entry of a 阅读 `sortUrl` (the category list), e.g.
     * `最新::/new.html|class.page@tag.a.-2@href`:
     * name, relative/absolute path, optional `,{…}` request options and the
     * optional per-category next-page rule after `|`.
     */
    data class Category(
        val name: String,
        val path: String,
        val requestJson: String?,
        val nextPageRule: String?,
    )

    /**
     * The raw imported Legado JSON object, or null for a manual / plain feed.
     * Parsed independently of `ruleArticles`: field-level features such as
     * `loginUrl` also apply to plain RSS sources (type 0) that carry no rules.
     */
    /**
     * True for 阅读's 「JS 源 / 加密源」: the source declares a remote JS library
     * (`jsLib`) and carries `getJs()` in its header instead of static rules, so
     * the rule set is produced/decrypted at runtime. This app has no such
     * runtime - the source must be reported as unsupported instead of failing
     * with a generic "cannot parse" (用户导入 yckceo 的 XH发布页 就是这个形态).
     */
    fun requiresJsRuntime(source: RssSource): Boolean {
        val fields = rawFields(source) ?: return false
        // parseRules() returns null exactly when there is no usable ruleArticles.
        if (parseRules(source) != null) return false
        return LegadoJsSource.isJsSource(fields)
    }

    /**
     * The source's exported fields: the object itself, or the first entry when the
     * raw text is the array form (`[ {…} ]` - the shape yckceo/阅读 share links
     * often use and the one a hand-copied file may keep).
     */
    private fun rawFields(source: RssSource): Map<*, *>? {
        return sourceFields(source.rawJson)
    }

    /**
     * 源 JSON 的字段对象：对象形式取本身，数组形式（`[{…}]`，阅读导出/分享和
     * 仓库里手放的 rssSource_*.json 都是这种）取第一条。
     */
    fun sourceFields(rawJson: String): Map<*, *>? {
        if (rawJson.isBlank()) return null
        val root = try {
            Json.parse(rawJson)
        } catch (_: Exception) {
            return null
        }
        return when (root) {
            is Map<*, *> -> root
            is List<*> -> root.firstOrNull() as? Map<*, *>
            else -> null
        }
    }

    /**
     * 阅读 的「单 URL / 网页型源」：`singleUrl: true`、没有静态规则、也不是 JS 源
     * （例如 PixivSource 项目里的「Pixiv 书源」卡片、「兽展日历」）。阅读直接把它
     * 当网页打开；本应用同样交给全屏浏览器，不再当订阅源抓取列表。
     *
     * 另外把**跑不出规则的 JS 源**也算进来（[requiresJsRuntime]，用户导入的
     * 「XH发布页」就是这种）：这种源的规则要运行时从远程 jsLib 解出来，我们做不到 ——
     * 以前点它会进文章列表然后报「不支持的 JS 源」，现在直接当网页打开原文，
     * 和用户在阅读里看到的行为接近。
     */
    fun isBrowseOnly(source: RssSource): Boolean {
        if (requiresJsRuntime(source)) return true
        val map = rawFields(source) ?: return false
        if ((map["singleUrl"] as? Boolean) != true) return false
        // 规则生成型 JS 源走自己的运行时，不按网页型处理。
        if (LegadoJsSource.isJsSource(map)) return false
        return parseRules(source) == null
    }

    /**
     * 源字段一律走 [rawFields]：用户手上和导出工具给的 JSON 既可能是对象，
     * 也可能是数组（`[{…}]`，仓库里的 rssSource_h视频.json 就是这种）。
     * 以前这里只收对象，数组形式的源会被当成普通 RSS，最后报「返回内容无法解析」。
     */
    private fun rawMap(source: RssSource): Map<*, *>? = rawFields(source)

    /** Parsed rules from the imported Legado JSON; null when it is a plain feed. */
    fun parseRules(source: RssSource): Rules? {
        val map = rawMap(source) ?: return null
        val ruleArticles = (map["ruleArticles"] as? String).orEmpty().trim()
        if (ruleArticles.isEmpty()) return null
        return rulesFrom(source, map)
    }

    /**
     * 规则入口：静态规则直接用；阅读的「JS 源」则先把 `jsLib` 拉下来、跑出规则
     * （`LegadoJsSource`），成功后再照常抓取。脚本与下载都比较重，必须在 IO 里调用。
     */
    suspend fun rulesFor(source: RssSource): Rules? {
        parseRules(source)?.let { return it }
        val map = rawMap(source) ?: return null
        if (!LegadoJsSource.isJsSource(map)) return null
        return withContext(Dispatchers.IO) { LegadoJsSource.resolveRules(source, map) }
    }

    /**
     * 从字段表构造规则：既用于导入的静态 JSON，也用于 JS 源运行时生成
     * （`jsLib` 的 `getJs()` 把规则写进 `source` 之后再由这里读回）。
     */
    fun rulesFrom(source: RssSource, map: Map<*, *>): Rules? {
        val ruleArticles = (map["ruleArticles"] as? String).orEmpty().trim()
        if (ruleArticles.isEmpty()) return null
        val variables = LinkedHashMap<String, String>()
        (map["variable"] as? String)?.let { raw ->
            try {
                (Json.parse(raw) as? Map<*, *>)?.forEach { (k, v) ->
                    if (k is String && v != null) variables[k] = v.toString()
                }
            } catch (_: Exception) {
            }
        }
        return Rules(
            sourceUrl = (map["sourceUrl"] as? String).orEmpty().ifBlank { source.url },
            sortUrl = (map["sortUrl"] as? String)?.takeIf { it.isNotBlank() },
            ruleArticles = ruleArticles,
            ruleNextPage = (map["ruleNextPage"] as? String)?.takeIf { it.isNotBlank() },
            ruleTitle = (map["ruleTitle"] as? String)?.takeIf { it.isNotBlank() },
            ruleLink = (map["ruleLink"] as? String)?.takeIf { it.isNotBlank() },
            ruleImage = (map["ruleImage"] as? String)?.takeIf { it.isNotBlank() },
            ruleDescription = (map["ruleDescription"] as? String)?.takeIf { it.isNotBlank() },
            ruleContent = (map["ruleContent"] as? String)?.takeIf { it.isNotBlank() },
            rulePubDate = (map["rulePubDate"] as? String)?.takeIf { it.isNotBlank() },
            header = (map["header"] as? String)?.takeIf { it.isNotBlank() },
            loginUrl = (map["loginUrl"] as? String)?.takeIf { it.isNotBlank() },
            loginUi = (map["loginUi"] as? String)?.takeIf { it.isNotBlank() },
            loginCheckJs = (map["loginCheckJs"] as? String)?.takeIf { it.isNotBlank() },
            jsLib = (map["jsLib"] as? String)?.takeIf { it.isNotBlank() },
            concurrentRate = (map["concurrentRate"] as? String)?.takeIf { it.isNotBlank() },
            style = (map["style"] as? String)?.takeIf { it.isNotBlank() },
            injectJs = (map["injectJs"] as? String)?.takeIf { it.isNotBlank() },
            contentWhitelist = (map["contentWhitelist"] as? String)?.takeIf { it.isNotBlank() },
            contentBlacklist = (map["contentBlacklist"] as? String)?.takeIf { it.isNotBlank() },
            shouldOverrideUrlLoading =
                (map["shouldOverrideUrlLoading"] as? String)?.takeIf { it.isNotBlank() },
            loadWithBaseUrl = (map["loadWithBaseUrl"] as? Boolean) ?: true,
            enabledCookieJar = (map["enabledCookieJar"] as? Boolean) ?: true,
            variables = variables,
            singleUrl = (map["singleUrl"] as? Boolean) ?: false,
            type = (map["type"] as? Long)?.toInt() ?: 0,
        ).also { ConcurrentRate.register(source.id, it.concurrentRate) }
    }

    /**
     * 阅读的「阅读器」字段（正文页排版 / 资源拦截 / 跳转拦截），
     * 3.x 在 ReadRssActivity、新版映射进 contentRule —— 都由文章页 WebView 消费。
     */
    data class WebOptions(
        val style: String?,
        val injectJs: String?,
        val contentWhitelist: String?,
        val contentBlacklist: String?,
        val shouldOverrideUrlLoading: String?,
        val loadWithBaseUrl: Boolean,
    )

    fun webOptions(source: RssSource?): WebOptions {
        val rules = source?.let { parseRules(it) }
        return WebOptions(
            style = rules?.style,
            injectJs = rules?.injectJs,
            contentWhitelist = rules?.contentWhitelist,
            contentBlacklist = rules?.contentBlacklist,
            shouldOverrideUrlLoading = rules?.shouldOverrideUrlLoading,
            loadWithBaseUrl = rules?.loadWithBaseUrl ?: true,
        )
    }

    /** 文章链接选项里存下的请求头（JSON 对象）；解析失败按没有处理。 */
    fun requestHeaders(article: com.wallpaperswitcher.data.RssArticle): Map<String, String> {
        if (article.requestHeaders.isBlank()) return emptyMap()
        return try {
            (Json.parse(article.requestHeaders) as? Map<*, *>)
                ?.mapNotNull { (key, value) ->
                    val name = key as? String ?: return@mapNotNull null
                    if (value == null) null else name to value.toString()
                }
                ?.toMap()
                .orEmpty()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun fetch(
        source: RssSource,
        rules: Rules,
        categoryIndex: Int = 0,
    ): List<FeedParser.ParsedArticle> =
        fetchPages(source, rules, categoryIndex, startCursor = null).articles

    /**
     * One incremental fetch of the article list. 阅读 loads a source's pages
     * lazily; we mirror that with a cursor so "load more" continues exactly
     * where the previous call stopped.
     *
     * The cursor is either `page:<n>` (index-based sources: `{{page}}` /
     * `<a,b,c>` lists / the `PAGE` next-page keyword) or an absolute page URL
     * (sources whose `ruleNextPage` extracts the next link from the page).
     * A null [PageResult.nextCursor] means the source has no more pages.
     */
    suspend fun fetchPages(
        source: RssSource,
        rules: Rules,
        categoryIndex: Int = 0,
        startCursor: String? = null,
        maxPages: Int = MAX_PAGES_PER_REFRESH,
    ): PageResult =
        withContext(Dispatchers.IO) {
            // 阅读's sortUrl is a category list; the first entry is the
            // default ("最新"/最新推荐 in these sources).
            ensureLogin(source, rules)
            val categories = sortUrls(source, rules)
            val category = categories.getOrNull(categoryIndex) ?: categories.firstOrNull()
            val sortName = category?.name.orEmpty()
            val collected = LinkedHashMap<String, FeedParser.ParsedArticle>()
            val visited = HashSet<String>()
            val cursor = startCursor?.takeIf { it.isNotBlank() }
            var page = cursorPage(cursor) ?: 1
            var currentUrl = cursorUrl(cursor) ?: pageUrl(rules, category, page, source.id)
            // Sites reorganise their categories ("Video Cosplay" now 404s on
            // cosplaytele) and a dead category URL used to leave the whole
            // source empty. Retry the source's own page once, the way 阅读
            // shows the home feed, so the list still has something to show.
            val homeUrl = source.url.trim().takeIf { it.isNotBlank() }
            var usedHomeFallback = false
            // Index-based sources already know the next URL, so page 2 is
            // fetched in parallel with page 1 (saves one round trip); the
            // result is only used when the loop really reaches that page.
            val pageBased = cursor == null || cursorPage(cursor) != null
            val parallelRule = category?.nextPageRule?.takeIf { it.isNotBlank() }
                ?: rules.ruleNextPage
            val prefetchUrl = if (
                pageBased && maxPages >= 2 &&
                parallelRule?.equals("PAGE", ignoreCase = true) == true
            ) {
                pageUrl(rules, category, page + 1, source.id)
            } else {
                null
            }
            val prefetched = prefetchUrl?.let { url ->
                async(Dispatchers.IO) {
                    runCatching {
                        httpGet(
                            url,
                            rules.header,
                            category?.requestJson,
                            sourceId = source.id,
                            cookies = rules.enabledCookieJar,
                        )
                    }.getOrNull()
                }
            }
            var nextCursor: String? = null
            var fetched = 0
            while (fetched < maxPages && currentUrl.isNotBlank()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!visited.add(currentUrl)) {
                    nextCursor = null
                    break
                }
                fetched++
                when (OnlineSourceRules.endpointPolicy(currentUrl, allowCleartext = true)) {
                    OnlineSourceRules.EndpointPolicy.NEEDS_HTTPS ->
                        throw FetchException("https_required")
                    OnlineSourceRules.EndpointPolicy.INVALID ->
                        throw FetchException("bad_url")
                    OnlineSourceRules.EndpointPolicy.OK -> Unit
                }
                val body = try {
                    if (prefetchUrl != null && currentUrl == prefetchUrl) {
                        prefetched?.await() ?: httpGet(
                            currentUrl,
                            rules.header,
                            category?.requestJson,
                            sourceId = source.id,
                            cookies = rules.enabledCookieJar,
                        )
                    } else {
                        httpGet(
                            currentUrl,
                            rules.header,
                            category?.requestJson,
                            sourceId = source.id,
                            cookies = rules.enabledCookieJar,
                        )
                    }
                } catch (t: Throwable) {
                    if (!usedHomeFallback && cursor == null && fetched == 1 &&
                        homeUrl != null && !samePageUrl(currentUrl, homeUrl)
                    ) {
                        usedHomeFallback = true
                        AppLog.w(
                            TAG,
                            "category '${sortName}' failed " +
                                "(${t.message?.take(60).orEmpty()}); using $homeUrl",
                        )
                        currentUrl = homeUrl
                        continue
                    }
                    throw t
                }
                checkLoginScript(source, rules, body, currentUrl)
                val engine = LegadoRuleEngine(
                    baseUrl = currentUrl,
                    variables = rules.variables + mapOf("page" to page.toString()),
                    sourceId = source.id,
                    jsLib = rules.jsLib,
                )
                var reverse = false
                var articlesRule = rules.ruleArticles
                if (articlesRule.startsWith("-")) {
                    reverse = true
                    articlesRule = articlesRule.substring(1)
                }
                val items = firstElements(engine, articlesRule, body)
                AppLog.d(
                    TAG,
                    "page=$page url=$currentUrl bytes=${body.length} items=${items.size}",
                )
                for (error in engine.takeErrors()) {
                    AppLog.w(TAG, "rule issue: ${error.reason} (${error.rule.take(80)})")
                }
                if (items.isEmpty()) {
                    if (!usedHomeFallback && cursor == null && fetched == 1 &&
                        homeUrl != null && !samePageUrl(currentUrl, homeUrl)
                    ) {
                        usedHomeFallback = true
                        AppLog.w(
                            TAG,
                            "category '${sortName}' parsed empty; using $homeUrl",
                        )
                        currentUrl = homeUrl
                        continue
                    }
                    nextCursor = null
                    break
                }
                val pageArticles = ArrayList<FeedParser.ParsedArticle>(items.size)
                for (item in items) {
                    val itemEngine = LegadoRuleEngine(
                        baseUrl = currentUrl,
                        variables = engineVariables(rules),
                        sourceId = source.id,
                        jsLib = rules.jsLib,
                    )
                    articleFrom(itemEngine, item, rules, currentUrl, sortName)
                        ?.let(pageArticles::add)
                }
                if (reverse) pageArticles.reverse()
                for (article in pageArticles) collected[article.guid] = article

                // Next page: the category's `|rule`, else ruleNextPage; "PAGE"
                // means "increment {{page}} / the <a,b,c> list".
                val nextRule = category?.nextPageRule?.takeIf { it.isNotBlank() }
                    ?: rules.ruleNextPage
                nextCursor = when {
                    nextRule.isNullOrBlank() -> null
                    nextRule.equals("PAGE", ignoreCase = true) -> "page:${page + 1}"
                    else -> firstValue(engine, nextRule, body, isUrl = true)
                        .takeIf { it.isNotBlank() }
                }
                if (nextCursor == null) break
                val nextUrl = cursorUrl(nextCursor)
                    ?: pageUrl(rules, category, page + 1, source.id)
                if (nextUrl.isBlank() || nextUrl == currentUrl) {
                    nextCursor = null
                    break
                }
                currentUrl = nextUrl
                page++
            }
            if (collected.isEmpty() && fetched == 0) {
                prefetched?.cancel()
                throw FetchException("parse")
            }
            prefetched?.cancel()
            PageResult(collected.values.toList(), nextCursor)
        }

    /** Result of one [fetchPages] call. */
    data class PageResult(
        val articles: List<FeedParser.ParsedArticle>,
        /** Cursor for the next "load more"; null = no more pages. */
        val nextCursor: String?,
    )

    /** Same page ignoring a trailing slash / a bare "#". */
    private fun samePageUrl(a: String, b: String): Boolean =
        a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

    /** `page:<n>` cursors carry the 1-based page index. */
    private fun cursorPage(cursor: String?): Int? {
        val text = cursor?.takeIf { it.startsWith("page:", ignoreCase = true) } ?: return null
        return text.substringAfter(':').toIntOrNull()?.coerceAtLeast(1)
    }

    /** URL cursors carry an absolute page URL. */
    private fun cursorUrl(cursor: String?): String? {
        val text = cursor?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("page:", ignoreCase = true)) return null
        return text.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun articleFrom(
        engine: LegadoRuleEngine,
        item: Any,
        rules: Rules,
        baseUrl: String,
        sortName: String = "",
    ): FeedParser.ParsedArticle? {
        val title = firstValue(engine, rules.ruleTitle, item)
        // Blank means "no value": resolving a blank URL against the base would
        // otherwise turn an empty image rule into a bogus image of the page.
        val linkRaw = firstValue(engine, rules.ruleLink, item)
        val linkOptions = UrlOptions.split(
            if (linkRaw.isBlank()) "" else OnlineSourceRules.resolveUrl(baseUrl, linkRaw) ?: linkRaw
        )
        val link = linkOptions.first
        val imageRaw = firstValue(engine, rules.ruleImage, item)
        val imageOptions = UrlOptions.split(
            if (imageRaw.isBlank()) "" else OnlineSourceRules.resolveUrl(baseUrl, imageRaw) ?: imageRaw
        )
        var image = imageOptions.first
        val description = firstValue(engine, rules.ruleDescription, item)
        val content = firstValue(engine, rules.ruleContent, item)
        if (image.isBlank()) {
            // Cover fallback: several sources have no usable ruleImage, but the
            // item's own HTML already carries a picture.
            val fromHtml = FeedParser.firstImageUrl(content.ifBlank { description })
            if (!fromHtml.isNullOrBlank()) {
                image = OnlineSourceRules.resolveUrl(baseUrl, fromHtml) ?: fromHtml
            }
        }
        val date = firstValue(engine, rules.rulePubDate, item)
        if (title.isBlank() && link.isBlank()) return null
        // 文章页 / 封面需要自定义请求头时存下来：阅读是把选项带在链接上，
        // 我们的链接要落库、要在 WebView 里打开，所以拆出来单独存。
        val requestHeaders = LinkedHashMap<String, String>()
        requestHeaders.putAll(linkOptions.second.headers)
        requestHeaders.putAll(imageOptions.second.headers)
        return FeedParser.ParsedArticle(
            guid = link.ifBlank { "$title|$date" },
            title = title,
            link = link,
            description = description.ifBlank { content },
            content = content,
            imageUrl = image,
            publishedAt = parseDate(date),
            sort = sortName,
            requestHeaders = if (requestHeaders.isEmpty()) "" else Json.encode(requestHeaders),
        )
    }

    /** 阅读 tries every comma-separated alternative until one yields a value. */
    private fun firstValue(
        engine: LegadoRuleEngine,
        rule: String?,
        content: Any?,
        isUrl: Boolean = false,
    ): String {
        if (rule.isNullOrBlank()) return ""
        for (alternative in RuleAlternatives.split(rule)) {
            // 单条规则出问题（正则、JS 异常…）不该让整次刷新失败：当作「没取到」，
            // 其余备选继续试。阅读 同样是逐条降级。
            val value = try {
                engine.getString(alternative, content, isUrl)
            } catch (_: Throwable) {
                ""
            }
            if (value.isNotBlank()) return value
        }
        return ""
    }

    /** Same as [firstValue] but for element-list rules (`ruleArticles`). */
    private fun firstElements(
        engine: LegadoRuleEngine,
        rule: String?,
        content: Any?,
    ): List<Any> {
        if (rule.isNullOrBlank()) return emptyList()
        for (alternative in RuleAlternatives.split(rule)) {
            val elements = try {
                engine.getElements(alternative, content)
            } catch (_: Throwable) {
                emptyList()
            }
            if (elements.isNotEmpty()) return elements
        }
        return emptyList()
    }

    /** [firstValue] with 阅读's "join every match" semantics (content rules). */
    private fun firstValueJoined(
        engine: LegadoRuleEngine,
        rule: String?,
        content: Any?,
    ): String {
        if (rule.isNullOrBlank()) return ""
        for (alternative in RuleAlternatives.split(rule)) {
            val value = try {
                engine.getString(alternative, content, joinAll = true)
            } catch (_: Throwable) {
                ""
            }
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun engineVariables(rules: Rules): Map<String, String> =
        rules.variables + mapOf("page" to "1")

    /** Parse the `名称::路径[|下一页规则]` lines of `sortUrl`. */
    fun parseCategories(sortUrl: String?): List<Category> {
        if (sortUrl.isNullOrBlank()) return emptyList()
        return sortUrl.split(CATEGORY_SEPARATOR).mapNotNull { line ->
            val text = line.trim()
            if (text.isEmpty()) return@mapNotNull null
            val separator = text.indexOf("::")
            if (separator <= 0) return@mapNotNull null
            val name = text.substring(0, separator).trim()
            var rest = text.substring(separator + 2).trim()
            var next: String? = null
            if (!rest.contains(",{")) {
                val pipe = rest.lastIndexOf('|')
                if (pipe > 0) {
                    next = rest.substring(pipe + 1).trim()
                    rest = rest.substring(0, pipe).trim()
                }
            }
            val comma = rest.indexOf(",{")
            val request = if (comma >= 0) rest.substring(comma + 1) else null
            val path = if (comma >= 0) rest.substring(0, comma).trim() else rest
            Category(name = name, path = path, requestJson = request, nextPageRule = next)
        }
    }

    /**
     * 阅读's `sortUrls()`: the category list is either literal (`&&` / newline
     * separated) or produced by a `<js>` / `@js:` snippet whose result is
     * cached for the session. Falls back to the source URL when empty.
     */
    fun sortUrls(source: RssSource, rules: Rules): List<Category> {
        val raw = rules.sortUrl?.takeIf { it.isNotBlank() }
            ?: return listOf(Category("", rules.sourceUrl, null, null))
        val resolved = if (isJsRule(raw)) resolveSortUrlScript(source, rules, raw) else raw
        return parseCategories(resolved)
            .ifEmpty { listOf(Category("", rules.sourceUrl, null, null)) }
    }

    private fun isJsRule(rule: String): Boolean {
        val text = rule.trimStart()
        return text.startsWith("<js>", ignoreCase = true) ||
            text.startsWith("@js:", ignoreCase = true)
    }

    private fun resolveSortUrlScript(source: RssSource, rules: Rules, raw: String): String {
        // A reserved key so the cache can never collide with `source.put/get`
        // variables of the same name.
        val cacheKey = "__sortUrl"
        val cached = RssSourceVariables.get(source.id, cacheKey)
        if (cached.isNotBlank()) return cached
        val script = loginScript(raw) ?: return raw
        val value = LegadoJs.eval(
            script = script,
            bindings = rules.variables,
            baseUrl = rules.sourceUrl,
            sourceId = source.id,
            jsLib = rules.jsLib,
        )
        if (!value.isNullOrBlank()) RssSourceVariables.put(source.id, cacheKey, value)
        return value ?: raw
    }

    /** Applies the `<a,b,c>` page list and `{{page}}` / `{{JS}}` substitution. */
    internal fun pageUrl(
        rules: Rules,
        category: Category?,
        page: Int,
        sourceId: Long,
    ): String {
        val rawPath = category?.path?.takeIf { it.isNotBlank() } ?: rules.sourceUrl
        // 阅读 allows a category path to be a JS snippet (`@js:` / `<js>`),
        // evaluated with `page` bound to the 1-based page number. Sources such
        // as 推次元 build "list" vs "list/index_2.html" that way. Without this
        // the literal "@js:…" text was handed to the HTTP layer, whose endpoint
        // policy rejected it as an invalid address, so the whole source failed
        // with `err:bad_url` ("无法访问").
        val evaluated = if (isJsRule(rawPath)) {
            LegadoJs.eval(
                script = loginScript(rawPath) ?: rawPath,
                bindings = rules.variables + mapOf("page" to page.toString()),
                baseUrl = rules.sourceUrl,
                sourceId = sourceId,
                jsLib = rules.jsLib,
            )?.takeIf { it.isNotBlank() } ?: rawPath
        } else {
            rawPath
        }
        val withPageList = replacePageList(evaluated, page)
        val substituted = substitute(withPageList, rules.variables, page, sourceId)
        if (substituted.startsWith("http://") || substituted.startsWith("https://")) {
            return substituted
        }
        val base = rules.sourceUrl.trim()
        // A category path is resolved against the source URL like 阅读 does:
        // "/cat" is absolute, "cat" is relative to the source URL's directory.
        return resolveAgainstSource(base, substituted)
    }

    /** 阅读 URL resolution: absolute `http(s)://` as-is, else URI-resolve. */
    fun resolveAgainstSource(sourceUrl: String, path: String): String {
        val base = sourceUrl.trim()
        val target = path.trim()
        if (target.startsWith("http://") || target.startsWith("https://")) return target
        OnlineSourceRules.resolveUrl(base, target)?.let { return it }
        if (base.isEmpty()) return target
        val normalizedBase = if (base.endsWith("/")) base else "$base/"
        return OnlineSourceRules.resolveUrl(normalizedBase, target.trimStart('/')) ?: target
    }

    /** 阅读's `<1,2,3>` page list: pick the page-th entry (last when past the end). */
    fun replacePageList(template: String, page: Int): String {
        if (!template.contains('<')) return template
        return PAGE_LIST.replace(template) { match ->
            val pages = match.groupValues[1].split(',').map { it.trim() }
            if (pages.isEmpty()) {
                match.value
            } else {
                pages.getOrElse(page - 1) { pages.last() }
            }
        }
    }

    /** A rule may append `,{headers:…}` options; the article only needs the URL. */
    private fun stripUrlOptions(url: String): String = UrlOptions.strip(url)

    /**
     * 阅读's `loginUrl`: fetched once per session (6h TTL) so the site can set
     * its session cookie; the cookie then rides along on every request through
     * the persistent cookie jar. Failures are non-fatal - the public part of
     * the source may still work.
     */
    private suspend fun ensureLogin(source: RssSource, rules: Rules) {
        val loginUrl = rules.loginUrl ?: return
        // A `@js:` / `<js>` loginUrl is a script, not a page: it only runs when
        // the user explicitly signs in (see the login screen).
        if (isJsLogin(loginUrl)) return
        val now = System.currentTimeMillis()
        // The 6h TTL survives a restart (persisted), so a cold start does not
        // re-hit the loginUrl.
        val last = maxOf(loginAt[source.id] ?: 0L, RssLoginStore.getLoginAt(source.id))
        if (now - last < LOGIN_TTL_MS) return
        val substituted = substitute(loginUrl, rules.variables, page = 1, sourceId = source.id)
        val absolute = if (substituted.startsWith("http://") || substituted.startsWith("https://")) {
            substituted
        } else {
            val base = rules.sourceUrl.trim()
            val normalized = if (base.endsWith("/")) base else "$base/"
            OnlineSourceRules.resolveUrl(normalized, substituted.trimStart('/')) ?: return
        }
        try {
            httpGet(
                stripUrlOptions(absolute),
                rules.header,
                requestJson = null,
                sourceId = source.id,
                cookies = rules.enabledCookieJar,
            )
            loginAt[source.id] = now
            RssLoginStore.putLoginAt(source.id, now)
            AppLog.d(TAG, "loginUrl fetched for source=${source.id}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "loginUrl failed: ${t.javaClass.simpleName}")
        }
    }

    /** True when `loginUrl` carries a JS snippet instead of a page URL. */
    fun isJsLogin(loginUrl: String?): Boolean =
        loginUrl?.trim()?.let {
            it.startsWith("@js:", ignoreCase = true) || it.startsWith("<js>", ignoreCase = true)
        } ?: false

    /**
     * 阅读 evaluates `loginCheckJs` on every fetched response (list and
     * content): the script sees the body as `result` and either throws or
     * returns false when the session is no longer valid. That turns into an
     * `auth` failure so the UI can prompt for a fresh login.
     */
    fun checkLoginScript(source: RssSource, rules: Rules?, body: String, url: String) {
        val check = rules?.loginCheckJs ?: loginCheckJs(source) ?: return
        val outcome = LegadoJs.run(
            script = check,
            bindings = mapOf("result" to body),
            baseUrl = url,
            sourceId = source.id,
            jsLib = rules?.jsLib,
        )
        val value = outcome.value?.trim()
        if (outcome.error != null || value == "false" || value == "0" ||
            value == "null" || value == "undefined"
        ) {
            AppLog.w(TAG, "login check failed: ${outcome.error ?: value}")
            throw FetchException("auth")
        }
    }

    /** The JS body of a `@js:` / `<js>` login script. */
    fun loginScript(loginUrl: String?): String? {
        val raw = loginUrl?.trim().orEmpty()
        return when {
            raw.startsWith("@js:", ignoreCase = true) -> raw.substring(4)
            raw.startsWith("<js>", ignoreCase = true) -> {
                val end = raw.lastIndexOf('<').takeIf { it > 4 } ?: raw.length
                raw.substring(4, end)
            }
            else -> null
        }
    }

    /**
     * Runs the source's `@js:` login like 阅读's `source.login()`: the script
     * defines a `login()` function, which we then call; anything it throws is
     * returned as an error message for the form.
     */
    fun runLoginScript(source: RssSource, script: String): LegadoJs.Result {
        val wrapped = buildString {
            append(script.trim())
            append("\n")
            append("if (typeof login == 'function') { login.apply(this); }")
            append(" else { throw 'Function login not implements!!!' }")
        }
        return LegadoJs.run(
            script = wrapped,
            bindings = emptyMap(),
            baseUrl = source.url,
            sourceId = source.id,
            jsLib = parseRules(source)?.jsLib,
        )
    }

    /** The `loginUi` form values saved by an earlier login (may be empty). */
    fun loginInfoMap(sourceId: Long): Map<String, String> {
        val raw = RssLoginStore.getUserInfo(sourceId) ?: return emptyMap()
        return try {
            (Json.parse(raw) as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
                val name = key as? String ?: return@mapNotNull null
                if (value == null) null else name to value.toString()
            }?.toMap().orEmpty()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Stores the values typed into the `loginUi` form before running the login script. */
    fun saveLoginInfo(sourceId: Long, values: Map<String, String>) {
        RssLoginStore.putUserInfo(sourceId, Json.encode(values))
    }

    private fun substitute(
        template: String,
        variables: Map<String, String>,
        page: Int,
        sourceId: Long,
    ): String {
        if (!template.contains("{{")) return template
        return RuleSplitter.innerRule(template) { expression ->
            val key = expression.trim()
            when {
                key == "page" -> page.toString()
                variables.containsKey(key) -> variables[key]
                else -> LegadoJs.eval(
                    key,
                    variables + mapOf("page" to page.toString()),
                    baseUrl = "",
                    sourceId = sourceId,
                )
            }
        }
    }

    /**
     * 阅读's `header` is a JSON object (`{"Referer":"…"}`), optionally produced
     * by a JS snippet (`@js:` / `<js>`). Older imports of ours used plain
     * `Key: Value` lines, so that form is still accepted as a fallback.
     */
    fun parseHeaderMap(header: String?, sourceId: Long, baseUrl: String = ""): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val json = when {
            header.startsWith("@js:", ignoreCase = true) ->
                LegadoJs.eval(header.substring(4), emptyMap(), baseUrl, sourceId)
            header.startsWith("<js>", ignoreCase = true) -> {
                val end = header.lastIndexOf('<').takeIf { it > 4 } ?: header.length
                LegadoJs.eval(header.substring(4, end), emptyMap(), baseUrl, sourceId)
            }
            else -> header
        }
        if (json.isNullOrBlank()) return emptyMap()
        val fromJson = try {
            (Json.parse(json) as? Map<*, *>)?.entries
                ?.mapNotNull { (key, value) ->
                    val name = key as? String ?: return@mapNotNull null
                    if (value == null) null else name to value.toString()
                }
                ?.toMap()
        } catch (_: Exception) {
            null
        }
        if (!fromJson.isNullOrEmpty()) return fromJson
        // Legacy line format: one `Name: value` per line.
        return header.lineSequence().mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) return@mapNotNull null
            val name = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()
    }

    private fun clientFor(cookies: Boolean): OkHttpClient =
        if (cookies) client else clientWithoutCookies

    private val clientWithoutCookies: OkHttpClient
        get() = com.wallpaperswitcher.engine.RssHttp.clientNoCookies

    private suspend fun httpGet(
        url: String,
        header: String?,
        requestJson: String?,
        sourceId: Long = 0L,
        cookies: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val request = buildRequest(url, header, requestJson, sourceId, extraHeaders)
        val http = clientFor(cookies)
        return try {
            ConcurrentRate.withLimit(sourceId) { executeCancellable(http, request) }
        } catch (t: Throwable) {
            // Fallback to a previously cached copy - a page we already visited
            // keeps working when the site is slow or briefly unreachable.
            cachedBody(http, request)?.let { return it }
            throw t
        }
    }

    /**
     * 组装一次抓取请求：阅读链接上的 `,{…}` 请求选项（请求头 / method / body）
     * 在这里并入，选项里的请求头优先于源 `header`（阅读 `AnalyzeUrl` 的顺序）。
     * 单独提出来是为了能在单元测试里直接断言最终请求。
     */
    internal fun buildRequest(
        url: String,
        header: String?,
        requestJson: String?,
        sourceId: Long = 0L,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request {
        // 阅读 允许链接带 `,{…}` 请求选项：地址本体照旧，选项（请求头 / method /
        // body）并进这一次请求 —— `…@href,{"headers":{"Referer":…}}` 就是这么用的。
        val (plainUrl, option) = UrlOptions.split(url)
        val builder = try {
            Request.Builder().url(plainUrl)
        } catch (t: Throwable) {
            throw FetchException("bad_url", cause = t)
        }
        builder.header("User-Agent", USER_AGENT)
        builder.header("Accept", "text/html, application/xhtml+xml, application/json, */*")
        parseHeaderMap(header, sourceId, plainUrl).forEach { (name, value) ->
            if (name.isNotBlank()) {
                try {
                    builder.header(name, value)
                } catch (_: Exception) {
                }
            }
        }
        // 链接选项的请求头覆盖源 header（阅读里 URL 选项的优先级更高）。
        (option.headers + extraHeaders).forEach { (name, value) ->
            if (name.isNotBlank()) {
                try {
                    builder.header(name, value)
                } catch (_: Exception) {
                }
            }
        }
        var method = option.method?.uppercase() ?: "GET"
        var body: String? = option.body
        var requestMediaType: String? = null
        requestJson?.let { raw ->
            try {
                val map = com.wallpaperswitcher.engine.Json.parse(raw) as? Map<*, *>
                if (map != null) {
                    method = (map["method"] as? String)?.uppercase() ?: "GET"
                    body = map["body"] as? String
                    requestMediaType = (map["mediaType"] as? String)
                    (map["headers"] as? Map<*, *>)?.forEach { (k, v) ->
                        if (k is String && v != null) {
                            try {
                                builder.header(k, v.toString())
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val request = if (method == "POST") {
            builder.post(
                (body ?: "").toRequestBody(
                    (requestMediaType ?: "application/x-www-form-urlencoded").toMediaType()
                )
            ).build()
        } else {
            builder.get().build()
        }
        return request
    }

    /**
     * The async OkHttp call: cancelling the coroutine (e.g. the user switched
     * category while this page was loading) aborts the request immediately
     * instead of waiting for it to finish.
     */
    private suspend fun executeCancellable(client: OkHttpClient, request: Request): String =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        val outcome = try {
                            if (!response.isSuccessful) {
                                Result.failure(
                                    FetchException(
                                        HttpFailureReason.of(response.code), response.code
                                    )
                                )
                            } else {
                                val body = response.peekBody(MAX_BODY_BYTES)
                                Result.success(
                                    ResponseCharset.decode(body.bytes(), body.contentType())
                                )
                            }
                        } catch (t: Throwable) {
                            Result.failure(t)
                        }
                        if (!cont.isCancelled) cont.resumeWith(outcome)
                    }
                }
            })
        }

    /** OkHttp `only-if-cached` read, used as the offline fallback of [httpGet]. */
    private fun cachedBody(client: OkHttpClient, request: Request): String? = try {
        client.newCall(
            request.newBuilder()
                .cacheControl(
                    okhttp3.CacheControl.Builder()
                        .onlyIfCached()
                        .maxStale(7, TimeUnit.DAYS)
                        .build()
                )
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.peekBody(MAX_BODY_BYTES)
                ResponseCharset.decode(body.bytes(), body.contentType())
                    .takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** Synchronous GET for `java.ajax(...)` inside JS rules. */
    fun fetchTextSync(url: String): String {
        val (plainUrl, option) = UrlOptions.split(url)
        val request = try {
            Request.Builder().url(plainUrl).header("User-Agent", USER_AGENT)
        } catch (t: Throwable) {
            throw FetchException("bad_url", cause = t)
        }
        option.headers.forEach { (name, value) ->
            if (name.isNotBlank()) {
                try {
                    request.header(name, value)
                } catch (_: Exception) {
                }
            }
        }
        val call = request.get().build()
        client.newCall(call).execute().use { response ->
            if (!response.isSuccessful) {
                throw FetchException(
                    HttpFailureReason.of(response.code), response.code
                )
            }
            val body = response.peekBody(MAX_BODY_BYTES)
            return ResponseCharset.decode(body.bytes(), body.contentType())
        }
    }

    /** `java.get/post` inside JS: the full response, not just the body. */
    fun httpRequestSync(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        mediaType: String? = null,
        cookies: Boolean = true,
    ): LegadoJsResponse {
        val (plainUrl, option) = UrlOptions.split(url)
        val builder = try {
            Request.Builder().url(plainUrl)
        } catch (t: Throwable) {
            throw FetchException("bad_url", cause = t)
        }
        builder.header("User-Agent", USER_AGENT)
        headers.forEach { (name, value) ->
            if (name.isNotBlank()) {
                try {
                    builder.header(name, value)
                } catch (_: Exception) {
                }
            }
        }
        option.headers.forEach { (name, value) ->
            if (name.isNotBlank()) {
                try {
                    builder.header(name, value)
                } catch (_: Exception) {
                }
            }
        }
        val request = when {
            method.equals("POST", ignoreCase = true) -> builder.post(
                (body ?: "").toRequestBody(
                    (mediaType ?: "application/x-www-form-urlencoded; charset=UTF-8").toMediaType()
                )
            ).build()
            method.equals("HEAD", ignoreCase = true) -> builder.head().build()
            else -> builder.get().build()
        }
        clientFor(cookies).newCall(request).execute().use { response ->
            val body = response.peekBody(MAX_BODY_BYTES)
            val text = ResponseCharset.decode(body.bytes(), body.contentType())
            val headerMap = LinkedHashMap<String, String>()
            for (name in response.headers.names()) {
                headerMap[name] = response.header(name).orEmpty()
            }
            return LegadoJsResponse(
                code = response.code,
                text = text,
                responseHeaders = headerMap,
                responseUrl = response.request.url.toString(),
            )
        }
    }

    /** Accepts the `headers` argument of `java.post`: a JS object or a JSON string. */
    fun toHeaderMap(value: Any?): Map<String, String> {
        when (value) {
            null -> return emptyMap()
            is Map<*, *> -> return value.entries.mapNotNull { (k, v) ->
                val key = k?.toString() ?: return@mapNotNull null
                if (v == null) null else key to v.toString()
            }.toMap()
            is String -> return try {
                (Json.parse(value) as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                    val key = k?.toString() ?: return@mapNotNull null
                    if (v == null) null else key to v.toString()
                }?.toMap().orEmpty()
            } catch (_: Exception) {
                parseHeaderMap(value, 0L)
            }
        }
        return try {
            // Rhino hands a JS object over as a Scriptable/NativeObject.
            val scriptable = value as? org.mozilla.javascript.Scriptable ?: return emptyMap()
            val ids = scriptable.ids ?: emptyArray()
            ids.mapNotNull { id ->
                val name = id?.toString() ?: return@mapNotNull null
                val item = scriptable.get(name, scriptable)
                if (item == null || item == org.mozilla.javascript.Scriptable.NOT_FOUND) {
                    null
                } else {
                    name to org.mozilla.javascript.Context.jsToJava(item, Any::class.java).toString()
                }
            }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /** `Content-Type` of a header map, if any (drives the POST media type). */
    fun mediaTypeOf(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value

    /**
     * The article body, fetched on demand: 阅读 applies `ruleContent` to the
     * article page (the list item itself usually carries no content).
     * Null when the source has no content rule or the page cannot be read; the
     * caller then falls back to the feed description.
     */
    suspend fun fetchArticleContent(
        source: RssSource,
        rules: Rules,
        link: String,
    ): String? {
        val base = fetchArticleBase(source, rules, link) ?: return null
        val extra = expandGalleryImages(source, rules, base.url, base.pageHtml)
        if (extra.isEmpty()) return base.html
        return base.html + "\n" + extra.joinToString("\n") { "<img src=\"$it\">" }
    }

    /** The article page itself: the rule output plus the raw page (for galleries). */
    data class ArticleBase(val html: String, val pageHtml: String, val url: String)

    /**
     * Fast half of [fetchArticleContent]: fetch the article page and apply the
     * content rule. Gallery sibling pages are **not** touched, so the reader can
     * show the first images immediately and stream the rest in.
     */
    suspend fun fetchArticleBase(
        source: RssSource,
        rules: Rules,
        link: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ArticleBase? = withContext(Dispatchers.IO) {
        val rule = rules.ruleContent?.takeIf { it.isNotBlank() } ?: return@withContext null
        ensureLogin(source, rules)
        val url = stripUrlOptions(link).takeIf { it.isNotBlank() } ?: return@withContext null
        if (OnlineSourceRules.endpointPolicy(url, allowCleartext = true) ==
            OnlineSourceRules.EndpointPolicy.INVALID
        ) {
            return@withContext null
        }
        val body = try {
            httpGet(
                url,
                rules.header,
                requestJson = null,
                sourceId = source.id,
                cookies = rules.enabledCookieJar,
                extraHeaders = extraHeaders,
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "article fetch failed: ${t.javaClass.simpleName}")
            return@withContext null
        }
        try {
            checkLoginScript(source, rules, body, url)
        } catch (t: Throwable) {
            AppLog.w(TAG, "article login check failed: ${t.javaClass.simpleName}")
            return@withContext null
        }
        val engine = LegadoRuleEngine(
            baseUrl = url,
            variables = rules.variables + mapOf("page" to "1"),
            sourceId = source.id,
            jsLib = rules.jsLib,
        )
        // 阅读 content rules come in two shapes: a selector chain, or a full
        // HTML template whose `{{…}}` blocks are inner rules / variables
        // (`{{@@tag.img@html}}`). Template results keep every match, like
        // 阅读's getString.
        val content = if (rule.contains("{{")) {
            expandContentTemplate(
                engine = engine,
                template = rule,
                content = body,
                variables = rules.variables + mapOf("page" to "1"),
                baseUrl = url,
                sourceId = source.id,
            )
        } else {
            firstValueJoined(engine, rule, body)
        }
        for (error in engine.takeErrors()) {
            AppLog.w(TAG, "content rule issue: ${error.reason} (${error.rule.take(60)})")
        }
        content.takeIf { it.isNotBlank() }?.let { ArticleBase(it, body, url) }
    }

    /**
     * 阅读 `{{…}}` blocks inside a content template: `{{@@rule}}` / `{{rule}}`
     * evaluate a nested rule against the page, anything else falls back to a
     * source variable or a JS expression.
     */
    fun expandContentTemplate(
        engine: LegadoRuleEngine,
        template: String,
        content: Any?,
        variables: Map<String, String>,
        baseUrl: String,
        sourceId: Long,
    ): String {
        return RuleSplitter.innerRule(template) { expression ->
            val expr = expression.trim()
            when {
                expr.isEmpty() -> ""
                expr.startsWith("@@") ->
                    engine.getString(expr.substring(2), content, joinAll = true)
                looksLikeRule(expr) -> engine.getString(expr, content, joinAll = true)
                variables.containsKey(expr) -> variables.getValue(expr)
                else -> LegadoJs.eval(
                    script = expr,
                    bindings = variables,
                    baseUrl = baseUrl,
                    sourceId = sourceId,
                ) ?: ""
            }
        }
    }

    private fun looksLikeRule(expr: String): Boolean {
        val lower = expr.lowercase()
        return expr.contains("@") ||
            lower.startsWith("class.") || lower.startsWith("tag.") ||
            lower.startsWith("id.") || lower.startsWith("text.") ||
            lower.startsWith("children") ||
            lower.startsWith("@css:") || lower.startsWith("@xpath:") ||
            lower.startsWith("@json:") || lower.startsWith("$.") ||
            lower.startsWith("$[") || expr.startsWith("/")
    }

    private val GALLERY_PAGE = Regex("""_(\d+)\.html""")
    private val GALLERY_IMAGE_EXT = Regex("""\.(jpe?g|png|webp|gif|bmp|avif)(\?|$)""")
    private const val MAX_GALLERY_PAGES = 40
    private const val GALLERY_PARALLELISM = 6
    /** Extra pages probed beyond the page's own `_N.html` max link. */
    private const val GALLERY_MIN_PROBE = 6
    /** Give up after this many consecutive pages without a new image. */
    private const val GALLERY_EMPTY_STREAK = 3

    /**
     * Many image sources hide the rest of a gallery behind sibling pages
     * (`19566_1.html`, `19566_2.html`, …) and let page script load them.
     * Their rules cannot run here, so when the content rule is script-driven we
     * do the same expansion natively: read the highest `_N.html` index from the
     * article page, fetch those pages and append every gallery image.
     */
    suspend fun expandGalleryImages(
        source: RssSource,
        rules: Rules,
        articleUrl: String,
        pageHtml: String,
    ): List<String> {
        if (rules.ruleContent?.contains("<script", ignoreCase = true) != true) return emptyList()
        if (!articleUrl.endsWith(".html", ignoreCase = true)) return emptyList()
        val base = articleUrl.dropLast(5)
        val cap = GALLERY_PAGE.findAll(pageHtml)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        if (cap <= 0) return emptyList()
        val images = LinkedHashSet<String>()
        val filter = scriptGalleryFilter(rules.ruleContent) ?: { url: String -> isGalleryImage(url) }
        // Safety net: if the source's own filter drops everything, fall back to
        // the generic one instead of returning an empty gallery.
        val genericImages = LinkedHashSet<String>()
        // Gallery pages are independent: fetch a few at a time so opening an
        // article does not wait for every page sequentially.
        // Probe slightly beyond the page's own max link; stop after a few empty
        // pages so a wrong/short cap cannot truncate the gallery.
        val lastPage = minOf(maxOf(cap, GALLERY_MIN_PROBE), MAX_GALLERY_PAGES)
        coroutineScope {
            val gate = Semaphore(GALLERY_PARALLELISM)
            val pages = (1..lastPage).map { index ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val pageUrl = "${base}_$index.html"
                        val html = try {
                            httpGet(
                                pageUrl,
                                rules.header,
                                requestJson = null,
                                sourceId = source.id,
                                cookies = rules.enabledCookieJar,
                            )
                        } catch (_: Throwable) {
                            null
                        }
                        pageUrl to html
                    }
                }
            }
            var emptyStreak = 0
            for (page in pages) {
                val (pageUrl, html) = page.await()
                if (html == null) {
                    emptyStreak++
                    if (emptyStreak >= GALLERY_EMPTY_STREAK && images.isNotEmpty()) break
                    continue
                }
                var added = 0
                for (raw in FeedParser.allImageUrls(html)) {
                    val absolute = OnlineSourceRules.resolveUrl(pageUrl, raw) ?: raw
                    if (filter(absolute) && images.add(absolute)) added++
                    if (isGalleryImage(absolute)) genericImages.add(absolute)
                }
                AppLog.d(
                    TAG,
                    "gallery ${pageUrl.substringAfterLast('/')} imgs=$added total=${images.size}"
                )
                emptyStreak = if (added == 0) emptyStreak + 1 else 0
                if (emptyStreak >= GALLERY_EMPTY_STREAK && images.isNotEmpty()) break
            }
        }
        if (images.isEmpty()) {
            if (genericImages.isEmpty()) return emptyList()
            AppLog.w(TAG, "gallery filter kept nothing; using generic filter")
            return genericImages.toList()
        }
        AppLog.d(TAG, "gallery expanded: source=${source.id} pages=$cap images=${images.size}")
        return images.toList()
    }

    /** Skip site chrome and the small cover thumbnails (`/pic/…`). */
    private fun isGalleryImage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("/template/") || lower.contains("/pic/")) return false
        if (lower.contains("logo") || lower.contains("icon")) return false
        return GALLERY_IMAGE_EXT.containsMatchIn(lower)
    }

    /**
     * 3w-style sources put their own keep/drop filter into the `ruleContent`
     * script (`/\/uploadfile\/(?!pic\/)/i.test(s)`, `logo|icon|…`). When we can
     * see such a path filter we follow it instead of the generic heuristics, so
     * the wall of images matches what 阅读 renders.
     */
    private fun scriptGalleryFilter(rule: String?): ((String) -> Boolean)? {
        if (rule.isNullOrBlank() || !rule.contains("<script", true)) return null
        val bodies = SCRIPT_REGEX_LITERAL.findAll(rule).map { it.groupValues[1] }.toList()
        if (bodies.isEmpty()) return null
        // Only accept a *clean* path literal such as `/uploadfile/`; regexes like
        // `\?.*$` (URL cleanup) must not be mistaken for the keep-path.
        val pathBody = bodies
            .map { it.replace("\\/", "/").substringBefore("(?") }
            .firstOrNull { CLEAN_PATH.matches(it.trim()) }
            ?: return null
        val keep = pathBody.trim().trim('/')
        if (keep.length < 4) return null
        val junk = bodies.firstOrNull { it.contains('|') }
            ?.split('|')
            ?.map { it.trim().trim('\\').trim('/').lowercase() }
            ?.filter { it.length >= 4 }
            .orEmpty()
        val dropPic = pathBody.contains("pic", ignoreCase = true)
        return { url ->
            val lower = url.lowercase()
            lower.contains("/$keep/") &&
                !(dropPic && lower.contains("/pic/")) &&
                junk.none { lower.contains(it) } &&
                GALLERY_IMAGE_EXT.containsMatchIn(lower)
        }
    }

    /** Regex literals of a page script (`/…/i`). */
    private val SCRIPT_REGEX_LITERAL = Regex("""/((?:[^/\\\n]|\\.)+)/[a-z]*""")

    /** A plain path literal (`/uploadfile/`), no regex metacharacters allowed. */
    private val CLEAN_PATH = Regex("""^/[A-Za-z0-9_\-/]{4,}/?$""")

    /**
     * Keeps one browser-collected image? Applies the source's own script filter
     * when it has one, otherwise the generic gallery heuristics. Used by the
     * browser mode so the picker only shows real gallery pictures.
     */
    fun keepCollectedImage(ruleContent: String?, url: String): Boolean {
        val filter = scriptGalleryFilter(ruleContent)
        return if (filter != null) filter(url) else isGalleryImage(url)
    }

    /**
     * The page an interactive login should open: the source's `loginUrl`
     * (resolved against `sourceUrl`), or the source URL itself when no login
     * page is configured.
     */
    fun loginEndpoint(source: RssSource): String {
        val map = rawMap(source)
        val rules = parseRules(source)
        // `loginUrl` is a source-level field: a plain RSS source (no
        // ruleArticles) can carry one too, so read it from the raw JSON
        // instead of the rules-only view.
        val raw = (map?.get("loginUrl") as? String)?.takeIf { it.isNotBlank() }
            ?: rules?.loginUrl?.takeIf { it.isNotBlank() }
            ?: source.url
        val substituted = substitute(raw, rules?.variables ?: emptyMap(), page = 1, sourceId = source.id)
        if (substituted.startsWith("http://") || substituted.startsWith("https://")) {
            return substituted
        }
        val base = (map?.get("sourceUrl") as? String)?.takeIf { it.isNotBlank() }
            ?: rules?.sourceUrl
            ?: source.url
        return resolveAgainstSource(base, substituted)
    }

    /**
     * 阅读 `loginCheckJs`: a snippet run inside the login WebView that reports
     * whether the page is already logged in (non-empty / `true` result). It is
     * optional; null / blank means "no automatic check configured".
     */
    fun loginCheckJs(source: RssSource): String? =
        (rawMap(source)?.get("loginCheckJs") as? String)?.takeIf { it.isNotBlank() }

    private val PAGE_LIST = Regex("""<([^<>]*)>""")
    private val CATEGORY_SEPARATOR = Regex("""(&&|\r?\n)+""")

    private const val LOGIN_TTL_MS = 6L * 60 * 60 * 1000
    private val loginAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val formats = listOf(
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"),
        )
        val text = raw.trim()
        for (formatter in formats) {
            try {
                return java.time.ZonedDateTime.parse(text, formatter).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
            try {
                return java.time.OffsetDateTime.parse(text, formatter).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
            try {
                return java.time.LocalDateTime.parse(text, formatter)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
            try {
                return java.time.LocalDate.parse(text, formatter)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return 0L
    }
}
