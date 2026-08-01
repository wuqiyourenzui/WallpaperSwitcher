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

    // --- 状态 ---

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

    val selectedGroupImages: StateFlow<List<WallpaperImage>> = _selectedGroupId
        .filterNotNull()
        .flatMapLatest { imageDao.getImagesByGroup(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage

    // --- 操作 ---

    fun selectGroup(id: Long?) {
        _selectedGroupId.value = id
    }

    fun toggleService(enabled: Boolean) {
        viewModelScope.launch {
            settingsDao.setBool(SettingsKeys.SERVICE_ENABLED, enabled)
            if (enabled) {
                WallpaperSwitchService.start(getApplication())
                _toastMessage.emit("壁纸切换已开启")
            } else {
                WallpaperSwitchService.stop(getApplication())
                _toastMessage.emit("壁纸切换已关闭")
            }
        }
    }

    fun toggleDoubleTap(enabled: Boolean) {
        viewModelScope.launch {
            settingsDao.setBool(SettingsKeys.DOUBLE_TAP_ENABLED, enabled)
        }
    }

    fun toggleUnlockSwitch(enabled: Boolean) {
        viewModelScope.launch {
            settingsDao.setBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, enabled)
        }
    }

    fun createGroup(name: String) {
        viewModelScope.launch {
            val group = WallpaperGroup(
                name = name,
                switchIntervalMs = 60_000L,
                switchMode = SwitchMode.RANDOM,
                scaleMode = ScaleMode.FIT
            )
            groupDao.insert(group)
        }
    }

    fun updateGroup(group: WallpaperGroup) {
        viewModelScope.launch { groupDao.update(group) }
    }

    fun deleteGroup(group: WallpaperGroup) {
        viewModelScope.launch {
            groupDao.delete(group)
            if (_selectedGroupId.value == group.id) {
                _selectedGroupId.value = null
            }
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

    /**
     * 添加单张图片
     */
    fun addImage(groupId: Long, uri: Uri, displayName: String, isVideo: Boolean = false) {
        viewModelScope.launch {
            val image = WallpaperImage(
                groupId = groupId,
                uri = uri.toString(),
                displayName = displayName,
                isVideo = isVideo
            )
            imageDao.insert(image)
        }
    }

    /**
     * 批量添加图片
     */
    fun addImages(groupId: Long, uris: List<Uri>, names: List<String>) {
        viewModelScope.launch {
            val images = uris.zip(names).map { (uri, name) ->
                WallpaperImage(
                    groupId = groupId,
                    uri = uri.toString(),
                    displayName = name
                )
            }
            imageDao.insertAll(images)
            _toastMessage.emit("已添加 ${images.size} 张图片")
        }
    }

    /**
     * 添加文件夹中所有图片
     */
    fun addFolder(groupId: Long, folderUri: Uri) {
        viewModelScope.launch {
            try {
                val images = mutableListOf<WallpaperImage>()
                val contentResolver = getApplication<Application>().contentResolver

                // 查询文件夹中的图片
                val childrenUri = Uri.parse(
                    "${folderUri}/document/primary"
                )

                // 使用 DocumentFile 遍历
                val documentFile = androidx.documentfile.provider.DocumentFile
                    .fromTreeUri(getApplication(), folderUri)

                if (documentFile != null && documentFile.isDirectory) {
                    documentFile.listFiles().forEach { file ->
                        if (file.isFile && isImageOrVideo(file.name ?: "")) {
                            images.add(
                                WallpaperImage(
                                    groupId = groupId,
                                    uri = file.uri.toString(),
                                    displayName = file.name ?: "未命名",
                                    isVideo = isVideoFile(file.name ?: ""),
                                    isFromFolder = true,
                                    folderPath = folderUri.toString()
                                )
                            )
                        }
                    }
                }

                if (images.isNotEmpty()) {
                    imageDao.insertAll(images)
                    _toastMessage.emit("已从文件夹添加 ${images.size} 张图片")
                } else {
                    _toastMessage.emit("文件夹中没有找到图片")
                }
            } catch (e: Exception) {
                _toastMessage.emit("添加文件夹失败: ${e.message}")
            }
        }
    }

    fun deleteImage(image: WallpaperImage) {
        viewModelScope.launch { imageDao.delete(image) }
    }

    fun deleteImages(images: List<WallpaperImage>) {
        viewModelScope.launch {
            imageDao.deleteByIds(images.map { it.id })
        }
    }

    fun switchNow() {
        WallpaperSwitchService.switchNow(getApplication())
        viewModelScope.launch { _toastMessage.emit("正在切换壁纸...") }
    }

    private fun isImageOrVideo(name: String): Boolean {
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "mp4", "3gp", "webm")
    }

    private fun isVideoFile(name: String): Boolean {
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in listOf("mp4", "3gp", "webm")
    }

    // ======== 自动扫描文件夹 ========

    /**
     * 扫描设备上包含图片的文件夹
     * 返回按图片数量降序排列的文件夹列表
     */
    suspend fun scanImageFolders(): List<ScannedFolder> = withContext(Dispatchers.IO) {
        val folders = mutableMapOf<String, MutableList<String>>() // folderPath -> list of content URIs
        val folderDisplayNames = mutableMapOf<String, String>()
        val contentResolver = getApplication<Application>().contentResolver

        // API 29+ 用 RELATIVE_PATH，旧版用 DATA
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val projection = if (useRelativePath) {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            )
        } else {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA
            )
        }

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue

                val contentUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )

                // 获取文件夹路径
                val folderKey = if (useRelativePath) {
                    val relPath = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    ) ?: ""
                    relPath.trimEnd('/').ifEmpty { null } ?: continue
                } else {
                    val dataPath = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    ) ?: continue
                    dataPath.substringBeforeLast('/').ifEmpty { null } ?: continue
                }

                folders.getOrPut(folderKey) { mutableListOf() }.add(contentUri.toString())

                if (folderKey !in folderDisplayNames) {
                    folderDisplayNames[folderKey] = folderKey.substringAfterLast('/').ifEmpty { "根目录" }
                }
            }
        }

        // 转换为 ScannedFolder 列表，过滤系统目录，按图片数量降序
        folders.map { (path, uris) ->
            ScannedFolder(
                path = path,
                name = folderDisplayNames[path] ?: path,
                imageCount = uris.size,
                sampleUris = uris
            )
        }
            .filter { it.imageCount >= 1 }
            .filter { !isSystemFolder(it.path) }
            .sortedByDescending { it.imageCount }
    }

    /**
     * 将扫描到的文件夹中的图片批量添加到分组
     */
    fun importScannedFolder(groupId: Long, folder: ScannedFolder) {
        viewModelScope.launch {
            try {
                val images = folder.sampleUris.map { uriStr ->
                    WallpaperImage(
                        groupId = groupId,
                        uri = uriStr,
                        displayName = Uri.parse(uriStr).lastPathSegment ?: "未命名",
                        isFromFolder = true,
                        folderPath = folder.path
                    )
                }

                if (images.isNotEmpty()) {
                    imageDao.insertAll(images)
                    _toastMessage.emit("已从「${folder.name}」导入 ${images.size} 张图片")
                } else {
                    _toastMessage.emit("该文件夹中没有找到图片")
                }
            } catch (e: Exception) {
                _toastMessage.emit("导入失败: ${e.message}")
            }
        }
    }

    private fun isSystemFolder(path: String): Boolean {
        val systemPaths = listOf(
            "Android", ".thumbnails", ".cache", ".data", ".Trash"
        )
        return systemPaths.any { path.contains(it) }
    }
}

/**
 * 扫描到的文件夹信息
 */
data class ScannedFolder(
    val path: String,
    val name: String,
    val imageCount: Int,
    val sampleUris: List<String> = emptyList()
)
