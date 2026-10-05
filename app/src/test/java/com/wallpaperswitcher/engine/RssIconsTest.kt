package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 订阅源网格的站点图标地址推导。 */
class RssIconsTest {

    @Test
    fun `an https feed maps to the root favicon`() {
        assertEquals(
            "https://a.example/favicon.ico",
            RssIcons.iconUrl("https://a.example/feed/atom.xml"),
        )
    }

    @Test
    fun `an http feed keeps its scheme`() {
        // 与源同协议：http 站点去要 https 的图标多半拿不到。
        assertEquals(
            "http://a.example/favicon.ico",
            RssIcons.iconUrl("http://a.example/feed"),
        )
    }

    @Test
    fun `a custom port is kept and a default port is not`() {
        assertEquals(
            "https://a.example:8443/favicon.ico",
            RssIcons.iconUrl("https://a.example:8443/rss"),
        )
        assertEquals(
            "https://a.example/favicon.ico",
            RssIcons.iconUrl("https://a.example:443/rss"),
        )
        assertEquals(
            "http://a.example/favicon.ico",
            RssIcons.iconUrl("http://a.example:80/rss"),
        )
    }

    @Test
    fun `host only source urls still work`() {
        assertEquals("https://meirentu.club/favicon.ico", RssIcons.iconUrl("https://meirentu.club"))
    }

    @Test
    fun `surface whitespace is ignored`() {
        assertEquals(
            "https://a.example/favicon.ico",
            RssIcons.iconUrl("  https://a.example/feed  "),
        )
    }

    @Test
    fun `non http sources fall back to a placeholder`() {
        assertNull(RssIcons.iconUrl("legado://import/rssSource?src=abc"))
        assertNull(RssIcons.iconUrl("a.example/feed"))
        assertNull(RssIcons.iconUrl(""))
        assertNull(RssIcons.iconUrl("https:///no-host"))
    }
}
