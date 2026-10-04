package com.wallpaperswitcher.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.wallpaperswitcher.engine.RssHttp

/**
 * 订阅源视频播放的清晰度封顶：1080p。再高的档位（源的清单里常见 4K）在手机上和
 * 这类 CDN 上只会让播放一直卡在缓冲，画质收益也看不出来。
 */
private const val MAX_VIDEO_WIDTH = 1920
private const val MAX_VIDEO_HEIGHT = 1080

/**
 * 阅读-style video playback: Media3/ExoPlayer handles plain mp4 **and** HLS
 * (`media3-exoplayer-hls` is on the classpath), and the data source carries the
 * source's Referer / UA / Cookie so signed CDNs serve the stream.
 *
 * `@OptIn(UnstableApi::class)`：`PlayerView` 属于 Media3 的 unstable API，
 * 不标注时 lint 直接把它当**错误**（NewApi/UnsafeOptInUsageError），会让
 * `:app:lintDebug` 失败。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun RssVideoPlayer(
    url: String,
    headers: Map<String, String>,
    modifier: Modifier = Modifier,
    /**
     * 变化时重建播放器。重试同一地址（签名过期后重抓）时地址字符串可能一模一样，
     * 只按 [url] 记忆会复用那个已经报错的实例。
     */
    mediaKey: Int = 0,
    /** 播放失败时回调（调用方决定重抓正文还是就此作罢）。 */
    onError: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val player = remember(url, mediaKey) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(RssHttp.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(60_000)
            .setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // Logged so a playback failure can be diagnosed later.
                        com.wallpaperswitcher.util.AppLog.w(
                            "RssVideo",
                            "playback failed: ${error.errorCodeName}: " +
                                "${error.message?.take(160).orEmpty()} ($url)"
                        )
                        onError?.invoke(error.errorCodeName)
                    }
                })
                setMediaItem(MediaItem.fromUri(url))
                // 不封顶的话 ExoPlayer 会一直挑清单里最高那档（源通常给 1080），
                // 带宽跟不上就一直转圈（真机实测 1080 档在这条网络上填不满缓冲）。
                // 封到 1080 让它在 720/480 里自适应，和阅读里播放器的行为一致。
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(MAX_VIDEO_WIDTH, MAX_VIDEO_HEIGHT)
                    .build()
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
    )
}
