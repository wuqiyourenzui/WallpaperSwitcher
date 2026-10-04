package com.wallpaperswitcher.engine

import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读 (Legado) subscription-source import. */
class LegadoImportTest {

    private val arrayJson = """
        [
          {"name":"Example","sourceUrl":"https://example.com/feed.xml","type":0,"enabled":true,
           "ruleArticles":"class.item","header":"User-Agent: X"},
          {"name":"NoUrl","type":0},
          {"name":"Atom","sourceUrl":"https://example.com/atom","type":1,"enabled":false}
        ]
    """.trimIndent()

    @Test
    fun jsonArrayImportsSourcesAndSkipsEntriesWithoutUrl() {
        val result = LegadoImport.parse(arrayJson)
        assertEquals(2, result.sources.size)
        assertEquals(1, result.skipped)
        assertEquals("Example", result.sources[0].name)
        assertEquals("https://example.com/feed.xml", result.sources[0].url)
        assertEquals(0, result.sources[0].type)
        assertEquals(true, result.sources[0].enabled)
        // The original Legado object (rules / header) must survive the import.
        assertTrue(result.sources[0].rawJson.contains("ruleArticles"))
        assertTrue(result.sources[0].rawJson.contains("User-Agent: X"))
        assertEquals(false, result.sources[1].enabled)
    }

    @Test
    fun legadoSourceNameBecomesTheSourceTitle() {
        val result = LegadoImport.parse(
            """{"sourceName":"美人图","sourceUrl":"https://meirentu.club","type":0}"""
        )
        assertEquals(1, result.sources.size)
        assertEquals("美人图", result.sources[0].name)
        assertEquals("https://meirentu.club", result.sources[0].url)
    }

    @Test
    fun singleObjectAndWrapperAreAccepted() {
        val single = LegadoImport.parse(
            """{"name":"One","sourceUrl":"https://a.example/feed"}"""
        )
        assertEquals(1, single.sources.size)
        val wrapped = LegadoImport.parse(
            """{"sources":[{"name":"One","sourceUrl":"https://a.example/feed"}]}"""
        )
        assertEquals(1, wrapped.sources.size)
    }

    @Test
    fun shareLinkWithInlineJsonIsAccepted() {
        val encoded = URLEncoder.encode(arrayJson, "UTF-8")
        val link = "legado://import/rssSource?src=$encoded"
        val result = LegadoImport.parse(link)
        assertEquals(2, result.sources.size)
        assertNull(LegadoImport.extractRemoteSourceUrl(link))
    }

    @Test
    fun shareLinkWithBase64PayloadIsAccepted() {
        val base64 = Base64.getEncoder()
            .encodeToString("""[{"name":"B","sourceUrl":"https://b.example/feed"}]"""
                .toByteArray(Charsets.UTF_8))
        val link = "legado://import/rssSource?src=$base64"
        val result = LegadoImport.parse(link)
        assertEquals(1, result.sources.size)
        assertEquals("https://b.example/feed", result.sources[0].url)
    }

    @Test
    fun shareLinkWithRemoteJsonUrlIsDetected() {
        val encoded = URLEncoder.encode("https://cdn.example/sources.json", "UTF-8")
        val link = "legado://import/rssSource?src=$encoded"
        assertEquals("https://cdn.example/sources.json", LegadoImport.extractRemoteSourceUrl(link))
        assertTrue(LegadoImport.parse(link).sources.isEmpty())
    }

    @Test
    fun bareSubscriptionUrlIsDownloadedAndParsed() {
        // 用户直接粘贴订阅地址（阅读的「导入网络文件」用法）：地址本身不是 JSON，
        // parse() 会返回空，必须交给 remoteUrlToFetch() 去下载。
        val url = "https://ycoo.net/source/plugin/bphp_clouds/upload/53583/" +
            "20260927/9da1a47d319532a7f7e73e79a3815e62.json"
        assertTrue(LegadoImport.parse(url).sources.isEmpty())
        assertEquals(url, LegadoImport.remoteUrlToFetch(url))
        // 前后空白不算地址的一部分。
        assertEquals(url, LegadoImport.remoteUrlToFetch("  $url\n"))
        // JSON / 分享链接 / 普通文本都不该被判成「要下载的地址」。
        assertNull(LegadoImport.remoteUrlToFetch("""[{"name":"x"}]"""))
        assertNull(LegadoImport.remoteUrlToFetch("hello"))
        assertEquals(
            "https://cdn.example/sources.json",
            LegadoImport.remoteUrlToFetch(
                "legado://import/rssSource?src=" +
                    URLEncoder.encode("https://cdn.example/sources.json", "UTF-8")
            ),
        )
    }

    @Test
    fun garbageYieldsNothing() {
        assertTrue(LegadoImport.parse("hello").sources.isEmpty())
        assertTrue(LegadoImport.parse("{not json").sources.isEmpty())
    }
}
