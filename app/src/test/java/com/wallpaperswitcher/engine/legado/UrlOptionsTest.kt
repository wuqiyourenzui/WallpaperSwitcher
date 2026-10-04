package com.wallpaperswitcher.engine.legado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读 `URL,{…}` 链接请求选项（AnalyzeUrl / UrlOptionSerializer）。 */
class UrlOptionsTest {

    @Test
    fun standardJsonOptionsAreParsed() {
        val (url, option) = UrlOptions.split(
            """https://a.com/list,{"headers":{"Referer":"https://a.com/","X-Token":"t1"}}"""
        )
        assertEquals("https://a.com/list", url)
        assertEquals("https://a.com/", option.headers["Referer"])
        assertEquals("t1", option.headers["X-Token"])
    }

    @Test
    fun looseJsonFromRealSourcesIsAccepted() {
        // 源里常见的单引号 / 不带引号的键（阅读用宽松解析兜底）。
        val (url, option) = UrlOptions.split(
            """https://a.com/play/1,{headers:{Referer:'https://a.com/list',Cookie:'a=1'}}"""
        )
        assertEquals("https://a.com/play/1", url)
        assertEquals("https://a.com/list", option.headers["Referer"])
        assertEquals("a=1", option.headers["Cookie"])
    }

    @Test
    fun methodAndBodyAreKept() {
        val (_, option) = UrlOptions.split(
            """https://a.com/search,{"method":"POST","body":"page=2"}"""
        )
        assertEquals("POST", option.method)
        assertEquals("page=2", option.body)
    }

    @Test
    fun structuredBodyIsReEncoded() {
        val (_, option) = UrlOptions.split(
            """https://a.com/api,{"method":"POST","body":{"page":2}}"""
        )
        assertEquals("""{"page":2}""", option.body)
    }

    @Test
    fun plainUrlsAndTrailingCommasAreUntouched() {
        val plain = "https://a.com/a,b?x=1,2"
        assertEquals(plain, UrlOptions.strip(plain))
        assertEquals(plain, UrlOptions.split(plain).first)
        assertTrue(UrlOptions.split(plain).second.isEmpty)
        assertTrue(UrlOptions.split("https://a.com/x").second.isEmpty)
    }

    @Test
    fun brokenOptionsKeepTheUrl() {
        val (url, option) = UrlOptions.split("""https://a.com/x,{headers:"""")
        assertEquals("https://a.com/x", url)
        assertTrue(option.isEmpty)
    }
}
