package com.wallpaperswitcher.ui.screens

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.RssArticle
import com.wallpaperswitcher.engine.legado.LegadoRss
import com.wallpaperswitcher.util.AppLog

private const val TAG = "RssBrowser"

/**
 * 「网页源」的文章：**直接用应用内浏览器打开原文**。
 *
 * 有些源不是图文/视频列表，而是需要登录或跑站点脚本的网页（Pixiv 书源这类）：
 * 规则抓不到正文、也抠不出媒体地址。这种文章以前会掉进图片收集页（那里只收集
 * `<img>`，什么都收集不到），现在直接打开原文。
 *
 * 顺带做一件有用的事：如果页面里的播放器（站点自己的脚本）**真的拉起了视频流**，
 * [onStreamFound] 会被回调，调用方可以切成原生播放器 —— 被 CDN 挑战页挡住的
 * 视频源就是靠这个拿到真正片地址的（见 [RssPageSniffer]）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RssArticleBrowserScreen(
    article: RssArticle,
    onBack: () -> Unit,
    /**
     * 页面里发现了可播的媒体流（站点播放器真正请求的那条）。
     *
     * **不再自动切走**（用户决定）：以前嗅到流就立刻换成原生播放器，于是视频源
     * 一打开就跳离网页，观感和阅读（网页播放）完全不同。现在只在顶栏挂一个
     * 「用这个视频」入口，由用户决定什么时候进原生播放 —— 网页照旧留在原地，
     * 用户可以自己看着站点的播放器，也可以一键换成我们干净的播放器。
     */
    onStreamFound: ((url: String, headers: Map<String, String>) -> Unit)? = null,
    /** 嗅到流之后是否显示「用这个视频」入口（见 [onStreamFound]）。 */
    showStreamAction: Boolean = true,
) {
    // 返回键/手势退出（全屏页面自己的返回处理，不能指望外层）。
    BackHandler { onBack() }
    val headers = remember(article.guid) {
        try {
            LegadoRss.requestHeaders(article)
        } catch (_: Throwable) {
            emptyMap()
        }
    }
    val pending = remember(article.guid) {
        java.util.Collections.synchronizedList(mutableListOf<SniffedStream>())
    }
    var handedOff by remember(article.guid) { mutableStateOf(false) }
    /** 嗅到的最佳流：不再自动用掉，留着给顶栏那个「用这个视频」入口。 */
    var foundStream by remember(article.guid) {
        mutableStateOf<SniffedStream?>(null)
    }

    // 嗅探：只挑「像正片」的流（广告流被 streamScore 压下去）。
    // 找到之后**只记录**，不回调 —— 交不交给原生播放器是用户按按钮决定的。
    LaunchedEffect(article.guid) {
        if (onStreamFound == null) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + SNIFF_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && foundStream == null) {
            kotlinx.coroutines.delay(500)
            val best = bestStream(pending.toList()) ?: continue
            AppLog.d(TAG, "page player stream (kept in page): ${best.first.take(140)}")
            foundStream = best
            return@LaunchedEffect
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Text(
                text = article.title.ifBlank { stringResource(R.string.rss_article) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 嗅到流之后才出现：一键换成原生播放器（网页照旧保留在这里）。
            // 这样视频源默认就是"网页播放"（和阅读一致），想要干净的全屏播放器
            // 或想把视频加入分组时再切过去。
            val stream = foundStream
            if (stream != null && showStreamAction && onStreamFound != null) {
                TextButton(onClick = {
                    handedOff = true
                    onStreamFound(stream.first, stream.second)
                }) {
                    Text(stringResource(R.string.rss_use_this_video))
                }
            }
            // 阅读的 `menu_browser_open`：把当前地址交给**系统浏览器**。
            // 内置浏览器带源的 header/cookie，遇到必须外部 App 才能完成的
            // 页面（第三方登录、App 唤起）时需要一个出口。
            val browserContext = LocalContext.current
            IconButton(onClick = {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(article.link),
                )
                try {
                    browserContext.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(
                        browserContext,
                        browserContext.getString(R.string.rss_no_browser),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.rss_open_system_browser),
                )
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                android.webkit.WebView(context).apply {
                    configureArticleWebView(this)
                    seedArticleCookies(article.link, headers)
                    if (onStreamFound != null) {
                        webViewClient = sniffingClient(article, pending)
                    }
                    loadUrl(article.link, headers)
                }
            },
            // Leaving the screen must really tear the page down: a detached
            // WebView keeps its renderer, page timers and (with autoplay
            // enabled) media alive until destroy() runs, and repeated article
            // opens would accumulate that per-page memory in a process that
            // also hosts the live-wallpaper engine.
            onRelease = { view ->
                try {
                    view.stopLoading()
                    view.destroy()
                } catch (_: Throwable) {
                }
            },
        )
    }
}
