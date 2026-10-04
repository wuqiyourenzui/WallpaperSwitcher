package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 阅读的「JS 源」：`jsLib` 在运行时生成规则（`getJs()` + `eval(String(...))`）。
 * 这里用内联 jsLib 覆盖整条链：jsLib → 源脚本 → 规则读回。
 */
class LegadoJsSourceTest2 {

    private fun source(jsLib: String, header: String = "<js>eval(String(getJs()));</js>") =
        RssSource(
            id = 42L,
            name = "JS 源",
            url = "https://js.example.com",
            rawJson = """{"sourceName":"JS 源","sourceUrl":"https://js.example.com",""" +
                """"type":0,"header":${escape(header)},"jsLib":${escape(jsLib)}}""",
        )

    private fun escape(text: String): String =
        com.wallpaperswitcher.engine.Json.encode(text)

    @Test
    fun libraryGeneratesTheRulesAtRuntime() {
        val jsLib = """
            function getJs() {
                return 'source.ruleArticles = "ul.list li";' +
                    'source.ruleTitle = "h2 a@text";' +
                    'source.ruleLink = "h2 a@href";' +
                    'source.sortUrl = "最新::/new.html";';
            }
        """.trimIndent()
        val rules = runBlocking { LegadoRss.rulesFor(source(jsLib)) }!!
        assertEquals("ul.list li", rules.ruleArticles)
        assertEquals("h2 a@text", rules.ruleTitle)
        assertEquals("h2 a@href", rules.ruleLink)
        assertEquals("最新::/new.html", rules.sortUrl)
    }

    @Test
    fun theRuntimeSeesTheSourceKeyAndCache() {
        val jsLib = """
            function getJs() {
                cache.put('seen', source.getKey());
                return 'source.ruleArticles = "div.item";source.ruleTitle = "' +
                    cache.get('seen') + '";';
            }
        """.trimIndent()
        val rules = runBlocking { LegadoRss.rulesFor(source(jsLib)) }!!
        assertEquals("div.item", rules.ruleArticles)
        assertEquals("https://js.example.com", rules.ruleTitle)
    }

    @Test
    fun withoutAHeaderTheRuntimeCallsGetJsItself() {
        // 阅读的 JS 源约定：header 里 eval(getJs())。没有 header 时直接调 getJs()。
        val jsLib = """function getJs(){ return 'source.ruleArticles = "li";'; }"""
        val src = RssSource(
            id = 43L,
            name = "JS 源",
            url = "https://js.example.com",
            rawJson = """{"sourceUrl":"https://js.example.com","type":0,""" +
                """"jsLib":${escape(jsLib)}}""",
        )
        val rules = runBlocking { LegadoRss.rulesFor(src) }!!
        assertEquals("li", rules.ruleArticles)
    }

    @Test
    fun unreachableLibraryUrlFailsSoftly() {
        val raw = """{"sourceUrl":"https://js.example.com","type":0,""" +
            """"header":"<js>eval(String(getJs()));</js>",""" +
            """"jsLib":"{\"x\":\"https://127.0.0.1:1/jsLib.js\"}"}"""
        val src = RssSource(id = 44L, name = "JS 源", url = "https://js.example.com", rawJson = raw)
        assertNull(runBlocking { LegadoRss.rulesFor(src) })
        // 拿不到规则时仍然按「需要阅读的 JS 运行时」报给用户，而不是别的错。
        assertEquals(true, LegadoRss.requiresJsRuntime(src))
    }

    @Test
    fun dangerousJavaClassesStayInvisible() {
        val jsLib = """
            function getJs() {
                var file = new Packages.java.io.File('/data/local/tmp/x');
                return 'source.ruleArticles = "li";';
            }
        """.trimIndent()
        assertNull(runBlocking { LegadoRss.rulesFor(source(jsLib)) })
    }

    @Test
    fun staticRuleSourcesNeverEnterTheJsRuntime() {
        val raw = """{"sourceUrl":"https://a.com","ruleArticles":"div.x",""" +
            """"jsLib":"{\"x\":\"https://a.com/jsLib.js\"}"}"""
        val src = RssSource(id = 45L, name = "静态源", url = "https://a.com", rawJson = raw)
        assertEquals("div.x", runBlocking { LegadoRss.rulesFor(src) }!!.ruleArticles)
    }
}
