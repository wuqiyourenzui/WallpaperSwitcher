package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the shared subscription-source rules that are easy to get wrong
 * silently: the HTTPS / cleartext policy, URL resolution and the stable result
 * codes the UI localizes.
 */
class OnlineSourceRulesTest {

    @Test
    fun publicCleartextIsRejectedButPrivateLanIsAllowed() {
        assertEquals(
            OnlineSourceRules.EndpointPolicy.OK,
            OnlineSourceRules.endpointPolicy("https://example.com/latest.jpg")
        )
        // A public http endpoint would leak the request to the whole network.
        assertEquals(
            OnlineSourceRules.EndpointPolicy.NEEDS_HTTPS,
            OnlineSourceRules.endpointPolicy("http://example.com/latest.jpg")
        )
        // A LAN NAS cannot present a trusted certificate; this stays allowed.
        assertEquals(
            OnlineSourceRules.EndpointPolicy.OK,
            OnlineSourceRules.endpointPolicy("http://192.168.1.10/dav/photos")
        )
        assertEquals(
            OnlineSourceRules.EndpointPolicy.OK,
            OnlineSourceRules.endpointPolicy("http://10.0.0.5:8080/x")
        )
        assertEquals(
            OnlineSourceRules.EndpointPolicy.OK,
            OnlineSourceRules.endpointPolicy("http://localhost:8080/x")
        )
        assertEquals(
            OnlineSourceRules.EndpointPolicy.INVALID,
            OnlineSourceRules.endpointPolicy("ftp://example.com/x")
        )
        assertEquals(
            OnlineSourceRules.EndpointPolicy.INVALID,
            OnlineSourceRules.endpointPolicy("not a url")
        )
    }

    @Test
    fun importedLegadoSourcesMayOptIntoPublicCleartext() {
        // 阅读 sources are imported by the user and may legitimately be http.
        assertEquals(
            OnlineSourceRules.EndpointPolicy.OK,
            OnlineSourceRules.endpointPolicy("http://example.com/feed", allowCleartext = true)
        )
    }

    @Test
    fun imageExtensionPrefersTheContentTypeAndRejectsHtml() {
        assertEquals("jpg", OnlineSourceRules.imageExtension("https://x/a", "image/jpeg"))
        assertEquals("png", OnlineSourceRules.imageExtension("https://x/a.PNG?x=1", null))
        assertEquals("gif", OnlineSourceRules.imageExtension("https://x/a.gif", "image/gif"))
        assertNull(OnlineSourceRules.imageExtension("https://x/page.html", "text/html"))
        assertNull(OnlineSourceRules.imageExtension("https://x/noext", null))
    }

    @Test
    fun resultCodesRoundTrip() {
        val ok = OnlineSourceRules.decodeResult(OnlineSourceRules.encodeOk(3, 2))!!
        assertEquals(false, ok.isError)
        assertEquals(3, ok.added)
        assertEquals(2, ok.skipped)
        val err = OnlineSourceRules.decodeResult(OnlineSourceRules.encodeError("auth"))!!
        assertEquals(true, err.isError)
        assertEquals("auth", err.reason)
        assertNull(OnlineSourceRules.decodeResult(""))
        assertNull(OnlineSourceRules.decodeResult("garbage"))
    }

    @Test
    fun privateHostDetectionCoversTheUsualLanRanges() {
        assertTrue(OnlineSourceRules.isPrivateHost("192.168.31.7"))
        assertTrue(OnlineSourceRules.isPrivateHost("172.16.5.4"))
        assertTrue(OnlineSourceRules.isPrivateHost("172.31.255.254"))
        assertTrue(OnlineSourceRules.isPrivateHost("nas.local"))
        assertEquals(false, OnlineSourceRules.isPrivateHost("172.32.0.1"))
        assertEquals(false, OnlineSourceRules.isPrivateHost("8.8.8.8"))
        assertEquals(false, OnlineSourceRules.isPrivateHost("example.com"))
    }

    @Test
    fun originOfDropsThePathAndKeepsThePort() {
        assertEquals("https://meirentu.club", OnlineSourceRules.originOf("https://meirentu.club/pic/1.html"))
        assertEquals("https://nas.example.com:8443", OnlineSourceRules.originOf("https://nas.example.com:8443/dav/x"))
        assertNull(OnlineSourceRules.originOf("not a url"))
    }

    @Test
    fun resolveUrlHandlesRelativeAbsoluteAndProtocolRelativeTargets() {
        val base = "https://a.example/dir/page.html"
        assertEquals("https://a.example/dir/c.jpg", OnlineSourceRules.resolveUrl(base, "c.jpg"))
        assertEquals("https://a.example/b.jpg", OnlineSourceRules.resolveUrl(base, "/b.jpg"))
        assertEquals("https://cdn.example/b.jpg", OnlineSourceRules.resolveUrl(base, "//cdn.example/b.jpg"))
        assertEquals(
            "https://cdn.example/b.jpg",
            OnlineSourceRules.resolveUrl(base, "https://cdn.example/b.jpg")
        )
        assertNull(OnlineSourceRules.resolveUrl("not a url", "x.jpg"))
    }

    @Test
    fun logSafeHostNeverReturnsThePathOrQuery() {
        assertEquals(
            "cdn.example.com",
            OnlineSourceRules.logSafeHost("https://cdn.example.com/a.jpg?token=secret")
        )
        assertEquals("?", OnlineSourceRules.logSafeHost("not a url"))
    }

    @Test
    fun htmlEntitiesAreDecoded() {
        assertEquals("周妍希&绮里嘉", OnlineSourceRules.decodeHtmlEntities("周妍希&amp;绮里嘉"))
        assertEquals("a<b>c", OnlineSourceRules.decodeHtmlEntities("a&lt;b&gt;c"))
        assertEquals("it's", OnlineSourceRules.decodeHtmlEntities("it&#39;s"))
        // &amp;lt; must decode to the literal "&lt;", not to "<".
        assertEquals("&lt;", OnlineSourceRules.decodeHtmlEntities("&amp;lt;"))
        assertEquals("plain", OnlineSourceRules.decodeHtmlEntities("plain"))
    }

    // --- 内置在线壁纸源的页面解析（HTML/Atom 片段取自真实页面） --------------

    @Test
    fun apodHeroImageLosesTheScalingQuery() {
        val html = """
            <div class="media-detail-hero__media">
              <figure><img src="https://assets.science.nasa.gov/content/dam/science/cds/apod/apod/2026/october/Smile.gif?w=512&amp;h=512&amp;fit=clip"/></figure>
            </div>
        """.trimIndent()
        val image = OnlineSourceRules.parseApodPage(html)
        assertEquals(
            "https://assets.science.nasa.gov/content/dam/science/cds/apod/apod/2026/october/Smile.gif",
            image?.url
        )
        assertEquals("Smile.gif", image?.displayName)
        // A video day (no hero <img>) must yield null, not a broken URL.
        assertNull(OnlineSourceRules.parseApodPage("<div class='media-detail-hero__media'></div>"))
    }

    @Test
    fun wikimediaThumbnailsAreUpgradedToTheOriginal() {
        val atom = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:commons,2026:potd</id>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
                  <img src="//upload.wikimedia.org/wikipedia/commons/thumb/a/ab/File.jpg/800px-File.jpg"/>
                </div></content>
              </entry>
            </feed>
        """.trimIndent()
        val images = OnlineSourceRules.parseWikimediaFeed(atom)
        assertEquals(1, images.size)
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/File.jpg",
            images.first().url
        )
        assertEquals("tag:commons,2026:potd", images.first().remoteKey)
    }

    @Test
    fun wikimediaRssItemsWithEscapedImagesAreParsed() {
        // The live feed is RSS 2.0 with HTML-escaped <img> inside <description>.
        val rss = """
            <rss version="2.0"><channel>
              <item>
                <title>Picture of the day for October 6</title>
                <link>https://commons.wikimedia.org/wiki/Special:FeedItem/potd/20261006000000</link>
                <description>&lt;img src="//upload.wikimedia.org/wikipedia/commons/thumb/a/ab/File.jpg/800px-File.jpg"/&gt;</description>
              </item>
            </channel></rss>
        """.trimIndent()
        val images = OnlineSourceRules.parseWikimediaFeed(rss)
        assertEquals(1, images.size)
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/File.jpg",
            images.first().url
        )
        assertTrue(images.first().remoteKey.startsWith("https://commons.wikimedia.org/"))
    }

    @Test
    fun netbianListingAndDetailYieldTheOriginalImage() {
        val listing = """
            <ul class="slist"><li><a href="/tupian/44186.html" target="_blank">
              <img src="/uploads/allimg/260930/thumb.jpg"/><b>森林 4K</b></a></li></ul>
        """.trimIndent()
        val links = OnlineSourceRules.parseNetbianListing(listing)
        assertEquals(listOf("https://pic.netbian.com/tupian/44186.html"), links)
        val detail = """
            <div class="photo-pic"><a id="img"><img
              src="/uploads/allimg/160627/full.jpg"
              data-pic="/uploads/allimg/160627/small.jpg" alt="阳光草地 4K壁纸"/></a></div>
        """.trimIndent()
        val image = OnlineSourceRules.parseNetbianDetail(detail, links.first())
        assertEquals("https://pic.netbian.com/uploads/allimg/160627/full.jpg", image?.url)
        assertEquals("阳光草地 4K壁纸", image?.displayName)
    }

    @Test
    fun ioliuDetailPrefersTheFullResolutionBingUrl() {
        val home = """
            <a href="/wallpapers/w10jpx932ijvmr">card</a>
            <a href="/wallpapers/w1cmx453jqmjdi">card</a>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://bing.ioliu.cn/wallpapers/w10jpx932ijvmr",
                "https://bing.ioliu.cn/wallpapers/w1cmx453jqmjdi",
            ),
            OnlineSourceRules.parseIoliuListing(home)
        )
        val detail = """
            <img src="https://cn.bing.com/th?id=OHR.KidsDay_PT-BR9683382943_400x240.jpg"/>
            <meta property="og:image" content="https://global.bing.com/th?id=OHR.KidsDay_PT-BR9683382943_1920x1200.jpg"/>
        """.trimIndent()
        val image = OnlineSourceRules.parseIoliuDetail(detail)
        assertEquals(
            "https://global.bing.com/th?id=OHR.KidsDay_PT-BR9683382943_1920x1200.jpg",
            image?.url
        )
    }
}
