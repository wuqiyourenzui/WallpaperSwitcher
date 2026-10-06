package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.LogText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 视频会话：起播 / 停止 / 看门狗 / 锁屏释放后的续播位置，从
 * `LiveWallpaperService` 拆分出来（第 21 轮解构）。
 *
 * 引擎只通过 [Host] 提供表面状态与渲染器，并在视频"起不来/卡死"时收到
 * [Host.onVideoStartFailed] —— 换哪一张、怎么恢复仍由引擎决定；这里只负责
 * "会话是否活着"这一件事（以前它散落在引擎的 `videoMode` / `videoHealthJob` /
 * `resumeVideoMediaId` / `resumeVideoPositionUs` 四个字段和一整段看门狗里）。
 */
internal class VideoSessionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tag: String,
    private val host: Host,
) {

    internal interface Host {
        fun surfaceReady(): Boolean
        fun renderer(): WallpaperRenderer?

        /** 视频起不来或卡死：引擎负责换一张并恢复显示。 */
        fun onVideoStartFailed()

        fun recoveryFailCount(): Int
        fun setRecoveryFailCount(value: Int)

        /**
         * The renderer's video generation, or -1 when no renderer exists.
         *
         * The watchdog captures it when it starts and re-checks it before
         * reporting a stall: a playback that was superseded mid-poll (user
         * switch, rotation, surface rebuild) must not be judged at all.
         */
        fun videoGeneration(): Int
    }

    /** True while a video session is expected on screen (replaces `videoMode`). */
    @Volatile
    var active = false
        private set

    private var healthJob: Job? = null

    /**
     * Media id + position (µs) to resume at, remembered when the video session is
     * released while the device is locked (see the engine's release runnable).
     * Consumed once by the next [resumePositionFor] for the SAME media; a
     * different video - or a user switch - starts at 0.
     */
    @Volatile
    private var resumeMediaId = 0L

    @Volatile
    private var resumePositionUs = 0L

    /** The session is (about to be) on screen - no renderer call, see [start]. */
    fun markActive() {
        active = true
    }

    /**
     * The media on screen is not a playing video any more (image/GIF switch,
     * failed start). Only clears the flag: tearing the decoder down is [stop]'s
     * job, and several callers keep the session alive on purpose (rotation).
     */
    fun markInactive() {
        active = false
    }

    /** Remember where a video being released while locked should resume. */
    fun rememberResume(mediaId: Long, positionUs: Long) {
        resumeMediaId = mediaId
        resumePositionUs = positionUs
    }

    /**
     * Position (µs) this video should resume at, when it is the media whose
     * session was released while locked (see the fields above). 0 = play from
     * the beginning. The remembered value is consumed so a later switch back
     * to the same clip starts normally.
     */
    fun resumePositionFor(mediaId: Long): Long {
        if (mediaId <= 0L || mediaId != resumeMediaId) return 0L
        val position = resumePositionUs
        if (position > 0L) {
            AppLog.d(tag, "Resuming video at ${position / 1000}ms (lock release)")
        }
        return position
    }

    /**
     * Start (or restart) the video session: fails without touching the renderer
     * when the surface or the renderer is not ready, otherwise marks the session
     * active, starts playback and arms the watchdog.
     */
    fun start(
        uriStr: String,
        scaleMode: ScaleMode,
        startPositionUs: Long = 0L
    ): Boolean {
        if (!host.surfaceReady()) {
            AppLog.w(tag, "startVideo: surface not ready")
            return false
        }
        val r = host.renderer()
        if (r == null) {
            AppLog.w(tag, "startVideo: renderer not ready")
            return false
        }
        active = true
        AppLog.d(tag, "startVideo: ${LogText.short(uriStr)}")
        r.startVideo(uriStr, scaleMode, startPositionUs)
        // A video really started: the pending resume belongs to it (or was
        // stale for a different clip) - either way it must not leak into a
        // later switch.
        resumeMediaId = 0L
        resumePositionUs = 0L
        startHealthMonitor()
        return true
    }

    /** Stop the session: clear the flag, disarm the watchdog, free the decoder. */
    fun stop() {
        active = false
        cancelHealthMonitor()
        host.renderer()?.stopVideo()
        // Short grace only: start() joins the old decode thread again
        // internally, so waiting 500ms here would double the worst-case
        // stall on rapid video switches. The generation guard keeps a
        // late-exiting old thread from touching the new video's resources.
        host.renderer()?.waitForDecodeThread(120)
    }

    /** Engine teardown / surface destruction: the watchdog must not fire again. */
    fun cancelHealthMonitor() {
        healthJob?.cancel()
        healthJob = null
    }

    /**
     * Watchdog for a stalled video: if the renderer stops presenting
     * frames for 8s while the engine still believes it is playing, report
     * the failure so the switch queue recovers with a different media.
     */
    private fun startHealthMonitor() {
        healthJob?.cancel()
        val startAt = SystemClock.elapsedRealtime()
        // Token of the playback this watchdog was started for. Every poll checks
        // it again: without it, a poll that was already past its `delay()` when
        // the user switched to the NEXT video judged that new video instead
        // (it reads `isVideoPlaying` and `lastVideoFrameAt` straight off the
        // renderer), and MediaFailureRecovery then blacklisted the media the
        // user had just switched to and replaced it again.
        val generationToken = host.videoGeneration()
        healthJob = scope.launch {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            var lastCheckAt = SystemClock.elapsedRealtime()
            // Deadline of the "this video never produced a frame" window. It
            // is pushed forward while playback is paused on purpose, so a
            // video that was started while the wallpaper was hidden is not
            // declared broken the moment it becomes visible again.
            var noFrameDeadlineAt = startAt + 8_000L
            // Interval of the NEXT check: the fast one while the wallpaper is
            // visible, the slow one while it is hidden (see the two branches
            // below). Chosen at the end of each iteration.
            var pollMs = VIDEO_WATCHDOG_POLL_MS
            while (isActive) {
                delay(pollMs)
                pollMs = VIDEO_WATCHDOG_POLL_MS
                if (!active || host.renderer()?.isVideoPlaying != true) return@launch
                // A newer video owns the renderer now: this watchdog is stale and
                // must not report anything (see `generationToken`).
                if (host.videoGeneration() != generationToken) return@launch
                val nowCheck = SystemClock.elapsedRealtime()
                // Missed polls => this process was frozen (doze / screen off).
                // That window must not be counted as playback time: refresh
                // the reference and continue instead of "recovering".
                if (nowCheck - lastCheckAt > 30_000L) {
                    host.renderer()?.resetVideoFrameClock()
                    lastCheckAt = nowCheck
                    continue
                }
                lastCheckAt = nowCheck
                // Screen off: the platform freezes the decoder (and often the
                // whole process), so "no frames" is expected - NOT a stall.
                // Keep the watchdog's reference fresh so the frozen window is
                // never counted (the device log showed a 102s "stall" that was
                // exactly the screen-off period, which made the engine switch
                // away from the video the moment the screen came back).
                if (pm?.isInteractive != true) {
                    host.renderer()?.resetVideoFrameClock()
                    noFrameDeadlineAt = SystemClock.elapsedRealtime() + 8_000L
                    // Nothing to detect while the screen is off: check once a
                    // minute instead of every 10s.
                    pollMs = VIDEO_WATCHDOG_HIDDEN_POLL_MS
                    continue
                }
                // Wallpaper hidden (another app in front / the system
                // live-wallpaper dialog): the video is paused on purpose and
                // produces no frames - that is not a stall.
                if (host.renderer()?.powerSaveMode == true) {
                    host.renderer()?.resetVideoFrameClock()
                    noFrameDeadlineAt = SystemClock.elapsedRealtime() + 8_000L
                    // Same as above: a wallpaper covered by another app used to
                    // cost 6 wakeups per minute for as long as the user stayed
                    // in that app.
                    pollMs = VIDEO_WATCHDOG_HIDDEN_POLL_MS
                    continue
                }
                val now = nowCheck
                val last = host.renderer()?.lastVideoFrameAt ?: 0L
                if (last < startAt) {
                    // No frame since this video started. Slow/cloud sources
                    // can take a while for the first frame, but 8s is
                    // already generous on-device; recovering sooner keeps
                    // the black-screen window short on devices whose codec
                    // path never produces frames.
                    if (now > noFrameDeadlineAt) {
                        AppLog.w(tag, "Video never presented a frame in ${(now - startAt) / 1000}s; recovering (last=$last)")
                        host.onVideoStartFailed()
                        return@launch
                    }
                } else if (now - last > 12_000L) {
                    AppLog.w(tag, "Video stalled: no frame for ${(now - last) / 1000}s; recovering")
                    host.onVideoStartFailed()
                    return@launch
                } else {
                    // Playing healthily: any previous recovery failures are
                    // stale, so auto-recovery can kick in again if needed.
                    if (host.recoveryFailCount() > 0) host.setRecoveryFailCount(0)
                }
            }
        }
    }

    private companion object {
        /** Watchdog poll while the wallpaper is visible. */
        private const val VIDEO_WATCHDOG_POLL_MS = 10_000L

        /** Poll while hidden / screen off: nothing to detect, so wake rarely. */
        private const val VIDEO_WATCHDOG_HIDDEN_POLL_MS = 60_000L
    }
}
