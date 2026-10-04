package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.wallpaperswitcher.data.RssArticle
import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/**
 * 订阅源「图片文章」的全屏收集页 —— 就是原来的流程，只是从对话框挪到全屏：
 *
 *   点开文章 → 规则正文在网页里渲染出来 → 边滚动边收集页面上的图片
 *   →「用这些图」把收集结果交回文章列表 → 选择器里勾选 → 加入分组
 *
 * 视频源不走这里（见 [RssVideoScreen]）：进哪个页面由 [RssArticleScreen] 按正文内容判。
 */
@Composable
fun RssImageScreen(
    viewModel: WallpaperViewModel,
    source: RssSource?,
    article: RssArticle,
    onCancel: () -> Unit,
    /** (收集到的图片, 请求头, 当前正在播的那条) */
    onUseImages: (List<String>, Map<String, String>, List<String>) -> Unit,
) {
    var ruleHtml by remember(article.sourceId, article.guid) { mutableStateOf<String?>(null) }

    LaunchedEffect(article.sourceId, article.guid) {
        ruleHtml = try {
            viewModel.loadRssArticleContent(article).html
        } catch (_: Throwable) {
            ""
        }
    }

    // 正文还没取到就先显示一个占位：不给 WebView 丢空内容，避免白屏。
    val html = ruleHtml ?: return
    RssWebArticleDialog(
        source = source,
        url = article.link,
        ruleHtml = html,
        fullScreen = true,
        onDismiss = onCancel,
        onCollect = { urls ->
            // 「用这些图」：交回列表页，由它打开选择器（勾选后加入分组）。
            // 第三个参数是"当前正在播的那条"——图片源没有播放器，留空。
            onUseImages(urls, emptyMap(), emptyList())
        },
    )
}
