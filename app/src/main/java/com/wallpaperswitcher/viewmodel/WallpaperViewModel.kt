package com.wallpaperswitcher.viewmodel

import com.wallpaperswitcher.util.AppLog

import com.wallpaperswitcher.util.LogText

import android.app.Application

import android.net.Uri

import android.provider.MediaStore

import androidx.lifecycle.AndroidViewModel

import androidx.lifecycle.viewModelScope

import androidx.room.withTransaction

import androidx.work.Constraints

import androidx.work.ExistingPeriodicWorkPolicy

import androidx.work.ExistingWorkPolicy

import androidx.work.OneTimeWorkRequestBuilder

import androidx.work.PeriodicWorkRequestBuilder

import androidx.work.WorkManager

import com.wallpaperswitcher.WallpaperSwitcherApp

import com.wallpaperswitcher.data.*

import com.wallpaperswitcher.engine.FloatingButtonContentPolicy
import com.wallpaperswitcher.engine.MediaScanner

import com.wallpaperswitcher.engine.MediaProbe

import com.wallpaperswitcher.engine.MediaTypes

import com.wallpaperswitcher.engine.clearMediaCursors

import com.wallpaperswitcher.engine.ScannedFolder

import com.wallpaperswitcher.engine.WallpaperTarget

import com.wallpaperswitcher.service.WallpaperSwitchService

import com.wallpaperswitcher.service.StaticApplyOutcome

import com.wallpaperswitcher.wallpaper.LiveWallpaperService

import com.wallpaperswitcher.worker.FolderAutoScanWorker

import java.util.concurrent.TimeUnit

import kotlinx.coroutines.*

import kotlinx.coroutines.ExperimentalCoroutinesApi

import kotlinx.coroutines.channels.BufferOverflow

import kotlinx.coroutines.flow.*

import kotlinx.coroutines.withContext


class WallpaperViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (getApplication() as WallpaperSwitcherApp).database
    private val groupDao = db.wallpaperGroupDao()
    private val imageDao = db.wallpaperImageDao()
    private val settingsDao = db.settingsDao()

    companion object {
        private const val TAG = "WallpaperViewModel"
        // Recursion bound for SAF folder imports (see addFolder).
        private const val MAX_IMPORT_DEPTH = 24
        /**
         * How long the HOME timer is held back after an explicit iset this
         * wallpaperi pick: just long enough to walk through the system live
         * wallpaper confirmation screen. The lock timer never uses this hold -
         * a manual lock pick only re-anchors its schedule by one interval.
         */
        const val MANUAL_PICK_HOLD_MS = 15_000L
        /**
         * Minimum spacing between two scan-progress publishes (see
         * [publishScanProgress]): the import/scan loops report far more often
         * than a human can read, and every publish reaches the UI.
         */
        private const val SCAN_PROGRESS_MIN_INTERVAL_MS = 200L
    }

    // --- States ---

    val groups: StateFlow<List<WallpaperGroup>> = groupDao.getAllGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Per-group media counts for the home screen cards (image + video + GIF).
    // distinctUntilChanged suppresses identical emissions, so a batch folder
    // import that triggers many table invalidations only recomposes the home
    // screen once the counts actually change.
    val mediaCounts: StateFlow<Map<Long, Int>> = imageDao.getMediaCounts()
        .map { list -> list.associate { it.groupId to it.mediaCount } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val serviceEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.SERVICE_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val doubleTapEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.DOUBLE_TAP_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val unlockSwitchEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.UNLOCK_SWITCH_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val floatingButtonEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.FLOATING_BUTTON_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)


    // Floating button appearance (base color + rest-state opacity percent).
    val floatingButtonColor: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.FLOATING_BUTTON_COLOR)
        .map { it ?: SettingsKeys.FLOATING_BUTTON_COLOR_DEFAULT }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsKeys.FLOATING_BUTTON_COLOR_DEFAULT)

    val floatingButtonAlpha: StateFlow<Int> = settingsDao.getValueFlow(SettingsKeys.FLOATING_BUTTON_ALPHA)
        .map { it?.toIntOrNull()?.coerceIn(SettingsKeys.FLOATING_BUTTON_ALPHA_MIN, 100) ?: SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT)

    // Floating button content: a short label, or a picture that replaces it
    // (see FloatingButtonContentPolicy).
    val floatingButtonText: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.FLOATING_BUTTON_TEXT)
        .map { it ?: SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT
        )

    val floatingButtonImageUri: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.FLOATING_BUTTON_IMAGE_URI)
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _selectedGroupId = MutableStateFlow<Long?>(null)
    val selectedGroupId: StateFlow<Long?> = _selectedGroupId

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedGroup: StateFlow<WallpaperGroup?> = _selectedGroupId
        .filterNotNull()
        .flatMapLatest { groupDao.getGroupByIdFlow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Full media list of the selected group (loaded in one shot, no paging).
    private val _loadedImages = MutableStateFlow<List<WallpaperImage>>(emptyList())
    val loadedImages: StateFlow<List<WallpaperImage>> = _loadedImages
    private val _totalImageCount = MutableStateFlow(0)
    val totalImageCount: StateFlow<Int> = _totalImageCount
    private val _isLoadingImages = MutableStateFlow(false)
    val isLoadingImages: StateFlow<Boolean> = _isLoadingImages
    // In-flight page load; a newer load cancels it so a stale query can never
    // block a group switch or publish late results.
    private var loadImagesJob: Job? = null
    // Monotonic load generation: a cancelled job's finally must never clear
    // the flag of the job that superseded it (that race caused two concurrent
    // page loads appending the same offset).
    private var loadImagesGeneration = 0

    // Scan progress
    private val _scanProgress = MutableStateFlow("")
    val scanProgress: StateFlow<String> = _scanProgress

    /** Wall-clock of the last non-empty scan-progress publish (see below). */
    private var lastScanProgressAt = 0L

    /**
     * Publish a scan-progress line.
     *
     * Throttled to one update per [SCAN_PROGRESS_MIN_INTERVAL_MS]: a folder
     * import writes progress every 50/100 media, and each write used to reach the
     * UI immediately (the card is the only consumer, but the count text is what
     * users watch). An EMPTY line - "the import finished" - is always published,
     * so the card disappears without waiting for the throttle window.
     */
    private fun publishScanProgress(text: String) {
        if (text.isNotEmpty()) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastScanProgressAt < SCAN_PROGRESS_MIN_INTERVAL_MS) return
            lastScanProgressAt = now
        }
        _scanProgress.value = text
    }

    // Toast events. extraBufferCapacity + DROP_OLDEST means emit() NEVER
    // suspends: with a zero-buffer flow, emit() would hang forever while the
    // app is in the background (no subscribers), freezing e.g. a folder import
    // or leaving _isLoadingImages stuck after a load failure.
    private val _toastMessage = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastMessage: SharedFlow<String> = _toastMessage

    // Long-lived "nstructions (e.g. inow tap 设为壁纸 in the system dialog").
    // They are shown as a floating bubble / repeated toast for several seconds
    // because the system live-wallpaper picker covers the app right after the
    // tap, so a normal ~2s toast is gone before the user can read it.
    private val _hintMessage = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val hintMessage: SharedFlow<String> = _hintMessage

    /**
     * Run a settings/CRUD write with a shared exception guard: a Room or
     * WorkManager failure (e.g. corrupted DB during migration) must never leave
     * the user with a silently dead toggle or a crashed coroutine.
     */
    private fun guardedWrite(errorMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, errorMessage, e)
                _toastMessage.emit("保存失败：${e.message}")
            }
        }
    }

    /**
     * If a media row that is still referenced as a cursor / write memo was just
     * deleted, clear those keys so no path keeps a dangling reference. Shares one
     * implementation with the automatic drops (engine self-heal, static applier)
     * - see [clearMediaCursors] - because the three used to clear different
     * subsets (the applier cleared nothing at all).
     */
    private suspend fun clearLastImageIdIfDeleted(deletedIds: Collection<Long>) =
        clearMediaCursors(settingsDao, deletedIds)

    // Last alpha value written to the database (see setFloatingButtonAlpha).
    private var lastWrittenFloatingAlpha: Int? = null
    // Debounce job for slider commits (see setFloatingButtonAlpha).
    private var alphaWriteJob: Job? = null

    // --- Actions ---

    fun selectGroup(id: Long?) {
        _selectedGroupId.value = id
        _loadedImages.value = emptyList()
        _totalImageCount.value = 0
        if (id != null) {
            loadAllImages(id)
        }
    }

    /**
     * Load EVERY image of the group in one shot (no paging). The user wants
     * the whole group available as soon as the detail screen opens, so the
     * fast scroller and scrolling never wait on another page fetch.
     */
    fun loadAllImages(groupId: Long) {
        // A new load supersedes any in-flight one: switching groups quickly
        // must never publish a stale group's list. The stale job is also
        // guarded by the selectedGroupId check below.
        loadImagesJob?.cancel()
        val gen = ++loadImagesGeneration
        loadImagesJob = viewModelScope.launch {
            _isLoadingImages.value = true
            try {
                // Only publish results for the group that is still selected:
                // a slow query for a previously-opened group must never
                // overwrite the list of the group the user switched to.
                if (_selectedGroupId.value == groupId) {
                    _totalImageCount.value = imageDao.getImageCountByGroup(groupId)
                    _loadedImages.value = imageDao.getImagesByGroupSync(groupId)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLog.e(TAG, "loadAllImages failed", e)
                _toastMessage.emit("加载图片失败: ${e.message}")
            } finally {
                // Only the current generation owns the flag: a cancelled older
                // load must not clear the new load's isLoadingImages.
                if (gen == loadImagesGeneration) {
                    _isLoadingImages.value = false
                }
            }
        }
    }

    fun toggleService(enabled: Boolean) {
        viewModelScope.launch {
            settingsDao.setBool(SettingsKeys.SERVICE_ENABLED, enabled)
            if (enabled) {
                try {
                    // Restart the interval from now: a stale anchor from an
                    // earlier session must not fire an "mmediate icatch-up"
                    // right after the user turns the timer on.
                    settingsDao.setLong(
                        SettingsKeys.TIMER_LAST_SWITCH_WALL_MS,
                        System.currentTimeMillis()
                    )
                    WallpaperSwitchService.start(getApplication())
                } catch (e: Exception) {
                    AppLog.e(TAG, "Failed to start wallpaper switch service", e)
                    // Revert the toggle so the UI reflects reality (the
                    // service did not start; boot/self-heal paths that ARE
                    // guarded will retry later).
                    settingsDao.setBool(SettingsKeys.SERVICE_ENABLED, false)
                    _toastMessage.emit("服务启动失败：${e.message}")
                }
            } else {
                // The lock timer is independent: only stop the service when the
                // lock timer is off too, otherwise keep it running for the lock.
                if (!settingsDao.getBool(SettingsKeys.LOCK_TIMER_ENABLED, true)) {
                    WallpaperSwitchService.stop(getApplication())
                }
            }
        }
    }

    fun toggleDoubleTap(enabled: Boolean) {
        guardedWrite("切换双击失败") {
            settingsDao.setBool(SettingsKeys.DOUBLE_TAP_ENABLED, enabled)
        }
    }

    fun toggleUnlockSwitch(enabled: Boolean) {
        guardedWrite("切换解锁失败") {
            settingsDao.setBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, enabled)
            // Unlock switching works in both modes: with the engine it switches
            // the live wallpaper, without it ScreenUnlockReceiver writes a static
            // home wallpaper. Only the *appearance* differs, so the hint says so
            // "nstead of the old ineeds the live wallpaper engine" (which was
            // wrong ever since the static fallback was added).
            if (enabled && !LiveWallpaperService.engineRunning) {
                _hintMessage.emit("解锁切换已开启：当前是静态壁纸模式，解锁后直接换静态壁纸（无动画）")
            }
        }
    }

    /**
     * Floating double-tap button fallback. Needs the "display over other apps"
     * permission; when it is missing, open the system permission screen first
     * (the button appears once permission is granted and the wallpaper engine
     * re-checks on the next visibility change).
     */
    fun toggleFloatingButton(enabled: Boolean) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (enabled) {
                try {
                    if (!android.provider.Settings.canDrawOverlays(app)) {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${app.packageName}")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        app.startActivity(intent)
                    }
                } catch (_: Exception) {}
            }
            settingsDao.setBool(SettingsKeys.FLOATING_BUTTON_ENABLED, enabled)
        }
    }

    /**
     * Set the floating button base color as "#RRGGBB". The engine applies it
     * to the button live (no restart needed).
     */
    fun setFloatingButtonColor(hex: String) {
        guardedWrite("保存按钮颜色失败") {
            settingsDao.setString(SettingsKeys.FLOATING_BUTTON_COLOR, hex)
        }
    }

    /**
     * Set the floating button's label (see [FloatingButtonContentPolicy]: it is
     * trimmed, capped and never blank). The engine applies it live.
     */
    fun setFloatingButtonText(text: String) {
        val clamped = FloatingButtonContentPolicy.clampText(text)
        guardedWrite("保存按钮文字失败") {
            settingsDao.setString(SettingsKeys.FLOATING_BUTTON_TEXT, clamped)
        }
    }

    /**
     * Set (or clear, with null/blank) the floating button's custom picture. The
     * URI must be a persisted content URI - the Settings screen takes the
     * persistable read permission before calling this.
     */
    fun setFloatingButtonImageUri(uri: String?) {
        val value = uri?.trim().orEmpty()
        guardedWrite("保存按钮图片失败") {
            settingsDao.setString(SettingsKeys.FLOATING_BUTTON_IMAGE_URI, value)
        }
    }

    /**
     * Set the floating button rest-state opacity in percent (5..100). The
     * write is debounced (~200ms): a slider drag fires one tick per frame,
     * and committing every tick would spam the database and invalidate every
     * settings flow (the whole Settings screen recomposes once per write).
     * The last value within the window always wins, so the live desktop
     * preview stays responsive.
     */
    fun setFloatingButtonAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(SettingsKeys.FLOATING_BUTTON_ALPHA_MIN, 100)
        if (clamped == lastWrittenFloatingAlpha) return
        alphaWriteJob?.cancel()
        alphaWriteJob = viewModelScope.launch {
            try {
                delay(200)
                lastWrittenFloatingAlpha = clamped
                settingsDao.setString(SettingsKeys.FLOATING_BUTTON_ALPHA, clamped.toString())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(TAG, "保存按钮透明度失败", e)
            }
        }
    }

    /**
     * Create a group on the ViewModel scope and invoke [onCreated] with the
     * new id. Unlike a composition scope launch, the insert survives activity
     * recreation: a rotation mid-insert can no longer cancel it silently.
     */
    fun createGroupAndOpen(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val id = groupDao.insert(WallpaperGroup(name = name))
                onCreated(id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(TAG, "创建分组失败", e)
                _toastMessage.emit("创建分组失败: ${e.message}")
            }
        }
    }

    fun updateGroup(group: WallpaperGroup) {
        guardedWrite("更新分组失败") { groupDao.update(group) }
    }

    fun deleteGroup(group: WallpaperGroup) {
        guardedWrite("删除分组失败") {
            groupDao.delete(group)
            // Reuse selectGroup(null) so the loaded list + count are cleared
            // too; otherwise the UI could briefly show the deleted group's
            // residual list.
            if (_selectedGroupId.value == group.id) selectGroup(null)
            // Clear last image ID if it belonged to the deleted group
            // (CASCADE deletes images, so the ID would point to nothing)
            val lastId = settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID)
            val image = imageDao.getImageById(lastId)
            if (image == null) {
                settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, 0L)
            }
            // Same for the lock screen's own cursor / write memo.
            val lastLockId = settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID_LOCK)
            if (lastLockId > 0L && imageDao.getImageById(lastLockId) == null) {
                settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID_LOCK, 0L)
            }
            val lastLockWrite = settingsDao.getLong(SettingsKeys.LAST_LOCK_WRITE_ID)
            if (lastLockWrite > 0L && imageDao.getImageById(lastLockWrite) == null) {
                settingsDao.setLong(SettingsKeys.LAST_LOCK_WRITE_ID, 0L)
            }
            val lastHomeWrite = settingsDao.getLong(SettingsKeys.LAST_HOME_WRITE_ID)
            if (lastHomeWrite > 0L && imageDao.getImageById(lastHomeWrite) == null) {
                settingsDao.setLong(SettingsKeys.LAST_HOME_WRITE_ID, 0L)
            }
            val manualPick = settingsDao.getLong(SettingsKeys.MANUAL_PICK_MEDIA_ID)
            if (manualPick > 0L && imageDao.getImageById(manualPick) == null) {
                settingsDao.setLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, 0L)
            }
        }
    }

    fun toggleGroupEnabled(groupId: Long, enabled: Boolean) {
        guardedWrite("切换分组失败") {
            val group = groupDao.getGroupById(groupId) ?: return@guardedWrite
            groupDao.update(group.copy(isEnabled = enabled))
            // A group that just became (in)active changes what each screen may
            // show: wake the timer loops so the change applies immediately
            // instead of after the lock loop's idle poll.
            WallpaperSwitchService.poke(getApplication())
        }
    }

    /**
     * Choose where this group's media may be shown: 桌面 / 锁屏 / 桌面和锁屏.
     * The home screen and the lock screen are switched independently, each from
     * the enabled groups that target it (Paperize-style dual screen).
     */
    fun setGroupTarget(groupId: Long, target: WallpaperTarget) {
        guardedWrite("设置应用位置失败") {
            groupDao.updateTarget(groupId, target.nameValue)
            AppLog.d(TAG, "setGroupTarget: group=$groupId target=${target.nameValue}")
            WallpaperSwitchService.poke(getApplication())
        }
    }

    // --- Global settings ---

    val globalIntervalMs: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.GLOBAL_INTERVAL_MS)
        .map { it?.toLongOrNull() ?: 60_000L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60_000L)

    // --- Lock screen triggers (independent from the home screen) ---
    val lockTimerEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.LOCK_TIMER_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val lockIntervalMs: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.LOCK_INTERVAL_MS)
        .map { it?.toLongOrNull() ?: 60_000L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60_000L)

    val globalSwitchMode: StateFlow<SwitchMode> = settingsDao.getValueFlow(SettingsKeys.GLOBAL_SWITCH_MODE)
        .map { try { SwitchMode.valueOf(it ?: "RANDOM") } catch (_: Exception) { SwitchMode.RANDOM } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SwitchMode.RANDOM)

    val globalScaleMode: StateFlow<ScaleMode> = settingsDao.getValueFlow(SettingsKeys.GLOBAL_SCALE_MODE)
        .map { try { ScaleMode.valueOf(it ?: "FIT") } catch (_: Exception) { ScaleMode.FIT } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScaleMode.FIT)

    // Low-res media clarity enhancement: "auto" | "off" | "strong".
    val clarityMode: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.CLARITY_MODE)
        .map { it ?: "auto" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "auto")


    // FILL/STRETCH auto-rotation for orientation-mismatched media (on by default).
    val rotateMismatchEnabled: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.ROTATE_MISMATCH_ENABLED)
            .map { it?.toBooleanStrictOrNull() ?: true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Rotation direction for the auto-rotate feature: clockwise (default) or
    // counter-clockwise.
    val rotateMismatchClockwise: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.ROTATE_MISMATCH_CW)
            .map { it?.toBooleanStrictOrNull() ?: true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Fade-in transition after each switch (default on).
    val switchFadeEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.SWITCH_FADE_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Video wallpaper audio (default off - see SettingsKeys.VIDEO_SOUND_ENABLED).
    val videoSoundEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.VIDEO_SOUND_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Periodic folder auto-scan
    val autoScanEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.AUTO_SCAN_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoScanIntervalMs: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.AUTO_SCAN_INTERVAL_MS)
        .map { it?.toLongOrNull() ?: (24L * 60 * 60 * 1000) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 24L * 60 * 60 * 1000)

    /** Wall-clock ms of the last auto-scan run (0 = never); shown in Settings. */
    val autoScanLastRunAt: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.AUTO_SCAN_LAST_RUN_AT)
        .map { it?.toLongOrNull() ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    init {
        // Self-heal the periodic folder scan: if the setting is on but the
        // unique work is gone (WorkManager DB reset, backup restore, an app
        // that was force-stopped before the first enqueue), re-enqueue it.
        // ExistingPeriodicWorkPolicy.UPDATE keeps an existing schedule as-is
        // (and does not reset its period), so doing this on every app start is
        // harmless. Without "t the toggle could look ion" while nothing was
        // ever scheduled - which is exactly what the settings screen cannot
        // show.
        viewModelScope.launch {
            try {
                if (settingsDao.getBool(SettingsKeys.AUTO_SCAN_ENABLED, false)) {
                    val interval = settingsDao.getLong(
                        SettingsKeys.AUTO_SCAN_INTERVAL_MS,
                        24L * 60 * 60 * 1000
                    )
                    scheduleAutoScan(interval)
                    AppLog.d(TAG, "Auto-scan work ensured (interval ${interval}ms)")
                    runAutoScanIfDue(interval)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Auto-scan self-heal failed", e)
            }
        }
    }

    /**
     * Catch-up scan when the app is opened.
     *
     * The periodic WorkManager job is the normal path, but aggressively managed
     * ROMs (MIUI/HyperOS) defer or silently drop JobScheduler jobs - the tablet
     * log contained no "Auto-scan" line at all even with the setting on. Opening
     * the app is a reliable moment to catch up: when the last run is older than
     * the configured interval, one immediate constraint-free scan is enqueued.
     */
    private fun runAutoScanIfDue(intervalMs: Long) {
        viewModelScope.launch {
            try {
                val last = settingsDao.getLong(SettingsKeys.AUTO_SCAN_LAST_RUN_AT, 0L)
                val due = last <= 0L || System.currentTimeMillis() - last >= intervalMs
                if (!due) return@launch
                AppLog.d(TAG, "Auto-scan is due (last run at $last); running it now")
                WorkManager.getInstance(getApplication()).enqueueUniqueWork(
                    "folder_auto_scan_now",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<FolderAutoScanWorker>().build()
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "Auto-scan catch-up failed", e)
            }
        }
    }

    fun toggleAutoScan(enabled: Boolean, intervalMs: Long) {
        guardedWrite("保存自动扫描失败") {
            val safeInterval = intervalMs.coerceAtLeast(15 * 60_000L)
            settingsDao.setBool(SettingsKeys.AUTO_SCAN_ENABLED, enabled)
            settingsDao.setLong(SettingsKeys.AUTO_SCAN_INTERVAL_MS, safeInterval)
            if (enabled) scheduleAutoScan(safeInterval) else cancelAutoScan()
        }
    }

    /**
     * Schedule (or reschedule) the periodic folder auto-scan. The interval is
     * passed explicitly: reading autoScanIntervalMs.value right after writing
     * it would return the STALE pre-write value (the Room Flow emits the new
     * value asynchronously), so the worker would keep running at the interval
     * the user just replaced.
     */
    private fun scheduleAutoScan(intervalMs: Long) {
        val safeInterval = intervalMs.coerceAtLeast(15 * 60_000L)
        val request = PeriodicWorkRequestBuilder<FolderAutoScanWorker>(safeInterval, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(getApplication())
            .enqueueUniquePeriodicWork("folder_auto_scan", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun cancelAutoScan() {
        WorkManager.getInstance(getApplication()).cancelUniqueWork("folder_auto_scan")
    }

    fun setGlobalInterval(ms: Long) {
        guardedWrite("保存切换间隔失败") {
            settingsDao.setLong(SettingsKeys.GLOBAL_INTERVAL_MS, ms.coerceAtLeast(10_000L))
        }
    }

    // --- Lock screen triggers ---

    fun toggleLockTimer(enabled: Boolean) {
        guardedWrite("切换锁屏定时失败") {
            settingsDao.setBool(SettingsKeys.LOCK_TIMER_ENABLED, enabled)
            if (enabled) {
                // Restart the lock interval from now so a stale anchor never
                // fires an immediate catch-up when the user turns it on.
                settingsDao.setLong(
                    SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS,
                    System.currentTimeMillis()
                )
                WallpaperSwitchService.ensureRunning(getApplication())
            } else {
                // The lock loop is usually parked in a delay() that can be hours
                // long, so it would not notice the toggle by itself. Stop the
                // service when no timer needs it anymore, otherwise wake the
                // loops so the lock loop exits now.
                if (settingsDao.getBool(SettingsKeys.SERVICE_ENABLED, false)) {
                    WallpaperSwitchService.poke(getApplication())
                } else {
                    WallpaperSwitchService.stop(getApplication())
                }
            }
        }
    }

    fun setLockInterval(ms: Long) {
        guardedWrite("保存锁屏切换间隔失败") {
            settingsDao.setLong(SettingsKeys.LOCK_INTERVAL_MS, ms.coerceAtLeast(10_000L))
        }
    }

    fun setGlobalSwitchMode(mode: SwitchMode) {
        guardedWrite("保存切换模式失败") {
            settingsDao.setString(SettingsKeys.GLOBAL_SWITCH_MODE, mode.name)
        }
    }

    fun setGlobalScaleMode(mode: ScaleMode) {
        guardedWrite("保存缩放模式失败") {
            settingsDao.setString(SettingsKeys.GLOBAL_SCALE_MODE, mode.name)
        }
    }

    fun setClarityMode(mode: String) {
        guardedWrite("保存清晰度失败") {
            settingsDao.setString(SettingsKeys.CLARITY_MODE, mode)
        }
    }

    fun toggleRotateMismatch(enabled: Boolean) {
        guardedWrite("保存旋转适配失败") {
            settingsDao.setBool(SettingsKeys.ROTATE_MISMATCH_ENABLED, enabled)
        }
    }

    fun setRotateMismatchClockwise(clockwise: Boolean) {
        guardedWrite("保存旋转方向失败") {
            settingsDao.setBool(SettingsKeys.ROTATE_MISMATCH_CW, clockwise)
        }
    }

    /**
     * Header lines for an exported runtime-log report: current settings, engine
     * state and library sizes, so a shared log can be analysed without asking
     * the user for each value.
     */
    suspend fun buildLogReportHeader(): List<String> = withContext(Dispatchers.IO) {
        val wall = try {
            android.app.WallpaperManager.getInstance(getApplication())
                .wallpaperInfo?.component?.flattenToShortString() ?: "none"
        } catch (_: Exception) {
            "unknown"
        }
        listOf(
            "--- state ---",
            "engine_running=${LiveWallpaperService.engineRunning}",
            "engine_database_ok=${!LiveWallpaperService.databaseUnavailable}",
            "service_enabled=${settingsDao.getBool(SettingsKeys.SERVICE_ENABLED, false)}",
            "switch_mode=${settingsDao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, "RANDOM")}",
            "scale_mode=${settingsDao.getString(SettingsKeys.GLOBAL_SCALE_MODE, "FIT")}",
            "rotate_mismatch=${settingsDao.getBool(SettingsKeys.ROTATE_MISMATCH_ENABLED, true)}",
            "rotate_direction=${if (settingsDao.getBool(SettingsKeys.ROTATE_MISMATCH_CW, true)) "cw" else "ccw"}",
            "clarity_mode=${settingsDao.getString(SettingsKeys.CLARITY_MODE, "auto")}",
            "switch_fade=${settingsDao.getBool(SettingsKeys.SWITCH_FADE_ENABLED, true)}",
            "interval_ms=${settingsDao.getLong(SettingsKeys.GLOBAL_INTERVAL_MS, 60_000L)}",
            "double_tap=${settingsDao.getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)}",
            "unlock_switch=${settingsDao.getBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)}",
            "floating_button=${settingsDao.getBool(SettingsKeys.FLOATING_BUTTON_ENABLED, false)}",
            "floating_button_text=${settingsDao.getString(SettingsKeys.FLOATING_BUTTON_TEXT, SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT)}",
            "floating_button_image=${LogText.short(settingsDao.getString(SettingsKeys.FLOATING_BUTTON_IMAGE_URI, ""))}",
            "last_image_id=${settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID, 0L)}",
            "enabled_groups=${groupDao.getEnabledGroupsSync().size}",
            "enabled_media_home=${imageDao.countByEnabledGroups(WallpaperTarget.SLOT_HOME)}",
            "enabled_media_lock=${imageDao.countByEnabledGroups(WallpaperTarget.SLOT_LOCK)}",
            // MediaStore-imported media needs this permission; without it every
            // switch fails with a SecurityException (which must never be
            // mistaken for "the file was deleted" - see MediaProbe).
            "read_media_permission=${MediaProbe.hasReadMediaPermission(getApplication())}",
            "wallpaper_component=$wall"
        )
    }

    fun setSwitchFadeEnabled(enabled: Boolean) {
        guardedWrite("保存切换动画失败") {
            settingsDao.setBool(SettingsKeys.SWITCH_FADE_ENABLED, enabled)
        }
    }


    fun setVideoSoundEnabled(enabled: Boolean) {
        guardedWrite("保存视频声音设置失败") {
            settingsDao.setBool(SettingsKeys.VIDEO_SOUND_ENABLED, enabled)
        }
    }

    // Theme color (stored as hex string like "#6750A4", empty = system default)
    val themeColor: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.THEME_COLOR)
        .map { it ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // --- Combined screen states ---
    //
    // Each screen subscribes to ONE combined StateFlow instead of N separate
    // Room-backed flows. Previously a screen's first composition subscribed to
    // every flow at once (13 for Settings), and each Room query emitted on the
    // main thread at its own time -> the whole screen was recomposed once per
    // emission, spread across several frames right in the middle of the tab
    // transition animation (the 首页↔设置 stutter). A combined flow still runs
    // the same queries, but emits ONE value -> ONE recomposition per change.
    // Declared after all inputs so the property initializers run in order.

    val homeUiState: StateFlow<HomeUiState> = combine(
        groups,
        mediaCounts,
        serviceEnabled,
        lockTimerEnabled
    ) { g, m, s, lock ->
        HomeUiState(groups = g, mediaCounts = m, serviceEnabled = s, lockTimerEnabled = lock)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    /**
     * Indices of [settingsUiState]'s combined input array. Named instead of bare
     * numbers: inserting a flow without renumbering every use used to fail only
     * at runtime (a mismatched `as` cast inside the flow, which silently stops
     * the settings screen from updating).
     */
    private object SettingsField {
        const val SERVICE_ENABLED = 0
        const val DOUBLE_TAP_ENABLED = 1
        const val UNLOCK_SWITCH_ENABLED = 2
        const val FLOATING_BUTTON_ENABLED = 3
        const val FLOATING_BUTTON_COLOR = 4
        const val FLOATING_BUTTON_ALPHA = 5
        const val GLOBAL_INTERVAL_MS = 6
        const val GLOBAL_SWITCH_MODE = 7
        const val GLOBAL_SCALE_MODE = 8
        const val CLARITY_MODE = 9
        const val SWITCH_FADE_ENABLED = 10
        const val THEME_COLOR = 11
        const val AUTO_SCAN_ENABLED = 12
        const val AUTO_SCAN_INTERVAL_MS = 13
        const val AUTO_SCAN_LAST_RUN_AT = 14
        const val ROTATE_MISMATCH_ENABLED = 15
        const val ROTATE_MISMATCH_CLOCKWISE = 16
        /** Lock-wallpaper rotation toggle (one picture per lock event). */
        const val LOCK_TIMER_ENABLED = 17
        const val LOCK_INTERVAL_MS = 18
        const val VIDEO_SOUND_ENABLED = 19
        const val FLOATING_BUTTON_TEXT = 20
        const val FLOATING_BUTTON_IMAGE_URI = 21
    }

    /**
     * Type-checked read of one combined value: a mismatch yields the caller's
     * default (and one log line) instead of a ClassCastException that would kill
     * the settings flow.
     */
    private fun <T> combined(values: Array<out Any?>, index: Int, name: String, type: Class<T>): T? {
        val value = values.getOrNull(index)
        if (type.isInstance(value)) return type.cast(value)
        if (loggedStateMismatches.add(index)) {
            AppLog.e(
                TAG,
                "settingsUiState[$index] ($name) is ${value?.javaClass?.simpleName ?: "null"}, " +
                    "expected ${type.simpleName} - falling back to the default"
            )
        }
        return null
    }

    private val loggedStateMismatches = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    )

    val settingsUiState: StateFlow<SettingsUiState> = combine(
        serviceEnabled,
        doubleTapEnabled,
        unlockSwitchEnabled,
        floatingButtonEnabled,
        floatingButtonColor,
        floatingButtonAlpha,
        globalIntervalMs,
        globalSwitchMode,
        globalScaleMode,
        clarityMode,
        switchFadeEnabled,
        themeColor,
        autoScanEnabled,
        autoScanIntervalMs,
        autoScanLastRunAt,
        rotateMismatchEnabled,
        rotateMismatchClockwise,
        lockTimerEnabled,
        lockIntervalMs,
        videoSoundEnabled,
        floatingButtonText,
        floatingButtonImageUri
    ) { a ->
        SettingsUiState(
            serviceEnabled = combined(a, SettingsField.SERVICE_ENABLED, "serviceEnabled", Boolean::class.javaObjectType) ?: false,
            doubleTapEnabled = combined(a, SettingsField.DOUBLE_TAP_ENABLED, "doubleTapEnabled", Boolean::class.javaObjectType) ?: true,
            unlockSwitchEnabled = combined(a, SettingsField.UNLOCK_SWITCH_ENABLED, "unlockSwitchEnabled", Boolean::class.javaObjectType) ?: false,
            floatingButtonEnabled = combined(a, SettingsField.FLOATING_BUTTON_ENABLED, "floatingButtonEnabled", Boolean::class.javaObjectType) ?: false,
            floatingButtonColor = combined(a, SettingsField.FLOATING_BUTTON_COLOR, "floatingButtonColor", String::class.java) ?: SettingsKeys.FLOATING_BUTTON_COLOR_DEFAULT,
            floatingButtonAlpha = combined(a, SettingsField.FLOATING_BUTTON_ALPHA, "floatingButtonAlpha", Integer::class.java)?.toInt() ?: SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT,
            floatingButtonText = combined(a, SettingsField.FLOATING_BUTTON_TEXT, "floatingButtonText", String::class.java) ?: SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT,
            floatingButtonImageUri = combined(a, SettingsField.FLOATING_BUTTON_IMAGE_URI, "floatingButtonImageUri", String::class.java).orEmpty(),
            globalIntervalMs = combined(a, SettingsField.GLOBAL_INTERVAL_MS, "globalIntervalMs", Long::class.javaObjectType) ?: 60_000L,
            globalSwitchMode = combined(a, SettingsField.GLOBAL_SWITCH_MODE, "globalSwitchMode", SwitchMode::class.java) ?: SwitchMode.RANDOM,
            globalScaleMode = combined(a, SettingsField.GLOBAL_SCALE_MODE, "globalScaleMode", ScaleMode::class.java) ?: ScaleMode.FIT,
            clarityMode = combined(a, SettingsField.CLARITY_MODE, "clarityMode", String::class.java) ?: "auto",
            switchFadeEnabled = combined(a, SettingsField.SWITCH_FADE_ENABLED, "switchFadeEnabled", Boolean::class.javaObjectType) ?: true,
            themeColor = combined(a, SettingsField.THEME_COLOR, "themeColor", String::class.java) ?: "",
            autoScanEnabled = combined(a, SettingsField.AUTO_SCAN_ENABLED, "autoScanEnabled", Boolean::class.javaObjectType) ?: false,
            autoScanIntervalMs = combined(a, SettingsField.AUTO_SCAN_INTERVAL_MS, "autoScanIntervalMs", Long::class.javaObjectType) ?: 24L * 60 * 60 * 1000,
            autoScanLastRunAt = combined(a, SettingsField.AUTO_SCAN_LAST_RUN_AT, "autoScanLastRunAt", Long::class.javaObjectType) ?: 0L,
            rotateMismatchEnabled = combined(a, SettingsField.ROTATE_MISMATCH_ENABLED, "rotateMismatchEnabled", Boolean::class.javaObjectType) ?: true,
            rotateMismatchClockwise = combined(a, SettingsField.ROTATE_MISMATCH_CLOCKWISE, "rotateMismatchClockwise", Boolean::class.javaObjectType) ?: true,
            lockTimerEnabled = combined(a, SettingsField.LOCK_TIMER_ENABLED, "lockTimerEnabled", Boolean::class.javaObjectType) ?: true,
            lockIntervalMs = combined(a, SettingsField.LOCK_INTERVAL_MS, "lockIntervalMs", Long::class.javaObjectType) ?: 60_000L,
            videoSoundEnabled = combined(a, SettingsField.VIDEO_SOUND_ENABLED, "videoSoundEnabled", Boolean::class.javaObjectType) ?: false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setThemeColor(hex: String) {
        guardedWrite("保存主题色失败") {
            settingsDao.setString(SettingsKeys.THEME_COLOR, hex)
        }
    }

    fun addImage(groupId: Long, uri: Uri, displayName: String) {
        guardedWrite("添加图片失败") {
            if (groupDao.getGroupById(groupId) == null) return@guardedWrite
            // Deduplicate by URI like addImages / the folder import / the
            // auto-scan worker: picking the same file twice used to add a second
            // row, and the shuffle pass then showed it twice.
            val uriStr = uri.toString()
            // The dedupe read AND the name/MIME resolution run on the IO
            // dispatcher: resolveDisplayName()/mimeOf() are synchronous provider
            // queries (plus a DocumentFile fallback), and guardedWrite() runs on
            // the main dispatcher - a multi-select of a few hundred files used to
            // do every one of those binder calls on the UI thread, which froze
            // the picker's return and could ANR.
            //
            // The system picker on many non-Xiaomi devices returns a generic
            // last path segment like "msf:1000000024" (no extension), which
            // used to be stored as-is and misclassified every video as IMAGE
            // (black playback). Resolve the real name + MIME instead.
            val row = withContext(Dispatchers.IO) {
                val alreadyThere = try {
                    imageDao.getUrisByGroup(groupId).contains(uriStr)
                } catch (_: Exception) {
                    false
                }
                if (alreadyThere) {
                    null
                } else {
                    val name = resolveDisplayName(uri, displayName)
                    WallpaperImage(
                        groupId = groupId,
                        uri = uriStr,
                        displayName = name,
                        mediaType = resolveMediaType(uri, name)
                    )
                }
            }
            if (row == null) {
                _toastMessage.emit("该媒体已经在分组里了")
                return@guardedWrite
            }
            imageDao.insert(row)
            refreshCount(groupId)
            refreshImages()
            // New media changes what a screen may show: wake the timers now
            // instead of waiting for the next interval (the idle waits inside the
            // service are long on purpose).
            WallpaperSwitchService.poke(getApplication())
        }
    }

    fun addImages(groupId: Long, uris: List<Uri>, names: List<String>) {
        guardedWrite("添加图片失败") {
            if (groupDao.getGroupById(groupId) == null) return@guardedWrite
            // Deduplicate by URI, both against what the group already holds and
            // within this batch: the folder-import path and the auto-scan worker
            // do this, the pickers did not, so re-adding the same file produced a
            // second row and the shuffle pass showed it twice.
            val known = try {
                imageDao.getUrisByGroup(groupId).toHashSet()
            } catch (_: Exception) {
                HashSet()
            }
            // Name + MIME resolution is synchronous provider work (one getType()
            // and one DISPLAY_NAME query per URI, plus a DocumentFile fallback):
            // on the UI thread a few-hundred-file multi-select froze the app right
            // after the picker closed. Same reason addFolder() already uses IO.
            // The provider MIME is fetched ONCE per URI and reused for both the
            // media type and the "is it supported" test (it used to be read
            // twice per file).
            val (images, duplicates) = withContext(Dispatchers.IO) {
                var dups = 0
                val built = uris.mapIndexedNotNull { index, uri ->
                    val fallback = names.getOrNull(index).orEmpty()
                    val mime = mimeOf(uri)
                    val name = resolveDisplayName(uri, fallback)
                    val mediaType = MediaTypes.fromMimeOrName(mime, name)
                    // Accept when the resolved name has a supported extension OR
                    // the provider reports a media MIME type (SAF names can lack
                    // an extension entirely). Note this must test the provider MIME
                    // "tself: resolveMediaType() already defaults to iIMAGE", so
                    // comparing its result let every file through.
                    val supported = isSupportedMedia(name) || mime != null
                    if (!supported) {
                        null
                    } else if (!known.add(uri.toString())) {
                        dups++
                        null
                    } else {
                        WallpaperImage(
                            groupId = groupId,
                            uri = uri.toString(),
                            displayName = name,
                            mediaType = mediaType
                        )
                    }
                }
                built to dups
            }
            if (images.isNotEmpty()) {
                // Chunk large multi-select imports: 100 rows per INSERT stays
                // under the 999 bound-variable limit of older SQLite builds.
                images.chunked(100).forEach { chunk -> imageDao.insertAll(chunk) }
                refreshCount(groupId)
                refreshImages()
                WallpaperSwitchService.poke(getApplication())
                // Media, not images: video and GIF files are added here too.
                _toastMessage.emit(
                    if (duplicates > 0) {
                        "已添加 ${images.size} 个媒体（跳过 $duplicates 个重复）"
                    } else {
                        "已添加 ${images.size} 个媒体"
                    }
                )
            } else {
                _toastMessage.emit(
                    if (duplicates > 0) "这些媒体已经在分组里了"
                    else "没有可添加的图片/视频"
                )
            }
        }
    }

    /**
     * Add folder via DocumentFile (SAF).
     * Optimized for large folders: batch insert, progress updates, yield for UI responsiveness.
     */
    private var addFolderJob: Job? = null

    fun addFolder(groupId: Long, folderUri: Uri) {
        addFolderJob?.cancel()
        addFolderJob = viewModelScope.launch {
            try {
                if (groupDao.getGroupById(groupId) == null) return@launch
                _toastMessage.emit("正在扫描文件夹...")
                var total = 0
                var alreadyThere = 0
                withContext(Dispatchers.IO) {
                    val docFile = try {
                        androidx.documentfile.provider.DocumentFile
                            .fromTreeUri(getApplication(), folderUri)
                    } catch (e: Exception) {
                        AppLog.e(TAG, "fromTreeUri failed", e)
                        null
                    } ?: return@withContext

                    if (!docFile.isDirectory) return@withContext

                    // Collect every media first (the recursive scan), then
                    // insert in ONE transaction: the old code committed one
                    // transaction per 100-row batch, i.e. ~100 fsyncs for a
                    // 10k-file folder.
                    val collected = mutableListOf<WallpaperImage>()
                    // Same dedupe rule as addImages / the auto-scan worker:
                    // re-importing a folder must not duplicate its media.
                    val known = try {
                        imageDao.getUrisByGroup(groupId).toHashSet()
                    } catch (_: Exception) {
                        HashSet()
                    }
                    suspend fun scanDir(
                        dir: androidx.documentfile.provider.DocumentFile,
                        depth: Int
                    ) {
                        if (!isActive || depth > MAX_IMPORT_DEPTH) return
                        val files = try {
                            dir.listFiles()
                        } catch (e: Exception) {
                            AppLog.e(TAG, "listFiles failed", e)
                            emptyArray()
                        }
                        for (file in files) {
                            if (!isActive) return
                            if (collected.size % 100 == 0) yield()
                            try {
                                if (file.isDirectory) {
                                    scanDir(file, depth + 1)
                                } else if (file.isFile && isSupportedMedia(file.name ?: "")) {
                                    val uriStr = file.uri.toString()
                                    if (known.add(uriStr)) {
                                        collected.add(WallpaperImage(
                                            groupId = groupId,
                                            uri = uriStr,
                                            displayName = file.name ?: "untitled",
                                            mediaType = detectMediaType(file.name ?: ""),
                                            isFromFolder = true,
                                            folderPath = folderUri.toString()
                                        ))
                                    } else {
                                        alreadyThere++
                                    }
                                }
                            } catch (_: Exception) { continue }
                        }
                    }

                    scanDir(docFile, 0)
                    if (collected.isNotEmpty() && isActive) {
                        db.withTransaction {
                            // 100 rows per INSERT stays under the 999
                            // bound-variable limit of older SQLite builds.
                            collected.chunked(100).forEach { chunk ->
                                imageDao.insertAll(chunk)
                                total += chunk.size
                            }
                        }
                    }
                }
                if (total > 0) {
                    refreshCount(groupId)
                    refreshImages()
                    WallpaperSwitchService.poke(getApplication())
                    _toastMessage.emit("已添加 $total 个媒体")
                } else {
                    _toastMessage.emit(
                        if (alreadyThere > 0) "该文件夹的媒体都已经在分组里了"
                        else "未找到媒体"
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(TAG, "addFolder failed", e)
                _toastMessage.emit("导入失败: ${e.message}")
            }
        }
    }

    fun deleteImage(image: WallpaperImage) {
        guardedWrite("删除图片失败") {
            imageDao.delete(image)
            clearLastImageIdIfDeleted(setOf(image.id))
            _selectedGroupId.value?.let { refreshCount(it) }
            refreshImages()
        }
    }

    fun deleteImages(images: List<WallpaperImage>) {
        guardedWrite("删除图片失败") {
            val ids = images.map { it.id }
            // Chunk the DELETE: older SQLite builds cap a statement at 999
            // bound variables, and a select-all delete can pass thousands of
            // "ds (would throw itoo many SQL variables").
            ids.chunked(500).forEach { chunk ->
                imageDao.deleteByIds(chunk)
            }
            clearLastImageIdIfDeleted(ids)
            _selectedGroupId.value?.let { refreshCount(it) }
            refreshImages()
        }
    }

    /**
     * Delete images by IDs directly — works across all pages, not just loaded ones.
     */
    fun deleteImagesByIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        guardedWrite("删除图片失败") {
            ids.toList().chunked(500).forEach { chunk ->
                imageDao.deleteByIds(chunk)
            }
            clearLastImageIdIfDeleted(ids)
            _selectedGroupId.value?.let { refreshCount(it) }
            refreshImages()
        }
    }

    /**
     * Get ALL image IDs in a group (across all pages) for select-all + batch delete.
     */
    suspend fun getAllImageIds(groupId: Long): List<Long> {
        return imageDao.getImageIdsByGroup(groupId)
    }

    /**
     * Scan every media entry in a group and return the ones whose files can no
     * longer be opened (deleted / moved / unreadable). Progress is reported
     * through [scanProgress]; runs on the IO dispatcher.
     */
    suspend fun scanBrokenMedia(groupId: Long): List<WallpaperImage> {
        return withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val all = imageDao.getImagesByGroupSync(groupId)
            val broken = mutableListOf<WallpaperImage>()
            var checked = 0
            for (image in all) {
                if (!isActive) return@withContext broken
                val ok = try {
                    resolver.openInputStream(Uri.parse(image.uri))?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }
                if (!ok) broken.add(image)
                checked++
                if (checked % 50 == 0 || checked == all.size) {
                    publishScanProgress("检查中 $checked/${all.size}")
                }
                if (checked % 100 == 0) yield()
            }
            publishScanProgress("")
            broken
        }
    }

    fun switchNow() {
        WallpaperSwitchService.switchNow(getApplication())
    }

    /**
     * Make one media item the displayed wallpaper, applied to the screen(s) its
     * group targets (Paperize-style dual screen):
     * - 桌面 (HOME): the live engine shows it (broadcast) when the engine is
     *   running, otherwise it is written as the static home wallpaper;
     * - 锁屏 (LOCK): always written as the static lock-screen wallpaper (a live
     *   wallpaper cannot render a different image there);
     * - a video/GIF that would have to be written statically is applied as its
     *   first frame, and the user is told so.
     */
    fun setImageAsWallpaper(image: WallpaperImage) {
        viewModelScope.launch {
            try {
                val group = groupDao.getGroupById(image.groupId)
                val target = WallpaperTarget.fromName(group?.target)
                AppLog.d(
                    TAG,
                    "setImageAsWallpaper: id=${image.id} type=${image.mediaType} target=${target.nameValue}"
                )
                // The user explicitly picked this media: restart that screen's
                // schedule so the pick stays for at least one full interval, and
                // (home only) keep the timer off it while the system dialog is
                // open. The lock timer must keep running - it is only re-anchored.
                val now = System.currentTimeMillis()
                if (target.includesHome) {
                    settingsDao.setLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, now)
                    settingsDao.setLong(
                        SettingsKeys.MANUAL_PICK_HOLD_UNTIL, now + MANUAL_PICK_HOLD_MS
                    )
                }
                if (target.includesLock) {
                    settingsDao.setLong(SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS, now)
                }
                val motion = MediaTypes.isMotion(image.mediaType)
                val engineRunning = LiveWallpaperService.engineRunning
                // The engine flag can be stale (its process was killed in the
                // background, or a preview engine just tore down) while the live
                // wallpaper is still the home wallpaper. Treat that as ithe
                // engine owns the home screeni - writing a static image there
                // would replace the live wallpaper the user just set up.
                // Binder round-trip to WallpaperManagerService: keep it off the
                // main thread (this runs while the user's tap is being handled).
                val homeIsLive = withContext(Dispatchers.IO) {
                    LiveWallpaperService.isHomeLiveWallpaper(getApplication())
                }

                // A motion wallpaper can only animate through the live engine.
                if (motion && target.includesHome && !engineRunning && !homeIsLive) {
                    launchLiveWallpaperPicker()
                    _hintMessage.emit("静态壁纸无法播放视频/GIF，请在系统动态壁纸中选择本应用")
                    return@launch
                }

                var applied = false
                // "Busy" (a timed apply held the static guard for the whole wait
                // window) must not be reported as "the file could not be read".
                var busy = false
                fun note(outcome: StaticApplyOutcome) {
                    when (outcome) {
                        StaticApplyOutcome.APPLIED -> applied = true
                        StaticApplyOutcome.BUSY -> busy = true
                        StaticApplyOutcome.FAILED -> Unit
                    }
                }
                if (target.includesHome) {
                    if (engineRunning || homeIsLive) {
                        // Keep LAST_IMAGE_ID in sync so the engine continues
                        // from this media after a restart (and so the engine
                        // picks it up when its process is restarted).
                        settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                        sendTargetBroadcast(image.id)
                        applied = true
                    } else {
                        note(WallpaperSwitchService.applyStaticWallpaper(
                            getApplication(),
                            image.id,
                            android.app.WallpaperManager.FLAG_SYSTEM
                        ))
                    }
                }
                if (target.includesLock) {
                    // A lock-screen wallpaper is static: videos/GIFs are skipped
                    // instead of being frozen on their first frame.
                    if (motion) {
                        AppLog.d(
                            TAG,
                            "setImageAsWallpaper: skipping motion media for the lock screen"
                        )
                        _toastMessage.emit("锁屏不支持视频/GIF，已自动跳过")
                    } else {
                        note(WallpaperSwitchService.applyStaticWallpaper(
                            getApplication(),
                            image.id,
                            android.app.WallpaperManager.FLAG_LOCK
                        ))
                    }
                }

                if (!applied) {
                    _toastMessage.emit(
                        if (busy) "系统正在写入壁纸，请稍后重试"
                        else "设置失败，无法读取该媒体文件"
                    )
                    return@launch
                }
                _toastMessage.emit("已设为${target.label}壁纸！")
            } catch (e: Exception) {
                AppLog.e(TAG, "setImageAsWallpaper failed", e)
                _toastMessage.emit("设置失败: ${e.message}")
            }
        }
    }

    /**
     * Live wallpaper flow: the media becomes the displayed item and the SYSTEM
     * live-wallpaper preview/confirmation screen is ALWAYS shown (even when our
     * engine is already running) so the user explicitly confirms the change.
     */
    fun setAsLiveWallpaper(image: WallpaperImage) {
        viewModelScope.launch {
            try {
                val group = groupDao.getGroupById(image.groupId)
                val target = WallpaperTarget.fromName(group?.target)
                AppLog.d(
                    TAG,
                    "setAsLiveWallpaper: id=${image.id} target=${target.nameValue}"
                )
                // Only a home-capable pick may move the HOME cursor: pointing it
                // at a lock-only media made the engine pick a different ("home")
                // image anyway, and it moved the cursor the lock enforcement
                // used to read (see MANUAL_PICK_MEDIA_ID).
                if (target.includesHome) {
                    // Remember the cursor BEFORE moving it: the picker's preview
                    // engine renders LAST_IMAGE_ID, and if the user cancels the
                    // system screen the engine must not apply the previewed media
                    // (see LiveWallpaperService.restoreHomeCursorAfterCancelledPick).
                    val previousHomeId = settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID, 0L)
                    settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                    LiveWallpaperService.notePreviewPick(previousHomeId, image.id)
                }
                if (target.includesLock) {
                    // Remember exactly what the user picked: the enforcement
                    // runs after the system dialog closes, and by then the home
                    // timer may already have advanced LAST_IMAGE_ID.
                    val pickedAt = System.currentTimeMillis()
                    settingsDao.setLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, image.id)
                    settingsDao.setLong(SettingsKeys.MANUAL_PICK_AT, pickedAt)
                }
                // Keep the engine preview (and the wallpaper it applies) on
                // THIS media while the system dialog is open, and restart the
                // schedule(s) the pick belongs to so it survives at least one
                // interval. The HOME anchor/hold only apply to home-capable
                // groups: a lock-only pick must not postpone the desktop timer
                // (same rule as setImageAsWallpaper).
                val now = System.currentTimeMillis()
                if (target.includesHome) {
                    settingsDao.setLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, now)
                    settingsDao.setLong(
                        SettingsKeys.MANUAL_PICK_HOLD_UNTIL, now + MANUAL_PICK_HOLD_MS
                    )
                }
                if (target.includesLock) {
                    settingsDao.setLong(SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS, now)
                }
                // Deliberately NO engine switch here.
                //
                // This used to push the picked media to the engine so the system
                // dialog's preview showed it - but that also meant the wallpaper
                // was already changed behind the dialog, so even CANCELLING the
                // system screen left the picked image applied (user report:
                // 「点击图片弹出系统动态壁纸界面，就设置了动态壁纸」).
                //
                // Clicking a picture now only opens the system live-wallpaper
                // screen. Confirming it re-applies our engine, which renders
                // LAST_IMAGE_ID (set above) and re-asserts the group's 应用位置
                // via enforceSlotsAfterLiveApply; cancelling changes nothing on
                // screen. A lock-only pick never touched the engine anyway.
                launchLiveWallpaperPicker()
                // Every group can be set as a live wallpaper. Which screen then
                // shows which media is decided by the group's 应用位置 (see
                // enforceSlotsAfterLiveApply): lock-targeted groups get this
                // media on the lock screen, home/both groups keep the home
                // engine running on their own media.
                // Long hint: the system picker opens on top of the app right
                // away, so this has to survive several seconds (see HintOverlay).
                _hintMessage.emit(
                    when {
                        // The system dialog fills the slot(s) it is told to, and
                        // on top of that this app re-asserts the group's 应用位置
                        // (see enforceSlotsAfterLiveApply), so the hint tells the
                        // user exactly which option matches their group.
                        target.includesHome && target.includesLock ->
                            "请选择“主屏幕和锁定屏幕”"
                        target.includesHome ->
                            "请选择“主屏幕”（该分组只用于桌面）"
                        else ->
                            // Lock-only group: the system dialog has no ilock
                            // screeni option for live wallpapers, and this app
                            // re-asserts the group's 应用位置 afterwards (see
                            // enforceSlotsAfterLiveApply), so any confirm in the
                            // dialog ends up with this group's image on the lock
                            // screen. The hint therefore only tells the user to
                            // confirm, not which option to pick.
                            "点击“设置壁纸”即可，锁屏会自动显示为该分组图片"
                    }
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "setAsLiveWallpaper failed", e)
                _toastMessage.emit("设置失败: ${e.message}")
            }
        }
    }

    private fun sendTargetBroadcast(targetId: Long) {
        val switchIntent = android.content.Intent(LiveWallpaperService.ACTION_SWITCH).apply {
            setPackage(getApplication<Application>().packageName)
            putExtra(LiveWallpaperService.EXTRA_TARGET_ID, targetId)
            putExtra(LiveWallpaperService.EXTRA_SOURCE, LiveWallpaperService.SOURCE_MANUAL)
        }
        getApplication<Application>().sendBroadcast(switchIntent)
        AppLog.d(TAG, "Switch broadcast sent with targetId=$targetId")
    }

    private fun launchLiveWallpaperPicker() {
        try {
            val intent = android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    android.content.ComponentName(getApplication(), com.wallpaperswitcher.wallpaper.LiveWallpaperService::class.java)
                )
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = android.content.Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private suspend fun refreshCount(groupId: Long) {
        _totalImageCount.value = imageDao.getImageCountByGroup(groupId)
        // Don't reload images here - let the UI trigger paged loading
        // This avoids OOM when adding large folders
    }

    /**
     * Reload the full image list of the selected group (call from UI after
     * adds/deletes so the grid reflects the database).
     */
    fun refreshImages() {
        val groupId = _selectedGroupId.value ?: return
        loadAllImages(groupId)
    }

    // ======== Folder scanning (background) ========

    // Cache of the last MediaStore folder scan. The folder picker dialog used
    // to re-scan the whole media library on EVERY open (a noticeable
    // "正在扫描文件夹..." wait on large libraries). Like the group
    // thumbnails, the list is now loaded once and reused, so the dialog opens
    // instantly. The cache is in-memory: a fresh scan happens automatically
    // after the process restarts.
    @Volatile
    private var cachedScannedFolders: List<ScannedFolder>? = null

    /**
     * Scan device folders that contain images and/or videos (MediaStore).
     * Cached: the first call scans MediaStore, later calls return the cached
     * list immediately (the picker dialog opens without re-scanning).
     */
    suspend fun loadScannedFolders(): List<ScannedFolder> {
        cachedScannedFolders?.let { return it }
        val scanned = MediaScanner.scanFolders(getApplication())
        // Cache only successful NON-EMPTY scans. An empty result usually means
        // the read-media permission was just granted/denied or the provider
        // hiccuped; caching it would make the folder dialog look permanently
        // incomplete until the process restarts. Empty devices simply rescan
        // on the next dialog open (a cheap MediaStore query).
        if (scanned.isNotEmpty()) cachedScannedFolders = scanned
        return scanned
    }

    /**
     * Force a fresh MediaStore folder scan, bypassing and refreshing the
     * "n-memory cache. Used by the folder picker's i重新扫描" action — the
     * cached list from loadScannedFolders() would otherwise stay stale until
     * the process restarts.
     */
    suspend fun rescanFolders(): List<ScannedFolder> {
        cachedScannedFolders = null
        val scanned = MediaScanner.scanFolders(getApplication())
        if (scanned.isNotEmpty()) cachedScannedFolders = scanned
        // Also record the outcome in the runtime log: the dialog shows a toast,
        // and an exported log should explain the same thing.
        AppLog.d(
            TAG,
            "rescanFolders: ${scanned.size} folders / " +
                "${scanned.sumOf { it.totalCount }} media"
        )
        return scanned
    }

    /**
     * Import several scanned folders into a group (images + videos, deduped).
     */
    fun importScannedFolders(groupId: Long, folders: List<ScannedFolder>) {
        if (folders.isEmpty()) return
        viewModelScope.launch {
            try {
                _toastMessage.emit("正在从 ${folders.size} 个文件夹导入...")
                AppLog.d(TAG, "importScannedFolders: group=$groupId folders=${folders.map { LogText.folder(it.path) }}")
                publishScanProgress("查询中...")
                var total = 0
                withContext(Dispatchers.IO) {
                    // Collect every new media first, then insert everything in
                    // ONE transaction: the old code committed a transaction per
                    // 100-row batch (dozens of fsyncs for large imports).
                    val collected = mutableListOf<WallpaperImage>()
                    val existing = imageDao.getUrisByGroup(groupId).toHashSet()
                    for (folder in folders) {
                        if (!isActive) return@withContext
                        val media = MediaScanner.queryFolderMedia(getApplication(), folder.path)
                        for (m in media) {
                            if (m.uri in existing) continue
                            existing.add(m.uri)
                            collected.add(WallpaperImage(
                                groupId = groupId,
                                uri = m.uri,
                                displayName = m.displayName,
                                mediaType = m.mediaType,
                                isFromFolder = true,
                                folderPath = folder.path,
                                // Free decode metadata from the MediaStore
                                // projection: later switches need one media read.
                                width = m.width,
                                height = m.height,
                                rotationDegrees = m.rotationDegrees
                            ))
                            // Progress updates come from the IO thread directly:
                            // MutableStateFlow is thread-safe, so no main-thread
                            // hop is needed inside the transaction.
                            if (collected.size % 100 == 0) {
                                publishScanProgress("查询中 ${collected.size} 个媒体")
                            }
                        }
                        publishScanProgress("查询中 ${collected.size} 个媒体")
                        yield()
                    }
                    if (collected.isNotEmpty() && isActive) {
                        db.withTransaction {
                            // 100 rows per INSERT keeps the bound-variable count
                            // well under the 999 limit of older SQLite builds.
                            collected.chunked(100).forEach { chunk ->
                                imageDao.insertAll(chunk)
                                total += chunk.size
                            }
                        }
                    }
                }
                publishScanProgress("")
                refreshCount(groupId)
                refreshImages()
                WallpaperSwitchService.poke(getApplication())
                if (total > 0) {
                    _toastMessage.emit("已导入 $total 个媒体")
                } else {
                    _toastMessage.emit("所选文件夹没有新媒体")
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(TAG, "importScannedFolders failed", e)
                _toastMessage.emit("导入失败: ${e.message}")
                publishScanProgress("")
            }
        }
    }

    private fun isSupportedMedia(name: String): Boolean = MediaTypes.isSupportedName(name)

    private fun detectMediaType(name: String): String = MediaTypes.fromName(name)

    /**
     * Real display name for a picked URI: providers hand out generic segments
     * ("msf:123", "document/123") that carry no extension, so ask the resolver
     * (and DocumentFile as a fallback) before falling back to [fallback].
     */
    private fun resolveDisplayName(uri: Uri, fallback: String): String {
        try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Exception) {
        }
        try {
            androidx.documentfile.provider.DocumentFile
                .fromSingleUri(getApplication(), uri)
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        } catch (_: Exception) {
        }
        return fallback
    }

    /** Media type from the provider MIME type, falling back to the extension. */
    private fun resolveMediaType(uri: Uri, name: String): String =
        MediaTypes.fromMimeOrName(mimeOf(uri), name)

    /** Provider MIME type for [uri], or null when it cannot be resolved. */
    private fun mimeOf(uri: Uri): String? = MediaTypes.mimeOf(getApplication(), uri)
}

/** Single combined state for the home screen (see homeUiState). */
data class HomeUiState(
    val groups: List<WallpaperGroup> = emptyList(),
    val mediaCounts: Map<Long, Int> = emptyMap(),
    val serviceEnabled: Boolean = false,
    /** The lock timer is independent from the home one; both keep the service alive. */
    val lockTimerEnabled: Boolean = true
)

/** Single combined state for the settings screen (see settingsUiState). */
data class SettingsUiState(
    val serviceEnabled: Boolean = false,
    val doubleTapEnabled: Boolean = true,
    val unlockSwitchEnabled: Boolean = false,
    val floatingButtonEnabled: Boolean = false,
    val floatingButtonColor: String = SettingsKeys.FLOATING_BUTTON_COLOR_DEFAULT,
    val floatingButtonAlpha: Int = SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT,
    val floatingButtonText: String = SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT,
    /** Persisted content URI of the custom picture; empty = draw the label. */
    val floatingButtonImageUri: String = "",
    val globalIntervalMs: Long = 60_000L,
    val globalSwitchMode: SwitchMode = SwitchMode.RANDOM,
    val globalScaleMode: ScaleMode = ScaleMode.FIT,
    val clarityMode: String = "auto",
    val switchFadeEnabled: Boolean = true,
    val themeColor: String = "",
    val autoScanEnabled: Boolean = false,
    val autoScanIntervalMs: Long = 24L * 60 * 60 * 1000,
    /** Wall-clock ms of the last auto-scan run; 0 = never. */
    val autoScanLastRunAt: Long = 0L,
    val rotateMismatchEnabled: Boolean = true,
    val rotateMismatchClockwise: Boolean = true,
    // Lock-screen timed switch, independent from the home-screen one.
    val lockTimerEnabled: Boolean = true,
    val lockIntervalMs: Long = 60_000L,
    /** Play the video wallpaper's audio while the wallpaper is visible. */
    val videoSoundEnabled: Boolean = false
)
