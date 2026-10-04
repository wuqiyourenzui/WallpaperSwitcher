package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分类地址里的 `@js:`：阅读的许多源（推次元 a2cy.com 就是一个）用它区分
 * 第 1 页与后续页（`list` vs `list/index_2.html`）。这里锁住两件事：
 * 分类路径会被 JS 求值，且 `page` 会作为变量传进脚本。
 */
class LegadoCategoryUrlTest {

    private val rawJson = """
        {
          "sourceUrl": "https://a2cy.com/phone/home/",
          "sortUrl": "正片::@js:'https://a2cy.com/phone/list' + (page > 1 ? '/index_' + page + '.html' : '')\n写真::https://a2cy.com/phone/daily",
          "ruleArticles": "ul.list_box li",
          "ruleTitle": "h2 a@text||h3@text",
          "ruleLink": "h2 a@href||a@href",
          "ruleImage": "img.lazy.0@data-loadsrc",
          "sourceName": "推次元"
        }
    """.trimIndent()

    private fun rules(): LegadoRss.Rules {
        val source = RssSource(
            id = 7L,
            name = "推次元",
            url = "https://a2cy.com/phone/home/",
            type = 0,
            rawJson = rawJson,
        )
        return LegadoRss.parseRules(source)!!
    }

    @Test
    fun jsCategoryPathIsEvaluatedForEachPage() {
        val rules = rules()
        val source = RssSource(id = 7L, url = rules.sourceUrl)
        val jsCategory = LegadoRss.sortUrls(source, rules).first()

        assertEquals(
            "https://a2cy.com/phone/list",
            LegadoRss.pageUrl(rules, jsCategory, page = 1, sourceId = 7L),
        )
        assertEquals(
            "https://a2cy.com/phone/list/index_2.html",
            LegadoRss.pageUrl(rules, jsCategory, page = 2, sourceId = 7L),
        )
    }

    @Test
    fun plainCategoryPathStillWorks() {
        val rules = rules()
        val source = RssSource(id = 7L, url = rules.sourceUrl)
        val plain = LegadoRss.sortUrls(source, rules)[1]

        assertEquals("写真", plain.name)
        assertEquals(
            "https://a2cy.com/phone/daily",
            LegadoRss.pageUrl(rules, plain, page = 1, sourceId = 7L),
        )
    }

    @Test
    fun sourceUrlIsUsedWhenThereIsNoCategory() {
        val rules = rules()
        assertEquals(
            "https://a2cy.com/phone/home/",
            LegadoRss.pageUrl(rules, category = null, page = 1, sourceId = 7L),
        )
    }

    /**
     * 推次元列表页的真实结构（a2cy.com/phone/list/ 抓下来的片段）：`ul.list_box li`
     * 里标题在 `h2 a`、链接同址、图片在 `img.lazy[data-loadsrc]`。锁住这条源的
     * 三个字段解析，防止以后规则引擎改动把它打回 0 篇。
     */
    @Test
    fun a2cyListMarkupParsesArticleFields() {
        val html = """
            <ul class="w pt40 list_box">
              <li>
                <div class="w tc hidden pr maxWidth"><a href="/phone/list/cos/2444.html"><img class="lazy" data-loadsrc="/d/file/phone/list/cos/2026-09-24/fd3f.jpg" alt="x"></a></div>
                <h2 class="w"><span class="vm header"></span><a href="/phone/list/cos/2444.html" class="vm">【推次元COS】原神 花火cos - 九柒喵</a></h2>
              </li>
              <li>
                <div class="w tc hidden pr maxWidth"><a href="/phone/list/cos/2441.html"><img class="lazy" data-loadsrc="/d/file/phone/list/cos/2026-09-22/8daf.jpg" alt="x"></a></div>
                <h2 class="w"><span class="vm header"></span><a href="/phone/list/cos/2441.html" class="vm">【推次元COS】NIKKE 薇尔维特cos</a></h2>
              </li>
            </ul>
        """.trimIndent()
        val engine = LegadoRuleEngine(baseUrl = "https://a2cy.com/phone/list/")

        val items = engine.getElements("ul.list_box li", html)
        assertEquals(2, items.size)
        // `||` must be split into alternatives BEFORE the `@` chain is applied:
        // passing the whole "h2 a@text||h3 a@text" to the engine splits it at the
        // second `@` and the middle segment ("text||h3 a") selects nothing.
        fun value(rule: String, isUrl: Boolean = false): String {
            for (alternative in RuleAlternatives.split(rule)) {
                val v = engine.getString(alternative, items[0], isUrl)
                if (v.isNotBlank()) return v
            }
            return ""
        }
        assertEquals(
            "【推次元COS】原神 花火cos - 九柒喵",
            value("h2 a@text||h3 a@text||h3@text"),
        )
        assertEquals(
            "https://a2cy.com/phone/list/cos/2444.html",
            value("h2 a@href||h3 a@href||a@href", isUrl = true),
        )
        assertEquals(
            "https://a2cy.com/d/file/phone/list/cos/2026-09-24/fd3f.jpg",
            value("img.lazy.0@data-loadsrc||img@data-loadsrc", isUrl = true),
        )
    }

    @Test
    fun alternativesSplitOnDoublePipeButNotInsideBracketsOrQuotes() {
        assertEquals(
            listOf("h2 a@text", "h3 a@text", "h3@text"),
            RuleAlternatives.split("h2 a@text||h3 a@text||h3@text"),
        )
        // `,` is the older separator and keeps working.
        assertEquals(listOf("a@href", "b@href"), RuleAlternatives.split("a@href,b@href"))
        // A `||` inside a CSS attribute selector / a JS string is left alone.
        assertEquals(
            listOf("a[href||data-x]@href"),
            RuleAlternatives.split("a[href||data-x]@href"),
        )
        assertEquals(
            listOf("@js:result || 'fallback'"),
            RuleAlternatives.split("@js:result || 'fallback'"),
        )
    }
}
