package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.RssArticle
import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.ui.theme.HiLoadingHint
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

private const val TAG = "RssArticle"

/** 文章打开后走哪条路。 */
private enum class ArticleMode { VIDEO, IMAGE, BROWSER }

/**
 * 订阅源文章的入口：**先看正文内容，再决定怎么显示**。
 *
 *  - 正文里能抠出媒体地址（或页面里有播放器钩子）→ 视频播放页（[RssVideoScreen]）；
 *  - 正文是图文/图集 → 图片收集页（[RssImageScreen]，原来的流程）；
 *  - **正文压根没拿到**（网页源：规则抓不到内容、要登录或跑站点脚本）→
 *    直接用浏览器打开原文（[RssArticleBrowserScreen]）。
 *
 * 判定用的是**规则求值后的正文**，也就是「点开文章里面的内容」，不是列表项的字段。
 */
@Composable
fun RssArticleScreen(
    viewModel: WallpaperViewModel,
    source: RssSource?,
    article: RssArticle,
    onBack: () -> Unit,
    /** 图片收集页点了「用这些图」：把结果交回文章列表，由它打开选择器。 */
    onUseImages: (List<String>, Map<String, String>, List<String>) -> Unit,
) {
    var mode by remember(article.sourceId, article.guid) { mutableStateOf<ArticleMode?>(null) }
    /** 浏览器页里嗅到的流：有值就直接切原生播放器（网页源其实是个视频页）。 */
    var sniffed by remember(article.sourceId, article.guid) {
        mutableStateOf<SniffedStream?>(null)
    }

    LaunchedEffect(article.sourceId, article.guid) {
        val html = try {
            viewModel.loadRssArticleContent(article).html
        } catch (t: Throwable) {
            AppLog.w(TAG, "content load failed: ${t.javaClass.simpleName}")
            ""
        }
        val decided = when {
            // 空正文 = 没抓到内容（网页源 / 被 CDN 挡回来）：交给浏览器，
            // 用户在网页里至少能看到文章本身 —— 真机上一堆 `image article (0B)`
            // 就是这么来的（规则脚本跟我们的引擎不兼容，或站点回挑战页）。
            html.isBlank() -> ArticleMode.BROWSER
            isVideoArticle(html, article.link) -> ArticleMode.VIDEO
            else -> ArticleMode.IMAGE
        }
        AppLog.d(
            TAG,
            "${decided.name.lowercase()} article (${html.length}B): ${article.link.take(90)}",
        )
        mode = decided
    }

    when (mode) {
        null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            HiLoadingHint(text = stringResource(R.string.rss_content_loading))
        }

        ArticleMode.VIDEO -> RssVideoScreen(
            viewModel = viewModel,
            article = article,
            onCancel = onBack,
        )

        ArticleMode.IMAGE -> RssImageScreen(
            viewModel = viewModel,
            source = source,
            article = article,
            onCancel = onBack,
            onUseImages = onUseImages,
        )

        ArticleMode.BROWSER -> {
            val stream = sniffed
            if (stream != null) {
                // 页面里的播放器真的拉起了流 → 交给原生播放器（+ 加入分组）。
                RssVideoScreen(
                    viewModel = viewModel,
                    article = article,
                    onCancel = onBack,
                    directStream = stream,
                )
            } else {
                RssArticleBrowserScreen(
                    article = article,
                    onBack = onBack,
                    onStreamFound = { url, headers -> sniffed = url to headers },
                )
            }
        }
    }
}
