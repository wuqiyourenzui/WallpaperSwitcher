package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import com.wallpaperswitcher.engine.ScreenPowerPolicy
import com.wallpaperswitcher.util.AppLog

/**
 * 电源/可见性状态机，从 `LiveWallpaperService` 拆分出来（逻辑逐字搬移）。
 *
 * 三个输入（壁纸可见性、我们自己的 UI 是否在前台、屏幕是否亮着）每次都会
 * 重新推导出渲染器的 power-save 状态；带可见性 blip 合并与退出动画宽限。
 * 引擎侧通过 [Host] 提供渲染器/GIF/前台状态，控制器不直接触碰引擎。
 */
internal class PowerSaveController(
    context: Context,
    private val mainHandler: Handler,
    private val tag: String,
    private val host: Host,
) {

    internal interface Host {
        fun engineDestroyed(): Boolean
        fun isPreview(): Boolean
        fun appInForeground(): Boolean
        /** Renderer's `powerSaveMode`, or null when no renderer exists yet. */
        fun rendererPowerSaveMode(): Boolean?
        fun setRendererPowerSave(paused: Boolean)
        fun muteAudioKeepingVideo()
        fun unmuteAudioReanchored()
        fun resetVideoFrameClock()
        fun nudgeGif()
    }

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    /**
     * Power-save input: the wallpaper's visibility as the power-save path sees
     * it. It may be optimistically true for a moment after our own UI closes
     * (see the engine's applyAppForeground).
     */
    @Volatile private var visibleInput = true
    private var powerSavePending: Runnable? = null
    /**
     * When our own UI last left the foreground (0 = it never did / it is back).
     * A resume inside [APP_EXIT_RESUME_GRACE_MS] of that is delayed, so the
     * Activity's close animation finishes before the decoder/GL/audio come
     * back.
     */
    private var appLeftAtMs = 0L
    /** Pending "pause the decode after the app-open animation" task. */
    private var appEntryPauseRunnable: Runnable? = null
    /** True while [powerSavePending] is a delayed visibility-blip pause. */
    private var pauseCoalescePending = false
    /**
     * When the floating-button overlay window last changed (shown, hidden or
     * removed). A "wallpaper covered" report that arrives right after this is
     * plausibly the ROM reacting to OUR window, and only such a report may be
     * coalesced.
     */
    @Volatile private var floatingWindowChangedAtMs = 0L

    fun setVisibleInput(visible: Boolean) {
        visibleInput = visible
    }

    fun noteFloatingWindowChanged() {
        floatingWindowChangedAtMs = SystemClock.elapsedRealtime()
    }

    /** The app came to the front: no exit grace; pause decode after the open animation. */
    fun onAppEntered() {
        // Back already (a quick app switch): no exit grace is pending.
        appLeftAtMs = 0L
        val pauseForUi = Runnable { refresh("app-foreground") }
        appEntryPauseRunnable = pauseForUi
        mainHandler.postDelayed(pauseForUi, APP_ENTRY_PAUSE_GRACE_MS)
    }

    /** The app went away: arm the exit grace and re-evaluate. */
    fun onAppLeft() {
        appLeftAtMs = SystemClock.elapsedRealtime()
        // The app is gone again before the entry grace expired: that pending
        // pause evaluation would only re-derive the same state.
        appEntryPauseRunnable?.let { mainHandler.removeCallbacks(it) }
        appEntryPauseRunnable = null
        refresh("app-left")
    }

    /**
     * Re-evaluate the renderer's power-save state after one of the three
     * inputs changed: the wallpaper's visibility ([visibleInput], set by
     * `onVisibilityChanged`), our own UI's foreground state ([Host.appInForeground])
     * and the screen state (read live).
     *
     * The state is DERIVED from those three every time instead of remembering
     * "the last request": the tablet log showed up to ~540 ON/OFF flips per
     * hour while the picker was open, every one of them a decoder pause/resume
     * plus an audio restart, because a `visible=true` callback and the
     * app-foreground signal kept overwriting each other. With derivation a
     * burst of disagreeing callbacks can only produce one transition.
     *
     * Two further guards, both driven by the tablet log (see 4.9.32):
     *
     * 1. **A resume is only applied while the screen really is on.** ROMs
     *    deliver a spurious "visible" callback while the screen is off (one
     *    arrived 13s after the screen-off event); resuming then decodes and
     *    renders into an invisible surface. The real state is re-read when the
     *    decision is applied, so a callback that arrives with the wrong
     *    ordering cannot win.
     * 2. **A resume is debounced by [VISIBILITY_DEBOUNCE_MS].** Window/app
     *    transitions deliver visibility callbacks in bursts, and on the video
     *    path every flip is a decoder pause/resume plus a playback-clock
     *    re-anchor. A pause is still applied immediately: hiding the wallpaper
     *    has to stop the decode and mute the audio at once (the "声音没立刻关"
     *    report).
     *
     * @param hint the signal that triggered this re-evaluation; used for the
     *   log line of a resume (a pause logs every input that asked for it).
     */
    fun refresh(hint: String) {
        if (host.engineDestroyed()) return
        val reasons = ScreenPowerPolicy.pauseReasons(
            wallpaperVisible = visibleInput,
            appInForeground = host.appInForeground(),
            screenInteractive = isScreenInteractive()
        )
        val pause = reasons.isNotEmpty()
        // A new evaluation supersedes whatever was pending. If the pending one
        // was a coalesced visibility pause and the inputs are visible again,
        // the blip was absorbed entirely - worth a log line, because that is
        // the only way to measure how often the ROM's oscillation would have
        // paused the engine.
        if (powerSavePending != null) {
            if (!pause && pauseCoalescePending) {
                AppLog.d(
                    tag,
                    "Visibility blip coalesced: no pause/resume for the blip " +
                        "| preview=${host.isPreview()}"
                )
            }
            powerSavePending?.let { mainHandler.removeCallbacks(it) }
            powerSavePending = null
        }
        pauseCoalescePending = false
        if (pause) {
            // Only a visibility loss that can plausibly be OUR own
            // floating-button window is coalesced. The ROM reports the
            // wallpaper as covered for ~250-400ms after every add/remove of
            // an APPLICATION_OVERLAY window, and only that oscillation may be
            // absorbed. Anything else - a real app coming to the front - must
            // stop the decode AND mute the audio at once: the old code
            // coalesced every "visibility"-only pause by
            // VISIBILITY_PAUSE_COALESCE_MS, which is exactly the reported
            // "切到其他应用，声音不能立刻停".
            val blipPossible = ScreenPowerPolicy.isVisibilityBlipOnly(reasons) &&
                SystemClock.elapsedRealtime() - floatingWindowChangedAtMs <=
                BLIP_COALESCE_WINDOW_MS
            if (blipPossible) {
                // A brief "covered" report caused by our own overlay is
                // delayed so it never reaches the renderer.
                val runnable = Runnable {
                    pauseCoalescePending = false
                    apply(hint)
                }
                pauseCoalescePending = true
                powerSavePending = runnable
                mainHandler.postDelayed(runnable, VISIBILITY_PAUSE_COALESCE_MS)
            } else {
                apply(hint)
            }
        } else {
            // A resume triggered by OUR app leaving the foreground (or by the
            // screen coming back) is a deliberate transition, not a window
            // burst: the wallpaper is in front and interactive. Debouncing it
            // froze the video for ~250ms after every return to the desktop
            // (reports: 「设置视频为壁纸后返回桌面黑屏一会才开始播放」、
            // 「进出壁纸软件后视频卡一下再播放」). The blip protection is not
            // lost: a covered report that follows is handled by the pause path
            // (and a brief one is coalesced by VISIBILITY_PAUSE_COALESCE_MS).
            val deliberateResume = !host.appInForeground() && visibleInput &&
                isScreenInteractive()
            // Our own UI just left: let its exit animation finish before the
            // decoder/GL/audio come back. Measured - resuming inside that
            // animation made the video's first frames uneven, because the
            // engine shares this process with the Activity that is animating
            // (that is why entering/leaving OUR app looks worse than other
            // apps, which never touch this process).
            val sinceAppLeft = if (appLeftAtMs > 0L) {
                SystemClock.elapsedRealtime() - appLeftAtMs
            } else {
                Long.MAX_VALUE
            }
            val delayMs = when {
                sinceAppLeft < APP_EXIT_RESUME_GRACE_MS ->
                    APP_EXIT_RESUME_GRACE_MS - sinceAppLeft
                deliberateResume -> 0L
                else -> VISIBILITY_DEBOUNCE_MS
            }
            if (delayMs <= 0L) {
                apply(hint)
            } else {
                val runnable = Runnable { apply(hint) }
                powerSavePending = runnable
                mainHandler.postDelayed(runnable, delayMs)
            }
        }
    }

    /** Derive and apply the state from the inputs plus the real screen state. */
    private fun apply(hint: String) {
        powerSavePending = null
        if (host.engineDestroyed()) return
        val reasons = ScreenPowerPolicy.pauseReasons(
            wallpaperVisible = visibleInput,
            appInForeground = host.appInForeground(),
            screenInteractive = isScreenInteractive()
        )
        val pause = reasons.isNotEmpty()
        if (host.rendererPowerSaveMode() == pause) {
            // No state change - but a quick enter/leave of our own UI never
            // paused at all, so an "own UI opened" audio mute still has to be
            // undone here.
            if (!pause) host.unmuteAudioReanchored()
            return
        }
        host.setRendererPowerSave(pause)
        if (!pause) {
            // Audible again: undo an "own UI opened" audio mute, joined to the
            // frame that is on screen right now - the decode kept running while
            // the sound was muted, so continuing where the audio stopped would
            // leave it behind the picture. AFTER the flag flip: the unmute
            // returns early while powerSaveMode is still true.
            host.unmuteAudioReanchored()
        }
        // ON: every input that asked for it, so the log shows WHY (including
        // the case where two inputs disagree). OFF: the signal that triggered
        // this re-evaluation. The instance kind is part of the line because the
        // picker's PREVIEW engine logs into the same file with the same TAG:
        // without it, two instances read like ONE engine flip-flopping (a real
        // analysis mistake - two "ON" lines 9ms apart with different reasons
        // are two engines, not churn).
        val reason = (if (pause) reasons.joinToString(", ") else hint) +
            " | preview=${host.isPreview()}"
        AppLog.d(tag, "Power save ${if (pause) "ON" else "OFF"} ($reason)")
        if (!pause) {
            // Back on the desktop: wake the GIF ticker now instead of letting
            // it wait out its (rare) paused poll.
            host.nudgeGif()
            // Resume a parked video with a FRESH watchdog window. The frames
            // stopped on purpose while hidden, and without this the health
            // monitor fired "Video stalled: no frame for 24s; recovering" the
            // instant the wallpaper came back (Redmi log 10:13:41 → 10:14:07)
            // - a FALSE stall that switched the user away from their video
            // right after unlocking, so a video wallpaper "disappeared" and
            // restarted from the beginning instead of continuing.
            //
            // The screen-on receiver also resets the clock, but it is skipped
            // whenever this visibility debounce has already resumed the engine
            // (the derived state does not change, so apply returns early),
            // which is exactly what happened in that log.
            host.resetVideoFrameClock()
        }
    }

    /**
     * `PowerManager.isInteractive()` for the power-save decision. A failure
     * counts as "interactive": refusing a resume forever would freeze a video
     * wallpaper, which is worse than the extra work of resuming.
     */
    private fun isScreenInteractive(): Boolean = try {
        powerManager.isInteractive
    } catch (_: Exception) {
        true
    }

    private companion object {
        /**
         * How long after our own UI left the foreground a resume is delayed,
         * so the Activity's close animation finishes first.
         */
        private const val APP_EXIT_RESUME_GRACE_MS = 250L
        /**
         * How long after our own UI came to the front the decode pause is
         * delayed: the picture keeps playing through the open animation while
         * the sound is already muted (measured; other apps reach us through
         * the system's covered report ~1.1s later).
         */
        private const val APP_ENTRY_PAUSE_GRACE_MS = 600L
        /**
         * How long a "wallpaper is visible again" request has to hold before the
         * renderer resumes (see [refresh]).
         */
        private const val VISIBILITY_DEBOUNCE_MS = 250L
        /**
         * How long a pause that is caused ONLY by the wallpaper's visibility is
         * delayed (see [ScreenPowerPolicy.isVisibilityBlipOnly]).
         */
        private const val VISIBILITY_PAUSE_COALESCE_MS = 600L
        /**
         * How long after a floating-button window change a "wallpaper covered"
         * report may be treated as OUR OWN overlay's doing (and coalesced).
         */
        private const val BLIP_COALESCE_WINDOW_MS = 1_500L
    }
}
