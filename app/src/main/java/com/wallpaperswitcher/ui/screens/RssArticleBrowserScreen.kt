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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    /** 页面里发现了可播的媒体流（站点播放器真正请求的那条）。 */
    onStreamFound: ((url: String, headers: Map<String, String>) -> Unit)? = null,
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

    // 嗅探：只挑「像正片」的流（广告流被 streamScore 压下去）。
    LaunchedEffect(article.guid) {
        if (onStreamFound == null) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + SNIFF_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && !handedOff) {
            kotlinx.coroutines.delay(500)
            val best = bestStream(pending.toList()) ?: continue
            AppLog.d(TAG, "page player stream: ${best.first.take(140)}")
            handedOff = true
            onStreamFound(best.first, best.second)
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
