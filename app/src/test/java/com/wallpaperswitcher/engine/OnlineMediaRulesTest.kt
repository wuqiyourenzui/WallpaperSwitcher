package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网盘来源的"能不能当壁纸"判定（见 OnlineSourceRules.mediaExtension / tooLargeToFetch /
 * safeRemoteName）。
 *
 * 这三条是网盘接视频时最容易出事的地方，而且都能在纯函数层面钉住：
 *  - 白名单放错 → 视频永远下不下来，或者内置图源开始抓 mp4；
 *  - 体积护栏算反 → 要么把 4K 长片拖满磁盘，要么把正常图片当"太大"跳过；
 *  - 文件名不削 basename → 服务端给的 href 能写到目录之外。
 */
class OnlineMediaRulesTest {

    // --- 扩展名/类型判定 -----------------------------------------------------

    @Test
    fun videosAreSkippedUnlessExplicitlyAllowed() {
        // 历史行为：内置源 / 老配置只看图片，mp4 必须被判成"不是图片"。
        assertNull(OnlineSourceRules.mediaExtension("https://nas/a.mp4", null, includeVideos = false))
        assertEquals(
            "jpg",
            OnlineSourceRules.mediaExtension("https://nas/a.jpg", null, includeVideos = false)
        )
        // 打开视频后，同一个地址就是可下载的视频。
        assertEquals(
            "mp4",
            OnlineSourceRules.mediaExtension("https://nas/a.mp4", null, includeVideos = true)
        )
    }

    @Test
    fun contentLengthAndQueryStringsDoNotConfuseTheExtension() {
        // 网盘直链常带签名参数；大小写也要能认。
        assertEquals(
            "mp4",
            OnlineSourceRules.mediaExtension("https://nas/a.MP4?sign=x&e=1", null, true)
        )
        // 没有后缀时以 Content-Type 为准（AList/OpenList 的 /d/ 直链就是这种形态）。
        assertEquals(
            "mp4",
            OnlineSourceRules.mediaExtension("https://nas/d/abc", "video/mp4", true)
        )
        assertEquals(
            "jpg",
            OnlineSourceRules.mediaExtension("https://nas/d/abc", "image/jpeg", true)
        )
    }

    @Test
    fun unsupportedFormsAreStillNull() {
        // avi/3gp 在 MediaTypes 清单里，但引擎解不出来 → 网盘白名单不收。
        assertNull(OnlineSourceRules.mediaExtension("https://nas/a.avi", null, true))
        assertNull(OnlineSourceRules.mediaExtension("https://nas/page.html", "text/html", true))
    }

    @Test
    fun videoExtensionMatchesTheWhitelist() {
        assertTrue(OnlineSourceRules.isVideoExtension("mp4"))
        assertTrue(OnlineSourceRules.isVideoExtension("MKV"))
        assertFalse(OnlineSourceRules.isVideoExtension("jpg"))
        assertFalse(OnlineSourceRules.isVideoExtension("avi"))
    }

    // --- 体积护栏 -------------------------------------------------------------

    @Test
    fun unknownSizeIsNeverRejected() {
        // 服务端没给大小（0）= 不知道，不能据此跳过，否则所有 WebDAV 无 length 的
        // 服务器都会一张图都下不到。
        assertFalse(OnlineSourceRules.tooLargeToFetch(size = 0L, maxBytes = 300L * 1024 * 1024))
        assertFalse(OnlineSourceRules.tooLargeToFetch(size = 100L, maxBytes = 0L))
    }

    @Test
    fun oversizedItemsAreRejectedBeforeDownloading() {
        val cap = 300L * 1024 * 1024
        assertTrue(OnlineSourceRules.tooLargeToFetch(size = cap + 1, maxBytes = cap))
        assertFalse(OnlineSourceRules.tooLargeToFetch(size = cap, maxBytes = cap))
    }

    // --- 文件名安全 -----------------------------------------------------------

    @Test
    fun onlyTheBaseNameIsKept() {
        assertEquals("c.mp4", OnlineSourceRules.safeRemoteName("/a/b/c.mp4"))
        assertEquals("c.mp4", OnlineSourceRules.safeRemoteName("c.mp4"))
    }

    @Test
    fun traversalAndControlCharactersAreRejected() {
        assertNull(OnlineSourceRules.safeRemoteName("../../etc/passwd"))
        assertNull(OnlineSourceRules.safeRemoteName("a\\b.mp4"))
        assertNull(OnlineSourceRules.safeRemoteName(""))
        assertNull(OnlineSourceRules.safeRemoteName("bad\u0001name.mp4"))
    }

    // --- 局域网明文（OpenList 默认 HTTP:5244）--------------------------------

    @Test
    fun lanCleartextIsAllowedForOpenList() {
        assertEquals(
            OnlineSourceRules.EndpointPolicy.OK,
            OnlineSourceRules.endpointPolicy("http://192.168.1.10:5244/dav")
        )
        assertEquals(
            OnlineSourceRules.EndpointPolicy.OK,
            OnlineSourceRules.endpointPolicy("http://openlist.local:5244/api/fs/list")
        )
        // 公网域名走明文仍然被拒（仓库的隐私模型）。
        assertEquals(
            OnlineSourceRules.EndpointPolicy.NEEDS_HTTPS,
            OnlineSourceRules.endpointPolicy("http://example.com:5244/dav")
        )
    }
}
