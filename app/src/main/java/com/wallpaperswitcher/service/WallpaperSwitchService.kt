package com.wallpaperswitcher.service

import com.wallpaperswitcher.util.AppLog

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.wallpaperswitcher.R
import com.wallpaperswitcher.WallpaperSwitcherApp
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsDao
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setBool
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.engine.SwitchSchedule
import com.wallpaperswitcher.engine.GroupPacing
import com.wallpaperswitcher.engine.GroupSchedulePlan
import com.wallpaperswitcher.engine.GroupRules
import com.wallpaperswitcher.engine.SwitchPicking
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.WallpaperApplier
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.ui.MainActivity
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of [WallpaperSwitchService.applyStaticWallpaper]: the caller shows a
 * matching message instead of blaming the media for everything.
 */
enum class StaticApplyOutcome {
    /** The wallpaper was written and the slot bookkeeping updated. */
    APPLIED,
    /** Another static apply held the guard for the whole wait window. */
    BUSY,
    /** Media missing/unreadable, or the platform rejected the write. */
    FAILED
}

/**
 * Timed wallpaper switch foreground service.
 * Switches wallpapers: broadcasts ACTION_SWITCH to the live wallpaper engine
 * when it is running, otherwise applies a static wallpaper directly.
 *
 * Timing is schedule-based (anchor + interval) rather than a fixed delay.
 *
 * Both schedules are anchored on wall-clock time and BOTH pause while the screen
 * is off: the time spent locked does not count, and on screen-on each anchor is
 * moved to "now" so the next switch is a full interval away. A tick that would
 * have been due while the screen was off is dropped instead of replayed as a
 * catch-up - nothing may change while nobody can see it, and a sleeping device
 * does not run user-space timers anyway.
 */
class WallpaperSwitchService : Service() {

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + com.wallpaperswitcher.util.logCoroutineFailures(TAG)
    )
    // Volatile: assigned by the restart coroutine, cancelled from onDestroy.
    @Volatile private var switchJob: Job? = null
    // The lock screen has its own schedule (own interval + anchor + toggle), so
    // it runs in its own loop, independent from the home timer.
    @Volatile private var lockSwitchJob: Job? = null
    // Serializes restarts so the previous loop is cancelled AND joined before
    // the next one starts. Two overlapping loops could dispatch one tick twice.
    private val loopRestartLock = Mutex()
    // Throttle the screen-off skip log to once per minute: at a 10s interval
    // the old code logged ~720 lines/hour while the screen was dark.
    private val lastScreenOffLogAt = java.util.concurrent.atomic.AtomicLong(0L)
    /**
     * Whether the foreground notification currently advertises the pause
     * action (it flips to 继续 while the 一键暂停 hold is active). Both timer
     * loops hand their freshly read state to [syncNotificationPause].
     */
    @Volatile private var notificationPaused = false
    // True once the "lock screen not showing" idle episode has been logged, so a
    // long unlocked session produces one line instead of one per re-check.
    // Only the lock loop coroutine touches it, but a restart coroutine may read
    // it right after a cancellation, so it is volatile like the other flags.
    @Volatile private var lockHiddenIdleAnnounced = false
    /**
     * True once the single "lock screen not showing" switch of the current
     * screen-on session has been applied (see [runLockSwitchLoop]). Reset on
     * screen-on and whenever the lock screen becomes visible again, so every
     * screen-on session - and every lock screen visit - gets its own one.
     */
    @Volatile private var lockHiddenSwitchDone = false
    /**
     * True once the "home timer idle because the lock screen covers the desktop"
     * episode has been logged (see the A1 gate in [runSwitchLoop]), so a long
     * locked-but-awake session produces one line instead of one per re-check.
     */
    @Volatile private var homeHiddenIdleAnnounced = false
    /**
     * True once the "our app is in the foreground, so the desktop timer is idle"
     * episode has been logged (see the app-foreground gate in [runSwitchLoop]).
     * One line per episode, and the gate itself is stateless: the tick is never
     * consumed, so leaving the app switches immediately (catch-up).
     */
    @Volatile private var homeAppHiddenIdleAnnounced = false
    // A single transient empty read (e.g. while the DB is being migrated or a
    // folder import replaced groups) must never kill the timer and flip the
    // toggle off silently; only stop after several consecutive empty checks.
    private var consecutiveEmptyGroupChecks = 0
    // Consecutive loop failures: back off instead of spinning at 10s forever
    // when the database is persistently unavailable.
    private var consecutiveLoopFailures = 0
    // True from the moment the timer pauses because the screen turned off until
    // the tick that resolves the pause (a catch-up switch, or a hand-off to the
    // unlock switch). Only touched by the receiver / the single loop coroutine.
    @Volatile private var pausedByScreenOff = false
    // Bounded retries for a catch-up whose live surface is not ready yet.
    // Written from the screen-state receiver (main thread) and read/cleared by
    // the timer loop, so it is volatile like the other shared flags.
    @Volatile private var surfaceRetryCount = 0
    // True once "Home timer disabled" has been logged for the current disabled
    // stretch (see runSwitchLoop): only the loop coroutine touches it.
    private var homeTimerDisabledLogged = false
    // True once the current 一键暂停 stretch has been logged, so the hold does
    // not write one line per re-check.
    @Volatile private var pauseIdleAnnounced = false

    /**
     * Screen state gate for the timers:
     * - screen off / locked -> BOTH loops are cancelled (no switches, no decodes
     *   behind the keyguard, no parked work) and the time spent there does NOT
     *   count towards either interval;
     * - screen on -> both intervals restart from that moment, so the next switch
     *   is a full interval away. A tick that would have been due while the screen
     *   was off is skipped instead of firing immediately at screen-on.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pausedByScreenOff = true
                    surfaceRetryCount = 0
                    switchJob?.cancel()
                    // The timer stops while the screen is off: on a sleeping
                    // device a user-space timer does not fire anyway (the CPU
                    // suspends without a wakelock), so keeping the loop "alive"
                    // only parks it - stopping it makes that explicit and costs
                    // nothing. The locked time does not count.
                    lockSwitchJob?.cancel()
                    AppLog.d(TAG, "Screen off: timers paused (interval restarts on screen-on)")
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Counting starts when the screen is actually displayed: a
                    // tick that became due while the screen was off is dropped
                    // instead of firing an immediate catch-up switch the moment
                    // the screen comes back.
                    AppLog.d(TAG, "Screen on: restarting both switch intervals from now")
                    pausedByScreenOff = false
                    surfaceRetryCount = 0
                    // A fresh screen-on session: the lock screen may be hidden
                    // for a while, and that session gets one timed switch of its
                    // own (see runLockSwitchLoop).
                    lockHiddenSwitchDone = false
                    lockHiddenIdleAnnounced = false
                    scope.launch {
                        try {
                            val db = AppDatabase.getInstance(applicationContext)
                            val dao = db.settingsDao()
                            val now = System.currentTimeMillis()
                            dao.setLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, now)
                            dao.setLong(
                                SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS,
                                now
                            )
                            // Per-group intervals (GroupPacing) re-anchor too: a
                            // group with its own rhythm must not count the
                            // screen-off time any more than the screen-wide
                            // schedule does. Rows that never switched stay due.
                            db.groupScheduleDao().reanchorAll(now)
                        } catch (e: Exception) {
                            AppLog.w(TAG, "screen-on re-anchor failed: ${e.javaClass.simpleName}")
                        }
                    }
                    startSwitchLoop()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        // ACTION_SCREEN_ON is a protected system broadcast: no exported flag
        // is required, and it is delivered even in the background.
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenStateReceiver, filter)
        } catch (e: Exception) {
            AppLog.w(TAG, "screen state receiver not registered: ${e.javaClass.simpleName}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure foreground state first (required when started via startForegroundService)
        // Android 14+ (targetSdk 34) requires an explicit foreground service type:
        // pass FOREGROUND_SERVICE_TYPE_SPECIAL_USE on API 29+ instead of relying on
        // the manifest-declared type (avoids MissingForegroundServiceTypeException
        // on strict/OEM builds).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        running = true
        activeInstance = this
        AppLog.d(TAG, "Service started, action=${intent?.action}")
        when (intent?.action) {
            ACTION_NOTIFY_SWITCH -> {
                AppLog.d(TAG, "Notification action: switch now")
                switchNow(applicationContext, LiveWallpaperService.SOURCE_NOTIFICATION)
            }
            ACTION_NOTIFY_TOGGLE_PAUSE -> {
                AppLog.d(TAG, "Notification action: toggle pause")
                scope.launch { togglePauseFromNotification() }
            }
        }
        // Any start intent (including a null one from a START_STICKY restart)
        // resumes the switch loop.
        startSwitchLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        // Also drop the in-place-poke target: a poke that arrives after this
        // must start the service for real instead of writing into a dead scope.
        if (activeInstance === this) activeInstance = null
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        switchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Restart the loop. The previous loop is cancelled AND joined before the
     * new one starts, so a tick can never be dispatched by two loops at once.
     */
    private fun startSwitchLoop() {
        scope.launch {
            loopRestartLock.withLock {
                switchJob?.cancelAndJoin()
                switchJob = scope.launch { runSwitchLoop() }
                lockSwitchJob?.cancelAndJoin()
                lockSwitchJob = scope.launch { runLockSwitchLoop() }
            }
        }
    }

    /**
     * Re-evaluate both timer loops in place, without a service start round-trip
     * (companion [poke]).
     *
     * @return false when this instance is already shutting down, so the caller
     *   falls back to a real start instead of poking a cancelled scope.
     */
    internal fun wakeLoopsInPlace(): Boolean {
        if (!running || !scope.isActive) return false
        startSwitchLoop()
        return true
    }

    private suspend fun runSwitchLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                // Self-heal guard: ensureRunning() can race a manual toggle
                // OFF and restart this service with a stale enabled state.
                // Re-check every tick so a zombie timer stops itself.
                if (!db.settingsDao().getBool(SettingsKeys.SERVICE_ENABLED, false)) {
                    // Logged once per disabled stretch, not once per loop start:
                    // with the in-place poke this loop restarts on every settings
                    // change, and repeating the same line for a timer the user
                    // deliberately switched off was pure noise (ten lines inside
                    // six seconds in the tablet log).
                    if (!homeTimerDisabledLogged) {
                        homeTimerDisabledLogged = true
                        AppLog.d(TAG, "Home timer disabled, stopping home loop")
                    }
                    // The lock timer is independent: keep the service (and the
                    // lock loop) alive while it still has work to do.
                    if (!db.settingsDao().getBool(SettingsKeys.LOCK_TIMER_ENABLED, true)) {
                        stopSelf()
                    }
                    return
                }
                homeTimerDisabledLogged = false
                // Locked / screen off: no timer work at all - no wakeups, no
                // switches, no decodes. The screen-on receiver restarts this
                // loop against the kept schedule.
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isInteractive != true) {
                    pausedByScreenOff = true
                    surfaceRetryCount = 0
                    AppLog.d(TAG, "Screen not interactive, pausing timer")
                    return
                }
                // 用户需求（桌面定时，最终版）：我们的应用在前台时桌面壁纸不可见，
                // 定时**完全不切**（连一次都不切）——每个 tick 都是一次全分辨率解码
                // （静态模式下还要叠加 JPEG 编码 + 写入系统壁纸），而没人看得到结果。
                //
                // 这个 tick **不消耗**：应用离开前台时 MainActivity.onStop 会 poke 本服务，
                // 循环重新检查时发现已到期就**立刻补切一张**（用户要求），随后从这一刻起
                // 按正常间隔轮换。这里的 5s 等待只是兜底（Activity 被杀 / 回调丢失）。
                //
                // 锁屏跟随动态壁纸（见 enforceSlotsLocked），不再有独立的锁屏循环。
                val appInForeground = LiveWallpaperService.isAppForeground()
                if (SwitchPicking.shouldIdleWhileAppInForeground(appInForeground)) {
                    if (!homeAppHiddenIdleAnnounced) {
                        homeAppHiddenIdleAnnounced = true
                        AppLog.d(
                            TAG,
                            "App in foreground: home timer idle (no switch while the app is open)"
                        )
                    }
                    delay(HOME_APP_HIDDEN_RECHECK_MS)
                    continue
                }
                homeAppHiddenIdleAnnounced = false

                // A1 (target screen must be visible): only the STATIC write is
                // worth gating on visibility. It decodes a full-resolution image,
                // JPEG-encodes it and hands it to the wallpaper service, and while
                // the lock screen covers the desktop nobody can see the result -
                // yet the work happened (and would happen again on the next tick
                // if the screen stayed on, e.g. while reading notifications).
                //
                // While our live wallpaper engine is running the gate is NOT
                // applied: the engine's own power-save already stops rendering
                // when it is not visible, and holding the tick back would make an
                // unlock show the old wallpaper for a moment before the catch-up
                // switch. The tick is NOT consumed here - the moment the desktop
                // is visible again the overdue tick switches once through the
                // normal catch-up path.
                if (!LiveWallpaperService.engineRunning && !isHomeScreenVisible()) {
                    if (!homeHiddenIdleAnnounced) {
                        homeHiddenIdleAnnounced = true
                        AppLog.d(
                            TAG,
                            "Home screen not visible (lock screen showing): home timer idle"
                        )
                    }
                    delay(HOME_HIDDEN_RECHECK_MS)
                    continue
                }
                homeHiddenIdleAnnounced = false

                // A single transient empty read (migration/import race) must
                // not kill the timer; only several consecutive ones do.
                val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                if (groups.isEmpty()) {
                    consecutiveEmptyGroupChecks++
                    if (consecutiveEmptyGroupChecks >= EMPTY_GROUP_STOP_THRESHOLD) {
                        AppLog.d(TAG, "No enabled groups for $consecutiveEmptyGroupChecks checks, stopping service")
                        // Sync the toggle so the UI reflects the stopped state.
                        db.settingsDao().setBool(SettingsKeys.SERVICE_ENABLED, false)
                        // The lock timer is independent: when it is still on,
                        // keep the service alive and let the lock loop idle -
                        // stopping here killed a lock-only timer while its
                        // toggle (and the UI's "锁屏运行中") still said it runs.
                        if (!db.settingsDao().getBool(SettingsKeys.LOCK_TIMER_ENABLED, true)) {
                            stopSelf()
                        }
                        return
                    }
                    AppLog.d(TAG, "No enabled groups (check $consecutiveEmptyGroupChecks/$EMPTY_GROUP_STOP_THRESHOLD), keeping timer alive")
                } else {
                    consecutiveEmptyGroupChecks = 0
                }

                // Schedule-based countdown: the next switch is due at
                // anchor + interval. The anchor is NOT reset when the screen
                // turns off, so the time spent locked still counts.
                val dao = db.settingsDao()
                // 一键暂停 ("稍后切换") holds the tick WITHOUT consuming it, so
                // the moment the pause expires the overdue switch fires - even
                // if the app was closed the whole time.
                val pausedLeft = pauseRemainingMs(dao)
                syncNotificationPause(pausedLeft > 0L)
                if (pausedLeft > 0L) {
                    if (!pauseIdleAnnounced) {
                        pauseIdleAnnounced = true
                        AppLog.d(TAG, "Paused (${pausedLeft}ms left): home timer holding")
                    }
                    delay(pausedLeft.coerceAtMost(PAUSE_RECHECK_MS))
                    continue
                }
                pauseIdleAnnounced = false
                // A manual pick (tapping an image / confirming the system live
                // wallpaper screen) must stay on screen while the user is in the
                // system dialog: postpone the timer briefly, then restart the
                // interval. Only the HOME timer does this - the lock timer has
                // its own schedule and is not held back by home-side picks.
                // The system live-wallpaper dialog itself (its preview engine is
                // alive) gets the same treatment: writing a static wallpaper
                // there changes the wallpaper under the dialog, which makes the
                // picker abort its apply and crash, and replaces the live
                // wallpaper the user is setting up.
                if (LiveWallpaperService.isPreviewDialogOpen()) {
                    AppLog.d(TAG, "System live-wallpaper dialog open, postponing home timer")
                    withContext(NonCancellable) {
                        moveScheduleAnchor(
                            dao,
                            SettingsKeys.TIMER_LAST_SWITCH_WALL_MS,
                            System.currentTimeMillis()
                        )
                    }
                    delay(PREVIEW_DIALOG_HOLD_WAIT_MS)
                    continue
                }
                val holdLeft = manualHoldRemainingMs(dao)
                if (holdLeft > 0L) {
                    AppLog.d(TAG, "Manual pick hold, postponing home timer ${holdLeft}ms")
                    withContext(NonCancellable) {
                        moveScheduleAnchor(dao, SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, System.currentTimeMillis())
                    }
                    delay(holdLeft.coerceAtMost(60_000L))
                    continue
                }
                // The next tick: the screen-wide schedule while every group
                // follows the global interval (the original behaviour, byte for
                // byte), or one group with its own rhythm once any group opted
                // into its own interval (see GroupPacing). A null tick means no
                // group can switch for this screen at all right now.
                val tick = nextScreenTick(
                    db,
                    dao,
                    WallpaperTarget.SLOT_HOME,
                    groups.filter { WallpaperTarget.fromName(it.target).includesHome },
                    SettingsKeys.GLOBAL_INTERVAL_MS,
                    SettingsKeys.TIMER_LAST_SWITCH_WALL_MS
                )
                if (tick == null) {
                    // Every group is media-less / outside its time window: stay
                    // alive but idle, and let a poke or the re-check pick it up.
                    pausedByScreenOff = false
                    surfaceRetryCount = 0
                    delay(HOME_IDLE_RECHECK_MS)
                    continue
                }
                val interval = tick.intervalMs
                val anchor = tick.anchor
                if (tick.waitMs > 0L) {
                    // Not due yet. The schedule is healthy again, so the next
                    // tick is a normal one, not a catch-up.
                    pausedByScreenOff = false
                    surfaceRetryCount = 0
                    delay(tick.waitMs)
                    continue
                }

                // Due now. A late tick is a catch-up: the service was killed /
                // frozen after the due time had passed (screen-off pauses are
                // already re-anchored at screen-on, so they never get here).
                val catchUp = pausedByScreenOff ||
                    SwitchSchedule.isCatchUp(anchor, interval, System.currentTimeMillis())

                // "解锁切换" switches on ACTION_USER_PRESENT. Give it a short
                // grace to prove that it really handles this unlock; only then
                // hand the tick over (otherwise a screen-on without an unlock,
                // or a receiver that silently skipped, would lose the switch).
                if (catchUp && LiveWallpaperService.engineRunning &&
                    dao.getBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)
                ) {
                    val graceStart = SystemClock.elapsedRealtime()
                    delay(UNLOCK_COORDINATION_GRACE_MS)
                    if (unlockSwitchDispatchedAt >= graceStart) {
                        AppLog.d(TAG, "Overdue tick left to the unlock switch; timer re-anchored")
                        pausedByScreenOff = false
                        surfaceRetryCount = 0
                        claimTick(db, dao, tick, WallpaperTarget.SLOT_HOME, SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, System.currentTimeMillis())
                        continue
                    }
                    val pmGrace = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pmGrace?.isInteractive != true) {
                        pausedByScreenOff = true
                        return
                    }
                }

                // A switch must land on a live surface: right after the screen
                // comes back its EGL surface is still being recreated, and a
                // switch rendered into a dead surface is dropped while the
                // engine still records the media as displayed (blank wallpaper).
                if (!awaitRenderSurface(catchUp)) {
                    val pmSurface = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pmSurface?.isInteractive != true) {
                        // Screen turned off while waiting: keep the tick pending.
                        pausedByScreenOff = true
                        return
                    }
                    if (catchUp && surfaceRetryCount < SURFACE_RETRY_LIMIT) {
                        // Do not abandon the catch-up on a slow surface: retry a
                        // few times, then give up so the loop cannot spin.
                        surfaceRetryCount++
                        AppLog.w(TAG, "Catch-up postponed: live surface not ready (retry $surfaceRetryCount/$SURFACE_RETRY_LIMIT)")
                        delay(SURFACE_RETRY_DELAY_MS)
                        continue
                    }
                    AppLog.w(TAG, "Skipping tick: live wallpaper surface not ready")
                    surfaceRetryCount = 0
                    pausedByScreenOff = false
                    claimTick(db, dao, tick, WallpaperTarget.SLOT_HOME, SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, System.currentTimeMillis())
                    continue
                }

                surfaceRetryCount = 0
                // Claim the tick BEFORE dispatching: a loop restart (screen-on
                // / settings change) cancels this coroutine, and without the
                // early claim the fresh loop would still see the tick as due
                // and switch twice in a row.
                val tickAt = System.currentTimeMillis()
                withContext(NonCancellable) { claimTick(db, dao, tick, WallpaperTarget.SLOT_HOME, SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, tickAt) }
                if (!sendSwitch(LiveWallpaperService.SOURCE_TIMER, tick.groupId)) {
                    // The screen turned off between the check and the dispatch.
                    // Put the anchor back so the tick (and its catch-up) stays
                    // pending for the next screen-on instead of being consumed.
                    withContext(NonCancellable) { restoreTick(db, dao, tick, WallpaperTarget.SLOT_HOME, SettingsKeys.TIMER_LAST_SWITCH_WALL_MS) }
                    pausedByScreenOff = true
                    return
                }
                pausedByScreenOff = false
                consecutiveLoopFailures = 0
                // Keep the home-screen widget's "current wallpaper / 下次切换"
                // line in sync with the tick that just ran (fire-and-forget).
                ioScope.launch {
                    try {
                        com.wallpaperswitcher.widget.WallpaperWidgetProvider
                            .refreshAll(applicationContext)
                    } catch (e: Exception) {
                        AppLog.d(TAG, "widget refresh failed: ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveLoopFailures++
                AppLog.e(TAG, "Switch loop error (attempt $consecutiveLoopFailures)", e)
                // Back off on repeated failures instead of spinning at 10s
                // forever (e.g. database persistently unavailable).
                delay(10_000L * consecutiveLoopFailures.coerceAtMost(6))
            }
        }
    }

    /**
     * Wall-clock anchor of a timer schedule: the next tick is due at
     * anchor + interval. [key] selects the schedule
     * ([SettingsKeys.TIMER_LAST_SWITCH_WALL_MS] for the home screen,
     * [SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS] for the lock screen).
     *
     * Always read from the DB (it is the single source of truth, shared with the
     * unlock-switch path) and seeded to "now" when the stored value is missing,
     * stale or in the future.
     *
     * [intervalMs] widens the "stale" window ([GroupPacing.staleAfterMs]): a
     * 7-day interval whose anchor is 2 days old is perfectly healthy, while the
     * fixed 24h window used to re-anchor it (the switch then never happened).
     */
    private suspend fun currentScheduleAnchor(
        dao: SettingsDao,
        key: String,
        intervalMs: Long = 0L,
    ): Long {
        val now = System.currentTimeMillis()
        val persisted = try {
            dao.getLong(key, 0L)
        } catch (_: Exception) {
            0L
        }
        val anchor = SwitchSchedule.resolveAnchor(
            persisted, now, GroupPacing.staleAfterMs(intervalMs)
        )
        if (anchor != persisted) {
            try {
                dao.setLong(key, anchor)
            } catch (e: Exception) {
                AppLog.d(TAG, "anchor write failed ($key): ${e.javaClass.simpleName}")
            }
        }
        return anchor
    }

    /** Start the next interval of [key] from [at]: a switch just happened. */
    private suspend fun moveScheduleAnchor(dao: SettingsDao, key: String, at: Long) {
        try {
            dao.setLong(key, at)
        } catch (e: Exception) {
            AppLog.d(TAG, "anchor move failed ($key): ${e.javaClass.simpleName}")
        }
    }

    /**
     * Wall-clock ms left of an explicit "set this wallpaper" pick that the
     * automatic timers must not override (0 = no hold).
     */
    private suspend fun manualHoldRemainingMs(dao: SettingsDao): Long {
        val until = try {
            dao.getLong(SettingsKeys.MANUAL_PICK_HOLD_UNTIL, 0L)
        } catch (_: Exception) {
            0L
        }
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * One pending tick of a screen: either the screen-wide schedule
     * (`groupId = 0`, the default) or a single group that carries its own
     * interval (see [GroupPacing]).
     */
    private class PendingTick(
        val groupId: Long,
        val waitMs: Long,
        val anchor: Long,
        val intervalMs: Long,
        /** The stored anchor before any claim, for the "dispatch did not happen" restore. */
        val rawAnchor: Long,
        /**
         * True when this group's rhythm is the shared SCREEN clock: the tick
         * then claims/restores the screen anchor (its own row is only
         * bookkeeping for the round-robin order and the media cursor).
         */
        val usesScreenClock: Boolean = false,
    )

    /**
     * The group this screen-wide tick should be scoped to, or null when this
     * screen has nothing to show right now.
     *
     * `null` means "idle" only when a 时间规则 is what excludes every group (the
     * loop then re-checks in [HOME_IDLE_RECHECK_MS], so the group comes back on
     * its own when its window opens). When nothing is restricted the screen-wide
     * pick (groupId = 0) is used, exactly as before; when several groups are
     * inside their windows the one that waited longest goes first, so the
     * rotation stays fair.
     */
    private suspend fun activeScreenGroupId(
        db: AppDatabase,
        dao: SettingsDao,
        slot: String,
        groups: List<com.wallpaperswitcher.data.WallpaperGroup>,
        now: Long,
    ): Long? {
        if (groups.none { GroupRules.hasTimeRules(it) }) return 0L
        val scopedGroups = groups.filter { GroupRules.hasTimeRules(it) }
        val counts = try {
            db.groupPickDao().countsForSlot(slot).associate { it.groupId to it.mediaCount }
        } catch (e: Exception) {
            // The counts query failed: do NOT turn the whole schedule into an
            // idle loop over a transient database error - fall back to the
            // screen-wide pick and let the next tick try again.
            AppLog.w(TAG, "group counts failed (${e.javaClass.simpleName}); screen-wide pick")
            return 0L
        }
        val candidates = GroupRules.screenTickCandidates(scopedGroups, counts, slot, now)
        if (candidates.isEmpty()) return null
        val rows = try {
            db.groupScheduleDao().getAllForSlot(slot).associateBy { it.groupId }
        } catch (_: Exception) {
            emptyMap()
        }
        return candidates.minWithOrNull(
            compareBy({ rows[it.id]?.lastSwitchAt ?: 0L }, { it.id })
        )?.id ?: 0L
    }

    /**
     * The next tick of [slot].
     *
     * The SCREEN clock drives the pacing (one anchor, one interval) and every
     * tick carries `groupId = 0` - the screen-wide pick:
     *
     *  - 随机 / 洗牌 draw from ALL enabled groups (per-group filters and
     *    时间规则 applied when the pool is built, see MediaPick.eligibleGroups);
     *  - 顺序 walks the current group to its end, then the next one.
     *
     * Per-group tick rotation (GroupPacing / GroupSchedulePlan) was removed on
     * request: it made 随机/洗牌 degenerate into a strict group round-robin
     * (28→29→32→…) instead of drawing from the whole enabled set, and it kept
     * 顺序 from walking group by group. 时间规则 is still honoured - inside the
     * pick, by excluding groups that are not active right now.
     */
    private suspend fun nextScreenTick(
        db: AppDatabase,
        dao: SettingsDao,
        slot: String,
        groups: List<com.wallpaperswitcher.data.WallpaperGroup>,
        globalIntervalKey: String,
        anchorKey: String,
        now: Long = System.currentTimeMillis(),
    ): PendingTick? {
        val globalInterval = dao.getLong(globalIntervalKey, 60_000L)
        // Stay idle (null tick) when nothing can be shown for this screen right
        // now: no enabled group has media for the slot, or every group with
        // media is outside its 时间规则 window. The pick itself would return
        // nothing; idling keeps the loop quiet instead of waking every interval.
        val anyCandidate = try {
            val counts = db.groupPickDao().countsForSlot(slot)
                .associate { it.groupId to it.mediaCount }
            groups.any { group ->
                (counts[group.id] ?: 0) > 0 && GroupRules.isActiveAt(group, now)
            }
        } catch (_: Exception) {
            true
        }
        if (!anyCandidate) return null
        val anchor = currentScheduleAnchor(dao, anchorKey, globalInterval)
        return PendingTick(
            groupId = 0L,
            waitMs = SwitchSchedule.waitMs(anchor, globalInterval, now),
            anchor = anchor,
            intervalMs = globalInterval,
            rawAnchor = anchor,
        )
    }

    /**
     * Claim a tick BEFORE dispatching it: a loop restart (screen-on / settings
     * change) cancels this coroutine, and without the early claim the fresh
     * loop would still see the tick as due and switch twice in a row.
     */
    private suspend fun claimTick(
        db: AppDatabase,
        dao: SettingsDao,
        tick: PendingTick,
        slot: String,
        anchorKey: String,
        at: Long,
    ) {
        // The shared screen clock moves for the screen-wide tick and for any
        // group that follows the global interval.
        if (tick.groupId <= 0L || tick.usesScreenClock) {
            moveScheduleAnchor(dao, anchorKey, at)
        }
        if (tick.groupId > 0L) {
            try {
                val scheduleDao = db.groupScheduleDao()
                scheduleDao.ensureRow(tick.groupId, slot)
                scheduleDao.updateLastSwitchAt(tick.groupId, slot, at)
            } catch (e: Exception) {
                AppLog.d(TAG, "group schedule write failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Undo [claimTick] when the dispatch did not happen after all. */
    private suspend fun restoreTick(
        db: AppDatabase,
        dao: SettingsDao,
        tick: PendingTick,
        slot: String,
        anchorKey: String,
    ) {
        if (tick.groupId <= 0L || tick.usesScreenClock) {
            moveScheduleAnchor(dao, anchorKey, tick.anchor)
        }
        if (tick.groupId > 0L) {
            try {
                db.groupScheduleDao()
                    .updateLastSwitchAt(tick.groupId, slot, tick.rawAnchor)
            } catch (e: Exception) {
                AppLog.d(TAG, "group schedule restore failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Clears the "pause" flag for the caller's log bookkeeping (see ON_PAUSE). */
    private suspend fun pauseRemainingMs(dao: SettingsDao): Long {
        val until = try {
            dao.getLong(SettingsKeys.PAUSE_UNTIL, 0L)
        } catch (_: Exception) {
            0L
        }
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * Keep the notification's pause action in sync with the real hold state.
     *
     * Both timer loops already read `pauseRemainingMs` every iteration, so they
     * hand the observed state over instead of querying the database again. The
     * guard makes this a no-op in the common (unchanged) case.
     */
    private fun syncNotificationPause(paused: Boolean) {
        if (notificationPaused == paused) return
        notificationPaused = paused
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.notify(NOTIFICATION_ID, createNotification(paused))
        } catch (t: Throwable) {
            AppLog.w(TAG, "notification refresh failed: ${t.javaClass.simpleName}")
        }
    }

    /** Notification 「暂停 1 小时 / 继续」: same 一键暂停 key as the home card. */
    private suspend fun togglePauseFromNotification() {
        try {
            val dao = AppDatabase.getInstance(applicationContext).settingsDao()
            val now = System.currentTimeMillis()
            val paused = dao.getLong(SettingsKeys.PAUSE_UNTIL) > now
            if (paused) {
                dao.setLong(SettingsKeys.PAUSE_UNTIL, 0L)
                AppLog.d(TAG, "Notification: resume")
            } else {
                dao.setLong(SettingsKeys.PAUSE_STARTED_AT, now)
                dao.setLong(SettingsKeys.PAUSE_UNTIL, now + NOTIFICATION_PAUSE_MS)
                AppLog.d(TAG, "Notification: pause for ${NOTIFICATION_PAUSE_MS}ms")
            }
            syncNotificationPause(!paused)
            wakeLoopsInPlace()
        } catch (t: Throwable) {
            AppLog.e(TAG, "notification pause toggle failed", t)
        }
    }

    /**
     * Independent timer for the LOCK screen.
     *
     * Own interval + anchor, paused while the screen is off like the home loop
     * (a sleeping device cannot run user-space timers, so a locked-time tick
     * would only be replayed as a catch-up on screen-on; both anchors restart on
     * screen-on instead). It picks only from the groups whose 应用位置 includes
     * 锁屏 and writes only the lock wallpaper slot; the home loop never touches
     * the lock screen, so the two screens rotate completely independently.
     */
    private suspend fun runLockSwitchLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val dao = db.settingsDao()
                if (!dao.getBool(SettingsKeys.LOCK_TIMER_ENABLED, true)) {
                    AppLog.d(TAG, "Lock timer disabled, stopping lock loop")
                    // Stop the whole service when the home timer is off too:
                    // nothing needs it anymore.
                    if (!dao.getBool(SettingsKeys.SERVICE_ENABLED, false)) {
                        stopSelf()
                    }
                    return
                }
                // Screen off / locked: pause as well. A sleeping device does not
                // run user-space timers (no wakelock, the CPU suspends), so a tick
                // due while locked cannot fire anyway - it would only be replayed
                // as a catch-up on screen-on. Stopping the loop makes "nothing
                // runs while locked" explicit, and the anchor is restarted on
                // screen-on so the locked time does not count either.
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isInteractive != true) {
                    AppLog.d(TAG, "Screen not interactive, pausing lock timer")
                    return
                }
                // 锁屏不可见时的策略（用户需求）：亮屏期间**定时到点仍然切一次**，
                // 然后只要锁屏还是不可见就保持空闲——既不会让锁屏壁纸在长时间亮屏
                // 使用后完全停在旧图上，也不会每个间隔都为一个看不见的槽位做一次
                // 全分辨率解码+编码。定时到点前不写入；下一次亮屏（或锁屏重新可见）
                // 才会再允许这一"一次"。
                val lockHidden = !isLockScreenShowing()
                if (lockHidden && lockHiddenSwitchDone) {
                    if (!lockHiddenIdleAnnounced) {
                        lockHiddenIdleAnnounced = true
                        AppLog.d(
                            TAG,
                            "Lock screen not showing: lock timer idle (one switch already done)"
                        )
                    }
                    delay(LOCK_HIDDEN_RECHECK_MS)
                    continue
                }
                if (!lockHidden) {
                    // The lock screen is on display (or an app is showing it):
                    // normal timed behaviour, and the next hidden episode gets
                    // its own single switch.
                    lockHiddenIdleAnnounced = false
                    lockHiddenSwitchDone = false
                }
                // The system live-wallpaper dialog is open (its preview engine
                // is alive): hold the tick without claiming it, so it fires as
                // soon as the dialog closes. A lock write while the picker is
                // applying a wallpaper disturbs that flow the same way a home
                // write does.
                if (LiveWallpaperService.isPreviewDialogOpen()) {
                    delay(PREVIEW_DIALOG_HOLD_WAIT_MS)
                    continue
                }
                // No group targets the lock screen (yet): stay alive but idle so
                // changing a group's 应用位置 starts switching without a restart.
                if (db.wallpaperImageDao()
                        .countByEnabledGroups(WallpaperTarget.SLOT_LOCK) == 0
                ) {
                    // Nothing targets the lock screen: stay alive but idle.
                    // This used to re-check every 15s; the loop is restarted by
                    // poke() whenever a group, its target or its media change,
                    // so one long wait is enough (10 minutes is a safety net in
                    // case a poke was missed).
                    delay(LOCK_IDLE_WAIT_MS)
                    continue
                }
                // NOTE: the lock timer deliberately ignores the manual-pick
                // hold. Manually setting a lock wallpaper only re-anchors its
                // schedule (one full interval, see the ViewModel), so the
                // periodic lock switching keeps working while a freshly set
                // image still gets a full interval on screen. Holding it here
                // made the lock timer look "broken" whenever the user set a few
                // lock images in a row (each pick renewed the hold).
                // The 一键暂停 hold applies to the lock timer too: "稍后切换"
                // means both screens stay as they are.
                val pausedLeft = pauseRemainingMs(dao)
                syncNotificationPause(pausedLeft > 0L)
                if (pausedLeft > 0L) {
                    delay(pausedLeft.coerceAtMost(PAUSE_RECHECK_MS))
                    continue
                }
                val lockTick = nextScreenTick(
                    db,
                    dao,
                    WallpaperTarget.SLOT_LOCK,
                    db.wallpaperGroupDao().getEnabledGroupsSync()
                        .filter { WallpaperTarget.fromName(it.target).includesLock },
                    SettingsKeys.LOCK_INTERVAL_MS,
                    SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS
                )
                if (lockTick == null) {
                    // Every lock group is media-less / outside its window: stay
                    // alive and let a poke or the safety re-check pick it up.
                    delay(LOCK_IDLE_WAIT_MS)
                    continue
                }
                if (lockTick.waitMs > 0L) {
                    delay(lockTick.waitMs)
                    continue
                }
                // A lock tick shares the static-apply guard with the home one.
                // Instead of dropping it after 2s (which pushed the whole
                // schedule later and later when both timers kept colliding),
                // wait for the guard in short steps.
                var lockGuardHeld = false
                var lockGuardWaited = 0L
                // Same backoff as withStaticApply: see there.
                var lockGuardStep = STATIC_APPLY_WAIT_STEP_MS
                while (!lockGuardHeld) {
                    if (staticApplyInProgress.compareAndSet(false, true)) {
                        lockGuardHeld = true
                        break
                    }
                    // Clamp every step to what is left of the budget. The loop used to
                    // delay a whole step and only check afterwards, so the documented 6s
                    // ceiling was really ~9.25s (250 + 500 + 1000x7 before the check
                    // tripped) - and the user waits on this path.
                    val remaining = STATIC_APPLY_WAIT_MAX_MS - lockGuardWaited
                    if (remaining <= 0L) break
                    val sleep = lockGuardStep.coerceAtMost(remaining)
                    delay(sleep)
                    lockGuardWaited += sleep
                    lockGuardStep = (lockGuardStep * 2).coerceAtMost(STATIC_APPLY_WAIT_STEP_MAX_MS)
                }
                if (!lockGuardHeld) {
                    AppLog.d(TAG, "Static wallpaper apply in progress, postponing lock tick")
                    delay(2_000L)
                    continue
                }
                try {
                    // Claim this tick BEFORE applying: restarting the loop
                    // (screen-on / settings change) cancels the old coroutine,
                    // and without the early claim the fresh loop would see the
                    // tick as still due and switch the lock twice in a row.
                    withContext(NonCancellable) {
                        claimTick(
                            db,
                            dao,
                            lockTick,
                            WallpaperTarget.SLOT_LOCK,
                            SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS,
                            System.currentTimeMillis()
                        )
                    }
                    val applied = WallpaperApplier.applyNext(
                        applicationContext,
                        WallpaperTarget.SLOT_LOCK,
                        android.app.WallpaperManager.FLAG_LOCK,
                        lockTick.groupId
                    )
                    if (applied != null) {
                        AppLog.d(
                            TAG,
                            if (lockHidden) {
                                "Lock wallpaper switched (lock screen not showing: the one " +
                                    "allowed switch of this screen-on)"
                            } else {
                                "Lock wallpaper switched"
                            }
                        )
                        // NOTE: no recordLockWrite() here any more. applyNext()
                        // already recorded this write through
                        // WallpaperApplier.recordWrite() (LAST_LOCK_WRITE_ID/AT were
                        // written twice per tick, i.e. two extra SQLite commits for
                        // the same values).
                        if (lockHidden) {
                            // Used up this screen-on's single hidden switch: stay
                            // idle until the lock screen shows again or the screen
                            // is switched off and back on.
                            lockHiddenSwitchDone = true
                        }
                    } else {
                        AppLog.e(TAG, "Lock wallpaper switch failed (no media?)")
                    }
                } finally {
                    staticApplyInProgress.set(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Lock switch loop error", e)
                delay(10_000L)
            }
        }
    }

    /**
     * True when the keyguard (lock screen) is currently showing, i.e. when a
     * write to the lock wallpaper slot can actually be seen (see the gate in
     * [runLockSwitchLoop]).
     *
     * Called only while the screen is already interactive, so "locked" here
     * means the lock screen is on display and not that the device is asleep.
     * A keyguard service that throws (or is missing on an odd ROM) must never
     * disable lock switching, so it defaults to true.
     */
    private fun isLockScreenShowing(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return true
        return try {
            km.isKeyguardLocked
        } catch (_: Exception) {
            true
        }
    }

    /**
     * True when the HOME wallpaper is actually on display: the screen is
     * interactive (checked by the caller) and the keyguard is not covering it.
     *
     * The default on an unreadable keyguard state is "visible": a ROM whose
     * keyguard service throws must never stop home switching.
     */
    private fun isHomeScreenVisible(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return true
        return try {
            !km.isKeyguardLocked
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Wait (bounded) until the live wallpaper can present a frame. Right after
     * the screen comes back its EGL surface is still being recreated, and a
     * switch started then is dropped while the engine still records the media
     * as displayed, which leaves the wallpaper blank. No-op in static mode.
     *
     * @return false when the surface did not become ready in time (the caller
     *   must then not switch).
     */
    private suspend fun awaitRenderSurface(catchUp: Boolean): Boolean {
        if (!LiveWallpaperService.engineRunning) return true
        if (LiveWallpaperService.isRenderSurfaceReady()) return true
        val maxWait = if (catchUp) SURFACE_READY_CATCH_UP_WAIT_MS else SURFACE_READY_NORMAL_WAIT_MS
        // Hoisted: the power manager does not change while we wait, and the old
        // loop called getSystemService() (a binder round trip) on every 250ms
        // iteration.
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        var waited = 0L
        // Exponential backoff (250 -> 500 -> 1000ms, capped): the surface normally
        // appears within a few hundred ms, so the first checks stay fast, while a
        // slow recreation no longer costs 4 wakeups per second for up to 15s (a
        // wait that is itself retried up to SURFACE_RETRY_LIMIT times).
        var step = SURFACE_READY_POLL_MS
        while (waited < maxWait) {
            if (pm?.isInteractive != true) return false
            delay(step)
            waited += step
            if (LiveWallpaperService.isRenderSurfaceReady()) {
                AppLog.d(TAG, "Live surface ready after ${waited}ms; switching")
                return true
            }
            step = (step * 2).coerceAtMost(SURFACE_READY_POLL_MAX_MS)
        }
        AppLog.w(TAG, "Live surface not ready after ${waited}ms")
        return false
    }

    /**
     * Route a switch to wherever it can be rendered:
     * - Live wallpaper engine running -> broadcast to the engine.
     * - Otherwise -> apply a static wallpaper via WallpaperManager.
     *
     * @return false only when the screen is off, so nothing was dispatched and
     *   the caller must keep the tick pending. A static apply that is skipped
     *   because another one is already running counts as dispatched: the
     *   in-flight apply is the wallpaper change for this interval.
     */
    private fun sendSwitch(source: String, groupId: Long = 0L): Boolean {
        // Screen off: nobody can see the result, and the live engine is in
        // power-save anyway. The tick is dropped; the interval restarts when
        // the screen comes back on (see the screen-state receiver).
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (!pm.isInteractive) {
            val now = SystemClock.elapsedRealtime()
            val lastLog = lastScreenOffLogAt.get()
            if (now - lastLog > 60_000L && lastScreenOffLogAt.compareAndSet(lastLog, now)) {
                AppLog.d(TAG, "Screen off, skipping $source switch (power save)")
            }
            return false
        }
        AppLog.d(TAG, "sendSwitch($source) engineRunning=${LiveWallpaperService.engineRunning}")
        if (LiveWallpaperService.engineRunning ||
            LiveWallpaperService.isHomeLiveWallpaper(applicationContext)
        ) {
            // The live wallpaper owns the home screen: let the engine switch
            // the media it displays. The lock screen has its own loop/settings
            // and is not touched here.
            //
            // The engine flag can be momentarily false while the wallpaper is
            // still live (the ROM killed the engine process in the background,
            // or a preview engine just tore down). Falling back to a static
            // write there would REPLACE the live wallpaper, so the broadcast is
            // sent either way - the engine applies LAST_IMAGE_ID when it comes
            // back up.
            sendSwitchBroadcast(source, groupId)
            return true
        }
        launchStaticTick(
            "home tick", WallpaperTarget.SLOT_HOME, android.app.WallpaperManager.FLAG_SYSTEM,
            groupId
        )
        return true
    }

    /**
     * Run one static tick for a single screen on a background coroutine.
     *
     * The timer loop does not wait for the apply, so a slow (e.g. cloud) bitmap
     * decode must never overlap the next tick's apply: two concurrent
     * setBitmap calls could corrupt the wallpaper and waste resources.
     *
     * Because home and lock share that one guard, a tick that loses the race
     * must WAIT for it instead of being dropped: with both timers on the same
     * interval they come due at the same instant (they are anchored together
     * after a reboot), and dropping the loser left the home wallpaper stuck on
     * the same image forever. After a bounded wait the tick is skipped so the
     * loop can never pile up.
     */
    private fun launchStaticTick(reason: String, slot: String, which: Int, groupId: Long = 0L) {
        scope.launch {
            try {
                val result = withStaticApply(STATIC_APPLY_WAIT_MAX_MS) {
                    runStaticTick(applicationContext, slot, which, groupId)
                }
                if (result == null) {
                    AppLog.d(TAG, "Static wallpaper apply still busy, skipping $reason")
                    return@launch
                }
                when (result.outcome) {
                    WallpaperApplier.StaticTickOutcome.APPLIED ->
                        AppLog.d(TAG, "Static wallpaper switched ($reason)")
                    // The screen already showed that media: nothing was written,
                    // so this must not read as a switch in the exported log.
                    WallpaperApplier.StaticTickOutcome.ALREADY_SHOWING ->
                        AppLog.d(TAG, "Static wallpaper already showing ($reason), nothing written")
                    // Debug, not error: "no media targets this screen" is a
                    // normal state (e.g. every group is lock-only) and the real
                    // failures (unreadable file, decode error) are already
                    // logged with their cause by WallpaperApplier.
                    WallpaperApplier.StaticTickOutcome.NO_MEDIA ->
                        AppLog.d(TAG, "Static wallpaper not switched ($reason): no media for $slot")
                    WallpaperApplier.StaticTickOutcome.FAILED ->
                        AppLog.d(TAG, "Static wallpaper not switched ($reason)")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // Never let a database/decode failure crash the service:
                // the timer keeps running and retries next tick.
                AppLog.e(TAG, "Static wallpaper switch crashed ($reason)", e)
            }
        }
    }

    /** Send the live-wallpaper switch trigger to our engine. */
    private fun sendSwitchBroadcast(source: String, groupId: Long = 0L) {
        val intent = Intent(LiveWallpaperService.ACTION_SWITCH)
        intent.setPackage(applicationContext.packageName)
        intent.putExtra(LiveWallpaperService.EXTRA_SOURCE, source)
        if (groupId > 0L) {
            intent.putExtra(LiveWallpaperService.EXTRA_GROUP_ID, groupId)
        }
        applicationContext.sendBroadcast(intent)
        AppLog.d(TAG, "Switch broadcast sent ($source)")
    }

    private fun createNotification(paused: Boolean = notificationPaused): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val switchIntent = PendingIntent.getForegroundService(
            this, REQUEST_NOTIFY_SWITCH,
            Intent(this, WallpaperSwitchService::class.java).setAction(ACTION_NOTIFY_SWITCH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getForegroundService(
            this, REQUEST_NOTIFY_PAUSE,
            Intent(this, WallpaperSwitchService::class.java).setAction(ACTION_NOTIFY_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Localised through the chosen language, not the system one: the service
        // runs outside composition and its context would otherwise keep the
        // system locale (see AppLocale.localized).
        val localized = com.wallpaperswitcher.ui.AppLocale.localized(this)
        return NotificationCompat.Builder(this, WallpaperSwitcherApp.CHANNEL_ID)
            .setContentTitle(localized.getString(R.string.notification_title))
            .setContentText(localized.getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_wallpaper_thumb)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_notif_next,
                localized.getString(R.string.notification_action_next),
                switchIntent
            )
            .addAction(
                if (paused) R.drawable.ic_notif_play else R.drawable.ic_notif_pause,
                localized.getString(
                    if (paused) R.string.tile_resume_label
                    else R.string.notification_action_pause
                ),
                pauseIntent
            )
            .build()
    }

    companion object {
        private const val TAG = "WallpaperSwitchService"
        /** Notification action: 下一张 (same entry as the home card / tile). */
        private const val ACTION_NOTIFY_SWITCH =
            "com.wallpaperswitcher.action.NOTIFY_SWITCH"
        /** Notification action: 暂停 1 小时 / 立即继续. */
        private const val ACTION_NOTIFY_TOGGLE_PAUSE =
            "com.wallpaperswitcher.action.NOTIFY_TOGGLE_PAUSE"
        private const val REQUEST_NOTIFY_SWITCH = 101
        private const val REQUEST_NOTIFY_PAUSE = 102
        /** The notification's pause action pauses for an hour (tile/widget: 24h). */
        private const val NOTIFICATION_PAUSE_MS = 60L * 60 * 1000
        /**
         * Fire-and-forget bookkeeping (anchor moves, unlock notifications): its
         * own scope so it survives an activity/service restart, with the same
         * failure guard as [scope] (an uncaught coroutine exception would kill
         * the process, which is shared with the UI).
         */
        private val ioScope = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + com.wallpaperswitcher.util.logCoroutineFailures(TAG)
        )
        private const val NOTIFICATION_ID = 1001
        // Require several consecutive empty-group reads before stopping, so a
        // transient DB state can never kill the timer on its own.
        private const val EMPTY_GROUP_STOP_THRESHOLD = 3
        // Bounded wait for the live surface (a switch rendered into a surface
        // that is still being recreated is dropped and leaves the wallpaper
        // blank). Catch-ups get a longer budget than normal ticks.
        private const val SURFACE_READY_POLL_MS = 250L
        /**
         * Ceiling of the exponential backoff [awaitRenderSurface] applies while it
         * waits for the EGL surface (see there).
         */
        private const val SURFACE_READY_POLL_MAX_MS = 1_000L
        private const val SURFACE_READY_NORMAL_WAIT_MS = 5_000L
        private const val SURFACE_READY_CATCH_UP_WAIT_MS = 15_000L
        // A catch-up whose surface never came back is retried a few times
        // before it is given up, instead of being lost on the first timeout.
        private const val SURFACE_RETRY_LIMIT = 3
        private const val SURFACE_RETRY_DELAY_MS = 5_000L
        // How long an overdue tick waits for ACTION_USER_PRESENT before it
        // decides that "解锁切换" is not going to handle this unlock.
        private const val UNLOCK_COORDINATION_GRACE_MS = 3_000L
        // Safety re-check while the lock timer has nothing to switch (no group
        // targets the lock screen). Any relevant change calls poke(), which
        // restarts the loops immediately - this is only a fallback for a missed
        // poke, so it can be long (was a 15s poll).
        // Safety re-check while the lock timer has nothing to switch (no group
        // targets the lock screen). Any relevant change calls poke(), which
        // restarts the loops immediately - this is only a fallback for a missed
        // poke, so it can be long (was a 15s poll).
        private const val LOCK_IDLE_WAIT_MS = 10 * 60_000L
        /**
         * Safety re-check while NO group can switch for a screen (every group
         * media-less or outside its 时间规则 window). Any relevant change calls
         * poke(), which restarts the loop immediately; this is only a fallback
         * for a missed poke - and it is also what notices a group becoming
         * active when its time window opens.
         */
        private const val HOME_IDLE_RECHECK_MS = 60_000L
        /**
         * How often the loops re-check a 一键暂停 hold. The hold never
         * consumes the tick, so this is purely how late the catch-up switch can
         * be after the pause expires while the app stayed closed.
         */
        private const val PAUSE_RECHECK_MS = 30_000L
        /**
         * How often the HOME loop re-checks whether the desktop is visible while
         * the lock screen covers it (see the A1 gate in [runSwitchLoop]).
         *
         * One keyguard lookup per minute, only while the screen is on AND the
         * keyguard is showing, next to a full-resolution decode + JPEG encode +
         * wallpaper write per tick that the gate avoids. The tick is not consumed,
         * so the desktop-gone-visible transition switches immediately.
         */
        /**
         * How often the HOME loop re-checks whether the desktop is visible while
         * the lock screen covers it (see the A1 gate in [runSwitchLoop]).
         *
         * 5s, not the 60s this used to be: the state it waits for (the keyguard
         * going away) is what the user is doing right then, and ROMs report
         * `isKeyguardLocked == true` for a moment after an unlock - a 60s re-check
         * turn that into a minute in which the desktop timer looks dead. The check
         * itself is one keyguard binder call plus one DB read, and it only runs
         * while the screen is on AND the keyguard is showing, which is normally a
         * couple of seconds. The tick is not consumed, so the desktop switches as
         * soon as the check passes.
         */
        private const val HOME_HIDDEN_RECHECK_MS = 5_000L
        /**
         * How often the home timer re-checks whether our app is still in the
         * foreground while it is idle (the desktop timer does not switch at all
         * while the app is open).
         *
         * Only a safety net: MainActivity.onStop() pokes this service, so leaving
         * the app resumes the timer (and fires the overdue catch-up switch)
         * immediately; this covers a missed poke (the activity killed without
         * onStop, an OEM that skips it). The app being in the foreground means the
         * screen is on and the user is interacting, so a 5s check (one DB read plus
         * one static field) costs nothing measurable.
         */
        private const val HOME_APP_HIDDEN_RECHECK_MS = 5_000L
        /**
         * How often the lock loop re-checks the keyguard while the lock screen is
         * NOT showing (see the "锁屏不可见时不应用" gate in
         * [runLockSwitchLoop]). One keyguard lookup per minute costs nothing
         * measurable next to the wallpaper write it avoids every interval, and
         * it keeps lock switching alive on ROMs that do not deliver
         * ACTION_SCREEN_ON reliably. The screen-on receiver restarts the loop
         * (and its interval) anyway, so this is only a safety net.
         */
        private const val LOCK_HIDDEN_RECHECK_MS = 60_000L
        // While the system live-wallpaper dialog is open both loops hold the
        // tick. The preview engine's onDestroy pokes the service when the dialog
        // closes, so this wait only has to cover a missed poke (was a 2s poll;
        // each iteration also re-anchors, so the interval still starts when the
        // dialog is gone).
        private const val PREVIEW_DIALOG_HOLD_WAIT_MS = 30_000L
        // Shared guard so the timer loop and a manual "switch now" can never
        // apply two static wallpapers at the same time.
        private val staticApplyInProgress = AtomicBoolean(false)
        // How long a losing static tick waits for the shared guard before it
        // gives up (see launchStaticTick): long enough to outlast a normal
        // bitmap decode + setBitmap, short enough to never pile up ticks.
    private const val STATIC_APPLY_WAIT_MAX_MS = 6_000L
    private const val STATIC_APPLY_WAIT_STEP_MS = 250L
    /** Ceiling of the backoff [withStaticApply] uses while the guard is busy. */
    private const val STATIC_APPLY_WAIT_STEP_MAX_MS = 1_000L
    /**
     * How long a manual live-wallpaper pick stays valid for the slot
     * enforcement. The dialog closes and the engine is created within a couple
     * of seconds; the window only has to be long enough to survive a slow
     * confirmation screen, and short enough that a later, unrelated engine
     * creation cannot resurrect an old pick.
     */
    private const val MANUAL_PICK_FRESH_MS = 3 * 60_000L

        /**
         * Run [block] while holding the shared [staticApplyInProgress] guard.
         * When the guard is busy, wait up to [waitMs] for it - a user-visible
         * tap must not be dropped - or give up immediately (waitMs = 0).
         *
         * @return null when the guard stayed busy, so the caller can skip.
         */
        private suspend fun <T> withStaticApply(waitMs: Long, block: suspend () -> T): T? {
            var waited = 0L
            // Backoff instead of a fixed 250ms step: a losing tick normally gets
            // the guard within a few hundred ms (one small read), so the first
            // waits stay short, but a long apply (a cloud media at its 15s load
            // timeout) no longer costs 4 wakeups per second for the whole wait.
            var step = STATIC_APPLY_WAIT_STEP_MS
            while (!staticApplyInProgress.compareAndSet(false, true)) {
                // Clamp the step to the remaining budget (same reason as the lock tick
                // loop): delaying a whole step and only checking afterwards made the
                // documented 6s ceiling ~9.25s (250 + 500 + 1000x7), and the manual
                // "switch now" tap waits here.
                val remaining = waitMs - waited
                if (remaining <= 0L) return null
                val sleep = step.coerceAtMost(remaining)
                delay(sleep)
                waited += sleep
                step = (step * 2).coerceAtMost(STATIC_APPLY_WAIT_STEP_MAX_MS)
            }
            try {
                return block()
            } finally {
                staticApplyInProgress.set(false)
            }
        }
        // True while this service is alive in the current process. Used by
        // ensureRunning() to self-heal after Android 15+ kills a long-running
        // foreground service in the background.
        @Volatile
        var running = false
            private set
        /**
         * The live instance in this process (null once it is destroyed), so a
         * settings change can re-evaluate the loops in place - see [poke].
         * Without it every poke went through startForegroundService(), which
         * re-ran startForeground()/createNotification() and cancelled and
         * re-created both timer coroutines (ten such starts in six seconds in
         * the tablet log while the user was flipping switches).
         */
        @Volatile
        private var activeInstance: WallpaperSwitchService? = null
        // elapsedRealtime of the last unlock switch dispatched by
        // ScreenUnlockReceiver. The timer uses it to hand an unlock's first
        // switch to that path instead of switching twice.
        @Volatile
        private var unlockSwitchDispatchedAt = 0L

        /**
         * One static switch tick for a single screen: pick the next media from
         * the groups whose 应用位置 includes [slot] and write it to [which].
         * Home and lock are switched by their own triggers (timers, double tap,
         * unlock, manual button), so they never depend on each other.
         *
         * The HOME slot is never overwritten while OUR live wallpaper is the
         * active one, because that would silently replace it (the user then has
         * to set the live wallpaper up again). The lock slot is unaffected - a
         * static lock image alongside the live home wallpaper is the intended
         * design.
         *
         * @return true when a wallpaper was applied.
         */
        internal suspend fun runStaticTick(
            context: Context,
            slot: String,
            which: Int,
            groupId: Long = 0L
        ): WallpaperApplier.StaticTickResult {
            // Belt-and-suspenders for the paths that are not the timer loops
            // (manual "switch now", unlock switch): never write a wallpaper
            // while the system live-wallpaper dialog is open.
            if (LiveWallpaperService.isPreviewDialogOpen()) {
                AppLog.d(TAG, "System live-wallpaper dialog open; skipping static $slot write")
                return WallpaperApplier.StaticTickResult(WallpaperApplier.StaticTickOutcome.FAILED)
            }
            if (slot == WallpaperTarget.SLOT_HOME &&
                LiveWallpaperService.isHomeLiveWallpaper(context)
            ) {
                AppLog.d(TAG, "Home is our live wallpaper; skipping static home write")
                return WallpaperApplier.StaticTickResult(WallpaperApplier.StaticTickOutcome.FAILED)
            }
            return WallpaperApplier.applyNextOutcome(context, slot, which, groupId)
        }

        /**
         * Run one static tick right now from outside the service (used by the
         * unlock receiver in static-wallpaper mode, where there is no live
         * engine to broadcast to). Shares the [staticApplyInProgress] guard so
         * it can never overlap a timed static apply.
         */
        internal suspend fun applyStaticTickNow(
            context: Context,
            slot: String,
            which: Int,
            groupId: Long = 0L
        ): Boolean {
            return try {
                val applied = withStaticApply(0L) { runStaticTick(context, slot, which, groupId) }
                if (applied == null) {
                    AppLog.d(TAG, "Static wallpaper apply already in progress, skipping $slot tick")
                    false
                } else {
                    // "Already showing that media" counts as applied: the screen
                    // does show what the caller asked for.
                    applied.outcome == WallpaperApplier.StaticTickOutcome.APPLIED ||
                        applied.outcome == WallpaperApplier.StaticTickOutcome.ALREADY_SHOWING
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLog.e(TAG, "Static $slot tick failed", e)
                false
            }
        }

        /**
         * Forget a pending manual live-wallpaper pick.
         *
         * [WallpaperViewModel.setAsLiveWallpaper] records the media the user
         * tapped so the slot enforcement can write exactly that image to the lock
         * screen after the system dialog closes. If the user cancels the dialog
         * instead, that record must not survive: the next engine recreation (a
         * system wallpaper restart, a reboot) would otherwise apply it and change
         * the lock screen behind the user's back. Called by the preview engine's
         * teardown when no real engine took over (see LiveWallpaperService).
         */
        internal suspend fun clearManualPick(context: Context, expectedPickedAt: Long = -1L) {
            try {
                val dao = AppDatabase.getInstance(context).settingsDao()
                if (dao.getLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, 0L) == 0L) return
                // Guard against "cancel the picker, then pick another media
                // within the grace window": that newer pick must survive. The
                // timestamp captured when the preview engine started identifies
                // the pick this cancellation belongs to.
                if (expectedPickedAt >= 0L &&
                    dao.getLong(SettingsKeys.MANUAL_PICK_AT, 0L) != expectedPickedAt
                ) {
                    AppLog.d(TAG, "Picker closed but a newer pick is pending; keeping it")
                    return
                }
                dao.setLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, 0L)
                AppLog.d(TAG, "Live wallpaper picker cancelled: cleared the pending pick")
            } catch (e: Exception) {
                AppLog.d(TAG, "clearManualPick failed: ${e.message}")
            }
        }

/**
         * How long a lock-screen write counts as "fresh" - within this window
         * the same media is not written to the lock slot again. Without it every
         * live-wallpaper apply (and every engine recreation) rewrote the same
         * lock image, which the device log showed as repeated
         * `Wallpaper applied (lock): <same file>` lines.
         */
        private const val LOCK_WRITE_REPEAT_WINDOW_MS = 5 * 60_000L

        private suspend fun lockWrittenRecently(dao: SettingsDao, imageId: Long): Boolean {
            if (imageId <= 0L) return false
            return try {
                val lastId = dao.getLong(SettingsKeys.LAST_LOCK_WRITE_ID, 0L)
                val lastAt = dao.getLong(SettingsKeys.LAST_LOCK_WRITE_AT, 0L)
                lastId == imageId &&
                    System.currentTimeMillis() - lastAt < LOCK_WRITE_REPEAT_WINDOW_MS
            } catch (_: Exception) {
                false
            }
        }

        private suspend fun recordLockWrite(dao: SettingsDao, imageId: Long) {
            WallpaperApplier.recordWrite(dao, WallpaperTarget.SLOT_LOCK, imageId)
        }

        private suspend fun applyLockAndRecord(
            dao: SettingsDao,
            context: Context,
            image: com.wallpaperswitcher.data.WallpaperImage
        ): Boolean {
            val (rotateMismatch, rotateClockwise) = WallpaperApplier.rotatePrefs(dao)
            val ok = WallpaperApplier.apply(
                context, image, android.app.WallpaperManager.FLAG_LOCK,
                rotateMismatch, rotateClockwise
            )
            if (ok) recordLockWrite(dao, image.id)
            return ok
        }
        internal suspend fun enforceSlotsAfterLiveApply(context: Context): Boolean {
            return try {
                // Bounded wait, not "give up immediately": a static apply can
                // be in flight exactly when the system dialog closes.
                withStaticApply(STATIC_APPLY_WAIT_MAX_MS) { enforceSlotsLocked(context) } ?: false
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLog.e(TAG, "enforceSlotsAfterLiveApply failed", e)
                false
            }
        }

        /** Body of [enforceSlotsAfterLiveApply]; the caller holds the guard. */
        /** Body of [enforceSlotsAfterLiveApply]; the caller holds the guard. */
        private suspend fun enforceSlotsLocked(context: Context): Boolean {
            val gateDb = AppDatabase.getInstance(context)
            val gateDao = gateDb.settingsDao()
            // 锁屏独立定时关着的时候，锁屏跟随动态壁纸（视频 / 图片都随桌面
            // 轮换）——清掉独立锁屏壁纸，系统会把动态壁纸也合成到锁屏上。
            // 开着的时候保持原来的独立锁屏图（只切换图片），见下面的分组逻辑。
            if (!gateDao.getBool(SettingsKeys.LOCK_TIMER_ENABLED, true)) {
                return clearLockForLiveWallpaper(context, gateDao)
            }

            val db = AppDatabase.getInstance(context)
            val dao = db.settingsDao()
            val imageDao = db.wallpaperImageDao()
            // The media the user tapped (and confirmed in the system dialog) is
            // the only reliable description of what just happened: the HOME
            // cursor may have been advanced by a home tick while the dialog was
            // open, which made a lock-group pick enforce the wrong image.
            val manualId = dao.getLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, 0L)
            val manualAt = dao.getLong(SettingsKeys.MANUAL_PICK_AT, 0L)
            val manualFresh =
                manualId > 0L &&
                    System.currentTimeMillis() - manualAt <= MANUAL_PICK_FRESH_MS
            val manual = if (manualFresh) imageDao.getImageById(manualId) else null
            if (manualId > 0L && manual == null) {
                // Expired, or the media was deleted: drop it so a later engine
                // recreation cannot re-apply a stale pick.
                dao.setLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, 0L)
            }
            val currentId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
            val current = manual
                ?: (if (currentId > 0L) imageDao.getImageById(currentId) else null)
            val currentTarget = WallpaperTarget.fromName(
                current?.let { db.wallpaperGroupDao().getGroupById(it.groupId)?.target }
            )
            val hasLockGroup = db.wallpaperGroupDao().getEnabledGroupsSync()
                .any { WallpaperTarget.fromName(it.target).includesLock }
            // The image the lock screen currently holds (a still one only:
            // the lock slot never shows video/GIF).
            val lastLockId = dao.getLong(SettingsKeys.LAST_IMAGE_ID_LOCK)
            val lastLock = if (lastLockId > 0L) {
                imageDao.getImageById(lastLockId)?.takeIf { it.mediaType == MediaTypes.IMAGE }
            } else {
                null
            }
            val enforced = when {
                // 锁屏 or 两者分组: the picked media belongs on the lock
                // screen, whatever option was chosen in the system dialog.
                current != null && currentTarget.includesLock -> {
                    if (lockWrittenRecently(dao, current.id)) {
                        AppLog.d(TAG, "Lock already shows this media, nothing to enforce")
                        true
                    } else if (current.mediaType != MediaTypes.IMAGE) {
                        // The lock screen is static: video/GIF media is
                        // skipped instead of freezing its first frame there.
                        AppLog.d(
                            TAG,
                            "Lock-targeted group (${currentTarget.nameValue}): " +
                                "${current.mediaType} skipped for the lock screen"
                        )
                        // Fall back to a still image so the lock screen is
                        // not left to the live wallpaper.
                        when {
                            lastLock != null ->
                                applyLockAndRecord(dao, context, lastLock)
                            hasLockGroup -> {
                                val id = WallpaperApplier.applyNext(
                                    context,
                                    WallpaperTarget.SLOT_LOCK,
                                    android.app.WallpaperManager.FLAG_LOCK
                                )
                                if (id != null) recordLockWrite(dao, id)
                                id != null
                            }
                            else -> false
                        }
                    } else {
                        val ok = applyLockAndRecord(dao, context, current)
                        AppLog.d(
                            TAG,
                            "Lock-targeted group (${currentTarget.nameValue}): " +
                                "picked media written to the lock screen=$ok"
                        )
                        ok
                    }
                }
                // 桌面分组: the lock screen must not be covered by the live
                // wallpaper. Restore the image it had - unless it was just
                // written (that is what made the log show the same lock
                // image being rewritten on every apply).
                lastLock != null && lockWrittenRecently(dao, lastLock.id) -> {
                    AppLog.d(TAG, "Lock screen already restored recently, nothing to enforce")
                    false
                }
                lastLock != null -> {
                    val ok = applyLockAndRecord(dao, context, lastLock)
                    AppLog.d(TAG, "Home-only group: previous lock image restored=$ok")
                    ok
                }
                // No usable lock image yet: take one from the lock groups.
                hasLockGroup -> {
                    val id = WallpaperApplier.applyNext(
                        context,
                        WallpaperTarget.SLOT_LOCK,
                        android.app.WallpaperManager.FLAG_LOCK
                    )
                    if (id != null) recordLockWrite(dao, id)
                    id != null
                }
                else -> {
                    AppLog.w(
                        TAG,
                        "Home-only live wallpaper was applied to both screens; " +
                            "no lock image available to restore"
                    )
                    false
                }
            }
            if (manual != null) {
                // Consumed: the next engine creation (rotation of a wallpaper
                // restart, a later pick) must not re-apply it.
                dao.setLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, 0L)
            }
            return enforced
        }
        /**
         * 锁屏跟随动态壁纸（锁屏独立定时关闭时）: remove the separate static lock
         * wallpaper so the system composites our LIVE wallpaper (with its videos)
         * on the lock screen too. Idempotent - it also recovers when another app
         * or the OEM re-sets a lock image behind our back.
         */
        private suspend fun clearLockForLiveWallpaper(
            context: Context,
            dao: SettingsDao,
        ): Boolean {
            return try {
                android.app.WallpaperManager.getInstance(context)
                    .clear(android.app.WallpaperManager.FLAG_LOCK)
                // The slot no longer holds one of our still images: forget the
                // write memo so switching the lock timer back on re-applies a
                // fresh image instead of believing the old one is still there.
                dao.setLong(SettingsKeys.LAST_LOCK_WRITE_ID, 0L)
                dao.setLong(SettingsKeys.LAST_LOCK_WRITE_AT, 0L)
                AppLog.d(
                    TAG,
                    "Lock follows the live wallpaper: static lock wallpaper cleared"
                )
                true
            } catch (t: Throwable) {
                AppLog.w(TAG, "Clearing the lock wallpaper failed: ${t.message}")
                false
            }
        }

        fun start(context: Context) {
            // minSdk 26 = Android 8.0: startForegroundService() is always
            // available (the service must then call startForeground()).
            try {
                context.startForegroundService(Intent(context, WallpaperSwitchService::class.java))
            } catch (e: Exception) {
                // Android 12+ can refuse a foreground-service start from the
                // background (e.g. a poke from the wallpaper engine while the UI
                // is not visible). The loops are restarted by the next trigger,
                // so this is logged instead of thrown into the caller.
                AppLog.w(TAG, "startForegroundService refused", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WallpaperSwitchService::class.java))
        }

        /**
         * Called by ScreenUnlockReceiver right after it broadcast the unlock
         * switch. The timer hands the unlock's first switch to that path (no
         * double switch) and the interval is re-anchored from now so the timer
         * does not fire again moments after an unlock.
         */
        fun notifyUnlockSwitchDispatched(context: Context) {
            unlockSwitchDispatchedAt = SystemClock.elapsedRealtime()
            val app = context.applicationContext
            ioScope.launch {
                try {
                    AppDatabase.getInstance(app)
                        .settingsDao()
                        .setLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, System.currentTimeMillis())
                } catch (e: Exception) {
                    AppLog.d(TAG, "unlock anchor write failed: ${e.javaClass.simpleName}")
                }
            }
        }

        /**
         * Cheap self-heal: Android 15+ can stop long-running foreground
         * services while the app is in the background (6h timeout / OEM power
         * killers). Any time the app returns to the foreground, restart the
         * timer if it is enabled but no longer running.
         */
        fun ensureRunning(context: Context) {
            if (running) return
            // The coroutine below runs on the process-lifetime ioScope, so it
            // must never capture the caller's (possibly Activity) context.
            val app = context.applicationContext
            ioScope.launch {
                try {
                    val dao = AppDatabase.getInstance(app).settingsDao()
                    // Either timer needs the service: the lock timer is
                    // independent of the home one (which may be off).
                    val needed = dao.getBool(SettingsKeys.SERVICE_ENABLED, false) ||
                        dao.getBool(SettingsKeys.LOCK_TIMER_ENABLED, true)
                    if (needed) {
                        AppLog.d(TAG, "A timer is enabled but the service is not running, restarting")
                        // Cold start from the app UI: begin a FRESH interval so
                        // merely opening the app can never change the wallpaper
                        // by itself. (The screen-on path deliberately does the
                        // opposite - it keeps the anchor so an overdue tick
                        // catches up after a lock.)
                        val now = System.currentTimeMillis()
                        dao.setLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, now)
                        dao.setLong(SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS, now)
                        start(app)
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "self-heal restart failed: ${e.javaClass.simpleName}")
                }
            }
        }

        /**
         * Wake both timer loops: a settings change (a group's 应用位置, a group
         * being enabled/disabled, new media) changes what a screen may show, so
         * re-evaluate now instead of waiting for the lock loop's idle poll or
         * the next interval. Starts the service when it is not running.
         *
         * When the service is already alive in this process the loops are
         * restarted in place. The old implementation called
         * startForegroundService() for that case too, so every settings change
         * re-ran startForeground()/createNotification() and replaced both timer
         * coroutines - the tablet log had ten "Service started, action=null"
         * lines inside six seconds while the user was flipping toggles, with no
         * work to show for it.
         */
        fun poke(context: Context) {
            // "What may be shown changed" is exactly when the SHUFFLE deck's
            // cached id list must be dropped (a group turned on/off, a target
            // changed, media imported): see MediaPick.enabledIdsFor.
            com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            val active = activeInstance
            if (active != null && active.wakeLoopsInPlace()) {
                AppLog.d(TAG, "poke: timer loops re-evaluated in place")
                return
            }
            if (running) {
                start(context)
            } else {
                ensureRunning(context)
            }
        }

        fun switchNow(context: Context, source: String = LiveWallpaperService.SOURCE_MANUAL) {
            // Manual "换一张" only touches the screen the user is looking at
            // (the home screen). The lock screen has its own timer and is never
            // changed by the home-side triggers.
            // The schedule is re-anchored so the timer cannot fire a moment
            // after this manual switch (the user just saw a new wallpaper).
            // Both coroutines below run on the process-lifetime ioScope, so they
            // must never capture the caller's (possibly Activity) context.
            val app = context.applicationContext
            ioScope.launch {
                try {
                    val db = AppDatabase.getInstance(app)
                    db.settingsDao()
                        .setLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, System.currentTimeMillis())
                } catch (e: Exception) {
                    AppLog.d(TAG, "manual-switch anchor write failed: ${e.javaClass.simpleName}")
                }
            }
            // 手动切换也要遵守分组自己的切换模式：屏幕按分组调度时，这一次点击
            // 就是"下一个到期的分组"的下一张（与定时切换、下一张预览完全一致），
            // 而不是走屏幕级取图用全局模式随机挑一张。
            // 手动切换跟随全局切换模式（屏幕级取图）：随机 / 洗牌在所有启用
            // 分组里取，顺序从当前图片逐组推进 —— 与定时切换、下一张预览一致。
            // 以前这里先挑一个分组再切，点击会退化成按分组轮转。
            dispatchManualSwitch(app, source, 0L)
            return
        }

        /** Send one manual switch of [groupId] (0 = the screen-wide pick). */
        private fun dispatchManualSwitch(app: Context, source: String, groupId: Long) {
            // `engineRunning` can be stale-false while OUR live wallpaper is
            // still the home wallpaper (the engine process was killed by the
            // OEM, or a preview engine just tore down). Writing a static home
            // wallpaper then would REPLACE the live wallpaper, so hand the
            // switch to the engine whenever it owns the home screen - not only
            // when its flag happens to be true.
            if (LiveWallpaperService.engineRunning ||
                LiveWallpaperService.isHomeLiveWallpaper(app)
            ) {
                // Home is the live wallpaper: ask the engine to display a new
                // media there.
                val intent = Intent(LiveWallpaperService.ACTION_SWITCH).apply {
                    putExtra(LiveWallpaperService.EXTRA_SOURCE, source)
                    if (groupId > 0L) putExtra(LiveWallpaperService.EXTRA_GROUP_ID, groupId)
                }
                intent.setPackage(app.packageName)
                app.sendBroadcast(intent)
                AppLog.d(TAG, "Switch ($source): engine handles the home screen (group=$groupId)")
                return
            }
            ioScope.launch {
                try {
                    // Bounded wait: a timer tick can hold the guard for the ~1s
                    // of a bitmap decode + setBitmap, and dropping the user's
                    // tap silently would look like a dead button.
                    val applied = withStaticApply(STATIC_APPLY_WAIT_MAX_MS) {
                        // Guarded tick: it refuses to overwrite our own live
                        // wallpaper even if the checks above raced a teardown.
                        runStaticTick(
                            app,
                            WallpaperTarget.SLOT_HOME,
                            android.app.WallpaperManager.FLAG_SYSTEM,
                            groupId
                        )
                    }
                    if (applied == null) {
                        AppLog.d(TAG, "Static wallpaper apply still busy, skipping switchNow")
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    AppLog.e(TAG, "Static wallpaper switch crashed", e)
                }
            }
        }

        /**
         * Apply a specific media item as the STATIC wallpaper in [which]
         * ([android.app.WallpaperManager.FLAG_SYSTEM] = home, `FLAG_LOCK` =
         * lock screen, or both OR-ed). Goes through the same
         * [staticApplyInProgress] guard as [switchNow], so a manual apply can
         * never overlap a timed static apply (two concurrent
         * WallpaperManager.setBitmap calls can corrupt the wallpaper).
         *
         * A user-visible tap waits (bounded) for a running timed apply instead
         * of being dropped silently, so the caller can tell "busy" apart from
         * "the media could not be read" and report the matching message.
         *
         * On success the screen's bookkeeping is updated as well
         * (LAST_IMAGE_ID / LAST_IMAGE_ID_LOCK / LAST_LOCK_WRITE_ID): the timed
         * loops continue from the media the user just picked, and
         * [enforceSlotsLocked] restores that pick instead of an older lock
         * image.
         */
        suspend fun applyStaticWallpaper(
            context: Context,
            targetId: Long,
            which: Int
        ): StaticApplyOutcome {
            // Refuse to replace our own live wallpaper on the home screen: the
            // caller runs this only because the engine flag looked false, which
            // can be stale (killed engine process, tearing-down preview engine).
            if (which == android.app.WallpaperManager.FLAG_SYSTEM &&
                LiveWallpaperService.isHomeLiveWallpaper(context)
            ) {
                AppLog.d(TAG, "Home is our live wallpaper; refusing static home write")
                return StaticApplyOutcome.FAILED
            }
            return try {
                val outcome = withStaticApply(STATIC_APPLY_WAIT_MAX_MS) {
                    val db = AppDatabase.getInstance(context)
                    val image = db
                        .wallpaperImageDao()
                        .getImageById(targetId)
                        ?: return@withStaticApply StaticApplyOutcome.FAILED
                    // Full-bleed decode + the user's rotation preference, exactly
                    // like the timed static ticks (see WallpaperApplier.apply).
                    val (rotateMismatch, rotateClockwise) =
                        WallpaperApplier.rotatePrefs(db.settingsDao())
                    val applied = withContext(Dispatchers.IO) {
                        WallpaperApplier.apply(
                            context, image, which, rotateMismatch, rotateClockwise
                        )
                    }
                    if (!applied) return@withStaticApply StaticApplyOutcome.FAILED
                    recordSlotWrite(context, image.id, which)
                    StaticApplyOutcome.APPLIED
                }
                if (outcome == null) {
                    AppLog.d(
                        TAG,
                        "Static wallpaper apply already in progress, skipping applyStaticWallpaper"
                    )
                    StaticApplyOutcome.BUSY
                } else {
                    outcome
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLog.e(TAG, "Static wallpaper apply crashed", e)
                StaticApplyOutcome.FAILED
            }
        }

        /**
         * Remember the media a manual apply just wrote, per screen.
         *
         * The timed loops use LAST_IMAGE_ID / LAST_IMAGE_ID_LOCK as their
         * sequential / shuffle cursor, and [enforceSlotsLocked] reads
         * LAST_LOCK_WRITE_* to know what the lock screen currently holds.
         * Without this, a manual lock pick was forgotten right away: the lock
         * timer continued from the older cursor and the next live-wallpaper
         * apply overwrote the pick with the previous lock image.
         */
        private suspend fun recordSlotWrite(context: Context, imageId: Long, which: Int) {
            try {
                val dao = AppDatabase.getInstance(context).settingsDao()
                if (which and android.app.WallpaperManager.FLAG_LOCK != 0) {
                    dao.setLong(SettingsKeys.LAST_IMAGE_ID_LOCK, imageId)
                    WallpaperApplier.recordWrite(dao, WallpaperTarget.SLOT_LOCK, imageId)
                }
                if (which and android.app.WallpaperManager.FLAG_SYSTEM != 0) {
                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, imageId)
                    WallpaperApplier.recordWrite(dao, WallpaperTarget.SLOT_HOME, imageId)
                }
            } catch (_: Exception) {
                // Bookkeeping only: never fail an already-applied wallpaper.
            }
        }

    }
}
