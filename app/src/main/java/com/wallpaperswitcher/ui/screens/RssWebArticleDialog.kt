package com.wallpaperswitcher.ui.screens

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.legado.LegadoRss
import com.wallpaperswitcher.engine.legado.RssSourceEditor
import java.io.ByteArrayInputStream
import org.json.JSONArray

/**
 * Optional "browser mode" for sources whose gallery only exists after the page
 * script ran (3w-style): a real WebView renders the article, we collect every
 * image it actually shows and hand the list back for "加入分组".
 *
 * 它和全屏的 [RssWebScreen] 共用同一份阅读对齐逻辑：同一个 [configureArticleWebView]
 * 配置、同一套 `style` / `injectJs` / 黑白名单 / 跳转拦截 / 直链播放页。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RssWebArticleDialog(
    source: RssSource?,
    url: String,
    /** The source's `ruleContent` output - 阅读 renders this, not the raw URL. */
    ruleHtml: String,
    onDismiss: () -> Unit,
    onCollect: (List<String>) -> Unit,
    /** true = 全屏页面（图片源点开文章就是它），false = 对话框（选择器里点「浏览器模式」）。 */
    fullScreen: Boolean = false,
) {
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val sniffed = remember { java.util.Collections.synchronizedSet(mutableSetOf<String>()) }
    // 阅读的阅读器字段：`style` / `injectJs` / 黑白名单 / 跳转拦截。
    val webOptions = remember(source?.id) { LegadoRss.webOptions(source) }

    val collector = remember {
        object {
            @JavascriptInterface
            fun onImages(json: String) {
                try {
                    val array = JSONArray(json)
                    val list = ArrayList<String>(array.length())
                    for (i in 0 until array.length()) {
                        val value = array.optString(i).trim()
                        // Only http(s) targets: the page is remote content, so
                        // never let it feed local/file/data URLs into the
                        // downloader through the JS bridge.
                        if (value.startsWith("http://") || value.startsWith("https://")) {
                            list.add(value)
                        }
                    }
                    if (list.isNotEmpty()) images = (images + list).distinct()
                } catch (_: Throwable) {
                }
            }
        }
    }

    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        configureArticleWebView(this)
                        // Compose dialogs are separate windows: the video
                        // SurfaceView often fails to composite there (sound but
                        // black picture). A hardware layer on the WebView makes
                        // its children - including the video surface - render
                        // into that layer instead.
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        addJavascriptInterface(collector, "wsCollector")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, finishedUrl: String?) {
                                view.evaluateJavascript(COLLECT_JS, null)
                                // 阅读 `injectJs`: 页面加载完在正文里再补一段脚本。
                                val inject = webOptions.injectJs
                                if (!inject.isNullOrBlank()) {
                                    try {
                                        view.evaluateJavascript(inject, null)
                                    } catch (_: Throwable) {
                                    }
                                }
                            }

                            // 阅读 `shouldOverrideUrlLoading`: 播放页里的跳转
                            // （站内广告、播放器换源）先交给源的规则判断。
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = pageUrlOverride(
                                webOptions.shouldOverrideUrlLoading,
                                request.url?.toString().orEmpty(),
                                source?.id ?: 0L,
                            )

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                // 协议相对地址的嗅探结果也要能播能下载。
                                val target = resolveProtocolRelativeUrls(
                                    request.url?.toString().orEmpty(), url,
                                )
                                // 阅读 `contentBlacklist` / `contentWhitelist`:
                                // 命中的资源直接回空响应（不下载、不执行）。
                                if (!pageAllowsResource(webOptions, target)) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                                // Sniff what the player really requests: signed
                                // mp4/HLS URLs never appear as static text.
                                if (MEDIA_SNIFF.containsMatchIn(target.lowercase()) &&
                                    sniffed.add(target)
                                ) {
                                    mainHandler.post { images = (images + target).distinct() }
                                }
                                return null
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            private var customView: View? = null
                            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                customView = view
                                showArticleFullscreen(this@apply, view)
                            }

                            override fun onHideCustomView() {
                                hideArticleFullscreen(customView)
                                customView = null
                            }
                        }
                        val headers = source?.let {
                            LegadoRss.parseHeaderMap(
                                RssSourceEditor.fieldValue(it.rawJson, "header"), it.id, it.url
                            )
                        }.orEmpty()
                        seedArticleCookies(url, headers)
                        // 阅读 runs the *content rule's* HTML in the WebView with
                        // the article URL as base, so the page's own scripts
                        // (gallery/`_N.html` fetching) work exactly like there.
                        val directMedia = bareMediaUrl(ruleHtml, url)
                        when {
                            directMedia != null -> {
                                // The rule output is just a media URL (video sources
                                // often decode to that): do not render it as text.
                                mainHandler.post { images = listOf(directMedia) }
                                loadDataWithBaseURL(
                                    url,
                                    wrapViewport(playerPageHtml(listOf(directMedia)), playerLayout = true),
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                            }
                            ruleHtml.isNotBlank() -> loadDataWithBaseURL(
                                url,
                                wrapViewport(withSourceStyle(webOptions.style, ruleHtml)),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                            else -> loadUrl(url, headers)
                        }
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    stringResource(R.string.rss_web_collected, images.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    enabled = images.isNotEmpty(),
                    onClick = { onCollect(images) }
                ) {
                    Text(stringResource(R.string.rss_web_use))
                }
            }
        }
    }

    // 图片源点开文章 = 全屏页面（原来就是这样）；从选择器里点「浏览器模式」
    // 进来时仍然是对话框，两种形态共用同一份收集逻辑。
    if (fullScreen) {
        // 全屏时系统返回键也要能退出（以前只靠底部「取消」，返回键被外层吃掉）。
        androidx.activity.compose.BackHandler { onDismiss() }
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            content()
        }
    }
}

/** Collect on load, while scrolling (lazy images) and every 2s as a safety net. */
private const val COLLECT_JS = """
(function(){
  if (window.__wsHooked) { window.__wsSend(); return; }
  window.__wsHooked = true;
  window.__wsSend = function(){
    try {
      var urls = [];
      var imgs = document.images || [];
      for (var i = 0; i < imgs.length; i++) {
        var src = imgs[i].currentSrc || imgs[i].getAttribute('data-src') ||
                  imgs[i].getAttribute('data-original') || imgs[i].src || '';
        if (src && urls.indexOf(src) < 0) urls.push(src);
      }
      // Videos (video sources / direct links) join the same list; the importer
      // stores them as video media rows.
      var vids = document.querySelectorAll('video');
      for (var v = 0; v < vids.length; v++) {
        var vs = vids[v].currentSrc || vids[v].src ||
                 vids[v].getAttribute('data-src') || '';
        if (vs && urls.indexOf(vs) < 0) urls.push(vs);
      }
      var srcs = document.querySelectorAll('video source, source[src]');
      for (var s = 0; s < srcs.length; s++) {
        var ss = srcs[s].getAttribute('src') || '';
        if (ss && urls.indexOf(ss) < 0) urls.push(ss);
      }
      var links = document.querySelectorAll('a[href]');
      for (var a = 0; a < links.length; a++) {
        var href = links[a].href || '';
        if (/\.(mp4|webm|mov|m3u8|mkv)(\?|$)/i.test(href) && urls.indexOf(href) < 0) {
          urls.push(href);
        }
      }
      wsCollector.onImages(JSON.stringify(urls));
    } catch (e) {}
  };
  var pending = false;
  window.addEventListener('scroll', function(){
    if (pending) return;
    pending = true;
    setTimeout(function(){ pending = false; window.__wsSend(); }, 400);
  }, true);
  setInterval(window.__wsSend, 2000);
  window.__wsSend();
})();
"""

/** Media requests worth capturing from the player (mp4 + HLS playlists/segments). */
private val MEDIA_SNIFF = Regex("""\.(mp4|webm|mov|m4v|m3u8|ts|m4s)(\?|$)""", RegexOption.IGNORE_CASE)
