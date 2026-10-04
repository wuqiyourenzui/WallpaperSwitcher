package com.wallpaperswitcher.ui.screens

import com.wallpaperswitcher.engine.legado.LegadoRss
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅源视频地址提取的回归（重写后的播放路径）。
 *
 * 视频源的 `ruleContent` 求值结果只有两种形态，两种最后都收敛成「一条地址交给
 * 原生播放器」（见 [RssVideoScreen]）：
 *
 *  - **一条媒体地址**：91porn / Rule34 / Pornhub 的 `@js:` 规则都是这种；
 *  - **一整段 HTML**：h视频的 hls.js 播放页，地址在 `<video>` / JS 里。
 *
 * 这个文件把提取结果钉住：源文件或规则形态变了，先在这里炸。
 */
class RssVideoUrlTest {

    // ---------- 只给一条地址的源 ----------

    @Test
    fun bareMp4IsTheVideoUrl() {
        val url = "https://cdn.91porn.example/videos/abc123.mp4"
        assertEquals(url, extractVideoUrl(url))
        // 前后空白也算（规则输出常常带换行）。
        assertEquals(url, extractVideoUrl("\n  $url  \n"))
        assertEquals(url, bareMediaUrl("  $url  "))
    }

    @Test
    fun barePlaylistIsTheVideoUrl() {
        val playlist = "https://cdn.rule34video.example/video/12345/hls/index.m3u8?sign=1"
        assertEquals(playlist, extractVideoUrl(playlist))
        assertTrue(isPlaylistUrl(playlist))
        assertFalse(isPlaylistUrl("https://cdn.example.com/video/abc.mp4"))
    }

    /** 正文是一整段 HTML（h视频）：从标记里抠出那条地址。 */
    @Test
    fun playerPageHtmlYieldsTheEmbeddedUrl() {
        val html = """
            <html><head>
              <meta name="referrer" content="never"/>
              <script src="https://unpkg.com/hls.js@1.4.3/dist/hls.min.js"></script>
            </head><body>
              <div class="container"><div class="title">示例</div>
              <video id="video" controls autoplay muted loop></video></div>
            <script>
            const v=document.getElementById('video'),s=[
              "https://cdn.senlin2026.com/20260903/BhNbafSa/index.m3u8",
              ""];
            let c=0;
            </script>
            </body></html>
        """.trimIndent()
        assertEquals(
            "https://cdn.senlin2026.com/20260903/BhNbafSa/index.m3u8",
            extractVideoUrl(html),
        )
        assertTrue(hasPlayerTag(html))
    }

    /** 没有媒体地址（图片站 / 需要站点脚本的页面）：交给调用方退回网页。 */
    @Test
    fun pageWithoutMediaHasNoVideoUrl() {
        assertNull(extractVideoUrl("<p>没有任何媒体地址的正文</p>"))
        assertNull(extractVideoUrl(""))
        assertNull(extractVideoUrl("   "))
        assertEquals(emptyList<String>(), allMediaUrls("<p>hi</p>"))
    }

    // ---------- 源类型判定（决定走播放器还是图片收集页） ----------

    @Test
    fun videoSourceIsDetectedFromTheArticleBody() {
        val base = "https://example.com/video/1"
        // 一条媒体地址
        assertTrue(isVideoArticle("https://cdn.example.com/a.mp4", base))
        // h视频 那种自带播放器的 HTML
        assertTrue(isVideoArticle(H_PLAYER_PAGE, base))
        // 被 CDN 挡了、规则抠不出地址，但页面里有播放器钩子（真机上就是这样）
        assertTrue(
            isVideoArticle(
                """<html><body><script>var player = {video_url: ""};</script>
                   <div id="player" data-video="https://x/1.m3u8"></div></body></html>""",
                base,
            ),
        )
        assertTrue(
            isVideoArticle(
                """<html><script>flashvars={mediaDefinitions:[{"format":"hls"}]}</script></html>""",
                base,
            ),
        )
        assertTrue(
            isVideoArticle("""<a href="https://rule34video.com/get_file/51/x/1_720p.mp4">dl</a>""", base),
        )
    }

    @Test
    fun imageSourceStaysAnImageSource() {
        val base = "https://example.com/meitu/1.html"
        assertFalse(isVideoArticle("", base))
        assertFalse(isVideoArticle("   ", base))
        // 图集正文：只有 <img>，没有媒体地址也没有播放器钩子
        assertFalse(
            isVideoArticle(
                """<html><p>正文文字</p><img src="https://img.example.com/1.jpg">
                   <img src="https://img.example.com/2.jpg"></html>""",
                base,
            ),
        )
        // 没有 src 的 <video> 标签不算（网页里常见的空占位）
        assertFalse(isVideoArticle("""<html><video id="v"></video></html>""", base))
    }

    /** h视频 的 `ruleContent`（节选自仓库里的 rssSource_h视频.json，规则已求值）。 */
    private val H_PLAYER_PAGE = """
        <html><head><script src="https://unpkg.com/hls.js@1.4.3/dist/hls.min.js"></script></head>
        <body><div class="container"><video id="video" controls autoplay muted loop></video></div>
        <script>
        const v=document.getElementById('video'),s=[
          "https://cdn.senlin2026.com/20260903/BhNbafSa/index.m3u8",
          ""];
        </script></body></html>
    """.trimIndent()

    /**
     * 页面嗅探的挑选逻辑：站点页面上先到的是前贴片广告（真机实测 XAnime 一开始
     * 抓到 `cdn1.traffichaus.com/.../black_video.mp4`），真正片是稍后请求的那条。
     */
    @Test
    fun snifferPrefersTheRealStreamOverAds() {
        assertTrue(streamScore("https://cdn.example.com/hls/master.m3u8") > 0)
        assertTrue(
            streamScore("https://cdn1.traffichaus.com/scripts/v5.20.5/black_video.mp4") <= 0,
        )
        // 广告清单也要排在正片文件之后：分数 = 10(清单) - 100(广告)
        assertTrue(
            streamScore("https://x.com/ads/preroll.m3u8") <
                streamScore("https://watch.example.com/videos/70352.mp4"),
        )
        // 正片清单最高
        assertTrue(
            streamScore("https://watch.example.com/videos/70352.m3u8") >
                streamScore("https://watch.example.com/videos/70352.mp4"),
        )
    }

    // ---------- 协议相对地址（Pornhub 的形态） ----------

    @Test
    fun protocolRelativeUrlIsResolvedAgainstTheArticle() {
        val base = "https://cn.pornhub.com/view_video.php?viewkey=ph1"
        val content = "//ev-h.phncdn.com/hls/123/master.m3u8"
        assertEquals(
            "https://ev-h.phncdn.com/hls/123/master.m3u8",
            extractVideoUrl(content, base),
        )
        // 没有页面地址就不猜协议 —— 也抠不出可播地址。
        assertNull(extractVideoUrl(content, null))
        assertNull(extractVideoUrl(content, ""))
    }

    @Test
    fun absoluteUrlsAreNeverDoublePrefixed() {
        val base = "https://cn.pornhub.com/view_video.php?viewkey=ph1"
        assertEquals(
            "https://cdn.example.com/a.m3u8",
            resolveProtocolRelativeUrls("https://cdn.example.com/a.m3u8", base),
        )
        // 同一个字符串里绝对地址与协议相对地址混排（页面 HTML 的常见形态）。
        val mixed = """<video src="https://a.com/1.mp4"></video><script>s=["//b.com/2.m3u8"]</script>"""
        val resolved = resolveProtocolRelativeUrls(mixed, base)
        assertTrue(resolved.contains("https://a.com/1.mp4"))
        assertTrue(resolved.contains("https://b.com/2.m3u8"))
        assertEquals(
            listOf("https://a.com/1.mp4", "https://b.com/2.m3u8"),
            allMediaUrls(mixed, base),
        )
    }

    /** 目录式路径（`…_720P_4000K_x.mp4/master.m3u8`）不能被截断成 `.mp4`。 */
    @Test
    fun directoryStylePathKeepsThePlaylistName() {
        val url = "https://ev-h.phncdn.com/hls/c6251/videos/202609/02/61062705/" +
            "1080P_4000K_61062705.mp4/master.m3u8?validfrom=1&validto=2"
        assertEquals(url, extractVideoUrl(url))
        assertEquals("m3u8", mediaExtensionOf(url))
        assertTrue(isPlaylistUrl(url))
    }

    // ---------- 仓库里的真实源文件 ----------

    /**
     * 三个真实源文件都是**数组形式**（`[{…}]`），且都带 `ruleContent`。
     * 这里直接读仓库里的 `rssSource_*.json`：源文件被改坏（规则名写错、类型变了）
     * 时，这个测试比任何手写样例都早发现。
     */
    @Test
    fun repositoryVideoSourcesStillCarryTheirContentRules() {
        val expected = mapOf(
            "rssSource_h视频.json" to "html",
            "rssSource_91porn视频.json" to "bare",
            "rssSource_Rule34视频.json" to "bare",
        )
        for ((file, shape) in expected) {
            val text = readRepoFile(file)
            assertNotNull("$file 读不到（测试工作目录应为 app/）", text)
            val fields = LegadoRss.sourceFields(text!!)
            assertNotNull("$file 不是阅读源的 JSON 形态", fields)
            val ruleContent = fields!!["ruleContent"] as? String
            assertNotNull("$file 的 ruleContent 丢了", ruleContent)
            assertTrue("$file 的 ruleContent 为空", ruleContent!!.isNotBlank())
            // `91porn` 的 `@js:` 里也有 `<video[^>]+src=` 这样的正则文本，
            // 所以只认「行首的 <video 标签」——规则输出 HTML 的形态。
            val isHtml = Regex("""\n\s*<video\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(ruleContent)
            assertEquals("$file 的正文形态变了", shape, if (isHtml) "html" else "bare")
            assertNotNull("$file 的 ruleArticles 丢了", fields["ruleArticles"] as? String)
        }
    }

    /** 源文件相对路径：Gradle 单测的工作目录是模块目录（app/）。 */
    private fun readRepoFile(name: String): String? {
        val candidates = listOf(File("../$name"), File(name), File("app/../$name"))
        return candidates.firstOrNull { it.isFile }?.readText()
    }
}
