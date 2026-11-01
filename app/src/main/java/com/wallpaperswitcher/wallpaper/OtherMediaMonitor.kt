package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import com.wallpaperswitcher.util.AppLog

/**
 * 「让位静音」：别的 App 正在放媒体时，壁纸视频的声音主动让开。
 *
 * **不抢音频焦点**（那会停掉用户正在听的音乐，属于替用户做决定），只旁听系统的
 * 播放配置回调 [AudioManager.registerAudioPlaybackCallback]：只要名单里有别人
 * 的媒体类播放，就把壁纸静音；名单清空后再恢复。
 *
 * 判定"是不是别人"的限制（先说清楚，别以后当成 bug）：
 * - 公开 SDK 里的 `AudioPlaybackConfiguration` 只暴露 `getAudioAttributes()`，
 *   `isActive()` / `getClientUid()` 都是 hidden API，所以**无法按 uid 排除自己**。
 * - 因此用"名单里媒体类播放的条数 − 自己在不在播"来判断：自己在出声时名单里必然有
 *   自己那一条；我们被静音后自己那条会从名单消失，剩下的就是别人。
 *
 * 只把 `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_VOICE_COMMUNICATION` 算作"该让位"：
 * 通知音、闹钟不在此列（前者是瞬时的，后者本来就该压过壁纸）。
 */
internal class OtherMediaMonitor(
    context: Context,
    private val tag: String,
    /** 我们自己的音频此刻是否在出声（用来从名单里减掉自己那一条）。 */
    private val weArePlaying: () -> Boolean,
    /** true = 有别人在放媒体，应该让位静音；false = 可以让回来了。 */
    private val onOthersActive: (Boolean) -> Unit,
) {

    private val audioManager: AudioManager? =
        context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    /** 当前判定结果（只在变化时回调，避免每帧都去 mute/unmute）。 */
    @Volatile
    private var othersActive = false

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            evaluate(configs)
        }
    }

    fun start() {
        if (registered) return
        val manager = audioManager ?: return
        try {
            manager.registerAudioPlaybackCallback(callback, handler)
            registered = true
            // 注册的那一刻可能已经有别的 App 在放，先按当前快照评估一次。
            evaluate(manager.activePlaybackConfigurations)
        } catch (t: Throwable) {
            AppLog.w(tag, "registerAudioPlaybackCallback failed: ${t.message}")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            audioManager?.unregisterAudioPlaybackCallback(callback)
        } catch (_: Throwable) {
        }
    }

    /** 当前是否判定为"别人在放媒体"（诊断/测试用）。 */
    fun isOthersActive(): Boolean = othersActive

    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        val mediaLike = configs.count { isMediaLike(it) }
        val ours = if (weArePlaying()) 1 else 0
        val others = othersMediaActive(mediaLike, ours)
        if (others == othersActive) return
        othersActive = others
        AppLog.d(
            tag,
            "Other media playback: ${if (others) "active -> wallpaper audio ducks" else "gone -> wallpaper audio restored"} " +
                "(media-like=$mediaLike, ours=$ours)"
        )
        try {
            onOthersActive(others)
        } catch (t: Throwable) {
            AppLog.w(tag, "duck callback failed: ${t.message}")
        }
    }

    private fun isMediaLike(config: AudioPlaybackConfiguration): Boolean =
        try {
            when (config.audioAttributes?.usage) {
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_VOICE_COMMUNICATION -> true

                else -> false
            }
        } catch (_: Throwable) {
            false
        }
}

/**
 * 「别人在放媒体吗」的判定（纯函数，单测见 OtherMediaMonitorTest）。
 *
 * @param mediaLike 系统名单里媒体类（MEDIA/GAME/VOICE_COMMUNICATION）播出条目数
 * @param ours 我们自己在出声就算 1 条（公开 SDK 拿不到 uid，只能这样减掉自己）
 */
internal fun othersMediaActive(mediaLike: Int, ours: Int): Boolean =
    (mediaLike - ours.coerceAtLeast(0)).coerceAtLeast(0) > 0
