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
    fun malformedFeedYieldsNothing() {
        assertTrue(FeedParser.parse("<rss").isEmpty())
        assertTrue(FeedParser.parse("{oops").isEmpty())
    }
}
