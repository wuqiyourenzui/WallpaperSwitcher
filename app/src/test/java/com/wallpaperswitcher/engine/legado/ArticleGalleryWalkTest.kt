package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.Json
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 图集规则里的翻页（`java.ajax` + `_N.html`）必须**把所有图片都取回来**。
 *
 * 这是用户在真机上看到的「文章只显示 5 张图，阅读能显示全部」那类问题的回归测试：
 * 规则自己用 `java.ajax` 往后走 `_1.html`、`_2.html`…，站点对「超出图集末尾」的
 * 页回 502/空 —— 走到头就停，但**不能**因为中途出现 5xx 就把整段规则掐掉
 * （那正是「只显示第一页的 5 张」的成因）。
 */
class ArticleGalleryWalkTest {

    /** 图集共 [galleryPages] 页，每页 [PER_PAGE] 张图。 */
    private val galleryPages = 9
    private val perPage = 5

    @Before
    fun setUp() {
        AjaxCircuit.reset()
    }

    @Test
    fun `the rule collects every image of the gallery`() {
        withServer { port ->
            val article = "http://127.0.0.1:$port/g/100.html"
            val imageCount = walkArticle(article)
            // 第 1 页 + 9 个 `_N.html` 兄弟页，每页 5 张。
            assertEquals((galleryPages + 1) * perPage, imageCount)
        }
    }

    @Test
    fun `an http error on a sibling page does not abort the walk`() {
        withServer(errorFromPage = 7) { port ->
            val article = "http://127.0.0.1:$port/g/100.html"
            val imageCount = walkArticle(article)
            // 第 7 页起站点回 502：前 6 页（含第 1 页）必须都拿到，
            // 而且不能因为 502 触发熔断把后面的正常页也掐掉。
            assertTrue("expected images from pages 1..6, got $imageCount", imageCount >= 7 * perPage)
        }
    }

    private fun walkArticle(articleUrl: String): Int {
        val source = RssSource(id = 77L, name = "gallery", url = articleUrl, rawJson = rules(articleUrl))
        val rules = LegadoRss.parseRules(source) ?: error("rules")
        val articleHtml = html(0)
        val engine = LegadoRuleEngine(baseUrl = articleUrl, variables = rules.variables, sourceId = source.id)
        val out = engine.getString(rules.ruleContent!!, articleHtml)
        return Regex("<img", RegexOption.IGNORE_CASE).findAll(out).count()
    }

    /** 与真实源同形：用 `src` 取正文页，再用 `java.ajax` 往后走 `_N.html`。 */
    private fun rules(articleUrl: String): String {
        val js = """
            @js:(function(){
              var h = String(src || '');
              var base = String(baseUrl || '').replace(/\.html${'$'}/, '');
              var seen = {}, out = [];
              function grab(x) {
                var re = /IMG\/(\d+\.jpg)/g, m;
                while ((m = re.exec(String(x)))) { if (!seen[m[1]]) { seen[m[1]] = 1; out.push(m[1]); } }
              }
              grab(h);
              var n = 1;
              while (n < 30) {
                var nx = base + '_' + n + '.html';
                var body = String(java.ajax(nx) || '');
                if (!body) { break; }
                grab(body);
                n++;
              }
              var o = [];
              for (var i = 0; i < out.length; i++) { o.push('<img src="' + out[i] + '" />'); }
              return o.join('');
            })()
        """.trimIndent()
        val fields = linkedMapOf<String, Any?>(
            "sourceName" to "gallery",
            "sourceUrl" to articleUrl,
            "ruleArticles" to "class.item",
            "ruleContent" to js,
        )
        return Json.encode(fields)
    }

    /** 第 [page] 页的 HTML（0 = 正文页）。 */
    private fun html(page: Int): String {
        val start = page * perPage
        val imgs = (start until start + perPage).joinToString("") { "<img src=\"IMG/$it.jpg\">" }
        return "<html><body><div class=\"content\">$imgs</div></body></html>"
    }

    private fun withServer(errorFromPage: Int = Int.MAX_VALUE, block: (Int) -> Unit) {
        assumeTrue(
            "cleartext loopback must be allowed to exercise the fetch path",
            com.wallpaperswitcher.engine.OnlineSourceRules
                .endpointPolicy("http://127.0.0.1:1/g/1.html", allowCleartext = true) ==
                com.wallpaperswitcher.engine.OnlineSourceRules.EndpointPolicy.OK,
        )
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "gallery-test-server") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    return@thread
                }
                try {
                    serve(socket, errorFromPage)
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

    private fun serve(socket: Socket, errorFromPage: Int) {
        socket.soTimeout = 5_000
        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val requestLine = input.readLine() ?: return
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrNull(1).orEmpty()
        // `100_1.html` → 1；正文页 `100.html` → 0。
        val page = Regex("""_(\d+)\.html""").find(target)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val out = socket.getOutputStream()
        // 兄弟页编号 1..galleryPages 存在，之后站点回 502（真实行为）。
        if (page > galleryPages) {
            // 超出图集末尾的兄弟页：站点就回 502（真实行为）。
            val body = "bad gateway".toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            out.write(body)
        } else if (page >= errorFromPage) {
            val body = "server error".toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            out.write(body)
        } else {
            val body = html(page).toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            out.write(body)
        }
        out.flush()
    }
}
