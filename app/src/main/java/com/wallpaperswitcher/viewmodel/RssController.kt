package com.wallpaperswitcher.viewmodel

import android.app.Application
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.setBool
import com.wallpaperswitcher.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读 (Legado) 订阅子系统，从 `WallpaperViewModel` 拆分出来：源列表/分类/
 * 分页/登录/导入与“加入分组”的编排。逻辑逐字搬移，只把 app、协程 scope 与
 * toast 输出改为构造参数。
 */
internal class RssController(
    private val app: Application,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val onToast: suspend (String) -> Unit,
) {

    companion object {
        /** 页面 Flow 失去订阅后 60 秒才停（与 [WallpaperViewModel] 同一口径）。 */
        const val FLOW_STOP_TIMEOUT_MS = 60_000L
    }

    private val tag = "RssController"

    /** 阅读订阅源 (Legado-compatible RSS/Atom feeds), newest first. */
    val rssSources: StateFlow<List<com.wallpaperswitcher.data.RssSource>> =
        db.rssSourceDao().observeAll()
            .stateIn(scope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MS), emptyList())

    /**
     * 订阅源列表的显示方式：false = 卡片列表（默认，老安装不变），
     * true = 缩略图网格（见 engine.RssIcons）。
     */
    val rssGridView: StateFlow<Boolean> =
        db.settingsDao().getValueFlow(SettingsKeys.RSS_GRID_VIEW)
            .map { it?.toBooleanStrictOrNull() ?: false }
            .stateIn(scope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MS), false)

    fun setRssGridView(enabled: Boolean) {
        guardedWrite("保存订阅显示方式失败") {
            db.settingsDao().setBool(SettingsKeys.RSS_GRID_VIEW, enabled)
        }
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun guardedWrite(errorMessage: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(tag, errorMessage, e)
                onToast(str(R.string.toast_save_failed, e.message.orEmpty()))
            }
        }
    }

    /**
     * 阅读的「调试源」（`RssSourceDebugActivity`）：抓一次当前分类的第一页，
     * 把列表规则/逐项字段各取到多少、示例条目长什么样报出来，用于判断
     * 「源在阅读里能显示、在这里显示不出来」到底断在哪一步。
     *
     * 不做任何写入：只读抓取 + 规则求值。返回文本报告（调用方直接展示）。
     */
    suspend fun rssDebugSource(source: com.wallpaperswitcher.data.RssSource): String =
        withContext(Dispatchers.IO) {
            val out = StringBuilder()
            val rules = try {
                com.wallpaperswitcher.engine.legado.LegadoRss.rulesFor(source)
            } catch (t: Throwable) {
                null
            }
            if (rules == null) {
                out.appendLine(str(R.string.rss_debug_no_rules))
                return@withContext out.toString()
            }
            val categories = try {
                com.wallpaperswitcher.engine.legado.LegadoRss.sortUrls(source, rules)
            } catch (t: Throwable) {
                emptyList()
            }
            out.appendLine("${str(R.string.rss_debug_categories)}: ${categories.size}")
            val index = try {
                com.wallpaperswitcher.engine.legado.RssCategories
                    .selectedIndex(app, source.id)
            } catch (_: Throwable) {
                0
            }
            val category = categories.getOrNull(index) ?: categories.firstOrNull()
            out.appendLine("${str(R.string.rss_debug_category)}: ${category?.name.orEmpty()}")
            val url = try {
                com.wallpaperswitcher.engine.legado.LegadoRss.pageUrl(rules, category, 1, source.id)
            } catch (t: Throwable) {
                out.appendLine("pageUrl: ${t.javaClass.simpleName}")
                return@withContext out.toString()
            }
            out.appendLine("URL: $url")
            out.appendLine("ruleArticles: ${rules.ruleArticles}")
            val page = try {
                // 只跑「抓一页 + 求值规则」，不落库（调试不该改数据）。
                com.wallpaperswitcher.engine.RssFetcher
                    .fetchPages(source, index, startCursor = null, maxPages = 1)
            } catch (t: Throwable) {
                out.appendLine("${str(R.string.rss_debug_failed)}: ${t.message?.take(160)}")
                return@withContext out.toString()
            }
            val articles = page.articles
            out.appendLine("${str(R.string.rss_debug_items)}: ${articles.size}")
            out.appendLine("cursor: ${page.nextCursor.orEmpty()}")
            for ((i, article) in articles.take(3).withIndex()) {
                out.appendLine()
                out.appendLine("[$i] title=${article.title.take(60)}")
                out.appendLine("    link=${article.link.take(70)}")
                out.appendLine("    image=${article.imageUrl.take(70)}")
                out.appendLine("    date=${article.publishedAt}")
            }
            out.toString()
        }

    /**
     * 后台刷新当前分类（界面已经在显示缓存）：不阻塞调用方，抓完由房间表
     * 自动把新内容顶到列表上。同一源的旧任务会先取消，避免并发抓同一个源。
     */
    fun launchRefreshRss(source: com.wallpaperswitcher.data.RssSource, index: Int) {
        rssFetchJobs.remove(source.id)?.cancel()
        val job = scope.launch {
            try {
                com.wallpaperswitcher.engine.legado.RssCategories
                    .setSelectedIndex(app, source.id, index)
                com.wallpaperswitcher.engine.legado.RssPaging
                    .setCursor(app, source.id, null)
                com.wallpaperswitcher.engine.RssSync.refresh(app, source.id, initialPages = 1)
            } catch (_: Exception) {
            }
        }
        rssFetchJobs[source.id] = job
    }

    /** In-flight subscription fetch per source (cancelled when it is preempted). */
    private val rssFetchJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /**
     * 进入一个源/切到一个分类时怎么开屏：有缓存就立即渲染（刷新在后台），
     * 没缓存只能等第一次抓取。抽出来是为了能离线测这条判定。
     */
    internal enum class RssEnterPlan {
        RenderFromCache,
        WaitForFirstFetch;

        companion object {
            /** [cachedCount] 是这个分类已存的文章数。 */
            fun decide(cachedCount: Int): RssEnterPlan =
                if (cachedCount > 0) RenderFromCache else WaitForFirstFetch
        }
    }

    // --- 阅读订阅源 (Legado-compatible) ---

    /** Articles of one subscription, newest first. */
    fun rssArticles(sourceId: Long): Flow<List<com.wallpaperswitcher.data.RssArticle>> =
        db.rssArticleDao().observeBySource(sourceId)

    /** Articles of one subscription category ("" = plain feed). */
    fun rssArticlesOfSort(
        sourceId: Long,
        sort: String,
    ): Flow<List<com.wallpaperswitcher.data.RssArticle>> =
        db.rssArticleDao().observeBySourceSort(sourceId, sort)

    /** 阅读 categories of a source (`sortUrl` entries); empty for plain feeds. */
    fun rssCategoryNames(source: com.wallpaperswitcher.data.RssSource): List<String> =
        com.wallpaperswitcher.engine.legado.RssCategories.names(source)

    /** Cached categories (instant) for the chips row. */
    suspend fun rssCachedCategories(source: com.wallpaperswitcher.data.RssSource): List<String> =
        com.wallpaperswitcher.engine.legado.RssCategories.cachedNames(app, source)

    /** Cache-only categories: never evaluates the source's `<js>` sortUrl. */
    suspend fun rssCachedCategoriesOnly(source: com.wallpaperswitcher.data.RssSource): List<String> =
        com.wallpaperswitcher.engine.legado.RssCategories.cachedOnly(app, source)

    /** Recompute + cache the categories (keeps the old list on failure). */
    suspend fun rssRefreshCategories(source: com.wallpaperswitcher.data.RssSource): List<String> =
        com.wallpaperswitcher.engine.legado.RssCategories.refreshNames(app, source)

    /**
     * 阅读编辑页菜单的「清除 Cookie」：把这个源域名下的会话清掉，下次请求重新登录。
     */
    suspend fun rssClearCookie(source: com.wallpaperswitcher.data.RssSource) {
        withContext(Dispatchers.IO) {
            try {
                com.wallpaperswitcher.engine.RssCookieStore.clearHost(source.url)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 「切换布局」：`articleStyle` 在 **1→2→3→4→1** 之间循环。
     *
     * 不含 0：0 是「没设过」（列表排版 = 单列列表），而单列列表与 1 在渲染上
     * 没有区别，所以循环把它和 `4` 合并，少按一次、也不会出现两档看起来一样。
     * 抽出来是为了能离线测这条循环。
     */
    internal object RssArticleStyleCycle {
        /** 下一档；越界值（0、手改坏、将来加新档）统一从 1 重新开始。 */
        fun next(current: Int): Int = when (current) {
            in 1..3 -> current + 1
            4 -> 1
            else -> 1
        }
    }

    /**
     * 阅读的「切换布局」：把该源的 `articleStyle` 推进一档并存回源，返回新值。
     *
     * 当前值**从库里现读**，不用调用方传来的那份 `source`：界面上的 `source`
     * 可能还是上一次重组时的旧值，连点两次会都从同一档算，看起来就是「点了没反应」
     * 或「跳档」。库里读到的值永远是最新的，所以一次点击必然只走一档。
     */
    suspend fun rssCycleArticleStyle(sourceId: Long): Int {
        return withContext(Dispatchers.IO) {
            val current = try {
                db.rssSourceDao().getById(sourceId)
            } catch (_: Throwable) {
                null
            } ?: return@withContext 0
            val next = RssArticleStyleCycle.next(current.articleStyle)
            try {
                db.rssSourceDao().update(current.copy(articleStyle = next))
            } catch (_: Throwable) {
            }
            next
        }
    }

    /** Index of the category the user last picked for this source. */
    suspend fun rssSelectedCategory(sourceId: Long): Int =
        com.wallpaperswitcher.engine.legado.RssCategories.selectedIndex(app, sourceId)

    /**
     * Switch category: remember the choice and fetch **one** page so the list
     * appears quickly; the rest is loaded lazily by scrolling.
     */
    suspend fun rssSelectCategory(source: com.wallpaperswitcher.data.RssSource, index: Int) {        // Preempt: whatever this source was fetching (previous category tap or
        // a load-more) is cancelled so the new category starts immediately.
        rssFetchJobs.remove(source.id)?.cancel()
        kotlinx.coroutines.currentCoroutineContext()[Job]
            ?.let { rssFetchJobs[source.id] = it }
        com.wallpaperswitcher.engine.legado.RssCategories
            .setSelectedIndex(app, source.id, index)
        // A new category starts from its own first page.
        com.wallpaperswitcher.engine.legado.RssPaging
            .setCursor(app, source.id, null)
        try {
            // **一页**先出内容（阅读也是这样：首屏只等第一页，往下翻再取下一页）。
            // 之前这里取 2 页：源自己带 `concurrentRate`（比如 1/1000）时限流会把
            // 「首屏」从 ~0.5 秒拖到 1–5 秒，第二页还可能是死链（有的源
            // `ruleNextPage` 拼出来的地址站点回 502），连带把首屏一起拖住。
            // 第二页交给列表底部的「加载更多」（RssSync.loadMore 用游标继续）。
            com.wallpaperswitcher.engine.RssSync.refresh(app, source.id, initialPages = 1)
        } catch (_: Exception) {
        }
    }

    /** Stored article count of a source (0 = brand-new, nothing cached yet). */
    suspend fun rssArticleCount(sourceId: Long): Int =
        withContext(Dispatchers.IO) {
            try {
                db.rssArticleDao().count(sourceId)
            } catch (_: Throwable) {
                0
            }
        }

    /** Stored article count of one category (0 = this category was never loaded). */
    suspend fun rssArticleCountOfSort(sourceId: Long, sort: String): Int =
        withContext(Dispatchers.IO) {
            try {
                db.rssArticleDao().countOfSort(sourceId, sort)
            } catch (_: Throwable) {
                0
            }
        }

    /**
     * 订阅源不做本地缓存：离开源界面时把这个源的文章行（含正文缓存）删掉，
     * 只保留用户加入分组的壁纸文件。放在 scope 里执行，界面已经销毁也能删干净。
     */
    fun clearRssSourceCache(sourceId: Long) {
        scope.launch {
            try {
                db.rssArticleDao().deleteBySource(sourceId)
                AppLog.d(
                    "RssCache",
                    "cleared cached articles of source=$sourceId",
                )
            } catch (_: Throwable) {
            }
        }
    }

    /** 订阅导入图片的自定义下载目录（"" = 应用私有目录）。 */
    suspend fun rssDownloadDirValue(): String =
        com.wallpaperswitcher.engine.RssDownloadDir.load(app)

    fun setRssDownloadDir(treeUri: String) {
        scope.launch {
            try {
                com.wallpaperswitcher.engine.RssDownloadDir.save(app, treeUri)
                onToast(
                    str(
                        if (treeUri.isBlank()) R.string.settings_rss_download_dir_reset_done
                        else R.string.settings_rss_download_dir_saved
                    )
                )
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Refresh when the user opens a source ("点击进去就加载").
     * Returns true when the source was fetched successfully.
     */
    suspend fun rssRefreshOnOpen(sourceId: Long): Boolean {
        return try {
            val report = com.wallpaperswitcher.engine.RssSync.refresh(app, sourceId)
            if (!report.ok) {
                try {
                    onToast(str(R.string.rss_refresh_failed, rssErrorText(report.reason)))
                } catch (_: Exception) {
                }
            }
            report.ok
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** True when the source's article list still has pages left to load. */
    suspend fun rssHasMore(sourceId: Long): Boolean =
        com.wallpaperswitcher.engine.legado.RssPaging.hasMore(app, sourceId)

    /**
     * 阅读-style "load more": append the next page(s) of the article list.
     * Returns true when there are still more pages afterwards.
     */
    suspend fun rssLoadMore(sourceId: Long): Boolean {
        rssFetchJobs.remove(sourceId)?.cancel()
        kotlinx.coroutines.currentCoroutineContext()[Job]
            ?.let { rssFetchJobs[sourceId] = it }
        return try {
            val report = com.wallpaperswitcher.engine.RssSync.loadMore(app, sourceId)
            if (!report.ok) {
                try {
                    onToast(str(R.string.rss_refresh_failed, rssErrorText(report.reason)))
                } catch (_: Exception) {
                }
            }
            report.ok && com.wallpaperswitcher.engine.legado.RssPaging
                .hasMore(app, sourceId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Same as [rssLoadMore] but runs in the controller scope: the footer of the
     * article list triggers paging from a `LaunchedEffect`, and scrolling the
     * footer out of view cancelled the fetch mid-flight ("The coroutine scope
     * left the composition") so pages silently never arrived.
     */
    fun requestRssLoadMore(sourceId: Long, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            val more = rssLoadMore(sourceId)
            onDone(more)
        }
    }

    fun addRssSource(name: String, url: String) {
        guardedWrite("添加订阅源失败") {
            val safeUrl = url.trim()
            if (safeUrl.isEmpty()) return@guardedWrite
            val safeName = name.trim().ifBlank {
                safeUrl.substringAfter("//").substringBefore('/').ifBlank { safeUrl }
            }
            // 手动填的地址已经在列表里时只改名，不再加一行（文章缓存与登录态挂在
            // id 上，不能丢）；类型与原始规则 JSON 也保持不动——这里是"添加"，
            // 用户没有提供新的规则。
            val existing = db.rssSourceDao().getAll()
                .firstOrNull { it.url.trim() == safeUrl }
            if (existing != null) {
                db.rssSourceDao().update(existing.copy(name = safeName))
            } else {
                db.rssSourceDao().insert(
                    com.wallpaperswitcher.data.RssSource(name = safeName, url = safeUrl)
                )
            }
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(app)
            onToast(str(R.string.rss_saved))
        }
    }

    fun deleteRssSource(source: com.wallpaperswitcher.data.RssSource) {
        guardedWrite("删除订阅源失败") {
            db.rssSourceDao().delete(source.id)
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(app)
        }
    }

    fun setRssSourceEnabled(source: com.wallpaperswitcher.data.RssSource, enabled: Boolean) {
        guardedWrite("切换订阅源失败") {
            db.rssSourceDao().update(source.copy(enabled = enabled))
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(app)
        }
    }

    /**
     * 批量删除订阅源。导入到分组里的图片/视频**不删**（它们是用户选中的壁纸），
     * 之后可以照常在分组里使用或手动删除。
     */
    fun deleteRssSources(ids: Set<Long>) {
        if (ids.isEmpty()) return
        guardedWrite("删除订阅源失败") {
            for (id in ids) {
                db.rssSourceDao().delete(id)
            }
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(app)
        }
    }

    /** Refresh one subscription now and report the result as a toast. */
    fun refreshRssSource(source: com.wallpaperswitcher.data.RssSource) {
        scope.launch {
            try {
                val report = com.wallpaperswitcher.engine.RssSync
                    .refresh(app, source.id)
                val message = if (report.ok) {
                    str(R.string.rss_refresh_done, report.added)
                } else {
                    str(R.string.rss_refresh_failed, rssErrorText(report.reason))
                }
                onToast(message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                onToast(
                    str(R.string.rss_refresh_failed, str(R.string.online_error_unknown))
                )
            }
        }
    }

    /** Localized text of a feed failure code (see OnlineSourceRules.decodeResult). */
    private fun rssErrorText(reason: String): String = when (reason) {
        "network" -> str(R.string.online_error_network)
        "timeout" -> str(R.string.online_error_timeout)
        "ssl" -> str(R.string.online_error_ssl)
        "auth" -> str(R.string.online_error_auth)
        "forbidden" -> str(R.string.online_error_forbidden)
        "not_found" -> str(R.string.online_error_not_found)
        "rate_limited" -> str(R.string.online_error_rate_limited)
        "server" -> str(R.string.online_error_server)
        "https_required" -> str(R.string.online_error_https_required)
        "bad_url" -> str(R.string.online_error_bad_url)
        "parse" -> str(R.string.online_error_parse)
        "empty" -> str(R.string.online_error_empty)
        else -> str(R.string.online_error_unknown)
    }

    /**
     * Import 阅读 (Legado) subscription sources: a JSON array / object, a
     * `legado://` share link with inline JSON/base64, or a share link whose
     * `src` is a remote JSON file (downloaded first).
     */
    fun importLegadoSources(text: String) {
        guardedWrite("导入订阅源失败") {
            var result = com.wallpaperswitcher.engine.LegadoImport.parse(text)
            if (result.sources.isEmpty()) {
                // legado:// 分享链接里的 src=<url>，或用户直接粘贴的订阅地址。
                val remote = com.wallpaperswitcher.engine.LegadoImport
                    .remoteUrlToFetch(text)
                if (remote != null) {
                    val body = com.wallpaperswitcher.engine.RssFetcher.fetchText(remote)
                    result = com.wallpaperswitcher.engine.LegadoImport.parse(body)
                }
            }
            if (result.sources.isEmpty()) {
                onToast(str(R.string.rss_import_failed))
                return@guardedWrite
            }
            // 同一个 URL 已经存在就地更新（保住 id → 文章缓存/登录态/列表顺序），
            // 不再每次导入都堆一行新的（见 engine.RssSourceImport）。
            val decision = com.wallpaperswitcher.engine.RssSourceImport
                .decide(db.rssSourceDao().getAll(), result.sources)
            for (source in decision.updated) db.rssSourceDao().update(source)
            for (source in decision.inserted) db.rssSourceDao().insert(source)
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(app)
            onToast(
                str(
                    R.string.rss_import_done,
                    decision.inserted.size,
                    decision.updated.size,
                    result.skipped + decision.ignored,
                )
            )
        }
    }

    fun markRssArticleRead(sourceId: Long, guid: String) {
        guardedWrite("标记已读失败") {
            db.rssArticleDao().markRead(sourceId, guid)
        }
    }

    /** Fetch the article body + images on demand (ruleContent). */
    suspend fun loadRssArticleContent(
        article: com.wallpaperswitcher.data.RssArticle,
        force: Boolean = false,
    ): com.wallpaperswitcher.engine.RssSync.ArticleContent =
        com.wallpaperswitcher.engine.RssSync.fetchContent(app, article, force)

    /** Stream the rest of a script-driven gallery in (see RssSync.loadGalleryImages). */
    suspend fun loadRssGalleryImages(
        article: com.wallpaperswitcher.data.RssArticle,
        pageHtml: String,
        baseHtml: String,
    ): List<String> =
        com.wallpaperswitcher.engine.RssSync
            .loadGalleryImages(app, article, pageHtml, baseHtml)

    /** Download the ticked article images into [groupId] (0 = 在线壁纸 group). */
    /**
     * 下载选中图片/视频到 [groupId]（0 = 自动分组）。
     *
     * **串行版本**：直接挂起跑完（不自己 launch）。ViewModel 用一把互斥锁把多次
     * "添加到分组"排队执行 —— 之前第二次添加会因为在跑而被直接丢弃（用户报的
     * "正在下载时再添加就失败"）。进度回调仍然每完成一项播一次。
     */
    suspend fun importRssImagesToGroup(
        article: com.wallpaperswitcher.data.RssArticle,
        urls: List<String>,
        groupId: Long,
        extraHeaders: Map<String, String> = emptyMap(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val source = try {
            db.rssSourceDao().getById(article.sourceId)
        } catch (_: Exception) {
            null
        }
        val report = com.wallpaperswitcher.engine.RssMediaImporter
            .importImages(app, source, urls, groupId, extraHeaders, onProgress)
        val message = when {
            report.blocked == "wifi" -> str(R.string.rss_blocked_wifi)
            report.blocked == "limit" -> str(R.string.rss_blocked_limit)
            report.failed > 0 ->
                str(R.string.rss_add_to_group_partial, report.added, report.failed)
            else -> str(R.string.rss_add_to_group_done, report.added)
        }
        onToast(message)
    }

    /** The URL the interactive login WebView should open. */
    fun rssLoginEndpoint(source: com.wallpaperswitcher.data.RssSource): String =
        com.wallpaperswitcher.engine.legado.LegadoRss.loginEndpoint(source)

    /** 阅读 `loginCheckJs`：判断当前页面是否已登录的脚本；没有则返回 null。 */
    fun rssLoginCheckJs(source: com.wallpaperswitcher.data.RssSource): String? =
        com.wallpaperswitcher.engine.legado.LegadoRss.loginCheckJs(source)

    /** True when the source logs in with a `@js:` / `<js>` script. */
    fun rssLoginIsScript(source: com.wallpaperswitcher.data.RssSource): Boolean =
        com.wallpaperswitcher.engine.legado.LegadoRss.isJsLogin(rssLoginRaw(source))

    private fun rssLoginRaw(source: com.wallpaperswitcher.data.RssSource): String? =
        try {
            val map = com.wallpaperswitcher.engine.legado.LegadoRss.sourceFields(source.rawJson)
            map?.get("loginUrl") as? String
        } catch (_: Exception) {
            null
        }

    /**
     * 阅读 `loginUi`: the login form definition, `[{"name":"账号","type":"text"}]`.
     * Returns name → input type pairs in declaration order.
     */
    fun rssLoginFields(source: com.wallpaperswitcher.data.RssSource): List<Pair<String, String>> {
        val raw = try {
            val map = com.wallpaperswitcher.engine.legado.LegadoRss.sourceFields(source.rawJson)
            map?.get("loginUi") as? String
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return try {
            (com.wallpaperswitcher.engine.Json.parse(raw) as? List<*>)
                ?.mapNotNull { item ->
                    val field = item as? Map<*, *> ?: return@mapNotNull null
                    val name = field["name"] as? String ?: return@mapNotNull null
                    val type = (field["type"] as? String) ?: "text"
                    if (name.isBlank() || type == "button") null else name to type
                }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Values saved by an earlier login, used to pre-fill the form. */
    fun rssLoginSavedValues(
        source: com.wallpaperswitcher.data.RssSource
    ): Map<String, String> =
        com.wallpaperswitcher.engine.legado.LegadoRss.loginInfoMap(source.id)

    /**
     * Runs the source's JS login (阅读 `source.login()`); the values typed into
     * the `loginUi` form are stored first, exactly like Legado saves them.
     * Returns the error message, or null on success.
     */
    suspend fun rssLoginRunScript(
        source: com.wallpaperswitcher.data.RssSource,
        values: Map<String, String>,
    ): String? = withContext(Dispatchers.IO) {
        com.wallpaperswitcher.engine.legado.LegadoRss.saveLoginInfo(source.id, values)
        val script = com.wallpaperswitcher.engine.legado.LegadoRss
            .loginScript(rssLoginRaw(source) ?: "")
            ?: return@withContext "unsupported"
        val result = com.wallpaperswitcher.engine.legado.LegadoRss.runLoginScript(source, script)
        if (result.error == null) rssLoginCompleted(source)
        result.error
    }

    /**
     * Saves the subscription-source editor: the row fields (name/url/type/
     * enabled) plus the edited 阅读 fields merged into the original JSON.
     * [rawOverride] replaces the whole JSON when the user edited it directly.
     * Returns a localized error message, or null on success.
     */
    suspend fun rssSourceSave(
        source: com.wallpaperswitcher.data.RssSource,
        name: String,
        url: String,
        type: Int,
        enabled: Boolean,
        changes: Map<String, String?>,
        rawOverride: String?,
    ): String? = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return@withContext str(R.string.rss_edit_url_invalid)
        }
        val raw = if (!rawOverride.isNullOrBlank()) {
            try {
                com.wallpaperswitcher.engine.Json.parse(rawOverride)
                rawOverride
            } catch (_: Exception) {
                return@withContext str(R.string.rss_edit_raw_invalid)
            }
        } else {
            val withText = com.wallpaperswitcher.engine.legado.RssSourceEditor
                .applyChanges(source.rawJson, changes)
            // The JSON keeps its own copies of the name/URL (阅读's sourceName /
            // sourceUrl). The URL always follows the form; the name is only
            // written when it was actually changed, so saving an unrelated edit
            // cannot overwrite a nicer title the import carried (old sources
            // were imported before `sourceName` was read).
            val displayName = name.trim().ifBlank { trimmedUrl }
            val identity = HashMap<String, String?>()
            identity["sourceUrl"] = trimmedUrl
            if (displayName != source.name) identity["sourceName"] = displayName
            val withIdentity = com.wallpaperswitcher.engine.legado.RssSourceEditor.applyChanges(
                withText,
                identity,
            )
            com.wallpaperswitcher.engine.legado.RssSourceEditor.applyTypedChanges(
                withIdentity,
                mapOf("type" to type.toLong(), "enabled" to enabled),
            )
        }
        val updated = source.copy(
            name = name.trim().ifBlank { trimmedUrl },
            url = trimmedUrl,
            type = type,
            enabled = enabled,
            rawJson = raw,
        )
        try {
            db.rssSourceDao().update(updated)
        } catch (_: Throwable) {
            return@withContext str(R.string.rss_edit_save_failed)
        }
        // Rules/URL may have changed: start paging from the top again.
        com.wallpaperswitcher.engine.legado.RssPaging.setCursor(app, source.id, null)
        onToast(str(R.string.rss_edit_saved))
        null
    }

    /** Called after the user finished logging in: refresh this source. */
    fun rssLoginCompleted(source: com.wallpaperswitcher.data.RssSource) {
        guardedWrite("登录后刷新失败") {
            onToast(str(R.string.rss_login_done))
        }
        scope.launch {
            try {
                com.wallpaperswitcher.engine.RssSync.refresh(app, source.id)
            } catch (_: Exception) {
            }
        }
    }
}
