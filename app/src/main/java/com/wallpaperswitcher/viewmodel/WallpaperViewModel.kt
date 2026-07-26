package com.wallpaperswitcher.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallpaperswitcher.WallpaperSwitcherApp
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
}
