package com.wallpaperswitcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperswitcher.R
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.OnlineBrowser
import com.wallpaperswitcher.engine.OnlineVideoThumbs
import kotlin.math.roundToInt

/**
 * 网盘媒体的全屏查看器 —— 交互对齐 OpenList 的网页预览：
 *
 *  - **当前目录内左右滑动**切换上一张/下一张（图片和视频混排，与网格顺序一致）；
 *  - 顶部显示 `序号/总数` 与文件名，右上角关闭；
 *  - 图片支持**双指缩放 + 双击放大**（放大后单指拖动平移，缩回原状后左右滑动仍然切换）；
 *  - 视频沿用 ExoPlayer 流式播放，切走即释放解码器；首帧占位 + 「缓冲中…」；
 *  - 向下拖拽超过阈值关闭（单手操作）。
 *
 * 全部是**在线读取**：图片走 Coil 带认证头直接拉，视频走 Range 流播，都不落盘。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun OnlineMediaViewerDialog(
    entries: List<OnlineBrowser.Entry>,
    startIndex: Int,
    headers: Map<String, String>,
    /** 正在为"设为壁纸"下载时置 true，底部显示文案。 */
    applying: Boolean,
    onDismiss: () -> Unit,
    onAddToGroup: (OnlineBrowser.Entry) -> Unit,
) {
    if (entries.isEmpty()) return
    val safeStart = startIndex.coerceIn(0, entries.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeStart) { entries.size }
    var dragOffset by remember { mutableStateOf(0f) }
    var heightPx by remember { mutableStateOf(0) }
    val dismissThresholdPx = heightPx * 0.18f
    val current = entries[pagerState.currentPage.coerceIn(0, entries.lastIndex)]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { heightPx = it.height }
                    .offset { IntOffset(0, dragOffset.roundToInt()) }
                    .pointerInput(dismissThresholdPx) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dismissThresholdPx > 0f && dragOffset > dismissThresholdPx) {
                                    onDismiss()
                                } else {
                                    dragOffset = 0f
                                }
                            },
                            onDragCancel = { dragOffset = 0f },
                        ) { _, dragAmount: Float ->
                            dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                        }
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = current.displayName,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} / ${entries.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_back))
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    // 预组合左右各一页：滑过去时图片已经在加载，切换明显更跟手。
                    beyondBoundsPageCount = 1,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) { page ->
                    val entry = entries[page]
                    val url = entry.url
                    if (url.isNullOrBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.online_viewer_failed),
                                color = Color.White,
                            )
                        }
                    } else if (entry.mediaType == MediaTypes.VIDEO) {
                        // 只有"当前页且已经停稳"才建播放器：滑走/滑动中立即释放，
                        // 避免同时开多个解码器（4K 一个就够重）。
                        if (page == pagerState.currentPage && !pagerState.isScrollInProgress) {
                            OnlineVideoSurface(
                                url = url,
                                headers = headers,
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                        }
                    } else {
                        ZoomableImage(url = url, headers = headers, title = entry.displayName)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (applying) {
                            stringResource(R.string.online_viewer_downloading)
                        } else {
                            stringResource(R.string.online_viewer_online_hint)
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !applying,
                        onClick = { onAddToGroup(current) },
                    ) { Text(stringResource(R.string.online_viewer_add_to_group)) }
                }
            }
        }
    }
}

/** 在线图片：Coil 直连（带认证头）+ 双击放大 / 放大后拖动平移。 */
@Composable
private fun ZoomableImage(url: String, headers: Map<String, String>, title: String) {
    val context = LocalContext.current
    var scale by remember(url) { mutableStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .crossfade(true)
            .build()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 手势分流：**只有双指、或已经放大时才吃手势**，1x 单指左右滑动留给
            // HorizontalPager 翻页（OpenList 手感）。不能用 detectTransformGestures：
            // 它会把单指平移一起消费掉，实测翻不动页。
            .pointerInput(url) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var event: androidx.compose.ui.input.pointer.PointerEvent
                    do {
                        event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // 双指：按两指间距比值缩放 + 质心位移平移。
                            // 手算而不用 calculateZoom/calculatePan：这两个 API 在不同
                            // Compose 版本里接收者类型变过（编译期就对不上），手算最稳。
                            val curDist = (pressed[0].position - pressed[1].position).getDistance()
                            val prevDist =
                                (pressed[0].previousPosition - pressed[1].previousPosition)
                                    .getDistance()
                            val zoom = if (prevDist > 1f) curDist / prevDist else 1f
                            val pan = (pressed[0].position - pressed[0].previousPosition +
                                (pressed[1].position - pressed[1].previousPosition)) / 2f
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            scale = next
                            if (next > 1.01f) offset += pan else offset = Offset.Zero
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && scale > 1.01f) {
                            // 已放大：单指拖动平移（此时才吃掉手势，1x 时留给翻页）。
                            offset += pressed[0].position - pressed[0].previousPosition
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(url) {
                detectTapGestures(
                    onDoubleTap = {
                        // 双击在 1x 与 2.5x 之间切换（OpenList 的手感）。
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

/** 在线视频：ExoPlayer + 带认证头的 HttpDataSource；首帧占位 + 缓冲指示。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun OnlineVideoSurface(
    url: String,
    headers: Map<String, String>,
) {
    val context = LocalContext.current
    var placeholder by remember(url) { mutableStateOf<Bitmap?>(null) }
    var showPlaceholder by remember(url) { mutableStateOf(true) }
    /**
     * 是否已经渲染出第一帧。转圈的显示条件**只看这个**，不看 `playbackState`
     * —— 状态回调在"监听注册晚于 prepare"时会漏（真机上遇到转圈永不消失），
     * 而"有没有帧"不会骗人。
     */
    var hasFrame by remember(url) { mutableStateOf(false) }
    /** 播放出错时的原因码（null = 没出错）。 */
    var errorText by remember(url) { mutableStateOf<String?>(null) }

    // 首帧：多数情况下网格里已经解过，这里直接命中磁盘缓存。
    LaunchedEffect(url) {
        placeholder = OnlineVideoThumbs.load(context, url, headers)
    }

    val player = remember(url) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(com.wallpaperswitcher.engine.WebDavProps.USER_AGENT)
            .createDataSource()
        val mediaSource = ProgressiveMediaSource.Factory { dataSource }
            .createMediaSource(MediaItem.fromUri(url))
        com.wallpaperswitcher.util.AppLog.d(
            "OnlineViewer",
            "player create …" + url.takeLast(28),
        )
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                hasFrame = true
                showPlaceholder = false
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 播放失败必须可见：只挂 onRenderedFirstFrame 时，出错就只剩一个转圈。
                errorText = "${error.errorCodeName}" +
                    (error.cause?.javaClass?.simpleName?.let { " / $it" } ?: "")
                com.wallpaperswitcher.util.AppLog.w(
                    "OnlineViewer",
                    "playback error: ${error.errorCodeName} cause=${error.cause}",
                )
            }
        }
        player.addListener(listener)
        onDispose {
            try {
                player.removeListener(listener)
                player.stop()
                player.release()
                com.wallpaperswitcher.util.AppLog.d(
                    "OnlineViewer",
                    "player release …" + url.takeLast(28),
                )
            } catch (_: Throwable) {
            }
        }
    }

    // 控件和画面在同一个界面里（上下排列），不是浮在视频上的覆盖层。
    Column(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            // 用 layout 里的 TextureView 版 PlayerView（见 view_player_texture.xml）：
            // 程序化创建无法指定 surface_type，只能靠 inflate。
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(
                    R.layout.view_player_texture,
                    android.widget.FrameLayout(ctx),
                    false,
                ) as PlayerView).apply {
                    this.player = player
                }
            },
            onRelease = { view -> view.player = null },
        )
        val frame = placeholder
        if (showPlaceholder && frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        errorText?.let { reason ->
            Text(
                text = stringResource(R.string.online_viewer_play_failed, reason),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (!hasFrame && errorText == null) {
            // 不用 M3 的 CircularProgressIndicator：捆绑的 animation-core 缺少它需要的
            // keyframes 方法，一点就 NoSuchMethodError 崩进程（本项目历史坑）。
            Text(
                text = stringResource(R.string.online_viewer_buffering),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
}
