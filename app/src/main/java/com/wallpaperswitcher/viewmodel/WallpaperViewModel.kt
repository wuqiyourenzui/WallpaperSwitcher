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
    /** Storage accounting / cleanup (see [StorageController]). */
    private val storage = StorageController(getApplication(), settingsDao)
    /** 阅读订阅子系统 (see [RssController]); the public methods below forward to it. */
    private val rss = RssController(
        app = getApplication(),
        db = db,
        scope = viewModelScope,
        onToast = { message -> _toastMessage.emit(message) },
    )
    /** 分组增删改与调度参数 (see [GroupController]); the public methods below forward to it. */
    private val groupOps = GroupController(
        app = getApplication(),
        db = db,
        storage = storage,
        scope = viewModelScope,
        selectedGroupId = { _selectedGroupId.value },
        onSelectGroup = { id -> selectGroup(id) },
        guard = { errorMessage, block -> guardedWrite(errorMessage, block) },
        onCreateFailed = { detail ->
            _toastMessage.emit(str(R.string.toast_create_group_failed, detail))
        },
    )
    /** 设为壁纸的两条流程 (see [WallpaperApplyController]); the public methods below forward to it. */
    private val applyCtl = WallpaperApplyController(
        app = getApplication(),
        db = db,
        scope = viewModelScope,
        localize = { id, args -> str(id, *args.toTypedArray()) },
        onToast = { message -> _toastMessage.emit(message) },
        onHint = { message -> _hintMessage.emit(message) },
        onPickerBlocked = { _liveWallpaperBlocked.tryEmit(Unit) },
        launchPicker = { launchLiveWallpaperPicker() },
    )

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

    /** 阅读订阅源 (Legado-compatible RSS/Atom feeds), newest first. */
    val rssSources: StateFlow<List<com.wallpaperswitcher.data.RssSource>> = rss.rssSources

    /**
     * 订阅源列表的显示方式：false = 卡片列表（默认，老安装不变），
     * true = 缩略图网格（见 engine.RssIcons）。
     */
    val rssGridView: StateFlow<Boolean> = rss.rssGridView

    fun setRssGridView(enabled: Boolean) = rss.setRssGridView(enabled)

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

    // 已加载的媒体窗口（见 engine.MediaWindow）：窗口状态由 [MediaWindowController]
    // 持有，这里只暴露 UI 需要的流。
    private val mediaWindow = MediaWindowController(
        app = getApplication(),
        imageDao = imageDao,
        scope = viewModelScope,
        currentGroupId = { _selectedGroupId.value },
        onToast = { message -> _toastMessage.emit(message) },
    )
    val loadedImages: StateFlow<List<WallpaperImage>> = mediaWindow.loadedImages
    val windowStart: StateFlow<Int> = mediaWindow.windowStart
    val totalImageCount: StateFlow<Int> = mediaWindow.totalImageCount
    val isLoadingImages: StateFlow<Boolean> = mediaWindow.isLoadingImages

    /** 扫描进度（见 [MediaLibraryController]）。 */
    private val mediaLibrary = MediaLibraryController(
        app = getApplication(),
        db = db,
        imageDao = imageDao,
        groupDao = groupDao,
        settingsDao = settingsDao,
        storage = storage,
        scope = viewModelScope,
        onToast = { message -> _toastMessage.emit(message) },
        currentGroupId = { _selectedGroupId.value },
        onLibraryChanged = { groupId ->
            refreshCount(groupId)
            refreshImages()
        },
    )

    val scanProgress: StateFlow<String> = mediaLibrary.scanProgress

    // Toast events. extraBufferCapacity + DROP_OLDEST means emit() NEVER
    // suspends: with a zero-buffer flow, emit() would hang forever while the
    // app is in the background (no subscribers), freezing e.g. a folder import
    // or leaving _isLoadingImages stuck after a load failure.

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
     * The system live-wallpaper screen cannot be shown because HyperOS/MIUI
     * has this app's 「动态壁纸服务」 permission turned off (see
     * [com.wallpaperswitcher.engine.LiveWallpaperPermission]). The picker
     * closes itself before it is drawn, so without this the tap would look like
     * a no-op; the UI answers with a dialog that opens the right settings page.
     *
     * `Unit` payload: the dialog only needs "it happened" and is idempotent.
     */
    private val _liveWallpaperBlocked = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val liveWallpaperBlocked: SharedFlow<Unit> = _liveWallpaperBlocked

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

    // Last alpha value written to the database (see setFloatingButtonAlpha).
    private var lastWrittenFloatingAlpha: Int? = null
    // Debounce job for slider commits (see setFloatingButtonAlpha).
    private var alphaWriteJob: Job? = null

    // --- Actions ---

    fun selectGroup(id: Long?) {
        _selectedGroupId.value = id
        mediaWindow.reset()
        if (id != null) {
            loadAllImages(id)
        }
    }

    /**
     * Load the FIRST page of the group (see [com.wallpaperswitcher.engine.MediaWindow]).
     *
     * 原来的实现一次把整组读进内存（"开页就要全部拿到"）；几百上千张的分组里
     * 光是元数据就是好几 MB，开页与每次 refresh 都要重查一遍。现在只取首页，
     * 其余交给 [ensureMediaRange] 在滚动到边缘/跳转时按需补。
     */
    fun loadAllImages(groupId: Long) {
        mediaWindow.load(groupId)
    }

    /**
     * 保证 [firstIndex]..[lastIndex]（0 基、按整组顺序）这一段已经加载：
     * 靠近窗口尾部接一页、靠近头部前插一页、离得远就换成以目标为中心的一页
     * （快速滚动条跳转）。判定见 [com.wallpaperswitcher.engine.MediaWindow.plan]。
     *
     * 请求会被合并：同一时刻只跑一个补页任务，滚动过程中连续到达的请求取并集，
     * 不会因为快速滑动排出一长串重复查询。
     */
    fun ensureMediaRange(firstIndex: Int, lastIndex: Int) {
        mediaWindow.ensureRange(firstIndex, lastIndex)
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
    fun createGroupAndOpen(name: String, onCreated: (Long) -> Unit) =
        groupOps.createGroupAndOpen(name, onCreated)

    fun updateGroup(group: WallpaperGroup) = groupOps.updateGroup(group)

    fun deleteGroup(group: WallpaperGroup) = groupOps.deleteGroup(group)

    /**
     * Delete several groups at once (home screen multi-select).
     *
     * Each group's media ROWS go with it (the Room relation cascades) - the files
     * on the phone are untouched, exactly like the single-group delete.
     */
    fun deleteGroups(ids: Set<Long>) = groupOps.deleteGroups(ids)

    /**
     * Enable/disable several groups at once (home screen multi-select).
     *
     * One [WallpaperSwitchService.poke] at the end instead of one per group: the
     * timer loops only need to re-evaluate which groups may feed each screen, and
     * poking N times would restart those loops N times.
     */
    fun setGroupsEnabled(ids: Set<Long>, enabled: Boolean) =
        groupOps.setGroupsEnabled(ids, enabled)

    fun toggleGroupEnabled(groupId: Long, enabled: Boolean) =
        groupOps.toggleGroupEnabled(groupId, enabled)

    /**
     * Choose where this group's media may be shown: 桌面 / 锁屏 / 桌面和锁屏.
     * The home screen and the lock screen are switched independently, each from
     * the enabled groups that target it (Paperize-style dual screen).
     */
    fun setGroupTarget(groupId: Long, target: WallpaperTarget) =
        groupOps.setGroupTarget(groupId, target)

    /**
     * 分组独立间隔: [intervalMs] = 0 means "follow the screen's global
     * interval" (the default). Anything else makes this group due on its own
     * rhythm (see GroupPacing) - the timer wakes for whichever group is due
     * first, and the switch then only shows THIS group's media.
     */
    fun setGroupInterval(groupId: Long, intervalMs: Long) =
        groupOps.setGroupInterval(groupId, intervalMs)

    /**
     * 时间规则: the minutes-of-day window this group may be shown in
     * (-1 = 全天). Out-of-window groups are skipped by the scheduler instead of
     * being switched to.
     */
    fun setGroupActiveWindow(groupId: Long, fromMinute: Int, toMinute: Int) =
        groupOps.setGroupActiveWindow(groupId, fromMinute, toMinute)

    /**
     * 时间规则 · 星期: bitmask with bit 0 = Monday … bit 6 = Sunday.
     * `0` or the full mask means "every day".
     */
    fun setGroupActiveDays(groupId: Long, daysMask: Int) =
        groupOps.setGroupActiveDays(groupId, daysMask)

    /**
     * 分组素材类型: "" = 两者都切换, "IMAGE" = 仅图片, "MOTION" = 仅视频（含 GIF）.
     * 只影响这个分组的取图，桌面/锁屏各自的节奏与全局模式都不变。
     */
    fun setGroupMediaFilter(groupId: Long, filter: String) =
        groupOps.setGroupMediaFilter(groupId, filter)

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
    suspend fun storageUsage(): StorageUsage = storage.usage()

    /**
     * 只统计、不删除：`files/rss`、`files/online` 里数据库已不再引用的文件有多少。
     *
     * 与 [cleanOrphanMedia] 共用同一套判断（引用集合 + 10 分钟保护期），所以界面上
     * 「可清理 280MB」和实际释放量不会对不上。
     */
    suspend fun measureOrphanMedia(): StorageCleanResult = storage.measureOrphans()

    /**
     * 清理"孤儿文件"：数据库里已经没有引用的订阅源 / 在线源下载文件。
     *
     * 复用启动时那次扫描的同一套判断（见
     * [com.wallpaperswitcher.engine.OwnedMediaCleaner]），先算大小再删，
     * 这样界面能告诉用户"释放了多少"，而不是只报"清理完成"。
     */
    suspend fun cleanOrphanMedia(): StorageCleanResult = storage.cleanOrphans()

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
            // ★ weights feed the weighted RANDOM pick: drop the cached
            // id/weight lists so the next switch (or preview) sees the new
            // star immediately instead of the pre-toggle set.
            com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
        }
    }

    // --- 在线壁纸源（设置里的内置选项） -------------------------------------

    /** 内置在线源对应的行（type/开关/上次结果），设置页按 order 列出。 */
    val onlineSources: StateFlow<List<com.wallpaperswitcher.data.OnlineSource>> =
        db.onlineSourceDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setOnlineSourceEnabled(type: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val app = getApplication<android.app.Application>()
                val updated =
                    com.wallpaperswitcher.engine.OnlineBuiltins.setEnabled(app, type, enabled)
                if (updated == null) {
                    _toastMessage.emit(str(R.string.online_error_unknown))
                    return@launch
                }
                if (enabled) {
                    com.wallpaperswitcher.engine.OnlineSourceScheduler.schedule(app, updated)
                    com.wallpaperswitcher.engine.OnlineSourceScheduler.refreshNow(app, updated.id)
                    _toastMessage.emit(str(R.string.settings_online_source_started, updated.name))
                } else {
                    com.wallpaperswitcher.engine.OnlineSourceScheduler.cancel(app, updated.id)
                }
            } catch (_: Throwable) {
                _toastMessage.emit(str(R.string.online_error_unknown))
            }
        }
    }

    /** 「立即更新」：所有已开启的内置源各排一次一次性任务（不受间隔限制）。 */
    fun refreshOnlineSources() {
        viewModelScope.launch {
            val app = getApplication<android.app.Application>()
            val enabled = try {
                db.onlineSourceDao().getEnabled()
            } catch (_: Throwable) {
                emptyList()
            }.let { com.wallpaperswitcher.engine.OnlineBuiltins.builtinEnabled(it) }
            if (enabled.isEmpty()) {
                _toastMessage.emit(str(R.string.settings_online_source_none))
                return@launch
            }
            enabled.forEach {
                com.wallpaperswitcher.engine.OnlineSourceScheduler.refreshNow(app, it.id)
            }
            _toastMessage.emit(str(R.string.settings_online_source_refreshing))
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
            if (enabled) {
                WallpaperSwitchService.ensureRunning(getApplication())
            } else {
                // 关闭锁屏独立定时：锁屏改为跟随动态壁纸（视频随桌面播放），
                // 立刻清掉独立锁屏图，不必等下一次应用/切换动态壁纸。
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        android.app.WallpaperManager.getInstance(getApplication())
                            .clear(android.app.WallpaperManager.FLAG_LOCK)
                        com.wallpaperswitcher.util.AppLog.d(
                            "WallpaperViewModel",
                            "Lock timer OFF: static lock wallpaper cleared (lock follows the live wallpaper)"
                        )
                    } catch (t: Throwable) {
                        com.wallpaperswitcher.util.AppLog.w(
                            "WallpaperViewModel",
                            "Clearing the lock wallpaper failed: ${t.message}"
                        )
                    }
                }
            }
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

    /**
     * 静态图微动效 (Ken Burns): still images slowly zoom in and out. Default
     * off (an old install behaves exactly as before); the running engine adopts
     * the change via the companion, and every switch re-reads the setting.
     */
    fun setKenBurnsEnabled(enabled: Boolean) {
        guardedWrite("保存微动效设置失败") {
            settingsDao.setBool(SettingsKeys.KEN_BURNS_ENABLED, enabled)
            LiveWallpaperService.applyKenBurnsFromSettings(getApplication(), enabled)
        }
    }

    /**
     * 首启自检向导 done flag.
     *
     * Initial value is `true` so an install that already dismissed the wizard
     * never flashes it while the database read is in flight; a fresh install's
     * missing key emits false and the wizard opens (see WallpaperSwitcherApp).
     */
    val setupWizardDone: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.SETUP_WIZARD_DONE)
            .map { it?.toBooleanStrictOrNull() ?: false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun markSetupWizardDone() {
        guardedWrite("保存向导状态失败") {
            settingsDao.setBool(SettingsKeys.SETUP_WIZARD_DONE, true)
        }
    }

    // --- 订阅下载策略 (see engine.RssDownloadPolicy) ---

    /** 仅 Wi-Fi 下载: block subscription imports on metered networks. */
    val rssWifiOnly: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.RSS_WIFI_ONLY)
            .map { it?.toBooleanStrictOrNull() ?: false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Daily cap in MB; 0 = 不限. */
    val rssDailyLimitMb: StateFlow<Int> =
        settingsDao.getValueFlow(SettingsKeys.RSS_DAILY_LIMIT_MB)
            .map { it?.toIntOrNull() ?: 0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 残留自动清理 TTL in days; 0 = 关闭. */
    val rssOrphanTtlDays: StateFlow<Int> =
        settingsDao.getValueFlow(SettingsKeys.RSS_ORPHAN_TTL_DAYS)
            .map { it?.toIntOrNull() ?: 0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setRssWifiOnly(enabled: Boolean) {
        guardedWrite("保存下载设置失败") {
            settingsDao.setBool(SettingsKeys.RSS_WIFI_ONLY, enabled)
        }
    }

    fun setRssDailyLimitMb(mb: Int) {
        guardedWrite("保存下载设置失败") {
            settingsDao.setLong(SettingsKeys.RSS_DAILY_LIMIT_MB, mb.coerceAtLeast(0).toLong())
        }
    }

    fun setRssOrphanTtlDays(days: Int) {
        guardedWrite("保存下载设置失败") {
            settingsDao.setLong(SettingsKeys.RSS_ORPHAN_TTL_DAYS, days.coerceAtLeast(0).toLong())
        }
    }

    /**
     * 缓存 TTL: run the expired-orphan sweep (no-op while 残留自动清理 = 关闭).
     * Called once when the app comes to the foreground; failures only log.
     */
    suspend fun sweepExpiredDownloads(): Int = storage.sweepExpiredDownloads()

    /**
     * 去重: delete app-owned images whose perceptual hash matches a larger copy
     * in the same group (subscription thumbnails vs originals, repeated shares).
     * The storage page calls this from its own coroutine and renders the result.
     */
    suspend fun dedupeOwnedImages(): com.wallpaperswitcher.engine.MediaDedupe.Result =
        storage.dedupeOwnedImages()

    /**
     * 清晰度增强的「增强」换成「画质增强（超分）」的一次性迁移：上一版独立开关
     * （4.9.151 之前短暂存在）如果开着、且清晰度还是"自动"，就把清晰度提升为
     * "超分"，然后删掉旧键（之后任何代码都不再读它）。
     */
    suspend fun migrateLegacyQualityEnhance() = storage.migrateLegacyQualityEnhance()

    // --- 清晰度增强（开/关）+ 超分算法二选一 ---

    /** 清晰度增强开关：只有显式 "off" 是关，历史值/缺失都算开启。 */
    val clarityEnabled: StateFlow<Boolean> =
        settingsDao.getValueFlow(SettingsKeys.CLARITY_MODE)
            .map { com.wallpaperswitcher.engine.ClarityMode.isEnabled(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** 超分算法："fsr1" / "anime4k"（未知值归一到 fsr1）。 */
    val enhanceAlgo: StateFlow<String> =
        settingsDao.getValueFlow(SettingsKeys.ENHANCE_ALGO)
            .map { key ->
                if (key == com.wallpaperswitcher.engine.EnhanceMode.ANIME4K_KEY) {
                    com.wallpaperswitcher.engine.EnhanceMode.ANIME4K_KEY
                } else {
                    com.wallpaperswitcher.engine.EnhanceMode.FSR1_KEY
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.wallpaperswitcher.engine.EnhanceMode.FSR1_KEY,
            )

    fun setClarityEnabled(enabled: Boolean) {
        guardedWrite("保存清晰度设置失败") {
            settingsDao.setString(
                SettingsKeys.CLARITY_MODE,
                if (enabled) com.wallpaperswitcher.engine.ClarityMode.ON
                else com.wallpaperswitcher.engine.ClarityMode.OFF,
            )
        }
    }

    fun setEnhanceAlgo(key: String) {
        guardedWrite("保存超分算法失败") {
            val safe = if (key == com.wallpaperswitcher.engine.EnhanceMode.ANIME4K_KEY) {
                com.wallpaperswitcher.engine.EnhanceMode.ANIME4K_KEY
            } else {
                com.wallpaperswitcher.engine.EnhanceMode.FSR1_KEY
            }
            settingsDao.setString(SettingsKeys.ENHANCE_ALGO, safe)
            pushEnhanceMode()
        }
    }

    /** 把当前算法推给运行中的引擎（见 engine.EnhanceMode）。 */
    private suspend fun pushEnhanceMode() {
        val key = settingsDao.getString(SettingsKeys.ENHANCE_ALGO, "")
        LiveWallpaperService.applyEnhanceModeFromSettings(
            getApplication(),
            com.wallpaperswitcher.engine.EnhanceMode.fromKey(key),
        )
    }

    /** 向导用：打开系统动态壁纸选择器；false = 两个入口都打不开。 */
    fun openLiveWallpaperPicker(): Boolean = launchLiveWallpaperPicker()

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

    // --- 阅读订阅源 (Legado-compatible) ---
    // 实现已拆到 [RssController]；这里保留 UI 既有调用入口（薄转发）。

    fun rssArticles(sourceId: Long) = rss.rssArticles(sourceId)
    fun rssArticlesOfSort(sourceId: Long, sort: String) = rss.rssArticlesOfSort(sourceId, sort)
    fun rssCategoryNames(source: com.wallpaperswitcher.data.RssSource) = rss.rssCategoryNames(source)
    suspend fun rssCachedCategories(source: com.wallpaperswitcher.data.RssSource) = rss.rssCachedCategories(source)
    suspend fun rssCachedCategoriesOnly(source: com.wallpaperswitcher.data.RssSource) = rss.rssCachedCategoriesOnly(source)
    suspend fun rssRefreshCategories(source: com.wallpaperswitcher.data.RssSource) = rss.rssRefreshCategories(source)
    suspend fun rssSelectedCategory(sourceId: Long) = rss.rssSelectedCategory(sourceId)
    suspend fun rssSelectCategory(source: com.wallpaperswitcher.data.RssSource, index: Int) {
        rss.rssSelectCategory(source, index)
    }

    suspend fun rssArticleCount(sourceId: Long) = rss.rssArticleCount(sourceId)
    suspend fun rssArticleCountOfSort(sourceId: Long, sort: String) = rss.rssArticleCountOfSort(sourceId, sort)
    fun clearRssSourceCache(sourceId: Long) = rss.clearRssSourceCache(sourceId)
    suspend fun rssDownloadDirValue() = rss.rssDownloadDirValue()
    fun setRssDownloadDir(treeUri: String) = rss.setRssDownloadDir(treeUri)
    suspend fun rssRefreshOnOpen(sourceId: Long) = rss.rssRefreshOnOpen(sourceId)
    suspend fun rssHasMore(sourceId: Long) = rss.rssHasMore(sourceId)
    suspend fun rssLoadMore(sourceId: Long) = rss.rssLoadMore(sourceId)
    fun requestRssLoadMore(sourceId: Long, onDone: (Boolean) -> Unit = {}) = rss.requestRssLoadMore(sourceId, onDone)

    fun addRssSource(name: String, url: String) = rss.addRssSource(name, url)
    fun deleteRssSource(source: com.wallpaperswitcher.data.RssSource) = rss.deleteRssSource(source)
    fun setRssSourceEnabled(source: com.wallpaperswitcher.data.RssSource, enabled: Boolean) = rss.setRssSourceEnabled(source, enabled)
    fun deleteRssSources(ids: Set<Long>) = rss.deleteRssSources(ids)
    fun refreshRssSource(source: com.wallpaperswitcher.data.RssSource) = rss.refreshRssSource(source)
    fun importLegadoSources(text: String) = rss.importLegadoSources(text)

    fun markRssArticleRead(sourceId: Long, guid: String) = rss.markRssArticleRead(sourceId, guid)
    suspend fun loadRssArticleContent(
        article: com.wallpaperswitcher.data.RssArticle,
        force: Boolean = false,
    ) = rss.loadRssArticleContent(article, force)
    suspend fun loadRssGalleryImages(
        article: com.wallpaperswitcher.data.RssArticle,
        pageHtml: String,
        baseHtml: String,
    ) = rss.loadRssGalleryImages(article, pageHtml, baseHtml)
    fun addRssImagesToGroup(
        article: com.wallpaperswitcher.data.RssArticle,
        urls: List<String>,
        groupId: Long,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = rss.addRssImagesToGroup(article, urls, groupId, extraHeaders)

    fun rssLoginEndpoint(source: com.wallpaperswitcher.data.RssSource) = rss.rssLoginEndpoint(source)
    fun rssLoginCheckJs(source: com.wallpaperswitcher.data.RssSource) = rss.rssLoginCheckJs(source)
    fun rssLoginIsScript(source: com.wallpaperswitcher.data.RssSource) = rss.rssLoginIsScript(source)
    fun rssLoginFields(source: com.wallpaperswitcher.data.RssSource) = rss.rssLoginFields(source)
    fun rssLoginSavedValues(source: com.wallpaperswitcher.data.RssSource) = rss.rssLoginSavedValues(source)
    suspend fun rssLoginRunScript(
        source: com.wallpaperswitcher.data.RssSource,
        values: Map<String, String>,
    ) = rss.rssLoginRunScript(source, values)
    suspend fun rssSourceSave(
        source: com.wallpaperswitcher.data.RssSource,
        name: String,
        url: String,
        type: Int,
        enabled: Boolean,
        changes: Map<String, String?>,
        rawOverride: String?,
    ) = rss.rssSourceSave(source, name, url, type, enabled, changes, rawOverride)
    fun rssLoginCompleted(source: com.wallpaperswitcher.data.RssSource) = rss.rssLoginCompleted(source)

    /**
     * 分享入库 (ACTION_SEND / ACTION_SEND_MULTIPLE): copy the shared streams
     * into app-private storage and add them to [groupId] as normal media rows.
     *
     * Runs on [viewModelScope] (copying a video can take a moment) and reports
     * the outcome as a toast; the dialog is dismissed immediately so the user
     * can keep using the app while the copy finishes.
     */
    fun importSharedMedia(
        uris: List<android.net.Uri>,
        groupId: Long,
        favorite: Boolean,
    ) {
        guardedWrite("分享入库失败") {
            val report = com.wallpaperswitcher.engine.SharedMediaImporter
                .importMedia(getApplication(), uris, groupId, favorite)
            if (report.added > 0) {
                WallpaperSwitchService.poke(getApplication())
            }
            val message = when {
                report.added > 0 && report.failed > 0 ->
                    str(R.string.share_import_partial, report.added, report.failed)
                report.added > 0 -> str(R.string.share_import_done, report.added)
                report.skipped > 0 && report.failed == 0 ->
                    str(R.string.share_import_duplicate)
                else -> str(R.string.share_import_error)
            }
            _toastMessage.emit(message)
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
     * The whole settings screen state, driven by ONE Room query over
     * `app_settings` (see [SettingsProjection] for the per-field parse/default).
     *
     * It used to be a 28-way `combine` of the 28 single-key flows above, so any
     * settings write re-ran all 28 queries and re-read 28 values through a
     * reflection index; the screen also updated field by field, so it could
     * show a mix of loaded and still-default fields. One query = one emission =
     * one consistent state. The single-key flows above stay as they are: other
     * screens and the wallpaper service collect them directly.
     */
    val settingsUiState: StateFlow<SettingsUiState> = settingsDao.getAllFlow()
        .map { rows -> SettingsProjection.of(rows.associate { it.key to it.value }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

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

    fun addImage(groupId: Long, uri: Uri, displayName: String) =
        mediaLibrary.addImage(groupId, uri, displayName)

    fun addImages(groupId: Long, uris: List<Uri>, names: List<String>) =
        mediaLibrary.addImages(groupId, uris, names)

    fun addFolder(groupId: Long, folderUri: Uri) =
        mediaLibrary.addFolder(groupId, folderUri)

    fun deleteImage(image: WallpaperImage) = mediaLibrary.deleteImage(image)

    fun deleteImages(images: List<WallpaperImage>) = mediaLibrary.deleteImages(images)

    /**
     * Delete images by IDs directly — works across all pages, not just loaded ones.
     */
    fun deleteImagesByIds(ids: Set<Long>) = mediaLibrary.deleteImagesByIds(ids)

    /**
     * Get ALL image IDs in a group (across all pages) for select-all + batch delete.
     */
    suspend fun getAllImageIds(groupId: Long): List<Long> = mediaLibrary.getAllImageIds(groupId)

    /**
     * Scan every media entry in a group and return the ones whose files can no
     * longer be opened (deleted / moved / unreadable).
     */
    suspend fun scanBrokenMedia(groupId: Long): List<WallpaperImage> =
        mediaLibrary.scanBrokenMedia(groupId)
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
         * 必须用这个回调决定要不要弹：失败时 Controller 自己会发失败 Toast
         * （分组被禁用 / 文件读不了 / 正忙），那时再弹一句"已推回"就是自相矛盾。
         */
        onResult: ((Boolean) -> Unit)? = null,
    ) = applyCtl.applyToScreen(image, forceSlot, onResult)

    /**
     * Live wallpaper flow: the media becomes the displayed item and the SYSTEM
     * live-wallpaper preview/confirmation screen is ALWAYS shown (even when our
     * engine is already running) so the user explicitly confirms the change.
     */
    fun setAsLiveWallpaper(image: WallpaperImage) = applyCtl.applyAsLive(image)

    /**
     * Opens the system live-wallpaper preview for this app's service.
     *
     * @return `false` when neither the preview nor the chooser could be
     * started, so the caller can say so instead of appearing dead.
     */
    private fun launchLiveWallpaperPicker(): Boolean {
        try {
            val intent = android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    android.content.ComponentName(getApplication(), com.wallpaperswitcher.wallpaper.LiveWallpaperService::class.java)
                )
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
            return true
        } catch (e: Exception) {
            AppLog.w(TAG, "ACTION_CHANGE_LIVE_WALLPAPER failed: ${e.message}")
            try {
                val intent = android.content.Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
                return true
            } catch (e2: Exception) {
                AppLog.e(TAG, "no live-wallpaper picker available", e2)
                return false
            }
        }
    }

    private suspend fun refreshCount(groupId: Long) {
        mediaWindow.setTotal(imageDao.getImageCountByGroup(groupId))
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

    /** Cached device-folder scan (see [MediaLibraryController.loadScannedFolders]). */
    suspend fun loadScannedFolders(): List<ScannedFolder> = mediaLibrary.loadScannedFolders()

    /** Force a fresh MediaStore folder scan. */
    suspend fun rescanFolders(): List<ScannedFolder> = mediaLibrary.rescanFolders()

    /** Import several scanned folders into a group (images + videos, deduped). */
    fun importScannedFolders(groupId: Long, folders: List<ScannedFolder>) =
        mediaLibrary.importScannedFolders(groupId, folders)
}
