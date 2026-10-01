package com.wallpaperswitcher.wallpaper

import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.LogText

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.engine.BitmapUtils
import com.wallpaperswitcher.engine.MediaPick
import com.wallpaperswitcher.engine.MediaProbe
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.ScreenPowerPolicy
import com.wallpaperswitcher.engine.dropGoneMedia
import com.wallpaperswitcher.engine.SwitchPicking
import com.wallpaperswitcher.engine.WallpaperApplier
import com.wallpaperswitcher.engine.WallpaperGeometry
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private data class SwitchRequest(val source: String, val targetId: Long? = null)

/** Prefetched next image: id, pixels and the GPU quarter turn they need. */
private class PrefetchedImage(val imageId: Long, val bitmap: Bitmap?, val rotateCw: Boolean?)

/**
 * Clarity-enhancement strength for a `clarity_mode` setting value. Single
 * source of truth for both the live settings collector and
 * `applyClarityMode()`, which used to carry their own copies of these numbers.
 */
private fun clarityStrength(mode: String?): Float = when (mode) {
    "off" -> 0f
    "strong" -> 1.6f
    // Default "auto": 1.25x instead of 1.0x. With the display-size decode in
    // BitmapUtils, high-res media no longer needs the unsharp mask at all; the
    // mask now only fires for genuinely upscaled low-res content, where a
    // slightly stronger default makes small images/videos look crisper.
    else -> 1.25f
}

class LiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "LiveWallpaperService"
        // The live wallpaper surface IS the home screen, so the engine picks
        // only from groups whose 应用位置 includes 桌面 (HOME / BOTH). Groups
        // reserved for the lock screen are applied there by the static path.
        private val HOME_SLOT = com.wallpaperswitcher.engine.WallpaperTarget.SLOT_HOME
        const val ACTION_SWITCH = "com.wallpaperswitcher.ACTION_SWITCH"
        const val EXTRA_TARGET_ID = "target_id"
        const val EXTRA_SOURCE = "switch_source"
        const val SOURCE_TIMER = "timer"
        const val SOURCE_UNLOCK = "unlock"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_DOUBLE_TAP = "double-tap"
        /** User tapped the floating switch button (see FloatingSwitchButton). */
        const val SOURCE_FLOATING = "floating-tap"
        private const val SWITCH_SETTLE_DELAY_MS = 30L
        /**
         * How long after the picker closes to wait for the REAL engine before
         * deciding the user cancelled (see onDestroy). The real engine is created
         * by the system as part of confirming, typically within a few hundred ms.
         */
        private const val PICK_CANCEL_GRACE_MS = 1_500L
        /**
         * How long a CONFIRMED pick waits before it applies the media.
         *
         * Short on purpose: the user just tapped 设置壁纸 and is looking at the
         * desktop, so the picked media has to appear right away. The old 1.5s
         * cancel-grace made the wallpaper sit unchanged for ~2s (user report:
         * 点击设置壁纸后卡顿). 250ms is still enough to let a ROM that re-creates
         * the service hand over to the new engine (that path is detected by the
         * generation counter before anything else happens).
         */
        private const val PICK_CONFIRM_GRACE_MS = 250L
        /**
         * Delay before the lock/slot correction runs after a confirmed pick.
         * That correction writes a static wallpaper (full-screen decode + JPEG
         * encode), which must not compete with the desktop switch and the system
         * dialog's closing animation the user is looking at.
         */
        private const val PICK_CONFIRM_ENFORCE_DELAY_MS = 1_200L
        /**
         * How long after our own UI leaves the foreground the engine resumes.
         *
         * The wallpaper engine shares the app's process, so resuming instantly
         * put the decoder + GL + audio back on the CPU/GPU while our Activity was
         * still running its close animation - the video's first frames then came
         * out uneven (user report: 「壁纸软件进出视频就是比其他软件进出会卡顿」;
         * entering/leaving other apps never touches this process). Shorter than
         * the animation, so the picture is live again once the desktop is fully
         * revealed.
         */
        private const val APP_EXIT_RESUME_GRACE_MS = 250L
        /**
         * How long the video keeps PLAYING after our own UI comes to the front,
         * while the audio is already muted.
         *
         * The app's open animation runs for ~0.3-0.5s and the wallpaper is visible
         * underneath it; MIUI reports us as "covered" at ~+0.6s. Pausing the
         * decode at once (the old behaviour) froze the picture in the middle of
         * that animation - a stutter only OUR app has, because other apps reach us
         * through the system's covered report (measured +0.65~1.25s). The audio
         * mutes instantly either way (see WallpaperRenderer.muteAudioKeepingVideo).
         */
        private const val APP_ENTRY_PAUSE_GRACE_MS = 600L
        /**
         * Fire-and-forget bookkeeping that must survive the engine scope being
         * cancelled in onDestroy (e.g. the shuffle deck flush), with the same
         * uncaught-failure guard as the engine's own scope.
         */
        private val ioScope = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + com.wallpaperswitcher.util.logCoroutineFailures(TAG)
        )
        /**
         * Attempts before a failed shuffle-progress write is dropped (it is only
         * a hint).
         *
         * The writes themselves need no serialization any more: since schema v6
         * the deck is one row per shown media and the insert is idempotent
         * (INSERT OR IGNORE), so an "older" write cannot resurrect ids the way a
         * whole-deck snapshot could. That removed the mutex + write-stamp pair the
         * comma-separated version needed.
         */
        private const val SHUFFLE_WRITE_ATTEMPTS = 3
        // When switches happen this quickly after the previous one (e.g. a
        // rapid double-tap burst / quick consecutive taps), skip the fade-in so
        // repeated black flashes never make fast switching feel sluggish.
        private const val RAPID_SWITCH_FADE_SKIP_MS = 700L
        // 20fps cap instead of 30fps: GIF wallpapers look identical (most GIFs
        // are <=15fps) but cost ~1/3 less CPU/GPU for the frame upload + swap.
        private const val GIF_FRAME_INTERVAL_MS = 50L
        /**
         * Upper bound for the frame-clock driven ticker (see
         * [startGifTicker]): a GIF's own frame delay is followed, but a broken
         * delay (or a very slow animation) must never park the ticker so long
         * that a pause/resume or a wallpaper switch is missed.
         */
        private const val GIF_MAX_FRAME_INTERVAL_MS = 1_000L
        /**
         * Extra time added to a GIF's declared frame delay before the next tick
         * (see [gifFrameStepMs]). Without it a tick could land a fraction of a
         * millisecond before the frame is due, present the same frame twice and
         * halve the animation's speed for the rest of that loop.
         */
        private const val GIF_FRAME_DELAY_MARGIN_MS = 8L
        // Prefetch is only worth it while the user is switching RAPIDLY (double
        // tap bursts, repeated floating-button taps): the gap to the previous
        // switch must be this short. Anything else is decoded on demand, which
        // halves the media-library reads per switch.
        private const val PREFETCH_RAPID_GAP_MS = 3_000L
        // An unused prefetched bitmap (one screen-size ARGB) is dropped after
        // this long: it only exists to make a rapid follow-up switch instant.
        private const val PREFETCH_KEEP_MS = 120_000L
        // Image/GIF bitmap loads get the same timeout as video opens, so a
        // stuck cloud/SAF provider can never freeze the switch queue forever.
        private const val BITMAP_LOAD_TIMEOUT_MS = 15_000L
        // Double-tap detection window and slop. Kept slightly more generous
        // than ViewConfiguration's defaults because some launchers compress or
        // jitter the events they forward to the wallpaper window.
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        /**
         * Delay before the floating button is actually removed when the
         * wallpaper stops being visible. Visibility flips back within a few tens
         * of milliseconds during app switches / picker transitions (tablet log
         * 09-20 23:32: hidden at .112, shown again at .149), and every flip used
         * to removeView + addView the overlay twice. A settle removes that churn;
         * showing is still immediate.
         *
         * Raised from 400ms to 1500ms after the phone log (09-30) showed the
         * button removing/adding its overlay every ~2s while the desktop was in
         * use: the ROM reports "wallpaper covered" for 250-400ms in those
         * moments, 400ms was just below that, so the button vanished (a visible
         * blink) and each add/remove fed the ROM another visibility flip.
         *
         * Lowered back to 150ms once the overlay stopped churning: the button is a
         * process-wide window that is only made invisible (see
         * FloatingSwitchButton), so hiding no longer removes/re-adds the window and
         * cannot feed the ROM another flip - which was the actual reason for the
         * long settle. The measured cost of 1500ms was that a *genuine* cover (the
         * user switches to another app) left the button visible for 1.5s (device
         * log 09-30 23:37: covered at .389, hidden at .947 = 1.56s; user report
         * "进入其他应用还是没有立即消失"). 150ms is below perception and still
         * swallows very short flips; our own UI's opening hides it immediately
         * (MainActivity -> FloatingSwitchButton.hideShared).
         */
        private const val FLOATING_BUTTON_HIDE_SETTLE_MS = 150L
        /**
         * How often the GIF ticker re-checks the visibility while it is paused.
         * Only a safety net - [nudgeGifTicker] resumes it immediately when the
         * desktop comes back, so a hidden GIF costs one wakeup per this interval
         * instead of one per second.
         */
        private const val GIF_PAUSED_POLL_MS = 5_000L
        /**
         * Bounds of the GIF watchdog's "wait until the wallpaper is visible"
         * loop (see [startGifHealthMonitor]). 5s steps instead of the old 1s: the
         * loop only waits for a visibility change, and a 1s poll cost up to 60
         * wakeups per started GIF. When the bound is reached while still hidden
         * the health check is abandoned instead of decoding anyway.
         */
        private const val GIF_HEALTH_POLL_MS = 5_000L
        private const val GIF_HEALTH_WAIT_MS = 60_000L
        /**
         * How many consecutive frame-draw failures the GIF ticker tolerates
         * before it gives up. Without a bound, a drawable that throws on every
         * rasterization kept the ticker alive forever at the file's frame rate:
         * 20 stack-trace log lines (each flushed to disk) and 20 wakeups per
         * second, for a wallpaper nobody can see.
         */
        private const val GIF_FRAME_FAILURE_LIMIT = 5
        /**
         * How long a "wallpaper is visible again" request has to hold before the
         * renderer resumes (see [refreshPowerSave]).
         *
         * The system delivers visibility callbacks in bursts during window and
         * app transitions - the tablet log had 139 ON<->OFF flips in 17 hours,
         * some 100ms apart - and every flip on the video path pauses/resumes the
         * decoder and re-anchors the playback clock. Waiting this long collapses a
         * burst into at most one state change, while a real return to the desktop
         * resumes playback after a quarter of a second (the frame on screen stays
         * visible throughout, so this is not perceptible as a stall).
         */
        private const val VISIBILITY_DEBOUNCE_MS = 250L
        /**
         * How long a pause that is caused ONLY by the wallpaper's visibility is
         * delayed (see [ScreenPowerPolicy.isVisibilityBlipOnly]).
         *
         * The phone log measured the blips at 250-400ms (the floating button's
         * overlay remove/add cycle and other window-stack changes), so 600ms
         * absorbs them while a genuine "another app is in front" pause is still
         * applied almost immediately (the audio tail is ≤600ms, below notice).
         * Screen-off and our own UI stay immediate - those must mute at once.
         */
        private const val VISIBILITY_PAUSE_COALESCE_MS = 600L
        /**
         * Delay before a blocked redraw is retried, and how many retries one
         * episode may use (see [retryDrawCurrentImageSoon]). The budget has to
         * cover the longest legitimate wait - a media load that hits its 15s
         * timeout with `switchInProgress` still set - plus its settling time,
         * while still bounding a wedged state (200 x 100ms = 20s).
         */
        private const val REDRAW_RETRY_DELAY_MS = 100L
        private const val REDRAW_RETRY_LIMIT = 200
        /**
         * Video-watchdog intervals (see [startVideoHealthMonitor]). While the
         * wallpaper is actually visible the 10s cadence is what keeps a stalled
         * decoder from freezing the screen; while it is hidden (screen off or
         * another app in front) a frozen decoder is EXPECTED, so the check only
         * has to stay alive as a safety net.
         */
        private const val VIDEO_WATCHDOG_POLL_MS = 10_000L
        private const val VIDEO_WATCHDOG_HIDDEN_POLL_MS = 60_000L
        /**
         * How long the LOCK screen may cover the wallpaper before its
         * decoder/drawable is released instead of parked.
         *
         * Only the lock screen releases (see [scheduleMediaReleaseWhileLocked]):
         * a wallpaper that is merely covered by another app keeps its parked
         * decoder, so returning to the desktop resumes from the same frame.
         * While locked nobody sees the restart that releasing it causes.
         */
        private const val MEDIA_RELEASE_AFTER_SCREEN_OFF_MS = 10_000L
        private const val DOUBLE_TAP_SLOP_DP = 40f
        /**
         * Throttle for the plain "touch received" line (see logTouchThrottled):
         * enough to prove the launcher forwards touches, without two lines per
         * finger tap in the exported log.
         */
        private const val TOUCH_LOG_INTERVAL_MS = 30_000L
        // How many alternative media a switch tries when the picked one is on
        // the "recently failed to start" blocklist.
        private const val FAILED_MEDIA_RETRY_LIMIT = 5
        // TTL of the per-slot enabled-media count (see enabledCountCached).
        private const val SLOT_COUNT_CACHE_MS = 3_000L

        @Volatile
        var engineRunning = false
            private set

        /**
         * True when the engine could not open the database (corrupt file, disk
         * error). The engine keeps running - it just cannot render anything - and
         * this flag makes that state visible in the exported runtime report
         * instead of only in one log line.
         */
        @Volatile
        var databaseUnavailable = false
            private set

        /**
         * True while the SYSTEM live-wallpaper picker is showing a preview of
         * this service.
         *
         * The picker binds a preview engine ("isPreview") for as long as the
         * dialog is open, so this flag is a precise "the user is in the system
         * dialog right now" signal - also when the dialog was opened from the
         * system wallpaper settings instead of from this app.
         *
         * While it is set the timers must not write a static wallpaper: such a
         * write changes the system wallpaper underneath the dialog, the picker
         * tears its preview down mid-apply and crashes (AOSP picker:
         * `WallpaperConnection.connect()` on a null reference - reproduced on the
         * AOSP 14 emulator), and on the tablet it showed up as "设置动态壁纸后
         * 又要重新设置" - the just-applied live wallpaper replaced by a static
         * image.
         */
        @Volatile
        private var previewEngineActive = false
        @Volatile
        private var previewEngineSince = 0L
        /**
         * How many times a REAL (non-preview) engine has been created in this
         * process. A preview engine remembers the value it started with, so its
         * onDestroy can tell "the user confirmed and the system created the real
         * engine" (counter moved on) from "the user cancelled the picker"
         * (counter unchanged).
         *
         * The old test was `engineRunning`, which is TRUE the whole time when our
         * live wallpaper is already the active one - i.e. exactly when a second
         * pick is made - so a cancelled pick was never cleared (tablet log
         * 09-20 23:32: a picker opened and dismissed, and `manual_pick_media_id`
         * stayed in the database; within its 3-minute freshness window any engine
         * recreation would have written that unconfirmed media to the lock
         * screen).
         */
        @Volatile
        private var realEngineGeneration = 0L
        /**
         * HOME cursor value before the last "click an image" pick, and the media
         * that pick previewed.
         *
         * The picker's preview engine renders `LAST_IMAGE_ID`, so setAsLiveWallpaper
         * moves that cursor to make the preview show the tapped media. If the user
         * then CANCELS, the cursor must be moved back: otherwise the real engine,
         * on becoming visible again, compares the cursor with what it is showing,
         * decides the media changed and applies the previewed image - i.e. the
         * home wallpaper changed even though nothing was confirmed (user report:
         * 「点击图片弹出系统动态壁纸界面，就设置了动态壁纸」/「还是会出现」).
         */
        @Volatile private var previewPickPreviousHomeId = -1L
        @Volatile private var previewPickHomeId = -1L
        @Volatile private var previewPickAtMs = 0L
        /**
         * True when a REAL (non-preview) engine was created after the current
         * pick - i.e. the user confirmed the system dialog. Decided by the engine
         * creation itself, not by the delayed cancel heuristic, so it is correct
         * even on ROMs that reuse/re-create engines in an unusual order.
         */
        @Volatile private var realApplySincePick = false
        /**
         * A pending pick is only trusted for this long: a picker that died without
         * its onDestroy must not block the engine's redraws forever.
         */
        private const val PREVIEW_PICK_MAX_AGE_MS = 120_000L

        /**
         * Remember the pick's HOME cursor move. Called by the ViewModel right
         * after [SettingsKeys.LAST_IMAGE_ID] has been written for a tap-to-set.
         */
        fun notePreviewPick(previousHomeId: Long, pickedId: Long) {
            previewPickPreviousHomeId = previousHomeId
            previewPickHomeId = pickedId
            previewPickAtMs = SystemClock.elapsedRealtime()
            realApplySincePick = false
        }

        /**
         * True while a tap-to-set may still be cancelled: the engine must not
         * apply the cursor the picker's preview moved (see notePreviewPick).
         */
        internal fun hasPendingPreviewPick(): Boolean =
            previewPickHomeId >= 0L &&
                !realApplySincePick &&
                SystemClock.elapsedRealtime() - previewPickAtMs < PREVIEW_PICK_MAX_AGE_MS

        /** A confirmed pick keeps its cursor; forget the pending restore. */
        private fun clearPendingPreviewPick() {
            previewPickPreviousHomeId = -1L
            previewPickHomeId = -1L
            previewPickAtMs = 0L
            realApplySincePick = false
        }

        /**
         * HOME cursor as the target of a confirmed pick, for the case where the
         * preview session no longer knows the picked id (see the caller).
         *
         * Returns 0 unless the cursor still points at a media whose group is
         * ENABLED and targets the home screen: a disabled group's media must never
         * be pushed to the engine (user report: 「当分组图片未启用时，里面的图片仍能
         * 设置为壁纸」), and a lock-only media has no business on the desktop.
         */
        private suspend fun homeCursorForConfirmedPick(context: Context): Long {
            return try {
                val appDb = AppDatabase.getInstance(context)
                val cursor = appDb.settingsDao().getLong(SettingsKeys.LAST_IMAGE_ID, 0L)
                if (cursor <= 0L) return 0L
                val media = appDb.wallpaperImageDao().getImageById(cursor) ?: return 0L
                val group = appDb.wallpaperGroupDao().getGroupById(media.groupId) ?: return 0L
                if (!group.isEnabled) return 0L
                if (!com.wallpaperswitcher.engine.WallpaperTarget
                        .fromName(group.target).includesHome
                ) {
                    return 0L
                }
                cursor
            } catch (_: Exception) {
                0L
            }
        }

        /**
         * Undo the HOME cursor move of a pick the user cancelled (see the fields
         * above). Only restores when the cursor still points at the previewed
         * media, so a newer pick or switch is never clobbered.
         */
        internal fun restoreHomeCursorAfterCancelledPick(context: Context) {
            val previous = previewPickPreviousHomeId
            val picked = previewPickHomeId
            val confirmed = realApplySincePick
            clearPendingPreviewPick()
            // A real engine appeared since the pick: the user confirmed, the
            // cursor is exactly where it belongs.
            if (picked < 0L || confirmed) return
            val appContext = context.applicationContext
            ioScope.launch {
                try {
                    val dao = AppDatabase.getInstance(appContext).settingsDao()
                    val current = dao.getLong(SettingsKeys.LAST_IMAGE_ID, 0L)
                    if (current != picked) return@launch
                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, previous.coerceAtLeast(0L))
                    AppLog.d(
                        TAG,
                        "Pick cancelled: HOME cursor restored to ${previous.coerceAtLeast(0L)}"
                    )
                } catch (_: Throwable) {
                }
            }
        }
        /**
         * How long the "system dialog is open" hold may last. It is released by
         * the preview engine's onDestroy and by the real engine's onCreate; this
         * bound only covers the case where the picker died without calling
         * either, so the timers can never be frozen permanently.
         */
        private const val PREVIEW_DIALOG_MAX_HOLD_MS = 5 * 60_000L

        /**
         * See [previewEngineActive]: true while the hold is still valid. Once the
         * bound is exceeded the picker must have died without calling either
         * onDestroy or the real engine's onCreate, so the flag is cleared here -
         * leaving it set made a later `onCreate` of a *preview* engine the only
         * way to refresh the timestamp, i.e. the hold could be re-armed long
         * after the dialog was gone.
         */
        fun isPreviewDialogOpen(): Boolean {
            if (!previewEngineActive) return false
            if (SystemClock.elapsedRealtime() - previewEngineSince >= PREVIEW_DIALOG_MAX_HOLD_MS) {
                previewEngineActive = false
                return false
            }
            return true
        }
        /**
         * Hide the floating double-tap button immediately. Called when the app
         * opens so the hotspot vanishes before the engine's visibility
         * callback catches up (which would otherwise lag during the window
         * transition).
         */
        fun dismissFloatingButtonIfAny() {
            activeEngine?.hideFloatingButtonNow()
        }

        /**
         * Our own activity became visible / was hidden (MainActivity.onStart /
         * onStop).
         *
         * The engine's own onVisibilityChanged lags behind the app-open window
         * animation, and while it lags the wallpaper still counts as visible: the
         * video's audio kept playing and the floating button kept showing for a
         * moment after the app opened ("打开应用时声音无法立刻关闭，还有悬浮旋钮").
         * Reporting the state explicitly makes both react in the same frame.
         *
         * Process-wide on purpose: the engine may not exist yet when the activity
         * starts (cold start), and a freshly created engine reads the flag.
         */
        @Volatile private var appForeground = false

        fun setAppForeground(foreground: Boolean) {
            appForeground = foreground
            activeEngine?.applyAppForeground(foreground)
        }

        /**
         * True while our own UI (MainActivity) is in the foreground.
         *
         * Read by [com.wallpaperswitcher.service.WallpaperSwitchService]'s home
         * timer: the desktop wallpaper cannot be seen behind our app, so the
         * timed switch is allowed to run once per foreground episode and then
         * stays idle (see SwitchPicking.shouldIdleWhileAppInForeground).
         *
         * Process-wide on purpose: the timer service and the UI share this
         * process, and the flag must survive the engine being recreated (MIUI
         * kills the wallpaper engine in the background all the time).
         */
        fun isAppForeground(): Boolean = appForeground
        /**
         * Direct switch trigger for the floating button: bypasses the broadcast
         * round-trip so a double-tap feels instant.
         */
        fun requestSwitchFromOutside(source: String): Boolean {
            return activeEngine?.requestSwitchFromOutside(source) ?: false
        }
        /**
         * Apply one specific media in the running engine.
         *
         * Used when the user confirmed a "click a picture" pick on a ROM that
         * did not re-create the wallpaper service (see the confirm detection in
         * the preview engine's onDestroy): the engine is still alive showing the
         * PREVIOUS media, so nothing would change on screen without this push.
         * Goes through the normal switch queue (same path as a manual pick), so
         * an engine that is already showing this media logs "already playing,
         * skip" instead of restarting it.
         *
         * @return true when a live engine accepted the request.
         */
        internal fun pushConfirmedPickToEngine(targetId: Long): Boolean {
            val engine = activeEngine
            if (engine == null) {
                // No live engine is owning the HOME screen right now (static
                // mode, or MIUI has not re-bound us yet): the cursor was kept,
                // so the next engine start renders the confirmed media.
                AppLog.w(TAG, "Confirmed pick: no live engine to apply id=$targetId (cursor kept)")
                return false
            }
            AppLog.d(TAG, "Confirmed pick pushed to the live engine: id=$targetId")
            engine.requestTargetFromOutside(SOURCE_MANUAL, targetId)
            return true
        }
        /**
         * Re-evaluate the floating button immediately (e.g. after the user
         * returns from granting the overlay permission) instead of waiting for
         * the next visibility change.
         */
        fun refreshFloatingButtonIfAny() {
            activeEngine?.refreshFloatingButtonNow()
        }
        /**
         * Low-memory callback forwarded from the Application: release optional
         * memory (the prefetched next image) while keeping the displayed media
         * intact. No-op when the engine is not alive.
         */
        fun onTrimMemory(level: Int) {
            if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return
            activeEngine?.trimMemoryNow()
        }

        /**
         * True when a switch started now can actually be presented: the active
         * engine's EGL surface is ready. The timer uses this to hold back its
         * catch-up switch for a moment after the screen comes back on, when the
         * wallpaper surface is still being recreated (rendering into it would
         * leave the wallpaper black).
         */
        fun isRenderSurfaceReady(): Boolean = activeEngine?.isRenderSurfaceReadyNow() == true


        /**
         * True when the HOME screen currently shows OUR live wallpaper.
         *
         * `engineRunning` can be stale for a moment - the engine process was
         * killed in the background (very common on MIUI/HyperOS) or a preview
         * engine just tore down - while WallpaperManager still reports this
         * service as the live wallpaper. Writing a static bitmap in that state
         * silently replaces the live wallpaper, so every static HOME write
         * checks this first and hands the switch to the engine instead.
         */
        fun isHomeLiveWallpaper(context: Context): Boolean {
            return try {
                val info = android.app.WallpaperManager.getInstance(context).wallpaperInfo
                info?.component?.packageName == context.packageName
            } catch (_: Exception) {
                false
            }
        }
        @Volatile
        private var activeEngine: LiveWallpaperEngine? = null
    }

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + com.wallpaperswitcher.util.logCoroutineFailures(TAG)
        )
        private val mainHandler = Handler(Looper.getMainLooper())
        private lateinit var db: AppDatabase
        @Volatile private var surfaceReady = false
        // Assume visible until the system tells us otherwise. Some devices do
        // not deliver onVisibilityChanged reliably right after unlock, which
        // would otherwise make double-tap / unlock switching appear dead.
        @Volatile private var isVisible = true
        // True while OUR activity is in the foreground (set by MainActivity via
        // the companion's setAppForeground). While it is, the wallpaper counts as
        // hidden even if onVisibilityChanged has not caught up with the window
        // animation: the audio is muted and the floating button stays off.
        @Volatile private var appInForeground = false
        // Was the wallpaper visible right before our UI came to the front? Only
        // then may leaving the UI resume playback optimistically (see
        // applyAppForeground): coming from another app must not.
        @Volatile private var visibleBeforeAppForeground = false
        private val powerManager: PowerManager by lazy {
            applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        }
        // All switch triggers (timer / double-tap / unlock / manual) are sent
        // through a single serialized queue. A switch in progress never blocks
        // or drops new triggers: they wait in the queue and run in order.
        private val switchChannel = Channel<SwitchRequest>(8)
        private val consumerStarted = AtomicBoolean(false)
        // True while a non-target (auto) switch is already queued or being
        // processed. Rapid triggers (double-tap bursts, timer ticks) coalesce
        // into ONE pending switch instead of piling up N queued requests that
        // each run their own screen-size decode — the root cause of the
        // "rapid tapping stutters after a few switches" behavior.
        private val pendingAutoSwitch = AtomicBoolean(false)
        @Volatile private var switchInProgress = false
        @Volatile private var switchStartedAt = 0L
    @Volatile private var lastSwitchCompletedAt = 0L
    /**
     * Source of the switch currently being executed (see [maybeFade]): a manual
     * tap must show the new picture the moment it is ready, so the user-initiated
     * triggers skip the "fade in from full black" transition that automatic
     * switches use.
     */
    @Volatile private var currentSwitchSource = ""
    /**
     * Media id + position (µs) to resume at, remembered when the video session is
     * released while the device is locked (see `scheduleMediaReleaseWhileLocked`).
     * Consumed once by the next [resumePositionFor] for the SAME media; a
     * different video - or a user switch - starts at 0.
     */
    @Volatile private var resumeVideoMediaId = 0L
    @Volatile private var resumeVideoPositionUs = 0L
        // Next-image prefetch cache: after a switch, the engine decodes the
        // next candidate image in the background so a follow-up switch (rapid
        // double-tap burst, unlock, next timer tick) can display it with
        // near-zero latency instead of decoding ~100-200ms on demand.
        private val prefetchLock = Any()
        private var prefetchedImageId = 0L
        private var prefetchedBitmap: Bitmap? = null
        // When the current prefetch was stored (elapsedRealtime); the delayed
        // expiry task only drops a bitmap that is still the same one.
        @Volatile private var prefetchedAt = 0L
        // Pending "drop the unused prefetch" task, replaced (not stacked) by a
        // newer prefetch.
        private var prefetchExpiryRunnable: Runnable? = null
        // The switch mode the prefetch was computed for. If the user changes
        // the mode (e.g. RANDOM -> SEQUENTIAL) between the prefetch and the
        // next switch, the cached "next" image is stale and must be dropped —
        // otherwise SEQUENTIAL would start from a random prefetch instead of
        // continuing after the currently displayed wallpaper.
        @Volatile private var prefetchedSwitchMode: SwitchMode? = null
        /** GPU quarter turn the prefetched bitmap needs (see takePrefetchCache). */
        @Volatile private var prefetchedRotateCw: Boolean? = null
        private val prefetchInProgress = AtomicBoolean(false)
        private val redrawInProgress = AtomicBoolean(false)
        // Coalesces the 100ms redraw retries (see retryDrawCurrentImageSoon):
        // several triggers can ask for one redraw, and only one retry may be
        // pending at a time. The counter is the bounded budget of the current
        // retry episode.
        private val redrawRetryQueued = AtomicBoolean(false)
        private val redrawRetryCount = java.util.concurrent.atomic.AtomicInteger(0)
        /**
         * True once the single "final attempt" after an exhausted retry budget has
         * been scheduled (see [retryDrawCurrentImageSoon]); reset whenever a
         * redraw really runs.
         */
        private val redrawFinalAttemptDone = AtomicBoolean(false)
        // True while the surface dimensions changed (rotation) but the
        // re-decode for the new size hasn't finished yet. Keeps the OLD bitmap
        // on screen (re-presented at the new quad) during the decode so the
        // transition never shows a blank/half-black frame.
        private val pendingOrientationRedraw = AtomicBoolean(false)
        // Volatile: written on the engine's IO switch consumer and the main
        // thread, read from both. A non-volatile field could leave one thread
        // seeing a stale/recycled reference and uploading a dead bitmap.
        @Volatile private var currentBitmap: Bitmap? = null
        /**
         * Bitmap kept alive while switching from an image to a video (see
         * [retireCurrentBitmap]): it is released as soon as the video's first
         * frame is on screen, when the video fails, on memory pressure and on
         * engine teardown.
         */
        @Volatile private var retiredBitmap: Bitmap? = null
        // Volatile: written by switch execution AND by the live scale-mode
        // setting collector (both IO), read by the GIF thread on every frame.
        @Volatile private var currentScaleMode: ScaleMode = ScaleMode.FIT
        // "自动旋转适配" setting: FILL/STRETCH rotates orientation-mismatched
        // media 90° (images/GIF/video). Written by the settings collector,
        // read by every decode/render path.
        @Volatile private var autoRotateMismatch = true
        // Direction of the auto-rotation (true = clockwise).
        @Volatile private var autoRotateClockwise = true
        // Reused "empty group" placeholder bitmap (screen-size ARGB). Cached so
        // repeated redraws with no media never allocate a fresh screen-size
        // bitmap each time (GC churn / jank on low-RAM devices).
        private var defaultBitmap: Bitmap? = null

        /**
         * Recycle and drop the currently displayed static bitmap. Call when
         * switching to a video/GIF so the old image is not leaked.
         */
        private fun clearCurrentBitmap() {
            val old = currentBitmap
            currentBitmap = null
            if (old != null && !old.isRecycled) old.recycle()
        }

        /**
         * Put the displayed bitmap aside instead of recycling it.
         *
         * Used when switching to a VIDEO: the image must stay on screen (its GL
         * texture keeps the pixels, and the engine redraws it if a surface event
         * arrives) until the new video's first frame is presented, so the bitmap
         * behind it cannot be released at switch time.
         */
        private fun retireCurrentBitmap() {
            val previous = retiredBitmap
            retiredBitmap = currentBitmap
            currentBitmap = null
            if (previous != null && !previous.isRecycled) previous.recycle()
        }

        /** Release the bitmap retired by [retireCurrentBitmap]. */
        private fun releaseRetiredBitmap() {
            val retired = retiredBitmap ?: return
            retiredBitmap = null
            if (!retired.isRecycled) retired.recycle()
        }

        private val shuffleShownIds = ConcurrentHashMap.newKeySet<Long>()
        @Volatile private var shuffleAllCount = 0
        /**
         * Ids shown in the current pass that still have to reach the
         * `shuffle_shown` table (see flushShuffleState). One row per switch - the
         * old code persisted the whole deck as a comma-separated string, which is
         * why it needed a size threshold and a 30s throttle.
         */
        private val pendingShuffleIds = ConcurrentHashMap.newKeySet<Long>()
        // Media ids that recently failed to start (e.g. a video with no video
        // track). Recovery switches skip these so one broken file can never
        // freeze the wallpaper until the next timer tick.
        private val failedMediaIds = ConcurrentHashMap.newKeySet<Long>()

        // Unified EGL renderer — lives across surface recreations
        private var renderer: WallpaperRenderer? = null
        private var rendererInitialized = false
        @Volatile private var videoMode = false
        /**
         * "视频壁纸播放声音" setting, as last read from the database. Kept here as
         * well as in the renderer because the renderer is created lazily on the
         * first surface event, which can happen after the settings flow emitted.
         */
        @Volatile private var videoSoundSetting = false
        @Volatile private var lastDisplayedId = 0L

        private var cachedScreenW = 0f
        private var cachedScreenH = 0f

        // Written on the GIF thread, read from main / engine IO threads, so
        // both are volatile: a stale read could make a superseded ticker keep
        // drawing a drawable that was just closed on the GIF thread.
        @Volatile private var gifDrawable: android.graphics.drawable.AnimatedImageDrawable? = null
        @Volatile private var gifFrameRunnable: Runnable? = null
        // Diagnostics: proves the GIF pipeline actually presented a frame (or
        // shows nothing but "skipped" logs from the renderer when it did not).
        @Volatile private var gifFirstFrameLogged = false
        // GIF frame rasterization runs on its own HandlerThread: at 20fps a
        // full-screen bitmap erase + Canvas draw (~10MB of memory traffic at
        // 1080p) on the MAIN thread stole frames from the app UI whenever a
        // GIF wallpaper was active while the app was open (the wallpaper
        // engine shares the app's main looper). All drawable lifecycle ops
        // (stop/close) are serialized on the same thread so a frame draw in
        // flight can never race a stop/close from the main thread.
        private var gifThread: HandlerThread? = null
        private var gifHandler: Handler? = null
        // Ping-pong buffers: the main thread draws frame N+1 into one buffer
        // while the render thread uploads frame N from the other, so the GL
        // upload can never read a half-drawn frame (tearing).
        private var gifBitmapBuffer: Bitmap? = null
        private var gifBitmapBufferAlt: Bitmap? = null
        // GIF uri whose decode is in flight; a newer switch clears it so a
        // stale decoded drawable can never start after newer media.
        @Volatile private var pendingGifUri: String? = null
        // Detects a decoder that stopped producing frames (e.g. a cloud file
        // whose stream read blocks forever) and triggers automatic recovery.
        private var videoHealthJob: kotlinx.coroutines.Job? = null
        /**
         * Set while a rotation-triggered redraw is (re)starting a video: the
         * fade would stack on the system's own rotation animation, so the
         * first-frame callback skips it once. A normal switch always clears it.
         */
        @Volatile private var suppressFadeUntilFirstFrame = false
        /**
         * A video's fade-in has to be decided when the switch happens (the
         * "rapid switching" check is about how fast the USER is tapping), but
         * applied when the first frame is really on screen. This carries that
         * decision across the decode delay.
         */
        @Volatile private var fadePendingForFirstFrame = false
        /**
         * True when the media is being drawn again after a power-save RELEASE
         * (locked long enough that the decoder was dropped), as opposed to a
         * switch the user asked for.
         *
         * Such a redraw must not fade in: the user just came back to the desktop
         * and expects the wallpaper to be there - a black fade would read as a
         * "black flash while the video rebuilds". Set by the release task,
         * consumed by [drawCurrentImage].
         */
        @Volatile private var resumeFromPowerSave = false
    // GIF watchdog: if no frame reaches the screen (some non-Xiaomi
        // devices fail to present AnimatedImageDrawable frames), fall back to
        // the file's first frame instead of leaving the wallpaper black.
    private var gifHealthJob: kotlinx.coroutines.Job? = null
    /**
     * Whether the current "wallpaper not visible" episode already logged its
     * GIF pause line. Cleared when a frame is presented again while visible, so
     * every hide/show cycle produces exactly one pause/resume pair even when
     * the timer swaps the GIF while it is hidden.
     */
    @Volatile private var gifPauseAnnounced = false
        // Consecutive recovery failures: stops the 1.5s auto-recovery loop
        // when every candidate media is broken, instead of switching forever
        // and burning battery. Reset once a video actually plays.
        @Volatile private var recoveryFailCount = 0

        private val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Only the currently active engine handles switch broadcasts.
                // When the wallpaper is re-applied, a stale engine can still be
                // registered for a moment; without this guard one broadcast
                // would trigger two concurrent switches and corrupt the GL
                // renderer (this showed up as an engine crash right after a
                // timed switch in the logs).
                if (activeEngine !== this@LiveWallpaperEngine) return
                if (intent.action == ACTION_SWITCH) {
                    val targetId = intent.getLongExtra(EXTRA_TARGET_ID, -1L)
                    // Keep the sender's source: the queue uses it to tell user
                    // triggers from timer ticks (a timer tick is dropped instead
                    // of piling up when the queue is full, and it does not
                    // pre-decode the next media - the "每次切换都会访问照片和视频"
                    // optimization). Hard-coding "broadcast" here silently
                    // disabled both rules.
                    val source = intent.getStringExtra(EXTRA_SOURCE) ?: "broadcast"
                    requestSwitch(source, if (targetId > 0) targetId else null)
                }
            }
        }

        // Screen on/off fallback: some devices do not deliver a visibility
        // change when the screen turns off/on, so the engine would otherwise
        // keep decoding video at full speed in the dark.
        private val screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        refreshPowerSave("screen-off")
                        // The keyguard may be showing now: re-evaluate the
                        // lock-screen switch button.
                        updateFloatingButton()
                        // Locked: nobody can see the wallpaper, so a parked
                        // decoder/drawable may be released after a short grace
                        // (see scheduleMediaReleaseWhileLocked). Covers the case
                        // where the user locks the screen while another app was
                        // already covering the wallpaper (no visibility change).
                        scheduleMediaReleaseWhileLocked()
                    }
                    // Only resume full speed if the wallpaper is actually
                    // visible: while the keyguard or another app still covers
                    // it (screen on), keep the low-power throttle active.
                    Intent.ACTION_SCREEN_ON -> {
                        refreshPowerSave("screen-on")
                        // Unlocked: never release what the user is about to see
                        // again (the media is restarted on visibility instead).
                        cancelMediaReleaseWhileLocked()
                        // Restart the video watchdog from now: the frozen
                        // screen-off window must never look like a stall.
                        renderer?.resetVideoFrameClock()
                        updateFloatingButton()
                    }
                }
            }
        }

        // Pure double-tap state machine (see engine.DoubleTapDetector).
        // GestureDetector only reports a double tap on the second ACTION_DOWN
        // and silently fails when the launcher / system delivers an incomplete
        // event stream (Android 16/17 devices and some OEM launchers consume or
        // drop one of the two taps). Tracking both DOWN and UP pairs lets the
        // engine recognize a double tap from whatever subset of events actually
        // reaches the wallpaper window.
        private val doubleTapDetector by lazy {
            com.wallpaperswitcher.engine.DoubleTapDetector(DOUBLE_TAP_TIMEOUT_MS, doubleTapSlopPx)
        }
        // Enabled-media count per screen, cached for SLOT_COUNT_CACHE_MS. One
        // switch plus its prefetch used to run the same COUNT (which walks the
        // group index over the whole library) two or three times; group/media
        // edits are far rarer than a tick, and a stale count can only delay a
        // shuffle-deck reset by one cache window.
        private val slotCountCacheAt = LongArray(2)
        private val slotCountCacheValue = IntArray(2)
        // Launcher-independent double-tap fallback (some Android 16/17
        // launchers stop forwarding touches to the wallpaper window).
        private var floatingButton: FloatingSwitchButton? = null
        /** Pending debounced hide of the floating button (see the constant). */
        private var floatingButtonHideRunnable: Runnable? = null
        /**
         * Power-save inputs (see [refreshPowerSave] / [ScreenPowerPolicy]): the
         * wallpaper's visibility as the power-save path sees it, plus the
         * debounced task that applies a resume. [isVisible] stays the system's
         * truth for drawing; this one may be optimistically true for a moment
         * after our own UI closes (see [applyAppForeground]).
         */
        @Volatile private var powerSaveVisibleInput = true
        private var powerSavePending: Runnable? = null
        /**
         * When our own UI last left the foreground (0 = it never did / it is back).
         * A resume inside [APP_EXIT_RESUME_GRACE_MS] of that is delayed, so the
         * Activity's close animation finishes before the decoder/GL/audio come
         * back (see APP_EXIT_RESUME_GRACE_MS).
         */
        private var appLeftAtMs = 0L
        /** Pending "pause the decode after the app-open animation" task. */
        private var appEntryPauseRunnable: Runnable? = null
        /** True while [powerSavePending] is a delayed visibility-blip pause. */
        private var pauseCoalescePending = false
        /** Pending "release the parked video/GIF" task (see the constant). */
        private var mediaReleaseRunnable: Runnable? = null
        // Throttle the timer self-heal to once every 30s to avoid churn.
        private var lastTimerSelfHealAt = 0L
        /** elapsedRealtime of the last plain touch log line. */
        private var lastTouchLogAt = 0L
        // Serialize floating-button state updates (DB read is async, so two
        // overlapping calls could apply stale state in the wrong order).
        private val floatingButtonUpdateLock = AtomicBoolean(false)
        // An update that arrived while [floatingButtonUpdateLock] was busy.
        // Without this, a fast "hidden while covered -> visible again" burst
        // (e.g. quick app switch back to the desktop) could drop the SHOW
        // request and leave the button missing until the next event.
        private val floatingButtonUpdatePending = AtomicBoolean(false)
        // Stops a queued floating-button update from re-showing the button
        // after the engine was destroyed.
        @Volatile private var engineDestroyed = false
        // Guards reassertTouchEvents() against re-entrancy: a posted
        // setTouchEventsEnabled() can re-enter onSurfaceCreated, but the flag
        // prevents it from re-queueing a second re-assert (avoids a cascade).
        private var touchReassertQueued = false
        /**
         * `MANUAL_PICK_AT` of the pending "set this media" pick when this
         * preview engine was created (-1 = not read yet / read failed). Used to
         * tell "this picker session was cancelled" from "the user has since
         * picked something newer".
         */
        @Volatile private var previewStartManualPickAt = -1L
        /**
         * Wallpaper ids of the SYSTEM / LOCK slots read when the picker opened
         * (-1 = unknown). See [wallpaperIdsChangedSincePick].
         */
        private var pickSystemWallpaperId = -1
        private var pickLockWallpaperId = -1
        private val doubleTapSlopPx: Float by lazy {
            applicationContext.resources.displayMetrics.density * DOUBLE_TAP_SLOP_DP
        }

        /**
         * Remember which system wallpaper the picker started from.
         *
         * Placed here (preview engine creation = the system dialog is opening)
         * and not in `notePreviewPick` because that runs before the picker
         * exists and has no Context.
         */
        private fun capturePickWallpaperIds() {
            try {
                val wm = android.app.WallpaperManager.getInstance(applicationContext)
                pickSystemWallpaperId = wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM)
                pickLockWallpaperId = wm.getWallpaperId(android.app.WallpaperManager.FLAG_LOCK)
                AppLog.d(
                    TAG,
                    "Pick baseline wallpaper ids: system=$pickSystemWallpaperId " +
                        "lock=$pickLockWallpaperId"
                )
            } catch (t: Throwable) {
                pickSystemWallpaperId = -1
                pickLockWallpaperId = -1
                // Visible on purpose: without the ids the "confirmed" signal is
                // blind and a confirmed pick can be mistaken for a cancel (see
                // wallpaperIdsChangedSincePick), so this must be diagnosable from
                // the log alone.
                AppLog.w(TAG, "Pick baseline wallpaper ids unavailable: ${t.message}")
            }
        }

        /**
         * True when a SYSTEM/LOCK wallpaper was written since the picker opened,
         * i.e. the user really confirmed the system dialog.
         *
         * Confirming a live wallpaper that is ALREADY ours does not always
         * re-create this service: on the Xiaomi tablet (25053RP5CC, Android 16)
         * `notifyWallpaperComponentChanged` fires and the AOSP wallpaper id moves
         * (3353 → 3354) while our engine keeps running untouched - so the "a new
         * engine appeared" test in onDestroy() reported "pick cancelled" and the
         * app reverted the cursor, i.e. the tapped media was never applied
         * (user report: 平板无法设置动态壁纸). The wallpaper id is the reliable
         * proof: it changes on every apply and stays put when the user cancels.
         *
         * Read synchronously when the picker's preview engine is torn down -
         * before the timer loops are poked (which could write a static LOCK
         * wallpaper within the cancel-grace window) and after the picker already
         * performed its apply, so this cannot be confused by unrelated writes.
         */
        private fun wallpaperIdsChangedSincePick(): Boolean {
            if (pickSystemWallpaperId < 0 && pickLockWallpaperId < 0) {
                AppLog.w(TAG, "Pick confirmation by wallpaper id unavailable (ids unknown)")
                return false
            }
            return try {
                val wm = android.app.WallpaperManager.getInstance(applicationContext)
                val sys = wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM)
                val lock = wm.getWallpaperId(android.app.WallpaperManager.FLAG_LOCK)
                val changed = (pickSystemWallpaperId >= 0 && sys != pickSystemWallpaperId) ||
                    (pickLockWallpaperId >= 0 && lock != pickLockWallpaperId)
                AppLog.d(
                    TAG,
                    "Pick wallpaper ids: system=$pickSystemWallpaperId->$sys " +
                        "lock=$pickLockWallpaperId->$lock changed=$changed"
                )
                changed
            } catch (t: Throwable) {
                AppLog.w(TAG, "Pick wallpaper ids unreadable: ${t.message}")
                false
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            // The system live-wallpaper picker runs a PREVIEW engine of this
            // service while the user is looking at the preview. A preview must
            // never own the global "the real wallpaper engine is running"
            // state: it used to claim engineRunning/activeEngine and then clear
            // them when the dialog closed, leaving the REAL engine alive while
            // the app believed nothing was running. The next timer tick then
            // wrote a STATIC home wallpaper and destroyed the live wallpaper the
            // user had just set - the "设置动态壁纸后又要重新设置" report
            // (tablet log: setAsLiveWallpaper -> Engine created -> static
            // "Wallpaper applied (home)").
            if (!isPreview) {
                // A new real engine took over: a preview engine from the picker
                // session can now tell "confirmed" from "cancelled".
                realEngineGeneration++
                // A real engine created while the picker's PREVIEW engine is still
                // alive is NOT proof that the user confirmed: MIUI/HyperOS restarts
                // the wallpaper engine in the background, and treating that as a
                // confirmation cleared the pending pick - which left the cancel
                // path with nothing to restore, so the unconfirmed media won on
                // the next visible redraw (the "点了没确认也换壁纸" report).
                // In that case keep the pending pick (and the dialog hold); the
                // preview engine's onDestroy makes the decision.
                val pickerStillOpen = previewEngineActive && hasPendingPreviewPick()
                if (!pickerStillOpen) {
                    // The pick whose preview moved the HOME cursor was CONFIRMED:
                    // the cursor must stay (see the fields above).
                    realApplySincePick = true
                }
                engineRunning = true
                activeEngine = this
                // A cold start brings the activity up first and the engine after
                // it: adopt the flag so the audio does not start playing behind
                // our own UI (see setAppForeground).
                appInForeground = appForeground
                refreshPowerSave("app-foreground")
                if (pickerStillOpen) {
                    AppLog.d(
                        TAG,
                        "Real engine created while the picker is open: pending pick kept " +
                            "(not a confirmation)"
                    )
                } else {
                    // Applying the real wallpaper ends the picker session for good
                    // (also covers a picker that died without its onDestroy).
                    previewEngineActive = false
                    // The pick was confirmed: its cursor move is what the user asked
                    // for, so nothing is restored on the (cancelled) path below.
                    clearPendingPreviewPick()
                }
                // The real wallpaper was applied: the system dialog is closing,
                // so the "now tap 设为壁纸 …" bubble has done its job.
                HintOverlay.dismiss()
            } else {
                // The system picker is open: hold every static write (see
                // previewEngineActive).
                previewEngineActive = true
                previewEngineSince = SystemClock.elapsedRealtime()
            }
            db = try {
                AppDatabase.getInstance(applicationContext)
            } catch (t: Throwable) {
                // An unopenable/corrupt database must never kill the wallpaper
                // process (an Engine callback that throws takes the whole
                // wallpaper down). Every path that touches `db` runs inside its
                // own try/catch, so the engine simply stays blank - and keeping
                // engineRunning/activeEngine claimed still stops the timer from
                // overwriting the user's live wallpaper with a static one.
                AppLog.e(TAG, "Engine created but the database could not be opened", t)
                databaseUnavailable = true
                return
            }
            databaseUnavailable = false
            // Remember which pending "set this media" pick this picker session
            // belongs to (only meaningful for the preview engine; see
            // previewStartManualPickAt). Read here, after `db` is available, so a
            // later re-pick cannot be mistaken for this one.
            if (isPreview) {
                // The picker is opening: remember the wallpaper id this pick
                // starts from so onDestroy() can tell a confirm from a cancel
                // even when the system does not re-create our engine (see
                // wallpaperIdsChangedSincePick).
                capturePickWallpaperIds()
                scope.launch {
                    try {
                        previewStartManualPickAt = db.settingsDao()
                            .getLong(SettingsKeys.MANUAL_PICK_AT, 0L)
                    } catch (_: Throwable) {
                    }
                }
            }
            // Touch events are what the double-tap gesture needs; a driver that
            // rejects the call must not kill the engine either.
            try {
                setTouchEventsEnabled(true)
            } catch (_: Exception) {
            }
            AppLog.d(TAG, "Engine created, touch events enabled")
            // The system live wallpaper dialog always fills the system slot (and
            // the lock slot when "主屏幕和锁定屏幕" is picked). Make the group's
            // 应用位置 win over that choice: lock-only groups get the lock screen
            // back, "两者" groups get the picked media mirrored to the lock, and
            // home-only groups get their previous lock wallpaper restored.
            scope.launch {
                try {
                    kotlinx.coroutines.delay(3_000L)
                    // Only the ACTIVE engine owns the real wallpaper: the
                    // system live-wallpaper picker runs a preview engine too,
                    // and correcting the slots from a preview would rewrite the
                    // lock screen while the user is merely looking at a
                    // wallpaper (observed as repeated lock writes in the log).
                    if (isPreview) {
                        AppLog.d(TAG, "Preview engine: skipping slot enforcement")
                        return@launch
                    }
                    val enforced = WallpaperSwitchService
                        .enforceSlotsAfterLiveApply(applicationContext)
                    AppLog.d(TAG, "Slot enforcement after live wallpaper apply=$enforced")
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Engine went away; nothing to restore.
                } catch (e: Exception) {
                    AppLog.e(TAG, "Slot enforcement after live wallpaper failed", e)
                }
            }
            // Apply the clarity setting live: toggling it in Settings updates
            // the currently displayed wallpaper immediately instead of waiting
            // for the next switch. The per-switch applyClarityMode() below
            // remains as a belt-and-suspenders for the very first render.
            scope.launch {
                try {
                    db.settingsDao().getValueFlow(SettingsKeys.CLARITY_MODE).collect { mode ->
                        // Re-present the current static wallpaper immediately so
                        // toggling the option has a visible effect.
                        //
                        // The system picker's PREVIEW engine never sharpens: its
                        // frames are composited by the picker (rotated when the
                        // tablet is landscape), and the 5-tap unsharp mask over a
                        // full-screen quad stole the GPU the compositor needed -
                        // the user saw the difference as "横屏设置动态壁纸卡顿、
                        // 竖屏不卡". The applied (real) wallpaper still sharpens
                        // exactly as configured.
                        renderer?.applyClarity(if (isPreview) 0f else clarityStrength(mode))
                    }
                } catch (_: Exception) {}
            }
            // Video sound (default off): applied live, so flipping the switch
            // either silences the video that is on screen right now or starts its
            // audio without a re-switch. The system live-wallpaper PREVIEW engine
            // stays silent: it only exists while the picker is open.
            scope.launch {
                try {
                    db.settingsDao().getValueFlow(SettingsKeys.VIDEO_SOUND_ENABLED).collect { value ->
                        val enabled = value?.toBooleanStrictOrNull() == true && !isPreview
                        videoSoundSetting = enabled
                        renderer?.applyVideoSound(enabled)
                    }
                } catch (_: Exception) {}
            }
            // Apply the scale mode live: toggling FIT/FILL/STRETCH in Settings
            // re-fits the CURRENTLY displayed wallpaper immediately instead of
            // waiting for the next switch (mirrors the clarity-mode behavior).
            scope.launch {
                try {
                    db.settingsDao().getValueFlow(SettingsKeys.GLOBAL_SCALE_MODE).collect { mode ->
                        val scaleMode = try {
                            ScaleMode.valueOf(mode ?: ScaleMode.FIT.name)
                        } catch (_: Exception) {
                            ScaleMode.FIT
                        }
                        applyScaleModeLive(scaleMode)
                    }
                } catch (_: Exception) {}
            }
            // Live "自动旋转适配" toggle: re-decode/refresh current media so the
            // change takes effect on the wallpaper immediately.
            scope.launch {
                try {
                    kotlinx.coroutines.flow.combine(
                        db.settingsDao().getValueFlow(SettingsKeys.ROTATE_MISMATCH_ENABLED),
                        db.settingsDao().getValueFlow(SettingsKeys.ROTATE_MISMATCH_CW)
                    ) { enabledStr, cwStr ->
                        (enabledStr?.toBooleanStrictOrNull() ?: true) to
                            (cwStr?.toBooleanStrictOrNull() ?: true)
                    }.collect { (enabled, clockwise) ->
                        applyRotateSettingsLive(enabled, clockwise)
                    }
                } catch (_: Exception) {}
            }
            // Start the switch queue consumer up-front so triggers are always
            // processed immediately.
            ensureSwitchConsumer()
            val filter = IntentFilter(ACTION_SWITCH)
            try {
                try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
                // NOT_EXPORTED on every API level: only this app may trigger a
                // switch. An exported receiver would let any other app spam
                // ACTION_SWITCH broadcasts and force wallpaper changes.
                // ContextCompat supplies the flags overload on all API levels
                // (the plain registerReceiver() overload without a flag throws
                // on Android 14+ for non-system broadcasts).
                androidx.core.content.ContextCompat.registerReceiver(
                    applicationContext,
                    switchReceiver,
                    filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } catch (_: Exception) {}
            try {
                try { applicationContext.unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
                val screenFilter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                applicationContext.registerReceiver(screenStateReceiver, screenFilter)
            } catch (_: Exception) {}
            // The wallpaper engine is alive whenever the wallpaper is applied,
            // which makes it the most reliable watchdog for the timer service
            // (Android 15+ can kill long-running foreground services) and for
            // the floating double-tap button.
            selfHealTimerService()
            updateFloatingButton()
            scope.launch {
                try {
                    db.settingsDao().getValueFlow(SettingsKeys.FLOATING_BUTTON_ENABLED).collect {
                        updateFloatingButton()
                    }
                } catch (_: Exception) {}
            }

            // Live appearance + content updates: recolors the on-screen button as
            // the user drags the opacity slider / picks a color, and swaps its
            // label or custom picture as soon as the settings change.
            scope.launch {
                try {
                    combine(
                        db.settingsDao().getValueFlow(SettingsKeys.FLOATING_BUTTON_COLOR),
                        db.settingsDao().getValueFlow(SettingsKeys.FLOATING_BUTTON_ALPHA),
                        db.settingsDao().getValueFlow(SettingsKeys.FLOATING_BUTTON_TEXT),
                        db.settingsDao().getValueFlow(SettingsKeys.FLOATING_BUTTON_IMAGE_URI)
                    ) { colorHex, alphaStr, text, imageUri ->
                        // [0] color, [1] alpha, [2] label, [3] picture URI
                        arrayOf(colorHex, alphaStr, text, imageUri)
                    }
                        .collect { values ->
                            val colorArgb = try {
                                FloatingSwitchButton.parseColor(values[0])
                            } catch (_: Exception) {
                                FloatingSwitchButton.DEFAULT_COLOR
                            }
                            val opacity = values[1]?.toIntOrNull()
                                ?.coerceIn(SettingsKeys.FLOATING_BUTTON_ALPHA_MIN, 100)
                                ?: SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT
                            mainHandler.post {
                                floatingButton?.setAppearance(colorArgb, opacity)
                                floatingButton?.setContent(
                                    values[2] ?: SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT,
                                    values[3].orEmpty()
                                )
                            }
                        }
                } catch (_: Exception) {}
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            // A device/driver quirk (e.g. some Xiaomi/HyperOS tablets) can make
            // metrics lookup or EGL setup throw right when the wallpaper is
            // applied; an engine callback that throws kills the whole wallpaper
            // process. Every surface/visibility callback is therefore
            // crash-proofed — the wallpaper retries on the next surface /
            // visibility event instead of dying.
            try {
                surfaceReady = true
                if (holder == null) return
                // NOTE: do NOT call setTouchEventsEnabled(true) synchronously
                // here. On some devices (Android 16 tablets) it re-enters
                // onSurfaceCreated via updateSurface(), causing an infinite
                // recursion -> StackOverflowError (the exact defect reported on
                // the Xiaomi Pad). Touch is enabled once in onCreate(); re-assert
                // it asynchronously + re-entrancy-guarded via reassertTouchEvents()
                // so surface recreation on launchers that clear the touch flag
                // still keeps double-tap working, without the recursion.
                if (!rendererInitialized) {
                    val metrics = getMetrics()
                    val sw = cachedScreenW.takeIf { it > 0 } ?: metrics.widthPixels.toFloat()
                    val sh = cachedScreenH.takeIf { it > 0 } ?: metrics.heightPixels.toFloat()
                    renderer = WallpaperRenderer(applicationContext, holder).also {
                        it.initialize(sw, sh)
                        // Sound only for the real wallpaper (never for the
                        // picker's preview engine), and only when enabled.
                        it.videoSoundEnabled = videoSoundSetting && !isPreview
                        // The picker's frames are composited (and on a landscape
                        // tablet rotated) by MIUI: no unsharp mask there, see
                        // applyClarityMode(). The applied wallpaper keeps it.
                        if (isPreview) {
                            it.sharpnessScale = 0f
                            AppLog.d(TAG, "Preview engine: sharpening off (picker composition)")
                        }
                        it.onVideoStartFailed = { onVideoStartFailed() }
                        // Fade in when the video's first frame is really on
                        // screen, not when the decode thread merely started.
                        it.onFirstVideoFrame = {
                            // The new video is on screen: the bitmap that was
                            // kept for the image->video transition can go now.
                            releaseRetiredBitmap()
                            if (suppressFadeUntilFirstFrame) {
                                // Rotation redraw: the system animation is
                                // already on screen, no extra fade.
                                suppressFadeUntilFirstFrame = false
                            } else if (fadePendingForFirstFrame) {
                                fadePendingForFirstFrame = false
                                scope.launch { maybeFade(force = true) }
                            } else {
                                AppLog.d(TAG, "No fade for this video switch (rapid switching)")
                            }
                        }
                    }
                    // Room's settings flow emits asynchronously, so on a cold
                    // start the renderer above can be born before the video-sound
                    // setting was ever read - the wallpaper then stayed silent
                    // until the setting was touched again (tablet log:
                    // "Video audio: thread finished" while the video kept
                    // playing). Read it once, now that the renderer exists.
                    if (::db.isInitialized && !videoSoundSetting) {
                        scope.launch {
                            try {
                                val enabled = db.settingsDao()
                                    .getBool(SettingsKeys.VIDEO_SOUND_ENABLED, false) && !isPreview
                                if (enabled) {
                                    videoSoundSetting = true
                                    renderer?.applyVideoSound(true)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    rendererInitialized = true
                }
                // Ensure the EGL window surface exists for this SurfaceHolder. This
                // is posted to the render thread, so drawing must wait until the
                // renderer reports its EGL surface is ready.
                renderer?.surfaceCreated()
                if (isVisible) {
                    redrawWhenSurfaceReady()
                }
                // Deferred, guarded touch re-assert (see note above).
                reassertTouchEvents()
            } catch (t: Throwable) {
                AppLog.e(TAG, "onSurfaceCreated failed (wallpaper will retry on next surface event)", t)
            }
        }

        /**
         * Re-assert touch events for this engine without the synchronous
         * re-entrancy that caused a StackOverflowError: the call is posted to
         * the main handler and gated by [touchReassertQueued], so even if
         * setTouchEventsEnabled() re-enters onSurfaceCreated (via updateSurface)
         * it can only happen once per surface creation — never as unbounded
         * recursion.
         */
        private fun reassertTouchEvents() {
            if (touchReassertQueued) return
            touchReassertQueued = true
            mainHandler.post {
                try {
                    setTouchEventsEnabled(true)
                } catch (_: Exception) {
                } finally {
                    touchReassertQueued = false
                }
            }
        }

        /**
         * Draw the current media once the renderer's EGL surface is actually
         * ready. surfaceCreated() runs on the render thread asynchronously, so
         * an immediate draw could hit a not-yet-created EGL surface and leave
         * the wallpaper blank (which looks like switching stopped working).
         */
        private fun redrawWhenSurfaceReady(attempts: Int = 0) {
            if (!isVisible) return
            val r = renderer ?: return
            if (r.isSurfaceReady()) {
                drawCurrentImage()
                return
            }
            if (attempts >= 60) {
                // The EGL surface never became ready (e.g. initialization keeps
                // failing). Stop polling: endless 50ms wakeups would waste
                // battery. A later surfaceCreated()/visibility event retries.
                AppLog.w(TAG, "Surface never became ready after ${attempts * 50L}ms; stopping redraw polling")
                return
            }
            mainHandler.postDelayed({ redrawWhenSurfaceReady(attempts + 1) }, 50L)
        }

        /**
         * Called (on the decode thread) when a video failed to start - e.g. it
         * was superseded by an even newer switch, or the GL resources were torn
         * down concurrently. lastDisplayedId must be reset, otherwise the
         * engine thinks the new media is already on screen and the previous
         * video's last frame stays frozen forever. A delayed redraw retries the
         * current media if nothing else started playing.
         */
        private fun onVideoStartFailed() {
            AppLog.w(TAG, "Video failed to start; scheduling recovery switch")
            // The image kept for the image->video transition has no successor to
            // wait for any more.
            releaseRetiredBitmap()
            val failedId = lastDisplayedId
            if (failedId > 0L) failedMediaIds.add(failedId)
            // Same permanent-failure cleanup as the image and GIF paths: a video
            // whose provider entry is really gone has to be dropped from the
            // library, otherwise every new engine session re-picks it and fails
            // again. The tablet log showed exactly that: "Fleurdelys.mov"
            // (uri .../video/media/89951, which MediaStore no longer had) failed
            // with `No item at ...` while its five dead IMAGE siblings were all
            // removed by the image path. The probe only deletes rows whose file
            // is genuinely gone, so a video that merely lost a race with a newer
            // switch is kept.
            if (failedId > 0L) dropMediaIfGone(failedId)
            videoMode = false
            lastDisplayedId = 0L
            recoveryFailCount++
            if (recoveryFailCount > 5) {
                AppLog.w(TAG, "Too many consecutive video failures; pausing auto-recovery")
                return
            }
            scope.launch {
                // Non-Xiaomi devices can fail to produce video frames at all
                // (codec/SurfaceTexture quirks). Show the file's first frame
                // instead of leaving a black wallpaper, then fall back to the
                // normal recovery switch only if even that fails.
                val frame = try {
                    if (failedId > 0L) {
                        db.wallpaperImageDao().getImageById(failedId)?.let {
                            videoFirstFrame(it.uri)
                        }
                    } else null
                } catch (_: Exception) {
                    null
                }
                if (engineDestroyed) {
                    if (frame != null && !frame.isRecycled) frame.recycle()
                    return@launch
                }
                if (frame != null && renderer?.isVideoPlaying != true) {
                    mainHandler.post {
                        if (engineDestroyed) {
                            if (!frame.isRecycled) frame.recycle()
                            return@post
                        }
                        val old = currentBitmap
                        currentBitmap = frame
                        if (old != null && old !== frame && !old.isRecycled) old.recycle()
                        renderer?.showImage(frame, currentScaleMode)
                        lastDisplayedId = failedId
                        AppLog.d(TAG, "Video fallback first frame shown: ${frame.width}x${frame.height}")
                    }
                    return@launch
                }
                if (frame != null && !frame.isRecycled) frame.recycle()
                delay(1500L)
                if (isVisible && surfaceReady && !switchInProgress && renderer?.isVideoPlaying != true) {
                    // Switch to a DIFFERENT media instead of retrying the same
                    // broken file: RANDOM pick excludes the failed LAST_IMAGE_ID
                    // and the failedMediaIds blocklist covers sequential/shuffle.
                    requestSwitch("recovery")
                }
            }
        }

        /**
         * Extract the first frame of a video as a fallback static image (used
         * when a device cannot play the video through MediaCodec/SurfaceTexture).
         * Shared with the static applier (see [FirstFrame]): one implementation,
         * with the container rotation applied, so portrait recordings are
         * upright in both modes.
         */
        private fun videoFirstFrame(uriStr: String, positionUs: Long = 0L): Bitmap? =
            com.wallpaperswitcher.engine.FirstFrame.video(applicationContext, uriStr, positionUs)

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            try {
                val oldW = cachedScreenW
                val oldH = cachedScreenH
                val sizeChanged = oldW > 0f && oldH > 0f &&
                    (oldW != width.toFloat() || oldH != height.toFloat())
                cachedScreenW = width.toFloat()
                cachedScreenH = height.toFloat()
                renderer?.surfaceChanged(width, height)
                if (sizeChanged) {
                    // The screen size changed (rotation / resize): the
                    // currently displayed media was decoded (or its buffer
                    // sized) for the OLD dimensions. Re-presenting the old
                    // texture is not enough on some tablets (HyperOS is
                    // known to keep showing the previous-dimension frame
                    // distorted after rotation). Invalidate the displayed
                    // state so drawCurrentImage() re-decodes and re-renders
                    // against the NEW surface size.
                    //
                    // This is done even while the wallpaper is NOT visible:
                    // rotating inside another app still recreates this surface,
                    // and the FILL/STRETCH 90° decision was made from the OLD
                    // orientation at decode time. Skipping it left a
                    // wrongly-rotated, blurry image on screen when the user came
                    // back to the desktop, until the next switch.
                    AppLog.d(TAG, "Surface size changed ${oldW.toInt()}x${oldH.toInt()} -> ${width}x$height; forcing re-render")
                    pendingOrientationRedraw.set(true)
                    // The prefetched next image was decoded for the OLD
                    // dimensions; keep it would make the next switch render
                    // a stale-size bitmap (blurry after a rotation).
                    clearPrefetchCache()
                }
                if (isVisible && surfaceReady) {
                    drawCurrentImage()
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "onSurfaceChanged failed", t)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            lastDisplayedId = 0L
            // The surface is gone: the renderer stops presenting, so a video that
            // was playing can no longer produce frames. Cancel the health monitor
            // instead of letting it trigger a spurious recovery switch while the
            // surface is destroyed.
            //
            // `videoMode` is deliberately NOT cleared here: it is also the identity
            // the visibility path uses ("a video session is alive, keep it" - see
            // onVisibilityChanged), and clearing it made a rotation that destroyed
            // the surface tear down the decoder + audio session even though the
            // renderer was still holding the clip. The monitor is cancelled
            // explicitly above, and every other consumer of `videoMode`
            // (drawCurrentImage's videoWasPlaying, the watchdogs) additionally
            // checks `renderer?.isVideoPlaying`, so a stale `true` cannot resurrect
            // a dead session.
            videoHealthJob?.cancel()
            videoHealthJob = null
            renderer?.surfaceDestroyed()
            pauseGif()
        }

        override fun onTouchEvent(event: MotionEvent) {
            handleTouchEvent(event)
        }

        /**
         * Robust double-tap detection that fires from either the second
         * ACTION_DOWN (the classic case) or the second ACTION_UP (fallback for
         * launchers that swallow one of the DOWN events).
         *
         * Logging: a recognized double tap is always logged. Plain DOWN/UP is
         * throttled (see [TOUCH_LOG_INTERVAL_MS]) - it is still enough to prove
         * from a captured log whether the launcher delivers touches to the
         * wallpaper window at all, without writing two lines per tap (the
         * swipe detector that needed the full stream was removed).
         */
        private fun handleTouchEvent(event: MotionEvent) {
            val action = event.actionMasked
            val x = event.x
            val y = event.y
            val now = SystemClock.uptimeMillis()
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    if (doubleTapDetector.onDown(x, y, now)) {
                        AppLog.d(TAG, "Touch DOWN ($x, $y): double-tap from DOWN")
                        onDoubleTapDetected()
                    }
                    logTouchThrottled("Touch DOWN ($x, $y)")
                }
                MotionEvent.ACTION_UP -> {
                    if (doubleTapDetector.onUp(x, y, now)) {
                        // The launcher consumed one of the DOWN events but
                        // still forwarded both UPs: count it as a double tap.
                        AppLog.d(TAG, "Touch UP ($x, $y): double-tap from UP fallback")
                        onDoubleTapDetected()
                    } else {
                        logTouchThrottled("Touch UP ($x, $y)")
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    doubleTapDetector.onCancel()
                    AppLog.d(TAG, "Touch CANCEL")
                }
            }
            super.onTouchEvent(event)
        }

        /**
         * Throttled "the wallpaper window really receives touches" line. Kept so
         * a captured log can still answer "does this launcher forward touches?",
         * without one line per finger movement.
         */
        private fun logTouchThrottled(message: String) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTouchLogAt < TOUCH_LOG_INTERVAL_MS) return
            lastTouchLogAt = now
            AppLog.d(TAG, message)
        }

        private fun onDoubleTapDetected() {
            // A double tap is an explicit user action: switch even if reading
            // the setting fails.
            scope.launch {
                try {
                    val enabled = db.settingsDao().getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                    if (enabled) {
                        requestSwitch(SOURCE_DOUBLE_TAP)
                    }
                } catch (_: Exception) {
                    requestSwitch(SOURCE_DOUBLE_TAP)
                }
            }
        }

        internal fun hideFloatingButtonNow() {
            mainHandler.post {
                // An immediate hide (the app just opened) must win over a
                // pending debounced one.
                cancelPendingFloatingButtonHide()
                floatingButton?.hideOverlay()
            }
        }

        /**
         * Our activity came to the front / went away (see the companion's
         * [setAppForeground]).
         *
         * Foreground: mute and hide NOW instead of waiting for
         * onVisibilityChanged, which lags behind the window animation. The
         * low-power switch also stops the decode/render work while our UI is in
         * front of the wallpaper.
         *
         * Hidden: re-evaluate - the wallpaper may be visible again (sound comes
         * back on the home screen), or another app/keyguard may still cover it.
         */
        internal fun applyAppForeground(foreground: Boolean) {
            if (appInForeground == foreground) return
            appInForeground = foreground
            if (foreground) {
                visibleBeforeAppForeground = isVisible
                // Back already (a quick app switch): no exit grace is pending.
                appLeftAtMs = 0L
                AppLog.d(TAG, "App UI foreground: mute audio now, pause decode after the open animation")
                // The sound stops with the tap (user requirement), but the picture
                // keeps playing until our UI has really covered the wallpaper:
                // pausing the decode at once froze the video DURING the app-open
                // animation, which is visible only for our own app - other apps
                // reach us through the system's covered report ~1.1s later
                // (measured; see APP_ENTRY_PAUSE_GRACE_MS).
                renderer?.muteAudioKeepingVideo()
                // Only this input changes; the state is re-derived from all of
                // them, so a "visible" callback arriving while our UI is up can no
                // longer resume the audio behind it (see ScreenPowerPolicy). The
                // evaluation is deferred so the decode survives the animation; a
                // covered report that arrives earlier pauses us right away anyway.
                val pauseForUi = Runnable { refreshPowerSave("app-foreground") }
                appEntryPauseRunnable = pauseForUi
                mainHandler.postDelayed(pauseForUi, APP_ENTRY_PAUSE_GRACE_MS)
                hideFloatingButtonNow()
            } else {
                AppLog.d(TAG, "App UI hidden: re-evaluating wallpaper state")
                // Resuming only on the system's visibility callback costs 1.5-2.5s
                // of silence on this ROM (measured): the callback that says "the
                // wallpaper is visible again" arrives long after the launcher is
                // up. The wallpaper was visible right before our UI opened and the
                // screen is on with no keyguard, so the user is back on the home
                // screen - resume now and let the callback correct us if they
                // actually switched into another app (worst case a ~0.3s blip
                // instead of seconds of silence).
                val keyguardShowing = try {
                    (getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                        ?.isKeyguardLocked == true
                } catch (_: Exception) {
                    false
                }
                val backOnDesktop = visibleBeforeAppForeground &&
                    !keyguardShowing && powerManager.isInteractive
                if (isVisible || backOnDesktop) {
                    // Optimistic visibility for the power-save inputs only: the
                    // system's own callback may still be 1.5-2.5s away, and it
                    // corrects this input as soon as it arrives.
                    powerSaveVisibleInput = true
                }
                // Arms the exit grace: the resume itself is delayed inside
                // refreshPowerSave (whichever signal asks for it first).
                appLeftAtMs = SystemClock.elapsedRealtime()
                // The app is gone again before the entry grace expired: that
                // pending pause evaluation would only re-derive the same state.
                appEntryPauseRunnable?.let { mainHandler.removeCallbacks(it) }
                appEntryPauseRunnable = null
                refreshPowerSave("app-left")
                updateFloatingButton()
            }
        }

        internal fun requestSwitchFromOutside(source: String): Boolean {
            if (activeEngine !== this) return false
            requestSwitch(source)
            return true
        }

        /** Apply one specific media from outside (see the companion's push). */
        internal fun requestTargetFromOutside(source: String, targetId: Long): Boolean {
            if (activeEngine !== this) return false
            requestSwitch(source, targetId)
            return true
        }

        internal fun refreshFloatingButtonNow() {
            updateFloatingButton()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            try {
                isVisible = visible
                // The callback is authoritative for the power-save input too (it
                // also clears the optimistic value set when our own UI closed).
                powerSaveVisibleInput = visible
                if (visible) {
                    cancelMediaReleaseWhileLocked()
                    selfHealTimerService()
                    updateFloatingButton()
                    refreshPowerSave("visibility")
                    // Retry the EGL setup if it failed earlier on this device
                    // (some drivers fail the first attempt, leaving the
                    // wallpaper black). No-op when the surface is healthy.
                    try { renderer?.retrySurfaceIfNeeded() } catch (_: Exception) {}
                    scope.launch {
                        if (!surfaceReady || renderer == null) return@launch
                        // A tap-to-set whose system screen may still be cancelled:
                        // the cursor was moved so the PICKER's preview could render
                        // the tapped media, and the real wallpaper must not follow
                        // it unless the user confirmed (which clears this pending
                        // state via the real engine's onCreate; the cancel path
                        // restores the cursor instead).
                        if (hasPendingPreviewPick()) return@launch
                        // A pending pick that outlived its trust window (the picker
                        // died without its onDestroy, or never started a preview
                        // engine at all): treat it as CANCELLED here instead of
                        // merely letting the redraw through. Otherwise the cursor
                        // move of a pick nobody confirmed would be applied a couple
                        // of minutes later - the original "没确认也换壁纸" report,
                        // just delayed.
                        if (previewPickHomeId >= 0L && !realApplySincePick) {
                            AppLog.d(
                                TAG,
                                "Pending pick expired without a decision: treating it as cancelled"
                            )
                            restoreHomeCursorAfterCancelledPick(applicationContext)
                            return@launch
                        }
                        val dao = db.settingsDao()
                        val savedId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                        // A rotation that happened while the wallpaper was
                        // covered left the displayed media decoded for the old
                        // surface size / screen orientation (see
                        // onSurfaceChanged): redraw it even though the id did
                        // not change.
                        if (savedId != lastDisplayedId || lastDisplayedId == 0L ||
                            pendingOrientationRedraw.get()
                        ) {
                            // A video that is still alive (parked while the
                            // wallpaper was covered) must keep its session: the
                            // rotation only changed the surface size, and
                            // resetting videoMode here would make
                            // drawCurrentImage() rebuild the decoder and the audio
                            // thread even though the clip can keep playing - see
                            // the rotation branch there.
                            if (videoMode && renderer?.isVideoPlaying == true) {
                                AppLog.d(TAG, "Back on desktop after rotation: video kept alive")
                            } else {
                                videoMode = false
                                clearCurrentBitmap()
                            }
                            drawCurrentImage()
                        }
                    }
                } else {
                    // The wallpaper is not visible: either the screen is off or
                    // another app covers it. Throttle decode/render so the engine
                    // stops burning power behind other apps. Playback never stops
                    // or restarts - it resumes full speed when visible again.
                    AppLog.d(TAG, "Wallpaper covered: pausing decode/audio")
                    updateFloatingButton()
                    refreshPowerSave("visibility")
                    // Parked decoders/drawables still hold their resources: drop
                    // them once the wallpaper has been LOCKED that long (the task
                    // re-checks `isInteractive()`, so merely opening another app
                    // only parks them - see scheduleMediaReleaseWhileLocked).
                    scheduleMediaReleaseWhileLocked()
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "onVisibilityChanged failed", t)
            }
        }

        override fun onDestroy() {
            engineDestroyed = true
            // Only the active engine owns the static engineRunning flag. When
            // the wallpaper is re-applied, the OLD engine may receive its
            // onDestroy AFTER the new engine already started; clearing the flag
            // unconditionally would make the timer fall back to the static path
            // and drop unlock/double-tap while the new engine is alive (exactly
            // what the captured logs showed).
            if (activeEngine === this) {
                activeEngine = null
                engineRunning = false
            }
            // The preview engine only releases the "system dialog is open"
            // hold when the picker really went away (it is the instance that
            // claimed it in onCreate).
            if (isPreview) {
                // Read the wallpaper ids BEFORE releasing the timer hold and
                // poking the loops below: a lock tick that fires right after the
                // poke could write a static lock wallpaper, and that write must
                // never be mistaken for the user's confirm (see
                // wallpaperIdsChangedSincePick).
                val confirmedByWallpaperId = wallpaperIdsChangedSincePick()
                val pickedHomeId = previewPickHomeId
                previewEngineActive = false
                // The system dialog is closing: wake the timer loops now instead
                // of letting them wait out PREVIEW_DIALOG_HOLD_WAIT_MS.
                WallpaperSwitchService.poke(applicationContext)
                // If the user confirmed, the system creates the REAL engine (which
                // records the confirmation in its onCreate). If it did not, the
                // picker was cancelled and the media recorded by
                // setAsLiveWallpaper must not be applied to the lock screen by a
                // later, unrelated engine recreation. `engineRunning` cannot tell
                // the two apart when our live wallpaper was ALREADY the active one:
                // it stays true in both cases, which is why the decision looks at
                // the pending pick and the system wallpaper ids instead.
                val appContext = applicationContext
                // Identity of THIS pick session. The decision must not touch state
                // that a newer pick (or an already-made decision) owns, so it is
                // gated on the pick's own timestamp instead of on the engine
                // generation: a mid-picker engine rebuild bumped the generation,
                // the old gate then skipped BOTH branches, and the unconfirmed
                // cursor move stayed in place (see onCreate above).
                val pickAtStart = previewPickAtMs
                val confirmedNow = confirmedByWallpaperId || realApplySincePick
                ioScope.launch {
                    try {
                        kotlinx.coroutines.delay(
                            if (confirmedNow) PICK_CONFIRM_GRACE_MS
                            else PICK_CANCEL_GRACE_MS
                        )
                        if (previewPickAtMs == pickAtStart && hasPendingPreviewPick()) {
                            if (confirmedByWallpaperId || realApplySincePick ||
                                wallpaperIdsChangedSincePick()
                            ) {
                                // MIUI/HyperOS: re-applying the live wallpaper that
                                // is already ours does not re-create this service,
                                // so "no new engine" is a false negative. Keep the
                                // cursor and make the (still running) engine apply
                                // the media the user confirmed.
                                AppLog.d(
                                    TAG,
                                    "Pick confirmed (idChange=$confirmedByWallpaperId " +
                                        "newEngine=$realApplySincePick): " +
                                        "pickedHomeId=$pickedHomeId prev=${previewPickPreviousHomeId}"
                                )
                                realApplySincePick = true
                                // Clears the pending pick, keeps the HOME cursor.
                                restoreHomeCursorAfterCancelledPick(appContext)
                                // The picked media: normally the pending pick's
                                // id; fall back to the HOME cursor, which the
                                // pick moved and the confirm keeps (the home
                                // timer is held while the picker is open, so it
                                // cannot have advanced in the meantime).
                                var targetHomeId = pickedHomeId
                                if (targetHomeId <= 0L) {
                                    // The pending pick is normally still there; it is
                                    // gone when a REAL engine was created between the
                                    // tap and this check (its onCreate clears it).
                                    // The HOME cursor the pick moved is then the
                                    // remaining record of what the user chose - and
                                    // it is still validated below (enabled,
                                    // home-capable group) so a stale cursor can never
                                    // re-apply a disabled group's media.
                                    targetHomeId = homeCursorForConfirmedPick(appContext)
                                }
                                if (targetHomeId > 0L) {
                                    pushConfirmedPickToEngine(targetHomeId)
                                }
                                try {
                                    // Let the desktop switch + the system dialog's
                                    // closing animation finish first: the slot
                                    // correction below decodes a full-screen image
                                    // and encodes a JPEG, and running that in the
                                    // same instant is exactly the stutter the user
                                    // reported right after tapping 设置壁纸.
                                    kotlinx.coroutines.delay(PICK_CONFIRM_ENFORCE_DELAY_MS)
                                    val enforced = WallpaperSwitchService
                                        .enforceSlotsAfterLiveApply(appContext)
                                    AppLog.d(
                                        TAG,
                                        "Slot enforcement after confirmed pick=$enforced"
                                    )
                                } catch (ce: kotlinx.coroutines.CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    AppLog.e(TAG, "Slot enforcement after confirmed pick failed", e)
                                }
                            } else {
                                AppLog.d(TAG, "Live wallpaper picker closed without a new engine: pick cancelled")
                                // Only clear the pick THIS session recorded: if the
                                // user already picked another media in the meantime,
                                // that newer pick must stay.
                                WallpaperSwitchService.clearManualPick(
                                    appContext,
                                    previewStartManualPickAt
                                )
                                // 取消时桌面壁纸必须保持原样：把点击时挪动的 HOME
                                // 游标放回去，否则真实引擎恢复可见时会按该游标把
                                // 预览过的那张图设成桌面壁纸（用户反馈"还是会出现"）。
                                restoreHomeCursorAfterCancelledPick(appContext)
                            }
                        } else {
                            AppLog.d(
                                TAG,
                                "Pick decision skipped: a newer pick or another decision " +
                                    "already owns the pending state"
                            )
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            lastDisplayedId = 0L
            switchInProgress = false
            switchStartedAt = 0L
            consumerStarted.set(false)
            mediaReleaseRunnable = null
            floatingButton?.hideOverlay()
            clearPrefetchCache()
            // Remove any pending delayed recovery / redraw callbacks so they
            // can never fire after the engine is destroyed.
            mainHandler.removeCallbacksAndMessages(null)
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            try { applicationContext.unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
            flushShuffleState()
            try { renderer?.release() } catch (_: Exception) {}
            renderer = null
            rendererInitialized = false
            // GIF shutdown is serialized on the GIF thread: stop the ticker,
            // close the drawable and recycle its ping-pong buffers there (a
            // frame draw in flight must never race a close/recycle from main),
            // then quit the thread. quitSafely() drains the posted task first.
            gifFrameRunnable?.let { gifHandler?.removeCallbacks(it) }
            gifFrameRunnable = null
            pendingGifUri = null
            val lastGif = gifDrawable
            gifDrawable = null
            gifHandler?.post {
                lastGif?.let { g ->
                    // AnimatedImageDrawable only exists on API 28+; on older
                    // versions gifDrawable is always null here, so this guard
                    // is both required for lint and correct at runtime.
                    if (Build.VERSION.SDK_INT >= 28) {
                        try { g.stop() } catch (_: Exception) {}
                        try { (g as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                    }
                }
                gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
                gifBitmapBufferAlt?.recycle(); gifBitmapBufferAlt = null
            }
            gifThread?.quitSafely()
            gifThread = null
            gifHandler = null
            currentBitmap?.recycle(); currentBitmap = null
            retiredBitmap?.recycle(); retiredBitmap = null
            defaultBitmap?.recycle(); defaultBitmap = null
            videoHealthJob?.cancel()
            videoHealthJob = null
            scope.cancel()
            super.onDestroy()
        }

        /**
         * Android 15+ can stop a long-running foreground service while the app
         * is in the background. Whenever the wallpaper becomes (or is kept)
         * visible, restart the timer if it is enabled but dead.
         */
        private fun selfHealTimerService() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTimerSelfHealAt < 30_000L) return
            lastTimerSelfHealAt = now
            scope.launch {
                try {
                    // A *running* service does not mean its timer loops are alive:
                    // both loops `return` out of their while-loop while the screen
                    // is off (deliberately - zero wakeups) and only ACTION_SCREEN_ON
                    // brings them back. On devices that skip that broadcast the timer
                    // stayed dead forever while `running` kept this watchdog from
                    // noticing. poke() restarts the loops in place (and still stops
                    // itself again if the timers are switched off).
                    if (WallpaperSwitchService.running) {
                        WallpaperSwitchService.poke(applicationContext)
                        return@launch
                    }
                    // Either timer needs the service: the lock timer is
                    // independent of the home one and may be the only reason it
                    // has to run (same rule as BootReceiver / ensureRunning).
                    val dao = db.settingsDao()
                    val needed = dao.getBool(SettingsKeys.SERVICE_ENABLED, false) ||
                        dao.getBool(SettingsKeys.LOCK_TIMER_ENABLED, true)
                    if (needed) {
                        AppLog.d(TAG, "Timer enabled but service not running, restarting (engine watchdog)")
                        WallpaperSwitchService.start(applicationContext)
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "Timer self-heal blocked (background FGS start?)", e)
                }
            }
        }

        /**
         * Show/hide the floating double-tap button according to the setting,
         * applying the user's color/opacity appearance. WindowManager calls
         * must run on the main thread, so the DB read happens off-main and the
         * window ops are posted to the main handler.
         */
        private fun updateFloatingButton() {
            // Only the REAL engine owns the floating button. The picker's preview
            // engine used to create a second overlay window at the same spot
            // (verified with dumpsys: two APPLICATION_OVERLAY windows of ours,
            // Window #12 and #13, both 144x144), which left a stale duplicate
            // behind whenever the preview engine outlived its dismiss - and the
            // live content updates only ever reached the instance held by the real
            // engine's field.
            if (isPreview) return
            if (!floatingButtonUpdateLock.compareAndSet(false, true)) {
                floatingButtonUpdatePending.set(true)
                return
            }
            scope.launch {
                try {
                    val enabled = try {
                        db.settingsDao().getBool(SettingsKeys.FLOATING_BUTTON_ENABLED, false)
                    } catch (_: Exception) {
                        false
                    }
                    val canOverlayEarly = try {
                        Settings.canDrawOverlays(applicationContext)
                    } catch (_: Exception) {
                        false
                    }
                    if (!enabled || !canOverlayEarly) {
                        // The feature is off: drop the window instead of leaving a
                        // hidden overlay attached.
                        mainHandler.post {
                            floatingButton?.removeOverlay()
                            floatingButton = null
                            finishFloatingButtonUpdate()
                        }
                        return@launch
                    }

                    // Same value the enable check above used (one permission read).
                    val canOverlay = canOverlayEarly
                    val colorArgb = try {
                        FloatingSwitchButton.parseColor(
                            db.settingsDao().getString(
                                SettingsKeys.FLOATING_BUTTON_COLOR,
                                SettingsKeys.FLOATING_BUTTON_COLOR_DEFAULT
                            )
                        )
                    } catch (_: Exception) {
                        FloatingSwitchButton.DEFAULT_COLOR
                    }
                    val opacity = try {
                        db.settingsDao()
                            .getLong(SettingsKeys.FLOATING_BUTTON_ALPHA, SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT.toLong())
                            .toInt()
                            .coerceIn(SettingsKeys.FLOATING_BUTTON_ALPHA_MIN, 100)
                    } catch (_: Exception) {
                        SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT
                    }
                    val buttonText = try {
                        db.settingsDao().getString(
                            SettingsKeys.FLOATING_BUTTON_TEXT,
                            SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT
                        )
                    } catch (_: Exception) {
                        SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT
                    }
                    val buttonImage = try {
                        db.settingsDao().getString(SettingsKeys.FLOATING_BUTTON_IMAGE_URI, "")
                    } catch (_: Exception) {
                        ""
                    }
                    mainHandler.post {
                        try {
                            if (engineDestroyed) return@post
                            // The floating button only lives on the desktop:
                            // hide it whenever the wallpaper is covered (any
                            // app open, lock screen, screen off).
                            if (enabled && canOverlay && isVisible && !appInForeground) {
                                // Visible again (or still): a hide that was only
                                // scheduled must be cancelled, so a transient
                                // cover costs no window churn at all.
                                cancelPendingFloatingButtonHide()
                                // ONE overlay window per process, attached once
                                // and then only shown/hidden (see
                                // FloatingSwitchButton): re-creating it per engine
                                // left duplicates behind (removeView reported no
                                // error while dumpsys showed three live windows)
                                // and every add/remove made the ROM flip the
                                // wallpaper's visibility.
                                val button = floatingButton
                                    ?: FloatingSwitchButton.obtain(applicationContext)
                                        .also { floatingButton = it }
                                button.setAppearance(colorArgb, opacity)
                                button.setContent(buttonText, buttonImage)
                                button.showOnDesktop()
                            } else if (appInForeground) {
                                // Our own app is in front: hide at once instead of
                                // after the settle debounce, otherwise the button
                                // lingers over the app's window transition.
                                cancelPendingFloatingButtonHide()
                                floatingButton?.hideOverlay()
                            } else {
                                scheduleFloatingButtonHide()
                            }
                        } finally {
                            finishFloatingButtonUpdate()
                        }
                    }
                } catch (_: Exception) {
                    finishFloatingButtonUpdate()
                }
            }
        }

        /**
         * Release the floating-button update lock and, if another update was
         * requested while this one was running (hide/show toggling during an
         * app transition), re-run it so the LAST state always wins.
         */
        private fun finishFloatingButtonUpdate() {
            floatingButtonUpdateLock.set(false)
            if (floatingButtonUpdatePending.compareAndSet(true, false)) {
                updateFloatingButton()
            }
        }

        /**
         * Remove the button after [FLOATING_BUTTON_HIDE_SETTLE_MS], unless the
         * wallpaper became visible again in the meantime (see the constant).
         */
        private fun scheduleFloatingButtonHide() {
            if (floatingButton == null || floatingButtonHideRunnable != null) return
            val runnable = Runnable {
                floatingButtonHideRunnable = null
                // Re-check on the main thread at fire time: "visible again" is
                // the common case this debounce exists for.
                if (engineDestroyed || isVisible) return@Runnable
                floatingButton?.hideOverlay()
            }
            floatingButtonHideRunnable = runnable
            mainHandler.postDelayed(runnable, FLOATING_BUTTON_HIDE_SETTLE_MS)
        }

        private fun cancelPendingFloatingButtonHide() {
            floatingButtonHideRunnable?.let { mainHandler.removeCallbacks(it) }
            floatingButtonHideRunnable = null
        }


        private fun flushShuffleState() {
            // onCreate() bails out early when the database cannot be opened, so
            // `db` can still be uninitialized when onDestroy() runs - reading it
            // there would throw out of a lifecycle callback.
            if (!::db.isInitialized) return
            val ids = pendingShuffleIds.toList()
            if (ids.isEmpty()) return
            pendingShuffleIds.removeAll(ids)
            // onDestroy runs on the main thread, so the write is dispatched to
            // IO and NOT awaited: blocking the main thread here (the old
            // runBlocking with an 800ms cap) risked a stall right in the middle
            // of the wallpaper transition. Shuffle state is only a hint - if
            // the process dies before the write lands, the deck simply resets.
            //
            // Since schema v6 the deck is one row per shown media (see
            // ShuffleShown): the write is a tiny INSERT, and the insert is
            // idempotent (IGNORE), so the old "serialize the writes and stamp them
            // so an older snapshot cannot land last" machinery is gone with the
            // snapshot itself.
            //
            // Only statics and locals are referenced inside the coroutine:
            // [ioScope] is process-lifetime, so touching an engine field here
            // would keep this engine and its service context alive until the
            // write finished.
            val shuffleDao = db.shuffleDao()
            val allCount = shuffleAllCount.toLong()
            val settingsDao = db.settingsDao()
            ioScope.launch {
                var attempt = 0
                while (true) {
                    try {
                        shuffleDao.insertShown(
                            ids.map { com.wallpaperswitcher.data.ShuffleShown(HOME_SLOT, it) }
                        )
                        settingsDao.setLong(SettingsKeys.SHUFFLE_ALL_COUNT, allCount)
                        return@launch
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        // Bounded retry: the old code set shuffleDirty = true so
                        // the NEXT flush retried, which could never work once the
                        // engine owning the flag was gone (and pinned it here).
                        attempt++
                        if (attempt >= SHUFFLE_WRITE_ATTEMPTS) {
                            AppLog.d(TAG, "Shuffle state write failed: ${e.message}")
                            return@launch
                        }
                        delay(300L * attempt)
                    }
                }
            }
        }

        private fun stopVideo() {
            videoMode = false
            videoHealthJob?.cancel()
            videoHealthJob = null
            renderer?.stopVideo()
            // Short grace only: startVideo() joins the old decode thread again
            // internally, so waiting 500ms here would double the worst-case
            // stall on rapid video switches. The generation guard keeps a
            // late-exiting old thread from touching the new video's resources.
            renderer?.waitForDecodeThread(120)
        }

        private fun pauseGif() {
            gifHealthJob?.cancel()
            gifHealthJob = null
            gifFrameRunnable?.let { gifHandler?.removeCallbacks(it) }
            gifFrameRunnable = null
            pendingGifUri = null
            // Also stop the underlying animation: without this the drawable
            // keeps decoding frames in the background after a switch away from
            // the GIF, wasting CPU for nothing. Done on the GIF thread (never
            // main) so a frame draw in flight can't race the stop/close;
            // playGif28() starts a fresh drawable, so returning to GIFs
            // restarts cleanly.
            gifHandler?.post {
                stopAndCloseGifDrawable()
            }
        }

        /**
         * Runs on the GIF thread: stop + close the current animated drawable.
         * Idempotent; safe to call twice (pauseGif + startGifTicker).
         */
        private fun stopAndCloseGifDrawable() {
            gifDrawable?.let { g ->
                // Drop the frame clock first: the ticker must not keep a
                // timestamp from a drawable that is about to be closed.
                try { g.callback = null } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= 28) {
                    try { g.stop() } catch (_: Exception) {}
                    try { (g as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                }
            }
            gifDrawable = null
        }

        /**
         * Re-evaluate the renderer's power-save state after one of the three
         * inputs changed: the wallpaper's visibility ([powerSaveVisibleInput],
         * set by `onVisibilityChanged`), our own UI's foreground state
         * ([appInForeground], set by `applyAppForeground`) and the screen state
         * (read live below).
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
        private fun refreshPowerSave(hint: String) {
            if (engineDestroyed) return
            val reasons = ScreenPowerPolicy.pauseReasons(
                wallpaperVisible = powerSaveVisibleInput,
                appInForeground = appInForeground,
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
                        TAG,
                        "Visibility blip coalesced: no pause/resume for the blip " +
                            "| preview=$isPreview"
                    )
                }
                powerSavePending?.let { mainHandler.removeCallbacks(it) }
                powerSavePending = null
            }
            pauseCoalescePending = false
            if (pause) {
                if (ScreenPowerPolicy.isVisibilityBlipOnly(reasons)) {
                    // See ScreenPowerPolicy.isVisibilityBlipOnly: a brief "covered"
                    // report is delayed so a blip never reaches the renderer.
                    val runnable = Runnable {
                        pauseCoalescePending = false
                        applyPowerSave(hint)
                    }
                    pauseCoalescePending = true
                    powerSavePending = runnable
                    mainHandler.postDelayed(runnable, VISIBILITY_PAUSE_COALESCE_MS)
                } else {
                    applyPowerSave(hint)
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
                val deliberateResume = !appInForeground && powerSaveVisibleInput &&
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
                    applyPowerSave(hint)
                } else {
                    val runnable = Runnable { applyPowerSave(hint) }
                    powerSavePending = runnable
                    mainHandler.postDelayed(runnable, delayMs)
                }
            }
        }

        /** Derive and apply the state from the inputs plus the real screen state. */
        private fun applyPowerSave(hint: String) {
            powerSavePending = null
            if (engineDestroyed) return
            val reasons = ScreenPowerPolicy.pauseReasons(
                wallpaperVisible = powerSaveVisibleInput,
                appInForeground = appInForeground,
                screenInteractive = isScreenInteractive()
            )
            val pause = reasons.isNotEmpty()
            if (renderer?.powerSaveMode == pause) {
                // No state change - but a quick enter/leave of our own UI never
                // paused at all, so an "own UI opened" audio mute still has to be
                // undone here.
                if (!pause) renderer?.unmuteAudioReanchored()
                return
            }
            renderer?.powerSaveMode = pause
            if (!pause) {
                // Audible again: undo an "own UI opened" audio mute, joined to the
                // frame that is on screen right now - the decode kept running while
                // the sound was muted, so continuing where the audio stopped would
                // leave it behind the picture. AFTER the flag flip: the unmute
                // returns early while powerSaveMode is still true.
                renderer?.unmuteAudioReanchored()
            }
            // ON: every input that asked for it, so the log shows WHY (including
            // the case where two inputs disagree). OFF: the signal that triggered
            // this re-evaluation. The instance kind is part of the line because the
            // picker's PREVIEW engine logs into the same file with the same TAG:
            // without it, two instances read like ONE engine flip-flopping (a real
            // analysis mistake - two "ON" lines 9ms apart with different reasons
            // are two engines, not churn).
            val reason = (if (pause) reasons.joinToString(", ") else hint) +
                " | preview=$isPreview"
            AppLog.d(TAG, "Power save ${if (pause) "ON" else "OFF"} ($reason)")
            if (!pause) {
                // Back on the desktop: wake the GIF ticker now instead of letting
                // it wait out its (rare) paused poll.
                nudgeGifTicker()
                // Resume a parked video with a FRESH watchdog window. The frames
                // stopped on purpose while hidden, and without this the health
                // monitor fired "Video stalled: no frame for 24s; recovering" the
                // instant the wallpaper came back (Redmi log 10:13:41 → 10:14:07)
                // - a FALSE stall that switched the user away from their video
                // right after unlocking, so a video wallpaper "disappeared" and
                // restarted from the beginning instead of continuing.
                //
                // The screen-on receiver also resets the clock, but it is skipped
                // whenever this visibility debounce has already resumed the
                // engine (the derived state does not change, so applyPowerSave returns early),
                // which is exactly what happened in that log.
                renderer?.resetVideoFrameClock()
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

        /**
         * Resume the GIF ticker immediately. Only the paused branch posts with
         * [GIF_PAUSED_POLL_MS], so without this nudge the animation would restart
         * up to 5s after the desktop came back. Pending callbacks are removed
         * first: posting the same runnable twice would start two tick chains and
         * play the animation at double speed.
         */
        private fun nudgeGifTicker() {
            val runnable = gifFrameRunnable ?: return
            val handler = gifHandler ?: return
            handler.removeCallbacks(runnable)
            handler.post(runnable)
        }

        /**
         * Delay until the GIF's own next frame, from the delays parsed out of
         * the file (see [com.wallpaperswitcher.engine.GifTiming]).
         *
         * The small margin over the declared delay keeps every tick on a frame
         * boundary: the drawable advances one frame per draw once the elapsed
         * time has reached the frame's duration, and a tick that landed a
         * fraction early would present the same frame twice and halve the
         * animation's speed. Falls back to [GIF_FRAME_INTERVAL_MS] whenever the
         * delays are unknown (not a GIF, unreadable provider), which is exactly
         * the cadence this ticker had before.
         */
        private fun gifFrameStepMs(frameDelaysMs: IntArray?, frameIndex: Int): Long {
            if (frameDelaysMs == null || frameDelaysMs.isEmpty()) {
                return GIF_FRAME_INTERVAL_MS
            }
            val declared = frameDelaysMs[frameIndex % frameDelaysMs.size]
            return (declared + GIF_FRAME_DELAY_MARGIN_MS)
                .coerceIn(GIF_FRAME_INTERVAL_MS, GIF_MAX_FRAME_INTERVAL_MS)
        }

        /**
         * Release the video decoder / GIF drawable once the wallpaper has been
         * LOCKED for [MEDIA_RELEASE_AFTER_SCREEN_OFF_MS].
         *
         * Both already stop consuming CPU when hidden (the loops wait on
         * [WallpaperRenderer.powerSaveMode] / the drawable is stopped), but they
         * keep their resources allocated: a hardware decoder + external texture
         * for video, the decoded frame buffers for a GIF (tens of MB). While the
         * screen is off nobody is watching, so dropping them is pure savings.
         *
         * A wallpaper that is merely covered by ANOTHER APP keeps its parked
         * session (the runnable re-checks `isInteractive()`): coming back to the
         * desktop then resumes from the same frame instead of restarting.
         *
         * When it really releases, the media is started again once the desktop
         * becomes visible ([lastDisplayedId] is reset so the redraw path
         * re-applies it).
         */
        private fun scheduleMediaReleaseWhileLocked() {
            if (mediaReleaseRunnable != null) return
            val runnable = Runnable {
                mediaReleaseRunnable = null
                if (engineDestroyed || isVisible) return@Runnable
                // Only the lock screen releases: if the screen is still
                // interactive, the wallpaper is only covered by another app and
                // must keep its parked session (position preserved).
                if (powerManager.isInteractive()) return@Runnable
                if (videoMode) {
                    AppLog.d(
                        TAG,
                        "Video session released after ${MEDIA_RELEASE_AFTER_SCREEN_OFF_MS / 1000}s " +
                            "locked (power save)"
                    )
                    stopVideo()
                    // Coming back must NOT fade in (that reads as a black flash
                    // while the video rebuilds).
                    resumeFromPowerSave = true
                    lastDisplayedId = 0L
                    // 接着上次位置继续播放: remember where the clip was when the
                    // session was released, so the rebuild resumes there instead
                    // of jumping back to the beginning (user request). The value
                    // is picked up by the next startVideo() for the same media -
                    // see resumePositionFor().
                    resumeVideoPositionUs = renderer?.lastVideoPositionUs ?: 0L
                    // Keep a still frame on screen for the rebuild: the restarted
                    // video needs ~100-200ms for its first frame, and on a surface
                    // that was recreated while locked (rotation) that gap would be
                    // black. The frame comes from the RESUME position, not the
                    // file's start, so the rebuild is seamless.
                    scope.launch {
                        try {
                            val dao = db.settingsDao()
                            val id = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                            val media = if (id > 0L) {
                                db.wallpaperImageDao().getImageById(id)
                            } else {
                                null
                            }
                            val uri = media?.takeIf { it.mediaType == MediaTypes.VIDEO }?.uri
                                ?: return@launch
                            // The resume only applies to THIS media; any other
                            // video that gets applied next starts at 0 (see
                            // resumePositionFor).
                            resumeVideoMediaId = id
                            val frame = videoFirstFrame(uri, resumeVideoPositionUs)
                                ?: return@launch
                            mainHandler.post {
                                // Already back (or the engine is gone): the normal
                                // redraw path owns the screen now.
                                if (engineDestroyed || isVisible) {
                                    if (!frame.isRecycled) frame.recycle()
                                    return@post
                                }
                                clearCurrentBitmap()
                                currentBitmap = frame
                                renderer?.showImage(frame, currentScaleMode)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    return@Runnable
                }
                if (gifDrawable != null || gifFrameRunnable != null) {
                    AppLog.d(
                        TAG,
                        "GIF released after ${MEDIA_RELEASE_AFTER_SCREEN_OFF_MS / 1000}s locked (power save)"
                    )
                    pauseGif()
                    // Recycled on the GIF thread (queued behind pauseGif's own
                    // stop/close task): a frame draw may still be in flight, and
                    // releasing the buffers from the main thread would race it.
                    gifHandler?.post {
                        gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
                        gifBitmapBufferAlt?.recycle(); gifBitmapBufferAlt = null
                    }
                    resumeFromPowerSave = true
                    lastDisplayedId = 0L
                }
            }
            mediaReleaseRunnable = runnable
            mainHandler.postDelayed(runnable, MEDIA_RELEASE_AFTER_SCREEN_OFF_MS)
        }

        private fun cancelMediaReleaseWhileLocked() {
            mediaReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
            mediaReleaseRunnable = null
        }

        /**
         * Live scale-mode change from Settings: update the field used by every
         * future render/redraw and ask the renderer to re-fit the currently
         * displayed media without restarting it (images re-present from the
         * existing texture, video quads recompute for the next frame, GIF
         * frames read [currentScaleMode] every tick).
         */
        private fun applyScaleModeLive(mode: ScaleMode) {
            val previous = currentScaleMode
            if (previous == mode) return
            currentScaleMode = mode
            // FILL decodes landscape media rotated to the screen orientation;
            // a stale prefetch would carry the previous mode's orientation.
            clearPrefetchCache()
            renderer?.applyScaleMode(mode)
            // Static images need a fresh decode (not just a re-quad) when the
            // mode toggles across FILL so the FILL 90° orientation applies;
            // video/GIF pick the new quad up on their next frames.
            val shownBitmap = currentBitmap
            val staticImageActive = !videoMode && gifDrawable == null &&
                shownBitmap != null && !shownBitmap.isRecycled
            if (staticImageActive) {
                lastDisplayedId = 0L
                drawCurrentImage()
            }
            AppLog.d(TAG, "Scale mode changed live to $mode (previous $previous)")
        }

        private fun applyRotateSettingsLive(enabled: Boolean, clockwise: Boolean) {
            if (autoRotateMismatch == enabled && autoRotateClockwise == clockwise) return
            autoRotateMismatch = enabled
            autoRotateClockwise = clockwise
            // A prefetch decoded under the previous setting would carry the
            // wrong orientation; drop it so the next switch decodes fresh.
            clearPrefetchCache()
            renderer?.autoRotateMismatch = enabled
            renderer?.autoRotateClockwise = clockwise
            renderer?.refreshAfterAutoRotateChange()
            AppLog.d(TAG, "Auto rotate mismatch = $enabled, clockwise = $clockwise")
            // Static images are decoded with the rotation applied at load time,
            // so they need a fresh decode; GIFs must restart to rebuild rotated
            // frames; videos only recompute their quad (done above).
            val shownBitmap = currentBitmap
            val staticImageActive = !videoMode && gifDrawable == null &&
                shownBitmap != null && !shownBitmap.isRecycled
            val gifActive = !videoMode && gifDrawable != null && gifFrameRunnable != null
            if (staticImageActive || gifActive) {
                lastDisplayedId = 0L
                drawCurrentImage()
            }
        }

        // ======== Switch logic ========

        /**
         * Submit a switch request. Requests are processed one at a time by a
         * single consumer, so a busy engine queues new triggers instead of
         * dropping them, and a single stuck/failed switch can never disable
         * timer / double-tap / unlock switching permanently.
         */
        private fun requestSwitch(source: String, targetId: Long? = null) {
            ensureSwitchConsumer()
            // If the current switch looks stuck (>30s), do NOT spawn a second
            // consumer: two consumers would pull from the channel concurrently
            // and run executeSwitch twice in parallel, which is exactly the
            // concurrent startVideo/stopVideoAndRender race that crashes the
            // GL renderer. Every blocking step inside a switch is time-bounded
            // (bitmap 15s, video open 15s), so the original consumer always
            // finishes eventually and processes this queued request.
            if (switchInProgress && switchStartedAt != 0L &&
                SystemClock.elapsedRealtime() - switchStartedAt > 30_000L
            ) {
                AppLog.w(TAG, "Switch appears stuck >30s; queued requests will run when it finishes")
            }
            AppLog.d(TAG, "Switch requested: $source target=$targetId")
            if (targetId != null) {
                // Target switches (manual selection) always queue.
                scope.launch {
                    switchChannel.send(SwitchRequest(source, targetId))
                }
                return
            }
            // Coalesce non-target switches: while one is already queued (or
            // being processed), fold new triggers into it. Rapid double-taps
            // then cause ONE switch instead of N queued requests that each run
            // their own screen-size decode — which made rapid tapping stutter
            // (every queued switch blocked on a fresh decode and the queue
            // drained slowly). The consumer resets the flag when it pulls a
            // request, so a trigger during a switch still queues the next one.
            if (!pendingAutoSwitch.compareAndSet(false, true)) {
                AppLog.d(TAG, "Switch coalesced into pending request ($source)")
                return
            }
            // Timer ticks are repetitive: when the queue is full (the engine is
            // still draining a backlog), drop the tick instead of accumulating
            // suspended senders — the next tick arrives within one interval
            // anyway. User triggers (double-tap / unlock / recovery) use the
            // guaranteed send below.
            if (source == SOURCE_TIMER) {
                if (switchChannel.trySend(SwitchRequest(source, null)).isSuccess) return
                AppLog.d(TAG, "Switch queue full, dropping $source tick")
                pendingAutoSwitch.set(false)
                return
            }
            // Guaranteed enqueue: send suspends until the queue has space, so a
            // trigger can never be silently dropped when the queue is full.
            scope.launch {
                switchChannel.send(SwitchRequest(source, null))
            }
        }

        private fun ensureSwitchConsumer() {
            if (consumerStarted.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        consumeSwitches()
                    } finally {
                        // Allow the consumer to be restarted if it ever dies.
                        consumerStarted.set(false)
                        // Any pending auto-request marker is stale now; a fresh
                        // trigger must be allowed to queue.
                        pendingAutoSwitch.set(false)
                    }
                }
            }
        }

        private suspend fun consumeSwitches() {
            for (req in switchChannel) {
                // This auto request was pulled from the queue: allow a new
                // non-target switch to queue while this one executes (the
                // rapid-tap coalescing window).
                if (req.targetId == null) pendingAutoSwitch.set(false)
                switchInProgress = true
                switchStartedAt = SystemClock.elapsedRealtime()
                try {
                    AppLog.d(TAG, "Switch start: ${req.source}")
                    currentSwitchSource = req.source
                    // Gap to the PREVIOUS switch: the prefetch rule uses it to
                    // tell "user is tapping rapidly" from "a one-off switch".
                    // 0 = no previous switch in this engine yet (a fresh engine
                    // used to report the whole elapsedRealtime uptime here, e.g.
                    // "Not a rapid switch (271624535ms)").
                    val sincePreviousSwitchMs = if (lastSwitchCompletedAt == 0L) {
                        0L
                    } else {
                        SystemClock.elapsedRealtime() - lastSwitchCompletedAt
                    }
                    executeSwitch(req.source, req.targetId)
                    lastSwitchCompletedAt = SystemClock.elapsedRealtime()
                    maybePrefetchNext(req.source, sincePreviousSwitchMs)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // Never let one bad switch kill the queue consumer.
                    AppLog.e(TAG, "Switch failed: ${req.source}", t)
                } finally {
                    switchInProgress = false
                    AppLog.d(TAG, "Switch done: ${req.source}")
                }
            }
        }

        private suspend fun executeSwitch(source: String, targetId: Long?) {
            // Only skip when the SCREEN is actually off: starting a decode in
            // the dark wastes battery, and the switch runs on the next tick
            // after screen-on. When another app merely covers the wallpaper
            // (screen still on), timer switches still execute - the new media
            // simply plays in the throttled power-save mode until visible.
            if (!powerManager.isInteractive()) {
                AppLog.d(TAG, "Skip $source switch while screen is off (power save)")
                return
            }
            val dao = db.settingsDao()
            val imageDao = db.wallpaperImageDao()
            applyClarityMode()
            autoRotateMismatch = try {
                dao.getBool(SettingsKeys.ROTATE_MISMATCH_ENABLED, true)
            } catch (_: Exception) {
                true
            }
            renderer?.autoRotateMismatch = autoRotateMismatch
            autoRotateClockwise = try {
                dao.getBool(SettingsKeys.ROTATE_MISMATCH_CW, true)
            } catch (_: Exception) {
                true
            }
            renderer?.autoRotateClockwise = autoRotateClockwise
            val switchMode = try {
                SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
            } catch (_: Exception) { SwitchMode.RANDOM }

            val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
            if (groups.isEmpty()) return

            // If target is already playing, skip restart (avoids video pause on "apply")
            if (targetId != null && targetId > 0 && targetId == lastDisplayedId) {
                clearPrefetchCache()
                if (videoMode && renderer?.isVideoPlaying == true) {
                    AppLog.d(TAG, "Target $targetId already playing, skip")
                    return
                }
                val shownBmp = currentBitmap
                if (!videoMode && shownBmp != null && !shownBmp.isRecycled) {
                    AppLog.d(TAG, "Target $targetId already showing, skip")
                    return
                }
                // A GIF already rendering must not be re-decoded and restarted
                // by re-selecting the same media (the animation would flash and
                // restart from the first frame).
                if (!videoMode && gifDrawable != null && gifFrameRunnable != null) {
                    AppLog.d(TAG, "Target $targetId GIF already rendering, skip")
                    return
                }
            }

            currentScaleMode = try {
                ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
            } catch (_: Exception) { ScaleMode.FIT }

            var cachedBitmap: Bitmap? = null
            // GPU quarter turn that belongs to [cachedBitmap] (see takePrefetchCache).
            var cachedRotateCw: Boolean? = null
            var nextImage = if (targetId != null && targetId > 0) {
                // A manual selection supersedes any prefetched next image.
                clearPrefetchCache()
                val img = imageDao.getImageById(targetId)
                // Explicit user selection always applies the chosen media.
                // Silently replacing a manual pick with a random image was
                // confusing. A DISABLED group's media never reaches this point:
                // the user-facing entry points (setAsLiveWallpaper /
                // setImageAsWallpaper) refuse a disabled group, and the internal
                // callers (media-type repair, a confirmed pick) take their id from
                // an enabled pick - so this permissive rule cannot re-apply media
                // from a group the user switched off.
                img ?: pickNextImage(SwitchMode.RANDOM, imageDao, 0L, dao)
            } else {
                val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                // Auto switch: consume the prefetch cache when it is still
                // valid, otherwise pick normally.
                // If a prefetch decode is already in flight (rapid double-tap
                // burst), briefly wait for it instead of launching a second
                // concurrent screen-size decode: parallel decodes spike memory
                // and CPU and make switching visibly stutter.
                if (prefetchInProgress.get()) {
                    var waited = 0
                    while (waited < 250) {
                        val ready = synchronized(prefetchLock) {
                            prefetchedImageId > 0L || prefetchedBitmap != null
                        }
                        if (ready || !prefetchInProgress.get()) break
                        delay(10)
                        waited += 10
                    }
                }
                // The cache holds the "next" image for the switch mode that was
                // active when it was built. If the user changed the mode since
                // (e.g. RANDOM -> SEQUENTIAL), it is stale: drop it so the new
                // mode picks fresh (SEQUENTIAL continues after the currently
                // displayed wallpaper instead of showing a leftover random
                // prefetch).
                synchronized(prefetchLock) {
                    if (prefetchedImageId > 0L && prefetchedSwitchMode != switchMode) {
                        val b = prefetchedBitmap
                        prefetchedBitmap = null
                        prefetchedImageId = 0L
                        prefetchedSwitchMode = null
                        prefetchedRotateCw = null
                        if (b != null && !b.isRecycled) b.recycle()
                    }
                }
                val prefetched = takePrefetchCache()
                val cachedId = prefetched.imageId
                val cachedBmp = prefetched.bitmap
                cachedRotateCw = prefetched.rotateCw
                var cached: WallpaperImage? = null
                if (cachedId > 0L && cachedBmp != null) {
                    val img = imageDao.getImageById(cachedId)
                    if (img != null && !MediaTypes.isMotion(img.mediaType) &&
                        img.id !in failedMediaIds
                    ) {
                        val group = db.wallpaperGroupDao().getGroupById(img.groupId)
                        if (group != null && group.isEnabled) cached = img
                    }
                    if (cached == null) {
                        cachedBmp.recycle()
                    }
                }
                if (cached != null) {
                    cachedBitmap = cachedBmp
                    cached
                } else {
                    // Query the enabled count ONCE and reuse it: the SHUFFLE
                    // path used to re-count on every random attempt (up to 10x
                    // per switch) plus its own total-count query.
                    val enabledCount = enabledCountCached(imageDao, HOME_SLOT)
                    pickNextImage(switchMode, imageDao, lastId, dao, enabledCount = enabledCount)
                }
            }

            if (nextImage == null) {
                nextImage = imageDao.getFirstFromEnabledGroups(HOME_SLOT)
                if (nextImage == null) return
            }

            // Skip media that recently failed to start (broken video files) so
            // recovery switches never re-pick the same broken item.
            var media: WallpaperImage = nextImage
            if (media.id in failedMediaIds) {
                var attempts = 0
                while (attempts < FAILED_MEDIA_RETRY_LIMIT) {
                    attempts++
                    // Exclude the PREVIOUS candidate on every pass. The old loop
                    // only assigned `media` when it had already found a healthy
                    // item, so all five queries excluded the same id and four of
                    // them were pure repetition (wasted media-library reads).
                    val alt = imageDao.getRandomImageFromEnabledGroupsExcluding(HOME_SLOT, media.id)
                        ?: imageDao.getRandomImageFromEnabledGroups(HOME_SLOT)
                        ?: break
                    media = alt
                    if (alt.id !in failedMediaIds) break
                }
            }
            nextImage = media

            // The media the engine is already showing: a timer tick that picks it
            // again (one-item group, sequential/shuffle wrap) must not restart
            // it - a video would visibly jump back to its first frame, and the
            // decode + texture upload is pure waste. The cursor still points at
            // this media, so the next distinctive pick works as before.
            if (targetId == null && lastDisplayedId != 0L && nextImage.id == lastDisplayedId) {
                AppLog.d(
                    TAG,
                    "Auto switch picked the media already on screen (${nextImage.displayName}); skipping"
                )
                return
            }
            val mediaType = nextImage.mediaType
            // Include the id: multiple files can share the same display name
            // (logs showed '1 (7).jpg' twice with different ids), which made
            // duplicate/order investigations ambiguous.
            AppLog.d(TAG, "Switch to: ${nextImage.displayName} ($mediaType) id=${nextImage.id}")

            pauseGif()

            // A video gets its fade-in from the render thread once its first
            // frame is really on screen (see onFirstVideoFrame); a switch must
            // never suppress that.
            var fadeHandledByFirstFrame = false
            // Did THIS switch manage to put its media on screen? A boolean of its
            // own, because `lastDisplayedId` is shared: a second switch that starts
            // right after (rapid taps + the prefetch flow) overwrites it, and the
            // old check then thought this media was never shown - which left the
            // shuffle pass without a record of it and repeated it later.
            var appliedThisSwitch = false
            suppressFadeUntilFirstFrame = false
            fadePendingForFirstFrame = false
            when (mediaType) {
                MediaTypes.VIDEO -> {
                    // Image/GIF -> Video. The previous image stays on screen: its
                    // GL texture still holds the pixels and the codec renders
                    // into its own texture, so the bitmap is only released once the
                    // new video's first frame is really presented (see
                    // onFirstVideoFrame). Recycling it here left a window where a
                    // redraw had nothing to draw - the image->video black flash.
                    stopVideo()
                    // No extra settle delay: stopVideo() already joins the decode
                    // thread (bounded, <=120ms) before returning.
                    retireCurrentBitmap()
                    if (startVideo(
                            nextImage.uri, currentScaleMode, resumePositionFor(nextImage.id)
                        )
                    ) {
                        lastDisplayedId = nextImage.id
                        appliedThisSwitch = true
                        fadeHandledByFirstFrame = true
                        // Decided here, applied by onFirstVideoFrame once the
                        // frame is actually on screen.
                        fadePendingForFirstFrame = !wasRapidSwitch()
                    }
                }
                MediaTypes.GIF -> {
                    // Any → GIF: stop video atomically (show nothing, GIF will overwrite)
                    stopVideo()
                    delay(SWITCH_SETTLE_DELAY_MS)
                    clearCurrentBitmap()
                    videoMode = false
                    mainHandler.post { playGif(nextImage.uri, currentScaleMode, nextImage.id) }
                    startGifHealthMonitor(nextImage.id, nextImage.uri)
                    lastDisplayedId = nextImage.id
                    appliedThisSwitch = true
                }
                else -> {
                    // Any → Image: load bitmap FIRST, then stop video + render atomically
                    videoMode = false
                    var bitmap = cachedBitmap
                    var rotateCw = cachedRotateCw
                    if (bitmap == null || bitmap.isRecycled) {
                        AppLog.d(TAG, "Loading image bitmap: ${LogText.short(nextImage.uri)}")
                        val loaded = loadBitmapWithTimeout(nextImage.uri, media = nextImage)
                        bitmap = loaded?.bitmap
                        rotateCw = loaded?.rotateCw
                    } else {
                        AppLog.d(TAG, "Using prefetched bitmap: ${nextImage.displayName} id=${nextImage.id}")
                    }
                    if (bitmap != null) {
                        // rotate= is the GPU quarter turn (see computeQuad): it is
                        // what replaced the old full-screen CPU rotation copy.
                        AppLog.d(
                            TAG,
                            "Bitmap loaded: ${bitmap.width}x${bitmap.height} " +
                                "rotate=${rotateCw?.let { if (it) "cw" else "ccw" } ?: "none"}"
                        )
                        // Recycle old bitmap to avoid memory leak
                        val old = currentBitmap
                        currentBitmap = bitmap
                        if (old != null && old != bitmap && !old.isRecycled) {
                            old.recycle()
                        }
                        // Always use stopVideoAndRender for clean transition.
                        // Even if isVideoPlaying is false, the decode thread might
                        // still be running and its cleanup could interfere.
                        // rotateCw: the FILL/STRETCH orientation turn is done by
                        // the quad, so no full-screen copy was needed here.
                        renderer?.stopVideoAndRender(bitmap, currentScaleMode, rotateCw)
                        lastDisplayedId = nextImage.id
                        appliedThisSwitch = true
                    } else {
                        AppLog.e(TAG, "Failed to load bitmap for: ${nextImage.displayName} uri=${LogText.short(nextImage.uri)}")
                        // An image that cannot be decoded may simply be
                        // mis-typed: older builds stored SAF videos as IMAGE
                        // (extension-less display names on non-Xiaomi
                        // devices), which is exactly the "video shows black"
                        // bug. Repair the row and retry that same media once
                        // instead of blocklisting a perfectly good file.
                        if (repairMisTypedMedia(nextImage)) {
                            AppLog.w(
                                TAG,
                                "Retrying ${nextImage.displayName} after repairing its media type"
                            )
                            lastDisplayedId = 0L
                            requestSwitch("media-type-repair", nextImage.id)
                        } else {
                            // A broken/deleted file must not leave the
                            // wallpaper black/stuck until the next timer tick:
                            // blocklist the id and schedule a recovery switch
                            // to a different media (bounded like the video
                            // recovery path).
                            failedMediaIds.add(nextImage.id)
                            lastDisplayedId = 0L
                            recoveryFailCount++
                            // Permanently gone (deleted / moved / permission
                            // revoked): drop the row so future sessions and timer
                            // ticks stop re-reading a dead URI.
                            dropMediaIfGone(nextImage.id)
                            if (recoveryFailCount <= 5) {
                                requestSwitch("recovery")
                            }
                        }
                    }
                }
            }
            // A healthy media was applied: drop THIS id from the failure
            // blocklist so a previously-broken file can be retried later (the
            // old `id !in failedMediaIds` guard made the cleanup dead code
            // exactly when the current media was the previously-broken one).
            // GIF is excluded: its decode is asynchronous (playGif), so
            // success can only be known when the animation actually starts —
            // markGifSuccess() handles that. Without this exclusion, every
            // broken GIF reset recoveryFailCount synchronously and the engine
            // would auto-recover forever when ALL GIFs in a group are broken.
            if (appliedThisSwitch && mediaType != MediaTypes.GIF) {
                failedMediaIds.remove(nextImage.id)
                if (failedMediaIds.isEmpty()) recoveryFailCount = 0
            }
            // Advance the switching cursor only for media that really reached
            // the screen. The old code wrote it before the decode, so a failed
            // (or later blocklisted) media was silently skipped for the rest of
            // the pass - the opposite of what the failure path claims.
            if (appliedThisSwitch) {
                try {
                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
                } catch (_: Exception) {
                }
            }
            // SHUFFLE: record the item as shown when THIS switch applied it.
            // (Still not at pick/prefetch time: a prefetched media whose cache was
            // invalidated, or whose apply failed, must stay available.) The flag is
            // per switch, so a following switch overwriting `lastDisplayedId` can no
            // longer make this media look "never shown" - that was how a pass came
            // to deal an image it had already displayed.
            if (appliedThisSwitch && switchMode == SwitchMode.SHUFFLE) {
                shuffleShownIds.add(nextImage.id)
                shuffleAllCount = enabledCountCached(imageDao, HOME_SLOT)
                // Persist the pass progress NOW, not only in onDestroy: MIUI kills
                // and recreates the wallpaper engine often, and a stale/missing
                // "already shown" set made the rebuilt deck deal images that had
                // already been shown before the pass finished.
                //
                // Only the NEWLY shown id is written (one row in `shuffle_shown`),
                // so this is cheap enough to do on every switch - the old code
                // rewrote the entire deck as a comma-separated string here (up to
                // ~230KB for a 38k library), which is why it had to be throttled.
                pendingShuffleIds.add(nextImage.id)
                flushShuffleState()
            }
            if (lastDisplayedId == nextImage.id && !fadeHandledByFirstFrame) maybeFade()
        }

        /**
         * Atomically consume the prefetch cache. Both id and bitmap are zero/null
         * when nothing is cached; the caller owns the bitmap once returned. The
         * rotation travels with it - the prefetched pixels are NOT rotated (the
         * renderer applies it), so the consuming switch must use the same value.
         */
        private fun takePrefetchCache(): PrefetchedImage {
            synchronized(prefetchLock) {
                val id = prefetchedImageId
                val bmp = prefetchedBitmap
                val rotateCw = prefetchedRotateCw
                prefetchedImageId = 0L
                prefetchedBitmap = null
                prefetchedSwitchMode = null
                prefetchedRotateCw = null
                if (id <= 0L || bmp == null || bmp.isRecycled) {
                    if (bmp != null && !bmp.isRecycled) bmp.recycle()
                    return PrefetchedImage(0L, null, null)
                }
                return PrefetchedImage(id, bmp, rotateCw)
            }
        }

        private fun clearPrefetchCache() {
            prefetchExpiryRunnable?.let { mainHandler.removeCallbacks(it) }
            prefetchExpiryRunnable = null
            synchronized(prefetchLock) {
                val b = prefetchedBitmap
                prefetchedBitmap = null
                prefetchedImageId = 0L
                prefetchedSwitchMode = null
                prefetchedRotateCw = null
                prefetchedAt = 0L
                if (b != null && !b.isRecycled) b.recycle()
            }
        }

        /**
         * Drop the prefetched bitmap if nobody consumed it within
         * [PREFETCH_KEEP_MS]. It only exists to make a rapid follow-up switch
         * instant, and it is a screen-size ARGB bitmap (tens of MB) - holding it
         * for an idle wallpaper is pure waste. A newer prefetch replaces
         * [prefetchedAt], so a stale expiry task cannot drop it.
         */
        private fun schedulePrefetchExpiry() {
            val storedAt = SystemClock.elapsedRealtime()
            prefetchedAt = storedAt
            // Replace the previous expiry task instead of stacking one per
            // prefetch: a rapid-tap burst would otherwise leave dozens of them
            // pending for two minutes.
            prefetchExpiryRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                val dropped = synchronized(prefetchLock) {
                    if (prefetchedBitmap != null && prefetchedAt == storedAt) {
                        clearPrefetchCache()
                        true
                    } else {
                        false
                    }
                }
                if (dropped) {
                    AppLog.d(TAG, "Dropped unused prefetch after ${PREFETCH_KEEP_MS / 1000}s")
                }
            }
            prefetchExpiryRunnable = runnable
            mainHandler.postDelayed(runnable, PREFETCH_KEEP_MS)
        }

        /**
         * Release optional memory on system low-memory pressure: the
         * prefetched next-image bitmap. The currently displayed media and its
         * GL resources are untouched; the next switch decodes fresh.
         */
        internal fun trimMemoryNow() {
            clearPrefetchCache()
            // The transition bitmap is optional memory: the normal path decodes
            // the image again if it is ever needed.
            releaseRetiredBitmap()
        }

        /**
         * True when this engine is visible and its EGL surface is ready to
         * present. See [isRenderSurfaceReady] for why the timer cares.
         */
        internal fun isRenderSurfaceReadyNow(): Boolean =
            surfaceReady && renderer?.isSurfaceReady() == true


        /**
         * Decode the next candidate image in the background so the following
         * auto-switch is near-instant. Only caches IMAGE media (never
         * videos/GIFs), only while the wallpaper is visible and the screen is
         * interactive, and never more than one image ahead. Picking is
         * side-effect free (SEQUENTIAL is item-based on LAST_IMAGE_ID, SHUFFLE
         * records shown items only when a switch actually applies them), so a
         * prefetch that is invalidated or fails can never skip an item.
         */
        private fun maybePrefetchNext(source: String, sincePreviousSwitchMs: Long) {
            if (!isVisible || !powerManager.isInteractive()) return
            synchronized(prefetchLock) {
                if (prefetchedImageId > 0L || prefetchedBitmap != null) return
            }
            // Prefetch ONLY while the user keeps switching rapidly: a warm
            // decode is what makes a double-tap burst / a run of floating-button
            // taps feel instant. A timer tick, or a user switch that came long
            // after the previous one, is decoded on demand in ~100ms instead -
            // pre-decoding there just doubled the number of media-library reads
            // ("每次切换都会访问手机和视频").
            if (source == SOURCE_TIMER) {
                AppLog.d(TAG, "Timer switch: skipping prefetch (media access)")
                return
            }
            if (sincePreviousSwitchMs <= 0L) {
                // First switch of this engine: there is no "previous" to compare
                // against, so this is never a rapid burst.
                AppLog.d(TAG, "First switch of this engine; skipping prefetch")
                return
            }
            if (sincePreviousSwitchMs > PREFETCH_RAPID_GAP_MS) {
                AppLog.d(TAG, "Not a rapid switch (${sincePreviousSwitchMs}ms); skipping prefetch")
                return
            }
            if (!prefetchInProgress.compareAndSet(false, true)) return
            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()
                    if (db.wallpaperGroupDao().getEnabledGroupsSync().isEmpty()) return@launch
                    val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    val switchMode = try {
                        SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                    } catch (_: Exception) { SwitchMode.RANDOM }
                    val next = pickNextImage(
                        switchMode, imageDao, lastId, dao,
                        forPrefetch = true,
                        enabledCount = enabledCountCached(imageDao, HOME_SLOT)
                    ) ?: return@launch
                    if (MediaTypes.isMotion(next.mediaType)) return@launch
                    if (next.id == lastDisplayedId || next.id in failedMediaIds) return@launch
                    AppLog.d(TAG, "Prefetching next image: ${next.displayName} id=${next.id}")
                    val prefetchedImage = loadBitmapWithTimeout(next.uri, media = next)
                    val bmp = prefetchedImage?.bitmap
                    if (bmp == null || bmp.isRecycled) return@launch
                    if (engineDestroyed || next.id == lastDisplayedId || next.id in failedMediaIds) {
                        bmp.recycle()
                        return@launch
                    }
                    var storedPrefetch = false
                    synchronized(prefetchLock) {
                        if (engineDestroyed || prefetchedImageId > 0L) {
                            bmp.recycle()
                        } else {
                            prefetchedImageId = next.id
                            prefetchedBitmap = bmp
                            prefetchedSwitchMode = switchMode
                            prefetchedRotateCw = prefetchedImage.rotateCw
                            storedPrefetch = true
                        }
                    }
                    if (storedPrefetch) {
                        AppLog.d(TAG, "Prefetch ready: ${next.displayName} id=${next.id}")
                        schedulePrefetchExpiry()
                    }
                } catch (_: Exception) {
                } finally {
                    prefetchInProgress.set(false)
                }
            }
        }

        private suspend fun pickNextImage(
            switchMode: SwitchMode, imageDao: WallpaperImageDao, lastId: Long, dao: SettingsDao,
            forPrefetch: Boolean = false, enabledCount: Int = -1
        ): WallpaperImage? {
            return when (switchMode) {
                SwitchMode.RANDOM -> {
                    MediaPick.random(imageDao, HOME_SLOT, lastId, enabledCount)
                }
                SwitchMode.SEQUENTIAL -> {
                    val count = if (enabledCount >= 0) enabledCount
                    else imageDao.countByEnabledGroups(HOME_SLOT)
                    if (count == 0) null else {
                        // Item-based cursor: continue AFTER the last displayed
                        // id, wrapping to the first enabled media when the deck
                        // is exhausted. Unlike an offset (SEQUENTIAL_INDEX)
                        // cursor, deleting/disabling media between switches can
                        // never skip an item, and prefetch needs no cursor
                        // bookkeeping at all.
                        val img = if (lastId > 0L) {
                            imageDao.getSequentialImageFromEnabledGroupsAfter(HOME_SLOT, lastId)
                                ?: imageDao.getFirstFromEnabledGroups(HOME_SLOT)
                        } else {
                            imageDao.getFirstFromEnabledGroups(HOME_SLOT)
                        }
                        // Prefetch must not pick media it cannot cache
                        // (video/GIF): the real switch still has to display
                        // them, so they stay at the current position.
                        if (forPrefetch && img != null &&
                            MediaTypes.isMotion(img.mediaType)
                        ) null else img
                    }
                }
                SwitchMode.SHUFFLE -> {
                    val totalCount = if (enabledCount >= 0) enabledCount
                    else imageDao.countByEnabledGroups(HOME_SLOT)
                    if (totalCount == 0) null else {
                        if (shuffleShownIds.isEmpty() && shuffleAllCount == 0) {
                            // Reload the deck the previous session left behind
                            // (see ShuffleShown): one indexed scan of small rows
                            // instead of parsing a comma-separated id string.
                            val savedIds = db.shuffleDao().getShownIds(HOME_SLOT)
                            val savedCount = dao.getLong(SettingsKeys.SHUFFLE_ALL_COUNT, 0L).toInt()
                            if (savedIds.isNotEmpty()) {
                                shuffleShownIds.addAll(savedIds)
                            }
                            shuffleAllCount = savedCount
                        }
                        // Only a FINISHED pass starts a new one. Changing the
                        // enabled set (import, toggle) keeps this pass going: the
                        // next pick filters the enabled ids by the shown ones, so
                        // nothing already shown can reappear before the pass is
                        // complete.
                        if (SwitchPicking.shouldResetShuffleDeck(
                                shuffleShownIds.size, totalCount
                            )
                        ) {
                            shuffleShownIds.clear()
                            db.shuffleDao().clearSlot(HOME_SLOT)
                        }
                        // Random UNSEEN pick, filtering the slot's id list in
                        // memory (the pre-review implementation, restored on
                        // request). The prefetch runs the same code, so the media
                        // it decodes ahead may differ from the next switch.
                        var candidate = MediaPick.shuffleUnseen(
                            imageDao = imageDao,
                            slot = HOME_SLOT,
                            shownIds = shuffleShownIds,
                            excludeId = lastId
                        )
                        if (candidate == null) {
                            // Every enabled media has been shown (or the only
                            // remaining one is the media already on screen):
                            // start a fresh pass (the pre-review fallback, restored
                            // on request).
                            shuffleShownIds.clear()
                            db.shuffleDao().clearSlot(HOME_SLOT)
                            candidate = imageDao.getRandomImageFromEnabledGroupsExcluding(HOME_SLOT, lastId)
                                ?: imageDao.getRandomImageFromEnabledGroups(HOME_SLOT)
                        }
                        // Diagnostics: a deck must be played through before an
                        // item may repeat, so the deck size and the pick are
                        // logged (a repeat with saved<N is a bug).
                        AppLog.d(
                            TAG,
                            "Shuffle pick: deck=${shuffleShownIds.size}/$totalCount " +
                                "(saved=$shuffleAllCount) -> id=${candidate?.id}"
                        )
                        // Prefetch of an uncacheable media (video/GIF) returns
                        // null so the caller aborts without caching anything.
                        if (forPrefetch && candidate?.mediaType != MediaTypes.IMAGE) null else candidate
                    }
                }
            }
        }

        /**
         * Re-run [drawCurrentImage] shortly, at most once per pending retry and
         * at most [REDRAW_RETRY_LIMIT] times in a row.
         *
         * The retries exist because a switch/redraw in flight must win (see the
         * callers), but they used to be queued unconditionally by every trigger -
         * a surface event, a visibility change and a rotation could each start
         * their own 10Hz chain on the main thread, and the chain never ended while
         * a media load kept `switchInProgress` set (a stuck cloud provider holds
         * it for the full 15s load timeout). At most one retry may be pending,
         * and the budget bounds the whole episode.
         *
         * The budget counts QUEUED retries only: one rotation fires three
         * triggers (surface created/changed + visibility) and two of them are
         * coalesced into the pending one - counting those burned the budget up to
         * three times too fast, so a genuinely blocked redraw gave up early and
         * could leave a stale frame on screen. When the budget IS exhausted one
         * final deferred attempt is made (a single one, never a loop) before the
         * episode is abandoned with a log line.
         *
         * @return false when the redraw was abandoned without another attempt.
         */
        private fun retryDrawCurrentImageSoon(): Boolean {
            // Coalesce first: a trigger that finds a retry already pending costs
            // nothing and must not consume budget.
            if (!redrawRetryQueued.compareAndSet(false, true)) return true
            if (redrawRetryCount.incrementAndGet() > REDRAW_RETRY_LIMIT) {
                if (redrawFinalAttemptDone.compareAndSet(false, true)) {
                    AppLog.w(
                        TAG,
                        "Redraw still blocked after ${redrawRetryCount.get()} retries; " +
                            "final attempt (switchInProgress=$switchInProgress " +
                            "surfaceReady=$surfaceReady)"
                    )
                    mainHandler.postDelayed({
                        redrawRetryQueued.set(false)
                        drawCurrentImage()
                    }, REDRAW_RETRY_DELAY_MS)
                    return true
                }
                redrawRetryQueued.set(false)
                AppLog.w(
                    TAG,
                    "Redraw abandoned after ${redrawRetryCount.get()} retries " +
                        "(switchInProgress=$switchInProgress surfaceReady=$surfaceReady)"
                )
                redrawRetryCount.set(0)
                return false
            }
            mainHandler.postDelayed({
                redrawRetryQueued.set(false)
                drawCurrentImage()
            }, REDRAW_RETRY_DELAY_MS)
            return true
        }

        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
            if (renderer?.powerSaveMode == true) {
                // Visible but the power-save flag is still set: a resume waits
                // [VISIBILITY_DEBOUNCE_MS] before it is applied, and the redraw
                // that follows immediately on onVisibilityChanged(true) used to
                // hit this line and be DROPPED. The media released while locked
                // then never came back: the wallpaper stayed frozen on the still
                // frame of a video (emulator log 05:21:45 released → 05:21:59
                // visible → no "Video started" ever again), so the video's
                // playback position and the file simply stopped matching.
                // Retry while the wallpaper is really visible; when it is covered
                // there is nothing to draw and the retry is skipped.
                if (isVisible) retryDrawCurrentImageSoon()
                return
            }
            val r = renderer ?: return
            if (switchInProgress) {
                // A switch is running right now; retry shortly instead of
                // racing with it (concurrent startVideo + stopVideoAndRender on
                // the GL renderer caused native crashes during timed switches).
                retryDrawCurrentImageSoon()
                return
            }
            // Guard against concurrent redraws (e.g. a surface event and a
            // visibility change firing at the same time) - two concurrent
            // drawCurrentImage calls would start the same video twice.
            if (!redrawInProgress.compareAndSet(false, true)) {
                // A redraw is already running (e.g. rapid rotation triggered a
                // new surface change while the old one is still decoding).
                // Retry shortly instead of dropping: the LAST state (final
                // orientation/size) must always win, otherwise the wallpaper
                // can be left showing a stale-orientation frame.
                retryDrawCurrentImageSoon()
                return
            }
            // This attempt really runs: the retry budget applies to the current
            // episode only.
            redrawRetryCount.set(0)
            redrawFinalAttemptDone.set(false)

            scope.launch {
                try {
                    if (switchInProgress) {
                        retryDrawCurrentImageSoon()
                        return@launch
                    }
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()
                    applyClarityMode()
                    autoRotateMismatch = try {
                        dao.getBool(SettingsKeys.ROTATE_MISMATCH_ENABLED, true)
                    } catch (_: Exception) {
                        true
                    }
                    renderer?.autoRotateMismatch = autoRotateMismatch
                    autoRotateClockwise = try {
                        dao.getBool(SettingsKeys.ROTATE_MISMATCH_CW, true)
                    } catch (_: Exception) {
                        true
                    }
                    renderer?.autoRotateClockwise = autoRotateClockwise
                    var imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)

                    val orientationRedraw = pendingOrientationRedraw.compareAndSet(true, false)
                    if (!orientationRedraw && imageId == lastDisplayedId && lastDisplayedId != 0L) {
                        if (videoMode && r.isVideoPlaying) return@launch
                        val shownBmp = currentBitmap
                        if (!videoMode && shownBmp != null && !shownBmp.isRecycled) return@launch
                        // A GIF that is already rendering must not be re-decoded
                        // and restarted by a coincidental redraw (e.g. a
                        // visibility retry while a switch is settling).
                        if (!videoMode && gifDrawable != null && gifFrameRunnable != null) return@launch
                    }

                    currentScaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }
                    var image = if (imageId > 0) imageDao.getImageById(imageId) else null

                    if (image == null) {
                        image = imageDao.getFirstFromEnabledGroups(HOME_SLOT)
                        if (image != null) dao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                    } else {
                        val group = db.wallpaperGroupDao().getGroupById(image.groupId)
                        // The engine renders the HOME screen: media whose group
                        // does not target 桌面 (lock-only groups, disabled
                        // groups, deleted groups) must never be shown here, even
                        // if LAST_IMAGE_ID still points at it (e.g. left over
                        // from a manual lock pick). Fall back to a home pick.
                        val homeSuitable = group != null && group.isEnabled &&
                            com.wallpaperswitcher.engine.WallpaperTarget
                                .fromName(group.target).suitsSlot(HOME_SLOT)
                        if (!homeSuitable) {
                            AppLog.d(
                                TAG,
                                "Last shown media is not for the home screen, picking a home one"
                            )
                            image = imageDao.getFirstFromEnabledGroups(HOME_SLOT)
                            if (image != null) dao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                        }
                    }

                    pauseGif()
                    // Remembered BEFORE the reset below: the video branch needs
                    // to know whether the media on screen was a playing clip
                    // (the old code tested `videoMode` after clearing it, so the
                    // rotation-resume branch could never run).
                    val videoWasPlaying = videoMode
                    videoMode = false
                    // True when this redraw is the desktop coming back after the
                    // decoder was released for power save: no fade then (it would
                    // read as a black flash while the video rebuilds).
                    val resumedFromPowerSave = resumeFromPowerSave
                    resumeFromPowerSave = false

                    if (image != null) {
                        when (image.mediaType) {
                            MediaTypes.VIDEO -> {
                                // A ROTATION while this exact clip is on screen must
                                // NOT restart it. The renderer recreates its EGL
                                // surface and recomputes the video quad in
                                // surfaceChanged(), and the decode thread keeps
                                // feeding frames, so nothing has to be re-decoded
                                // here. Restarting (the old behaviour) discarded the
                                // playback position AND the audio session: the
                                // tablet log shows every turn as
                                // "Surface size changed" → "startVideo" →
                                // "Video started", with the picture visibly
                                // snapping back to the first frame.
                                val rotationKeepsClip = orientationRedraw &&
                                    r.isCurrentVideo(image.uri)
                                if (rotationKeepsClip && videoWasPlaying &&
                                    r.isVideoPlaying
                                ) {
                                    AppLog.d(TAG, "Rotation: video keeps playing (no restart)")
                                    // Re-apply the quad against the new surface size
                                    // (the turn may have flipped the auto-rotate
                                    // mismatch decision) without touching the
                                    // session.
                                    r.refreshAfterAutoRotateChange()
                                    return@launch
                                }
                                clearCurrentBitmap()
                                // The session did have to be rebuilt (the rotation
                                // destroyed the surface, or the decoder was released
                                // while the device was locked): continue at the
                                // position that was playing instead of jumping back
                                // to 0. The still frame shown while the decoder
                                // rebuilds is taken from this position too (see the
                                // release path), so the rebuild is seamless.
                                val resumeUs = resumePositionFor(image.id).let { pending ->
                                    if (pending > 0L) {
                                        pending
                                    } else if (rotationKeepsClip) {
                                        r.lastVideoPositionUs.also { position ->
                                            AppLog.d(
                                                TAG,
                                                "Rotation: video continues at " +
                                                    "${position / 1000}ms (not from the start)"
                                            )
                                        }
                                    } else {
                                        0L
                                    }
                                }
                                if (startVideo(
                                        image.uri, currentScaleMode, resumeUs
                                    )
                                ) {
                                    lastDisplayedId = image.id
                                    // The fade is started by the renderer once the
                                    // video's first frame is on screen. On a
                                    // rotation redraw it is suppressed entirely
                                    // (the 200ms black overlay would stack on the
                                    // system rotation animation), and after a
                                    // power-save release it is suppressed too.
                                    suppressFadeUntilFirstFrame =
                                        orientationRedraw || resumedFromPowerSave
                                    fadePendingForFirstFrame =
                                        !orientationRedraw && !resumedFromPowerSave &&
                                            !wasRapidSwitch()
                                } else {
                                    lastDisplayedId = 0L
                                }
                                return@launch
                            }
                            MediaTypes.GIF -> {
                                clearCurrentBitmap()
                                videoMode = false
                                mainHandler.post { playGif(image.uri, currentScaleMode, image.id) }
                                startGifHealthMonitor(image.id, image.uri)
                                lastDisplayedId = image.id
                                if (!orientationRedraw && !resumedFromPowerSave) maybeFade()
                                return@launch
                            }
                            else -> {
                                videoMode = false
                                // A rotation redraw usually arrives right after
                                // the system rotated the DISPLAY - MIUI runs its
                                // portrait-only wallpaper picker rotated when the
                                // tablet is landscape, and the wallpaper surface
                                // follows it. That is exactly the moment the user
                                // is watching, and re-decoding a display-sized
                                // photo there costs 150-500ms plus a fresh
                                // 39MB upload (report: 横屏设置动态壁纸卡顿、
                                // 竖屏不卡). When the bitmap that was decoded for
                                // the old orientation already covers the new
                                // screen there is nothing to decode - just
                                // re-apply the quad/90° decision.
                                val shownBmp = currentBitmap
                                val sw = cachedScreenW.toInt()
                                val sh = cachedScreenH.toInt()
                                if (orientationRedraw && image.id == lastDisplayedId &&
                                    shownBmp != null && !shownBmp.isRecycled &&
                                    sw > 0 && sh > 0 &&
                                    bitmapFillsScreen(shownBmp, sw, sh)
                                ) {
                                    val newRotate =
                                        com.wallpaperswitcher.engine.BitmapUtils
                                            .fillRotationFor(
                                                shownBmp, sw, sh,
                                                autoRotateMismatch, autoRotateClockwise
                                            )
                                    AppLog.d(
                                        TAG,
                                        "Rotation: re-presenting ${shownBmp.width}x${shownBmp.height} " +
                                            "(big enough for ${sw}x$sh, no re-decode)"
                                    )
                                    r.refreshImageQuad(newRotate)
                                    lastDisplayedId = image.id
                                    return@launch
                                }
                                if (orientationRedraw) {
                                    // Why the fast path above did not apply - the
                                    // guards are silent otherwise, and "it still
                                    // re-decoded" is otherwise undiagnosable from a
                                    // user log.
                                    val bmpOk = shownBmp != null && !shownBmp.isRecycled
                                    AppLog.d(
                                        TAG,
                                        "Rotation: re-decoding " +
                                            "${shownBmp?.width}x${shownBmp?.height} for ${sw}x$sh " +
                                            "(idMatch=${image.id == lastDisplayedId} " +
                                            "bmpOk=$bmpOk " +
                                            "fills=${if (shownBmp != null && bmpOk) bitmapFillsScreen(shownBmp, sw, sh) else false})"
                                    )
                                }
                                AppLog.d(TAG, "drawCurrentImage loading bitmap: ${LogText.short(image.uri)}")
                                // Decode against the actual wallpaper Surface
                                // size: on tablets the display metrics can lag
                                // behind the recreated surface right after a
                                // rotation, which would otherwise re-decode for
                                // the previous orientation.
                                val bitmap = loadBitmapWithTimeout(
                                    image.uri,
                                    screenW = cachedScreenW.toInt().takeIf { it > 0 },
                                    screenH = cachedScreenH.toInt().takeIf { it > 0 },
                                    media = image
                                )
                                if (bitmap?.bitmap != null) {
                                    val bmp = bitmap.bitmap
                                    AppLog.d(TAG, "drawCurrentImage bitmap loaded: ${bmp.width}x${bmp.height}")
                                    val old = currentBitmap
                                    currentBitmap = bmp
                                    if (old != null && old !== bmp && !old.isRecycled) {
                                        old.recycle()
                                    }
                                    r.stopVideoAndRender(bmp, currentScaleMode, bitmap.rotateCw)
                                    lastDisplayedId = image.id
                                    if (!orientationRedraw && !resumedFromPowerSave) maybeFade()
                                    return@launch
                                } else {
                                    AppLog.e(TAG, "drawCurrentImage failed to load bitmap: ${LogText.short(image.uri)}")
                                    lastDisplayedId = 0L
                                    // Self-heal for media that an older build
                                    // stored as IMAGE while the file is really
                                    // a video/GIF (SAF hands back
                                    // extension-less display names on
                                    // non-Xiaomi devices - the "video shows
                                    // black" bug). Repair the row once and
                                    // redraw, so existing libraries fix
                                    // themselves without re-adding the file.
                                    if (repairMisTypedMedia(image)) {
                                        mainHandler.postDelayed({ drawCurrentImage() }, 250L)
                                    }
                                }
                            }
                        }
                    }
                    videoMode = false
                    r.showImage(getDefaultBitmap(), currentScaleMode)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    AppLog.e(TAG, "drawCurrentImage error", t)
                } finally {
                    redrawInProgress.set(false)
                }
            }
        }

        /**
         * Sync the clarity-enhancement setting into the renderer. Called before
         * every switch/redraw so the user's choice applies to the next media.
         */
        private suspend fun applyClarityMode() {
            val mode = try {
                db.settingsDao().getString(SettingsKeys.CLARITY_MODE, "auto")
            } catch (_: Exception) {
                "auto"
            }
            renderer?.sharpnessScale = if (isPreview) 0f else clarityStrength(mode)
        }

        /**
         * Start the fade-in transition after a switch when the setting is
         * enabled and the wallpaper is actually visible (skip it while covered
         * or the screen is off - nobody can see it then).
         */
        private suspend fun maybeFade(force: Boolean = false) {
            if (renderer?.powerSaveMode == true) return
            if (renderer?.isSurfaceReady() != true) return
            // 用户主动点击（悬钮 / 双击 / 「立即切换壁纸」）不淡入：淡入的第一帧
            // 是纯黑，再花 200ms 渐显，点一下要等这段黑场才看到新图，就是"不跟手"
            // 的来源。过渡动画设置仍然作用于自动切换（定时 / 解锁 / 恢复）。
            if (isManualSwitchSource(currentSwitchSource)) {
                AppLog.d(TAG, "Manual switch, skipping fade (instant response)")
                return
            }
            // Rapid successive switches (double-tap bursts / quick taps) skip
            // the fade so the wallpaper changes feel instant.
            // [force] is used by the video first-frame path, where the decision
            // was already made when the switch started (the frame arrives a few
            // hundred ms later and would otherwise always look "rapid").
            if (!force && wasRapidSwitch()) {
                AppLog.d(TAG, "Rapid switch, skipping fade")
                return
            }
            val enabled = try {
                db.settingsDao().getBool(SettingsKeys.SWITCH_FADE_ENABLED, true)
            } catch (_: Exception) {
                true
            }
            if (enabled) {
                AppLog.d(TAG, "Fade-in requested")
                renderer?.requestFade()
            }
        }

        /** True for the triggers a user performs by hand (see [maybeFade]). */
        private fun isManualSwitchSource(source: String): Boolean =
            source == SOURCE_MANUAL ||
                source == SOURCE_DOUBLE_TAP ||
                source == SOURCE_FLOATING

        /** True when the previous switch finished less than the fade-skip window ago. */
        private fun wasRapidSwitch(): Boolean =
            SystemClock.elapsedRealtime() - lastSwitchCompletedAt < RAPID_SWITCH_FADE_SKIP_MS

        /**
         * Position (µs) this video should resume at, when it is the media whose
         * session was released while locked (see the fields above). 0 = play from
         * the beginning. The remembered value is consumed so a later switch back
         * to the same clip starts normally.
         */
        private fun resumePositionFor(mediaId: Long): Long {
            if (mediaId <= 0L || mediaId != resumeVideoMediaId) return 0L
            val position = resumeVideoPositionUs
            if (position > 0L) {
                AppLog.d(TAG, "Resuming video at ${position / 1000}ms (lock release)")
            }
            return position
        }

        private fun startVideo(
            uriStr: String,
            scaleMode: ScaleMode,
            startPositionUs: Long = 0L
        ): Boolean {
            if (!surfaceReady) {
                AppLog.w(TAG, "startVideo: surface not ready")
                return false
            }
            val r = renderer
            if (r == null) {
                AppLog.w(TAG, "startVideo: renderer not ready")
                return false
            }
            videoMode = true
            AppLog.d(TAG, "startVideo: ${LogText.short(uriStr)}")
            r.startVideo(uriStr, scaleMode, startPositionUs)
            // A video really started: the pending resume belongs to it (or was
            // stale for a different clip) - either way it must not leak into a
            // later switch.
            resumeVideoMediaId = 0L
            resumeVideoPositionUs = 0L
            startVideoHealthMonitor()
            return true
        }

        /**
         * Watchdog for a stalled video: if the renderer stops presenting
         * frames for 8s while the engine still believes it is playing, report
         * the failure so the switch queue recovers with a different media.
         */
        private fun startVideoHealthMonitor() {
            videoHealthJob?.cancel()
            val startAt = SystemClock.elapsedRealtime()
            videoHealthJob = scope.launch {
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
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
                    if (!videoMode || renderer?.isVideoPlaying != true) return@launch
                    val nowCheck = SystemClock.elapsedRealtime()
                    // Missed polls => this process was frozen (doze / screen off).
                    // That window must not be counted as playback time: refresh
                    // the reference and continue instead of "recovering".
                    if (nowCheck - lastCheckAt > 30_000L) {
                        renderer?.resetVideoFrameClock()
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
                        renderer?.resetVideoFrameClock()
                        noFrameDeadlineAt = SystemClock.elapsedRealtime() + 8_000L
                        // Nothing to detect while the screen is off: check once a
                        // minute instead of every 10s.
                        pollMs = VIDEO_WATCHDOG_HIDDEN_POLL_MS
                        continue
                    }
                    // Wallpaper hidden (another app in front / the system
                    // live-wallpaper dialog): the video is paused on purpose and
                    // produces no frames - that is not a stall.
                    if (renderer?.powerSaveMode == true) {
                        renderer?.resetVideoFrameClock()
                        noFrameDeadlineAt = SystemClock.elapsedRealtime() + 8_000L
                        // Same as above: a wallpaper covered by another app used to
                        // cost 6 wakeups per minute for as long as the user stayed
                        // in that app.
                        pollMs = VIDEO_WATCHDOG_HIDDEN_POLL_MS
                        continue
                    }
                    val now = nowCheck
                    val last = renderer?.lastVideoFrameAt ?: 0L
                    if (last < startAt) {
                        // No frame since this video started. Slow/cloud sources
                        // can take a while for the first frame, but 8s is
                        // already generous on-device; recovering sooner keeps
                        // the black-screen window short on devices whose codec
                        // path never produces frames.
                        if (now > noFrameDeadlineAt) {
                            AppLog.w(TAG, "Video never presented a frame in ${(now - startAt) / 1000}s; recovering (last=$last)")
                            onVideoStartFailed()
                            return@launch
                        }
                    } else if (now - last > 12_000L) {
                        AppLog.w(TAG, "Video stalled: no frame for ${(now - last) / 1000}s; recovering")
                        onVideoStartFailed()
                        return@launch
                    } else {
                        // Playing healthily: any previous recovery failures are
                        // stale, so auto-recovery can kick in again if needed.
                        if (recoveryFailCount > 0) recoveryFailCount = 0
                    }
                }
            }
        }

        private fun playGif(uriStr: String, scaleMode: ScaleMode, mediaId: Long) {
            if (!surfaceReady) return
            pendingGifUri = uriStr
            // Decode off the main thread: ImageDecoder's first pass for a large
            // GIF can take tens of ms and would jank the UI on the main looper.
            // The drawable (or fallback bitmap) is handed back to the main
            // thread for the actual animation, guarded so a stale decode that
            // lost the race against a newer switch is discarded.
            scope.launch {
                var decodedDrawable: android.graphics.drawable.Drawable? = null
                try {
                    if (Build.VERSION.SDK_INT >= 28) {
                        // Hard timeout: ImageDecoder.decodeDrawable can block
                        // forever on a stuck cloud/SAF provider (coroutine
                        // cancellation cannot interrupt it), which used to
                        // leave pendingGifUri stuck and the wallpaper frozen on
                        // the previous frame until the next switch.
                        val drawable = decodeGifDrawableWithTimeout(uriStr)
                        decodedDrawable = drawable
                        // Frame timing for the ticker (see GifTiming): read here,
                        // on the IO thread, so the drawable's own delays decide
                        // how often a frame is rasterized and uploaded instead
                        // of the fixed 20fps cadence. Null keeps that cadence.
                        val frameDelaysMs = if (drawable != null) {
                            com.wallpaperswitcher.engine.GifTiming
                                .frameDelaysMs(applicationContext, uriStr)
                        } else {
                            null
                        }
                        mainHandler.post {
                            if (pendingGifUri != uriStr || !surfaceReady) {
                                try { (drawable as? java.lang.AutoCloseable)?.close() } catch (_: Exception) {}
                                return@post
                            }
                            pendingGifUri = null
                            if (drawable != null) {
                                playGif28(drawable, scaleMode, mediaId, frameDelaysMs)
                            } else {
                                onGifFailed(mediaId)
                            }
                        }
                    } else {
                        // The GIF ticker draws this frame itself, so the quarter
                        // turn has to be baked here (the renderer's quad rotation
                        // is only used for the direct image path).
                        val bmp = loadBakedBitmap(uriStr)
                        mainHandler.post {
                            if (pendingGifUri != uriStr || !surfaceReady) {
                                if (bmp != null && !bmp.isRecycled) bmp.recycle()
                                return@post
                            }
                            pendingGifUri = null
                            if (bmp != null) {
                                renderer?.showImage(bmp, scaleMode)
                                markGifSuccess(mediaId)
                            } else {
                                onGifFailed(mediaId)
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    // Engine destroyed while the GIF was decoding: close the
                    // decoded drawable so it is not leaked (its posted task is
                    // dropped by removeCallbacksAndMessages in onDestroy).
                    try {
                        (decodedDrawable as? java.lang.AutoCloseable)?.close()
                    } catch (_: Exception) {}
                    throw ce
                } catch (t: Throwable) {
                    AppLog.e(TAG, "playGif failed, falling back to static frame", t)
                    // Baked: this frame is drawn by the GIF path, not the quad.
                    val bmp = loadBakedBitmap(uriStr)
                    mainHandler.post {
                        if (pendingGifUri != uriStr || !surfaceReady) {
                            if (bmp != null && !bmp.isRecycled) bmp.recycle()
                            return@post
                        }
                        pendingGifUri = null
                        if (bmp != null) {
                            renderer?.showImage(bmp, scaleMode)
                            markGifSuccess(mediaId)
                        } else {
                            onGifFailed(mediaId)
                        }
                    }
                }
            }
        }

        /**
         * A GIF failed to decode (both the animated drawable and the static
         * fallback). Mirrors the IMAGE failure path: blocklist the id and
         * schedule a recovery switch so a broken GIF never leaves the wallpaper
         * blank until the next timer tick.
         */
        private fun onGifFailed(mediaId: Long) {
            if (mediaId <= 0L) return
            failedMediaIds.add(mediaId)
            if (lastDisplayedId == mediaId) lastDisplayedId = 0L
            recoveryFailCount++
            // Same permanent-failure cleanup as the image path: a GIF whose file
            // is gone must not be retried on every future timer tick.
            dropMediaIfGone(mediaId)
            if (recoveryFailCount <= 5) {
                AppLog.w(TAG, "GIF failed to load (id=$mediaId), scheduling recovery switch")
                requestSwitch("recovery")
            }
        }

        /**
         * A GIF actually started rendering (animated or static fallback).
         * Un-blocklist the id and reset the recovery counter so auto-recovery
         * can kick in again for genuinely broken files.
         */
        private fun markGifSuccess(mediaId: Long) {
            if (mediaId <= 0L) return
            failedMediaIds.remove(mediaId)
            recoveryFailCount = 0
        }

        /**
         * Watchdog for GIF playback: some devices (reported on vivo/OriginOS)
         * decode the animation but never present a frame, leaving a black
         * wallpaper. If nothing reached the screen within 8s, show the file's
         * first frame as a static image so the wallpaper is never black.
         */
        private fun startGifHealthMonitor(mediaId: Long, uriStr: String) {
            gifHealthJob?.cancel()
            gifHealthJob = scope.launch {
                // Judge "no frame" only while the wallpaper is actually visible:
                // a screen-off freeze is not a GIF failure (same reasoning as the
                // video watchdog).
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                var waitedForScreen = 0L
                while (waitedForScreen < GIF_HEALTH_WAIT_MS &&
                    (pm?.isInteractive != true || renderer?.powerSaveMode == true)
                ) {
                    delay(GIF_HEALTH_POLL_MS)
                    waitedForScreen += GIF_HEALTH_POLL_MS
                }
                // Still hidden when the wait ran out (screen off, another app in
                // front, the system live-wallpaper dialog): do NOT decode a
                // full-screen first frame and upload it. That was the one code
                // path that kept doing heavy work while nobody could see the
                // wallpaper - against this file's own "no decode while hidden"
                // rule - and showImage() has no power-save guard of its own.
                // A later visible start goes through a fresh watchdog.
                if (pm?.isInteractive != true || renderer?.powerSaveMode == true) {
                    AppLog.d(
                        TAG,
                        "GIF health check skipped: wallpaper still not visible after " +
                            "${waitedForScreen / 1000}s"
                    )
                    return@launch
                }
                delay(8_000L)
                if (engineDestroyed) return@launch
                if (lastDisplayedId != mediaId || gifFirstFrameLogged) return@launch
                AppLog.w(TAG, "GIF produced no frame for 8s; falling back to first frame")
                // Baked: this frame is drawn by the GIF path, not the quad.
                val bmp = loadBakedBitmap(uriStr)
                if (bmp != null) {
                    mainHandler.post {
                        if (engineDestroyed || lastDisplayedId != mediaId) {
                            if (!bmp.isRecycled) bmp.recycle()
                            return@post
                        }
                        val old = currentBitmap
                        currentBitmap = bmp
                        if (old != null && old !== bmp && !old.isRecycled) old.recycle()
                        renderer?.showImage(bmp, currentScaleMode)
                        AppLog.d(TAG, "GIF fallback frame shown: ${bmp.width}x${bmp.height}")
                    }
                }
            }
        }

        @android.annotation.TargetApi(28)
        private fun playGif28(
            drawable: android.graphics.drawable.Drawable,
            scaleMode: ScaleMode,
            mediaId: Long,
            frameDelaysMs: IntArray?
        ) {
            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                drawable.repeatCount = -1
                drawable.start()

                // Render into a buffer at most the screen size: a large GIF
                // would otherwise upload its full intrinsic resolution to the
                // GPU ~30 times per second, a major power drain. The wallpaper
                // displays at screen resolution anyway, so the visible quality
                // is identical to the original file.
                val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
                val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)
                val screenMax = maxOf(getMetrics().widthPixels, getMetrics().heightPixels)
                // FIT never enlarges; FILL/STRETCH magnify the GIF, so keep a
                // higher buffer ceiling in those modes to avoid softening large
                // GIFs when they are upscaled.
                val gifCap = when (scaleMode) {
                    ScaleMode.FILL, ScaleMode.STRETCH ->
                        minOf(screenMax * 2, 4096).coerceAtLeast(2560)
                    else -> screenMax
                }
                val gifScale = if (maxOf(intrinsicW, intrinsicH) > gifCap) {
                    gifCap.toFloat() / maxOf(intrinsicW, intrinsicH)
                } else {
                    1f
                }
                var frameW = (intrinsicW * gifScale).toInt().coerceAtLeast(1)
                var frameH = (intrinsicH * gifScale).toInt().coerceAtLeast(1)
                // FILL/STRETCH rotates media whose orientation mismatches the
                // screen (same rule as static images) so more of the GIF shows
                // instead of a thin perpendicular strip.
                val rotateForScreen = shouldRotateMediaForScreen(intrinsicW, intrinsicH)
                // Hard bound on the two ping-pong ARGB buffers: the 4096 cap in
                // FILL/STRETCH would otherwise allocate ~128MB of buffers on
                // top of the drawable's own decoded frames, which can OOM on
                // tablets/low-RAM devices. Scale the FRAME (aspect preserved)
                // when the monster-GIF budget would be exceeded.
                val maxFramePixels = (96L * 1024 * 1024) / 2 / 4
                if (frameW.toLong() * frameH > maxFramePixels) {
                    val shrink = kotlin.math.sqrt(
                        maxFramePixels.toDouble() / (frameW.toDouble() * frameH)
                    )
                    frameW = (frameW * shrink).toInt().coerceAtLeast(1)
                    frameH = (frameH * shrink).toInt().coerceAtLeast(1)
                }
                val frameDrawScale = frameW.toFloat() / intrinsicW
                val bufferW = if (rotateForScreen) frameH else frameW
                val bufferH = if (rotateForScreen) frameW else frameH
                // Hand the drawable + buffer sizes to the GIF thread: it stops
                // the previous GIF (if any), owns the ping-pong buffers and
                // runs the frame ticker OFF the main thread. FIFO ordering on
                // the GIF handler guarantees the pauseGif() stop posted before
                // this runs first, and the start() above is a one-time kick.
                ensureGifThread()
                gifHandler?.post {
                    startGifTicker(
                        drawable, bufferW, bufferH, frameDrawScale, mediaId, rotateForScreen,
                        frameDelaysMs
                    )
                }
                // The animated drawable started: the GIF is healthy.
                markGifSuccess(mediaId)
            } else {
                // Decoder returned a non-animated drawable (e.g. single-frame GIF):
                // render its first frame so the screen is never left blank.
                //
                // Runs on the GIF thread, not on the caller's (main) thread: this
                // path allocates a screen-size ARGB bitmap, rasterizes the frame
                // into it and may rotate it (one more full-size copy). The
                // animated branch above deliberately moved the same kind of work
                // off the main thread - the wallpaper engine shares the app's main
                // looper, so a stutter here is visible while the app is open.
                val mode = scaleMode
                val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
                val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)
                ensureGifThread()
                val posted = gifHandler?.post {
                    try {
                        // Cap the fallback frame at the screen resolution: a huge
                        // single-frame GIF would otherwise allocate a full-size
                        // ARGB bitmap (the screen only displays at screen size).
                        val screenMax = maxOf(getMetrics().widthPixels, getMetrics().heightPixels)
                        val cap = minOf(screenMax, 4096).coerceAtLeast(1920)
                        val maxDim = maxOf(intrinsicW, intrinsicH)
                        val frameScale = if (maxDim > cap) cap.toFloat() / maxDim else 1f
                        val frameW = (intrinsicW * frameScale).toInt().coerceAtLeast(1)
                        val frameH = (intrinsicH * frameScale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                        val cv = Canvas(bmp)
                        if (frameScale < 1f) cv.scale(frameScale, frameScale)
                        drawable.draw(cv)
                        val displayed = if (shouldRotateMediaForScreen(intrinsicW, intrinsicH)) {
                            rotateBitmap90(bmp, autoRotateClockwise)
                        } else {
                            bmp
                        }
                        if (engineDestroyed) {
                            // The engine went away while this task was queued:
                            // release the frame instead of holding a screen-size
                            // bitmap for a wallpaper that no longer exists.
                            if (!displayed.isRecycled) displayed.recycle()
                        } else {
                            renderer?.showImage(displayed, mode)
                            markGifSuccess(mediaId)
                        }
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "GIF static frame failed", t)
                        mainHandler.post { onGifFailed(mediaId) }
                    } finally {
                        try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                    }
                }
                // The GIF thread is quitting (engine teardown) - post() returned
                // false, so nothing above will run: release the drawable here
                // instead of leaking its decoded frames.
                if (posted != true) {
                    try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                }
            }
        }

        /**
         * Lazily create the GIF render thread. Runs on the caller's thread
         * (main) only the first time a GIF is actually played.
         */
        private fun ensureGifThread() {
            if (gifHandler == null) {
                val t = HandlerThread("GifRender")
                t.start()
                gifThread = t
                gifHandler = Handler(t.looper)
            }
        }

        /**
         * Runs on the GIF thread: replace the current GIF with [drawable] and
         * start its frame ticker. Buffer allocation, frame rasterization and
         * buffer swaps all stay on this thread; only the GL upload crosses to
         * the render thread via showGifFrame().
         *
         * Only reachable from [playGif28] (AnimatedImageDrawable exists from
         * API 28 on): the annotation states that for lint, which otherwise flags
         * the start()/stop() calls below against minSdk 26.
         */
        @android.annotation.TargetApi(28)
        private fun startGifTicker(
            drawable: android.graphics.drawable.AnimatedImageDrawable,
            frameW: Int,
            frameH: Int,
            drawScale: Float,
            mediaId: Long,
            rotateForScreen: Boolean,
            frameDelaysMs: IntArray?
        ) {
            stopAndCloseGifDrawable()
            gifDrawable = drawable
            gifFirstFrameLogged = false
            try {
                gifBitmapBuffer?.recycle()
                gifBitmapBufferAlt?.recycle()
                gifBitmapBuffer = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                gifBitmapBufferAlt = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                // Buffer allocation failed (OOM on low-RAM devices despite the
                // pixel cap). Never crash the wallpaper process: close the
                // drawable and let the engine recover with a different media.
                AppLog.e(TAG, "GIF buffer allocation failed (${frameW}x$frameH)", t)
                stopAndCloseGifDrawable()
                gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
                gifBitmapBufferAlt?.recycle(); gifBitmapBufferAlt = null
                mainHandler.post { onGifFailed(mediaId) }
                return
            }

            val r = renderer
            // Opaque GIFs overwrite every pixel of the buffer, so the
            // full-screen erase (a ~10MB clear at 1080p, 20x/sec) is skipped.
            val opaque = drawable.opacity == PixelFormat.OPAQUE
            // True while the animation is frozen because the wallpaper is not
            // visible (see below).
            var pausedForVisibility = false
            // Position in the GIF's own frame-delay list (see [GifTiming]).
            // The drawable advances one frame per draw once the elapsed time
            // reaches the frame's duration, so ticking on that duration is what
            // keeps one rasterize + upload per frame instead of the fixed 20fps
            // sampling that re-drew and re-uploaded every frame of a slower GIF.
            var frameIndex = 0
            // Consecutive rasterization failures (see GIF_FRAME_FAILURE_LIMIT).
            // Reset by every frame that is drawn, so a one-off glitch does not
            // count towards the limit.
            var consecutiveFrameFailures = 0
            val runnable = object : Runnable {
                override fun run() {
                    // A newer ticker superseded this one (startGifTicker
                    // replaced gifFrameRunnable, or pauseGif nulled it): stop
                    // this chain instead of drawing with a stale drawable.
                    if (gifFrameRunnable !== this) return
                    if (gifDrawable == null) return
                    // Not visible (screen off / another app in front / the
                    // system live-wallpaper dialog): pause the animation
                    // COMPLETELY, exactly like video does. stop() freezes it on
                    // the current frame and consumes nothing; start() resumes
                    // from that frame, so the animation neither advances
                    // unseen nor jumps when the desktop comes back.
                    if (renderer?.powerSaveMode == true) {
                        if (!pausedForVisibility) {
                            pausedForVisibility = true
                            try {
                                gifDrawable?.stop()
                            } catch (_: Exception) {
                            }
                            // One line per hidden episode (the timer may swap in
                            // a new GIF while it is hidden).
                            if (!gifPauseAnnounced) {
                                gifPauseAnnounced = true
                                AppLog.d(TAG, "GIF paused (wallpaper not visible)")
                            }
                        }
                        // Safety-net poll only: a resume nudges the
                        // ticker directly on resume (see nudgeGifTicker), so this
                        // can be rare instead of once per second while hidden.
                        gifHandler?.postDelayed(this, GIF_PAUSED_POLL_MS)
                        return
                    }
                    if (pausedForVisibility) {
                        pausedForVisibility = false
                        try {
                            gifDrawable?.start()
                        } catch (_: Exception) {
                        }
                        if (gifPauseAnnounced) {
                            AppLog.d(TAG, "GIF resumed (wallpaper visible again)")
                        }
                    }
                    // Keep ticking even when the surface is temporarily
                    // unavailable, so the animation resumes automatically
                    // once the surface comes back (previously the runnable
                    // returned without rescheduling and the GIF froze).
                    if (surfaceReady) {
                        try {
                            val bmp = gifBitmapBuffer ?: return
                            if (!opaque) bmp.eraseColor(Color.TRANSPARENT)
                            val cv = Canvas(bmp)
                            if (rotateForScreen) {
                                // Draw the source rotated 90° into the
                                // portrait/landscape buffer so FILL crops the
                                // short axis instead of a thin strip.
                                cv.save()
                                cv.translate(frameW / 2f, frameH / 2f)
                                cv.rotate(if (autoRotateClockwise) 90f else -90f)
                                cv.scale(drawScale, drawScale)
                                cv.translate(
                                    -drawable.intrinsicWidth.coerceAtLeast(1) / 2f,
                                    -drawable.intrinsicHeight.coerceAtLeast(1) / 2f
                                )
                                drawable.draw(cv)
                                cv.restore()
                            } else {
                                if (drawScale < 1f) {
                                    cv.scale(drawScale, drawScale)
                                }
                                drawable.draw(cv)
                            }
                            // Read the CURRENT scale mode every frame so a live
                            // Settings change re-fits the animation without a
                            // restart (currentScaleMode is volatile).
                            r?.showGifFrame(bmp, currentScaleMode)
                            // Visible playback again: the next hidden episode
                            // announces itself.
                            gifPauseAnnounced = false
                            if (!gifFirstFrameLogged) {
                                gifFirstFrameLogged = true
                                AppLog.d(TAG, "GIF frame presented: ${bmp.width}x${bmp.height} mode=$currentScaleMode")
                            }
                            // Swap buffers for the next frame: the render
                            // thread may still be uploading the frame we
                            // just posted, so never redraw into it.
                            val tmp = gifBitmapBuffer
                            gifBitmapBuffer = gifBitmapBufferAlt
                            gifBitmapBufferAlt = tmp
                            // This tick presented a frame: step to the delay of
                            // the frame that is due next.
                            frameIndex++
                            consecutiveFrameFailures = 0
                        } catch (t: Throwable) {
                            // Bounded: a drawable that throws on every frame used
                            // to keep this ticker running forever at the file's
                            // frame rate (20 stack traces + 20 wakeups per second,
                            // each log line flushed to disk). Give up instead and
                            // let the engine recover with another media, exactly
                            // like the load-failure path does.
                            consecutiveFrameFailures++
                            AppLog.e(
                                TAG,
                                "GIF frame draw failed " +
                                    "($consecutiveFrameFailures/$GIF_FRAME_FAILURE_LIMIT)",
                                t
                            )
                            if (consecutiveFrameFailures >= GIF_FRAME_FAILURE_LIMIT) {
                                gifFrameRunnable = null
                                stopAndCloseGifDrawable()
                                mainHandler.post { onGifFailed(mediaId) }
                                return
                            }
                        }
                    }
                    gifHandler?.postDelayed(this, gifFrameStepMs(frameDelaysMs, frameIndex))
                }
            }
            gifFrameRunnable = runnable
            gifHandler?.post(runnable)
        }

        /**
         * Ask the content provider for the item's real MIME type and repair the
         * stored media type when the two disagree (a video stored as IMAGE -
         * the "video shows black" bug caused by SAF's extension-less display
         * names). Returns true when the row was repaired, so the caller can
         * redraw the now-correctly-typed media.
         */
        private suspend fun repairMisTypedMedia(image: com.wallpaperswitcher.data.WallpaperImage): Boolean {
            val mime = com.wallpaperswitcher.engine.MediaTypes.mimeOf(
                applicationContext, android.net.Uri.parse(image.uri)
            )
            // null when the row is already motion media or the provider has
            // nothing better to say (see MediaTypes.repairFromMime).
            val repaired = MediaTypes.repairFromMime(image.mediaType, mime) ?: return false
            return try {
                db.wallpaperImageDao().updateMediaType(image.id, repaired)
                AppLog.w(
                    TAG,
                    "Repaired mis-typed media ${image.displayName}: ${image.mediaType} -> $repaired"
                )
                true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Delete a media row whose file is permanently gone (see
         * [MediaProbe.isGone]) and rewind the home cursor when it pointed at
         * that row, so neither the timer nor a later session can pick a dead
         * entry again. Runs on the engine's IO scope: it is called from load
         * failures, never on the main thread.
         */
        private fun dropMediaIfGone(mediaId: Long) {
            if (mediaId <= 0L) return
            scope.launch {
                try {
                    // Shared with the static applier and the UI delete paths, so
                    // all of them forget the same cursors (see dropGoneMedia).
                    val image = db.wallpaperImageDao().getImageById(mediaId) ?: return@launch
                    dropGoneMedia(applicationContext, image, TAG)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "Failed to drop unreadable media id=$mediaId", t)
                }
            }
        }

        /**
         * True when the pixels already decoded into [bmp] are enough to show the
         * media on the current [sw]x[sh] surface: the screen never has to
         * magnify them. The orientation rule is applied through the same quad the
         * renderer will use, so FILL/FIT/STRETCH are all covered.
         *
         * Used by the rotation redraw: when this holds, the bitmap decoded for
         * the previous orientation can simply be re-presented (see
         * refreshImageQuad) instead of decoding the media again - which is what
         * made setting a wallpaper stutter on a landscape tablet (MIUI rotates
         * the display for its portrait-only picker, so the surface turns twice
         * per setting flow and each turn re-decoded a display-sized photo).
         */
        private fun bitmapFillsScreen(bmp: android.graphics.Bitmap, sw: Int, sh: Int): Boolean {
            if (bmp.width <= 0 || bmp.height <= 0 || sw <= 0 || sh <= 0) return false
            val rotateCw = BitmapUtils.fillRotationFor(
                bmp, sw, sh, autoRotateMismatch, autoRotateClockwise
            )
            // The axis bookkeeping (a 90° turn feeds the screen's width from the
            // bitmap's HEIGHT) lives in the pure helper so it is unit-tested -
            // getting it wrong silently re-decoded every rotated photo.
            return WallpaperGeometry.bitmapCoversQuad(
                wd = bmp.width,
                ht = bmp.height,
                screenW = sw,
                screenH = sh,
                scaleMode = currentScaleMode,
                rotateCw = rotateCw
            )
        }

        /**
         * True when the orientation rule (see [BitmapUtils.quarterTurnFor])
         * wants this media turned 90° first because its orientation mismatches
         * the wallpaper surface. Applies to every scale mode now: FIT included,
         * where the turned media is also displayed larger (a mismatched
         * orientation is limited by the screen's short side when fitted).
         */
        private fun shouldRotateMediaForScreen(
            srcW: Int,
            srcH: Int
        ): Boolean {
            var sw = cachedScreenW.toInt()
            var sh = cachedScreenH.toInt()
            if (sw <= 0 || sh <= 0) {
                val metrics = getMetrics()
                sw = metrics.widthPixels
                sh = metrics.heightPixels
            }
            if (sw <= 0 || sh <= 0) return false
            return BitmapUtils.wantsQuarterTurn(
                srcW, srcH, sw, sh, autoRotateMismatch
            )
        }

        private fun rotateBitmap90(bmp: Bitmap, clockwise: Boolean): Bitmap {
            if (bmp.isRecycled) return bmp
            // Same implementation as the image path (BitmapUtils.bakeQuarterTurn
            // -> rotateQuarterTurn), so a GIF frame and a still image can never
            // be turned in different directions.
            return BitmapUtils.bakeQuarterTurn(bmp, clockwise)
        }

        private fun getDefaultBitmap(): Bitmap {
            val existing = defaultBitmap
            if (existing != null && !existing.isRecycled) return existing
            return createDefaultBitmap().also { defaultBitmap = it }
        }

        private fun createDefaultBitmap(): Bitmap {
            val m = getMetrics()
            // Cap the placeholder to 2048px on the long side: on large tablets
            // a full-screen-size ARGB bitmap (3040x2032 ~= 24MB) is wasteful
            // for a text placeholder and could contribute to OOM on low-heap
            // device builds. The GPU scales it up trivially.
            val maxDim = maxOf(m.widthPixels, m.heightPixels)
            val scale = if (maxDim > 2048) 2048f / maxDim else 1f
            val w = (m.widthPixels * scale).toInt().coerceAtLeast(1)
            val h = (m.heightPixels * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.DKGRAY)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Wallpaper Switcher", w / 2f, h / 2f, p)
            return bmp
        }

        /**
         * Enabled-media count for [slot], cached for [SLOT_COUNT_CACHE_MS].
         * See [slotCountCacheAt] for why a short-TTL cache is safe here.
         */
        private suspend fun enabledCountCached(imageDao: WallpaperImageDao, slot: String): Int {
            val index = if (slot == HOME_SLOT) 0 else 1
            val now = SystemClock.elapsedRealtime()
            if (now - slotCountCacheAt[index] <= SLOT_COUNT_CACHE_MS) {
                return slotCountCacheValue[index]
            }
            val count = imageDao.countByEnabledGroups(slot)
            slotCountCacheValue[index] = count
            slotCountCacheAt[index] = now
            return count
        }

        /**
         * Decode [media]. [media] is null only on the rare fallback paths
         * (GIF first-frame / health fallback) where the row is not at hand; the
         * stored decode metadata then comes from the in-memory cache instead.
         */
        private fun loadBitmap(
            uriStr: String,
            media: com.wallpaperswitcher.data.WallpaperImage? = null
        ): com.wallpaperswitcher.engine.BitmapUtils.EngineImage? {
            return com.wallpaperswitcher.engine.BitmapUtils.loadBitmapForEngine(
                applicationContext, uriStr, currentScaleMode,
                null, null, autoRotateMismatch, autoRotateClockwise,
                media?.width ?: 0, media?.height ?: 0, media?.rotationDegrees ?: 0
            )
        }

        /**
         * Load a bitmap with the "orientation mismatch" quarter turn already
         * baked into the pixels. Only the GIF fallback paths need this: they hand
         * the bitmap to their own frame ticker instead of the quad renderer, which
         * is where the engine takes the GPU rotation.
         */
        private fun loadBakedBitmap(uriStr: String): Bitmap? {
            val loaded = loadBitmap(uriStr) ?: return null
            return loaded.rotateCw?.let {
                com.wallpaperswitcher.engine.BitmapUtils.bakeQuarterTurn(loaded.bitmap, it)
            } ?: loaded.bitmap
        }

        /**
         * Load a bitmap with a hard timeout. BitmapFactory/ContentResolver
         * calls cannot be interrupted by coroutine cancellation (a plain
         * withTimeout would keep waiting for the blocking call), so the decode
         * runs on a helper thread and the caller abandons it after [timeoutMs].
         * If the abandoned thread ever finishes, its bitmap is recycled.
         */
        private fun loadBitmapWithTimeout(
            uriStr: String,
            timeoutMs: Long = BITMAP_LOAD_TIMEOUT_MS,
            screenW: Int? = null,
            screenH: Int? = null,
            media: com.wallpaperswitcher.data.WallpaperImage? = null
        ): com.wallpaperswitcher.engine.BitmapUtils.EngineImage? {
            val result = java.util.concurrent.atomic.AtomicReference<
                com.wallpaperswitcher.engine.BitmapUtils.EngineImage?
                >(null)
            val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
            val thread = Thread({
                try {
                    val loaded = if (screenW != null && screenH != null) {
                        com.wallpaperswitcher.engine.BitmapUtils.loadBitmapForEngine(
                            applicationContext, uriStr, currentScaleMode,
                            screenW, screenH, autoRotateMismatch, autoRotateClockwise,
                            media?.width ?: 0, media?.height ?: 0, media?.rotationDegrees ?: 0
                        )
                    } else {
                        loadBitmap(uriStr, media)
                    }
                    if (abandoned.get()) {
                        loaded?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                    } else {
                        result.set(loaded)
                        // The caller may have timed out between the check above and
                        // this store; it then returns null and would never look at
                        // (or recycle) this bitmap - a whole screen-size ARGB would
                        // leak. Re-check and take it back in that case.
                        if (abandoned.get()) {
                            val stored = result.getAndSet(null)
                            stored?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                        }
                    }
                } catch (_: Throwable) {}
            }, "BitmapLoad").apply {
                // Never keep the process alive because a provider is stuck.
                isDaemon = true
                start()
            }
            try {
                thread.join(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                abandoned.set(true)
                thread.interrupt()
                return null
            }
            val bmp = result.get()
            if (bmp != null && bmp.bitmap.isRecycled) return null
            return bmp
        }

        /**
         * Decode an animated GIF drawable with a hard timeout, mirroring
         * [loadBitmapWithTimeout]: ImageDecoder.decodeDrawable cannot be
         * interrupted by coroutine cancellation, so the decode runs on a
         * helper thread and the caller abandons it after [timeoutMs]. If the
         * abandoned thread ever finishes, its drawable is closed. Returns null
         * on timeout / failure / API < 28 (the caller falls back to recovery).
         */
        private fun decodeGifDrawableWithTimeout(
            uriStr: String,
            timeoutMs: Long = BITMAP_LOAD_TIMEOUT_MS
        ): android.graphics.drawable.Drawable? {
            if (Build.VERSION.SDK_INT < 28) return null
            val result = java.util.concurrent.atomic.AtomicReference<android.graphics.drawable.Drawable?>(null)
            val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
            val thread = Thread({
                try {
                    val source = android.graphics.ImageDecoder.createSource(contentResolver, Uri.parse(uriStr))
                    val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    if (abandoned.get()) {
                        try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                    } else {
                        result.set(drawable)
                        if (abandoned.get()) {
                            val stored = result.getAndSet(null)
                            try { (stored as? java.lang.AutoCloseable)?.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Throwable) {}
            }, "GifDecode").apply {
                // Never keep the process alive because a provider is stuck.
                isDaemon = true
                start()
            }
            try {
                thread.join(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                abandoned.set(true)
                thread.interrupt()
                return null
            }
            return result.get()
        }

        private fun getMetrics(): android.util.DisplayMetrics {
            return com.wallpaperswitcher.engine.BitmapUtils.getScreenMetrics(applicationContext)
        }
    }
}
