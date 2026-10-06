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
import com.wallpaperswitcher.engine.ClarityMode
import com.wallpaperswitcher.engine.EnhanceMode
import com.wallpaperswitcher.engine.GroupPick
import com.wallpaperswitcher.engine.MediaPick
import com.wallpaperswitcher.engine.MediaScanner
import com.wallpaperswitcher.engine.MediaProbe
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.ScreenPowerPolicy
import com.wallpaperswitcher.engine.dropGoneMedia
import com.wallpaperswitcher.engine.SwitchPicking
import com.wallpaperswitcher.engine.WallpaperApplier
import com.wallpaperswitcher.engine.WallpaperGeometry
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class SwitchRequest(
    val source: String,
    val targetId: Long? = null,
    /**
     * Non-zero when the tick belongs to one group with its own switch rhythm
     * (see GroupPacing): the engine then picks the next media of THAT group
     * instead of the whole screen.
     */
    val groupId: Long = 0L,
)


class LiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "LiveWallpaperService"
        // The live wallpaper surface IS the home screen, so the engine picks
        // only from groups whose 应用位置 includes 桌面 (HOME / BOTH). Groups
        // reserved for the lock screen are applied there by the static path.
        private val HOME_SLOT = com.wallpaperswitcher.engine.WallpaperTarget.SLOT_HOME
        const val ACTION_SWITCH = "com.wallpaperswitcher.ACTION_SWITCH"
        const val EXTRA_TARGET_ID = "target_id"
        /** The group a per-group timer tick belongs to (0 = the whole screen). */
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_SOURCE = "switch_source"
        const val SOURCE_TIMER = "timer"
        const val SOURCE_UNLOCK = "unlock"
        /** 视频播完再切: the held timed switch, run when the clip ends. */
        const val SOURCE_VIDEO_END = "video-end"
        /** 顺序=新的在前 (see WallpaperGroup.sortOrder). */
        private const val SORT_NEWEST = "NEWEST"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_DOUBLE_TAP = "double-tap"
        /** User tapped 下一张 on the foreground notification. */
        const val SOURCE_NOTIFICATION = "notification"
        /** User tapped the floating switch button (see FloatingSwitchButton). */
        const val SOURCE_FLOATING = "floating-tap"
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
         * How long the LOCK screen may cover the wallpaper before its
         * decoder/drawable is released instead of parked.
         *
         * Only the lock screen releases (see [scheduleMediaReleaseWhileLocked]):
         * a wallpaper that is merely covered by another app keeps its parked
         * decoder, so returning to the desktop resumes from the same frame.
         * While locked nobody sees the restart that releasing it causes.
         */
        private const val MEDIA_RELEASE_AFTER_SCREEN_OFF_MS = 10_000L
        // How many alternative media a switch tries when the picked one is on
        // the "recently failed to start" blocklist.

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
        fun requestSwitchFromOutside(source: String, groupId: Long = 0L): Boolean {
            return activeEngine?.requestSwitchFromOutside(source, groupId) ?: false
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
         * 悬浮按钮「长按预览」确认：把**用户刚刚预览到的那一张**交给引擎。
         *
         * 为什么不复用 [requestSwitchFromOutside]：那是一条普通的"再挑一次"
         * （屏幕级或按分组节奏），而预览是 [com.wallpaperswitcher.engine.NextPreview]
         * 按"下一次该轮到谁"算出来的 —— 只要有任何分组带自己的间隔，两条路径
         * 就是不同的池子，于是用户看到"预览 A、切过去 B"。这里按 id 精确应用，
         * 预览看到什么就得到什么。
         *
         * 返回 false 表示引擎没接（静态模式 / 进程刚起）：调用方不该假装成功。
         */
        internal fun applyPreviewedMedia(mediaId: Long): Boolean =
            pushConfirmedPickToEngine(mediaId)

        /**
         * Re-evaluate the floating button immediately (e.g. after the user
         * returns from granting the overlay permission) instead of waiting for
         * the next visibility change.
         */
        fun refreshFloatingButtonIfAny() {
            activeEngine?.refreshFloatingButtonNow()
        }
        /**
         * 过渡动画 setting changed: adopt it in the running engine so the next
         * switch already uses it (the engine also reads the setting per switch,
         * so a killed/recreated engine picks it up too).
         */
        fun applyTransitionFromSettings(context: Context, mode: String) {
            activeEngine?.applyTransitionMode(mode)
        }
        /**
         * 静态图微动效 setting changed: adopt it in the running engine (the
         * engine also reads the setting before every switch, so a
         * killed/recreated engine picks it up too).
         */
        fun applyKenBurnsFromSettings(context: Context, enabled: Boolean) {
            activeEngine?.applyKenBurnsEnabled(enabled)
        }

        /** 放大算法（FSR1 / Anime4K）开关变化：立刻应用并重绘当前静态图。 */
        fun applyEnhanceModeFromSettings(context: Context, mode: Int) {
            activeEngine?.applyEnhanceMode(mode)
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
        /**
         * Bitmap decode / orientation / placeholder helpers live in their own
         * loader; the engine supplies the current scale + auto-rotate state.
         */
        private val bitmapLoader = MediaBitmapLoader(
            context = applicationContext,
            host = object : MediaBitmapLoader.Host {
                override fun metrics(): android.util.DisplayMetrics =
                    this@LiveWallpaperEngine.getMetrics()
                override fun scaleMode(): ScaleMode =
                    this@LiveWallpaperEngine.currentScaleMode
                override fun autoRotateMismatch(): Boolean =
                    this@LiveWallpaperEngine.autoRotateMismatch
                override fun autoRotateClockwise(): Boolean =
                    this@LiveWallpaperEngine.autoRotateClockwise
                override fun cachedScreenSize(): Pair<Int, Int> =
                    cachedScreenW.toInt() to cachedScreenH.toInt()
            },
        )
        /**
         * The video session (playback start/stop, stall watchdog, resume
         * position after a lock release) lives in its own controller; the
         * engine supplies the surface/renderer and handles failures.
         */
        private val video = VideoSessionController(
            context = applicationContext,
            scope = scope,
            tag = TAG,
            host = object : VideoSessionController.Host {
                override fun surfaceReady(): Boolean =
                    this@LiveWallpaperEngine.surfaceReady
                override fun renderer(): WallpaperRenderer? =
                    this@LiveWallpaperEngine.renderer
                override fun onVideoStartFailed() =
                    this@LiveWallpaperEngine.recovery.onVideoStartFailed()
                override fun recoveryFailCount(): Int =
                    this@LiveWallpaperEngine.recovery.failCount()
                override fun setRecoveryFailCount(value: Int) {
                    // 看门狗只会在"健康播放"时把它清零（见 VideoSessionController）。
                    if (value == 0) this@LiveWallpaperEngine.recovery.resetFailCount()
                }
                override fun videoGeneration(): Int =
                    this@LiveWallpaperEngine.renderer?.videoGeneration?.get() ?: -1
            },
        )
        /**
         * 媒体失败恢复（坏视频/坏 GIF/解不开的图片 + 失效清理 + 类型修复）见
         * [MediaFailureRecovery]；引擎只提供表面/渲染器与恢复切换的入口。
         */
        private lateinit var recovery: MediaFailureRecovery
        private fun createRecovery() = MediaFailureRecovery(
            context = applicationContext,
            db = db,
            scope = scope,
            tag = TAG,
            host = object : MediaFailureRecovery.Host {
                override fun lastDisplayedId(): Long =
                    this@LiveWallpaperEngine.lastDisplayedId
                override fun setLastDisplayedId(id: Long) {
                    this@LiveWallpaperEngine.lastDisplayedId = id
                }
                override fun engineDestroyed(): Boolean =
                    this@LiveWallpaperEngine.engineDestroyed
                override fun isVisible(): Boolean = this@LiveWallpaperEngine.isVisible
                override fun surfaceReady(): Boolean =
                    this@LiveWallpaperEngine.surfaceReady
                override fun switchInProgress(): Boolean =
                    this@LiveWallpaperEngine.switchInProgress
                override fun renderer(): WallpaperRenderer? =
                    this@LiveWallpaperEngine.renderer
                override fun currentScaleMode(): ScaleMode =
                    this@LiveWallpaperEngine.currentScaleMode
                override fun mainHandler(): Handler = this@LiveWallpaperEngine.mainHandler
                override fun releaseRetiredBitmap() =
                    this@LiveWallpaperEngine.releaseRetiredBitmap()
                override fun presentStillFrame(frame: Bitmap, mediaId: Long) {
                    val old = this@LiveWallpaperEngine.currentBitmap
                    this@LiveWallpaperEngine.currentBitmap = frame
                    if (old != null && old !== frame && !old.isRecycled) old.recycle()
                    this@LiveWallpaperEngine.renderer?.showImage(frame, currentScaleMode)
                    this@LiveWallpaperEngine.lastDisplayedId = mediaId
                }
                override fun loadFirstFrame(uri: String, positionUs: Long): Bitmap? =
                    this@LiveWallpaperEngine.videoFirstFrame(uri, positionUs)
                override fun requestRecoverySwitch(groupId: Long) {
                    this@LiveWallpaperEngine.switchQueue.request("recovery", null, groupId)
                }
            },
        )
        /**
         * 「预解码下一张」的决策见 [PrefetchController]；缓存 [prefetch] 仍由引擎
         * 共享持有（切换路径要 take/clear）。
         */
        private lateinit var prefetcher: PrefetchController
        /**
         * 一次切换的完整流程见 [SwitchExecutor]；引擎只暴露渲染状态与
         * 清图/退位/淡入三个动作。
         */
        private lateinit var switchExec: SwitchExecutor
        /**
         * GIF playback lives in its own controller (render thread, ping-pong
         * buffers, health watchdog); the engine only supplies state callbacks.
         */
        private val gif = GifPlaybackController(
            context = applicationContext,
            scope = scope,
            mainHandler = mainHandler,
            tag = TAG,
            decodeTimeoutMs = MediaBitmapLoader.BITMAP_LOAD_TIMEOUT_MS,
            host = object : GifPlaybackController.Host {
                override fun surfaceReady(): Boolean = this@LiveWallpaperEngine.surfaceReady
                override fun engineDestroyed(): Boolean = this@LiveWallpaperEngine.engineDestroyed
                override fun powerSaveMode(): Boolean = renderer?.powerSaveMode == true
                override fun currentScaleMode(): ScaleMode = this@LiveWallpaperEngine.currentScaleMode
                override fun autoRotateClockwise(): Boolean = this@LiveWallpaperEngine.autoRotateClockwise
                override fun screenMaxPx(): Int = bitmapLoader.screenMaxPx()
                override fun shouldRotateForScreen(width: Int, height: Int): Boolean =
                    bitmapLoader.shouldRotateMediaForScreen(width, height)
                override fun rotate90(bitmap: Bitmap, clockwise: Boolean): Bitmap =
                    bitmapLoader.rotateBitmap90(bitmap, clockwise)
                override fun loadBakedBitmap(uri: String): Bitmap? =
                    bitmapLoader.loadBakedBitmap(uri)
                override fun showImage(bitmap: Bitmap, scaleMode: ScaleMode) {
                    renderer?.showImage(bitmap, scaleMode)
                }
                override fun showGifFrame(bitmap: Bitmap, scaleMode: ScaleMode): Boolean =
                    renderer?.showGifFrame(bitmap, scaleMode) ?: false
                override fun isCurrentMedia(mediaId: Long): Boolean =
                    lastDisplayedId == mediaId
                override fun presentGifFallback(bitmap: Bitmap, mediaId: Long) {
                    if (engineDestroyed || lastDisplayedId != mediaId) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return
                    }
                    val old = currentBitmap
                    currentBitmap = bitmap
                    if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
                    renderer?.showImage(bitmap, currentScaleMode)
                    AppLog.d(TAG, "GIF fallback frame shown: ${bitmap.width}x${bitmap.height}")
                }
                override fun onGifSuccess(mediaId: Long) = recovery.markSuccess(mediaId)
                override fun onGifFailed(mediaId: Long) =
                    this@LiveWallpaperEngine.recovery.onGifFailed(mediaId)
            },
        )
        /**
         * The launcher-independent floating double-tap button lives in its own
         * controller; the engine supplies visibility/foreground state and
         * settings readers.
         */
        private val floating = FloatingButtonController(
            context = applicationContext,
            scope = scope,
            mainHandler = mainHandler,
            host = object : FloatingButtonController.Host {
                override fun isPreview(): Boolean = this@LiveWallpaperEngine.isPreview
                override fun engineDestroyed(): Boolean = this@LiveWallpaperEngine.engineDestroyed
                override fun wallpaperVisible(): Boolean = this@LiveWallpaperEngine.isVisible
                override fun appInForeground(): Boolean = this@LiveWallpaperEngine.appInForeground
                override fun onWindowChanged() = noteFloatingWindowChange()
                override suspend fun buttonEnabled(): Boolean =
                    db.settingsDao().getBool(SettingsKeys.FLOATING_BUTTON_ENABLED, false)
                override suspend fun canDrawOverlays(): Boolean =
                    Settings.canDrawOverlays(applicationContext)
                override suspend fun colorHex(): String = db.settingsDao().getString(
                    SettingsKeys.FLOATING_BUTTON_COLOR,
                    SettingsKeys.FLOATING_BUTTON_COLOR_DEFAULT
                )
                override suspend fun alphaValue(): Long = db.settingsDao().getLong(
                    SettingsKeys.FLOATING_BUTTON_ALPHA,
                    SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT.toLong()
                )
                override suspend fun buttonText(): String = db.settingsDao().getString(
                    SettingsKeys.FLOATING_BUTTON_TEXT,
                    SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT
                )
                override suspend fun buttonImageUri(): String =
                    db.settingsDao().getString(SettingsKeys.FLOATING_BUTTON_IMAGE_URI, "")
            },
        )
        /** Touch / double-tap handling (see [TouchGestureController]). */
        private val touch = TouchGestureController(
            tag = TAG,
            scope = scope,
            density = applicationContext.resources.displayMetrics.density,
            host = object : TouchGestureController.Host {
                override suspend fun doubleTapEnabled(): Boolean =
                    db.settingsDao().getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                override fun requestSwitch() =
                    this@LiveWallpaperEngine.switchQueue.request(SOURCE_DOUBLE_TAP)
            },
        )
        /** Power-save / visibility state (see [PowerSaveController]). */
        private val power = PowerSaveController(
            context = applicationContext,
            mainHandler = mainHandler,
            tag = TAG,
            host = object : PowerSaveController.Host {
                override fun engineDestroyed(): Boolean = this@LiveWallpaperEngine.engineDestroyed
                override fun isPreview(): Boolean = this@LiveWallpaperEngine.isPreview
                override fun appInForeground(): Boolean = this@LiveWallpaperEngine.appInForeground
                override fun rendererPowerSaveMode(): Boolean? = renderer?.powerSaveMode
                override fun setRendererPowerSave(paused: Boolean) {
                    renderer?.powerSaveMode = paused
                }
                override fun muteAudioKeepingVideo() {
                    renderer?.muteAudioKeepingVideo()
                }
                override fun unmuteAudioReanchored() {
                    renderer?.unmuteAudioReanchored()
                }
                override fun resetVideoFrameClock() {
                    renderer?.resetVideoFrameClock()
                }
                override fun nudgeGif() = gif.nudge()
            },
        )
        /** Serialized switch queue (see [SwitchQueue]). */
        private val switchQueue = SwitchQueue(
            scope = scope,
            tag = TAG,
            host = object : SwitchQueue.Host {
                override fun isUserTapSource(source: String): Boolean =
                    this@LiveWallpaperEngine.isUserTapSource(source)
                override fun isSwitchInProgress(): Boolean = switchInProgress
                override fun switchStartedAt(): Long = switchStartedAt
                override fun markSwitchStarted() {
                    switchInProgress = true
                    switchStartedAt = SystemClock.elapsedRealtime()
                }
                override fun markSwitchDone() {
                    switchInProgress = false
                }
                override fun lastSwitchCompletedAt(): Long = lastSwitchCompletedAt
                override fun markSwitchCompleted() {
                    lastSwitchCompletedAt = SystemClock.elapsedRealtime()
                }
                override suspend fun resolveUserTapGroup(req: SwitchRequest): Long =
                    this@LiveWallpaperEngine.resolveUserTapGroup(req)
                override suspend fun execute(req: SwitchRequest, effectiveGroupId: Long) {
                    switchExec.execute(req.source, req.targetId, effectiveGroupId)
                }
                override suspend fun afterSwitch(
                    req: SwitchRequest,
                    sincePreviousSwitchMs: Long,
                    effectiveGroupId: Long,
                ) {
                    prefetcher.maybePrefetchNext(req.source, sincePreviousSwitchMs, effectiveGroupId)
                }
            },
        )
        private lateinit var db: AppDatabase
        /** 取图与 shuffle 牌堆 (see [MediaPicker]); needs [db], so it is set in onCreate. */
        private lateinit var picker: MediaPicker
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
        /**
         * 引擎是在“我们自己的 UI 已在前台”时创建的（冷启动，或选壁纸确认后系统
         * 重建引擎）。这种引擎没有经历 applyAppForeground(true)，没有可见性快照；
         * 首次退到桌面时允许一次乐观恢复，修复“设为动态壁纸后没声音、要等系统
         * 补发可见回调才恢复”的问题。
         */
        @Volatile private var resumeOnNextAppExit = false
        private val powerManager: PowerManager by lazy {
            applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        }
        // All switch triggers (timer / double-tap / unlock / manual) are sent
        // through a single serialized queue. A switch in progress never blocks
        // or drops new triggers: they wait in the queue and run in order.
        // True while a non-target (auto) switch is already queued or being
        // processed. Rapid triggers (double-tap bursts, timer ticks) coalesce
        // into ONE pending switch instead of piling up N queued requests that
        // each run their own screen-size decode — the root cause of the
        // "rapid tapping stutters after a few switches" behavior.
    /**
     * User taps (双击 / 悬浮按钮 / 立即切换) that are queued or executing. Each tap
     * gets its own switch up to [MAX_PENDING_USER_SWITCHES]; see requestSwitch.
     */
        @Volatile private var switchInProgress = false
        @Volatile private var switchStartedAt = 0L
    @Volatile private var lastSwitchCompletedAt = 0L
    /**
     * 视频播完再切: a timed switch that arrived while a video was playing is
     * held here and executed by [onVideoPassCompleted] instead of cutting the
     * clip off. Manual switches never set it - a tap must act immediately.
     */
    @Volatile private var pendingVideoEndSwitch = false
    /**
     * The group the held timed switch belongs to: when the clip finally ends,
     * the switch must continue that group's rotation instead of falling back
     * to the screen-wide media pool (which knows nothing about the group's
     * 仅图片 / 仅视频 filter).
     */
    @Volatile private var pendingVideoEndGroupId = 0L
    /**
     * Media id + position (µs) to resume at, remembered when the video session is
     * released while the device is locked (see `scheduleMediaReleaseWhileLocked`).
     * Consumed once by the next [resumePositionFor] for the SAME media; a
     * different video - or a user switch - starts at 0.
     */
        private val prefetch = PrefetchCache(mainHandler, TAG)
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

        // Unified EGL renderer — lives across surface recreations
        private var renderer: WallpaperRenderer? = null
        private var rendererInitialized = false
        /**
         * "视频壁纸播放声音" setting, as last read from the database. Kept here as
         * well as in the renderer because the renderer is created lazily on the
         * first surface event, which can happen after the settings flow emitted.
         */
        @Volatile private var videoSoundSetting = false
        @Volatile private var lastDisplayedId = 0L

        private var cachedScreenW = 0f
        private var cachedScreenH = 0f

        // Detects a decoder that stopped producing frames (e.g. a cloud file
        // whose stream read blocks forever) and triggers automatic recovery.
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
                    val groupId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
                    switchQueue.request(
                        source,
                        if (targetId > 0) targetId else null,
                        groupId.coerceAtLeast(0L)
                    )
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
                        power.refresh("screen-off")
                        // The keyguard may be showing now: re-evaluate the
                        // lock-screen switch button.
                        floating.update()
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
                        power.refresh("screen-on")
                        // Unlocked: never release what the user is about to see
                        // again (the media is restarted on visibility instead).
                        cancelMediaReleaseWhileLocked()
                        // Restart the video watchdog from now: the frozen
                        // screen-off window must never look like a stall.
                        renderer?.resetVideoFrameClock()
                        floating.update()
                    }
                }
            }
        }

        private fun noteFloatingWindowChange() {
            power.noteFloatingWindowChanged()
        }
        /** Pending "release the parked video/GIF" task (see the constant). */
        private var mediaReleaseRunnable: Runnable? = null
        // Throttle the timer self-heal to once every 30s to avoid churn.
        private var lastTimerSelfHealAt = 0L
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
                if (appInForeground) {
                    // 补记“前台之前是否可见”的快照；并允许首次 App UI hidden 时
                    // 乐观恢复（ROM 可能永远不补发 onVisibilityChanged(true)）。
                    visibleBeforeAppForeground = isVisible
                    resumeOnNextAppExit = true
                }
                power.refresh("app-foreground")
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
            // 取图 / shuffle 牌堆 (see [MediaPicker]); created here because it
            // needs the database handle that was just opened.
            picker = MediaPicker(applicationContext, db, ioScope)
            // 媒体失败恢复同样需要数据库句柄 (see [MediaFailureRecovery]).
            recovery = createRecovery()
            prefetcher = PrefetchController(
                context = applicationContext,
                db = db,
                scope = scope,
                tag = TAG,
                cache = prefetch,
                picker = picker,
                loader = bitmapLoader,
                isUserTapSource = { source -> isUserTapSource(source) },
                host = object : PrefetchController.Host {
                    override fun isVisible(): Boolean = this@LiveWallpaperEngine.isVisible
                    override fun isInteractive(): Boolean = powerManager.isInteractive()
                    override fun lastDisplayedId(): Long =
                        this@LiveWallpaperEngine.lastDisplayedId
                    override fun isFailed(mediaId: Long): Boolean = recovery.isFailed(mediaId)
                    override fun engineDestroyed(): Boolean =
                        this@LiveWallpaperEngine.engineDestroyed
                },
            )
            switchExec = SwitchExecutor(
                db = db,
                picker = picker,
                loader = bitmapLoader,
                gif = gif,
                prefetch = prefetch,
                recovery = recovery,
                video = video,
                switchQueue = switchQueue,
                mainHandler = mainHandler,
                powerManager = powerManager,
                tag = TAG,
                host = object : SwitchExecutor.Host {
                    override fun renderer(): WallpaperRenderer? =
                        this@LiveWallpaperEngine.renderer
                    override fun currentBitmap(): Bitmap? =
                        this@LiveWallpaperEngine.currentBitmap
                    override fun setCurrentBitmap(bitmap: Bitmap?) {
                        this@LiveWallpaperEngine.currentBitmap = bitmap
                    }
                    override fun scaleMode(): ScaleMode =
                        this@LiveWallpaperEngine.currentScaleMode
                    override fun setScaleMode(mode: ScaleMode) {
                        this@LiveWallpaperEngine.currentScaleMode = mode
                    }
                    override fun autoRotateMismatch(): Boolean =
                        this@LiveWallpaperEngine.autoRotateMismatch
                    override fun setAutoRotateMismatch(value: Boolean) {
                        this@LiveWallpaperEngine.autoRotateMismatch = value
                    }
                    override fun autoRotateClockwise(): Boolean =
                        this@LiveWallpaperEngine.autoRotateClockwise
                    override fun setAutoRotateClockwise(value: Boolean) {
                        this@LiveWallpaperEngine.autoRotateClockwise = value
                    }
                    override fun lastDisplayedId(): Long =
                        this@LiveWallpaperEngine.lastDisplayedId
                    override fun setLastDisplayedId(id: Long) {
                        this@LiveWallpaperEngine.lastDisplayedId = id
                    }
                    override fun setSuppressFadeUntilFirstFrame(value: Boolean) {
                        this@LiveWallpaperEngine.suppressFadeUntilFirstFrame = value
                    }
                    override fun setFadePendingForFirstFrame(value: Boolean) {
                        this@LiveWallpaperEngine.fadePendingForFirstFrame = value
                    }
                    override fun pendingVideoEndSwitch(): Boolean =
                        this@LiveWallpaperEngine.pendingVideoEndSwitch
                    override fun setPendingVideoEndSwitch(value: Boolean) {
                        this@LiveWallpaperEngine.pendingVideoEndSwitch = value
                    }
                    override fun pendingVideoEndGroupId(): Long =
                        this@LiveWallpaperEngine.pendingVideoEndGroupId
                    override fun setPendingVideoEndGroupId(value: Long) {
                        this@LiveWallpaperEngine.pendingVideoEndGroupId = value
                    }
                    override suspend fun applyClarityMode() =
                        this@LiveWallpaperEngine.applyClarityMode()
                    override suspend fun applyKenBurnsMode() =
                        this@LiveWallpaperEngine.applyKenBurnsMode()
                    override fun clearCurrentBitmap() =
                        this@LiveWallpaperEngine.clearCurrentBitmap()
                    override fun retireCurrentBitmap() =
                        this@LiveWallpaperEngine.retireCurrentBitmap()
                    override suspend fun maybeFade() = this@LiveWallpaperEngine.maybeFade()
                },
            )
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
                        // 画质增强（超分） is the clarity panel's third option now:
                        // one DB value drives both the sharpening scale and the
                        // super-resolution flag (legacy "strong" migrates inside
                        // ClarityMode).
                        renderer?.applyClarity(
                            if (isPreview) 0f else ClarityMode.sharpnessScale(mode),
                            qualityBoost = !isPreview && ClarityMode.boostsQuality(mode),
                            mode = if (isPreview) EnhanceMode.BUILT_IN else currentEnhanceMode(),
                        )
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
            switchQueue.ensureConsumer()
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
            floating.update()
            scope.launch {
                try {
                    db.settingsDao().getValueFlow(SettingsKeys.FLOATING_BUTTON_ENABLED).collect {
                        floating.update()
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
                                floating.applyAppearance(
                                    colorArgb,
                                    opacity,
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
                        // The engine may be (re)created while our own UI is
                        // already in the foreground - MIUI tears the wallpaper
                        // down and instantiates it again all the time, and the
                        // process can be restarted by an install/update while
                        // the app is open. `applyAppForeground(true)` is a
                        // TRANSITION callback and will not fire again, so the
                        // fresh renderer used to start its audio behind our UI
                        // (user report: entering the app no longer silenced the
                        // video). Apply the mute immediately in that case.
                        // (Keep this as its own statement at the same level as
                        // the assignment above: it is NOT an expression appended
                        // to it.)
                        if (appInForeground && !isPreview) {
                            it.muteAudioKeepingVideo()
                        }
                        // The picker's frames are composited (and on a landscape
                        // tablet rotated) by MIUI: no unsharp mask there, see
                        // applyClarityMode(). The applied wallpaper keeps it.
                        if (isPreview) {
                            it.sharpnessScale = 0f
                            AppLog.d(TAG, "Preview engine: sharpening off (picker composition)")
                        }
                        it.onVideoStartFailed = {
                            // A clip that never started (or whose decode gave
                            // up / stalled) can never complete a pass, so a
                            // held timed switch waiting on it must be cleared
                            // here. Otherwise the hold leaked and every later
                            // timed tick was dropped forever ("still held"
                            // spam with the wallpaper frozen on a fallback
                            // frame); the next tick switches normally again.
                            if (pendingVideoEndSwitch) {
                                pendingVideoEndSwitch = false
                                pendingVideoEndGroupId = 0L
                                AppLog.d(
                                    TAG,
                                    "Held timed switch released: the video failed to start"
                                )
                            }
                            recovery.onVideoStartFailed()
                        }
                        // 视频播完再切: a timed switch that arrived while the
                        // clip was playing runs here, at the end of the pass.
                        it.onVideoPassCompleted = {
                            if (pendingVideoEndSwitch) {
                                pendingVideoEndSwitch = false
                                val heldGroupId = pendingVideoEndGroupId
                                pendingVideoEndGroupId = 0L
                                AppLog.d(
                                    TAG,
                                    "Video pass finished: running the held timed switch" +
                                        if (heldGroupId > 0L) " (group=$heldGroupId)" else ""
                                )
                                switchQueue.request(SOURCE_VIDEO_END, null, heldGroupId)
                            }
                        }
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
                                scope.launch { maybeFade() }
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
        // 视频起不来时的恢复见 [MediaFailureRecovery.onVideoStartFailed]。

        /**
         * Extract the first frame of a video as a fallback static image (used
         * when a device cannot play the video through MediaCodec/SurfaceTexture).
         * Shared with the static applier (see [FirstFrame]): one implementation,
         * with the container rotation applied, so portrait recordings are
         * upright in both modes.
         */
        private fun videoFirstFrame(uriStr: String, positionUs: Long = 0L): Bitmap? =
            bitmapLoader.videoFirstFrame(uriStr, positionUs)

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
                    prefetch.clear()
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
            // `video.active` is deliberately NOT cleared here: it is also the identity
            // the visibility path uses ("a video session is alive, keep it" - see
            // onVisibilityChanged), and clearing it made a rotation that destroyed
            // the surface tear down the decoder + audio session even though the
            // renderer was still holding the clip. The monitor is cancelled
            // explicitly above, and every other consumer of `video.active`
            // (drawCurrentImage's videoWasPlaying, the watchdogs) additionally
            // checks `renderer?.isVideoPlaying`, so a stale `true` cannot resurrect
            // a dead session.
            video.cancelHealthMonitor()
            renderer?.surfaceDestroyed()
            gif.pause()
        }

        override fun onTouchEvent(event: MotionEvent) {
            // 触摸只会送到当前真正可见的壁纸窗口：这是“确实在桌面”的可靠证据。
            // 某些 ROM 在选壁纸对话框关闭后不补发 onVisibilityChanged(true)，
            // 导致 video/audio 永远停在 “wallpaper not visible”；用户一碰桌面
            // 就应当立刻恢复，而不是等下一次系统回调。
            if (!appInForeground && renderer?.powerSaveMode == true) {
                power.setVisibleInput(true)
                power.refresh("user-touch")
            }
            touch.handle(event)
            super.onTouchEvent(event)
        }

        internal fun hideFloatingButtonNow() {
            floating.dismissNow()
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
                power.onAppEntered()
                AppLog.d(TAG, "App UI foreground: mute audio now, pause decode after the open animation")
                // The sound stops with the tap (user requirement), but the picture
                // keeps playing until our UI has really covered the wallpaper:
                // pausing the decode at once froze the video DURING the app-open
                // animation, which is visible only for our own app - other apps
                // reach us through the system's covered report ~1.1s later
                // (measured; see APP_ENTRY_PAUSE_GRACE_MS).
                renderer?.muteAudioKeepingVideo()
                // The deferred pause evaluation lives in PowerSaveController:
                // the decode survives the open animation while a covered report
                // that arrives earlier still pauses us right away.
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
                val backOnDesktop = (visibleBeforeAppForeground || resumeOnNextAppExit) &&
                    !keyguardShowing && powerManager.isInteractive
                resumeOnNextAppExit = false
                if (isVisible || backOnDesktop) {
                    // Optimistic visibility for the power-save inputs only: the
                    // system's own callback may still be 1.5-2.5s away, and it
                    // corrects this input as soon as it arrives.
                    power.setVisibleInput(true)
                }
                // Arms the exit grace: the resume itself is delayed inside
                // PowerSaveController (whichever signal asks for it first).
                power.onAppLeft()
                floating.update()
            }
        }

        internal fun requestSwitchFromOutside(source: String, groupId: Long = 0L): Boolean {
            if (activeEngine !== this) return false
            // 悬浮按钮只出现在桌面上；点它同样证明壁纸可见（手动按钮走
            // SOURCE_MANUAL，此时 App 在前台，不能算）。
            if (!appInForeground && source == SOURCE_FLOATING &&
                renderer?.powerSaveMode == true
            ) {
                power.setVisibleInput(true)
                power.refresh("floating-tap")
            }
            switchQueue.request(source, null, groupId)
            return true
        }

        /** Apply one specific media from outside (see the companion's push). */
        internal fun requestTargetFromOutside(source: String, targetId: Long): Boolean {
            if (activeEngine !== this) return false
            switchQueue.request(source, targetId)
            return true
        }

        internal fun refreshFloatingButtonNow() {
            floating.update()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            try {
                AppLog.d(TAG, "Visibility changed: visible=$visible (preview=$isPreview)")
                isVisible = visible
                if (visible) resumeOnNextAppExit = false
                // The callback is authoritative for the power-save input too (it
                // also clears the optimistic value set when our own UI closed).
                power.setVisibleInput(visible)
                if (visible) {
                    cancelMediaReleaseWhileLocked()
                    selfHealTimerService()
                    floating.update()
                    power.refresh("visibility")
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
                            // resetting video.active here would make
                            // drawCurrentImage() rebuild the decoder and the audio
                            // thread even though the clip can keep playing - see
                            // the rotation branch there.
                            if (video.active && renderer?.isVideoPlaying == true) {
                                AppLog.d(TAG, "Back on desktop after rotation: video kept alive")
                            } else {
                                video.markInactive()
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
                    floating.update()
                    power.refresh("visibility")
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
            switchQueue.reset()
            mediaReleaseRunnable = null
            floating.shutdown()
            prefetch.clear()
            // Remove any pending delayed recovery / redraw callbacks so they
            // can never fire after the engine is destroyed.
            mainHandler.removeCallbacksAndMessages(null)
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            try { applicationContext.unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
            if (::picker.isInitialized) picker.flush()
            try { renderer?.release() } catch (_: Exception) {}
            renderer = null
            rendererInitialized = false
            // GIF shutdown is serialized on its own render thread (see the
            // controller): stop the ticker, close the drawable and recycle
            // the ping-pong buffers there, then quit the thread.
            gif.shutdown()
            currentBitmap?.recycle(); currentBitmap = null
            retiredBitmap?.recycle(); retiredBitmap = null
            bitmapLoader.releaseDefaultBitmap()
            video.cancelHealthMonitor()
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

        // 视频会话的起播/停止/看门狗见 [VideoSessionController]。





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
                if (video.active) {
                    AppLog.d(
                        TAG,
                        "Video session released after ${MEDIA_RELEASE_AFTER_SCREEN_OFF_MS / 1000}s " +
                            "locked (power save)"
                    )
                    video.stop()
                    // Coming back must NOT fade in (that reads as a black flash
                    // while the video rebuilds).
                    resumeFromPowerSave = true
                    lastDisplayedId = 0L
                    // 接着上次位置继续播放: remember where the clip was when the
                    // session was released, so the rebuild resumes there instead
                    // of jumping back to the beginning (user request). The value
                    // is picked up by the next video.start() for the same media -
                    // see video.resumePositionFor().
                    val releasedPositionUs = renderer?.lastVideoPositionUs ?: 0L
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
                            // video.resumePositionFor).
                            video.rememberResume(id, releasedPositionUs)
                            val frame = videoFirstFrame(uri, releasedPositionUs)
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
                if (gif.isActive()) {
                    AppLog.d(
                        TAG,
                        "GIF released after ${MEDIA_RELEASE_AFTER_SCREEN_OFF_MS / 1000}s locked (power save)"
                    )
                    gif.releaseWhileLocked()
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
            prefetch.clear()
            renderer?.applyScaleMode(mode)
            // Static images need a fresh decode (not just a re-quad) when the
            // mode toggles across FILL so the FILL 90° orientation applies;
            // video/GIF pick the new quad up on their next frames.
            val shownBitmap = currentBitmap
            val staticImageActive = !video.active && !gif.isActive() &&
                shownBitmap != null && !shownBitmap.isRecycled
            if (staticImageActive) {
                lastDisplayedId = 0L
                drawCurrentImage()
            }
            AppLog.d(TAG, "Scale mode changed live to $mode (previous $previous)")
        }

        /** 过渡动画 change pushed from the settings screen (see the companion). */
        internal fun applyTransitionMode(mode: String) {
            renderer?.setTransitionMode(mode)
        }

        /** 静态图微动效 change pushed from the settings screen (see the companion). */
        internal fun applyKenBurnsEnabled(enabled: Boolean) {
            renderer?.setKenBurnsEnabled(enabled)
        }

        /** 放大算法变化（见 [com.wallpaperswitcher.engine.EnhanceMode]）。 */
        internal fun applyEnhanceMode(mode: Int) {
            renderer?.setEnhanceMode(mode)
        }

        private fun applyRotateSettingsLive(enabled: Boolean, clockwise: Boolean) {
            if (autoRotateMismatch == enabled && autoRotateClockwise == clockwise) return
            autoRotateMismatch = enabled
            autoRotateClockwise = clockwise
            // A prefetch decoded under the previous setting would carry the
            // wrong orientation; drop it so the next switch decodes fresh.
            prefetch.clear()
            renderer?.autoRotateMismatch = enabled
            renderer?.autoRotateClockwise = clockwise
            renderer?.refreshAfterAutoRotateChange()
            AppLog.d(TAG, "Auto rotate mismatch = $enabled, clockwise = $clockwise")
            // Static images are decoded with the rotation applied at load time,
            // so they need a fresh decode; GIFs must restart to rebuild rotated
            // frames; videos only recompute their quad (done above).
            val shownBitmap = currentBitmap
            val staticImageActive = !video.active && !gif.isActive() &&
                shownBitmap != null && !shownBitmap.isRecycled
            val gifActive = !video.active && gif.isActive()
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


        /**
         * The group a user tap (悬浮按钮 / 双击 / 立即切换) belongs to.
         *
         * The timer always carries the group its schedule picked, but those
         * triggers reach the engine directly with `groupId = 0`. Resolving the
         * group here - on the switch consumer, not on the tap's main thread -
         * keeps the tap's pick, cursor and prefetch inside the same group as
         * the timer (see [com.wallpaperswitcher.engine.GroupSchedulePlan]).
         *
         * @return 0 when no group drives its own rhythm; the screen-wide pick is
         *   the correct default then.
         */
        private suspend fun resolveUserTapGroup(req: SwitchRequest): Long {
            if (req.targetId != null || req.groupId > 0L) return req.groupId
            if (!isUserTapSource(req.source)) return 0L
            // 手动触发跟随全局切换模式，不再先挑一个分组：
            //  - 随机 / 洗牌：在所有启用分组（时间规则允许的）的媒体池里取；
            //  - 顺序：从当前图片开始，本组走完 → 下一个分组。
            // 以前这里按「等得最久」挑一个分组再切，结果随机/洗牌退化成按分组
            // 轮转（用户看到的 28→29→32 循环），顺序模式也永远走不到逐组推进。
            return 0L
        }

        // 一次切换的完整流程见 [SwitchExecutor]；引擎只提供渲染状态与三个渲染动作。




        /**
         * True for the triggers the user performs by hand: the floating button,
         * a double tap on the desktop, or the "立即切换壁纸" button. These get
         * their own tap queue (each tap switches) and always leave a prefetched
         * next image behind, so tapping again is a ~20ms texture upload instead
         * of a ~250ms decode ("不跟手").
         */
        private fun isUserTapSource(source: String): Boolean =
            source == SOURCE_FLOATING ||
                source == SOURCE_DOUBLE_TAP ||
                source == SOURCE_MANUAL

        /**
         * Release optional memory on system low-memory pressure: the
         * prefetched next-image bitmap, the displayed static image's CPU
         * bitmap (its GL texture keeps the pixels on screen) and the GIF
         * ping-pong buffers when no GIF is driving the screen.
         *
         * Runs on the MAIN thread (Application.onTrimMemory -> here).
         *
         * What is never released, and why:
         * - **视频播放中** (`video.active`): the decoder + external texture are
         *   the live picture; dropping them is the parked-release path
         *   ([scheduleMediaReleaseWhileLocked], which re-arms a redraw), not this
         *   one.
         * - **切换进行中** (`switchInProgress` / `redrawInProgress`): a switch in
         *   flight still owns/hands over `currentBitmap`
         *   (SwitchExecutor's retain/clear and drawCurrentImage's decode). Taking
         *   it away there could recycle a bitmap the render thread is about to
         *   upload (`GLUtils.texImage2D` on a recycled bitmap fails, and the
         *   guard in renderImage would then silently skip the frame).
         * - **powerSaveMode**: the wallpaper is not visible (screen off / covered
         *   / our own UI). The parked-release path owns that state and must stay
         *   the only one releasing media there, otherwise a resume would have
         *   nothing to re-apply.
         *
         * "释放后必须有人重绘" - every release below leaves a redraw trigger
         * behind:
         * - static image: the GL texture + the renderer's cached source size make
         *   the image re-presentable, and `surfaceChanged()` /
         *   `retrySurfaceIfNeeded()` / `refreshImageQuad()` /
         *   `repaintForTransition()` / Ken Burns all redraw from the texture now
         *   (see WallpaperRenderer.canRepaintFromTexture). `drawCurrentImage()`
         *   re-decodes the file on a rotation or when the desktop comes back.
         * - GIF: its buffers are only dropped while `!gif.isActive()`, i.e. when
         *   no ticker is painting into them; a later `gif.play()` allocates fresh
         *   ones, and a running ticker rebuilds them on its next tick.
         */
        internal fun trimMemoryNow() {
            prefetch.clear()
            // The transition bitmap is optional memory: the normal path decodes
            // the image again if it is ever needed.
            releaseRetiredBitmap()
            if (!isVisible || renderer?.powerSaveMode == true) return
            if (video.active || switchInProgress || redrawInProgress.get()) return
            // GIF first: while a GIF animates, its ping-pong buffers are the
            // frame targets the ticker is drawing into, so they are off limits
            // (dropping them mid-animation would leave the next ticks with
            // nothing to paint into). The static-image release below is
            // independent of this one: after a normal GIF switch
            // `currentBitmap` is null, but an empty-group placeholder or the
            // GIF health fallback can legitimately have both a bitmap and a
            // GIF on screen at once.
            if (!gif.isActive()) {
                gif.releaseBuffersForMemory()
            }
            val shown = currentBitmap ?: return
            if (shown.isRecycled) {
                currentBitmap = null
                return
            }
            // Read the dimensions BEFORE recycling: a recycled Bitmap answers
            // width/height as a matter of current implementation, not contract.
            val shownW = shown.width
            val shownH = shown.height
            // Static image on screen: hand its CPU pixels back. Ownership:
            // - the engine recycles it HERE, on the main thread, and drops its
            //   reference. Nothing else reads the pixels: the image is already
            //   in the GL texture, and the render thread holds its own reference
            //   to any bitmap whose upload that switch queued (the switch case
            //   is excluded above).
            // - the renderer forgets its `lastDenoiseBitmap` reference so the
            //   pixels are really collectable, and keeps a texture-side marker
            //   so a rotation/surface recreation still redraws (see
            //   WallpaperRenderer.dropImageBitmapForMemory).
            // `lastDisplayedId` is deliberately left alone: it keeps meaning "this
            // media is the one on screen", which is what the redraw path needs to
            // know to re-present or re-decode it (the texture makes re-decoding
            // unnecessary until something asks for a new decode).
            currentBitmap = null
            if (!shown.isRecycled) shown.recycle()
            renderer?.dropImageBitmapForMemory()
            AppLog.d(
                TAG,
                "Low memory: released the displayed image bitmap " +
                    "(${shownW}x${shownH}); GL texture kept for redraw"
            )
        }

        /**
         * True when this engine is visible and its EGL surface is ready to
         * present. See [isRenderSurfaceReady] for why the timer cares.
         */
        internal fun isRenderSurfaceReadyNow(): Boolean =
            surfaceReady && renderer?.isSurfaceReady() == true


        // 预取决策/解码见 [PrefetchController].`r`n
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
                    applyKenBurnsMode()
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
                        if (video.active && r.isVideoPlaying) return@launch
                        val shownBmp = currentBitmap
                        if (!video.active && shownBmp != null && !shownBmp.isRecycled) return@launch
                        // A GIF that is already rendering must not be re-decoded
                        // and restarted by a coincidental redraw (e.g. a
                        // visibility retry while a switch is settling).
                        if (!video.active && gif.isActive()) return@launch
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

                    gif.pause()
                    // Remembered BEFORE the reset below: the video branch needs
                    // to know whether the media on screen was a playing clip
                    // (the old code tested `video.active` after clearing it, so the
                    // rotation-resume branch could never run).
                    val videoWasPlaying = video.active
                    video.markInactive()
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
                                val resumeUs = video.resumePositionFor(image.id).let { pending ->
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
                                if (video.start(
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
                                        !orientationRedraw && !resumedFromPowerSave
                                } else {
                                    lastDisplayedId = 0L
                                }
                                return@launch
                            }
                            MediaTypes.GIF -> {
                                clearCurrentBitmap()
                                video.markInactive()
                                mainHandler.post { gif.play(image.uri, currentScaleMode, image.id) }
                                gif.startHealthMonitor(image.id, image.uri)
                                lastDisplayedId = image.id
                                if (!orientationRedraw && !resumedFromPowerSave) maybeFade()
                                return@launch
                            }
                            else -> {
                                video.markInactive()
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
                                    bitmapLoader.bitmapFillsScreen(shownBmp, sw, sh)
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
                                            "fills=${if (shownBmp != null && bmpOk) bitmapLoader.bitmapFillsScreen(shownBmp, sw, sh) else false})"
                                    )
                                }
                                AppLog.d(TAG, "drawCurrentImage loading bitmap: ${LogText.short(image.uri)}")
                                // Decode against the actual wallpaper Surface
                                // size: on tablets the display metrics can lag
                                // behind the recreated surface right after a
                                // rotation, which would otherwise re-decode for
                                // the previous orientation.
                                val bitmap = bitmapLoader.loadBitmapWithTimeout(
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
                                    if (recovery.repairMisTypedMedia(image)) {
                                        mainHandler.postDelayed({ drawCurrentImage() }, 250L)
                                    }
                                }
                            }
                        }
                    }
                    video.markInactive()
                    r.showImage(bitmapLoader.getDefaultBitmap(), currentScaleMode)
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
            renderer?.sharpnessScale = if (isPreview) 0f else ClarityMode.sharpnessScale(mode)
            renderer?.setQualityBoost(!isPreview && ClarityMode.boostsQuality(mode))
            renderer?.setEnhanceMode(if (isPreview) EnhanceMode.BUILT_IN else currentEnhanceMode())
        }

        /**
         * 当前超分算法：`enhance_algo`；键还没写入时回退到 4.9.154 的两个旧开关
         * （迁移在 App 启动时执行，这里只是保证迁移前重建的引擎也走对算法）。
         */
        private suspend fun currentEnhanceMode(): Int = try {
            val dao = db.settingsDao()
            val stored = dao.getString(SettingsKeys.ENHANCE_ALGO, "")
            if (stored.isNotBlank()) {
                EnhanceMode.fromKey(stored)
            } else {
                EnhanceMode.fromKey(
                    EnhanceMode.legacyKey(
                        fsr1 = dao.getBool(SettingsKeys.LEGACY_FSR1_ENHANCE_ENABLED, false),
                        anime4k = dao.getBool(SettingsKeys.LEGACY_ANIME4K_ENHANCE_ENABLED, false),
                    )
                )
            }
        } catch (_: Exception) {
            EnhanceMode.FSR1
        }

        /**
         * Sync the 静态图微动效 setting into the renderer. Called before every
         * switch/redraw (next to [applyClarityMode]) so the engine never depends
         * on a settings push having reached it.
         */
        private suspend fun applyKenBurnsMode() {
            val enabled = try {
                db.settingsDao().getBool(SettingsKeys.KEN_BURNS_ENABLED, false)
            } catch (_: Exception) {
                false
            }
            renderer?.setKenBurnsEnabled(enabled)
        }

        /**
         * Play the 过渡动画 after a switch, when the setting is enabled and the
         * wallpaper is actually visible (skip it while covered or the screen is
         * off - nobody can see it then).
         *
         * Manual triggers (悬浮按钮 / 双击 / 「立即切换壁纸」) play it too: the user
         * asked for the transition to apply to EVERY switch, including a rapid
         * double-tap run: the transitions used to be skipped within 700ms of the
         * previous switch, which is exactly when the user double-taps again, so
         * the animation "disappeared". A transition that is already running is
         * simply restarted by the next switch (see WallpaperRenderer).
         */
        private suspend fun maybeFade() {
            if (renderer?.powerSaveMode == true) return
            if (renderer?.isSurfaceReady() != true) return
            val enabled = try {
                db.settingsDao().getBool(SettingsKeys.SWITCH_FADE_ENABLED, true)
            } catch (_: Exception) {
                true
            }
            if (enabled) {
                // 过渡动画 setting (fade / slide / zoom / none). Read here so a
                // change applies from the very next switch, no restart needed.
                val transition = try {
                    db.settingsDao().getString(
                        SettingsKeys.SWITCH_TRANSITION,
                        SettingsKeys.SWITCH_TRANSITION_DEFAULT
                    )
                } catch (_: Exception) {
                    SettingsKeys.SWITCH_TRANSITION_DEFAULT
                }
                AppLog.d(TAG, "Transition requested: $transition")
                renderer?.requestTransition(transition)
            }
        }

        // GIF 失败/成功的记账见 [MediaFailureRecovery].



        private fun getMetrics(): android.util.DisplayMetrics {
            return com.wallpaperswitcher.engine.BitmapUtils.getScreenMetrics(applicationContext)
        }
    }
}
