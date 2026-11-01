package com.wallpaperswitcher.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.wallpaperswitcher.util.AppLog

/**
 * 订阅源视频：**直接加载站点自己的播放页**（和阅读一致）。
 *
 * 为什么不自己合成播放页：曾经用 [playerPageHtml] 生成 HTML5 播放页来播视频，
 * mp4 的 CORS 问题解决之后仍然卡在 `readyState=0/1`（元数据到手、`paused=false`、
 * 无错误，就是永远不出画，整屏黑）。而**站点自己的页面**里本来就有可用的
 * `<video>`（真机抓到的播放页 38KB，含 `<video>` / `.mp4`），让站点自己的 JS
 * 起播放器就没有这个合成问题 —— 阅读也正是这么做的（`ReadRssActivity` 是 WebView）。
 * 那个合成方案已经删掉（原来的 `RssWebPlayer.kt`），别再加回来。
 *
 * 顺带把页面里的媒体地址捞出来回调给 [onStreamFound]，这样「加入分组 /
 * 设为动态壁纸」这条链路照旧可用（`strencode2` 由站点自己的 JS 解密，
 * 我们从 DOM 里读结果，不需要复刻它的算法）。
 *
 * @param pageUrl 站点播放页地址（文章链接）
 * @param headers 源的请求头（Referer / UA 等）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun RssSitePlayer(
    pageUrl: String,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    /**
     * **不为空时按规则渲染**：这是 `ruleContent` 的输出（HTML 型源，h视频那类）。
     * 用 [pageUrl] 当 baseUrl，规则里的相对地址照常解析；同时因为 origin 是站点
     * 自己，页面里的站点脚本/CORS 行为和真页面一致。
     */
    ruleHtml: String? = null,
    /**
     * `ruleContent` 输出的**媒体地址**（直链型源）。这是规则给出的结果，
     * 直接把它交给 WebView 自带的播放器 —— 不走我们合成的 `data:` 播放页
     * （那条路 CORS / blob 都出不了画），也不去加载站点页面（那会丢掉规则）。
     */
    mediaUrl: String? = null,
    /** 从页面 DOM 里读到的媒体地址（可能多次回调，取第一条即可）。 */
    onStreamFound: ((url: String, headers: Map<String, String>) -> Unit)? = null,
) {
    // 读 DOM 里 <video> 的地址：等站点 JS 把播放器建起来之后再读。
    val probe = remember(pageUrl) {
        """
        (function(){
          var v = document.querySelector('video');
          if (!v) return '';
          var u = v.currentSrc || v.src || '';
          if (!u) { var s = v.querySelector('source'); if (s) u = s.src || ''; }
          return u;
        })()
        """.trimIndent()
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            android.webkit.WebView(context).apply {
                configureArticleWebView(this)
                seedArticleCookies(pageUrl, headers)
                if (ruleHtml != null) {
                    // 规则输出是 HTML：**按规则渲染**，用页面地址当 baseUrl
                    // （相对地址按站点解析，origin 也是站点自己的）。
                    loadDataWithBaseURL(pageUrl, ruleHtml, "text/html", "utf-8", null)
                } else if (mediaUrl != null) {
                    // 规则输出是一条媒体地址：**直接播这条地址**，交给 WebView
                    // 自带的播放器。这是"规则驱动"里最省事的一条 —— 不合成页面、
                    // 不碰 CORS、也不需要站点的 JS。
                    loadUrl(mediaUrl, headers)
                } else {
                    // 规则什么都没给：加载**站点真页面**，让站点自己的 JS 起播放器。
                    // 必须带源的 header —— 真机上不带只会拿到 639 字节的 stub。
                    loadUrl(pageUrl, headers)
                }
                if (onStreamFound != null) {
                    var reported = false
                    // 站点播放器是异步建起来的：轮询几次，拿到地址就回调一次。
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    val task = object : Runnable {
                        var tries = 0
                        override fun run() {
                            if (reported || tries++ > 20) return
                            evaluateJavascript(probe) { value ->
                                val url = value
                                    ?.trim('"')
                                    ?.takeIf { it.startsWith("http") && it != "null" }
                                if (!reported && url != null) {
                                    reported = true
                                    AppLog.d("RssSitePlayer", "page media: ${url.take(150)}")
                                    onStreamFound(url, headers)
                                }
                            }
                            handler.postDelayed(this, 1000)
                        }
                    }
                    handler.postDelayed(task, 1500)
                }
            }
        },
        onRelease = { view ->
            try {
                view.stopLoading()
                view.destroy()
            } catch (_: Throwable) {
            }
        },
    )
}
