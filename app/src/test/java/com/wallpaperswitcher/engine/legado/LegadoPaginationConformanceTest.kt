package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.OnlineSourceRules
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 阅读 (legado-E) 订阅列表翻页 / 逐项字段的对照测试。
 *
 * 对齐的四件事（参考 legado-E `model/rss/RssParserByRule.kt`）：
 *  1. `ruleNextPage == "PAGE"` 是「地址不变、页码递增」的哨兵值，不是新地址；
 *  2. 翻页游标必须同时记住「下一页地址」与「它代表第几页」，否则恢复之后的
 *     `{{page}}` 会退回第 1 页；
 *  3. 逐项过滤是「标题为空即丢弃」（`if (rssArticle.title.isBlank()) return null`）；
 *  4. 一次 [LegadoRss.fetchPages] 只返回**本次**抓到的条目（阅读也是每次调用
 *     返回一页，由调用方累积）。
 *
 * 抓取用例起一个本地 HTTP 服务（纯 `java.net.ServerSocket`：Android 单测跑在
 * mockable jar 上，`com.sun.net.httpserver` 不在类路径里），把整条链路
 * （pageUrl → 请求 → 规则 → 游标）跑通；游标解析用例不依赖网络，始终运行。
 */
class LegadoPaginationConformanceTest {

    private val listUrl = "https://example.test/phone/list"

    /** 页面内容：页码 → 该页条目（标题 → 链接序号；null 表示该条没有标题元素）。 */
    private val pages: Map<Int, List<Pair<String, String?>>> = mapOf(
        1 to listOf("第一条" to "1", "第二条" to "2"),
        2 to listOf("第三条" to "3", "第四条" to "4"),
    )

    /**
     * [sourceUrl] 同时是列表地址与相对链接的解析基准：`PAGE` 源的地址里带
     * `{{page}}` 占位符，链接规则源则是固定地址。
     */
    private fun rules(
        sourceUrl: String = listUrl,
        nextPage: String? = "PAGE",
    ) = LegadoRss.Rules(
        sourceUrl = sourceUrl,
        sortUrl = null,
        ruleArticles = "ul.list li",
        ruleNextPage = nextPage,
        ruleTitle = "h2 a@text",
        ruleLink = "h2 a@href",
        ruleImage = null,
        ruleDescription = null,
        ruleContent = null,
        rulePubDate = null,
        header = null,
        loginUrl = null,
        loginUi = null,
        loginCheckJs = null,
        jsLib = null,
        concurrentRate = null,
        style = null,
        injectJs = null,
        contentWhitelist = null,
        contentBlacklist = null,
        shouldOverrideUrlLoading = null,
        loadWithBaseUrl = true,
        enabledCookieJar = true,
        variables = emptyMap(),
        singleUrl = false,
        type = 0,
    )

    private fun source(url: String) = RssSource(id = 1L, name = "list", url = url, type = 0)

    // ---------------------------------------------------------------- 游标

    @Test
    fun `index cursor carries the page the url template renders`() {
        assertEquals(2, LegadoRss.parseCursor("page:2").page)
        assertNull(LegadoRss.parseCursor("page:2").url)
        // 大小写不敏感；非法页码按「未知」处理。
        assertEquals(3, LegadoRss.parseCursor("PAGE:3").page)
        assertNull(LegadoRss.parseCursor("page:abc").page)
        assertNull(LegadoRss.parseCursor(null).page)
        assertNull(LegadoRss.parseCursor("  ").url)
        // 0 / 负数会被规范到第 1 页，而不是产生一个不可用的页码。
        assertEquals(1, LegadoRss.parseCursor("page:0").page)
        assertEquals(1, LegadoRss.parseCursor("page:-3").page)
    }

    @Test
    fun `link cursor keeps its page so page variables survive a resume`() {
        val ref = LegadoRss.parseCursor(LegadoRss.urlCursor("$listUrl/index_2.html", 2))
        assertEquals("$listUrl/index_2.html", ref.url)
        assertEquals(2, ref.page)
        // 老版本只存了地址：仍然可用，页码按「未知」处理。
        val legacy = LegadoRss.parseCursor("$listUrl/index_2.html")
        assertEquals("$listUrl/index_2.html", legacy.url)
        assertNull(legacy.page)
        // 地址里出现 `|page:` 不是分隔符，只有最后一个才是。
        assertEquals(
            "$listUrl/x|1.html",
            LegadoRss.parseCursor(LegadoRss.urlCursor("$listUrl/x|1.html", 4)).url,
        )
    }

    @Test
    fun `link cursor round-trips without doubling the page prefix`() {
        // 真实源的上一页链接 + 页码 → 游标 → 再解析回来，必须原样还原。
        // （线上曾出现 `…index3.html|page:page:3`：每存一次多一层 `page:`。）
        val url = "$listUrl/index3.html"
        val once = LegadoRss.urlCursor(url, 3)
        assertEquals("$url|page:3", once)
        val ref = LegadoRss.parseCursor(once)
        assertEquals(url, ref.url)
        assertEquals(3, ref.page)

        // 再走一轮（把解析出来的地址当成下一页地址存回去）也必须稳定，不能累积。
        val twice = LegadoRss.urlCursor(ref.url!!, ref.page!!)
        assertEquals(once, twice)
    }

    @Test
    fun `page url renders the page counter from the source template`() {
        val rules = rules(sourceUrl = "$listUrl?page={{page}}")
        assertEquals("$listUrl?page=1", LegadoRss.pageUrl(rules, null, 1, 1L))
        assertEquals("$listUrl?page=2", LegadoRss.pageUrl(rules, null, 2, 1L))
    }

    // ------------------------------------------------------------ 抓取链路

    @Test
    fun `PAGE rule repeats the address with a growing page counter`() {
        withServer { port ->
            val sourceUrl = "http://127.0.0.1:$port/list?page={{page}}"
            val rules = rules(sourceUrl = sourceUrl)
            val src = source(sourceUrl)
            runBlocking {
                // 第一页：游标指向第 2 页（地址模板不变，只有页码前进）。
                val first = LegadoRss.fetchPages(src, rules, maxPages = 1)
                assertEquals(2, first.articles.size)
                assertEquals("http://127.0.0.1:$port/list?page=1&item=1", first.articles.first().link)
                assertEquals("page:2", first.nextCursor)

                // 「加载更多」：同一模板 + 第 2 页；只返回本次抓到的条目。
                val second = LegadoRss.fetchPages(src, rules, startCursor = first.nextCursor)
                assertEquals(2, second.articles.size)
                assertEquals("http://127.0.0.1:$port/list?page=2&item=3", second.articles.first().link)
                assertEquals("第三条", second.articles.first().title)
            }
        }
    }

    @Test
    fun `link rule walks to the extracted address and stops at the end`() {
        withServer { port ->
            val sourceUrl = "http://127.0.0.1:$port/list"
            // `a.next@href` 给出下一页地址：第 1 页有、第 2 页没有（列表结束）。
            val rules = rules(sourceUrl = sourceUrl, nextPage = "div.pager a.next@href")
            val src = source(sourceUrl)
            runBlocking {
                val first = LegadoRss.fetchPages(src, rules, maxPages = 1)
                assertEquals(2, first.articles.size)
                // 游标 = 下一页地址 + 它代表第 2 页。
                assertEquals(
                    "http://127.0.0.1:$port/list?page=2",
                    LegadoRss.parseCursor(first.nextCursor).url,
                )
                assertEquals(2, LegadoRss.parseCursor(first.nextCursor).page)

                val second = LegadoRss.fetchPages(src, rules, startCursor = first.nextCursor)
                assertEquals(2, second.articles.size)
                assertEquals("第三条", second.articles.first().title)
                // 最后一页没有下一页链接 → 游标为空 = 列表到此为止。
                assertNull(second.nextCursor)
            }
        }
    }

    @Test
    fun `a dead next page keeps the page that was already read`() {
        withServer { port ->
            val sourceUrl = "http://127.0.0.1:$port/list"
            // 下一页规则指向一个站点不存在的地址（「秀人集」的 ruleNextPage 会
            // 拼出 new_1.html，站点回 502）。第一页读到了就必须留住它，
            // 不能因为翻页失败把整次刷新判成失败、列表空着转圈。
            val rules = rules(sourceUrl = sourceUrl, nextPage = "div.pager a.next@href")
            deadNextPage = true
            runBlocking {
                val result = LegadoRss.fetchPages(source(sourceUrl), rules, maxPages = 2)
                assertEquals(2, result.articles.size)
                assertNull(result.nextCursor)
            }
        }
    }

    @Test
    fun `an item without a title is dropped`() {
        withServer { port ->
            val sourceUrl = "http://127.0.0.1:$port/list"
            val rules = rules(sourceUrl = sourceUrl, nextPage = null)
            // 第 1 页多一条没有 h2 的条目：阅读取到空标题就丢弃它。
            extraListItems = listOf("无标题条目" to null)
            runBlocking {
                val result = LegadoRss.fetchPages(source(sourceUrl), rules, maxPages = 1)
                assertEquals(2, result.articles.size)
                assertEquals(listOf("第一条", "第二条"), result.articles.map { it.title })
            }
        }
    }

    // ------------------------------------------------------------- 测试服务

    /** 追加到第 1 页末尾的额外条目（「无标题」用例用）。 */
    @Volatile
    private var extraListItems: List<Pair<String, String?>> = emptyList()

    /** 让下一页地址返回 502（「下一页是死链」用例用）。 */
    @Volatile
    private var deadNextPage = false

    private fun html(number: Int, items: List<Pair<String, String?>>): String {
        val rows = items.joinToString("\n") { (title, file) ->
            val anchor = if (file == null) {
                "<span>无标题</span>"
            } else {
                "<h2><a href=\"/list?page=$number&amp;item=$file\">$title</a></h2>"
            }
            "<li class=\"item\">$anchor</li>"
        }
        // 只有「还有下一页」时才给下一页链接。
        val next = if (pages.containsKey(number + 1)) {
            "<div class=\"pager\"><a class=\"next\" href=\"/list?page=${number + 1}\">下一页</a></div>"
        } else {
            ""
        }
        return "<html><body><ul class=\"list\">$rows</ul>$next</body></html>"
    }

    /**
     * 起一个本地 HTTP 服务并跑 [block]。用例需要真实明文 HTTP（`endpointPolicy`
     * 对环回地址是放行的，见 OnlineSourceRules），否则跳过而不是假装通过。
     */
    private fun withServer(block: (Int) -> Unit) {
        assumeTrue(
            "cleartext loopback must be allowed to exercise the fetch path",
            OnlineSourceRules.endpointPolicy("http://127.0.0.1:1/list", allowCleartext = true) ==
                OnlineSourceRules.EndpointPolicy.OK,
        )
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "rss-test-server") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    return@thread
                }
                try {
                    serve(socket)
                } catch (_: Throwable) {
                } finally {
                    try {
                        socket.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        try {
            block(server.localPort)
        } finally {
            try {
                server.close()
            } catch (_: Throwable) {
            }
            worker.join(2_000)
        }
    }

    /** 一次请求 / 一个响应：只认 `GET /list?page=N`，其余按第 1 页。 */
    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val requestLine = input.readLine() ?: return
        // 读掉请求头，避免客户端写请求体时被 RST。
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrNull(1).orEmpty()
        val query = target.substringAfter('?', "")
        val number = query.substringAfter("page=", "1").substringBefore('&').toIntOrNull() ?: 1
        if (deadNextPage && number > 1) {
            val body = "bad gateway".toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 502 Bad Gateway\r\n")
                append("Content-Type: text/plain\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(head)
                write(body)
                flush()
            }
            return
        }
        val base = pages[number] ?: emptyList()
        val items = if (number == 1) base + extraListItems else base
        val body = html(number, items).toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(head)
            write(body)
            flush()
        }
    }
}
