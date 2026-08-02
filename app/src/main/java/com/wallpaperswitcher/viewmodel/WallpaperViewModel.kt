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

    private val db = (app as WallpaperSwitcherApp).database
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

    fun createGroup(name: String) {
        viewModelScope.launch {
            groupDao.insert(WallpaperGroup(name = name))
        }
    }

    fun updateGroup(group: WallpaperGroup) {
        viewModelScope.launch { groupDao.update(group) }
    }

    fun deleteGroup(group: WallpaperGroup) {
        viewModelScope.launch {
            groupDao.delete(group)
            if (_selectedGroupId.value == group.id) _selectedGroupId.value = null
        }
    }

    fun updateGroupInterval(groupId: Long, intervalMs: Long) {
        viewModelScope.launch {
            val group = groupDao.getGroupById(groupId) ?: return@launch
            groupDao.update(group.copy(switchIntervalMs = intervalMs.coerceAtLeast(60_000L)))
        }
    }

    fun updateGroupSwitchMode(groupId: Long, mode: SwitchMode) {
        viewModelScope.launch {
            val group = groupDao.getGroupById(groupId) ?: return@launch
            groupDao.update(group.copy(switchMode = mode))
        }
    }

    fun updateGroupScaleMode(groupId: Long, mode: ScaleMode) {
        viewModelScope.launch {
            val group = groupDao.getGroupById(groupId) ?: return@launch
            groupDao.update(group.copy(scaleMode = mode))
        }
    }

    fun toggleGroupEnabled(groupId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val group = groupDao.getGroupById(groupId) ?: return@launch
            groupDao.update(group.copy(isEnabled = enabled))
        }
    }

    fun addImage(groupId: Long, uri: Uri, displayName: String, isVideo: Boolean = false) {
        viewModelScope.launch {
            imageDao.insert(WallpaperImage(groupId = groupId, uri = uri.toString(), displayName = displayName, isVideo = isVideo))
            refreshCount(groupId)
        }
    }

    fun addImages(groupId: Long, uris: List<Uri>, names: List<String>) {
        viewModelScope.launch {
            // Filter to images only
            val imagePairs = uris.zip(names).filter { (uri, name) ->
                isImageOnly(name) || uri.toString().contains("image")
            }
            val images = imagePairs.map { (uri, name) ->
                WallpaperImage(groupId = groupId, uri = uri.toString(), displayName = name)
            }
            if (images.isNotEmpty()) {
                imageDao.insertAll(images)
                refreshCount(groupId)
                _toastMessage.emit("Added ${images.size} images")
            } else {
                _toastMessage.emit("No images found")
            }
        }
    }

    /**
     * Add folder via DocumentFile (SAF).
     * Runs in background, shows progress via toast.
     */
    private var addFolderJob: Job? = null

    fun addFolder(groupId: Long, folderUri: Uri) {
        addFolderJob?.cancel() // Cancel previous add if running
        addFolderJob = viewModelScope.launch {
            try {
                _toastMessage.emit("Scanning folder...")
                var total = 0
                withContext(Dispatchers.IO) {
                    val docFile = androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(getApplication(), folderUri) ?: return@withContext
                    if (!docFile.isDirectory) return@withContext
                    val batch = mutableListOf<WallpaperImage>()
                    docFile.listFiles().forEach { file ->
                        if (!isActive) return@withContext // Check cancellation
                        if (file.isFile && isImageOnly(file.name ?: "")) {
                            batch.add(WallpaperImage(
                                groupId = groupId,
                                uri = file.uri.toString(),
                                displayName = file.name ?: "untitled",
                                isFromFolder = true,
                                folderPath = folderUri.toString()
                            ))
                            if (batch.size >= 100) {
                                imageDao.insertAll(batch.toList())
                                total += batch.size
                                batch.clear()
                            }
                        }
                    }
                    if (batch.isNotEmpty() && isActive) {
                        imageDao.insertAll(batch)
                        total += batch.size
                    }
                }
                if (total > 0) {
                    refreshCount(groupId)
                    _toastMessage.emit("Added $total images")
                } else {
                    _toastMessage.emit("No images found")
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "addFolder failed", e)
                    _toastMessage.emit("Failed: ${e.message}")
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

    fun switchNow() {
        WallpaperSwitchService.switchNow(getApplication())
    }

    fun setImageAsWallpaper(image: WallpaperImage) {
        viewModelScope.launch {
            try {
                settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                // Update live wallpaper surface
                val intent = com.wallpaperswitcher.wallpaper.LiveWallpaperService.ACTION_SWITCH
                val switchIntent = android.content.Intent(intent)
                switchIntent.setPackage(getApplication<Application>().packageName)
                getApplication<Application>().sendBroadcast(switchIntent)
                // Also set via WallpaperManager for non-live-wallpaper mode
                val engine = com.wallpaperswitcher.engine.WallpaperEngine(getApplication())
                engine.setWallpaperForImage(image.uri)
                _toastMessage.emit("Wallpaper set!")
            } catch (e: Exception) {
                _toastMessage.emit("Error: ${e.message}")
            }
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

            // Try RELATIVE_PATH first (API 29+), fall back to DATA
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA
            )

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLong(idCol)

                        // Get folder path from DATA column
                        val folderKey = if (dataCol >= 0) {
                            val dataPath = cursor.getString(dataCol)
                            if (!dataPath.isNullOrBlank()) dataPath.substringBeforeLast('/') else null
                        } else null
                        ?: continue

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
                .filter { f -> listOf("Android", ".thumbnails", ".cache", ".Trash", "obb").none { f.path.contains(it, ignoreCase = true) } }
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
                _toastMessage.emit("Importing images from '${folder.name}'...")
                val images = withContext(Dispatchers.IO) {
                    val result = mutableListOf<WallpaperImage>()
                    val contentResolver = getApplication<Application>().contentResolver
                    val projection = arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME
                    )
                    // Match folder path using LIKE query
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
                        while (cursor.moveToNext()) {
                            try {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "untitled"
                                val uri = Uri.withAppendedPath(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                                )
                                result.add(WallpaperImage(
                                    groupId = groupId,
                                    uri = uri.toString(),
                                    displayName = name,
                                    isFromFolder = true,
                                    folderPath = folder.path
                                ))
                            } catch (_: Exception) { continue }
                        }
                    }
                    result
                }
                if (images.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        images.chunked(500).forEach { imageDao.insertAll(it) }
                    }
                    refreshCount(groupId)
                    _toastMessage.emit("Imported ${images.size} images from '${folder.name}'")
                } else {
                    _toastMessage.emit("No images found")
                }
            } catch (e: Exception) {
                _toastMessage.emit("Import failed: ${e.message}")
            }
        }
    }

    private fun isImageOnly(name: String): Boolean {
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    }
}

data class ScannedFolder(
    val path: String,
    val name: String,
    val imageCount: Int,
    val sampleUris: List<String> = emptyList() // Only a few for preview
)
