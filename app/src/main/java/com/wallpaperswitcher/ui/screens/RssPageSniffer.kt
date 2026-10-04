package com.wallpaperswitcher.ui.screens

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.wallpaperswitcher.data.RssArticle
import com.wallpaperswitcher.util.AppLog

private const val TAG = "RssSniff"

/** 打开网页后等站点播放器拉起流的耐心（毫秒）。 */
internal const val SNIFF_TIMEOUT_MS = 12_000L

/**
 * 广告/占位流的特征。视频站的页面上几乎总有前贴片广告（真机实测 XAnime 一开始
 * 抓到的是 `cdn1.traffichaus.com/.../black_video.mp4`），照抓照播的话用户看到的是
 * 一段黑屏广告；真正片是稍后由站点播放器请求的另一个地址。
 */
private val AD_STREAM = Regex(
    """(traffichaus|exoclick|juicyads|popads|adnium|tsyndicate|black_video|/_?ads?/|""".trimIndent() +
        """[?&](?:utm_|ad_|zone)|pre[-_]?roll)""",
    RegexOption.IGNORE_CASE
)

/** 选择优先级：清单 > 整段文件；广告流排最后。 */
internal fun streamScore(url: String): Int {
    var score = 0
    if (isPlaylistUrl(url)) score += 10
    if (AD_STREAM.containsMatchIn(url)) score -= 100
    return score
}

/** 抓到的流：地址 + 站点播放器发它时用的请求头。 */
internal typealias SniffedStream = Pair<String, Map<String, String>>

/**
 * 嗅探用的 WebViewClient：把页面请求过的媒体地址收进 [pending]
 * （HLS 分片不算，那不是一条可播的流）。
 *
 * 用在「网页源」的浏览器页上：文章本来就是在浏览器里打开的，顺手看一眼站点
 * 播放器请求了什么 —— 被 CDN 挑战页挡住、规则抠不出地址的视频源，就靠这个拿到
 * 真正片地址。
 */
internal fun sniffingClient(
    article: RssArticle,
    pending: MutableList<SniffedStream>,
): WebViewClient = object : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val target = resolveProtocolRelativeUrls(request.url?.toString().orEmpty(), article.link)
        val ext = mediaExtensionOf(target)
        val path = target.substringBefore('?').lowercase()
        val isSegment = path.endsWith(".ts") || path.endsWith(".m4s")
        if (ext != null && !isSegment && pending.none { it.first == target }) {
            val captured = LinkedHashMap<String, String>()
            try {
                for ((key, value) in request.requestHeaders) {
                    if (key.isNotBlank() && value.isNotBlank() && !isSkippedHeader(key)) {
                        captured[key] = value
                    }
                }
            } catch (_: Throwable) {
            }
            pending.add(target to captured)
            AppLog.d(TAG, "sniffed ${target.take(140)}")
        }
        return null
    }
}

/**
 * 从已抓到的候选里挑一条「像正片」的：清单优先、广告流排除。
 * 返回 null 表示目前只有广告/普通文件，值得再等一会儿。
 */
internal fun bestStream(pending: List<SniffedStream>): SniffedStream? =
    pending.maxByOrNull { streamScore(it.first) }?.takeIf { streamScore(it.first) > 0 }
