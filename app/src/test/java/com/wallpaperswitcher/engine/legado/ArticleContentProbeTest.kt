package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.FeedParser
import com.wallpaperswitcher.engine.Json
import com.wallpaperswitcher.engine.RssHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 联网诊断探针（**不 assert 内容**，只打印证据）：用「秀人集（8012359）」这条
 * 真实源走一遍**正文链路**。它的 `ruleContent` 是一段用 `src` 取正文页图片的
 * `@js:`，正好覆盖「点开文章和阅读显示不一样」这一类规则。
 */
class ArticleContentProbeTest {

    @Test
    fun probeArticleContent() {
        assumeTrue("needs network", networkReachable())
        val report = StringBuilder()
        val source = source()
        val rules = LegadoRss.parseRules(source)
        report.append("parseRules=${rules != null} rawLen=${source.rawJson.length}\n")
        if (rules == null) {
            report.append("raw=${source.rawJson.take(240)}\n")
            println(report)
            return
        }
        val category = LegadoRss.sortUrls(source, rules).first()
        val listUrl = LegadoRss.pageUrl(rules, category, 1, source.id)
        report.append("listUrl=$listUrl\n")
        val listBody = try {
            httpGet(listUrl, rules.header)
        } catch (t: Throwable) {
            report.append("LIST FETCH FAILED: ${t.javaClass.simpleName}: ${t.message?.take(120)}\n")
            println(report)
            return
        }
        val engine = LegadoRuleEngine(baseUrl = listUrl, variables = rules.variables + mapOf("page" to "1"), sourceId = source.id)
        val items = engine.getElements(rules.ruleArticles, listBody)
        report.append("items=${items.size}\n")
        if (items.isEmpty()) {
            println(report)
            return
        }
        val itemEngine = LegadoRuleEngine(baseUrl = listUrl, variables = rules.variables + mapOf("page" to "1"), sourceId = source.id)
        val link = RuleAlternatives.split(rules.ruleLink!!)
            .firstNotNullOfOrNull { rule ->
                itemEngine.getString(rule, items.first(), true)?.takeIf { it.isNotBlank() }
            }
            .orEmpty()
        report.append("firstLink=$link\n")
        if (link.isBlank()) {
            println(report)
            return
        }

        val base = runBlocking { LegadoRss.fetchArticleBase(source, rules, link) }
        report.append("\n=== fetchArticleBase ===\n")
        if (base == null) {
            report.append("null（正文规则没产出内容）\n")
        } else {
            report.append("articlePage=${base.pageHtml.length} bytes\n")
            report.append("ruleOutput=${base.html.length} chars\n")
            report.append("img tags=${Regex("<img", RegexOption.IGNORE_CASE).findAll(base.html).count()}\n")
            report.append("image urls=${FeedParser.allImageUrls(base.html).size}\n")
            report.append("head: ${base.html.take(240).replace("\n", " ")}\n")
        }
        // 单独看每条备选各自的结果，定位是哪一条在起作用、有没有报错。
        val page = try {
            httpGet(link, rules.header)
        } catch (t: Throwable) {
            report.append("ARTICLE FETCH FAILED: ${t.javaClass.simpleName}\n")
            println(report)
            return
        }
        report.append("\narticlePage=${page.length} bytes\n")
        val contentEngine = LegadoRuleEngine(baseUrl = link, variables = rules.variables + mapOf("page" to "1"), sourceId = source.id)
        for (alternative in RuleAlternatives.split(rules.ruleContent!!)) {
            val value = try {
                contentEngine.getString(alternative, page, joinAll = true)
            } catch (t: Throwable) {
                "THREW ${t.javaClass.simpleName}: ${t.message?.take(80)}"
            }
            report.append(
                "--- alt[${alternative.take(30)}…] -> ${value.length} chars, " +
                    "imgs=${Regex("<img", RegexOption.IGNORE_CASE).findAll(value).count()}\n"
            )
        }
        for (error in contentEngine.takeErrors()) {
            report.append("    RULE ERROR: ${error.reason} | ${error.rule.take(70)}\n")
        }
        // 图集翻页用的 java.ajax：单独试一次，把失败原因打出来
        val second = link.replace(Regex("""\.html$"""), "_2.html")
        report.append("\n=== java.ajax($second) ===\n")
        val fetched = try {
            LegadoRss.fetchTextSync(second).length
        } catch (t: Throwable) {
            val reason = (t as? com.wallpaperswitcher.engine.FetchException)
                ?.let { "reason=${it.reason} status=${it.httpStatus}" }
                ?: "${t.javaClass.simpleName}: ${t.message?.take(90)}"
            report.append("FAILED: $reason\n")
            -1
        }
        if (fetched >= 0) {
            report.append("ok, $fetched chars\n")
        }

        // 规则里的图集翻页：`java.ajax` 在 JS 规则里到底能不能拿到页面，
        // byIndex 拼出的地址是否真的存在 —— 这是「只显示 5 张图」的关键一步。
        report.append("\n=== gallery walk via JS rule ===\n")
        val walkJs = """
            @js:(function(){
              var page = String(src ? baseUrl : baseUrl);
              var out = [];
              function byIndex(u, k) {
                var m = String(u).match(/^(.*?)(?:_([0-9]+))?\.html$/);
                if (!m) { return ''; }
                var b = m[1];
                var c = (m[2] !== undefined) ? (parseInt(m[2], 10) + 1) : (k - 1);
                return b + '_' + c + '.html';
              }
              var cur = page, n = 1;
              while (n < 6) {
                var nx = byIndex(cur, n + 1);
                if (!nx) { out.push('byIndex-empty@' + n); break; }
                var body = String(java.ajax(nx) || '');
                out.push(nx.split('/').pop() + '=' + body.length);
                if (!body) { break; }
                cur = nx; n++;
              }
              return out.join(' | ');
            })()
        """.trimIndent()
        val walkEngine = LegadoRuleEngine(baseUrl = link, variables = emptyMap(), sourceId = 38L)
        val walk = try {
            walkEngine.getString(walkJs, page)
        } catch (t: Throwable) {
            "THREW ${t.javaClass.simpleName}: ${t.message?.take(80)}"
        }
        report.append("$walk\n")

        // 同一条 walk，但带上源自己的 concurrentRate（秀人集 = 1/1000）与
        // 引擎的 JS 超时 —— 「只显示 5 张图」很可能就是被限流拖到 JS 超时。
        com.wallpaperswitcher.engine.legado.ConcurrentRate.register(38L, "1/1000")
        val started = System.currentTimeMillis()
        val walk2 = try {
            LegadoRuleEngine(baseUrl = link, variables = emptyMap(), sourceId = 38L)
                .getString(walkJs.replace("n < 6", "n < 30"), page)
        } catch (t: Throwable) {
            "THREW ${t.javaClass.simpleName}: ${t.message?.take(80)}"
        }
        report.append("with concurrentRate 1/1000 (+30 pages), ${System.currentTimeMillis() - started}ms\n")
        report.append("$walk2\n")
        println(report)
    }

    /**
     * 用字段表 + [Json.encode] 生成 rawJson：手写 JSON 里的 `\s` / `\/` / 引号
     * 极易写坏（本探针第一版就是被这个坑到，规则根本没解析出来）。
     */
    private fun source(): RssSource {
        val fields = linkedMapOf<String, Any?>(
            "sourceName" to "秀人集（8012359）",
            "sourceUrl" to "http://3w.8012359.xyz/new.html",
            "sortUrl" to "最新::http://3w.8012359.xyz/new.html",
            "ruleArticles" to "@css:li.i_list",
            "ruleTitle" to TITLE_JS,
            "ruleLink" to "@css:a@href",
            "ruleImage" to "@css:img@src",
            "rulePubDate" to "@css:.meta-post@text##[\\s\\S]*?(\\d{4}\\.\\d{2}\\.\\d{2})[\\s\\S]*##\$1",
            "ruleContent" to CONTENT_JS,
        )
        return RssSource(
            id = 38L,
            name = "秀人集",
            url = "http://3w.8012359.xyz/new.html",
            rawJson = Json.encode(fields),
        )
    }

    /** 与源同形：标题规则在 JS 里调用 `java.getString('@css:…')`。 */
    private val TITLE_JS = """
        @js:(function(){
          var P = '@' + 'css:';
          var a = '', b = '';
          try { a = String(java.getString(P + '.meta-title@text') || '').trim(); } catch (e) {}
          try { b = String(java.getString(P + '.postlist-imagenum@text') || '').trim(); } catch (e) {}
          var t = a + (b ? (' ' + b) : '');
          if (!t) { try { t = String(java.getString(P + 'a@title') || '').trim(); } catch (e) {} }
          return t;
        })()
    """.trimIndent()

    /** 与源同形：正文规则用 `src`（当前内容）扫出图片地址并输出 img 标签。 */
    private val CONTENT_JS = """
        @js:(function(){
          var h = String(src || '');
          var host = 'http://3w.8012359.xyz';
          var hm = String(baseUrl || '').match(/^(https?:\/\/[^\/]+)/);
          if (hm) { host = hm[1]; }
          var out = [], seen = {}, m;
          var IMG = /uploadfile\/[0-9]{6}\/[A-Za-z0-9_\/.\-]+\.(?:jpg|jpeg|png|webp)/ig;
          function grab(x) {
            IMG.lastIndex = 0;
            while ((m = IMG.exec(x))) { if (!seen[m[0]]) { seen[m[0]] = 1; out.push(host + '/' + m[0]); } }
          }
          grab(h);
          var q = String.fromCharCode(39);
          var o = [];
          if (!out.length) { return '<div>NO-SRC-OR-NO-IMG</div>'; }
          for (var i = 0; i < out.length; i++) { o.push('<img src=' + q + out[i] + q + ' />'); }
          return '<div>COUNT=' + out.length + '</div>' + o.join('');
        })()
    """.trimIndent()

    private fun httpGet(url: String, header: String?): String {
        val request: Request = LegadoRss.buildRequest(url, header, null, 38L)
        RssHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val peek = response.peekBody(4L * 1024 * 1024)
            return ResponseCharset.decode(peek.bytes(), peek.contentType())
        }
    }

    private fun networkReachable(): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("www.baidu.com", 443), 5_000) }
        true
    } catch (_: Throwable) {
        false
    }
}
