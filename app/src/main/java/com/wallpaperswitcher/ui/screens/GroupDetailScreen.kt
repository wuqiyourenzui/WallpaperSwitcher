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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.decode.VideoFrameDecoder
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.engine.ScannedFolder
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    viewModel: WallpaperViewModel,
    groupId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val group by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val images by viewModel.loadedImages.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalImageCount.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<WallpaperImage?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Derive load-more state to avoid recomposition on every scroll
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = images.size - 1
            lastVisibleItem >= 0 && lastVisibleItem >= images.size - 12 && images.size < totalCount && !isLoadingMore
        }
    }

    // Refresh images when the screen becomes visible. After an activity or
    // process recreation (e.g. returning from the system live-wallpaper
    // picker), re-select the group so the grid is repopulated instead of
    // staying empty / falling back to the home screen.
    LaunchedEffect(groupId) {
        if (viewModel.selectedGroupId.value != groupId) {
            viewModel.selectGroup(groupId)
        } else {
            viewModel.refreshImages()
        }
    }

    val currentGroup = group
    // Groups are mixed: allow both images and videos to be added.
    val mimeTypes = arrayOf("image/*", "video/*")

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

    // Single image picker
    val singleImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.addImage(groupId, it, it.lastPathSegment ?: "untitled")
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
    }

    // Folder picker (system)
    // postDelayed avoids crash on MIUI/HyperOS where ActivityResult callback
    // fires before Activity is fully ready
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            viewModel.addFolder(groupId, uri)
        }, 100)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 分组信息头部
        if (currentGroup != null) {
            GroupInfoHeader(
                group = currentGroup,
                imageCount = totalCount,
                loadedCount = images.size,
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
                        if (!isSelectionMode) selectedIds = emptySet()
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
        AnimatedVisibility(visible = isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 全选/取消全选
                val isAllSelected = selectedIds.size == totalCount && totalCount > 0
                TextButton(
                    onClick = {
                        if (isAllSelected) {
                            selectedIds = emptySet()
                        } else {
                            coroutineScope.launch {
                                val ids = viewModel.getAllImageIds(groupId)
                                selectedIds = ids.toSet()
                            }
                        }
                    }
                ) {
                    Icon(
                        if (isAllSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isAllSelected) "取消全选" else "全选")
                }

                Text(
                    "已选 ${selectedIds.size} 张",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                Spacer(modifier = Modifier.weight(1f))
                if (selectedIds.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.deleteImagesByIds(selectedIds)
                            selectedIds = emptySet()
                            isSelectionMode = false
                        }
                    ) {
                        Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除所选")
                    }
                }
            }
        }

        // 导入进度
        if (scanProgress.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Static icon instead of CircularProgressIndicator: the
                    // bundled animation-core version lacks the method M3's
                    // progress indicator needs, which crashed with
                    // NoSuchMethodError.
                    Icon(
                        Icons.Outlined.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        scanProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        // 图片网格
        if (images.isEmpty()) {
            EmptyImagesHint()
        } else {
            LazyVerticalGrid(
                // Adaptive columns: 3 on phones, more on tablets/landscape, so
                // the grid uses the available width instead of fixed 3 cells.
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    images,
                    key = { _, image -> image.id },
                    contentType = { _, _ -> "media" }
                ) { _, image ->
                    // Stable selection check - only recompose when THIS image's selection changes
                    val isImageSelected = remember(selectedIds) { image.id in selectedIds }
                    ImageGridItem(
                        image = image,
                        isSelected = isImageSelected,
                        selectionMode = isSelectionMode,
                        onClick = {
                            if (isSelectionMode) {
                                selectedIds = if (image.id in selectedIds)
                                    selectedIds - image.id
                                else
                                    selectedIds + image.id
                            }
                        },
                        onDelete = { viewModel.deleteImage(image) },
                        onSetWallpaper = { previewImage = image },
                        onSetLiveWallpaper = { viewModel.setAsLiveWallpaper(image) }
                    )
                }
            }

            // Load more trigger - outside grid to avoid recomposition
            LaunchedEffect(shouldLoadMore) {
                if (shouldLoadMore) viewModel.loadImages(groupId)
            }
        }
    }

    // Add wallpaper dialog
    if (showAddDialog) {
        AddWallpaperDialog(
            onDismiss = { showAddDialog = false },
            onAddSingle = {
                showAddDialog = false
                singleImagePicker.launch(mimeTypes)
            },
            onAddMultiple = {
                showAddDialog = false
                imagePickerLauncher.launch(mimeTypes)
            },
            onAddFolder = {
                showAddDialog = false
                folderPickerLauncher.launch(null)
            },
            onScanFolders = {
                showAddDialog = false
                showFolderPicker = true
            }
        )
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            viewModel = viewModel,
            groupId = groupId,
            onDismiss = { showFolderPicker = false }
        )
    }

    // Wallpaper preview dialog
    previewImage?.let { image ->
        WallpaperPreviewDialog(
            image = image,
            onDismiss = { previewImage = null },
            onConfirm = {
                viewModel.setImageAsWallpaper(image)
                previewImage = null
            }
        )
    }
}

@Composable
private fun GroupInfoHeader(
    group: WallpaperGroup,
    imageCount: Int,
    loadedCount: Int,
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
                    "$imageCount 个媒体${if (loadedCount < imageCount) " (已加载 $loadedCount)" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
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
    onSetWallpaper: () -> Unit,
    onSetLiveWallpaper: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Cache the image request + painters so scrolling (and theme-color
    // changes that recompose every grid item) never rebuild or re-fetch them.
    val imageRequest = remember(image.uri, image.mediaType, context) {
        ImageRequest.Builder(context)
            .data(Uri.parse(image.uri))
            // Lower-resolution thumbnails (300px) decode faster, use less
            // memory and upload to the GPU quicker while still looking sharp
            // in a 3-column grid on phone screens.
            .size(300, 300)
            // No crossfade: during fast scrolling every newly composed cell
            // would otherwise start a 200ms fade animation on the UI thread.
            .crossfade(0)
            // Hardware bitmaps are rendered directly by the GPU and are much
            // cheaper than software bitmaps when scrolling. Thumbnails are
            // only displayed, never read back into Canvas.
            .allowHardware(true)
            .apply {
                // Use video frame decoder for video/GIF thumbnails
                if (image.mediaType == "VIDEO") {
                    decoderFactory(VideoFrameDecoder.Factory())
                }
            }
            .build()
    }
    val placeholderPainter = remember { ColorPainter(Color(0xFFE0E0E0)) }
    val errorPainter = remember { ColorPainter(Color(0xFFBDBDBD)) }

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
            model = imageRequest,
            contentDescription = image.displayName,
            contentScale = ContentScale.Crop,
            placeholder = placeholderPainter,
            error = errorPainter,
            modifier = Modifier.fillMaxSize()
        )

        // Media type indicator for video/GIF
        if (image.mediaType == "VIDEO" || image.mediaType == "GIF") {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (image.mediaType == "VIDEO") Icons.Filled.Videocam else Icons.Filled.Gif,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

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
                        text = { Text("设为壁纸") },
                        onClick = { showMenu = false; onSetWallpaper() },
                        leadingIcon = { Icon(Icons.Filled.Wallpaper, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("设为动态壁纸") },
                        onClick = { showMenu = false; onSetLiveWallpaper() },
                        leadingIcon = { Icon(Icons.Filled.LiveTv, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
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
                    Text("选择单张图片/视频", modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onAddMultiple,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("选择多张图片/视频", modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onScanFolders,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("扫描到的文件夹（可多选）", modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onAddFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("从文件夹添加", modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Lists device folders that contain images/videos (scanned via MediaStore) and
 * lets the user select several at once to import into the group.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FolderPickerDialog(
    viewModel: WallpaperViewModel,
    groupId: Long,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var folders by remember { mutableStateOf<List<ScannedFolder>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var searchQuery by remember { mutableStateOf("") }
    // 0 = most media first, 1 = name A-Z
    var sortByName by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        folders = withContext(Dispatchers.IO) { viewModel.loadScannedFolders() }
        loading = false
    }

    val displayFolders = remember(folders, searchQuery, sortByName) {
        val all = folders.orEmpty()
        val filtered = if (searchQuery.isBlank()) all else all.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.path.contains(searchQuery, ignoreCase = true)
        }
        if (sortByName) {
            filtered.sortedBy { it.name.lowercase() }
        } else {
            filtered.sortedByDescending { it.totalCount }
        }
    }
    val allVisibleSelected = displayFolders.isNotEmpty() &&
        displayFolders.all { it.path in selectedPaths }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择文件夹（搜索/多选/排序）") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索文件夹名称或路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空")
                            }
                        }
                    }
                )
                // Count + select-all on their own line, sort chips below:
                // previously everything shared one Row, which squeezed the
                // "共 N 个文件夹" text on narrow screens (it could clip or
                // overflow next to the chips and the select-all button).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "共 ${displayFolders.size} 个文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (displayFolders.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedPaths = if (allVisibleSelected) {
                                    selectedPaths - displayFolders.map { it.path }.toSet()
                                } else {
                                    selectedPaths + displayFolders.map { it.path }
                                }
                            }
                        ) {
                            Text(if (allVisibleSelected) "取消全选" else "全选")
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = !sortByName,
                        onClick = { sortByName = false },
                        label = { Text("媒体多优先") }
                    )
                    FilterChip(
                        selected = sortByName,
                        onClick = { sortByName = true },
                        label = { Text("名称排序") }
                    )
                }
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("正在扫描文件夹...")
                    }
                    displayFolders.isEmpty() -> Text(
                        if (folders.isNullOrEmpty()) "未扫描到包含图片或视频的文件夹"
                        else "没有匹配的文件夹"
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(displayFolders, key = { it.path }) { folder ->
                            val isSelected = folder.path in selectedPaths
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedPaths = if (isSelected) selectedPaths - folder.path
                                        else selectedPaths + folder.path
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val sample = folder.sampleUris.firstOrNull()
                                if (sample != null) {
                                    // Cache the request per folder so selection /
                                    // filter recompositions never rebuild it, and
                                    // let video samples show a real thumbnail.
                                    val sampleRequest = remember(sample, context) {
                                        ImageRequest.Builder(context)
                                            .data(Uri.parse(sample))
                                            .size(96, 96)
                                            .crossfade(0)
                                            .allowHardware(true)
                                            .apply {
                                                decoderFactory(VideoFrameDecoder.Factory())
                                            }
                                            .build()
                                    }
                                    AsyncImage(
                                        model = sampleRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    Text(
                                        "${folder.imageCount} 张图片 / ${folder.videoCount} 个视频",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(checked = isSelected, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedPaths.isNotEmpty(),
                onClick = {
                    val selected = folders.orEmpty().filter { it.path in selectedPaths }
                    viewModel.importScannedFolders(groupId, selected)
                    onDismiss()
                }
            ) { Text("导入所选 (${selectedPaths.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * Wallpaper preview dialog.
 * Shows the image and lets the user confirm before setting as wallpaper.
 */
@Composable
fun WallpaperPreviewDialog(
    image: WallpaperImage,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设为壁纸") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Image preview
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Uri.parse(image.uri))
                        .size(800, 800)
                        .crossfade(200)
                        .allowHardware(false)
                        .apply {
                            if (image.mediaType == "VIDEO") {
                                decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                            }
                        }
                        .build(),
                    contentDescription = image.displayName,
                    contentScale = ContentScale.Fit,
                    placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFE0E0E0)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .clip(RoundedCornerShape(12.dp))
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    image.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    when (image.mediaType) {
                        "VIDEO" -> "将此视频设置为壁纸"
                        "GIF" -> "将此 GIF 设置为壁纸"
                        else -> "将此图片设置为壁纸"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
