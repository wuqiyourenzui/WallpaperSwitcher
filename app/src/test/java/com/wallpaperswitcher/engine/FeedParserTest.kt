package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RSS 2.0 / Atom / JSON Feed parsing for the subscription reader. */
class FeedParserTest {

    @Test
    fun rssItemsCarryTitleLinkDateAndEnclosureImage() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>Feed</title>
                <item>
                  <title>First &amp; best</title>
                  <link>https://example.com/1</link>
                  <description>&lt;p&gt;Hello &lt;img src="/img/a.jpg"&gt;&lt;/p&gt;</description>
                  <pubDate>Tue, 06 Oct 2026 12:34:56 GMT</pubDate>
                  <guid>guid-1</guid>
                </item>
                <item>
                  <title>Second</title>
                  <link>https://example.com/2</link>
                  <description>plain</description>
                  <media:content url="https://cdn.example.com/b.jpg" />
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val articles = FeedParser.parse(xml)
        assertEquals(2, articles.size)
        assertEquals("First & best", articles[0].title)
        assertEquals("https://example.com/1", articles[0].link)
        assertEquals("guid-1", articles[0].guid)
        assertTrue(articles[0].publishedAt > 0L)
        // Relative <img src> is resolved against the item link.
        assertEquals("https://example.com/img/a.jpg", articles[0].imageUrl)
        assertEquals("https://cdn.example.com/b.jpg", articles[1].imageUrl)
    }

    @Test
    fun atomEntriesUseHrefLinksAndMediaThumbnail() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:media="http://search.yahoo.com/mrss/">
              <title>Atom feed</title>
              <entry>
                <title>Entry</title>
                <link rel="alternate" href="https://example.com/atom/1" />
                <id>tag:example.com,2026:1</id>
                <updated>2026-10-06T12:34:56Z</updated>
                <summary>Summary text</summary>
                <media:thumbnail url="https://cdn.example.com/t.jpg" />
              </entry>
            </feed>
        """.trimIndent()
        val articles = FeedParser.parse(xml)
        assertEquals(1, articles.size)
        assertEquals("Entry", articles[0].title)
        assertEquals("https://example.com/atom/1", articles[0].link)
        assertEquals("tag:example.com,2026:1", articles[0].guid)
        assertEquals("https://cdn.example.com/t.jpg", articles[0].imageUrl)
        assertTrue(articles[0].publishedAt > 0L)
    }

    @Test
    fun jsonFeedIsParsed() {
        val json = """
            {"version":"https://jsonfeed.org/version/1.1",
             "title":"JSON",
             "items":[
               {"id":"1","url":"https://example.com/j/1","title":"J1",
                "summary":"summary","content_html":"<p>body</p>",
                "image":"https://cdn.example.com/j.jpg",
                "date_published":"2026-10-06T12:34:56Z"}
             ]}
        """.trimIndent()
        val articles = FeedParser.parse(json)
        assertEquals(1, articles.size)
        assertEquals("J1", articles[0].title)
        assertEquals("https://example.com/j/1", articles[0].link)
        assertEquals("https://cdn.example.com/j.jpg", articles[0].imageUrl)
        assertTrue(articles[0].publishedAt > 0L)
    }

    @Test
    fun stripHtmlRemovesTagsAndDecodesEntities() {
        assertEquals(
            "Hello world & more",
            FeedParser.stripHtml("<p>Hello <b>world</b> &amp; more</p>")
        )
        assertEquals("", FeedParser.stripHtml(""))
    }

    @Test
    fun firstImageUrlFindsSrcAndDataSrc() {
        assertEquals(
            "https://x/a.jpg",
            FeedParser.firstImageUrl("""<p><img class="lazy" data-src="https://x/a.jpg"></p>""")
        )
        assertEquals(
            "https://x/b.png",
            FeedParser.firstImageUrl("""<img src="https://x/b.png"/>""")
        )
    }

    @Test
    fun imageUrlPrefersLazyDataAttributesOverAPlaceholderSrc() {
        // 懒加载画廊：src 是占位图（data: URI 会被跳过），真实地址在 data-src。
        assertEquals(
            "https://x/big.jpg",
            FeedParser.firstImageUrl("""<img src="data:image/gif;base64,R0lGOD" data-src="https://x/big.jpg">""")
        )
        // 占位 src 是普通小图、data-* 在后面出现时也必须由 data-* 赢。
        assertEquals(
            "https://x/orig.jpg",
            FeedParser.firstImageUrl("""<img src="https://x/thumb.jpg" data-original="https://x/orig.jpg">""")
        )
    }

    @Test
    fun imageUrlFallsBackToSrcsetBeforePlainSrc() {
        // 原图优先：srcset 取**最大**的一项（列表里的 URL 就是下载地址）。
        assertEquals(
            "https://x/large.jpg",
            FeedParser.firstImageUrl(
                """<img src="https://x/thumb.jpg" srcset="https://x/small.jpg 300w, https://x/large.jpg 1200w">"""
            )
        )
        assertEquals(
            "https://x/b.jpg",
            FeedParser.firstImageUrl(
                """<img src="https://x/thumb.jpg" data-srcset="https://x/a.jpg 480w, https://x/b.jpg 960w">"""
            )
        )
    }

    @Test
    fun srcsetWithoutDescriptorsTakesTheLastEntry() {
        // 没有描述符时按约定"最后一个更大"，取最后一项。
        assertEquals(
            "https://x/2x.jpg",
            FeedParser.firstImageUrl(
                """<img src="https://x/t.jpg" srcset="https://x/1x.jpg 1x, https://x/2x.jpg 2x">"""
            )
        )
        assertEquals(
            "https://x/full.jpg",
            FeedParser.firstImageUrl(
                """<img src="https://x/t.jpg" srcset="https://x/small.jpg, https://x/full.jpg">"""
            )
        )
    }

    @Test
    fun malformedFeedYieldsNothing() {
        assertTrue(FeedParser.parse("<rss").isEmpty())
        assertTrue(FeedParser.parse("{oops").isEmpty())
    }

    @Test
    fun doctypeEntitiesAreRejected() {
        // XXE: a feed comes from the network and must never read a local file.
        // disallow-doctype-decl turns this document into a parse error.
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel>
              <item><title>&xxe;</title><link>https://example.com/1</link></item>
            </channel></rss>
        """.trimIndent()
        assertTrue(FeedParser.parse(xml).isEmpty())
    }
}
