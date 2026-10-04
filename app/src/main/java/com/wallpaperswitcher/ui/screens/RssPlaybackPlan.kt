package com.wallpaperswitcher.ui.screens

import com.wallpaperswitcher.engine.OnlineSourceRules

/**
 * 订阅源正文里的视频地址提取（纯函数，无 Android 依赖，单测直接跑）。
 *
 * 视频源的 `ruleContent` 求值结果只有两种形态：
 *  - **一条媒体地址**（91porn / Rule34 / Pornhub 的 `@js:` 规则都是这种，
 *    空白/换行包裹也算）；
 *  - **一整段 HTML**（h视频的 hls.js 播放页），地址藏在 `<video src>` / JS 里。
 *
 * 之前的实现把这两条路和「网页里嗅探 + 合成 hls.js 播放页 + 图片收集」混在一起，
 * 真机上踩了一串坑（WebView 里 `readyState=4` 却全黑、合成文档 `body` 高 0、
 * 失败重试风暴）。现在只做一件事：**把地址抠出来交给原生播放器**。
 */

/**
 * 正文里媒体地址的判定。
 *
 * 三段都要，缺一个就出错：
 *  - `(?<![\w:])`：`//` 前面不能是字母/数字/`:`（`https://x` 的 `//` 会被它排掉，
 *    否则会补成 `https://https://x`）；它是**零宽**的，所以第 0 位也能匹配
 *    （用「边界字符」当左边界时，整段正文就是一条裸地址的话永远匹配不到）。
 *  - `((?:https?:)?//…)`：捕获组从 `//` 开始，下游拼字符串不会多留一个字符。
 *  - `(?![^\s"'<>\\])`：URL 右边必须是分隔符，别让贪婪匹配把后面的字吞进来。
 */
internal val MEDIA_URL_FIND = Regex(
    """(?<![\w:])((?:https?:)?//[^\s"'<>\\]+)(?![^\s"'<>\\])""",
    RegexOption.IGNORE_CASE
)

/** 可播的媒体扩展名（HLS 清单也算）。 */
private val MEDIA_EXTENSIONS = listOf("m3u8", "mp4", "webm", "mov", "m4v")

/** 路径结尾的媒体扩展名（`…/index.m3u8?a=1` -> `m3u8`）；没有则 null。 */
internal fun mediaExtensionOf(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return MEDIA_EXTENSIONS.firstOrNull { path.endsWith(".$it") }
}

/** HLS 清单（`index.m3u8`）而不是整段媒体文件。 */
internal fun isPlaylistUrl(url: String): Boolean =
    mediaExtensionOf(url) == "m3u8"

/** `<video>` / `<audio>` 标签：正文 HTML 自带播放器。 */
private val PLAYER_TAG = Regex("""<\s*(video|audio)\b""", RegexOption.IGNORE_CASE)

/**
 * 协议相对地址（`//cdn.example.com/a.m3u8`）。
 *
 * Pornhub 这类源的 `mediaDefinitions` 里 `videoUrl` 就是这种写法：它只在浏览器里
 * 才有意义，落到 ExoPlayer 手里是无效地址 —— 以前它连「媒体地址」都算不上
 * （[MEDIA_URL_FIND] 只认 `https?://`），于是正文被当成普通网页渲染，看起来就是
 * 「视频无法播放」。
 */
private val PROTOCOL_RELATIVE = Regex("""(?<![\w:])(//[A-Za-z0-9])""")

/** 协议相对地址的匹配区间（`range` 指向两个斜杠）。 */
private fun protocolRelativeRanges(text: String): List<IntRange> =
    PROTOCOL_RELATIVE.findAll(text).mapNotNull { it.groups[1]?.range }.toList()

/** 把正文/规则输出里的协议相对地址补成绝对地址（其它字符原样保留）。 */
internal fun resolveProtocolRelativeUrls(text: String, baseUrl: String?): String {
    if (text.isBlank() || !text.contains("//")) return text
    // 没有页面地址就没有"协议"可言：不猜（猜出来的地址可能与站点实际提供的协议不符）。
    val origin = baseUrl?.takeIf { it.isNotBlank() }?.let { OnlineSourceRules.originOf(it) }
    val scheme = origin?.substringBefore("://")?.takeIf { it == "http" || it == "https" } ?: return text
    val ranges = protocolRelativeRanges(text)
    if (ranges.isEmpty()) return text
    // 只在 `//` 前面插入 `scheme:`（`//` 本身保持不动）。
    val out = StringBuilder(text.length + (scheme.length + 1) * ranges.size)
    var cursor = 0
    for (range in ranges) {
        out.append(text, cursor, range.first)
        out.append(scheme).append(':')
        cursor = range.first
    }
    out.append(text, cursor, text.length)
    return out.toString()
}

/**
 * 正文里所有能播的媒体地址（顺序 = 出现顺序，去重）。
 *
 * 正文先做协议补全（[resolveProtocolRelativeUrls]，只在有 [baseUrl] 时生效），
 * 返回的地址一定是 ExoPlayer 能直接用的绝对地址；补不上的协议相对地址被丢掉。
 */
internal fun allMediaUrls(text: String, baseUrl: String? = null): List<String> {
    if (text.isBlank()) return emptyList()
    val resolved = resolveProtocolRelativeUrls(text, baseUrl)
    val out = LinkedHashSet<String>()
    for (match in MEDIA_URL_FIND.findAll(resolved)) {
        val url = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) continue
        if (mediaExtensionOf(url) == null) continue
        out.add(url)
    }
    return out.toList()
}

/**
 * 正文只输出一条媒体地址时返回它（91porn / Rule34 / Pornhub 的 `ruleContent`
 * 就是一条 mp4 或 m3u8，没有任何标记）。含标记（`<`）的正文一律不算。
 */
internal fun bareMediaUrl(text: String, baseUrl: String? = null): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.contains('<')) return null
    val resolved = resolveProtocolRelativeUrls(trimmed, baseUrl)
    return resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?.takeIf { mediaExtensionOf(it) != null }
}

/** 正文里第一个能播的地址（优先正文自己那条，其次页面里出现的）。 */
internal fun firstMediaUrl(text: String, baseUrl: String? = null): String? =
    bareMediaUrl(text, baseUrl) ?: allMediaUrls(text, baseUrl).firstOrNull()

/**
 * 从 `ruleContent` 求值出的正文里取一条可播地址；取不到返回 null。
 *
 * 只给一条地址的源直接用它；给的是 HTML（源自带播放器）时取正文里第一条媒体地址 ——
 * 这也是为什么 h视频 那种 hls.js 播放页现在同样由原生播放器播（WebView 那条路在
 * 真机上会「`readyState=4` 却全黑」，见 [RssVideoPlayer] 的说明）。
 *
 * 注意：**图片源也会走到这里**（正文里的 `<img>` 不算媒体地址，所以返回 null），
 * 但 URL 规则里带 `@js:` 的图片源可能恰好吐出一个 `.mp4`，那种情况按视频处理是
 * 用户想要的（能播就播）。判定源类型的入口见 [isVideoArticle]。
 */
internal fun extractVideoUrl(html: String, baseUrl: String? = null): String? {
    if (html.isBlank()) return null
    val resolved = resolveProtocolRelativeUrls(html, baseUrl)
    bareMediaUrl(resolved, baseUrl)?.let { return it }
    return allMediaUrls(resolved, baseUrl).firstOrNull()
}

/** 正文里是否带播放器标签（诊断/日志用）。 */
internal fun hasPlayerTag(html: String): Boolean = PLAYER_TAG.containsMatchIn(html)

/**
 * 这条订阅源是不是**视频源**：`ruleContent` 求值出来的正文里有可播地址。
 *
 * 用来决定点开文章时走哪条路：
 *  - 视频源 → [RssVideoScreen]（原生播放器 + 「加入分组」）；
 *  - 图片源 → 图片收集页（浏览器收集 + 用这些图 / 加入分组）。
 *
 * 判定放在**内容加载完**之后（`RssArticleScreen` 里本来就会加载），
 * 而不是在打开瞬间猜，所以不会先闪一下错误的界面。
 *
 * 判据不止「已经能抠出地址」——真机上不少视频源的文章页会被 CDN 挡回来
 * （Challenge / 403，正文只有一两百字节），规则自然抠不出东西；只看这一点就会
 * 把视频文章错判成图片。所以再放宽一层：正文里出现**播放器钩子**（视频站
 * 页面里那些 `video_url` / `get_file` / `.m3u8` 标记）也按视频处理 ——
 * 进播放器后抠不出地址会自己退网页，比在图片收集页里看一堆广告图强。
 */
internal fun isVideoArticle(html: String, baseUrl: String?): Boolean {
    if (html.isBlank()) return false
    // 只给一条媒体地址的源（91porn / Rule34 / Pornhub）一看就知道。
    if (bareMediaUrl(html, baseUrl) != null) return true
    // 给整段 HTML 的源：带播放器标签 + 里面有地址 = 视频（h视频）。
    if (hasPlayerTag(html) && allMediaUrls(html, baseUrl).isNotEmpty()) return true
    // 播放器钩子：地址还没成型（或被站点挡了）也算视频。
    return VIDEO_PAGE_HINT.containsMatchIn(html)
}

/** 视频站页面里的播放器痕迹（判源类型用，不参与取地址）。 */
private val VIDEO_PAGE_HINT = Regex(
    """(video_url|videourl|mediaDefinitions|get_file/|\.m3u8|""".trimIndent() +
        """["'](?:file|src|source|videoUrl)["']\s*:\s*["']https?://[^"']*\.(?:mp4|m3u8))""",
    RegexOption.IGNORE_CASE
)

/**
 * 「浏览器收集」对话框里给直链用的最小播放页。
 *
 * 订阅源的正文播放已经不走网页（见 [RssVideoScreen]，直接交给原生播放器），
 * 这里只剩一个用途：用户从订阅源正文里点进「浏览器收集」时，规则输出若是一条
 * 直链，就在对话框里内联播出来，而不是把地址当文本显示。
 */
internal fun playerPageHtml(urls: List<String>): String {
    val playlist = urls.filter { it.isNotBlank() }
        .joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
    return """
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no">
<script src="https://unpkg.com/hls.js@1.4.3/dist/hls.min.js"></script>
<style>
html,body{height:100%;margin:0;padding:0;background:#000;overflow:hidden}
#player{position:absolute;inset:0;width:100%;height:100%;background:#000}
#player video{display:block;width:100%;height:100%;object-fit:contain;background:#000}
</style>
</head><body>
<div id="player"><video id="video" controls autoplay loop playsinline></video></div>
<script>
var v = document.getElementById('video');
var s = [$playlist];
var c = 0;
function next() {
  if (!s.length) return;
  c >= s.length && (c = 0);
  var u = s[c++];
  if (typeof Hls !== 'undefined' && Hls.isSupported()) {
    var h = new Hls({ enableSoftwareAES: true, forceKeyFrameOnDiscontinuity: true });
    h.loadSource(u);
    h.attachMedia(v);
    h.on(Hls.Events.MANIFEST_PARSED, function () { v.play().catch(function () {}); });
    h.on(Hls.Events.ERROR, function (_, d) { d && d.fatal && next(); });
  } else {
    v.src = u;
    v.play().catch(function () {});
    v.onerror = next;
  }
}
next();
</script>
</body></html>
""".trimIndent()
}

/** 只有播放页需要的最小布局（对话框里的直链播放）。 */
internal const val PLAYER_LAYOUT_CSS =
    "<style>html,body{height:100%!important;margin:0!important;padding:0!important;" +
        "background:#000!important}video{width:100%!important;height:100%!important;" +
        "object-fit:contain!important;background:#000!important}</style>"
