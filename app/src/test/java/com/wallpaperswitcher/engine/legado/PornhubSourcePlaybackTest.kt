package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.Json
import com.wallpaperswitcher.ui.screens.allMediaUrls
import com.wallpaperswitcher.ui.screens.bareMediaUrl
import com.wallpaperswitcher.ui.screens.extractVideoUrl
import com.wallpaperswitcher.ui.screens.resolveProtocolRelativeUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Pornhub视频」源的提取回归（真机上报「视频无法播放」的那一个）。
 *
 * 源的 `ruleContent` 是一段 `@js:`：从文章页里抠出 `"mediaDefinitions":[ … ]`
 * 的 JSON，挑高度 ≤1080 的最高档 hls，返回 `videoUrl`。这条规则本身是好的，断在
 * **返回值**上：
 *
 *  1. Pornhub 的 `videoUrl` 是**协议相对地址**（`//ev-h.phncdn.com/…`）。它过不了
 *     `https?://` 判定（于是系统认为"正文里没有媒体"→ 什么都不播），也过不了
 *     ExoPlayer。现在按文章地址补 scheme 后再交给原生播放器。
 *  2. 规则里 `/"mediaDefinitions":(\[)/` 要求冒号后**紧跟** `[`（没有空格），
 *     所以这里必须用真实形态的页面片段，否则规则本身就返回空串。
 */
class PornhubSourcePlaybackTest {

    private val ruleContent = "@js:(function(){var html=String(result);" +
        "var m=html.match(/\"mediaDefinitions\":(\\[)/);if(!m)return '';" +
        "var i=m.index+m[0].length-1;var depth=0,end=-1;" +
        "for(var j=i;j<html.length;j++){var c=html[j];" +
        "if(c==='[')depth++;else if(c===']'){depth--;if(depth===0){end=j+1;break;}}}" +
        "if(end<0)return '';var arr=[];try{arr=JSON.parse(html.substring(i,end));}catch(e){return '';}" +
        "var h=arr.filter(function(d){return d.format==='hls'&&d.height>0&&d.height<=1080;})" +
        ".sort(function(a,b){return b.height-a.height;});" +
        "var pick=h.length?h[0]:arr.filter(function(d){return d.format==='hls'&&d.height>0;})" +
        ".sort(function(a,b){return a.height-b.height;})[0];" +
        "return pick?pick.videoUrl:'';})()"

    private val source = RssSource(
        id = 50L,
        name = "Pornhub视频",
        url = "https://cn.pornhub.com/",
        rawJson = """{"sourceUrl":"https://cn.pornhub.com/","ruleArticles":"li.videoblock",""" +
            """"ruleTitle":"span.title a@text","ruleLink":"a.linkVideoThumb@href",""" +
            """"ruleContent":${Json.encode(ruleContent)}}""",
    )

    private val articleUrl = "https://cn.pornhub.com/view_video.php?viewkey=ph1"

    /** Pornhub 视频页里 `mediaDefinitions` 的真实形态（冒号后没有空格）。 */
    private fun page(mediaUrl: String) = """
        <!doctype html><html><body><script>
        var flashvars_123 = {"video_id":"123",
        "mediaDefinitions":[{"format":"hls","videoUrl":"$mediaUrl","quality":"","height":1080},
                            {"format":"mp4","videoUrl":"https://ev-h.phncdn.com/mp4/720.mp4","height":720}],
        "other": 1};
        </script></body></html>
    """.trimIndent()

    private fun contentOf(mediaUrl: String, baseUrl: String = articleUrl): String {
        val rules = LegadoRss.parseRules(source)!!
        return LegadoRuleEngine(baseUrl = baseUrl)
            .getString(rules.ruleContent!!, page(mediaUrl), joinAll = true)
    }

    /** 规则本身能取到地址（真机日志里断在下一步，不是断在这里）。 */
    @Test
    fun sourceRulePicksTheBestHlsVariant() {
        assertEquals(
            "https://ev-h.phncdn.com/hls/123/master.m3u8",
            contentOf("https://ev-h.phncdn.com/hls/123/master.m3u8"),
        )
        // Pornhub 实际给的是协议相对地址：规则原样返回，不补 scheme。
        assertEquals(
            "//ev-h.phncdn.com/hls/123/master.m3u8",
            contentOf("//ev-h.phncdn.com/hls/123/master.m3u8"),
        )
    }

    /**
     * 真机上报的那条路径：`videoUrl` 是协议相对地址时，先补成绝对地址再交给
     * 原生播放器；没有页面地址就补不上（也不猜），此时不应给出播不了的地址。
     */
    @Test
    fun protocolRelativeVideoUrlBecomesPlayable() {
        val content = contentOf("//ev-h.phncdn.com/hls/123/master.m3u8")
        assertEquals("//ev-h.phncdn.com/hls/123/master.m3u8", content)

        assertEquals(
            "https://ev-h.phncdn.com/hls/123/master.m3u8",
            bareMediaUrl(content, articleUrl),
        )
        assertEquals(
            "https://ev-h.phncdn.com/hls/123/master.m3u8",
            extractVideoUrl(content, articleUrl),
        )
        assertEquals(
            listOf("https://ev-h.phncdn.com/hls/123/master.m3u8"),
            allMediaUrls(content, articleUrl),
        )
        // 没有 base 时补不上 scheme：宁可当作"没有媒体"（退回网页），
        // 也不要递一个 ExoPlayer 用不了的地址。
        assertEquals(emptyList<String>(), allMediaUrls(content, ""))
        assertNull(extractVideoUrl(content, null))
    }

    /** 协议相对地址的补全规则（含"绝对地址不能被二次补全"）。 */
    @Test
    fun protocolRelativeResolutionRules() {
        assertEquals(
            "https://ev-h.phncdn.com/hls/123/master.m3u8",
            resolveProtocolRelativeUrls("//ev-h.phncdn.com/hls/123/master.m3u8", articleUrl),
        )
        // 已带 scheme 的绝对地址补了会变成 https://https://…，必须原样保留。
        assertEquals(
            "https://cdn.example.com/a.m3u8",
            resolveProtocolRelativeUrls("https://cdn.example.com/a.m3u8", articleUrl),
        )
        // http 的文章页 -> http 的流（内网/明文源）。
        assertEquals(
            "http://cdn.example.com/a.m3u8",
            resolveProtocolRelativeUrls("//cdn.example.com/a.m3u8", "http://example.com/a"),
        )
        // 没有页面地址就原样返回（不猜协议）。
        assertEquals(
            "//cdn.example.com/a.m3u8",
            resolveProtocolRelativeUrls("//cdn.example.com/a.m3u8", null),
        )
        // 混排：绝对地址 + 协议相对地址各归各的。
        assertEquals(
            """<video src="https://a.com/1.mp4"></video><script>s=["https://b.com/2.m3u8"]</script>""",
            resolveProtocolRelativeUrls(
                """<video src="https://a.com/1.mp4"></video><script>s=["//b.com/2.m3u8"]</script>""",
                articleUrl,
            ),
        )
    }

    /** 正文里虽然有 `mediaDefinitions`，但确实没有 hls 档位：规则返回空串。 */
    @Test
    fun articleWithoutHlsProducesNoUrl() {
        val noHls = """<html><body><script>var x={"mediaDefinitions":[
            {"format":"mp4","videoUrl":"https://ev-h.phncdn.com/mp4/720.mp4","height":720}]};</script></body></html>"""
        assertEquals("", contentOf(noHls))
        assertTrue(extractVideoUrl("", articleUrl) == null)
    }
}
