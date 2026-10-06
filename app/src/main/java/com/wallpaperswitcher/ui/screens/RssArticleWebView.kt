package com.wallpaperswitcher.ui.screens

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import com.wallpaperswitcher.engine.RssCookieStore
import com.wallpaperswitcher.engine.legado.LegadoJs
import com.wallpaperswitcher.engine.legado.LegadoRss
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 文章页 WebView 的统一配置。
 *
 * 全屏播放页和「浏览器收集」对话框各自建 WebView，以前两边设置不同 —— 对话框
 * 设了 `useWideViewPort` / 缩放 / 自带 UA，全屏这边都没有，于是同一篇文章在两条
 * 路径里排版和 UA 不一致（有些源会按 UA 下发不同的播放页）。阅读 (Legado) 的文章页
 * 只用一份固定配置，我们也收敛成这一份。
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun configureArticleWebView(view: WebView) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadsImagesAutomatically = true
        // 阅读的播放页是自动播放的（h视频的 `<video autoplay>`），
        // 没有这一行 WebView 会要求先点一下才出声/出画。
        mediaPlaybackRequiresUserGesture = false
        // 规则正文大多没有 viewport：不设的话按 980px 排版、字号很小。
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        // 阅读不覆盖文章页的 UA（只在自己的抓取请求里用浏览器 UA）：
        // 覆盖成抓取用的 UA 会让站点按「非浏览器」下发页面/播放器。
        userAgentString = null
    }
    // 播放页的视频 surface 要在 WebView 的硬件层里合成（「浏览器收集」对话框
    // 早就这么做，否则会出现「有声音/在缓冲，但画面全黑」）。文章页里播放视频的
    // 情况越来越多（自有播放页 + 我们合成的 hls.js 播放页），所以这条放到共享配置里。
    view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
}

/** True for the request headers that must not be forwarded to the player. */
internal fun isSkippedHeader(name: String): Boolean =
    name.lowercase() in SKIPPED_HEADER_NAMES

/** Hop-by-hop / range headers are produced by the player itself. */
private val SKIPPED_HEADER_NAMES = setOf(
    "host", "connection", "content-length", "range", "accept-encoding",
)

/**
 * 文章页的 Cookie：会话 jar 里该地址的 cookie + 链接/正文选项里带的
 * `Cookie` 头（后者优先，和阅读 `enabledCookieJar` + header 的顺序一致）。
 * 播放页自己的取流请求因此带上登录态（源大多要求 `Referer` + Cookie）。
 */
internal fun seedArticleCookies(url: String, headers: Map<String, String> = emptyMap()) {
    if (url.isBlank()) return
    val manager = CookieManager.getInstance()
    try {
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null) {
            for (cookie in RssCookieStore.jar.loadForRequest(httpUrl)) {
                manager.setCookie(url, "${cookie.name}=${cookie.value}")
            }
        }
    } catch (_: Throwable) {
    }
    val explicit = headers.entries
        .firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
        ?.value
        .orEmpty()
    if (explicit.isBlank()) return
    for (pair in explicit.split(';')) {
        val cookie = pair.trim()
        if (cookie.isNotEmpty()) manager.setCookie(url, cookie)
    }
}

/**
 * 页面内播放的诊断：3 秒后把播放器的真实状态交给收集器（`wsCollector`），
 * 由调用方按 [PLAYER_STATE_PREFIX] 识别后写进运行日志。
 *
 * 「黑屏但在缓冲」这类问题在设备上无法靠猜：必须知道 `<video>` 有没有尺寸、
 * `readyState` / `networkState` / `currentTime` / `paused` 各是什么。
 */
internal const val PLAYER_STATE_PREFIX = "[wsstate]"

internal const val PLAYER_STATE_JS = """
(function(){
  if (window.__wsStateHooked) return;
  window.__wsStateHooked = true;
  window.__wsErr = function(m){
    if (!document.querySelector('video')) return;
    try { wsCollector.onImages(JSON.stringify(['$PLAYER_STATE_PREFIX err ' + m])); } catch (e) {}
  };
  window.addEventListener('error', function(e){ window.__wsErr('js:' + (e.message || '')); }, true);
  function sample(when){
    if (!document.querySelector('video')) return;
    try {
      var v = document.querySelector('video');
      var r = v ? v.getBoundingClientRect() : {width:0,height:0};
      var cs = v ? getComputedStyle(v) : null;
      function box(sel){
        var e = sel ? document.querySelector(sel) : null;
        if (!e) return sel + '=none';
        var b = e.getBoundingClientRect();
        var s = getComputedStyle(e);
        return sel + '=' + Math.round(b.width) + 'x' + Math.round(b.height) +
               '(' + s.height + ',' + s.position + ')';
      }
      var s = v
        ? Math.round(r.width) + 'x' + Math.round(r.height) + ' ready=' + v.readyState +
          ' net=' + v.networkState + ' t=' + Number(v.currentTime).toFixed(1) +
          ' paused=' + v.paused + ' err=' + (v.error ? v.error.code : 0) +
          ' disp=' + (cs ? cs.display : '?') + ' pos=' + (cs ? cs.position : '?') +
          ' h=' + (cs ? cs.height : '?') +
          ' src=' + String(v.currentSrc || v.src || '').slice(0, 50) +
          ' | ' + box('html') + ' ' + box('body') + ' ' + box('#player') + ' ' + box('#video') +
          ' | css=' + (document.querySelector('style') ? 'yes' : 'no') +
          ' vp=' + (document.querySelector('meta[name=viewport]')
              ? document.querySelector('meta[name=viewport]').getAttribute('content') : 'none')
        : 'no video element';
      wsCollector.onImages(JSON.stringify(['$PLAYER_STATE_PREFIX ' + when + ' ' + s]));
    } catch (e) {}
  }
  // 起播 + 几处关键时间点各采一次（不是每秒刷）：起播、换档、卡顿的时间线够用，
  // 又不会在正常运行里把日志刷满。
  [1, 3, 8, 20, 40].forEach(function(ms){ setTimeout(function(){ sample('t' + ms); }, ms * 1000); });
})();
"""

/**
 * 阅读的网页全屏播放（`WebChromeClient.onShowCustomView`）：播放页自己调
 * `requestFullscreen()` 时，把系统给的视图铺满**窗口**。挂在 WebView 自己的
 * 父容器里是不行的 —— 那是 Compose 的 AndroidView 宿主，Compose 会按自己的
 * 布局重新摆放它，全屏视图要么被压扁要么被裁掉。这里挂到 decorView。
 */
@SuppressLint("UseGetLayoutParamsForSizedView")
internal fun showArticleFullscreen(webView: WebView, view: View) {
    val root = fullscreenHostFor(webView) ?: return
    if (view.parent is ViewGroup) (view.parent as ViewGroup).removeView(view)
    root.addView(
        view,
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    view.bringToFront()
}

/** Pairs with [showArticleFullscreen]: remove the view the player handed over. */
internal fun hideArticleFullscreen(view: View?) {
    (view?.parent as? ViewGroup)?.removeView(view)
}

/** Number of views to walk up when looking for the activity's content root. */
private const val FULLSCREEN_ROOT_MAX_DEPTH = 6

/**
 * 网页里请求全屏时挂载用的根视图：从 WebView 往上找第一个有尺寸的 FrameLayout
 * （正常是 `android.R.id.content`）；找不到就退回最外层 rootView。
 */
internal fun fullscreenHostFor(webView: WebView): ViewGroup? {
    var node: View? = webView.parent as? View
    var depth = 0
    while (node != null && depth < FULLSCREEN_ROOT_MAX_DEPTH) {
        if (node is android.widget.FrameLayout && node.height > 0) return node
        node = node.parent as? View
        depth++
    }
    return webView.rootView as? ViewGroup
}

/** The rules that follow the loaded body: `style` / 跳转拦截 / 黑白名单. */

/**
 * 阅读 `style`: 源自带的 CSS 直接拼在正文 HTML 前面（同阅读 `clHtml`）。
 * 源把播放页的排版（标题位置、video 偏移）写在这里，缺了它正文就只是裸 HTML。
 */
internal fun withSourceStyle(style: String?, html: String): String =
    if (style.isNullOrBlank()) html else "<style>\n$style\n</style>\n$html"

/**
 * 阅读 `shouldOverrideUrlLoading`: 规则是段 JS，`url` 是本次跳转地址，
 * 返回 true（或 1）表示这次跳转由规则处理、WebView 不再导航。
 */
internal fun pageUrlOverride(script: String?, url: String, sourceId: Long): Boolean {
    if (script.isNullOrBlank() || url.isBlank()) return false
    val result = try {
        LegadoJs.eval(script, mapOf("url" to url), baseUrl = url, sourceId = sourceId)
    } catch (_: Throwable) {
        null
    }
    val text = result?.trim().orEmpty()
    return text.equals("true", ignoreCase = true) || text == "1"
}

/**
 * 阅读 `contentBlacklist` / `contentWhitelist`: 逗号分隔的「前缀或正则」。
 * 有黑名单时命中即拦；没有黑名单时白名单只放行命中的，其余全拦。
 */
internal fun pageAllowsResource(options: LegadoRss.WebOptions, url: String): Boolean {
    if (url.isBlank()) return true
    val blacklist = urlMatchRules(options.contentBlacklist)
    if (blacklist.isNotEmpty()) return blacklist.none { urlMatchesRule(it, url) }
    val whitelist = urlMatchRules(options.contentWhitelist)
    if (whitelist.isNotEmpty()) return whitelist.any { urlMatchesRule(it, url) }
    return true
}

private fun urlMatchRules(raw: String?): List<String> =
    raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

private fun urlMatchesRule(rule: String, url: String): Boolean {
    if (rule.isEmpty()) return false
    if (url.startsWith(rule)) return true
    // WebView 会对页面里每一个子资源都走一遍黑白名单（shouldInterceptRequest 在
    // 后台线程上），原来每条规则、每次调用都 `Regex(rule)` 现编译一次 —— 一张图
    // 几十个资源就是几十次编译，而正则编译是这里最贵的一步。按规则串缓存编译结果。
    // 空规则短路，和原来「空规则编译异常→false」一致。
    val pattern = compiledRule(rule) ?: return false
    return try {
        url.matches(pattern)
    } catch (_: Throwable) {
        false
    }
}

/**
 * `Regex` 编译结果缓存：`shouldInterceptRequest` 对每个子资源都会命中它。
 *
 * **必须有界**：规则串来自源的 JSON，正常一个源只有几条（黑白名单各一行），但
 * 一个源可以带来任意多个不同的规则串，而这张表跟着进程活（本 App 的进程还包含
 * 常驻的实时壁纸服务），无上限就是内存单调增长 —— 与 [LegadoJs] 里
 * `scriptCache` 的缺陷同形。LRU 上限 256 与该处保持一致：一个源最多几条规则，
 * 256 条足以覆盖「多个源同时开着 + 站点地址里的动态串」的命中面，超出后淘汰最久
 * 未用的（淘汰后只是下次重新编译一次，不改变任何行为）。
 *
 * 访问顺序 LRU（`LinkedHashMap(..., accessOrder = true)`）不是线程安全的，而
 * `shouldInterceptRequest` 回调在 WebView 的后台线程上，所以读写都在
 * [rulePatternsLock] 里。
 */
private fun compiledRule(rule: String): Regex? = synchronized(rulePatternsLock) {
    if (rulePatterns.containsKey(rule)) {
        // accessOrder = true：get 也会把这条挪到队尾（最近使用）。
        rulePatterns[rule]?.regex
    } else {
        val holder = try {
            PatternHolder(Regex(rule))
        } catch (_: Throwable) {
            // 规则串不是合法正则：把失败也缓存下来，避免每次子资源都重复抛一次
            // 异常（原来这条路径每个资源都要构造并抛出）。
            PatternHolder(null)
        }
        rulePatterns[rule] = holder
        holder.regex
    }
}

private val rulePatternsLock = Any()

/** 规则串 → 编译结果，最近使用在队尾；超过 [RULE_CACHE_MAX] 淘汰最久未用的。 */
private val rulePatterns = object : LinkedHashMap<String, PatternHolder>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PatternHolder>?): Boolean =
        size > RULE_CACHE_MAX
}

private const val RULE_CACHE_MAX = 256

/**
 * 缓存项：`regex == null` 表示这条规则编译失败过（`LinkedHashMap` 可以存 null，
 * 但包装成对象后「编译失败」与「还没编译」在读取侧不用再区分）。
 */
private class PatternHolder(val regex: Regex?)

/**
 * 规则正文大多没有 viewport（阅读在 WebView 里同样按站点自己的排版）：
 * 不补一行，图片/视频会按 980px 布局、整页缩小。`playerLayout` 为真时再补一层
 * 播放布局（见 [PLAYER_LAYOUT_CSS]）。
 */
internal fun wrapViewport(html: String, playerLayout: Boolean = false): String {
    val css = "<style>img{max-width:100%!important;height:auto!important}" +
        "body{overflow-x:hidden}</style>" + if (playerLayout) PLAYER_LAYOUT_CSS else ""
    val meta = """<meta name="viewport" content="width=device-width, initial-scale=1.0">"""
    if (html.contains("name=\"viewport\"", ignoreCase = true)) {
        return html.replaceFirst(Regex("<head[^>]*>", RegexOption.IGNORE_CASE), "$0$css")
    }
    return when {
        html.contains("<head>", ignoreCase = true) ->
            html.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$meta$css")
        html.contains("<html", ignoreCase = true) ->
            html.replaceFirst(
                Regex("<html[^>]*>", RegexOption.IGNORE_CASE),
                "$0<head>$meta$css</head>",
            )
        else -> "$meta$css$html"
    }
}
