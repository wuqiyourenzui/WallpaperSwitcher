package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the online-source rules that are easy to get wrong silently: the
 * WorkManager interval floor, the HTTPS policy, the Bing/WebDAV parsers and the
 * stable result codes the UI localizes.
 */
class OnlineSourceRulesTest {

    @Test
    fun intervalIsClampedToTheWorkManagerFloorAndTheSevenDayCeiling() {
        // 0 (never set) falls back to the daily default; anything below the
        // 15-minute periodic minimum is raised, so WorkManager never rejects it.
        assertEquals(24 * 60, OnlineSourceRules.normalizeIntervalMinutes(0))
        assertEquals(15, OnlineSourceRules.normalizeIntervalMinutes(5))
        assertEquals(30, OnlineSourceRules.normalizeIntervalMinutes(30))
        assertEquals(7 * 24 * 60, OnlineSourceRules.normalizeIntervalMinutes(Int.MAX_VALUE))
    }

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
    fun bingPayloadParsesAndResolvesTheRelativeUrl() {
        val json = """
            {"images":[
              {"url":"/th?id=OHR.Foo_ZH-CN123_1920x1080.jpg&rf=LaDigue",
               "enddate":"20261002","title":"t"},
              {"url":"https://cdn.example.com/2.jpg","enddate":"20261001","title":"t2"}
            ]}
        """.trimIndent()
        val images = OnlineSourceRules.parseBingPayload(json)
        assertEquals(2, images.size)
        assertEquals("20261002", images[0].remoteKey)
        assertEquals(
            "https://www.bing.com/th?id=OHR.Foo_ZH-CN123_1920x1080.jpg&rf=LaDigue",
            images[0].url
        )
        assertEquals("20261001", images[1].remoteKey)
        assertEquals("https://cdn.example.com/2.jpg", images[1].url)
    }

    @Test
    fun malformedBingPayloadIsEmptyInsteadOfThrowing() {
        assertTrue(OnlineSourceRules.parseBingPayload("{").isEmpty())
        assertTrue(OnlineSourceRules.parseBingPayload("""{"images":[]}""").isEmpty())
    }

    @Test
    fun webDavListingParsesAnyNamespacePrefix() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/photos/a.jpg</d:href>
                <d:propstat><d:prop>
                  <d:getlastmodified>Tue, 06 Oct 2026 12:34:56 GMT</d:getlastmodified>
                  <d:getcontentlength>1234</d:getcontentlength>
                </d:prop></d:propstat>
              </d:response>
              <d:response><d:href>/dav/photos/sub/</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = OnlineSourceRules.parseWebDavListing(xml)
        assertEquals(2, entries.size)
        assertEquals("/dav/photos/a.jpg", entries[0].href)
        assertTrue(entries[0].lastModifiedMs > 0L)
        assertEquals(1234L, entries[0].sizeBytes)
        assertTrue(OnlineSourceRules.looksLikeWebDavDirectory(entries[1].href))
    }

    @Test
    fun webDavListingRejectsDoctypeEntities() {
        // XXE: the listing comes from the network and must never read a local
        // file. disallow-doctype-decl turns this document into a parse error.
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>&xxe;</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
        assertTrue(OnlineSourceRules.parseWebDavListing(xml).isEmpty())
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
    fun basicAuthHeaderEncodesUserAndPassword() {
        assertEquals("Basic dXNlcjpwYXNz", OnlineSourceRules.basicAuthHeader("user", "pass"))
        assertNull(OnlineSourceRules.basicAuthHeader("", ""))
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
    fun keepCountIsClampedToTheSupportedRange() {
        assertEquals(0, OnlineSourceRules.normalizeKeepCount(0))
        assertEquals(0, OnlineSourceRules.normalizeKeepCount(-5))
        assertEquals(2000, OnlineSourceRules.normalizeKeepCount(Int.MAX_VALUE))
    }

    // --- 美人图 (meirentu.club) HTML scraping ---

    @Test
    fun meirentuAlbumIdsAreUniqueAndKeepTheListingOrder() {
        val html = """
            <a href="/pic/441354166995.html">a</a>
            <a href="/pic/441354166995-2.html">a page 2</a>
            <a href="/pic/227192094259.html">b</a>
            <a href="/group/xiuren.html">not an album</a>
            <a href="/pic/972117786737-38.html">c last page</a>
        """.trimIndent()
        assertEquals(
            listOf(441354166995L, 227192094259L, 972117786737L),
            OnlineSourceRules.parseMeirentuAlbumIds(html)
        )
    }

    @Test
    fun meirentuImagesAreFilteredByAlbumIdAndResolved() {
        val html = """
            <img src="/static/img/logo.png" />
            <img style="min-height:220px"
                 src="https://p12.mmdb.cc/file/20261002/441354166995/00159791.jpg" />
            <img style="min-height:220px"
                 src="//p12.mmdb.cc/file/20261002/441354166995/00208074.jpg" />
            <img class="waitpic lazyimg"
                 data-src="https://cdn20.mmdb.cc/file/20251223/440610471951/0.jpg" />
            <img src="https://p12.mmdb.cc/file/20261002/441354166995/00398140.jpg?v=1" />
        """.trimIndent()
        val urls = OnlineSourceRules.parseMeirentuImageUrls(
            html, albumId = 441354166995L, pageUrl = "https://meirentu.club/pic/441354166995.html"
        )
        assertEquals(3, urls.size)
        assertTrue(urls[0].endsWith("/441354166995/00159791.jpg"))
        assertTrue(urls[1].startsWith("https://p12.mmdb.cc/"))
        assertTrue(urls[2].endsWith("/441354166995/00398140.jpg?v=1"))
    }

    @Test
    fun meirentuAlbumPageUrlFollowsTheSitesPaginationScheme() {
        assertEquals(
            "https://meirentu.club/pic/123.html",
            OnlineSourceRules.meirentuAlbumPageUrl("https://meirentu.club/", 123L, 1)
        )
        assertEquals(
            "https://meirentu.club/pic/123-3.html",
            OnlineSourceRules.meirentuAlbumPageUrl("https://meirentu.club/group/xiuren.html", 123L, 3)
        )
    }

    @Test
    fun originOfDropsThePathAndKeepsThePort() {
        assertEquals("https://meirentu.club", OnlineSourceRules.originOf("https://meirentu.club/pic/1.html"))
        assertEquals("https://nas.example.com:8443", OnlineSourceRules.originOf("https://nas.example.com:8443/dav/x"))
        assertNull(OnlineSourceRules.originOf("not a url"))
    }

    @Test
    fun pagesPerAlbumAndMaxPerRunAreClamped() {
        // 0 (older rows / not set) falls back to the product defaults.
        assertEquals(2, OnlineSourceRules.normalizePagesPerAlbum(0))
        assertEquals(2, OnlineSourceRules.normalizePagesPerAlbum(-3))
        assertEquals(10, OnlineSourceRules.normalizePagesPerAlbum(99))
        assertEquals(8, OnlineSourceRules.normalizeMaxPerRun(0))
        assertEquals(8, OnlineSourceRules.normalizeMaxPerRun(-1))
        assertEquals(50, OnlineSourceRules.normalizeMaxPerRun(1000))
    }

    @Test
    fun selectedImagesRoundTripWithBlanksAndDuplicatesRemoved() {
        val raw = "https://p12.mmdb.cc/a.jpg\n\n https://p12.mmdb.cc/b.jpg \nhttps://p12.mmdb.cc/a.jpg\n"
        val parsed = OnlineSourceRules.parseSelectedImages(raw)
        val expected = listOf("https://p12.mmdb.cc/a.jpg", "https://p12.mmdb.cc/b.jpg")
        assertEquals(expected, parsed)
        assertEquals("", OnlineSourceRules.encodeSelectedImages(emptyList()))
        assertEquals(
            expected,
            OnlineSourceRules.parseSelectedImages(OnlineSourceRules.encodeSelectedImages(expected))
        )
    }

    @Test
    fun meirentuAlbumCardsCarryCoverAndLabel() {
        val html = """
            <li><a href="/pic/441354166995.html">
              <img class="lazyimg" data-src="https://cdn20.mmdb.cc/file/20261002/441354166995/0.jpg"
                   alt="鱼子酱Fish" /></a></li>
            <li><a href="/pic/227192094259.html">
              <img class="lazyimg" data-src="//cdn20.mmdb.cc/file/20261001/227192094259/0.jpg"
                   alt="王馨瑶" /></a></li>
        """.trimIndent()
        val albums = OnlineSourceRules.parseMeirentuAlbums(html, "https://meirentu.club/")
        assertEquals(2, albums.size)
        assertEquals(441354166995L, albums[0].id)
        assertEquals("https://cdn20.mmdb.cc/file/20261002/441354166995/0.jpg", albums[0].coverUrl)
        assertEquals("鱼子酱Fish", albums[0].label)
        assertEquals("王馨瑶", albums[1].label)
        assertEquals("https://cdn20.mmdb.cc/file/20261001/227192094259/0.jpg", albums[1].coverUrl)
    }

    @Test
    fun htmlEntitiesInAlbumLabelsAreDecoded() {
        assertEquals("周妍希&绮里嘉", OnlineSourceRules.decodeHtmlEntities("周妍希&amp;绮里嘉"))
        assertEquals("a<b>c", OnlineSourceRules.decodeHtmlEntities("a&lt;b&gt;c"))
        assertEquals("it's", OnlineSourceRules.decodeHtmlEntities("it&#39;s"))
        // &amp;lt; must decode to the literal "&lt;", not to "<".
        assertEquals("&lt;", OnlineSourceRules.decodeHtmlEntities("&amp;lt;"))
        assertEquals("plain", OnlineSourceRules.decodeHtmlEntities("plain"))
    }
}
