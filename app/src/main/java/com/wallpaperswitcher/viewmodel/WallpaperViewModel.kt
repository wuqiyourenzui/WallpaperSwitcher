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

import androidx.annotation.StringRes

import com.wallpaperswitcher.R

import com.wallpaperswitcher.data.*

import com.wallpaperswitcher.engine.FloatingButtonContentPolicy
import com.wallpaperswitcher.engine.MediaScanner

import com.wallpaperswitcher.engine.MediaProbe

import com.wallpaperswitcher.engine.MediaTypes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    /**
     * Localised string for the app's current locale. Toasts and the floating
     * hint are emitted from the ViewModel (outside any composable), so they read
     * their text through [AppLocale.localized] instead of `stringResource`: the
     * plain application context always follows the SYSTEM locale, so every
     * toast/hint would stay in the default language after an in-app switch.
     */
    private fun str(@StringRes id: Int, vararg args: Any): String =
        com.wallpaperswitcher.ui.AppLocale.localized(getApplication()).getString(id, *args)

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
        /** Upper bound of the 最近 N 张不重复 window offered in Settings. */
        const val MAX_RECENT_NO_REPEAT = 50
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

    /** 在线壁纸源 (Bing / URL / WebDAV), newest first. */
    val onlineSources: StateFlow<List<com.wallpaperswitcher.data.OnlineSource>> =
        db.onlineSourceDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 阅读订阅源 (Legado-compatible RSS/Atom feeds), newest first. */
    val rssSources: StateFlow<List<com.wallpaperswitcher.data.RssSource>> =
        db.rssSourceDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val serviceEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.SERVICE_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * 一键暂停: wall-clock ms until which both timed loops hold their ticks
     * (0 = running). Declared here, before [homeUiState] reads it - a property
     * referenced from an earlier initializer would still be uninitialized.
     */
    val pauseUntil: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.PAUSE_UNTIL)
        .map { it?.toLongOrNull() ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

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
    /** In-flight subscription fetch per source (cancelled when it is preempted). */
    private val rssFetchJobs =
        java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    /** Images collected by the full-screen browser mode, consumed by the picker. */
    val rssBrowserResult =
        kotlinx.coroutines.flow.MutableStateFlow<List<String>?>(null)

    fun setRssBrowserResult(urls: List<String>) {
        rssBrowserResult.value = urls
    }

    /**
     * Headers the site's own player used for the streams the browser collected;
     * the downloader replays them so signed CDNs answer instead of 403-ing.
     */
    var rssBrowserHeaders: Map<String, String> = emptyMap()

    /** The stream the full-screen player was actually showing (pre-selected). */
    var rssBrowserSelected: List<String> = emptyList()

    fun consumeRssBrowserResult(): List<String>? {
        val value = rssBrowserResult.value
        rssBrowserResult.value = null
        return value
    }

    /**
     * The article whose full-screen browser is open. Kept here (not in the list
     * screen's `remember`) because that screen is disposed while the browser
     * overlay is showing, which lost the article and the picker never opened.
     */
    var rssBrowserArticle: com.wallpaperswitcher.data.RssArticle? = null

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
                _toastMessage.emit(str(R.string.toast_save_failed, e.message.orEmpty()))
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
                _toastMessage.emit(str(R.string.toast_load_images_failed, e.message.orEmpty()))
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
                    _toastMessage.emit(
                        str(R.string.toast_service_start_failed, e.message.orEmpty())
                    )
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
                _hintMessage.emit(str(R.string.hint_unlock_static_mode))
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
                _toastMessage.emit(str(R.string.toast_create_group_failed, e.message.orEmpty()))
            }
        }
    }

    fun updateGroup(group: WallpaperGroup) {
        guardedWrite("更新分组失败") { groupDao.update(group) }
    }

    fun deleteGroup(group: WallpaperGroup) {
        guardedWrite("删除分组失败") {
            val uris = try {
                imageDao.getUrisByGroup(group.id)
            } catch (_: Throwable) {
                emptyList()
            }
            groupDao.delete(group)
            deleteOwnedMediaFiles(uris)
                // The group's media left the enabled set: drop the SHUFFLE deck's
                // cached id list (see MediaPick.enabledIdsFor).
                com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            // Reuse selectGroup(null) so the loaded list + count are cleared
            // too; otherwise the UI could briefly show the deleted group's
            // residual list.
            if (_selectedGroupId.value == group.id) selectGroup(null)
            clearCursorsOfDeletedMedia()
        }
    }

    /**
     * Delete several groups at once (home screen multi-select).
     *
     * Each group's media ROWS go with it (the Room relation cascades) - the files
     * on the phone are untouched, exactly like the single-group delete.
     */
    fun deleteGroups(ids: Set<Long>) {
        if (ids.isEmpty()) return
        guardedWrite("删除分组失败") {
                com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            val uris = ArrayList<String>()
            ids.forEach { id ->
                groupDao.getGroupById(id)?.let { group ->
                    try {
                        uris.addAll(imageDao.getUrisByGroup(id))
                    } catch (_: Throwable) {
                    }
                    groupDao.delete(group)
                }
            }
            deleteOwnedMediaFiles(uris)
            // The detail screen must not keep showing a group that is now gone.
            if (_selectedGroupId.value?.let { it in ids } == true) selectGroup(null)
            clearCursorsOfDeletedMedia()
        }
    }

    /**
     * Enable/disable several groups at once (home screen multi-select).
     *
     * One [WallpaperSwitchService.poke] at the end instead of one per group: the
     * timer loops only need to re-evaluate which groups may feed each screen, and
     * poking N times would restart those loops N times.
     */
    fun setGroupsEnabled(ids: Set<Long>, enabled: Boolean) {
        if (ids.isEmpty()) return
        guardedWrite("批量切换分组失败") {
                com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            var changed = false
            ids.forEach { id ->
                val group = groupDao.getGroupById(id) ?: return@forEach
                if (group.isEnabled != enabled) {
                    groupDao.update(group.copy(isEnabled = enabled))
                    changed = true
                }
            }
            if (changed) WallpaperSwitchService.poke(getApplication())
        }
    }

    /**
     * Drop cursors / write memos that pointed at media of a group that was just
     * deleted: their rows are gone (CASCADE), so a stale id would make the engine
     * or the lock enforcement chase a media that no longer exists.
     */
    private suspend fun clearCursorsOfDeletedMedia() {
        val lastId = settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID)
        if (lastId > 0L && imageDao.getImageById(lastId) == null) {
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

    /**
     * 分组独立间隔: [intervalMs] = 0 means "follow the screen's global
     * interval" (the default). Anything else makes this group due on its own
     * rhythm (see GroupPacing) - the timer wakes for whichever group is due
     * first, and the switch then only shows THIS group's media.
     */
    fun setGroupInterval(groupId: Long, intervalMs: Long) {
        guardedWrite("设置分组间隔失败") {
            groupDao.updateInterval(groupId, intervalMs.coerceAtLeast(0L))
            AppLog.d(TAG, "setGroupInterval: group=$groupId interval=${intervalMs}ms")
            WallpaperSwitchService.poke(getApplication())
        }
    }

    /**
     * 时间规则: the minutes-of-day window this group may be shown in
     * (-1 = 全天). Out-of-window groups are skipped by the scheduler instead of
     * being switched to.
     */
    fun setGroupActiveWindow(groupId: Long, fromMinute: Int, toMinute: Int) {
        guardedWrite("设置分组时段失败") {
            val group = groupDao.getGroupById(groupId) ?: return@guardedWrite
            groupDao.update(
                group.copy(
                    activeFromMinute = fromMinute.coerceIn(-1, 1439),
                    activeToMinute = toMinute.coerceIn(-1, 1439)
                )
            )
            AppLog.d(TAG, "setGroupActiveWindow: group=$groupId $fromMinute..$toMinute")
            WallpaperSwitchService.poke(getApplication())
        }
    }

    /**
     * 时间规则 · 星期: bitmask with bit 0 = Monday … bit 6 = Sunday.
     * `0` or the full mask means "every day".
     */
    fun setGroupActiveDays(groupId: Long, daysMask: Int) {
        guardedWrite("保存分组星期失败") {
            val group = groupDao.getGroupById(groupId) ?: return@guardedWrite
            val mask = daysMask and com.wallpaperswitcher.engine.GroupRules.ALL_DAYS
            groupDao.update(
                group.copy(
                    activeDays = if (mask == com.wallpaperswitcher.engine.GroupRules.ALL_DAYS) {
                        0
                    } else {
                        mask
                    }
                )
            )
            AppLog.d(TAG, "setGroupActiveDays: group=$groupId mask=$mask")
            WallpaperSwitchService.poke(getApplication())
        }
    }

    /**
     * 分组素材类型: "" = 两者都切换, "IMAGE" = 仅图片, "MOTION" = 仅视频（含 GIF）.
     * 只影响这个分组的取图，桌面/锁屏各自的节奏与全局模式都不变。
     */
    fun setGroupMediaFilter(groupId: Long, filter: String) {
        guardedWrite("保存分组素材设置失败") {
            val group = groupDao.getGroupById(groupId) ?: return@guardedWrite
            val safe = when (filter) {
                com.wallpaperswitcher.engine.GroupRules.MEDIA_IMAGE,
                com.wallpaperswitcher.engine.GroupRules.MEDIA_VIDEO -> filter
                else -> ""
            }
            groupDao.update(group.copy(filterMode = safe))
            com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            AppLog.d(TAG, "setGroupMediaFilter: group=$groupId filter=$safe")
            WallpaperSwitchService.poke(getApplication())
        }
    }

    // --- 一键暂停（稍后切换）--- (restored after the accidental edit)

    fun snooze(durationMs: Long) {
        val safe = durationMs.coerceIn(60_000L, 7L * 24 * 60 * 60 * 1000)
        guardedWrite("暂停失败") {
            settingsDao.setLong(SettingsKeys.PAUSE_STARTED_AT, System.currentTimeMillis())
            settingsDao.setLong(SettingsKeys.PAUSE_UNTIL, System.currentTimeMillis() + safe)
            AppLog.d(TAG, "snooze: ${safe}ms")
            WallpaperSwitchService.poke(getApplication())
        }
    }

    /** 暂停到明天早上 8 点。 */
    fun snoozeUntilMorning() {
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 8)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis + 60_000L) {
            target.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        pauseUntilAt(target.timeInMillis)
    }

    private fun pauseUntilAt(wallClockMs: Long) {
        guardedWrite("暂停失败") {
            settingsDao.setLong(SettingsKeys.PAUSE_STARTED_AT, System.currentTimeMillis())
            settingsDao.setLong(SettingsKeys.PAUSE_UNTIL, wallClockMs)
            AppLog.d(TAG, "pauseUntil: $wallClockMs")
            WallpaperSwitchService.poke(getApplication())
        }
    }

    fun resumeNow() {
        guardedWrite("继续失败") {
            settingsDao.setLong(SettingsKeys.PAUSE_UNTIL, 0L)
            AppLog.d(TAG, "resumeNow")
            WallpaperSwitchService.poke(getApplication())
        }
    }

    // --- 下一张预览 ---

    private val _previewImage = MutableStateFlow<WallpaperImage?>(null)
    val previewImage: StateFlow<WallpaperImage?> = _previewImage
    private val _previewLoading = MutableStateFlow(false)
    val previewLoading: StateFlow<Boolean> = _previewLoading
    private val _previewVisible = MutableStateFlow(false)
    val previewVisible: StateFlow<Boolean> = _previewVisible
    /** 预览针对哪一块屏（HOME/LOCK）：确认时写回同一块。 */
    private val _previewSlot = MutableStateFlow(WallpaperTarget.SLOT_HOME)
    val previewSlot: StateFlow<String> = _previewSlot
    private var previewJob: Job? = null

    /** 预览下一张: read-only (no cursor move, no wallpaper write). */
    fun previewNext() {
        _previewVisible.value = true
        if (previewJob?.isActive == true) return
        previewJob = viewModelScope.launch {
            _previewLoading.value = true
            try {
                _previewImage.value = com.wallpaperswitcher.engine.NextPreview.nextHome(db)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "previewNext failed", e)
                _previewImage.value = null
            } finally {
                _previewLoading.value = false
            }
        }
    }

    fun dismissPreview() {
        _previewVisible.value = false
    }

    fun applyPreview() {
        val image = _previewImage.value
        _previewVisible.value = false
        if (image != null) setImageAsWallpaper(image)
    }

    // --- 新功能：回滚 / 预览 / 收藏 / 大图浏览 ---

    /**
     * 「下一张预览」的带槽位版本：悬浮按钮长按时要按**那块屏**预览
     * （桌面走 HOME 的选择路径、锁屏走 LOCK 的），确认后写到同一块屏。
     */
    fun previewNextFor(slot: String) {
        _previewVisible.value = true
        _previewSlot.value = slot
        if (previewJob?.isActive == true) return
        previewJob = viewModelScope.launch {
            _previewLoading.value = true
            try {
                _previewImage.value = com.wallpaperswitcher.engine.NextPreview
                    .nextForSlot(db, slot)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "previewNextFor($slot) failed", e)
                _previewImage.value = null
            } finally {
                _previewLoading.value = false
            }
        }
    }

    /** 长按预览确认：把预览到的那张写到 [slot]（用户主动选，会重锚该屏定时）。 */
    fun applyPreviewTo(slot: String) {
        val image = _previewImage.value
        _previewVisible.value = false
        if (image == null) {
            AppLog.d(TAG, "applyPreviewTo($slot): nothing previewed")
            return
        }
        setImageAsWallpaper(image, slot)
    }

    /** 「最近显示」：这一屏（或两屏）最近显示过的媒体，新的在前。 */    suspend fun recentShown(limit: Int = 60, slot: String? = null): List<WallpaperImage> =
        withContext(Dispatchers.IO) {
            try {
                db.recentDao().recentMedia(slot, limit).map { it.image }
            } catch (e: Exception) {
                AppLog.w(TAG, "recentShown failed: ${e.javaClass.simpleName}")
                emptyList()
            }
        }

    /**
     * 「最近显示」带回滚需要的行级信息：媒体本体 + 哪块屏 + 什么时候显示的。
     *
     * 合并两屏（slot = null）时同一个 mediaId 可能出现两行（HOME/LOCK 各一次），
     * 去重交给调用方（它才知道要按屏分组还是只留最新）。
     */
    suspend fun recentShownRows(limit: Int = 60, slot: String? = null): List<RecentShownEntry> =
        withContext(Dispatchers.IO) {
            try {
                db.recentDao().recentMedia(slot, limit)
            } catch (e: Exception) {
                AppLog.w(TAG, "recentShownRows failed: ${e.javaClass.simpleName}")
                emptyList()
            }
        }

    /** 收藏聚合（跨分组，按 uri 去重）。 */
    val favorites: StateFlow<List<WallpaperImage>> = imageDao.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- 存储与流量守门 ---

    /**
     * 存储占用统计（挂起，进页面时算一次）。
     *
     * 分三类是因为它们的清理方式完全不同：
     *  - `rss` / `online`：应用私有目录里下载的订阅源 / 在线源媒体，**可以**被清；
     *  - 相册 / 文件夹来源：只登记 uri，清不动也不该动。
     */
    suspend fun storageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        val filesDir = getApplication<android.app.Application>().filesDir
        fun scan(sub: String): StorageDirUsage {
            val root = java.io.File(filesDir, sub)
            if (!root.isDirectory) return StorageDirUsage(sub, 0, 0L)
            var count = 0
            var bytes = 0L
            root.walkTopDown().filter { it.isFile }.forEach {
                count++
                bytes += it.length()
            }
            return StorageDirUsage(sub, count, bytes)
        }
        try {
            StorageUsage(rss = scan("rss"), online = scan("online"))
        } catch (e: Exception) {
            AppLog.w(TAG, "storageUsage failed: ${e.javaClass.simpleName}")
            StorageUsage()
        }
    }

    /**
     * 只统计、不删除：`files/rss`、`files/online` 里数据库已不再引用的文件有多少。
     *
     * 与 [cleanOrphanMedia] 共用同一套判断（引用集合 + 10 分钟保护期），所以界面上
     * 「可清理 280MB」和实际释放量不会对不上。
     */
    suspend fun measureOrphanMedia(): StorageCleanResult = withContext(Dispatchers.IO) {
        try {
            val scan = com.wallpaperswitcher.engine.OwnedMediaCleaner
                .measureOrphans(getApplication())
            StorageCleanResult(scan.files, scan.bytes)
        } catch (e: Exception) {
            AppLog.w(TAG, "measureOrphanMedia failed: ${e.javaClass.simpleName}")
            StorageCleanResult(0, 0L)
        }
    }

    /**
     * 清理"孤儿文件"：数据库里已经没有引用的订阅源 / 在线源下载文件。
     *
     * 复用启动时那次扫描的同一套判断（见
     * [com.wallpaperswitcher.engine.OwnedMediaCleaner]），先算大小再删，
     * 这样界面能告诉用户"释放了多少"，而不是只报"清理完成"。
     */
    suspend fun cleanOrphanMedia(): StorageCleanResult = withContext(Dispatchers.IO) {
        val before = try {
            com.wallpaperswitcher.engine.OwnedMediaCleaner.measureOrphans(getApplication())
        } catch (e: Exception) {
            AppLog.w(TAG, "measureOrphans failed: ${e.javaClass.simpleName}")
            com.wallpaperswitcher.engine.OwnedMediaCleaner.OrphanScan(0, 0L)
        }
        try {
            com.wallpaperswitcher.engine.OwnedMediaCleaner.sweep(getApplication())
        } catch (e: Exception) {
            AppLog.w(TAG, "cleanOrphanMedia failed: ${e.javaClass.simpleName}")
        }
        StorageCleanResult(before.files, before.bytes)
    }

    /** 收藏/取消收藏（大图浏览的双击、收藏页的按钮都走这里）。 */
    fun setFavorite(mediaId: Long, favorite: Boolean) {
        guardedWrite("收藏失败") {
            imageDao.setFavorite(mediaId, favorite)
        }
    }

    /**
     * 收藏/取消收藏**整张图**（按 uri，覆盖它在所有分组里的行）。
     *
     * 界面是按 uri 判断星标的（收藏页跨分组聚合、大图浏览比对 favorites），
     * 而同一个文件可以在多个分组里各有一行 —— 只改当前这一行，在 B 组里
     * "取消收藏"改的是本来就 =0 的那行：星标不动，提示却说已取消，怎么点都去不掉。
     */
    fun setFavoriteByUri(uri: String, favorite: Boolean) {
        guardedWrite("收藏失败") {
            imageDao.setFavoriteByUri(uri, favorite)
        }
    }

    // --- Settings writers (restored) ---

    fun setGlobalInterval(ms: Long) {
        guardedWrite("保存切换间隔失败") {
            settingsDao.setLong(
                SettingsKeys.GLOBAL_INTERVAL_MS,
                ms.coerceAtLeast(com.wallpaperswitcher.engine.SwitchSchedule.MIN_INTERVAL_MS)
            )
            WallpaperSwitchService.poke(getApplication())
        }
    }

    fun setLockInterval(ms: Long) {
        guardedWrite("保存锁屏间隔失败") {
            settingsDao.setLong(
                SettingsKeys.LOCK_INTERVAL_MS,
                ms.coerceAtLeast(com.wallpaperswitcher.engine.SwitchSchedule.MIN_INTERVAL_MS)
            )
            WallpaperSwitchService.poke(getApplication())
        }
    }

    fun toggleLockTimer(enabled: Boolean) {
        guardedWrite("切换锁屏定时失败") {
            settingsDao.setBool(SettingsKeys.LOCK_TIMER_ENABLED, enabled)
            if (enabled) WallpaperSwitchService.ensureRunning(getApplication())
            WallpaperSwitchService.poke(getApplication())
        }
    }

    fun setGlobalSwitchMode(mode: SwitchMode) {
        guardedWrite("保存切换模式失败") {
            settingsDao.setString(SettingsKeys.GLOBAL_SWITCH_MODE, mode.name)
            com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            WallpaperSwitchService.poke(getApplication())
        }
    }

    fun setGlobalScaleMode(mode: ScaleMode) {
        guardedWrite("保存缩放模式失败") {
            settingsDao.setString(SettingsKeys.GLOBAL_SCALE_MODE, mode.name)
        }
    }

    fun setClarityMode(mode: String) {
        guardedWrite("保存清晰度设置失败") {
            settingsDao.setString(SettingsKeys.CLARITY_MODE, mode)
        }
    }

    fun toggleRotateMismatch(enabled: Boolean) {
        guardedWrite("保存自动旋转设置失败") {
            settingsDao.setBool(SettingsKeys.ROTATE_MISMATCH_ENABLED, enabled)
        }
    }

    fun setRotateMismatchClockwise(clockwise: Boolean) {
        guardedWrite("保存旋转方向失败") {
            settingsDao.setBool(SettingsKeys.ROTATE_MISMATCH_CW, clockwise)
        }
    }

    fun setSwitchTransition(value: String) {
        val safe = when (value) {
            SettingsKeys.SWITCH_TRANSITION_SLIDE,
            SettingsKeys.SWITCH_TRANSITION_ZOOM,
            SettingsKeys.SWITCH_TRANSITION_NONE -> value
            else -> SettingsKeys.SWITCH_TRANSITION_FADE
        }
        guardedWrite("保存过渡动画失败") {
            settingsDao.setString(SettingsKeys.SWITCH_TRANSITION, safe)
            settingsDao.setBool(
                SettingsKeys.SWITCH_FADE_ENABLED,
                safe != SettingsKeys.SWITCH_TRANSITION_NONE
            )
            LiveWallpaperService.applyTransitionFromSettings(getApplication(), safe)
        }
    }

    fun setScenePauseOnPowerSave(enabled: Boolean) {
        guardedWrite("保存场景规则失败") {
            settingsDao.setBool(SettingsKeys.SCENE_PAUSE_ON_POWER_SAVE, enabled)
            WallpaperSwitchService.poke(getApplication())
        }
    }

    fun setScenePauseOnLowBattery(enabled: Boolean) {
        guardedWrite("保存场景规则失败") {
            settingsDao.setBool(SettingsKeys.SCENE_PAUSE_ON_LOW_BATTERY, enabled)
            WallpaperSwitchService.poke(getApplication())
        }
    }

    fun setVideoSoundEnabled(enabled: Boolean) {
        guardedWrite("保存视频声音设置失败") {
            settingsDao.setBool(SettingsKeys.VIDEO_SOUND_ENABLED, enabled)
        }
    }

    fun setVideoPlayToEnd(enabled: Boolean) {
        guardedWrite("保存视频播完再切设置失败") {
            settingsDao.setBool(SettingsKeys.VIDEO_PLAY_TO_END, enabled)
        }
    }

    fun toggleAutoScan(enabled: Boolean, intervalMs: Long) {
        guardedWrite("保存自动扫描设置失败") {
            val safeInterval = intervalMs.coerceAtLeast(15 * 60_000L)
            settingsDao.setBool(SettingsKeys.AUTO_SCAN_ENABLED, enabled)
            settingsDao.setLong(SettingsKeys.AUTO_SCAN_INTERVAL_MS, safeInterval)
            val work = WorkManager.getInstance(getApplication())
            if (enabled) {
                work.enqueueUniquePeriodicWork(
                    "folder_auto_scan",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<FolderAutoScanWorker>(
                        safeInterval, TimeUnit.MILLISECONDS
                    ).setConstraints(Constraints.NONE).build()
                )
            } else {
                work.cancelUniqueWork("folder_auto_scan")
            }
        }
    }

    // --- 在线壁纸源 (Bing 每日图 / 指定 URL / WebDAV) ---

    /**
     * Create / update one online source.
     *
     * @param plainPassword null = keep the stored password (an edit that left
     *   the field untouched), "" = clear it, otherwise the new password. The
     *   value is encrypted here with the Android Keystore; the database never
     *   sees the plaintext (see OnlineSecretStore).
     */
    fun saveOnlineSource(
        source: com.wallpaperswitcher.data.OnlineSource,
        plainPassword: String?,
    ) {
        guardedWrite("保存在线壁纸源失败") {
            val typeName = str(
                when (source.type) {
                    com.wallpaperswitcher.data.OnlineSource.TYPE_BING ->
                        R.string.online_type_bing
                    com.wallpaperswitcher.data.OnlineSource.TYPE_MEIRENTU ->
                        R.string.online_type_meirentu
                    com.wallpaperswitcher.data.OnlineSource.TYPE_WEBDAV ->
                        R.string.online_type_webdav
                    else -> R.string.online_type_url
                }
            )
            val safe = source.copy(
                name = source.name.trim().ifBlank { typeName },
                intervalMinutes = com.wallpaperswitcher.engine.OnlineSourceRules
                    .normalizeIntervalMinutes(source.intervalMinutes),
                keepCount = com.wallpaperswitcher.engine.OnlineSourceRules
                    .normalizeKeepCount(source.keepCount),
                url = source.url.trim(),
                webdavUrl = source.webdavUrl.trim(),
                webdavPath = source.webdavPath.trim().trim('/'),
                username = source.username.trim(),
            )
            val stored = if (plainPassword == null) {
                safe
            } else {
                safe.copy(
                    passwordCipher = com.wallpaperswitcher.engine.OnlineSecretStore
                        .encrypt(plainPassword)
                )
            }
            val id = if (stored.id > 0L) {
                db.onlineSourceDao().update(stored)
                stored.id
            } else {
                db.onlineSourceDao().insert(stored)
            }
            com.wallpaperswitcher.engine.OnlineSourceScheduler
                .schedule(getApplication(), stored.copy(id = id))
            _toastMessage.emit(str(R.string.online_saved))
        }
    }

    /** Remove a source, its downloaded files and its media rows. */
    fun deleteOnlineSource(source: com.wallpaperswitcher.data.OnlineSource) {
        guardedWrite("删除在线壁纸源失败") {
            com.wallpaperswitcher.engine.OnlineSourceScheduler
                .cancel(getApplication(), source.id)
            com.wallpaperswitcher.engine.OnlineSync.purgeSource(getApplication(), source.id)
            db.onlineSourceDao().delete(source.id)
        }
    }

    /** 立即更新: one-shot fetch that ignores the periodic interval. */
    fun refreshOnlineSource(source: com.wallpaperswitcher.data.OnlineSource) {
        if (!source.enabled) {
            viewModelScope.launch {
                try {
                    _toastMessage.emit(str(R.string.online_error_disabled))
                } catch (_: Exception) {
                }
            }
            return
        }
        com.wallpaperswitcher.engine.OnlineSourceScheduler
            .refreshNow(getApplication(), source.id)
        viewModelScope.launch {
            try {
                _toastMessage.emit(str(R.string.online_refresh_started))
            } catch (_: Exception) {
            }
        }
    }

    // --- 阅读订阅源 (Legado-compatible) ---

    /** Articles of one subscription, newest first. */
    fun rssArticles(sourceId: Long): kotlinx.coroutines.flow.Flow<List<com.wallpaperswitcher.data.RssArticle>> =
        db.rssArticleDao().observeBySource(sourceId)

    /** Articles of one subscription category ("" = plain feed). */
    fun rssArticlesOfSort(
        sourceId: Long,
        sort: String,
    ): kotlinx.coroutines.flow.Flow<List<com.wallpaperswitcher.data.RssArticle>> =
        db.rssArticleDao().observeBySourceSort(sourceId, sort)

    /** 阅读 categories of a source (`sortUrl` entries); empty for plain feeds. */
    fun rssCategoryNames(source: com.wallpaperswitcher.data.RssSource): List<String> =
        com.wallpaperswitcher.engine.legado.RssCategories.names(source)

    /** Cached categories (instant) for the chips row. */
    suspend fun rssCachedCategories(source: com.wallpaperswitcher.data.RssSource): List<String> =
        com.wallpaperswitcher.engine.legado.RssCategories.cachedNames(getApplication(), source)

    /** Cache-only categories: never evaluates the source's `<js>` sortUrl. */
    suspend fun rssCachedCategoriesOnly(source: com.wallpaperswitcher.data.RssSource): List<String> =
        com.wallpaperswitcher.engine.legado.RssCategories.cachedOnly(getApplication(), source)

    /** Recompute + cache the categories (keeps the old list on failure). */
    suspend fun rssRefreshCategories(source: com.wallpaperswitcher.data.RssSource): List<String> =
        com.wallpaperswitcher.engine.legado.RssCategories.refreshNames(getApplication(), source)

    /** Index of the category the user last picked for this source. */
    suspend fun rssSelectedCategory(sourceId: Long): Int =
        com.wallpaperswitcher.engine.legado.RssCategories.selectedIndex(getApplication(), sourceId)

    /**
     * Remember the picked category and refresh that source so the list shows
     * the new category. Old articles of other categories stay cached (they are
     * filtered out by `sort`).
     */
    /**
     * Switch category: remember the choice and fetch **one** page so the list
     * appears quickly; the rest is loaded lazily by scrolling.
     */
    suspend fun rssSelectCategory(source: com.wallpaperswitcher.data.RssSource, index: Int) {
        // Preempt: whatever this source was fetching (previous category tap or
        // a load-more) is cancelled so the new category starts immediately.
        rssFetchJobs.remove(source.id)?.cancel()
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.let { rssFetchJobs[source.id] = it }
        com.wallpaperswitcher.engine.legado.RssCategories
            .setSelectedIndex(getApplication(), source.id, index)
        // A new category starts from its own first page.
        com.wallpaperswitcher.engine.legado.RssPaging
            .setCursor(getApplication(), source.id, null)
        try {
            com.wallpaperswitcher.engine.RssSync
                // 2 pages: index-based sources fetch page 2 concurrently with
                // page 1 (see LegadoRss prefetch), so this is ~1 round trip
                // while doubling the list the user sees immediately.
                .refresh(getApplication(), source.id, initialPages = 2)
        } catch (_: Exception) {
        }
    }

    /** Stored article count of a source (0 = brand-new, nothing cached yet). */
    suspend fun rssArticleCount(sourceId: Long): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                db.rssArticleDao().count(sourceId)
            } catch (_: Throwable) {
                0
            }
        }

    /** Stored article count of one category (0 = this category was never loaded). */
    suspend fun rssArticleCountOfSort(sourceId: Long, sort: String): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                db.rssArticleDao().countOfSort(sourceId, sort)
            } catch (_: Throwable) {
                0
            }
        }

    /**
     * 订阅源不做本地缓存：离开源界面时把这个源的文章行（含正文缓存）删掉，
     * 只保留用户加入分组的壁纸文件。放在 viewModelScope 里执行，界面已经销毁
     * 也能删干净。
     */
    fun clearRssSourceCache(sourceId: Long) {
        viewModelScope.launch {
            try {
                db.rssArticleDao().deleteBySource(sourceId)
                com.wallpaperswitcher.util.AppLog.d(
                    "RssCache",
                    "cleared cached articles of source=$sourceId",
                )
            } catch (_: Throwable) {
            }
        }
    }

    /** 订阅导入图片的自定义下载目录（"" = 应用私有目录）。 */
    suspend fun rssDownloadDirValue(): String =
        com.wallpaperswitcher.engine.RssDownloadDir.load(getApplication())

    fun setRssDownloadDir(treeUri: String) {
        viewModelScope.launch {
            try {
                com.wallpaperswitcher.engine.RssDownloadDir.save(getApplication(), treeUri)
                _toastMessage.emit(
                    str(
                        if (treeUri.isBlank()) R.string.settings_rss_download_dir_reset_done
                        else R.string.settings_rss_download_dir_saved
                    )
                )
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Refresh when the user opens a source ("点击进去就加载").
     * Returns true when the source was fetched successfully.
     */
    suspend fun rssRefreshOnOpen(sourceId: Long): Boolean {
        return try {
            val report = com.wallpaperswitcher.engine.RssSync.refresh(getApplication(), sourceId)
            if (!report.ok) {
                try {
                    _toastMessage.emit(
                        str(R.string.rss_refresh_failed, rssErrorText(report.reason))
                    )
                } catch (_: Exception) {
                }
            }
            report.ok
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** True when the source's article list still has pages left to load. */
    suspend fun rssHasMore(sourceId: Long): Boolean =
        com.wallpaperswitcher.engine.legado.RssPaging.hasMore(getApplication(), sourceId)

    /**
     * 阅读-style "load more": append the next page(s) of the article list.
     * Returns true when there are still more pages afterwards.
     */
    suspend fun rssLoadMore(sourceId: Long): Boolean {
        rssFetchJobs.remove(sourceId)?.cancel()
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.let { rssFetchJobs[sourceId] = it }
        return try {
            val report = com.wallpaperswitcher.engine.RssSync.loadMore(getApplication(), sourceId)
            if (!report.ok) {
                try {
                    _toastMessage.emit(
                        str(R.string.rss_refresh_failed, rssErrorText(report.reason))
                    )
                } catch (_: Exception) {
                }
            }
            report.ok && com.wallpaperswitcher.engine.legado.RssPaging
                .hasMore(getApplication(), sourceId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Same as [rssLoadMore] but runs in the ViewModel scope: the footer of the
     * article list triggers paging from a `LaunchedEffect`, and scrolling the
     * footer out of view cancelled the fetch mid-flight ("The coroutine scope
     * left the composition") so pages silently never arrived.
     */
    fun requestRssLoadMore(sourceId: Long, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val more = rssLoadMore(sourceId)
            onDone(more)
        }
    }

    fun addRssSource(name: String, url: String) {
        guardedWrite("添加订阅源失败") {
            val safeUrl = url.trim()
            if (safeUrl.isEmpty()) return@guardedWrite
            val safeName = name.trim().ifBlank {
                safeUrl.substringAfter("//").substringBefore('/').ifBlank { safeUrl }
            }
            db.rssSourceDao().insert(
                com.wallpaperswitcher.data.RssSource(name = safeName, url = safeUrl)
            )
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(getApplication())
            _toastMessage.emit(str(R.string.rss_saved))
        }
    }

    fun deleteRssSource(source: com.wallpaperswitcher.data.RssSource) {
        guardedWrite("删除订阅源失败") {
            db.rssSourceDao().delete(source.id)
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(getApplication())
        }
    }

    fun setRssSourceEnabled(source: com.wallpaperswitcher.data.RssSource, enabled: Boolean) {
        guardedWrite("切换订阅源失败") {
            db.rssSourceDao().update(source.copy(enabled = enabled))
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(getApplication())
        }
    }

    /**
     * 批量删除订阅源。导入到分组里的图片/视频**不删**（它们是用户选中的壁纸），
     * 之后可以照常在分组里使用或手动删除。
     */
    fun deleteRssSources(ids: Set<Long>) {
        if (ids.isEmpty()) return
        guardedWrite("删除订阅源失败") {
            for (id in ids) {
                db.rssSourceDao().delete(id)
            }
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(getApplication())
        }
    }

    /** Refresh one subscription now and report the result as a toast. */
    fun refreshRssSource(source: com.wallpaperswitcher.data.RssSource) {
        viewModelScope.launch {
            try {
                val report = com.wallpaperswitcher.engine.RssSync
                    .refresh(getApplication(), source.id)
                val message = if (report.ok) {
                    str(R.string.rss_refresh_done, report.added)
                } else {
                    str(R.string.rss_refresh_failed, rssErrorText(report.reason))
                }
                _toastMessage.emit(message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _toastMessage.emit(
                    str(R.string.rss_refresh_failed, str(R.string.online_error_unknown))
                )
            }
        }
    }

    /** Localized text of a feed failure code (see OnlineSourceRules.decodeResult). */
    private fun rssErrorText(reason: String): String = when (reason) {
        "network" -> str(R.string.online_error_network)
        "timeout" -> str(R.string.online_error_timeout)
        "ssl" -> str(R.string.online_error_ssl)
        "auth" -> str(R.string.online_error_auth)
        "forbidden" -> str(R.string.online_error_forbidden)
        "not_found" -> str(R.string.online_error_not_found)
        "rate_limited" -> str(R.string.online_error_rate_limited)
        "server" -> str(R.string.online_error_server)
        "https_required" -> str(R.string.online_error_https_required)
        "bad_url" -> str(R.string.online_error_bad_url)
        "parse" -> str(R.string.online_error_parse)
        "empty" -> str(R.string.online_error_empty)
        else -> str(R.string.online_error_unknown)
    }

    /**
     * Import 阅读 (Legado) subscription sources: a JSON array / object, a
     * `legado://` share link with inline JSON/base64, or a share link whose
     * `src` is a remote JSON file (downloaded first).
     */
    fun importLegadoSources(text: String) {
        guardedWrite("导入订阅源失败") {
            var result = com.wallpaperswitcher.engine.LegadoImport.parse(text)
            if (result.sources.isEmpty()) {
                // legado:// 分享链接里的 src=<url>，或用户直接粘贴的订阅地址。
                val remote = com.wallpaperswitcher.engine.LegadoImport
                    .remoteUrlToFetch(text)
                if (remote != null) {
                    val body = com.wallpaperswitcher.engine.RssFetcher.fetchText(remote)
                    result = com.wallpaperswitcher.engine.LegadoImport.parse(body)
                }
            }
            if (result.sources.isEmpty()) {
                _toastMessage.emit(str(R.string.rss_import_failed))
                return@guardedWrite
            }
            for (source in result.sources) {
                db.rssSourceDao().insert(source)
            }
            com.wallpaperswitcher.engine.RssScheduler.ensureScheduled(getApplication())
            _toastMessage.emit(
                str(R.string.rss_import_done, result.sources.size, result.skipped)
            )
        }
    }

    fun markRssArticleRead(sourceId: Long, guid: String) {
        guardedWrite("标记已读失败") {
            db.rssArticleDao().markRead(sourceId, guid)
        }
    }

    /** Fetch the article body + images on demand (ruleContent). */
    suspend fun loadRssArticleContent(
        article: com.wallpaperswitcher.data.RssArticle,
        force: Boolean = false,
    ): com.wallpaperswitcher.engine.RssSync.ArticleContent =
        com.wallpaperswitcher.engine.RssSync.fetchContent(getApplication(), article, force)

    /** Stream the rest of a script-driven gallery in (see RssSync.loadGalleryImages). */
    suspend fun loadRssGalleryImages(
        article: com.wallpaperswitcher.data.RssArticle,
        pageHtml: String,
        baseHtml: String,
    ): List<String> =
        com.wallpaperswitcher.engine.RssSync
            .loadGalleryImages(getApplication(), article, pageHtml, baseHtml)

    /** Download the ticked article images into [groupId] (0 = 在线壁纸 group). */
    fun addRssImagesToGroup(
        article: com.wallpaperswitcher.data.RssArticle,
        urls: List<String>,
        groupId: Long,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        guardedWrite("加入分组失败") {
            val source = try {
                db.rssSourceDao().getById(article.sourceId)
            } catch (_: Exception) {
                null
            }
            val report = com.wallpaperswitcher.engine.RssMediaImporter
                .importImages(getApplication(), source, urls, groupId, extraHeaders)
            val message = if (report.failed > 0) {
                str(R.string.rss_add_to_group_partial, report.added, report.failed)
            } else {
                str(R.string.rss_add_to_group_done, report.added)
            }
            _toastMessage.emit(message)
        }
    }

    /** The URL the interactive login WebView should open. */
    fun rssLoginEndpoint(source: com.wallpaperswitcher.data.RssSource): String =
        com.wallpaperswitcher.engine.legado.LegadoRss.loginEndpoint(source)

    /** 阅读 `loginCheckJs`：判断当前页面是否已登录的脚本；没有则返回 null。 */
    fun rssLoginCheckJs(source: com.wallpaperswitcher.data.RssSource): String? =
        com.wallpaperswitcher.engine.legado.LegadoRss.loginCheckJs(source)

    /** True when the source logs in with a `@js:` / `<js>` script. */
    fun rssLoginIsScript(source: com.wallpaperswitcher.data.RssSource): Boolean =
        com.wallpaperswitcher.engine.legado.LegadoRss.isJsLogin(rssLoginRaw(source))

    private fun rssLoginRaw(source: com.wallpaperswitcher.data.RssSource): String? =
        try {
            val map = com.wallpaperswitcher.engine.legado.LegadoRss.sourceFields(source.rawJson)
            map?.get("loginUrl") as? String
        } catch (_: Exception) {
            null
        }

    /**
     * 阅读 `loginUi`: the login form definition, `[{"name":"账号","type":"text"}]`.
     * Returns name → input type pairs in declaration order.
     */
    fun rssLoginFields(source: com.wallpaperswitcher.data.RssSource): List<Pair<String, String>> {
        val raw = try {
            val map = com.wallpaperswitcher.engine.legado.LegadoRss.sourceFields(source.rawJson)
            map?.get("loginUi") as? String
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return try {
            (com.wallpaperswitcher.engine.Json.parse(raw) as? List<*>)
                ?.mapNotNull { item ->
                    val field = item as? Map<*, *> ?: return@mapNotNull null
                    val name = field["name"] as? String ?: return@mapNotNull null
                    val type = (field["type"] as? String) ?: "text"
                    if (name.isBlank() || type == "button") null else name to type
                }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Values saved by an earlier login, used to pre-fill the form. */
    fun rssLoginSavedValues(
        source: com.wallpaperswitcher.data.RssSource
    ): Map<String, String> =
        com.wallpaperswitcher.engine.legado.LegadoRss.loginInfoMap(source.id)

    /**
     * Runs the source's JS login (阅读 `source.login()`); the values typed into
     * the `loginUi` form are stored first, exactly like Legado saves them.
     * Returns the error message, or null on success.
     */
    suspend fun rssLoginRunScript(
        source: com.wallpaperswitcher.data.RssSource,
        values: Map<String, String>,
    ): String? = withContext(Dispatchers.IO) {
        com.wallpaperswitcher.engine.legado.LegadoRss.saveLoginInfo(source.id, values)
        val script = com.wallpaperswitcher.engine.legado.LegadoRss
            .loginScript(rssLoginRaw(source) ?: "")
            ?: return@withContext "unsupported"
        val result = com.wallpaperswitcher.engine.legado.LegadoRss.runLoginScript(source, script)
        if (result.error == null) rssLoginCompleted(source)
        result.error
    }

    /**
     * Saves the subscription-source editor: the row fields (name/url/type/
     * enabled) plus the edited 阅读 fields merged into the original JSON.
     * [rawOverride] replaces the whole JSON when the user edited it directly.
     * Returns a localized error message, or null on success.
     */
    suspend fun rssSourceSave(
        source: com.wallpaperswitcher.data.RssSource,
        name: String,
        url: String,
        type: Int,
        enabled: Boolean,
        changes: Map<String, String?>,
        rawOverride: String?,
    ): String? = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return@withContext str(R.string.rss_edit_url_invalid)
        }
        val raw = if (!rawOverride.isNullOrBlank()) {
            try {
                com.wallpaperswitcher.engine.Json.parse(rawOverride)
                rawOverride
            } catch (_: Exception) {
                return@withContext str(R.string.rss_edit_raw_invalid)
            }
        } else {
            val withText = com.wallpaperswitcher.engine.legado.RssSourceEditor
                .applyChanges(source.rawJson, changes)
            // The JSON keeps its own copies of the name/URL (阅读's sourceName /
            // sourceUrl). The URL always follows the form; the name is only
            // written when it was actually changed, so saving an unrelated edit
            // cannot overwrite a nicer title the import carried (old sources
            // were imported before `sourceName` was read).
            val displayName = name.trim().ifBlank { trimmedUrl }
            val identity = HashMap<String, String?>()
            identity["sourceUrl"] = trimmedUrl
            if (displayName != source.name) identity["sourceName"] = displayName
            val withIdentity = com.wallpaperswitcher.engine.legado.RssSourceEditor.applyChanges(
                withText,
                identity,
            )
            com.wallpaperswitcher.engine.legado.RssSourceEditor.applyTypedChanges(
                withIdentity,
                mapOf("type" to type.toLong(), "enabled" to enabled),
            )
        }
        val updated = source.copy(
            name = name.trim().ifBlank { trimmedUrl },
            url = trimmedUrl,
            type = type,
            enabled = enabled,
            rawJson = raw,
        )
        try {
            db.rssSourceDao().update(updated)
        } catch (_: Throwable) {
            return@withContext str(R.string.rss_edit_save_failed)
        }
        // Rules/URL may have changed: start paging from the top again.
        com.wallpaperswitcher.engine.legado.RssPaging.setCursor(getApplication(), source.id, null)
        _toastMessage.emit(str(R.string.rss_edit_saved))
        null
    }

    /** Called after the user finished logging in: refresh this source. */
    fun rssLoginCompleted(source: com.wallpaperswitcher.data.RssSource) {
        guardedWrite("登录后刷新失败") {
            _toastMessage.emit(str(R.string.rss_login_done))
        }
        viewModelScope.launch {
            try {
                com.wallpaperswitcher.engine.RssSync.refresh(getApplication(), source.id)
            } catch (_: Exception) {
            }
        }
    }

    /** Device/version/settings header lines for the exported log report. */
    suspend fun buildLogReportHeader(): List<String> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val interval = try {
            settingsDao.getLong(SettingsKeys.GLOBAL_INTERVAL_MS, 60_000L)
        } catch (_: Exception) {
            60_000L
        }
        val mode = try {
            settingsDao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, "RANDOM")
        } catch (_: Exception) {
            "RANDOM"
        }
        val groups = try {
            settingsDao.getLong(SettingsKeys.PICK_SEQ)
        } catch (_: Exception) {
            0L
        }
        listOf(
            "App: Wallpaper Switcher $version",
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "(Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})",
            "Interval: ${interval}ms, mode=$mode, applied switches=$groups",
            "Engine running: ${LiveWallpaperEngineRunning()}"
        )
    }

    private fun LiveWallpaperEngineRunning(): Boolean = try {
        LiveWallpaperService.engineRunning
    } catch (_: Exception) {
        false
    }

    /** 配置导出: groups + global wallpaper settings as one JSON document. */
    suspend fun exportConfigText(): String = withContext(Dispatchers.IO) {
        com.wallpaperswitcher.engine.ConfigBackup.encode(
            com.wallpaperswitcher.engine.ConfigBackup.read(db)
        )
    }

    /** 配置导入: returns how many groups / subscriptions were created. */
    suspend fun importConfigText(
        text: String,
    ): com.wallpaperswitcher.engine.ConfigBackup.ApplyResult =
        withContext(Dispatchers.IO) {
        val config = com.wallpaperswitcher.engine.ConfigBackup.decode(text)
            ?: throw IllegalArgumentException("not a wallpaper-switcher config")
        val result = com.wallpaperswitcher.engine.ConfigBackup.apply(db, config)
        AppLog.d(TAG, "importConfig: ${result.groups} groups, ${result.sources} sources")
        WallpaperSwitchService.poke(getApplication())
        result
    }

    // Theme color (stored as hex string like "#6750A4", empty = system default)
    val themeColor: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.THEME_COLOR)
        .map { it ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Light/dark mode: "system" (follow the phone) / "light" / "dark".
    val themeMode: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.THEME_MODE)
        .map { it ?: SettingsKeys.THEME_MODE_SYSTEM }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsKeys.THEME_MODE_SYSTEM
        )

    // UI language: a locale tag from SettingsKeys.TRANSLATED_LOCALES, or "system".
    val locale: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.LOCALE)
        .map { it ?: SettingsKeys.LOCALE_SYSTEM }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsKeys.LOCALE_SYSTEM
        )

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

    /**
     * Rows that can only be read with READ_MEDIA_* (see the DAO): the home screen
     * uses it to decide whether a missing permission is worth a hint.
     *
     * Declared BEFORE [homeUiState] on purpose - Kotlin initializes properties in
     * declaration order, and `combine(...)` reads this one eagerly.
     */
    private val mediaStoreRowCount: StateFlow<Int> = imageDao.getMediaStoreRowCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- Settings flows (rebuilt after the accidental edit that removed them) ---

    val globalIntervalMs: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.GLOBAL_INTERVAL_MS)
        .map { it?.toLongOrNull() ?: 60_000L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60_000L)

    val globalSwitchMode: StateFlow<SwitchMode> = settingsDao.getValueFlow(SettingsKeys.GLOBAL_SWITCH_MODE)
        .map { name ->
            try {
                SwitchMode.valueOf(name ?: SwitchMode.RANDOM.name)
            } catch (_: Exception) {
                SwitchMode.RANDOM
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SwitchMode.RANDOM)

    val globalScaleMode: StateFlow<ScaleMode> = settingsDao.getValueFlow(SettingsKeys.GLOBAL_SCALE_MODE)
        .map { name ->
            try {
                ScaleMode.valueOf(name ?: ScaleMode.FIT.name)
            } catch (_: Exception) {
                ScaleMode.FIT
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScaleMode.FIT)

    val clarityMode: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.CLARITY_MODE)
        .map { it ?: "auto" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "auto")

    val switchFadeEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.SWITCH_FADE_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val rotateMismatchEnabled: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.ROTATE_MISMATCH_ENABLED)
            .map { it?.toBooleanStrictOrNull() ?: true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val rotateMismatchClockwise: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.ROTATE_MISMATCH_CW)
            .map { it?.toBooleanStrictOrNull() ?: true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val switchTransition: StateFlow<String> =
        settingsDao.getValueFlow(SettingsKeys.SWITCH_TRANSITION)
            .map { it ?: SettingsKeys.SWITCH_TRANSITION_DEFAULT }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsKeys.SWITCH_TRANSITION_DEFAULT
            )

    val scenePauseOnPowerSave: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.SCENE_PAUSE_ON_POWER_SAVE)
            .map { it?.toBooleanStrictOrNull() ?: false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val scenePauseOnLowBattery: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.SCENE_PAUSE_ON_LOW_BATTERY)
            .map { it?.toBooleanStrictOrNull() ?: false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val videoPlayToEnd: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.VIDEO_PLAY_TO_END)
            .map { it?.toBooleanStrictOrNull() ?: false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val favoriteBoost: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.FAVORITE_BOOST)
            .map { it?.toBooleanStrictOrNull() ?: true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val recentNoRepeat: StateFlow<Int> =
        settingsDao.getValueFlow(SettingsKeys.RECENT_NO_REPEAT)
            .map { it?.toIntOrNull()?.coerceIn(0, MAX_RECENT_NO_REPEAT) ?: 0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lockTimerEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.LOCK_TIMER_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val lockIntervalMs: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.LOCK_INTERVAL_MS)
        .map { it?.toLongOrNull() ?: 60_000L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60_000L)

    val videoSoundEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.VIDEO_SOUND_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoScanEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.AUTO_SCAN_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoScanIntervalMs: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.AUTO_SCAN_INTERVAL_MS)
        .map { it?.toLongOrNull() ?: 86_400_000L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 86_400_000L)

    val autoScanLastRunAt: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.AUTO_SCAN_LAST_RUN_AT)
        .map { it?.toLongOrNull() ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val homeUiState: StateFlow<HomeUiState> = combine(
        groups,
        mediaCounts,
        serviceEnabled,
        lockTimerEnabled,
        mediaStoreRowCount
    ) { g, m, s, lock, storeRows ->
        HomeUiState(
            groups = g,
            mediaCounts = m,
            serviceEnabled = s,
            lockTimerEnabled = lock,
            mediaStoreRowCount = storeRows
        )
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
        const val THEME_MODE = 22
        const val SWITCH_TRANSITION = 23
        const val SCENE_PAUSE_ON_POWER_SAVE = 24
        const val SCENE_PAUSE_ON_LOW_BATTERY = 25
        const val VIDEO_PLAY_TO_END = 26
        const val FAVORITE_BOOST = 27
        const val RECENT_NO_REPEAT = 28
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
        floatingButtonImageUri,
        themeMode,
        switchTransition,
        scenePauseOnPowerSave,
        scenePauseOnLowBattery,
        videoPlayToEnd,
        favoriteBoost,
        recentNoRepeat
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
            themeMode = combined(a, SettingsField.THEME_MODE, "themeMode", String::class.java)
                ?: SettingsKeys.THEME_MODE_SYSTEM,
            autoScanEnabled = combined(a, SettingsField.AUTO_SCAN_ENABLED, "autoScanEnabled", Boolean::class.javaObjectType) ?: false,
            autoScanIntervalMs = combined(a, SettingsField.AUTO_SCAN_INTERVAL_MS, "autoScanIntervalMs", Long::class.javaObjectType) ?: 24L * 60 * 60 * 1000,
            autoScanLastRunAt = combined(a, SettingsField.AUTO_SCAN_LAST_RUN_AT, "autoScanLastRunAt", Long::class.javaObjectType) ?: 0L,
            rotateMismatchEnabled = combined(a, SettingsField.ROTATE_MISMATCH_ENABLED, "rotateMismatchEnabled", Boolean::class.javaObjectType) ?: true,
            rotateMismatchClockwise = combined(a, SettingsField.ROTATE_MISMATCH_CLOCKWISE, "rotateMismatchClockwise", Boolean::class.javaObjectType) ?: true,
            lockTimerEnabled = combined(a, SettingsField.LOCK_TIMER_ENABLED, "lockTimerEnabled", Boolean::class.javaObjectType) ?: true,
            lockIntervalMs = combined(a, SettingsField.LOCK_INTERVAL_MS, "lockIntervalMs", Long::class.javaObjectType) ?: 60_000L,
            videoSoundEnabled = combined(a, SettingsField.VIDEO_SOUND_ENABLED, "videoSoundEnabled", Boolean::class.javaObjectType) ?: false,
            switchTransition = combined(a, SettingsField.SWITCH_TRANSITION, "switchTransition", String::class.java)
                ?: SettingsKeys.SWITCH_TRANSITION_DEFAULT,
            scenePauseOnPowerSave = combined(a, SettingsField.SCENE_PAUSE_ON_POWER_SAVE, "scenePauseOnPowerSave", Boolean::class.javaObjectType) ?: false,
            scenePauseOnLowBattery = combined(a, SettingsField.SCENE_PAUSE_ON_LOW_BATTERY, "scenePauseOnLowBattery", Boolean::class.javaObjectType) ?: false,
            videoPlayToEnd = combined(a, SettingsField.VIDEO_PLAY_TO_END, "videoPlayToEnd", Boolean::class.javaObjectType) ?: false,
            favoriteBoost = combined(a, SettingsField.FAVORITE_BOOST, "favoriteBoost", Boolean::class.javaObjectType) ?: true,
            recentNoRepeat = combined(a, SettingsField.RECENT_NO_REPEAT, "recentNoRepeat", Integer::class.javaObjectType)?.toInt() ?: 0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setThemeColor(hex: String) {
        guardedWrite("保存主题色失败") {
            settingsDao.setString(SettingsKeys.THEME_COLOR, hex)
        }
    }

    /** Light/dark mode: [SettingsKeys.THEME_MODE_SYSTEM] / _LIGHT / _DARK. */
    fun setThemeMode(mode: String) {
        guardedWrite("保存主题模式失败") {
            settingsDao.setString(SettingsKeys.THEME_MODE, mode)
        }
    }

    /** UI language: a tag from [SettingsKeys.TRANSLATED_LOCALES], or "system". */
    fun setLocale(tag: String) {
        guardedWrite("保存语言设置失败") {
            settingsDao.setString(SettingsKeys.LOCALE, tag)
            // Mirror for MainActivity.attachBaseContext, which runs before Room.
            com.wallpaperswitcher.ui.AppLocale.store(getApplication(), tag)
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
                _toastMessage.emit(str(R.string.toast_media_already_in_group))
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
                        str(R.string.toast_added_media_skipped, images.size, duplicates)
                    } else {
                        str(R.string.toast_added_media, images.size)
                    }
                )
            } else {
                _toastMessage.emit(
                    str(
                        if (duplicates > 0) R.string.toast_media_all_present
                        else R.string.toast_no_addable_media
                    )
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
                _toastMessage.emit(str(R.string.state_scanning_folders))
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
                    _toastMessage.emit(str(R.string.toast_added_media, total))
                } else {
                    _toastMessage.emit(
                        str(
                            if (alreadyThere > 0) R.string.toast_folder_media_present
                            else R.string.toast_no_media_found
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(TAG, "addFolder failed", e)
                _toastMessage.emit(str(R.string.toast_import_failed, e.message.orEmpty()))
            }
        }
    }

    fun deleteImage(image: WallpaperImage) {
        guardedWrite("删除图片失败") {
            imageDao.delete(image)
            deleteOwnedMediaFiles(listOf(image.uri))
                com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            clearLastImageIdIfDeleted(setOf(image.id))
            _selectedGroupId.value?.let { refreshCount(it) }
            refreshImages()
        }
    }

    fun deleteImages(images: List<WallpaperImage>) {
        guardedWrite("删除图片失败") {
            val ids = images.map { it.id }
            val uris = images.map { it.uri }
                com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            // Chunk the DELETE: older SQLite builds cap a statement at 999
            // bound variables, and a select-all delete can pass thousands of
            // "ds (would throw itoo many SQL variables").
            ids.chunked(500).forEach { chunk ->
                imageDao.deleteByIds(chunk)
            }
            deleteOwnedMediaFiles(uris)
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
            // URIs first: a subscription import lives in the app's own storage
            // and its file must go together with the row.
            // 分片查询：SQLite 的绑定变量上限在旧设备上是 999，一次 select-all
            // 删除几千张时会把整条语句撑爆（和下面分片 DELETE 同样的原因）。
            val uris = ArrayList<String>(ids.size)
            try {
                ids.toList().chunked(500).forEach { chunk ->
                    uris.addAll(imageDao.getUrisByIds(chunk))
                }
            } catch (_: Throwable) {
            }
                com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            ids.toList().chunked(500).forEach { chunk ->
                imageDao.deleteByIds(chunk)
            }
            deleteOwnedMediaFiles(uris)
            clearLastImageIdIfDeleted(ids)
            _selectedGroupId.value?.let { refreshCount(it) }
            refreshImages()
        }
    }

    /**
     * 订阅源导入的壁纸是下载到应用私有目录的（`files/rss/<源 id>/`、`files/online/`），
     * 删行时必须把文件一起删掉，否则存储会一直涨。相册 / 文件夹来源的 uri 指向用户
     * 自己的文件，**绝不能删**，所以这里只认应用私有目录下的路径。
     */
    private suspend fun deleteOwnedMediaFiles(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val app = getApplication<android.app.Application>()
        val root = app.filesDir.absolutePath.trimEnd('/')
        // 用户自选的订阅下载目录（SAF）里的文件也是我们创建的，同样要一起删。
        val tree = try {
            com.wallpaperswitcher.engine.RssDownloadDir.load(app)
        } catch (_: Throwable) {
            ""
        }
        // 一定要在 IO 线程上删：一次选择上千张时，逐个删文件（尤其是 SAF 文档，
        // 每个都是 binder 调用）如果跑在主线程，界面就会卡住。批量并行 + 分批
        // yield，既快又不会把主线程堵死。
        val startedAt = System.currentTimeMillis()
        var removed = 0
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val gate = Semaphore(8)
            coroutineScope {
                val jobs = uris.map { uri ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        gate.withPermit {
                            if (deleteOneOwnedFile(app, root, tree, uri)) 1 else 0
                        }
                    }
                }
                for (job in jobs) removed += job.await()
            }
        }
        if (removed > 0) {
            com.wallpaperswitcher.util.AppLog.d(
                "MediaDelete",
                "deleted $removed file(s) in ${System.currentTimeMillis() - startedAt}ms",
            )
        }
    }

    /** 删除一个"应用自己的"文件；不属于应用目录的一律不动，返回是否真的删了。 */
    private fun deleteOneOwnedFile(
        app: android.app.Application,
        root: String,
        tree: String,
        uri: String,
    ): Boolean {
        return try {
            if (uri.startsWith("content://")) {
                if (tree.isBlank() ||
                    !com.wallpaperswitcher.engine.RssDownloadDir.isInside(tree, uri)
                ) {
                    return false
                }
                android.provider.DocumentsContract.deleteDocument(
                    app.contentResolver,
                    android.net.Uri.parse(uri),
                )
            } else {
                if (!uri.startsWith("file://")) return false
                val path = uri.removePrefix("file://")
                if (!path.startsWith("$root/rss/") && !path.startsWith("$root/online/")) {
                    return false
                }
                val file = java.io.File(path)
                file.exists() && file.delete()
            }
        } catch (_: Throwable) {
            false
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
                publishScanProgress(
                    str(R.string.scan_progress_checking, checked, all.size)
                )
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
    fun setImageAsWallpaper(
        image: WallpaperImage,
        forceSlot: String? = null,
        /**
         * 应用结果回调（可选）。
         *
         * 调用方如果自己有"成功"提示（例如「最近显示」推回时弹的"已推回"），
         * 必须用这个回调决定要不要弹：失败时 ViewModel 自己会发失败 Toast
         * （分组被禁用 / 文件读不了 / 正忙），那时再弹一句"已推回"就是自相矛盾。
         */
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            try {
                val group = groupDao.getGroupById(image.groupId)
                // A DISABLED group is not part of the rotation: its media must
                // not be settable from the group screen either (user report:
                // 「当分组图片未启用时，里面的图片仍能设置为壁纸」). The engine, the
                // static applier and both timers only ever pick from ENABLED
                // groups, so applying this media produced a wallpaper the next
                // redraw/switch replaced again.
                if (group != null && !group.isEnabled) {
                    AppLog.d(
                        TAG,
                        "setImageAsWallpaper ignored: group ${group.id} is disabled"
                    )
                    _toastMessage.emit(str(R.string.toast_group_disabled))
                    onResult?.invoke(false)
                    return@launch
                }
                val target = when (forceSlot) {
                    // 调用方明确指定了屏（大图浏览的「设为桌面/锁屏」、回滚页的
                    // 「推回当前屏」）：就写那一块，**即使分组是 LOCK-only/BOTH**。
                    // 以前只对 LOCK 做了强制，传 HOME 会回落到分组自己的 target ——
                    // 于是 LOCK-only 分组里点「设为桌面」实际上写的是锁屏。
                    WallpaperTarget.SLOT_LOCK -> WallpaperTarget.LOCK
                    WallpaperTarget.SLOT_HOME -> WallpaperTarget.HOME
                    else -> WallpaperTarget.fromName(group?.target)
                }
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
                    _hintMessage.emit(str(R.string.hint_motion_needs_engine))
                    onResult?.invoke(false)
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
                        // picks it up when its process is restarted). This is
                        // deliberate even when no engine accepts the push right
                        // now: the cursor is the PICK, and the next engine start
                        // renders it (see pushConfirmedPickToEngine).
                        settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                        // A fire-and-forget broadcast used to be sent here and the
                        // result thrown away, so a stale `engineRunning`/`homeIsLive`
                        // (engine killed between the read and the send) reported
                        // "已设为壁纸" while nothing changed. Ask the engine directly
                        // and only claim success when one really accepted it.
                        applied = LiveWallpaperService.pushConfirmedPickToEngine(image.id)
                        if (!applied) {
                            AppLog.w(
                                TAG,
                                "No live engine accepted the home pick; cursor kept for the next start"
                            )
                        }
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
                        _toastMessage.emit(str(R.string.toast_lock_no_motion))
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
                        str(
                            if (busy) R.string.toast_wallpaper_busy
                            else R.string.toast_wallpaper_unreadable
                        )
                    )
                    onResult?.invoke(false)
                    return@launch
                }
                _toastMessage.emit(
                    str(R.string.toast_wallpaper_set, str(target.labelRes))
                )
                onResult?.invoke(true)
            } catch (e: Exception) {
                AppLog.e(TAG, "setImageAsWallpaper failed", e)
                _toastMessage.emit(str(R.string.toast_set_failed, e.message.orEmpty()))
                onResult?.invoke(false)
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
                // Same rule as setImageAsWallpaper: a disabled group's media is
                // not settable (see the comment there). Checked BEFORE the HOME
                // cursor is moved / the picker is launched, so a disabled pick
                // cannot reach the engine or leave a pending preview pick behind.
                if (group != null && !group.isEnabled) {
                    AppLog.d(
                        TAG,
                        "setAsLiveWallpaper ignored: group ${group.id} is disabled"
                    )
                    _toastMessage.emit(str(R.string.toast_group_disabled))
                    return@launch
                }
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
                            str(R.string.hint_pick_both)
                        target.includesHome ->
                            str(R.string.hint_pick_home)
                        else ->
                            // Lock-only group: the system dialog has no ilock
                            // screeni option for live wallpapers, and this app
                            // re-asserts the group's 应用位置 afterwards (see
                            // enforceSlotsAfterLiveApply), so any confirm in the
                            // dialog ends up with this group's image on the lock
                            // screen. The hint therefore only tells the user to
                            // confirm, not which option to pick.
                            str(R.string.hint_pick_lock)
                    }
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "setAsLiveWallpaper failed", e)
                _toastMessage.emit(str(R.string.toast_set_failed, e.message.orEmpty()))
            }
        }
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
                _toastMessage.emit(str(R.string.toast_importing_folders, folders.size))
                AppLog.d(TAG, "importScannedFolders: group=$groupId folders=${folders.map { LogText.folder(it.path) }}")
                publishScanProgress(str(R.string.scan_progress_querying))
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
                publishScanProgress(
                    str(R.string.scan_progress_querying_media, collected.size)
                )
                            }
                        }
                publishScanProgress(
                    str(R.string.scan_progress_querying_media, collected.size)
                )
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
                    _toastMessage.emit(str(R.string.toast_imported_media, total))
                } else {
                    _toastMessage.emit(str(R.string.toast_no_new_media))
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(TAG, "importScannedFolders failed", e)
                _toastMessage.emit(str(R.string.toast_import_failed, e.message.orEmpty()))
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
    val lockTimerEnabled: Boolean = true,
    /**
     * Media rows that need READ_MEDIA_* (`content://media/...`). 0 means the
     * library is SAF-only, where a missing permission is harmless.
     */
    val mediaStoreRowCount: Int = 0
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
    /** "system" (follow the phone) / "light" / "dark". */
    val themeMode: String = SettingsKeys.THEME_MODE_SYSTEM,
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
    val videoSoundEnabled: Boolean = false,
    /** 过渡动画: "fade" / "slide" / "zoom" / "none" (see SettingsKeys). */
    val switchTransition: String = SettingsKeys.SWITCH_TRANSITION_DEFAULT,
    /** 场景规则: hold the timed loops while the battery saver is on. */
    val scenePauseOnPowerSave: Boolean = false,
    /** 场景规则: hold the timed loops while the battery is low (<=15%). */
    val scenePauseOnLowBattery: Boolean = false,
    /** 视频播完再切: a timed switch waits for the current clip's pass to end. */
    val videoPlayToEnd: Boolean = false,
    /** 收藏优先: favourites get a higher weight in RANDOM / SHUFFLE. */
    val favoriteBoost: Boolean = true,
    /** 最近 N 张不重复 (0 = off) for RANDOM. */
    val recentNoRepeat: Int = 0
)
