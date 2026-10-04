package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阅读链接请求选项真正生效：`,{headers/method/body}` 会并进最终请求，
 * 而不是像以前那样被剥掉丢掉（见 `LegadoRss.buildRequest`）。
 */
class LegadoRequestOptionsTest {

    @Test
    fun urlOptionsBecomeRequestHeadersAndWinOverSourceHeader() {
        val request = LegadoRss.buildRequest(
            url = """https://api.a.com/play/1,{"headers":{"Referer":"https://a.com/list","X-Token":"t1"}}""",
            header = """{"Referer":"https://a.com/","X-From-Source":"1"}""",
            requestJson = null,
        )
        assertEquals("https://api.a.com/play/1", request.url.toString())
        assertEquals("https://a.com/list", request.header("Referer"))
        assertEquals("t1", request.header("X-Token"))
        assertEquals("1", request.header("X-From-Source"))
    }

    @Test
    fun singleQuotedOptionsFromRealSourcesAlsoApply() {
        val request = LegadoRss.buildRequest(
            url = """https://a.com/x,{headers:{Referer:'https://a.com/list'}}""",
            header = null,
            requestJson = null,
        )
        assertEquals("https://a.com/x", request.url.toString())
        assertEquals("https://a.com/list", request.header("Referer"))
    }

    @Test
    fun optionsCanTurnTheFetchIntoAPost() {
        val request = LegadoRss.buildRequest(
            url = """https://a.com/api,{"method":"POST","body":"page=2"}""",
            header = null,
            requestJson = null,
        )
        assertEquals("POST", request.method)
        val body = request.body ?: error("no body")
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        assertEquals("page=2", buffer.readUtf8())
    }

    @Test
    fun perCategoryRequestJsonStillApplies() {
        val request = LegadoRss.buildRequest(
            url = "https://a.com/api",
            header = null,
            requestJson = """{"method":"POST","body":{"page":3}}""",
        )
        assertEquals("POST", request.method)
    }

    @Test
    fun storedArticleHeadersAreParsedBack() {
        val article = com.wallpaperswitcher.data.RssArticle(
            sourceId = 5L,
            guid = "g",
            requestHeaders = """{"Referer":"https://a.com/list"}""",
        )
        assertEquals("https://a.com/list", LegadoRss.requestHeaders(article)["Referer"])
        assertEquals(
            emptyMap<String, String>(),
            LegadoRss.requestHeaders(article.copy(requestHeaders = "")),
        )
    }

    @Test
    fun readerFieldsAreReadFromTheSourceJson() {
        val source = RssSource(
            id = 5L,
            name = "s",
            url = "https://a.com",
            rawJson = """{"sourceUrl":"https://a.com","ruleArticles":"class.card",""" +
                """"style":"body{color:red}","injectJs":"console.log(1)",""" +
                """"contentBlacklist":"https://ads.","contentWhitelist":null,""" +
                """"shouldOverrideUrlLoading":"url.startsWith('x')",""" +
                """"loadWithBaseUrl":false,"concurrentRate":"2/1000"}""",
        )
        val options = LegadoRss.webOptions(source)
        assertEquals("body{color:red}", options.style)
        assertEquals("console.log(1)", options.injectJs)
        assertEquals("https://ads.", options.contentBlacklist)
        assertEquals("url.startsWith('x')", options.shouldOverrideUrlLoading)
        assertEquals(false, options.loadWithBaseUrl)
        val rules = LegadoRss.parseRules(source)!!
        assertEquals("2/1000", rules.concurrentRate)
    }
}
