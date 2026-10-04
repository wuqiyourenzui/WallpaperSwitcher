package com.wallpaperswitcher.engine

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.RssArticle
import com.wallpaperswitcher.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Refresh of one subscription: fetch, merge (keeping the read state), prune and
 * record the result. Serialized so a "refresh all" cannot download the same
 * feed twice or race the article table.
 */
object RssSync {

    private const val TAG = "RssSync"
    /** Articles kept per source; older ones are deleted after each refresh. */
    /**
     * Kept in sync with the list query's cap: paging appends older pages, so
     * both have to be large enough for several pages to coexist.
     */
    const val KEEP_PER_SOURCE = com.wallpaperswitcher.data.LIST_LIMIT
    /** Pages fetched when (re)loading a source / when the user scrolls further. */
    private const val INITIAL_PAGES = 2
    private const val LOAD_MORE_PAGES = 2
    /**
     * Marker stored at the head of every cached article body. Versions before
     * this could cache a partial gallery (5-12 images); bumping the marker makes
     * those bodies look stale once and be re-fetched automatically.
     */
    private const val CONTENT_VERSION = "<!--wsbody:2-->"
    /** Playable file extensions (HLS playlists are intentionally excluded). */
    private val VIDEO_FILE = Regex("""\.(mp4|webm|mov|m4v|mkv)(\?|$)""")

    /** True when a collected URL is a playable video file (not an HLS playlist). */
    fun looksLikePlayableVideo(url: String): Boolean =
        VIDEO_FILE.containsMatchIn(url.lowercase())

    /** One lock per source: refreshing A must not block B (or its own paging). */
    private val sourceLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    private fun lockFor(sourceId: Long): Mutex = sourceLocks.getOrPut(sourceId) { Mutex() }

    data class Report(val ok: Boolean, val added: Int, val reason: String)

    /**
     * Article body for the reader, plus the images found on the article page.
     * [pageHtml] is the raw page: the reader passes it back to
     * [loadGalleryImages] so script-driven galleries stream in afterwards
     * instead of blocking the first paint.
     */
    data class ArticleContent(
        val html: String,
        val images: List<String>,
        val pageHtml: String = "",
    )

    /**
     * Load one article's body. Rule-based sources fetch the article page and
     * apply `ruleContent`; the result is cached in `rss_articles.content`.
     */
    suspend fun fetchContent(
        context: Context,
        article: RssArticle,
        force: Boolean = false,
    ): ArticleContent =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val source = try {
                db.rssSourceDao().getById(article.sourceId)
            } catch (_: Throwable) {
                null
            }
            val rules = source?.let {
                com.wallpaperswitcher.engine.legado.LegadoRss.rulesFor(it)
            }
            // The list query deliberately leaves `content` out (it is the bulky
            // column), so a cached body has to be read back from the database.
            val stored = if (article.content.isNotBlank()) {
                article.content
            } else {
                try {
                    db.rssArticleDao().getContent(article.sourceId, article.guid).orEmpty()
                } catch (_: Throwable) {
                    ""
                }
            }
            // The version marker is metadata, never part of the rendered body.
            var html = stored.removePrefix(CONTENT_VERSION)
                .ifBlank { article.description }
            if (stored.isNotBlank() && !stored.startsWith(CONTENT_VERSION)) {
                // Stale body from an older pipeline: show it, then re-fetch below.
                html = stored
            }
            var pageHtml = ""
            // Already cached (the merged body, gallery included): re-opening the
            // article must not fetch the page or re-expand the gallery again.
            val cachedFresh = stored.startsWith(CONTENT_VERSION)
            if (stored.isNotBlank() && !force && cachedFresh) {
                pageHtml = ""
            } else if (rules?.ruleContent != null && article.link.isNotBlank() && source != null) {
                com.wallpaperswitcher.engine.legado.LegadoRss
                    .fetchArticleBase(
                        source,
                        rules,
                        article.link,
                        // 阅读链接选项里存的请求头（Referer 之类）也用于正文抓取。
                        com.wallpaperswitcher.engine.legado.LegadoRss.requestHeaders(article),
                    )
                    ?.let { base ->
                        html = base.html
                        pageHtml = base.pageHtml
                        try {
                            db.rssArticleDao().updateContent(
                                article.sourceId, article.guid, CONTENT_VERSION + base.html
                            )
                        } catch (_: Throwable) {
                        }
                    }
            }
            val base = article.link.ifBlank { source?.url.orEmpty() }
            val images = LinkedHashSet<String>()
            if (article.imageUrl.isNotBlank()) images.add(article.imageUrl)
            val ruleContent = rules?.ruleContent?.takeIf { it.isNotBlank() }
            val rawImages = LinkedHashSet<String>()
            for (raw in FeedParser.allImageUrls(html)) {
                val url = OnlineSourceRules.resolveUrl(base, raw) ?: raw
                rawImages.add(url)
            }
            if (ruleContent != null) {
                // Rule sources: apply the same filter as the gallery/browser
                // paths so script/CSS finds cannot smuggle site chrome in.
                for (url in rawImages) {
                    if (com.wallpaperswitcher.engine.legado.LegadoRss
                            .keepCollectedImage(ruleContent, url)
                    ) {
                        images.add(url)
                    }
                }
                if (images.isEmpty()) images.addAll(rawImages)
            } else {
                images.addAll(rawImages)
            }
            // Video sources: collect playable URLs (and follow one level of
            // iframe/player pages, where these sites keep the real address).
            if (source != null) {
                var videoCandidates = FeedParser.allVideoUrls(html)
                var depth = 0
                while (depth < 2) {
                    val direct = videoCandidates.filter { VIDEO_FILE.containsMatchIn(it.lowercase()) }
                    for (url in direct) {
                        images.add(OnlineSourceRules.resolveUrl(base, url) ?: url)
                    }
                    val pages = videoCandidates
                        .filterNot { VIDEO_FILE.containsMatchIn(it.lowercase()) }
                        .take(2)
                    if (pages.isEmpty()) break
                    videoCandidates = emptyList()
                    for (pageUrl in pages) {
                        val absolute = OnlineSourceRules.resolveUrl(base, pageUrl) ?: continue
                        val nested = try {
                            com.wallpaperswitcher.engine.RssFetcher.fetchText(absolute)
                        } catch (_: Throwable) {
                            continue
                        }
                        videoCandidates = videoCandidates + FeedParser.allVideoUrls(nested)
                    }
                    depth++
                }
            }
            ArticleContent(html = html, images = images.toList(), pageHtml = pageHtml)
        }

    /**
     * The rest of a script-driven gallery: fetch the `_N.html` sibling pages and
     * return their images. The merged body is written back to the article so the
     * next open is instant. Already-cached articles pass an empty [pageHtml] and
     * skip this entirely.
     */
    suspend fun loadGalleryImages(
        context: Context,
        article: RssArticle,
        pageHtml: String,
        baseHtml: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (pageHtml.isBlank() || article.link.isBlank()) return@withContext emptyList()
        val db = AppDatabase.getInstance(context)
        val source = try {
            db.rssSourceDao().getById(article.sourceId)
        } catch (_: Throwable) {
            null
        } ?: return@withContext emptyList()
        val rules = com.wallpaperswitcher.engine.legado.LegadoRss.rulesFor(source)
            ?: return@withContext emptyList()
        val images = try {
            com.wallpaperswitcher.engine.legado.LegadoRss
                .expandGalleryImages(source, rules, article.link, pageHtml)
        } catch (_: Throwable) {
            emptyList()
        }
        if (images.isEmpty()) return@withContext emptyList()
        try {
            val merged = baseHtml + "\n" + images.joinToString("\n") { "<img src=\"$it\">" }
            db.rssArticleDao()
                .updateContent(article.sourceId, article.guid, CONTENT_VERSION + merged)
        } catch (_: Throwable) {
        }
        images
    }

    suspend fun refresh(
        context: Context,
        sourceId: Long,
        initialPages: Int = INITIAL_PAGES,
    ): Report = lockFor(sourceId).withLock {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val source = try {
                db.rssSourceDao().getById(sourceId)
            } catch (_: Throwable) {
                null
            } ?: return@withContext Report(false, 0, "missing")
            if (!source.enabled) return@withContext Report(false, 0, "disabled")
            // 单 URL / 网页型源是「用浏览器打开」的：抓列表没有意义，直接跳过，
            // 也不写「更新失败」状态（否则卡片会一直显示解析失败）。
            if (com.wallpaperswitcher.engine.legado.LegadoRss.isBrowseOnly(source)) {
                return@withContext Report(true, 0, "browse")
            }

            val startedAt = System.currentTimeMillis()
            try {
                val categoryIndex = com.wallpaperswitcher.engine.legado.RssCategories
                    .selectedIndex(context, sourceId)
                // A refresh restarts from the source's first page and remembers
                // the cursor so "load more" can continue from there.
                val page = RssFetcher.fetchPages(
                    source = source,
                    categoryIndex = categoryIndex,
                    startCursor = null,
                    maxPages = initialPages.coerceAtLeast(1),
                )
                val parsed = page.articles
                val existingRead = existingReadIds(db, sourceId)
                val existingIds = try {
                    db.rssArticleDao().getGuids(sourceId).toHashSet()
                } catch (_: Throwable) {
                    HashSet()
                }
                val rows = buildRows(
                    sourceId,
                    parsed,
                    cachedContents(db, sourceId, parsed),
                    existingRead,
                    System.currentTimeMillis(),
                )
                if (rows.isNotEmpty()) db.rssArticleDao().insertAll(rows)
                db.rssArticleDao().prune(sourceId, KEEP_PER_SOURCE)
                com.wallpaperswitcher.engine.legado.RssPaging
                    .setCursor(context, sourceId, page.nextCursor)
                val added = rows.count { it.guid !in existingIds }
                val now = System.currentTimeMillis()
                db.rssSourceDao().recordResult(
                    id = sourceId,
                    at = now,
                    result = OnlineSourceRules.encodeOk(added, 0),
                    errorAt = 0L,
                )
                AppLog.d(TAG, "RSS refresh ok: source=$sourceId fetched=${rows.size} new=$added")
                AppLog.d(TAG, "RSS refresh took ${System.currentTimeMillis() - startedAt}ms (source=$sourceId)")
                Report(true, added, "ok")
            } catch (t: Throwable) {
                val reason = RssFetcher.classify(t)
                val now = System.currentTimeMillis()
                try {
                    db.rssSourceDao().recordResult(
                        id = sourceId,
                        at = now,
                        result = OnlineSourceRules.encodeError(reason),
                        errorAt = now,
                    )
                } catch (_: Throwable) {
                }
                AppLog.w(
                    TAG,
                    "RSS refresh failed: source=$sourceId reason=$reason " +
                        "(${t.javaClass.simpleName}: ${t.message?.take(160).orEmpty()})" +
                        // 记下前几帧：像 PatternSyntaxException 这种没有上下文的
                        // 异常，只有堆栈能指出是哪条规则/哪一步抛的。
                        t.stackTrace.take(6).joinToString(" <- ") {
                            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                        }
                )
                Report(false, 0, reason)
            }
        }
    }

    /** Refresh every enabled source, one after another (used by the worker). */
    suspend fun refreshAll(context: Context) {
        val sources = try {
            AppDatabase.getInstance(context).rssSourceDao().getEnabled()
        } catch (_: Throwable) {
            return
        }
        for (source in sources) {
            refresh(context, source.id)
        }
    }

    /**
     * 阅读-style "load more": fetch the next page(s) of the source's article
     * list and append them. Returns `end` in [Report.reason] when the source
     * has no further pages.
     */
    suspend fun loadMore(context: Context, sourceId: Long): Report = lockFor(sourceId).withLock {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val source = try {
                db.rssSourceDao().getById(sourceId)
            } catch (_: Throwable) {
                null
            } ?: return@withContext Report(false, 0, "missing")
            val cursor = com.wallpaperswitcher.engine.legado.RssPaging
                .cursor(context, sourceId)
                ?: return@withContext Report(true, 0, "end")
            try {
                val categoryIndex = com.wallpaperswitcher.engine.legado.RssCategories
                    .selectedIndex(context, sourceId)
                val page = RssFetcher.fetchPages(
                    source = source,
                    categoryIndex = categoryIndex,
                    startCursor = cursor,
                    maxPages = LOAD_MORE_PAGES,
                )
                val existingIds = try {
                    db.rssArticleDao().getGuids(sourceId).toHashSet()
                } catch (_: Throwable) {
                    HashSet()
                }
                // The next page is OLDER, so it must be stamped below everything
                // already in the list: the list sorts by publishedAt DESC then
                // fetchedAt DESC, and an undated feed ends up ordered by
                // fetchedAt alone. Using "now" here put page 2 on top of page 1
                // (the list jumped to the freshly fetched rows).
                val oldest = try {
                    db.rssArticleDao().minFetchedAt(sourceId)
                } catch (_: Throwable) {
                    null
                } ?: System.currentTimeMillis()
                val rows = buildRows(
                    sourceId,
                    page.articles,
                    cachedContents(db, sourceId, page.articles),
                    existingReadIds(db, sourceId),
                    oldest - 1,
                )
                // IGNORE: an article that is already on screen keeps its slot.
                if (rows.isNotEmpty()) db.rssArticleDao().insertNew(rows)
                db.rssArticleDao().prune(sourceId, KEEP_PER_SOURCE)
                val total = try {
                    db.rssArticleDao().count(sourceId)
                } catch (_: Throwable) {
                    0
                }
                // At the cap the old pages cannot be kept any more, so stop
                // advertising more pages instead of paging into the void.
                val next = if (total >= KEEP_PER_SOURCE) null else page.nextCursor
                com.wallpaperswitcher.engine.legado.RssPaging
                    .setCursor(context, sourceId, next)
                val added = rows.count { it.guid !in existingIds }
                AppLog.d(
                    TAG,
                    "RSS load more ok: source=$sourceId fetched=${rows.size} new=$added " +
                        "total=$total more=${next != null}"
                )
                Report(true, added, if (next == null) "end" else "ok")
            } catch (t: Throwable) {
                val reason = RssFetcher.classify(t)
                try {
                    val now = System.currentTimeMillis()
                    db.rssSourceDao().recordResult(
                        id = sourceId,
                        at = now,
                        result = OnlineSourceRules.encodeError(reason),
                        errorAt = now,
                    )
                } catch (_: Throwable) {
                }
                AppLog.w(
                    TAG,
                    "RSS load more failed: source=$sourceId reason=$reason " +
                        "(${t.javaClass.simpleName}: ${t.message?.take(160).orEmpty()})"
                )
                Report(false, 0, reason)
            }
        }
    }

    private suspend fun existingReadIds(
        db: AppDatabase,
        sourceId: Long,
    ): Set<String> = try {
        db.rssArticleDao().getReadGuids(sourceId).toHashSet()
    } catch (_: Throwable) {
        HashSet()
    }

    /** Cached bodies of just the fetched guids (the bulk `content` column stays in SQLite). */
    private suspend fun cachedContents(
        db: AppDatabase,
        sourceId: Long,
        parsed: List<FeedParser.ParsedArticle>,
    ): Map<String, String> = try {
        val guids = parsed.filter { it.content.isBlank() }.map { it.guid }
        if (guids.isEmpty()) {
            emptyMap()
        } else {
            db.rssArticleDao().getContents(sourceId, guids).associate { it.guid to it.content }
        }
    } catch (_: Throwable) {
        emptyMap()
    }

    /** Merge fetched articles with the stored rows (keeps read state + body). */
    private fun buildRows(
        sourceId: Long,
        parsed: List<FeedParser.ParsedArticle>,
        cachedContents: Map<String, String>,
        existingRead: Set<String>,
        now: Long,
    ): List<RssArticle> = parsed.mapIndexed { index, article ->
        RssArticle(
            sourceId = sourceId,
            guid = article.guid,
            title = article.title,
            link = article.link,
            description = article.description,
            // A rule-based source carries no content in the list item; a
            // refresh must NOT wipe the body the reader fetched on demand.
            content = article.content.ifBlank { cachedContents[article.guid].orEmpty() },
            imageUrl = article.imageUrl,
            requestHeaders = article.requestHeaders,
            sort = article.sort,
            publishedAt = article.publishedAt,
            // Feeds without dates sort purely by `fetchedAt`, so the page order
            // has to be encoded in it: item 0 is the page's first (newest) one.
            fetchedAt = now - index,
            isRead = article.guid in existingRead,
        )
    }
}
