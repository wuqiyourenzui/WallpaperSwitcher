package com.wallpaperswitcher.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wallpaperswitcher.WallpaperSwitcherApp
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.engine.MediaScanner
import com.wallpaperswitcher.engine.ScannedFolder
import com.wallpaperswitcher.engine.WallpaperApplier
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import com.wallpaperswitcher.worker.FolderAutoScanWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

class WallpaperViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (getApplication() as WallpaperSwitcherApp).database
    private val groupDao = db.wallpaperGroupDao()
    private val imageDao = db.wallpaperImageDao()
    private val settingsDao = db.settingsDao()

    companion object {
        private const val TAG = "WallpaperViewModel"
        private const val PAGE_SIZE = 50
    }

    // --- States ---

    val groups: StateFlow<List<WallpaperGroup>> = groupDao.getAllGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Per-group media counts for the home screen cards (image + video + GIF).
    val mediaCounts: StateFlow<Map<Long, Int>> = imageDao.getMediaCounts()
        .map { list -> list.associate { it.groupId to it.mediaCount } }
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

    private val _selectedGroupId = MutableStateFlow<Long?>(null)
    val selectedGroupId: StateFlow<Long?> = _selectedGroupId

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedGroup: StateFlow<WallpaperGroup?> = _selectedGroupId
        .filterNotNull()
        .flatMapLatest { groupDao.getGroupByIdFlow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Paged image list
    private val _loadedImages = MutableStateFlow<List<WallpaperImage>>(emptyList())
    val loadedImages: StateFlow<List<WallpaperImage>> = _loadedImages
    private val _totalImageCount = MutableStateFlow(0)
    val totalImageCount: StateFlow<Int> = _totalImageCount
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore
    // In-flight page load; a newer load cancels it so a stale query can never
    // block a group switch or publish late results.
    private var loadImagesJob: Job? = null

    // Scan progress
    private val _scanProgress = MutableStateFlow("")
    val scanProgress: StateFlow<String> = _scanProgress

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage

    // --- Actions ---

    fun selectGroup(id: Long?) {
        _selectedGroupId.value = id
        _loadedImages.value = emptyList()
        _totalImageCount.value = 0
        if (id != null) {
            loadImages(id, reset = true)
        }
    }

    /**
     * Load images in pages. Call with reset=true for first load.
     */
    fun loadImages(groupId: Long, reset: Boolean = false) {
        if (!reset && _isLoadingMore.value) return
        // A new load supersedes any in-flight one: without this, quickly
        // switching groups while the previous group's page is still loading
        // would skip the new group's first page (the isLoadingMore guard) and
        // leave it stuck empty. The stale job is also guarded by the
        // selectedGroupId check below, so it can never publish late results.
        loadImagesJob?.cancel()
        loadImagesJob = viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                // Only publish results for the group that is still selected:
                // a slow query for a previously-opened group must never
                // overwrite the list of the group the user switched to.
                if (_selectedGroupId.value == groupId) {
                    if (reset) {
                        _totalImageCount.value = imageDao.getImageCountByGroup(groupId)
                        val firstPage = imageDao.getImagesByGroupPaged(groupId, PAGE_SIZE, 0)
                        _loadedImages.value = firstPage
                    } else {
                        val currentSize = _loadedImages.value.size
                        val nextPage = imageDao.getImagesByGroupPaged(groupId, PAGE_SIZE, currentSize)
                        if (nextPage.isNotEmpty()) {
                            _loadedImages.value = _loadedImages.value + nextPage
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "loadImages failed", e)
                _toastMessage.emit("加载图片失败: ${e.message}")
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun toggleService(enabled: Boolean) {
        viewModelScope.launch {
            settingsDao.setBool(SettingsKeys.SERVICE_ENABLED, enabled)
            if (enabled) WallpaperSwitchService.start(getApplication())
            else WallpaperSwitchService.stop(getApplication())
        }
    }

    fun toggleDoubleTap(enabled: Boolean) {
        viewModelScope.launch { settingsDao.setBool(SettingsKeys.DOUBLE_TAP_ENABLED, enabled) }
    }

    fun toggleUnlockSwitch(enabled: Boolean) {
        viewModelScope.launch { settingsDao.setBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, enabled) }
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
     * Create a new group and return its ID.
     */
    suspend fun createGroup(name: String): Long {
        return groupDao.insert(WallpaperGroup(name = name))
    }

    fun updateGroup(group: WallpaperGroup) {
        viewModelScope.launch { groupDao.update(group) }
    }

    fun deleteGroup(group: WallpaperGroup) {
        viewModelScope.launch {
            groupDao.delete(group)
            if (_selectedGroupId.value == group.id) _selectedGroupId.value = null
            // Clear last image ID if it belonged to the deleted group
            // (CASCADE deletes images, so the ID would point to nothing)
            val lastId = settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID)
            val image = imageDao.getImageById(lastId)
            if (image == null) {
                settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, 0L)
            }
        }
    }

    fun toggleGroupEnabled(groupId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val group = groupDao.getGroupById(groupId) ?: return@launch
            groupDao.update(group.copy(isEnabled = enabled))
        }
    }

    // --- Global settings ---

    val globalIntervalMs: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.GLOBAL_INTERVAL_MS)
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

    // Fade-in transition after each switch (default on).
    val switchFadeEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.SWITCH_FADE_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Periodic folder auto-scan
    val autoScanEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.AUTO_SCAN_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoScanIntervalMs: StateFlow<Long> = settingsDao.getValueFlow(SettingsKeys.AUTO_SCAN_INTERVAL_MS)
        .map { it?.toLongOrNull() ?: (24L * 60 * 60 * 1000) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 24L * 60 * 60 * 1000)

    fun toggleAutoScan(enabled: Boolean, intervalMs: Long) {
        viewModelScope.launch {
            settingsDao.setBool(SettingsKeys.AUTO_SCAN_ENABLED, enabled)
            settingsDao.setLong(SettingsKeys.AUTO_SCAN_INTERVAL_MS, intervalMs.coerceAtLeast(15 * 60_000L))
            if (enabled) scheduleAutoScan() else cancelAutoScan()
        }
    }

    private fun scheduleAutoScan() {
        val intervalMs = autoScanIntervalMs.value.coerceAtLeast(15 * 60_000L)
        val request = PeriodicWorkRequestBuilder<FolderAutoScanWorker>(intervalMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(getApplication())
            .enqueueUniquePeriodicWork("folder_auto_scan", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun cancelAutoScan() {
        WorkManager.getInstance(getApplication()).cancelUniqueWork("folder_auto_scan")
    }

    fun setGlobalInterval(ms: Long) {
        viewModelScope.launch { settingsDao.setLong(SettingsKeys.GLOBAL_INTERVAL_MS, ms.coerceAtLeast(10_000L)) }
    }

    fun setGlobalSwitchMode(mode: SwitchMode) {
        viewModelScope.launch { settingsDao.setString(SettingsKeys.GLOBAL_SWITCH_MODE, mode.name) }
    }

    fun setGlobalScaleMode(mode: ScaleMode) {
        viewModelScope.launch { settingsDao.setString(SettingsKeys.GLOBAL_SCALE_MODE, mode.name) }
    }

    fun setClarityMode(mode: String) {
        viewModelScope.launch { settingsDao.setString(SettingsKeys.CLARITY_MODE, mode) }
    }

    fun setSwitchFadeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDao.setBool(SettingsKeys.SWITCH_FADE_ENABLED, enabled) }
    }

    // Theme color (stored as hex string like "#6750A4", empty = system default)
    val themeColor: StateFlow<String> = settingsDao.getValueFlow(SettingsKeys.THEME_COLOR)
        .map { it ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setThemeColor(hex: String) {
        viewModelScope.launch { settingsDao.setString(SettingsKeys.THEME_COLOR, hex) }
    }

    fun addImage(groupId: Long, uri: Uri, displayName: String) {
        viewModelScope.launch {
            if (groupDao.getGroupById(groupId) == null) return@launch
            val mediaType = detectMediaType(displayName)
            imageDao.insert(WallpaperImage(
                groupId = groupId,
                uri = uri.toString(),
                displayName = displayName,
                mediaType = mediaType
            ))
            refreshCount(groupId)
            refreshImages()
        }
    }

    fun addImages(groupId: Long, uris: List<Uri>, names: List<String>) {
        viewModelScope.launch {
            if (groupDao.getGroupById(groupId) == null) return@launch
            val imagePairs = uris.zip(names).filter { (_, name) ->
                if (!isSupportedMedia(name)) return@filter false
                true
            }
            val images = imagePairs.map { (uri, name) ->
                WallpaperImage(groupId = groupId, uri = uri.toString(), displayName = name, mediaType = detectMediaType(name))
            }
            if (images.isNotEmpty()) {
                // Chunk large multi-select imports: 100 rows per INSERT stays
                // under the 999 bound-variable limit of older SQLite builds.
                images.chunked(100).forEach { chunk -> imageDao.insertAll(chunk) }
                refreshCount(groupId)
                refreshImages()
                _toastMessage.emit("Added ${images.size} images")
            } else {
                _toastMessage.emit("No images found")
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
                val batch = mutableListOf<WallpaperImage>()
                withContext(Dispatchers.IO) {
                    val docFile = try {
                        androidx.documentfile.provider.DocumentFile
                            .fromTreeUri(getApplication(), folderUri)
                    } catch (e: Exception) {
                        Log.e(TAG, "fromTreeUri failed", e)
                        null
                    } ?: return@withContext

                    if (!docFile.isDirectory) return@withContext

                    suspend fun scanDir(dir: androidx.documentfile.provider.DocumentFile) {
                        if (!isActive) return
                        val files = try {
                            dir.listFiles()
                        } catch (e: Exception) {
                            Log.e(TAG, "listFiles failed", e)
                            emptyArray()
                        }
                        for (file in files) {
                            if (!isActive) return
                            try {
                                if (file.isDirectory) {
                                    scanDir(file)
                                } else if (file.isFile && isSupportedMedia(file.name ?: "")) {
                                    batch.add(WallpaperImage(
                                        groupId = groupId,
                                        uri = file.uri.toString(),
                                        displayName = file.name ?: "untitled",
                                        mediaType = detectMediaType(file.name ?: ""),
                                        isFromFolder = true,
                                        folderPath = folderUri.toString()
                                    ))
                                    if (batch.size >= 100) {
                                        imageDao.insertAll(batch.toList())
                                        total += batch.size
                                        batch.clear()
                                    }
                                }
                            } catch (_: Exception) { continue }
                        }
                    }

                    scanDir(docFile)
                    if (batch.isNotEmpty() && isActive) {
                        imageDao.insertAll(batch)
                        total += batch.size
                        batch.clear()
                    }
                }
                if (total > 0) {
                    refreshCount(groupId)
                    refreshImages()
                    _toastMessage.emit("已添加 $total 个媒体")
                } else {
                    _toastMessage.emit("未找到媒体")
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "addFolder failed", e)
                    _toastMessage.emit("导入失败: ${e.message}")
                }
            }
        }
    }

    fun deleteImage(image: WallpaperImage) {
        viewModelScope.launch {
            imageDao.delete(image)
            _selectedGroupId.value?.let { refreshCount(it) }
            refreshImages()
        }
    }

    fun deleteImages(images: List<WallpaperImage>) {
        viewModelScope.launch {
            // Chunk the DELETE: older SQLite builds cap a statement at 999
            // bound variables, and a select-all delete can pass thousands of
            // ids (would throw "too many SQL variables").
            images.map { it.id }.chunked(500).forEach { chunk ->
                imageDao.deleteByIds(chunk)
            }
            _selectedGroupId.value?.let { refreshCount(it) }
            refreshImages()
        }
    }

    /**
     * Delete images by IDs directly — works across all pages, not just loaded ones.
     */
    fun deleteImagesByIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.toList().chunked(500).forEach { chunk ->
                imageDao.deleteByIds(chunk)
            }
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
                    _scanProgress.value = "检查中 $checked/${all.size}"
                }
                if (checked % 100 == 0) yield()
            }
            _scanProgress.value = ""
            broken
        }
    }

    fun switchNow() {
        WallpaperSwitchService.switchNow(getApplication())
    }

    fun setImageAsWallpaper(image: WallpaperImage) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "setImageAsWallpaper: id=${image.id} type=${image.mediaType} uri=${image.uri}")

                // 1. Save target ID. The engine reads this from DB when it starts.
                settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                Log.d(TAG, "LAST_IMAGE_ID saved: ${image.id}")
                // 1b. SEQUENTIAL mode continues from the wallpaper that was
                // just set, not from the beginning: anchor the index at the
                // item AFTER this one (in id order).
                anchorSequentialIndex(image)

                // 2. Engine running -> broadcast; otherwise set the static wallpaper.
                val engineRunning = LiveWallpaperService.engineRunning
                Log.d(TAG, "engineRunning=$engineRunning")
                if (engineRunning) {
                    sendTargetBroadcast(image.id)
                } else if (image.mediaType == "VIDEO" || image.mediaType == "GIF") {
                    // A static system wallpaper cannot play video/GIF animation.
                    // Guide the user to the live wallpaper setup instead, where
                    // the media is actually rendered.
                    launchLiveWallpaperPicker()
                } else {
                    val ok = withContext(Dispatchers.IO) {
                        WallpaperApplier.apply(getApplication(), image)
                    }
                    if (!ok) {
                        _toastMessage.emit("设置失败，无法读取该媒体文件")
                        return@launch
                    }
                }

                _toastMessage.emit("壁纸已设置！")
            } catch (e: Exception) {
                Log.e(TAG, "setImageAsWallpaper failed", e)
                _toastMessage.emit("设置失败: ${e.message}")
            }
        }
    }

    fun setAsLiveWallpaper(image: WallpaperImage) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "setAsLiveWallpaper: id=${image.id}")
                settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                anchorSequentialIndex(image)

                val engineRunning = LiveWallpaperService.engineRunning
                if (engineRunning) {
                    sendTargetBroadcast(image.id)
                    _toastMessage.emit("壁纸已设置！")
                } else {
                    // First-time setup: launch the live wallpaper picker.
                    launchLiveWallpaperPicker()
                }
            } catch (e: Exception) {
                Log.e(TAG, "setAsLiveWallpaper failed", e)
                _toastMessage.emit("设置失败: ${e.message}")
            }
        }
    }

    /**
     * Point SEQUENTIAL_INDEX at the media that follows [image] in the
     * id-ordered sequence, so 顺序播放 continues from the wallpaper the user
     * just set instead of restarting from the first item. Only applied when
     * the image belongs to an enabled group (otherwise it is not part of the
     * enabled sequence).
     */
    private suspend fun anchorSequentialIndex(image: WallpaperImage) {
        try {
            val group = groupDao.getGroupById(image.groupId) ?: return
            if (!group.isEnabled) return
            val total = imageDao.countByEnabledGroups()
            if (total <= 0) return
            val idx = imageDao.getSequentialIndexBefore(image.id)
            settingsDao.setLong(SettingsKeys.SEQUENTIAL_INDEX, ((idx + 1) % total).toLong())
        } catch (_: Exception) {}
    }

    private fun sendTargetBroadcast(targetId: Long) {
        val switchIntent = android.content.Intent(LiveWallpaperService.ACTION_SWITCH).apply {
            setPackage(getApplication<Application>().packageName)
            putExtra(LiveWallpaperService.EXTRA_TARGET_ID, targetId)
            putExtra(LiveWallpaperService.EXTRA_SOURCE, LiveWallpaperService.SOURCE_MANUAL)
        }
        getApplication<Application>().sendBroadcast(switchIntent)
        Log.d(TAG, "Switch broadcast sent with targetId=$targetId")
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
     * Refresh the first page of images (call from UI when needed)
     */
    fun refreshImages() {
        val groupId = _selectedGroupId.value ?: return
        loadImages(groupId, reset = true)
    }

    // ======== Folder scanning (background) ========

    /**
     * Scan device folders that contain images and/or videos (MediaStore).
     */
    suspend fun loadScannedFolders(): List<ScannedFolder> {
        return MediaScanner.scanFolders(getApplication())
    }

    /**
     * Import several scanned folders into a group (images + videos, deduped).
     */
    fun importScannedFolders(groupId: Long, folders: List<ScannedFolder>) {
        if (folders.isEmpty()) return
        viewModelScope.launch {
            try {
                _toastMessage.emit("正在从 ${folders.size} 个文件夹导入...")
                Log.d(TAG, "importScannedFolders: group=$groupId folders=${folders.map { it.path }}")
                _scanProgress.value = "查询中..."
                var total = 0
                withContext(Dispatchers.IO) {
                    val existing = imageDao.getUrisByGroup(groupId).toHashSet()
                    for (folder in folders) {
                        if (!isActive) return@withContext
                        val media = MediaScanner.queryFolderMedia(getApplication(), folder.path)
                        val batch = mutableListOf<WallpaperImage>()
                        for (m in media) {
                            if (m.uri in existing) continue
                            existing.add(m.uri)
                            batch.add(WallpaperImage(
                                groupId = groupId,
                                uri = m.uri,
                                displayName = m.displayName,
                                mediaType = m.mediaType,
                                isFromFolder = true,
                                folderPath = folder.path
                            ))
                            // 100 rows per INSERT keeps the bound-variable count
                            // well under the 999 limit of older SQLite builds.
                            if (batch.size >= 100) {
                                imageDao.insertAll(batch.toList())
                                total += batch.size
                                batch.clear()
                                withContext(Dispatchers.Main) {
                                    _scanProgress.value = "导入中 $total 个媒体"
                                }
                                yield()
                            }
                        }
                        if (batch.isNotEmpty() && isActive) {
                            imageDao.insertAll(batch)
                            total += batch.size
                        }
                    }
                }
                _scanProgress.value = ""
                refreshCount(groupId)
                refreshImages()
                if (total > 0) {
                    _toastMessage.emit("已导入 $total 个媒体")
                } else {
                    _toastMessage.emit("所选文件夹没有新媒体")
                }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "importScannedFolders failed", e)
                    _toastMessage.emit("导入失败: ${e.message}")
                }
                _scanProgress.value = ""
            }
        }
    }

    private fun isSupportedMedia(name: String): Boolean {
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "mp4", "mkv", "webm", "avi", "mov", "3gp")
    }

    private fun detectMediaType(name: String): String {
        val ext = name.lowercase().substringAfterLast('.', "")
        return when (ext) {
            "gif" -> "GIF"
            "mp4", "mkv", "webm", "avi", "mov", "3gp" -> "VIDEO"
            else -> "IMAGE"
        }
    }
}
