package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户导入的两个「视频」源（type = 2）的列表规则回归。
 *
 * 91porn视频：`div.well.well-sm` + `a.0@href`（取块内第一个 a）
 * Rule34视频：`div.item.thumb` + `a.th@href` + `img.thumb@data-original`
 *
 * 这两个写法如果我们的引擎解析得和阅读不一致（取不到链接/标题），列表就会比
 * 阅读少条目 —— 先在这里把字段级行为钉住。
 */
class VideoSourceRulesTest {

    private val ninetyOneHtml = """
        <div class="well well-sm">
          <a href="/view_video.php?viewkey=abc123"><img class="img-responsive" src="/thumb/1.jpg"></a>
          <span class="video-title">示例标题一</span>
        </div>
        <div class="well well-sm">
          <a href="/view_video.php?viewkey=def456"><img class="img-responsive" src="/thumb/2.jpg"></a>
          <span class="video-title">示例标题二</span>
        </div>
    """.trimIndent()

    /**
     * 用户手上/仓库里的源文件是**数组形式**（`[{…}]`，阅读导出与 yckceo 分享
     * 都这样）。以前 `rawMap` 只收对象，这类源会被当成普通 RSS，最后报
     * 「返回内容无法解析」—— h视频 / 91porn / Rule34 三个文件正是这种。
     */
    @Test
    fun arrayFormSourceJsonIsParsedLikeTheObjectForm() {
        val raw = """
            [
              {
                "sourceName": "h视频",
                "sourceUrl": "https://api.sgapiaba.xyz",
                "type": 0,
                "ruleArticles": "${'$'}.rescont.data[*]",
                "ruleTitle": "${'$'}.title",
                "ruleLink": "/api/videoplay/{{${'$'}.id}}?uuid=1",
                "header": "{\"Referer\":\"https://api.sgapiaba.xyz/\"}"
              }
            ]
        """.trimIndent()
        val source = RssSource(
            id = 7L,
            name = "h视频",
            url = "https://api.sgapiaba.xyz",
            rawJson = raw,
        )
        val rules = LegadoRss.parseRules(source)!!
        assertEquals("$.rescont.data[*]", rules.ruleArticles)
        assertEquals("$.title", rules.ruleTitle)
        assertEquals("https://api.sgapiaba.xyz/", rules.header?.let {
            LegadoRss.parseHeaderMap(it, source.id, source.url)["Referer"]
        })
        // 数组形式也要能读到 header（以前 RssFetcher 自己解析时会漏掉）。
        assertEquals(
            "https://api.sgapiaba.xyz/",
            LegadoRss.parseHeaderMap(
                LegadoRss.sourceFields(raw)?.get("header") as? String,
                source.id,
                source.url,
            )["Referer"],
        )
    }

    @Test
    fun ninetyOneVideoListFieldsMatchLegado() {
        val engine = LegadoRuleEngine(baseUrl = "https://example.com/index.php")
        val items = engine.getElements("div.well.well-sm", ninetyOneHtml)
        assertEquals(2, items.size)
        assertEquals("示例标题一", engine.getString("span.video-title@text", items[0]))
        assertEquals(
            "https://example.com/view_video.php?viewkey=abc123",
            engine.getString("a.0@href", items[0], isUrl = true),
        )
        assertEquals(
            "https://example.com/thumb/1.jpg",
            engine.getString("img.img-responsive@src", items[0], isUrl = true),
        )
    }

    private val rule34Html = """
        <div class="item thumb">
          <a class="th" href="/video/12345/sample-title/"><img class="thumb" data-original="/thumbs/1.jpg" src="/blank.gif"></a>
          <div class="thumb_title">Sample Title One</div>
        </div>
        <div class="item thumb">
          <a class="th" href="/video/67890/second-title/"><img class="thumb" data-original="/thumbs/2.jpg" src="/blank.gif"></a>
          <div class="thumb_title">Sample Title Two</div>
        </div>
    """.trimIndent()

    @Test
    fun rule34VideoListFieldsMatchLegado() {
        val engine = LegadoRuleEngine(baseUrl = "https://rule34video.com/latest-updates/")
        val items = engine.getElements("div.item.thumb", rule34Html)
        assertEquals(2, items.size)
        assertEquals("Sample Title One", engine.getString("div.thumb_title@text", items[0]))
        assertEquals(
            "https://rule34video.com/video/12345/sample-title/",
            engine.getString("a.th@href", items[0], isUrl = true),
        )
        assertEquals(
            "https://rule34video.com/thumbs/1.jpg",
            engine.getString("img.thumb@data-original", items[0], isUrl = true),
        )
    }

    /**
     * h视频：整源走 JSON API，规则里到处是 `{{规则}}` 嵌套写法 ——
     * 链接 `/api/videoplay/{{$.id}}?uuid=1`、封面 `{{$.coverbase64.url}}`、
     * 日期 `📆{{$.updated_at## .*}}  ⏱️{{$.playtimes}}`。阅读会在 `{{}}` 里先按
     * 规则求值；我们之前只认变量/JS，取不到就把字面量留下 —— 链接因此和阅读不同。
     */
    @Test
    fun hVideoJsonApiRulesMatchLegado() {
        val body = """
            {"rescont":{"data":[
              {"id":1,"title":"示例一","coverbase64":{"url":"/c/1.jpg"},
               "updated_at":"2026-10-01 10:00:00","playtimes":3},
              {"id":2,"title":"示例二","coverbase64":{"url":"/c/2.jpg"},
               "updated_at":"2026-10-02 11:00:00","playtimes":4}
            ],"next_page_url":"/api/videosort/0?page=2"}}
        """.trimIndent()
        val engine = LegadoRuleEngine(baseUrl = "https://api.example.com/api/videosort/0?page=1")

        val items = engine.getElements("$.rescont.data[*]", body)
        assertEquals(2, items.size)
        assertEquals("示例一", engine.getString("$.title", items[0]))
        assertEquals(
            "https://api.example.com/api/videoplay/1?uuid=1",
            engine.getString("/api/videoplay/{{$.id}}?uuid=1", items[0], isUrl = true),
        )
        assertEquals(
            "https://api.example.com/c/1.jpg",
            engine.getString("{{$.coverbase64.url}}", items[0], isUrl = true),
        )
        assertEquals(
            "📆2026-10-01  ⏱️3",
            engine.getString("📆{{$.updated_at## .*}}  ⏱️{{$.playtimes}}", items[0]),
        )
        assertEquals(
            "/api/videosort/0?page=2",
            engine.getString("$.rescont.next_page_url", body),
        )
    }

    /**
     * h视频的分类地址里用 JS 生成页码（`{{ Math.ceil(Math.random()*1500) }}`）。
     * 设备上这条源报 `err:bad_url`，就是这种分类 URL 没拼出来 —— 这里直接对
     * `pageUrl()` 做回归：第 1、2 页都必须是合法地址。
     */
    @Test
    fun hVideoSortUrlWithJsPageNumberStaysAValidUrl() {
        val raw = """
            {"sourceUrl":"https://api.sgapiaba.xyz","type":0,
             "ruleArticles":"$.rescont.data[*]",
             "sortUrl":"\n随机::/api/videosort/0?page={{ Math.ceil(Math.random()*1500) }}\n最新::/api/videosort/0?page={{page}}\n"}
        """.trimIndent()
        val source = com.wallpaperswitcher.data.RssSource(
            id = 38L,
            name = "h视频",
            url = "https://api.sgapiaba.xyz",
            rawJson = raw,
        )
        val rules = LegadoRss.parseRules(source)!!
        val categories = LegadoRss.sortUrls(source, rules)
        assertEquals(listOf("随机", "最新"), categories.map { it.name })

        val random = LegadoRss.pageUrl(rules, categories[0], page = 1, sourceId = source.id)
        assertTrue("random category url was '$random'", random.startsWith("https://api.sgapiaba.xyz/api/videosort/0?page="))
        assertTrue(
            "random page number missing in '$random'",
            random.removePrefix("https://api.sgapiaba.xyz/api/videosort/0?page=").toIntOrNull() != null,
        )
        assertEquals(
            "https://api.sgapiaba.xyz/api/videosort/0?page=2",
            LegadoRss.pageUrl(rules, categories[1], page = 2, sourceId = source.id),
        )
    }
}
