package com.wallpaperswitcher.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.viewmodel.ScannedFolder
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    viewModel: WallpaperViewModel,
    groupId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val group by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val images by viewModel.selectedGroupImages.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showScanDialog by remember { mutableStateOf(false) }
    var selectedImages by remember { mutableStateOf(setOf<Long>()) }
    var isSelectionMode by remember { mutableStateOf(false) }

    // 图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val names = uris.map { uri ->
                uri.lastPathSegment ?: "未命名"
            }
            viewModel.addImages(groupId, uris, names)
            // 持久化权限
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
        }
    }

    // 文件夹选择器
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.addFolder(groupId, it)
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
    }

    // 单张图片选择器
    val singleImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.addImage(groupId, it, it.lastPathSegment ?: "未命名")
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
    }

    val currentGroup = group

    Column(modifier = Modifier.fillMaxSize()) {
        // 分组信息头部
        if (currentGroup != null) {
            GroupInfoHeader(
                group = currentGroup,
                imageCount = images.size,
                onSettingsClick = { showSettingsDialog = true },
                onDeleteClick = {
                    viewModel.deleteGroup(currentGroup)
                    onBack()
                }
            )
        }

        // 操作栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Add, "添加", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加壁纸")
            }

            if (images.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        isSelectionMode = !isSelectionMode
                        if (!isSelectionMode) selectedImages = emptySet()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isSelectionMode) Icons.Filled.Close else Icons.Filled.Checklist,
                        "选择",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isSelectionMode) "取消选择" else "批量操作")
                }
            }
        }

        // 选择模式操作栏
        AnimatedVisibility(visible = isSelectionMode && selectedImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "已选 ${selectedImages.size} 张",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                Spacer(modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick = {
                        val toDelete = images.filter { it.id in selectedImages }
                        viewModel.deleteImages(toDelete)
                        selectedImages = emptySet()
                        isSelectionMode = false
                    }
                ) {
                    Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除所选")
                }
            }
        }

        // 图片网格
        if (images.isEmpty()) {
            EmptyImagesHint()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(images, key = { it.id }) { image ->
                    ImageGridItem(
                        image = image,
                        isSelected = image.id in selectedImages,
                        selectionMode = isSelectionMode,
                        onClick = {
                            if (isSelectionMode) {
                                selectedImages = if (image.id in selectedImages)
                                    selectedImages - image.id
                                else
                                    selectedImages + image.id
                            }
                        },
                        onDelete = { viewModel.deleteImage(image) },
                        onSetWallpaper = { viewModel.setImageAsWallpaper(image) }
                    )
                }
            }
        }
    }

    // 添加壁纸对话框
    if (showAddDialog) {
        AddWallpaperDialog(
            onDismiss = { showAddDialog = false },
            onAddSingle = {
                showAddDialog = false
                singleImagePicker.launch(arrayOf("image/*"))
            },
            onAddMultiple = {
                showAddDialog = false
                imagePickerLauncher.launch(arrayOf("image/*"))
            },
            onAddFolder = {
                showAddDialog = false
                folderPickerLauncher.launch(null)
            },
            onScanFolders = {
                showAddDialog = false
                showScanDialog = true
            }
        )
    }

    // 自动扫描文件夹对话框
    if (showScanDialog) {
        ScanFoldersDialog(
            viewModel = viewModel,
            groupId = groupId,
            onDismiss = { showScanDialog = false }
        )
    }

    // 分组设置对话框
    if (showSettingsDialog && currentGroup != null) {
        GroupSettingsDialog(
            group = currentGroup,
            onDismiss = { showSettingsDialog = false },
            onUpdate = { updated ->
                viewModel.updateGroup(updated)
                showSettingsDialog = false
            },
            onIntervalChange = { viewModel.updateGroupInterval(groupId, it) },
            onSwitchModeChange = { viewModel.updateGroupSwitchMode(groupId, it) },
            onScaleModeChange = { viewModel.updateGroupScaleMode(groupId, it) }
        )
    }
}

@Composable
private fun GroupInfoHeader(
    group: WallpaperGroup,
    imageCount: Int,
    onSettingsClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "$imageCount 张壁纸",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }

            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, "设置")
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    Icons.Filled.Delete,
                    "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除分组") },
            text = { Text("确定删除「${group.name}」及其所有壁纸？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteClick()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ImageGridItem(
    image: WallpaperImage,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSetWallpaper: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(
                if (isSelected)
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else
                    Modifier
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(Uri.parse(image.uri))
                .crossfade(true)
                .build(),
            contentDescription = image.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 选择指示器
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 更多按钮
        if (!selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            ) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Set as Wallpaper") },
                        onClick = { showMenu = false; onSetWallpaper() },
                        leadingIcon = { Icon(Icons.Filled.Wallpaper, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }

        // 视频标识
        if (image.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("视频", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun EmptyImagesHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.AddPhotoAlternate,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "还没有壁纸",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "点击「添加壁纸」开始",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun AddWallpaperDialog(
    onDismiss: () -> Unit,
    onAddSingle: () -> Unit,
    onAddMultiple: () -> Unit,
    onAddFolder: () -> Unit,
    onScanFolders: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加壁纸") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onAddSingle,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Image, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("选择单张图片", modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onAddMultiple,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("选择多张图片", modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onAddFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("添加整个文件夹", modifier = Modifier.weight(1f))
                }
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                TextButton(
                    onClick = onScanFolders,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动扫描文件夹")
                        Text(
                            "扫描设备上包含图片的文件夹",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsDialog(
    group: WallpaperGroup,
    onDismiss: () -> Unit,
    onUpdate: (WallpaperGroup) -> Unit,
    onIntervalChange: (Long) -> Unit,
    onSwitchModeChange: (SwitchMode) -> Unit,
    onScaleModeChange: (ScaleMode) -> Unit
) {
    var intervalMinutes by remember { mutableStateOf((group.switchIntervalMs / 60_000).toInt()) }
    var selectedSwitchMode by remember { mutableStateOf(group.switchMode) }
    var selectedScaleMode by remember { mutableStateOf(group.scaleMode) }
    var intervalText by remember { mutableStateOf(intervalMinutes.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分组设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 切换间隔
                Text("切换间隔（分钟）", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { newValue ->
                        intervalText = newValue
                        newValue.toIntOrNull()?.let { intervalMinutes = it }
                    },
                    label = { Text("最小 1 分钟") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        val min = intervalText.toIntOrNull() ?: 0
                        if (min < 1) Text("不能小于 1 分钟", color = MaterialTheme.colorScheme.error)
                    }
                )

                // 快捷间隔按钮
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 15, 30, 60).forEach { min ->
                        FilterChip(
                            selected = intervalMinutes == min,
                            onClick = {
                                intervalMinutes = min
                                intervalText = min.toString()
                            },
                            label = { Text("${min}分") }
                        )
                    }
                }

                Divider()

                // 切换模式
                Text("切换模式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedSwitchMode == mode,
                            onClick = { selectedSwitchMode = mode },
                            label = {
                                Text(
                                    when (mode) {
                                        SwitchMode.RANDOM -> "随机"
                                        SwitchMode.SEQUENTIAL -> "顺序"
                                        SwitchMode.SHUFFLE -> "洗牌"
                                    }
                                )
                            }
                        )
                    }
                }

                Divider()

                // 缩放模式
                Text("图片适配", style = MaterialTheme.typography.labelLarge)
                Column {
                    ScaleMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedScaleMode = mode }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedScaleMode == mode,
                                onClick = { selectedScaleMode = mode }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    when (mode) {
                                        ScaleMode.FILL -> "填充"
                                        ScaleMode.FIT -> "适应"
                                        ScaleMode.STRETCH -> "拉伸"
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    when (mode) {
                                        ScaleMode.FILL -> "裁剪填满屏幕，保持比例"
                                        ScaleMode.FIT -> "完整显示图片，可能有黑边"
                                        ScaleMode.STRETCH -> "强制拉伸填满屏幕"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalMinutes = intervalMinutes.coerceAtLeast(1)
                    onIntervalChange(finalMinutes * 60_000L)
                    onSwitchModeChange(selectedSwitchMode)
                    onScaleModeChange(selectedScaleMode)
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 自动扫描文件夹对话框
 * 扫描设备上包含图片的文件夹，支持一键导入
 */
@Composable
fun ScanFoldersDialog(
    viewModel: WallpaperViewModel,
    groupId: Long,
    onDismiss: () -> Unit
) {
    var scannedFolders by remember { mutableStateOf<List<ScannedFolder>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var importedPaths by remember { mutableStateOf(setOf<String>()) }

    // 启动扫描
    LaunchedEffect(Unit) {
        try {
            scannedFolders = viewModel.scanImageFolders()
        } catch (e: Exception) {
            scanError = e.message ?: "扫描失败"
        } finally {
            isScanning = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("扫描图片文件夹")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    isScanning -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("正在扫描设备上的图片文件夹...")
                        }
                    }
                    scanError != null -> {
                        Text(
                            "扫描失败: $scanError",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                    scannedFolders.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Outlined.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "未找到包含图片的文件夹",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        Text(
                            "找到 ${scannedFolders.size} 个包含图片的文件夹",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(scannedFolders) { folder ->
                                val isImported = folder.path in importedPaths
                                ScannedFolderItem(
                                    folder = folder,
                                    isImported = isImported,
                                    onImport = {
                                        viewModel.importScannedFolder(groupId, folder)
                                        importedPaths = importedPaths + folder.path
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}

@Composable
private fun ScannedFolderItem(
    folder: ScannedFolder,
    isImported: Boolean,
    onImport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isImported)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isImported) Icons.Filled.CheckCircle else Icons.Outlined.Folder,
                contentDescription = null,
                tint = if (isImported)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${folder.imageCount} 张图片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    folder.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isImported) {
                Text(
                    "已导入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                FilledTonalButton(
                    onClick = onImport,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("导入")
                }
            }
        }
    }
}
