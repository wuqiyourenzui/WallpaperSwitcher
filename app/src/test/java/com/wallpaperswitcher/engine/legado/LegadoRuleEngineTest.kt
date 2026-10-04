package com.wallpaperswitcher.engine.legado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读 (Legado) 规则语法核心: CSS / 索引 / 组合 / 替换 / JSONPath / XPath. */
class LegadoRuleEngineTest {

    private val html = """
        <html><body>
          <div class="list">
            <div class="item">
              <a href="/a/1.html"><h3>Title One</h3>
                <img data-src="/img/1.jpg"></a>
              <p class="desc">Desc one</p><span class="time">2026-10-06</span>
            </div>
            <div class="item">
              <a href="/a/2.html"><h3>Title Two</h3>
                <img data-src="/img/2.jpg"></a>
              <p class="desc">Desc two</p><span class="time">2026-10-05</span>
            </div>
          </div>
          <div class="other"><h3>Other</h3></div>
        </body></html>
    """.trimIndent()

    private fun engine() = LegadoRuleEngine(baseUrl = "https://example.com/root/")

    @Test
    fun cssRuleSelectsArticlesAndExtractsFields() {
        val engine = engine()
        val items = engine.getElements("class.item", html)
        assertEquals(2, items.size)
        assertEquals("Title One", engine.getString("tag.h3@text", items[0]))
        assertEquals(
            "https://example.com/a/1.html",
            engine.getString("tag.a@href", items[0], isUrl = true)
        )
        assertEquals(
            "https://example.com/img/1.jpg",
            engine.getString("tag.img@data-src", items[0], isUrl = true)
        )
        assertEquals("Desc one", engine.getString("class.desc@text", items[0]))
    }

    @Test
    fun indexSyntaxPicksExcludesAndRanges() {
        val engine = engine()
        assertEquals(1, engine.getElements("class.item.0", html).size)
        assertEquals(1, engine.getElements("class.item.-1", html).size)
        assertEquals(1, engine.getElements("class.item!0", html).size)
        assertEquals(2, engine.getElements("class.item[0:1]", html).size)
        assertEquals(1, engine.getElements("class.item[1]", html).size)
    }

    @Test
    fun combinatorsMergeAndFallback() {
        val engine = engine()
        assertEquals(3, engine.getElements("class.item&&class.other", html).size)
        assertEquals(2, engine.getElements("class.missing||class.item", html).size)
    }

    @Test
    fun regexReplacementAfterSelection() {
        val engine = engine()
        assertEquals("Title 1", engine.getString("tag.h3@text##One##1", html))
        assertEquals(
            "Title 2",
            engine.getString("class.item.1@tag.h3@text##Two##2", html)
        )
    }

    @Test
    fun jsonPathSelectsObjectsAndValues() {
        val engine = engine()
        val json = """{"data":{"list":[{"title":"A"},{"title":"B"}]}}"""
        assertEquals(2, engine.getElements("$.data.list", json).size)
        assertEquals("A", engine.getString("$.data.list[0].title", json))
        assertEquals(2, engine.getElements("@Json:data.list", json).size)
    }

    @Test
    fun xpathSelectsNodes() {
        val engine = engine()
        val items = engine.getElements("@xpath://div[@class='item']", html)
        assertEquals(2, items.size)
        assertEquals("Title One", engine.getString("@xpath://h3/text()", html))
    }

    @Test
    fun xpathAbsolutePathAndAttributeRuleWork() {
        val engine = engine()
        // 新版阅读用 jsoup 的 selectXpath：绝对路径与 `…/@attr` 都能取到值。
        assertEquals("Title One", engine.getString("/html/body/div/div/a/h3/text()", html))
        assertEquals(
            "https://example.com/a/1.html",
            engine.getString("@xpath://a/@href", html, isUrl = true)
        )
    }

    @Test
    fun doubleAtEscapeInsideBracesRunsTheRule() {
        // 好壁纸的正文规则 `{{@@tag.img@html}}`：`@@` 是转义，里面仍是规则。
        val page = """<div class="card"><img data-src="/i/1.jpg"></div>"""
        assertEquals(
            """<img data-src="/i/1.jpg">""",
            engine().getString("{{@@tag.img@html}}", page)
        )
    }

    @Test
    fun variablesAreSubstituted() {
        val engine = LegadoRuleEngine(
            baseUrl = "https://example.com/",
            variables = mapOf("page" to "3", "host" to "example.com")
        )
        assertEquals("3", engine.getString("{{page}}", "x"))
        assertEquals("https://example.com/", engine.getString("https://{{host}}/", "x"))
    }

    @Test
    fun jsRulesAreExecutedWithTheResultBinding() {
        // Rhino smoke test first: if the engine itself cannot evaluate a
        // literal, the failure is the dependency, not the rule engine.
        val rhinoContext = org.mozilla.javascript.Context.enter()
        try {
            val scope = rhinoContext.initStandardObjects()
            val evaluated = rhinoContext.evaluateString(scope, "'Title One!'", "t", 1, null)
            assertEquals(
                "rhino raw",
                "Title One!",
                org.mozilla.javascript.Context.jsToJava(evaluated, Any::class.java).toString()
            )
        } finally {
            org.mozilla.javascript.Context.exit()
        }
        val engine = engine()
        assertEquals(
            "segments",
            listOf("JS:'Title One!'"),
            engine.debugSegments("<js>'Title One!'</js>")
        )
        val simple = engine.getString("<js>'Title One!'</js>", html)
        assertEquals(
            "js errors: " + engine.takeErrors(),
            "Title One!",
            simple
        )
        assertEquals("Title One", engine.getString("<js>result</js>", "Title One"))
        val value = engine.getString("tag.h3@text@js:result = result + '!'", html)
        assertEquals(emptyList<Any>(), engine.takeErrors())
        assertEquals("Title One!", value)
        // The rule that appends request options is the pattern used by the
        // imported subscription sources.
        val withOptions = engine.getString(
            "tag.a@href@js:result = result + ',{\"headers\":{\"Referer\":\"https://x/\"}}'",
            html,
            isUrl = true
        )
        assertTrue(withOptions.startsWith("https://example.com/a/1.html"))
        assertTrue(withOptions.contains("\"Referer\""))
    }

    @Test
    fun malformedRuleDoesNotThrow() {
        val engine = engine()
        assertTrue(engine.getElements("css???", html).isEmpty())
        assertEquals("", engine.getString("$.missing.field", """{"a":1}"""))
    }

    @Test
    fun chainedClassRuleAppliesToAllMatchesNotOnlyTheFirst() {
        // The real source's ruleArticles is `class.clearfix@class.art`: the
        // navbar also carries class "clearfix", so the engine must continue
        // the selector on EVERY clearfix element.
        val page = """
            <div class="navbar clearfix"><span>menu</span></div>
            <div class="clearfix"><div class="art">A</div><div class="art">B</div></div>
        """.trimIndent()
        val items = engine().getElements("class.clearfix@class.art", page)
        assertEquals(2, items.size)
    }

    @Test
    fun sortUrlCategoryListIsParsedLikeLegado() {
        val sortUrl = """
            最新::/
            秀人::/xiu-ren/XiuRen/
            随机::/xiu-ren/XiuRen/index_{{Math.floor(Math.random()*563)+1}}.html
            搜索::/e/search/index.php,{"method": "POST", "body": "keyboard=x"}
        """.trimIndent()
        val categories = LegadoRss.parseCategories(sortUrl)
        assertEquals(4, categories.size)
        assertEquals("最新", categories[0].name)
        assertEquals("/", categories[0].path)
        assertEquals("/xiu-ren/XiuRen/", categories[1].path)
        assertTrue(categories[2].path.contains("{{"))
        assertEquals("/e/search/index.php", categories[3].path)
        assertTrue(categories[3].requestJson!!.contains("POST"))
    }

    @Test
    fun jsTemplateExpressionsAreEvaluated() {
        assertEquals("7", LegadoJs.eval("Math.floor(Math.random()*1)+7", emptyMap(), ""))
        assertEquals("abc", LegadoJs.eval("'abc'", emptyMap(), ""))
    }

    @Test
    fun sourceVariablesAndJavaHelpersAreBoundInJsRules() {
        // source.setVariable/getVariable, like 阅读's runtime variables.
        val engine = LegadoRuleEngine(baseUrl = "https://example.com/", sourceId = 42L)
        assertEquals(
            "hi",
            engine.getString("<js>source.setVariable('hi'); source.getVariable()</js>", "x")
        )
        assertEquals("hi", RssSourceVariables.get(42L))
        // java.getString applies a rule to the current content.
        assertEquals(
            "Title One",
            engine.getString("<js>java.getString('tag.h3@text')</js>", html)
        )
        assertEquals(
            "2",
            engine.getString("<js>java.getElements('class.item').length</js>", html)
        )
    }

    @Test
    fun webJsSegmentIsRecognizedWithoutRunningAWebView() {
        // The JVM has no WebView; parsing must still classify the segment, and
        // evaluating it must fail soft (null) instead of crashing.
        assertEquals(
            listOf("WEBJS:return 1"),
            engine().debugSegments("@webjs:return 1")
        )
        assertEquals("", engine().getString("@webjs:return 1", html))
    }

    @Test
    fun pageListPlaceholdersPickTheRequestedPage() {
        assertEquals("/list_2.html", LegadoRss.replacePageList("/list_<1,2,3>.html", 2))
        // Past the end the last entry is reused, like 阅读.
        assertEquals("/list_3.html", LegadoRss.replacePageList("/list_<1,2,3>.html", 9))
        assertEquals("/list.html", LegadoRss.replacePageList("/list.html", 2))
    }

    @Test
    fun categoryNextPageRuleIsParsed() {
        val categories = LegadoRss.parseCategories(
            "yalayi::/zhi-ming/yalayi/|.pagebar-1@tag.a.-2@href"
        )
        assertEquals(1, categories.size)
        assertEquals("/zhi-ming/yalayi/", categories[0].path)
        assertEquals(".pagebar-1@tag.a.-2@href", categories[0].nextPageRule)
    }

    @Test
    fun loginEndpointUsesLoginUrlEvenForPlainFeeds() {
        // type=0 (plain RSS) with no ruleArticles: parseRules() returns null,
        // but loginUrl is a source-level field and must still be honored.
        val source = com.wallpaperswitcher.data.RssSource(
            id = 7,
            url = "http://10.0.2.2:8129/feed",
            type = 0,
            rawJson = """{"sourceName":"t","sourceUrl":"http://10.0.2.2:8129/feed",""" +
                """"loginUrl":"http://10.0.2.2:8129/login","type":0}""",
        )
        assertEquals("http://10.0.2.2:8129/login", LegadoRss.loginEndpoint(source))
    }

    @Test
    fun loginEndpointResolvesRelativeLoginUrlAgainstSourceUrl() {
        val source = com.wallpaperswitcher.data.RssSource(
            id = 8,
            url = "https://example.com/feed.xml",
            rawJson = """{"sourceUrl":"https://example.com/root/","loginUrl":"login.html"}""",
        )
        assertEquals("https://example.com/root/login.html", LegadoRss.loginEndpoint(source))
    }

    @Test
    fun loginEndpointFallsBackToSourceUrlWithoutLoginUrl() {
        val source = com.wallpaperswitcher.data.RssSource(
            id = 9,
            url = "https://example.com/feed.xml",
            rawJson = """{"sourceUrl":"https://example.com/feed.xml"}""",
        )
        assertEquals("https://example.com/feed.xml", LegadoRss.loginEndpoint(source))
    }

    @Test
    fun loginCheckJsIsOptionalAndReadFromRawJson() {
        val withCheck = com.wallpaperswitcher.data.RssSource(
            id = 10,
            url = "https://example.com/feed",
            rawJson = """{"loginCheckJs":"document.cookie.includes('sid=')"}""",
        )
        assertEquals("document.cookie.includes('sid=')", LegadoRss.loginCheckJs(withCheck))
        val without = com.wallpaperswitcher.data.RssSource(
            id = 11,
            url = "https://example.com/feed",
            rawJson = """{"sourceUrl":"https://example.com/feed"}""",
        )
        assertEquals(null, LegadoRss.loginCheckJs(without))
    }

    @Test
    fun ruleAlternativesSplitOnTopLevelCommasOnly() {
        assertEquals(listOf("a", "b", "c"), RuleAlternatives.split("a,b,c"))
        // Commas inside selectors / filters / JS objects stay put.
        assertEquals(
            listOf("img[alt=\"a,b\"]@src", "tag.img@data-src"),
            RuleAlternatives.split("img[alt=\"a,b\"]@src,tag.img@data-src")
        )
        assertEquals(
            listOf("@js: return {'a':1, 'b':2}"),
            RuleAlternatives.split("@js: return {'a':1, 'b':2}")
        )
        // A `<js>` block is opaque even when it contains commas.
        assertEquals(
            listOf("<js>var a = [1,2][0]; a</js>", "tag.p@text"),
            RuleAlternatives.split("<js>var a = [1,2][0]; a</js>,tag.p@text")
        )
    }

    @Test
    fun headerIsParsedFromJsonJsOrLegacyLines() {
        val json = LegadoRss.parseHeaderMap(
            "{\n  \"User-Agent\": \"UA-1\",\n  \"Referer\": \"https://a.com\"\n}",
            1L,
        )
        assertEquals("UA-1", json["User-Agent"])
        assertEquals("https://a.com", json["Referer"])
        val legacy = LegadoRss.parseHeaderMap("Referer: https://b.com", 1L)
        assertEquals("https://b.com", legacy["Referer"])
        val fromJs = LegadoRss.parseHeaderMap(
            "@js: JSON.stringify({'Referer': 'https://c.com'})",
            1L,
            "https://c.com/",
        )
        assertEquals("https://c.com", fromJs["Referer"])
        assertTrue(LegadoRss.parseHeaderMap(null, 1L).isEmpty())
    }

    @Test
    fun categoryListAcceptsAndAndNewlines() {
        val categories = LegadoRss.parseCategories(
            "最新::/new.html&&随机::/r/index_{{page}}.html\n旧::/old.html"
        )
        assertEquals(3, categories.size)
        assertEquals("最新", categories[0].name)
        assertEquals("/r/index_{{page}}.html", categories[1].path)
    }

    @Test
    fun jsLoginIsDetectedAndStripped() {
        assertEquals(true, LegadoRss.isJsLogin("@js:function login(){}"))
        assertEquals(true, LegadoRss.isJsLogin("<js>function login(){}</js>"))
        assertEquals(false, LegadoRss.isJsLogin("https://a.com/login"))
        assertEquals(
            "function login(){}",
            LegadoRss.loginScript("@js:function login(){}")
        )
    }

    @Test
    fun htmlExtractionJoinsEveryMatchLikeLegado() {
        // 阅读's `tag.img@html` returns the outerHtml of *all* matches (a
        // gallery must not collapse to its first image).
        val joined = engine().getString("tag.img@html", html, joinAll = true)
        assertTrue(joined.contains("/img/1.jpg"))
        assertTrue(joined.contains("/img/2.jpg"))
        // The default single-value behaviour stays untouched.
        val first = engine().getString("tag.img@data-src", html)
        assertEquals("/img/1.jpg", first)
    }

    @Test
    fun contentTemplateExpandsInnerRules() {
        val page = """<div class="a">
            <img data-src="/uploadfile/a1.jpg"><img data-src="/uploadfile/a2.jpg">
        </div>"""
        val template = """<div id="box">{{@@tag.img@html}}</div>"""
        val out = LegadoRss.expandContentTemplate(
            engine = LegadoRuleEngine(baseUrl = "http://3w.example.com/XiuRen/1.html"),
            template = template,
            content = page,
            variables = emptyMap(),
            baseUrl = "http://3w.example.com/XiuRen/1.html",
            sourceId = 0L,
        )
        assertTrue(out.contains("id=\"box\""))
        assertTrue(out.contains("/uploadfile/a1.jpg"))
        assertTrue(out.contains("/uploadfile/a2.jpg"))
    }

    @Test
    fun categoryPathsResolveAgainstTheSourceUrl() {
        // Absolute path: replaces the whole path of the source URL.
        assertEquals(
            "http://site.example:8080/cat2",
            LegadoRss.resolveAgainstSource("http://site.example:8080/cat1", "/cat2")
        )
        // Relative path: resolved against the source URL's directory.
        assertEquals(
            "http://site.example:8080/sub/cat2",
            LegadoRss.resolveAgainstSource("http://site.example:8080/feed.xml", "sub/cat2")
        )
        // Same shape as the real sources (root source URL + leading slash).
        assertEquals(
            "http://3w.example.com/XiuRen/",
            LegadoRss.resolveAgainstSource("http://3w.example.com/", "/XiuRen/")
        )
        assertEquals(
            "https://b.com/x",
            LegadoRss.resolveAgainstSource("https://a.com/y", "https://b.com/x")
        )
    }

    @Test
    fun cssAttributeSelectorIsNotMistakenForAnIndex() {
        val page = """
            <div class="posts-item">
              <h2 class="item-heading">套图标题一</h2>
              <a href="/p/1">链接</a>
            </div>
            <div class="posts-item">
              <h2 class="item-heading">套图标题二</h2>
              <a href="/p/2">链接</a>
            </div>
        """.trimIndent()
        val engine = LegadoRuleEngine(baseUrl = "https://mtldss.top/")
        // Real source rule: h2[class="item-heading"]@text
        assertEquals(
            "套图标题一",
            engine.getString("""h2[class="item-heading"]@text""", page)
        )
        assertEquals(
            "套图标题一\n套图标题二",
            engine.getString("""h2[class="item-heading"]@text""", page, joinAll = true)
        )
        // Index syntax still works.
        assertEquals(
            "/p/2",
            engine.getString("tag.a[-1]@href", page)
        )
        assertEquals(
            "套图标题二",
            engine.getString("tag.h2.1@text", page)
        )
    }

    @Test
    fun lazyLoadedImagesFallBackToDataAttributes() {
        val page = """
            <div class="item"><img src="data:image/gif;base64,R0lGOD"
                data-original="/img/lazy1.jpg"></div>
        """.trimIndent()
        val engine = LegadoRuleEngine(baseUrl = "https://a.com/")
        // The rule only knows src/data-src; the real URL sits in data-original.
        assertEquals(
            "/img/lazy1.jpg",
            engine.getString("tag.img@data-src", page)
        )
        // A `data:` placeholder in `src` also falls back to the real attribute.
        assertEquals(
            "/img/lazy1.jpg",
            engine.getString("tag.img@src", page)
        )
    }
}
