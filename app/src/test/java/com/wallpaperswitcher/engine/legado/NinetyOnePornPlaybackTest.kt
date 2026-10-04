package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.Json
import com.wallpaperswitcher.ui.screens.extractVideoUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 91porn：**列表链接与正文视频必须一一对应**。
 *
 * 用户报「91porn 源视频与文章不符」—— 播放出来的片子跟点开的那篇不是同一个。
 * 这个源的两个关键规则：
 *
 *  - `ruleLink`: `a.0@href`（取块内第一个 `<a>` 的 href，带 `viewkey=`）；
 *  - `ruleContent`: 一段 `@js:`，从文章页里 `document.write(strencode2("…"))`
 *    那段解出真正的 mp4 地址。
 *
 * 只要链接按条目各自取、正文按各自的页面解，就不会串片。这里把这条链路钉住：
 * 三个条目 → 三条不同的链接 → 三份不同正文 → 三条不同的 mp4。
 */
class NinetyOnePornPlaybackTest {

    /** 原源里的 `ruleContent`（`@js:`，与仓库 rssSource_91porn视频.json 一致）。 */
    private val ruleContent = "@js:(function(){var html=String(result);" +
        "var m=html.match(/document\\.write\\(strencode2\\(\\s*[\"']([^\"']+)[\"']\\s*\\)\\)/);" +
        "if(m){try{var dec=decodeURIComponent(m[1]);" +
        "var s=dec.match(/src=['\"]([^'\"]+)['\"]/);if(s&&s[1])return s[1];}catch(e){}}" +
        "var m2=html.match(/<source[^>]+src=[\"']([^\"']+)[\"']/);if(m2)return m2[1];" +
        "var m3=html.match(/<video[^>]+src=[\"']([^\"']+)[\"']/);if(m3)return m3[1];" +
        "return '';})()"

    private val source = RssSource(
        id = 33L,
        name = "91porn视频",
        url = "https://w1226.9p58b.com/index.php",
        rawJson = """{"sourceUrl":"https://w1226.9p58b.com/index.php","type":2,""" +
            """"ruleArticles":"div.well.well-sm","ruleTitle":"span.video-title@text",""" +
            """"ruleLink":"a.0@href","ruleImage":"img.img-responsive@src",""" +
            """"ruleContent":${Json.encode(ruleContent)}}""",
    )

    private fun listHtml(keys: List<String>) = keys.joinToString("\n") { key ->
        """
        <div class="well well-sm">
          <a href="/view_video.php?viewkey=$key"><img class="img-responsive" src="/thumb/$key.jpg"></a>
          <span class="video-title">标题 $key</span>
        </div>
        """.trimIndent()
    }

    /** 文章页：`strencode2` 里是 URL 编码后的 `<video src=…>` 片段。 */
    private fun articleHtml(file: String) = """
        <html><body>
        <script>
        document.write(strencode2("%3Cvideo%20src%3D%22https%3A%2F%2Fcdn.example.com%2F$file%22%3E"));
        </script>
        </body></html>
    """.trimIndent()

    /** 列表里每个条目的链接各自带自己的 viewkey（不会都指向第一条）。 */
    @Test
    fun eachListItemKeepsItsOwnLink() {
        val keys = listOf("aaa111", "bbb222", "ccc333")
        val rules = LegadoRss.parseRules(source)!!
        val engine = LegadoRuleEngine(baseUrl = "https://w1226.9p58b.com/index.php")
        val items = engine.getElements(rules.ruleArticles, listHtml(keys))
        assertEquals(3, items.size)
        val links = items.map { engine.getString(rules.ruleLink!!, it, isUrl = true) }
        assertEquals(
            listOf(
                "https://w1226.9p58b.com/view_video.php?viewkey=aaa111",
                "https://w1226.9p58b.com/view_video.php?viewkey=bbb222",
                "https://w1226.9p58b.com/view_video.php?viewkey=ccc333",
            ),
            links,
        )
    }

    /** 每篇文章各自解出自己的 mp4（同样的规则、不同的页面 → 不同的片子）。 */
    @Test
    fun eachArticleResolvesItsOwnVideo() {
        val rules = LegadoRss.parseRules(source)!!
        val cases = mapOf(
            "aaa111" to "aaa111.mp4",
            "bbb222" to "bbb222.mp4",
            "ccc333" to "ccc333.mp4",
        )
        for ((key, file) in cases) {
            val base = "https://w1226.9p58b.com/view_video.php?viewkey=$key"
            val content = LegadoRuleEngine(baseUrl = base)
                .getString(rules.ruleContent!!, articleHtml(file), joinAll = true)
            assertEquals("https://cdn.example.com/$file", content)
            assertEquals("https://cdn.example.com/$file", extractVideoUrl(content, base))
        }
        // 三条互不相同 —— 串片会在这里露出来。
        val urls = cases.values.map { extractVideoUrl("https://cdn.example.com/$it", null) }
        assertEquals(urls.size, urls.distinct().size)
    }

    /** 解不出来时不要瞎猜：返回空串（调用方会退回浏览器 / 嗅探）。 */
    @Test
    fun unparsableArticleYieldsNothing() {
        val rules = LegadoRss.parseRules(source)!!
        val base = "https://w1226.9p58b.com/view_video.php?viewkey=x"
        assertEquals("", LegadoRuleEngine(baseUrl = base).getString(rules.ruleContent!!, "<html></html>"))
        assertTrue(extractVideoUrl("", base) == null)
    }

    /**
     * 「XH发布页」这类源的形态：没有静态规则，`jsLib` + `header` 里的 `getJs()`，
     * 规则得靠运行时从远程脚本解出来 —— 我们跑不了。这种源点开就应该**当网页打开**，
     * 而不是进文章列表再报「不支持的 JS 源」（真机日志里的
     * `RSS refresh failed: source=29 reason=unsupported_js`）。
     */
    @Test
    fun jsOnlySourceIsTreatedAsAWebPage() {
        val xh = RssSource(
            id = 29L,
            name = "XH发布页",
            url = "https://qyyuapi.com",
            rawJson = """
                {"sourceName":"XH发布页","sourceUrl":"https://qyyuapi.com",
                 "jsLib":"https://qyyuapi.com/lib.js",
                 "header":"<js>getJs()</js>","enabled":true}
            """.trimIndent(),
        )
        assertTrue("JS 源应该按网页型源处理", LegadoRss.isBrowseOnly(xh))
        assertTrue("确认它确实需要 JS 运行时", LegadoRss.requiresJsRuntime(xh))

        // 有静态规则的源不受影响（照常抓列表）。
        assertFalse(LegadoRss.isBrowseOnly(source))
        // 普通网页型源（singleUrl，无规则）同样是网页型。
        assertTrue(
            LegadoRss.isBrowseOnly(
                RssSource(
                    id = 46L,
                    name = "Pixiv 书源",
                    url = "https://pixivsource.pages.dev",
                    rawJson = """{"sourceName":"Pixiv 书源","sourceUrl":"https://pixivsource.pages.dev","singleUrl":true}""",
                ),
            ),
        )
    }
}
