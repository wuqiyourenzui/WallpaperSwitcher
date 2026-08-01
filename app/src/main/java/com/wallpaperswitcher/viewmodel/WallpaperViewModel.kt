package com.wallpaperswitcher.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallpaperswitcher.WallpaperSwitcherApp
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WallpaperViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as WallpaperSwitcherApp).database
    private val groupDao = db.wallpaperGroupDao()
    private val imageDao = db.wallpaperImageDao()
    private val settingsDao = db.settingsDao()

    companion object {
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
            val images = uris.zip(names).map { (uri, name) ->
                WallpaperImage(groupId = groupId, uri = uri.toString(), displayName = name)
            }
            imageDao.insertAll(images)
            refreshCount(groupId)
            _toastMessage.emit("Added ${images.size} images")
        }
    }

    /**
     * Add folder via DocumentFile (SAF).
     * Runs in background, shows progress via toast.
     */
    fun addFolder(groupId: Long, folderUri: Uri) {
        viewModelScope.launch {
            try {
                _toastMessage.emit("Scanning folder...")
                val images = withContext(Dispatchers.IO) {
                    val result = mutableListOf<WallpaperImage>()
                    val docFile = androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(getApplication(), folderUri) ?: return@withContext result
                    if (!docFile.isDirectory) return@withContext result
                    docFile.listFiles().forEach { file ->
                        if (file.isFile && isImageOrVideo(file.name ?: "")) {
                            result.add(WallpaperImage(
                                groupId = groupId,
                                uri = file.uri.toString(),
                                displayName = file.name ?: "untitled",
                                isVideo = isVideoFile(file.name ?: ""),
                                isFromFolder = true,
                                folderPath = folderUri.toString()
                            ))
                        }
                    }
                    result
                }
                if (images.isNotEmpty()) {
                    withContext(Dispatchers.IO) { imageDao.insertAll(images) }
                    refreshCount(groupId)
                    _toastMessage.emit("Added ${images.size} images from folder")
                } else {
                    _toastMessage.emit("No images found in folder")
                }
            } catch (e: Exception) {
                _toastMessage.emit("Failed: ${e.message}")
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
                _toastMessage.emit("Wallpaper set!")
            } catch (e: Exception) {
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }

    private suspend fun refreshCount(groupId: Long) {
        _totalImageCount.value = imageDao.getImageCountByGroup(groupId)
        // Reload first page
        _loadedImages.value = imageDao.getImagesByGroupPaged(groupId, PAGE_SIZE, 0)
    }

    // ======== Folder scanning (background) ========

    /**
     * Scan device folders via MediaStore. Returns folder name -> URI list.
     * Much faster than scanning file system.
     */
    suspend fun scanImageFolders(): List<ScannedFolder> = withContext(Dispatchers.IO) {
        val folders = mutableMapOf<String, MutableList<String>>()
        val folderNames = mutableMapOf<String, String>()
        val contentResolver = getApplication<Application>().contentResolver
        val useRelPath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val projection = if (useRelPath) {
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH)
        } else {
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        }

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())

                val folderKey = if (useRelPath) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))
                        ?.trimEnd('/')?.ifEmpty { null } ?: continue
                } else {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                        ?.substringBeforeLast('/')?.ifEmpty { null } ?: continue
                }

                folders.getOrPut(folderKey) { mutableListOf() }.add(uri.toString())
                folderNames.putIfAbsent(folderKey, folderKey.substringAfterLast('/').ifEmpty { "Root" })
            }
        }

        folders.map { (path, uris) ->
            ScannedFolder(path = path, name = folderNames[path] ?: path, imageCount = uris.size, sampleUris = uris)
        }
            .filter { it.imageCount >= 2 }
            .filter { f -> listOf("Android", ".thumbnails", ".cache", ".Trash").none { f.path.contains(it) } }
            .sortedByDescending { it.imageCount }
    }

    /**
     * Import all images from a scanned folder (batch insert).
     */
    fun importScannedFolder(groupId: Long, folder: ScannedFolder) {
        viewModelScope.launch {
            try {
                _toastMessage.emit("Importing ${folder.imageCount} images...")
                val images = withContext(Dispatchers.IO) {
                    folder.sampleUris.map { uriStr ->
                        WallpaperImage(
                            groupId = groupId,
                            uri = uriStr,
                            displayName = Uri.parse(uriStr).lastPathSegment ?: "untitled",
                            isFromFolder = true,
                            folderPath = folder.path
                        )
                    }
                }
                // Batch insert (500 at a time)
                withContext(Dispatchers.IO) {
                    images.chunked(500).forEach { chunk ->
                        imageDao.insertAll(chunk)
                    }
                }
                refreshCount(groupId)
                _toastMessage.emit("Imported ${images.size} images from '${folder.name}'")
            } catch (e: Exception) {
                _toastMessage.emit("Import failed: ${e.message}")
            }
        }
    }

    private fun isImageOrVideo(name: String): Boolean {
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "mp4", "3gp", "webm")
    }

    private fun isVideoFile(name: String): Boolean {
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in listOf("mp4", "3gp", "webm")
    }
}

data class ScannedFolder(
    val path: String,
    val name: String,
    val imageCount: Int,
    val sampleUris: List<String> = emptyList()
)
