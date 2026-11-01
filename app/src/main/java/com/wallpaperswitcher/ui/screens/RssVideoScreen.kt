package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.RssArticle
import com.wallpaperswitcher.engine.legado.LegadoRss
import com.wallpaperswitcher.ui.theme.HiLoadingHint
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/**
 * 订阅源的视频播放页：**只负责把视频放出来**。
 *
 * 视频来源是规则（`ruleContent`）求值出的那条媒体地址 —— 正文只给一条地址的源
 * （91porn / Rule34 / Pornhub）和正文是完整播放页 HTML 的源（h视频），最后都收敛成
 * 「一条地址交给原生 Media3 播放器」。这里不再渲染规则网页、不再做图片收集，
 * 也没有「加入分组 / 系统播放器 / 阅读 / 用这些图」那一排按钮：打开就是看视频。
 *
 * 两个真机问题在这里处理：
 *  1. **签名会过期**：Pornhub 这类 CDN 的分片地址带短时效签名，下一次打开或播放
 *     中途可能直接 502。所以失败时**重新抓一次正文**拿新地址再试（[MAX_ATTEMPTS] 次），
 *     而不是抱着过期地址空转。
 *  2. **清单里最高档常常放不动**：交给 ExoPlayer 自适应，播放器侧封顶 1080
 *     （见 [RssVideoPlayer]）。
 *
 * 万一整篇正文里一个地址都没有（规则变了、站点改了），退回网页模式打开原文链接，
 * 至少不是一屏黑。
 */
private const val TAG = "RssVideo"
private const val MAX_ATTEMPTS = 3

private sealed interface VideoState {
    data object Loading : VideoState
    /**
     * **规则驱动**：`ruleContent` 输出的是 HTML，按规则渲染（h视频那类）。
     * 这种内容只有规则知道长什么样，不能交给站点页面。[mediaUrl] 是顺带抠出的
     * 地址（可能为空），给「加入分组」用。
     */
    data class RuleHtml(
        val html: String,
        val mediaUrl: String?,
        val headers: Map<String, String>,
    ) : VideoState

    /**
     * `ruleContent` 只给出一条**媒体地址**（91porn 这类）：不在本地合成播放页，
     * 改为加载**站点自己的播放页**让站点播放器出画，地址从 DOM 读回来。
     */
    data object Sniffing : VideoState
    /** 地址拿到了但放不动（签名/网络）。 */
    data class Failed(val reason: String) : VideoState
}

@Composable
fun RssVideoScreen(
    viewModel: WallpaperViewModel,
    article: RssArticle,
    onCancel: () -> Unit,
    /**
     * 已知的流（网页源在浏览器里嗅到的）：直接用这条播，不再去抓正文。
     * 为空时按正文里的地址播。
     */
    directStream: SniffedStream? = null,
) {
    var state by remember { mutableStateOf<VideoState>(VideoState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    /** 每次重试都换 key，保证播放器真的被重建（而不是复用上一个失败实例）。 */
    var mediaKey by remember { mutableIntStateOf(0) }
    /** 播放器报错只触发一次重试，避免「报错 -> 重建 -> 再报错」的循环。 */
    var errorHandled by remember { mutableStateOf(false) }
    var articleHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showGroupPicker by remember { mutableStateOf(false) }
    /**
     * 站点播放器真正在用的媒体地址（从页面 DOM 里读出来的）。
     *
     * 播放现在交给站点自己的页面，所以「加入分组」要的地址不能只看规则解析结果
     * —— 站点用 `strencode2` 加密地址时，规则解不开（列表里那句"解析失败"），
     * 但站点自己的 JS 解得开。这里存它 DOM 里真正用的那条。
     */
    var resolvedMediaUrl by remember(article.guid) { mutableStateOf<String?>(null) }

    LaunchedEffect(article.sourceId, article.guid, attempt, directStream) {
        // 网页源在浏览器里嗅到的流：地址与请求头都现成，直接播，不再抓正文。
        val known = directStream
        if (known != null) {
            AppLog.d(TAG, "play direct stream: ${known.first.take(160)}")
            articleHeaders = known.second
            mediaKey++
            errorHandled = false
            state = VideoState.RuleHtml(known.first, known.first, known.second)
            return@LaunchedEffect
        }
        state = VideoState.Loading
        val headers = try {
            LegadoRss.requestHeaders(article)
        } catch (_: Throwable) {
            emptyMap()
        }
        // 同时带上会话 cookie（登录态源取流常常要它）；导入器自己会合并源的
        // `header`（Referer/UA）并把 Referer 兜到源的 origin。
        articleHeaders = headers + cookieHeadersFor(article.link)
        // 第 2 次起强制重抓：缓存里的地址可能带着过期签名。
        val content = try {
            viewModel.loadRssArticleContent(article, force = attempt > 0)
        } catch (t: Throwable) {
            AppLog.w(TAG, "content load failed: ${t.javaClass.simpleName}")
            null
        }
        val url = content?.let { extractVideoUrl(it.html, article.link) }
        if (url == null) {
            // 抠不出地址不代表没有视频：CDN 挑战页 / 403 的源，正文里什么都没有。
            // 打开文章页看站点自己的播放器请求什么（见 RssPageSniffer）。
            AppLog.d(TAG, "no media url (attempt=$attempt) -> sniff the page")
            state = VideoState.Sniffing
            return@LaunchedEffect
        }
        // 方案 3 的分流：**两种都按规则来**，区别只在怎么渲染。
        //  1) `ruleContent` 输出是 HTML（h视频那类，规则自己拼播放器标签）→
        //     直接渲染规则结果；
        //  2) 输出只是一条媒体地址（Rule34 / rou.video / 91porn 这类
        //     "正文=一条直链"）→ 这条地址**就是规则的结果**，必须用它播；
        //     渲染交给 WebView 自带的播放器（`loadUrl(媒体地址)`），
        //     而不是我们合成的播放页 —— 合成那条路在这台设备上
        //     CORS 要 XHR、blob 又 `readyState` 停在 0/1，一帧都不出。
        // 只有规则**什么都没给**时才退到站点页面。
        val raw = content?.html.orEmpty()
        if (raw.contains('<')) {
            AppLog.d(TAG, "rule content is HTML (${raw.length}B) -> render it as-is")
            state = VideoState.RuleHtml(raw, url, articleHeaders)
            return@LaunchedEffect
        }
        AppLog.d(
            TAG,
            "rule content is a media url -> play the RULE's url directly: ${url.take(130)}",
        )
        state = VideoState.RuleHtml("", url, articleHeaders)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部只留一条标题 + 返回：播放页不需要别的东西。
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Text(
                    text = article.title.ifBlank { stringResource(R.string.rss_video_title) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 有可播地址时才给「加入分组」：下载的就是这条地址
                // （m3u8 会由导入器拉全部分片再合成 mp4），没地址可存就没意义。
                // 地址可以来自规则解析（下面解析成功时）或站点 DOM（见 resolvedMediaUrl）。
                val canSave = (state as? VideoState.RuleHtml)?.mediaUrl != null || resolvedMediaUrl != null
                if (canSave) {
                    IconButton(onClick = { showGroupPicker = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                            contentDescription = stringResource(R.string.rss_video_save),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val current = state) {
                // 规则输出是 HTML → 按规则渲染（h视频那类源的正路）。
                // 规则输出是一条地址 → 仍然用规则的地址，只是交给 WebView 自带
                // 播放器（见 RssSitePlayer 的 mediaUrl）。
                is VideoState.RuleHtml -> RssSitePlayer(
                    pageUrl = article.link,
                    modifier = Modifier.fillMaxSize(),
                    headers = current.headers,
                    ruleHtml = current.html.takeIf { it.isNotBlank() },
                    mediaUrl = current.mediaUrl,
                )

                VideoState.Loading -> HiLoadingHint(
                    text = stringResource(R.string.rss_video_loading),
                )

                // 规则解不出（strencode2 这类只有站点自己解得开的加密地址）→
                // 加载**站点自己的播放页**，让站点的 JS 起播放器；
                // 再从 DOM 里把真地址读回来给「加入分组」用。
                VideoState.Sniffing -> RssSitePlayer(
                    pageUrl = article.link,
                    modifier = Modifier.fillMaxSize(),
                    headers = articleHeaders,
                    onStreamFound = { url, _ -> resolvedMediaUrl = url },
                )

                is VideoState.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.rss_video_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        if (attempt + 1 < MAX_ATTEMPTS) {
                            TextButton(onClick = { attempt++ }) {
                                Text(stringResource(R.string.rss_reload))
                            }
                        }
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                }
            }
        }
    }

    // 「加入分组」：把当前这条视频（mp4 直接下；m3u8 由导入器拉分片合成 mp4）
    // 下载进选定的壁纸分组 —— 复用订阅源那条导入管线。
    // 优先用**站点 DOM 里读到的地址**：站点用 `strencode2` 加密时规则那条解不开，
    // 但站点自己的 JS 解开了，DOM 里就有真地址；规则解出来的作为兜底。
    val playing = state as? VideoState.RuleHtml
    // 优先用**站点 DOM 里读到的地址**（站点自己解密出来的那条）；规则顺带抠出的
    // 地址作为兜底。
    val pickUrl = resolvedMediaUrl ?: playing?.mediaUrl
    if (showGroupPicker && pickUrl != null) {
        val headers = playing?.headers ?: articleHeaders
        val groups by viewModel.groups.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showGroupPicker = false },
            title = { Text(stringResource(R.string.rss_pick_group)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.online_group_auto),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addRssImagesToGroup(
                                    article, listOf(pickUrl), 0L, headers,
                                )
                                showGroupPicker = false
                            }
                            .padding(vertical = 12.dp),
                    )
                    groups.forEach { group ->
                        Text(
                            group.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.addRssImagesToGroup(
                                        article, listOf(pickUrl), group.id, headers,
                                    )
                                    showGroupPicker = false
                                }
                                .padding(vertical = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 会话 jar 里该地址的 cookie，包成 `Cookie` 头给导入器用。
 *
 * 抓取/播放时 cookie 走 OkHttp 的 jar，而下载走的是导入器自己的客户端（不带 jar），
 * 所以这里显式把 cookie 传下去 —— 登录态的源（91porn 这类）取流常常要它。
 */
private fun cookieHeadersFor(link: String): Map<String, String> {
    if (link.isBlank()) return emptyMap()
    val cookie = try {
        com.wallpaperswitcher.engine.RssCookieStore.cookieHeader(link)
    } catch (_: Throwable) {
        ""
    }
    return if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie)
}
