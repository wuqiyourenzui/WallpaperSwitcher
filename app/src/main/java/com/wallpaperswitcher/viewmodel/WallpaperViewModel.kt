package com.wallpaperswitcher.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallpaperswitcher.WallpaperSwitcherApp
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlinx.coroutines.*
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

    val serviceEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.SERVICE_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val doubleTapEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.DOUBLE_TAP_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val unlockSwitchEnabled: StateFlow<Boolean> = settingsDao.getValueFlow(SettingsKeys.UNLOCK_SWITCH_ENABLED)
        .map { it?.toBooleanStrictOrNull() ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _selectedGroupId = MutableStateFlow<Long?>(null)
    val selectedGroupId: StateFlow<Long?> = _selectedGroupId

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
        if (_isLoadingMore.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
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
     * Create a new group and return its ID.
     */
    suspend fun createGroup(name: String, type: String = "IMAGE"): Long {
        return groupDao.insert(WallpaperGroup(name = name, type = type))
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

    fun setGlobalInterval(ms: Long) {
        viewModelScope.launch { settingsDao.setLong(SettingsKeys.GLOBAL_INTERVAL_MS, ms.coerceAtLeast(10_000L)) }
    }

    fun setGlobalSwitchMode(mode: SwitchMode) {
        viewModelScope.launch { settingsDao.setString(SettingsKeys.GLOBAL_SWITCH_MODE, mode.name) }
    }

    fun setGlobalScaleMode(mode: ScaleMode) {
        viewModelScope.launch { settingsDao.setString(SettingsKeys.GLOBAL_SCALE_MODE, mode.name) }
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
            val group = groupDao.getGroupById(groupId) ?: return@launch
            val mediaType = detectMediaType(displayName)
            // Validate: IMAGE group can only have images, VIDEO group can only have videos
            if (group.type == "IMAGE" && mediaType == "VIDEO") {
                _toastMessage.emit("图片分组不能添加视频")
                return@launch
            }
            if (group.type == "VIDEO" && mediaType != "VIDEO") {
                _toastMessage.emit("视频分组只能添加视频")
                return@launch
            }
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
            val group = groupDao.getGroupById(groupId) ?: return@launch
            val imagePairs = uris.zip(names).filter { (uri, name) ->
                if (!isSupportedMedia(name)) return@filter false
                val mt = detectMediaType(name)
                if (group.type == "IMAGE" && mt == "VIDEO") return@filter false
                if (group.type == "VIDEO" && mt != "VIDEO") return@filter false
                true
            }
            val images = imagePairs.map { (uri, name) ->
                WallpaperImage(groupId = groupId, uri = uri.toString(), displayName = name, mediaType = detectMediaType(name))
            }
            if (images.isNotEmpty()) {
                imageDao.insertAll(images)
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
                val group = groupDao.getGroupById(groupId) ?: return@launch
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
                                    val mt = detectMediaType(file.name ?: "")
                                    // Filter by group type
                                    if (group.type == "IMAGE" && mt == "VIDEO") continue
                                    if (group.type == "VIDEO" && mt != "VIDEO") continue
                                    batch.add(WallpaperImage(
                                        groupId = groupId,
                                        uri = file.uri.toString(),
                                        displayName = file.name ?: "untitled",
                                        mediaType = mt,
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
                    _toastMessage.emit("已添加 $total 张图片")
                } else {
                    _toastMessage.emit("未找到图片")
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
        }
    }

    fun deleteImages(images: List<WallpaperImage>) {
        viewModelScope.launch {
            imageDao.deleteByIds(images.map { it.id })
            _selectedGroupId.value?.let { refreshCount(it) }
        }
    }

    /**
     * Delete images by IDs directly — works across all pages, not just loaded ones.
     */
    fun deleteImagesByIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            imageDao.deleteByIds(ids.toList())
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

    fun switchNow() {
        WallpaperSwitchService.switchNow(getApplication())
    }

    fun setImageAsWallpaper(image: WallpaperImage) {
        viewModelScope.launch {
            try {
                // 1. Save target ID (live wallpaper reads this from DB)
                settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)

                // 2. Send broadcast to running live wallpaper engine
                val switchIntent = android.content.Intent(com.wallpaperswitcher.wallpaper.LiveWallpaperService.ACTION_SWITCH).apply {
                    setPackage(getApplication<Application>().packageName)
                    putExtra(com.wallpaperswitcher.wallpaper.LiveWallpaperService.EXTRA_TARGET_ID, image.id)
                }
                getApplication<Application>().sendBroadcast(switchIntent)

                // 3. Always launch picker to ensure system wallpaper is set.
                //    If engine is already running, the picker will briefly destroy/recreate it,
                //    but the new engine will read the correct LAST_IMAGE_ID from DB.
                //    Without this, after group delete+recreate, the engine may be in a
                //    broken state (surface destroyed, stale image reference) and the
                //    broadcast alone won't recover it.
                launchLiveWallpaperPicker()

                _toastMessage.emit("壁纸已设置！")
            } catch (e: Exception) {
                _toastMessage.emit("设置失败: ${e.message}")
            }
        }
    }

    fun setAsLiveWallpaper(image: WallpaperImage) {
        setImageAsWallpaper(image) // Same logic
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
        viewModelScope.launch {
            _totalImageCount.value = imageDao.getImageCountByGroup(groupId)
            _loadedImages.value = imageDao.getImagesByGroupPaged(groupId, PAGE_SIZE, 0)
        }
    }

    // ======== Folder scanning (background) ========

    /**
     * Scan device image folders via MediaStore.
     * Compatible with Xiaomi/MIUI devices.
     * Only scans images (no videos).
     */
    suspend fun scanImageFolders(): List<ScannedFolder> = withContext(Dispatchers.IO) {
        try {
            val folderCounts = mutableMapOf<String, Int>()
            val folderSamples = mutableMapOf<String, MutableList<String>>() // only a few URIs per folder
            val folderNames = mutableMapOf<String, String>()
            val contentResolver = getApplication<Application>().contentResolver

            // Use RELATIVE_PATH on API 29+, fall back to DATA on older versions
            val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val projection = if (useRelativePath) {
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
            }

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val pathCol = cursor.getColumnIndex(if (useRelativePath) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLong(idCol)
                        val rawPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                        if (rawPath.isNullOrBlank()) continue

                        // Normalize folder key: RELATIVE_PATH ends with '/', DATA is absolute
                        val folderKey = if (useRelativePath) {
                            rawPath.trimEnd('/')
                        } else {
                            @Suppress("DEPRECATION")
                            rawPath.substringBeforeLast('/')
                        }
                        if (folderKey.isEmpty()) continue

                        // Count images per folder
                        folderCounts[folderKey] = (folderCounts[folderKey] ?: 0) + 1

                        // Only store first 3 URIs per folder for preview
                        val samples = folderSamples.getOrPut(folderKey) { mutableListOf() }
                        if (samples.size < 3) {
                            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                            samples.add(uri.toString())
                        }

                        folderNames.putIfAbsent(folderKey, folderKey.substringAfterLast('/').ifEmpty { "Root" })
                    } catch (_: Exception) { continue }
                }
            }

            folderCounts.map { (path, count) ->
                ScannedFolder(
                    path = path,
                    name = folderNames[path] ?: path,
                    imageCount = count,
                    sampleUris = folderSamples[path] ?: emptyList()
                )
            }
                .filter { it.imageCount >= 2 }
                .filter { f ->
                    val segments = f.path.split("/").map { it.lowercase() }
                    val blocked = setOf("android", ".thumbnails", ".cache", ".trash", "obb")
                    segments.none { it in blocked }
                }
                .sortedByDescending { it.imageCount }
        } catch (e: Exception) {
            Log.e(TAG, "scanImageFolders failed", e)
            emptyList()
        }
    }

    /**
     * Import all images from a scanned folder (batch insert).
     */
    /**
     * Import all images from a scanned folder.
     * Re-queries MediaStore to get all URIs (not just samples).
     */
    fun importScannedFolder(groupId: Long, folder: ScannedFolder) {
        viewModelScope.launch {
            try {
                _toastMessage.emit("正在从「${folder.name}」导入...")
                _scanProgress.value = "查询中..."
                var total = 0
                withContext(Dispatchers.IO) {
                    val contentResolver = getApplication<Application>().contentResolver
                    val projection = arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME
                    )
                    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.SIZE} > 0"
                    } else {
                        "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.SIZE} > 0"
                    }
                    val selectionArgs = arrayOf("${folder.path}%")

                    contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection, selection, selectionArgs, null
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val batch = mutableListOf<WallpaperImage>()

                        while (cursor.moveToNext()) {
                            if (!isActive) return@withContext
                            try {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "untitled"
                                val uri = Uri.withAppendedPath(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                                )
                                batch.add(WallpaperImage(
                                    groupId = groupId,
                                    uri = uri.toString(),
                                    displayName = name,
                                    isFromFolder = true,
                                    folderPath = folder.path
                                ))
                                if (batch.size >= 500) {
                                    imageDao.insertAll(batch.toList())
                                    total += batch.size
                                    batch.clear()
                                    withContext(Dispatchers.Main) {
                                        _scanProgress.value = "导入中 $total 张"
                                    }
                                    yield()
                                }
                            } catch (_: Exception) { continue }
                        }
                        if (batch.isNotEmpty() && isActive) {
                            imageDao.insertAll(batch)
                            total += batch.size
                        }
                    }
                    _scanProgress.value = ""
                }
                if (total > 0) {
                    refreshCount(groupId)
                    refreshImages()
                    _toastMessage.emit("已从「${folder.name}」导入 $total 张图片")
                } else {
                    _toastMessage.emit("未找到图片")
                }
            } catch (e: Exception) {
                _toastMessage.emit("导入失败: ${e.message}")
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

data class ScannedFolder(
    val path: String,
    val name: String,
    val imageCount: Int,
    val sampleUris: List<String> = emptyList() // Only a few for preview
)
