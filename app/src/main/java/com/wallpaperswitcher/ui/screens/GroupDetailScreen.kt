package com.wallpaperswitcher.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.decode.VideoFrameDecoder
import androidx.core.content.ContextCompat
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.engine.ScannedFolder
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wallpaperswitcher.ui.theme.LocalAccentColor

// Grid thumbnails decode deliberately below the ~312px cell (3x density) so
// memory traffic and decode time stay tiny; slight softness is accepted in
// exchange for smooth flinging through thousands of media.
private const val THUMBNAIL_DECODE_SIZE = 176

private fun buildGridThumbnailRequest(context: Context, image: WallpaperImage, size: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(image.uri))
        .size(size, size)
        // No crossfade: during fast scrolling every newly composed cell would
        // otherwise start a fade animation on the UI thread.
        .crossfade(0)
        // Hardware bitmaps render straight from GPU memory while scrolling.
        // ARGB_8888 is requested explicitly because the loader's RGB_565 default
        // is incompatible with hardware bitmaps: Coil then had to keep these
        // thumbnails as SOFTWARE bitmaps, which are re-uploaded on every draw and
        // dominated the warm-cache scroll cost (p90 44ms measured).
        .allowHardware(true)
        .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
        .apply {
            if (image.mediaType == MediaTypes.VIDEO) {
                decoderFactory(VideoFrameDecoder.Factory())
            }
        }
        .build()

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
    val isLoadingImages by viewModel.isLoadingImages.collectAsStateWithLifecycle()
    // NOTE: scanProgress is deliberately NOT collected here any more. It changes
    // up to five times a second while a folder import runs (and once per 100
    // media), and reading it in this scope recomposed the whole screen -
    // including the LazyVerticalGrid content lambda - on every tick. The card
    // below collects it inside its own composable instead.

    var showAddDialog by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<WallpaperImage?>(null) }
    // Selection as a per-key snapshot map: each grid item observes only its
    // own key, so ticking one checkbox does not recompute/recompose every
    // visible item (the old Set<Long> + remember(selectedIds) pattern did).
    val selectedMap = remember { mutableStateMapOf<Long, Boolean>() }
    var isSelectionMode by remember { mutableStateOf(false) }
    // 失效媒体清理：非空时显示确认对话框；cleaningBroken 防止重复扫描。
    var brokenMedia by remember { mutableStateOf<List<WallpaperImage>?>(null) }
    var cleaningBroken by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    // Grid scroll state: used by the right-edge fast scroller. All images are
    // loaded on open (no paging), so no load-more trigger is needed.
    val gridState = rememberLazyGridState()

    // Refresh images when the screen becomes visible. After an activity or
    // process recreation (e.g. returning from the system live-wallpaper
    // picker), re-select the group so the grid is repopulated instead of
    // staying empty / falling back to the home screen. When the group was
    // already selected (normal navigation from Home), the load is already
    // in flight or the list is loaded - do NOT issue a second full query.
    LaunchedEffect(groupId) {
        if (viewModel.selectedGroupId.value != groupId) {
            viewModel.selectGroup(groupId)
        } else if (viewModel.loadedImages.value.isEmpty() && !viewModel.isLoadingImages.value) {
            viewModel.refreshImages()
        }
    }

    // Refresh the grid whenever the screen becomes visible again (returning
    // from another activity, or after the periodic folder auto-scan added
    // media while the user was elsewhere). loadAllImages() has a generation
    // guard, so a redundant refresh is cheap and safe.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, groupId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                viewModel.selectedGroupId.value == groupId
            ) {
                viewModel.refreshImages()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                onTargetChange = { target -> viewModel.setGroupTarget(currentGroup.id, target) },
                onRename = { newName -> viewModel.updateGroup(currentGroup.copy(name = newName)) },
                onDeleteClick = {
                    viewModel.deleteGroup(currentGroup)
                    onBack()
                }
            )
        }

        // 操作栏：默认显示“添加壁纸 / 批量操作 / 清理失效”，等宽、等高、紧凑
        // 内边距，避免窄屏上按钮文字被挤压；进入批量模式后整栏替换为选择工具
        // 栏（不再叠加两行），选择模式用“退出/全选/已选/删除”管理。
        if (!isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 主操作：添加壁纸
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Filled.Add, "添加", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("添加壁纸", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (images.isNotEmpty()) {
                    // 次要操作：批量操作
                    OutlinedButton(
                        onClick = {
                            selectedMap.clear()
                            isSelectionMode = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Filled.Checklist, "选择", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("批量操作", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    // 次要操作：清理失效
                    OutlinedButton(
                        onClick = {
                            if (!cleaningBroken) {
                                cleaningBroken = true
                                coroutineScope.launch {
                                    val broken = viewModel.scanBrokenMedia(groupId)
                                    cleaningBroken = false
                                    if (broken.isEmpty()) {
                                        Toast.makeText(context, "没有失效媒体", Toast.LENGTH_SHORT).show()
                                    } else {
                                        brokenMedia = broken
                                    }
                                }
                            }
                        },
                        enabled = !cleaningBroken,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Outlined.BrokenImage, "清理", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (cleaningBroken) "清理中…" else "清理失效",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            // 批量选择工具栏（替换操作栏，避免两行堆叠）
            // The selection count is read INSIDE SelectionToolbar: with the read
            // here, ticking one checkbox recomposed the entire screen (header +
            // grid lambda + progress card) for every tap.
            SelectionToolbar(
                selectedMap = selectedMap,
                totalCount = totalCount,
                groupId = groupId,
                viewModel = viewModel,
                isSelectionModeNow = { isSelectionMode },
                onExit = {
                    selectedMap.clear()
                    isSelectionMode = false
                },
                onDelete = {
                    viewModel.deleteImagesByIds(selectedMap.keys.toSet())
                    selectedMap.clear()
                    isSelectionMode = false
                }
            )
        }

        // 导入进度
        ScanProgressCard(viewModel)

        // 图片网格
        if (images.isEmpty()) {
            if (isLoadingImages || totalCount > 0) {
                // First page is still loading (or a refresh is in progress):
                // show a loading hint instead of flashing "还没有壁纸".
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "正在加载...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                EmptyImagesHint()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    // Adaptive columns: 3 on phones, more on tablets/landscape, so
                    // the grid uses the available width instead of fixed 3 cells.
                    columns = GridCells.Adaptive(104.dp),
                    state = gridState,
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
                        // Per-key snapshot read: only THIS item recomposes when its
                        // own selection changes. The callbacks are remembered per
                        // image id so unchanged items skip recomposition when the
                        // parent recomposes on another item's selection change.
                        val isImageSelected = selectedMap[image.id] ?: false
                        val onClick = remember(image.id, isSelectionMode) {
                            {
                                if (isSelectionMode) {
                                    if (selectedMap.containsKey(image.id)) {
                                        selectedMap.remove(image.id)
                                    } else {
                                        selectedMap[image.id] = true
                                    }
                                } else {
                                    // Tapping a picture sets it as the live
                                    // wallpaper (the system preview screen is
                                    // always shown for confirmation).
                                    viewModel.setAsLiveWallpaper(image)
                                }
                                Unit
                            }
                        }
                        val onDelete = remember(image.id) { { viewModel.deleteImage(image) } }
                        // The menu's "设为壁纸" opens the preview dialog, whose
                        // confirm button applies the media (engine switch when
                        // the live wallpaper runs, otherwise a static apply).
                        val onSetWallpaper = remember(image.id) { { previewImage = image } }
                        ImageGridItem(
                            image = image,
                            isSelected = isImageSelected,
                            selectionMode = isSelectionMode,
                            onClick = onClick,
                            onDelete = onDelete,
                            onSetWallpaper = onSetWallpaper
                        )
                    }
                }

                // Right-edge fast scroller: drag or tap to jump quickly
                // through the images of this group.
                GridFastScroller(
                    gridState = gridState,
                    itemCount = images.size,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }

    // Scanning the MediaStore folder list needs the read-media permission. It
    // is requested only when the user taps "扫描到的文件夹" — never at app
    // entry — and the folder dialog opens only after the grant.
    val folderScanPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        if (grantResults.values.all { it }) {
            showAddDialog = false
            showFolderPicker = true
        } else {
            Toast.makeText(
                context,
                "需要存储权限才能扫描设备文件夹，可在系统设置中开启后重试",
                Toast.LENGTH_LONG
            ).show()
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
                val missing = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.READ_MEDIA_IMAGES)
                        add(Manifest.permission.READ_MEDIA_VIDEO)
                    } else {
                        add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    showAddDialog = false
                    showFolderPicker = true
                } else {
                    folderScanPermissionLauncher.launch(missing.toTypedArray())
                }
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

    // 失效媒体清理确认
    brokenMedia?.let { broken ->
        AlertDialog(
            onDismissRequest = { brokenMedia = null },
            title = { Text("清理失效媒体") },
            text = {
                Text("发现 ${broken.size} 个无法读取的媒体（文件可能已被删除或移动）。确定从分组中删除吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteImages(broken)
                        Toast.makeText(context, "已删除 ${broken.size} 个失效媒体", Toast.LENGTH_SHORT).show()
                        brokenMedia = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { brokenMedia = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GroupInfoHeader(
    group: WallpaperGroup,
    imageCount: Int,
    loadedCount: Int,
    onTargetChange: (WallpaperTarget) -> Unit,
    onRename: (String) -> Unit,
    onDeleteClick: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer
                        )
                    )
                )
        ) {
            // 分组信息：标题 + 媒体数在左、操作按钮在右上，应用位置单独一行
            // （FlowRow：窄屏自动换行，不会挤掉按钮或裁掉 chip）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            group.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                buildString {
                                    append("$imageCount 个媒体")
                                    if (loadedCount in 1 until imageCount) {
                                        append("（已加载 $loadedCount）")
                                    }
                                    append(" · ")
                                    append(WallpaperTarget.fromName(group.target).shortLabel)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 重命名 / 删除：紧凑图标按钮，按在右上角
                    IconButton(
                        onClick = { showRenameDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            "重命名",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            "删除",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider(
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 应用位置（Paperize 双屏思路）：这个分组的图片只出现在桌面、
                // 只出现在锁屏，或者两边都出现。桌面和锁屏各自独立轮换，各自
                // 从"包含它"的分组里取图。
                val currentTarget = WallpaperTarget.fromName(group.target)
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Same height as the chips so the label sits on their text
                    // baseline instead of floating above it.
                    Box(
                        modifier = Modifier.height(30.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            "应用位置",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
                        )
                    }
                    WallpaperTarget.entries.forEach { option ->
                        FilterChip(
                            selected = option == currentTarget,
                            onClick = { onTargetChange(option) },
                            label = {
                                Text(
                                    option.shortLabel,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }
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

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName != group.name,
                    onClick = {
                        showRenameDialog = false
                        onRename(newName.trim())
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * Bulk-selection toolbar.
 *
 * Split out of [GroupDetailScreen] on purpose: this is the only place that reads
 * `selectedMap.size`, so ticking a checkbox (or "全选" filling the map with 5k
 * ids) recomposes just this row instead of the whole screen - the grid's item
 * lambda and its visible items used to be re-executed on every single tap.
 *
 * [isSelectionModeNow] is read lazily when the "全选" result arrives, so a late
 * list result cannot overwrite a selection the user already changed (e.g. they
 * left selection mode meanwhile).
 */
@Composable
private fun SelectionToolbar(
    selectedMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Boolean>,
    totalCount: Int,
    groupId: Long,
    viewModel: WallpaperViewModel,
    isSelectionModeNow: () -> Boolean,
    onExit: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // Snapshot reads: they subscribe THIS composable only.
    val selectedCount = selectedMap.size
    val isAllSelected = selectedCount == totalCount && totalCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            modifier = Modifier.size(40.dp),
            onClick = onExit
        ) {
            Icon(Icons.Filled.Close, "退出选择", modifier = Modifier.size(20.dp))
        }
        TextButton(
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            onClick = {
                if (isAllSelected) {
                    selectedMap.clear()
                } else {
                    scope.launch {
                        val ids = viewModel.getAllImageIds(groupId)
                        // Guard against a late result overwriting a selection the
                        // user already changed (e.g. they toggled out of selection
                        // mode meanwhile).
                        if (isSelectionModeNow()) {
                            selectedMap.clear()
                            ids.forEach { selectedMap[it] = true }
                        }
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
            "已选 $selectedCount/$totalCount",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalAccentColor.current,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (selectedCount > 0) {
            Button(
                onClick = onDelete,
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("删除所选")
            }
        }
    }
}

/**
 * Folder-import / scan progress card.
 *
 * Collects [WallpaperViewModel.scanProgress] in its own scope: the value changes
 * several times a second during a big import, and collecting it in
 * [GroupDetailScreen] made every tick recompose the whole screen.
 */
@Composable
private fun ScanProgressCard(viewModel: WallpaperViewModel) {
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    if (scanProgress.isEmpty()) return
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
    val context = LocalContext.current
    // Cache the image request + painters so scrolling (and theme-color
    // changes that recompose every grid item) never rebuild or re-fetch them.
            val imageRequest = remember(image.uri, image.mediaType, context) {
                buildGridThumbnailRequest(context, image, THUMBNAIL_DECODE_SIZE)
            }
    val placeholderPainter = remember { ColorPainter(Color(0xFFE0E0E0)) }
    val errorPainter = remember { ColorPainter(Color(0xFFBDBDBD)) }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .then(
                if (isSelected)
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
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

        // 选择模式：半透明遮罩让选中态更直观
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                        else
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.15f)
                    )
            )
        }

        // Media type chip for video/GIF
        if (MediaTypes.isMotion(image.mediaType)) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (image.mediaType == MediaTypes.VIDEO) Icons.Filled.Videocam else Icons.Filled.Gif,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    if (image.mediaType == MediaTypes.VIDEO) "视频" else "GIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
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
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // The menu is composed ONLY while it is open: a collapsed
                // DropdownMenu still builds its transition state + popup plumbing
                // for every visible cell, which the grid scroll paid for on every
                // frame.
                if (showMenu) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("设为壁纸") },
                            onClick = { showMenu = false; onSetWallpaper() },
                            leadingIcon = { Icon(Icons.Filled.Wallpaper, null) }
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
    // Sort modes: 0 = most media first, 1 = name A-Z, 2 = newest media first
    var sortMode by remember { mutableStateOf(0) }
    // Scroll state for the folder list: used by the right-edge fast scroller
    // so a long folder list (hundreds of folders) can be jumped through
    // without scrolling frame by frame.
    val listState = rememberLazyListState()

    // "重新扫描" state: re-runs the MediaStore scan (bypassing the ViewModel's
    // in-memory cache) so newly added folders show up without restarting.
    var scanning by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun startScan() {
        if (scanning || loading) return
        scanning = true
        coroutineScope.launch {
            val known = folders.orEmpty().map { it.path }.toSet()
            try {
                val result = withContext(Dispatchers.IO) { viewModel.rescanFolders() }
                folders = result
                // The scan used to be silent: the only sign of life was the
                // button label, so "did it work?" was impossible to answer when
                // the folder count had not changed. Report the outcome.
                val media = result.sumOf { it.totalCount }
                val added = result.count { it.path !in known }
                Toast.makeText(
                    context,
                    when {
                        result.isEmpty() ->
                            "扫描完成：未找到文件夹（请确认已授予照片/视频权限）"
                        added > 0 ->
                            "扫描完成：${result.size} 个文件夹 / $media 个媒体（新增 $added 个文件夹）"
                        else ->
                            "扫描完成：${result.size} 个文件夹 / $media 个媒体（没有新增）"
                    },
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                // MediaScanner.scanFolders() never throws (returns emptyList on
                // failure); this is just a safety net.
                Toast.makeText(context, "扫描失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                scanning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        folders = withContext(Dispatchers.IO) { viewModel.loadScannedFolders() }
        loading = false
    }

    val displayFolders = remember(folders, searchQuery, sortMode) {
        val all = folders.orEmpty()
        val filtered = if (searchQuery.isBlank()) all else all.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.path.contains(searchQuery, ignoreCase = true)
        }
        when (sortMode) {
            1 -> filtered.sortedBy { it.name.lowercase() }
            2 -> filtered.sortedByDescending { it.newestAddedAt }
            else -> filtered.sortedByDescending { it.totalCount }
        }
    }
    val allVisibleSelected = displayFolders.isNotEmpty() &&
        displayFolders.all { it.path in selectedPaths }

    // A plain Material3 AlertDialog keeps its content and its button row apart by
    // ~32dp of internal padding (24dp text slot + 8dp button slot) and leaves
    // another ~48dp under the buttons, which reads as a big empty band between
    // the folder list and 导入所选. This dialog therefore draws the standard
    // Material surface itself, so the actions sit right below the list.
    // 宽度显式给出（与系统对话框一致：min(92% 屏宽, 560dp)）。不要用
    // `fillMaxWidth(...) + widthIn(...)` 这种组合：两者一起用会产生互相矛盾的
    // 约束，对话框会变成没有内容的白板。
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val dialogWidth = minOf(560, (screenWidthDp * 0.92f).toInt()).dp
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        // 自己给宽度（和 Material3 AlertDialog 的上限一致）：用平台默认宽度时
        // 窗口是 WRAP_CONTENT，内部 fillMaxWidth() 在无界约束下测量失败，对话框
        // 会整个不显示。
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(dialogWidth)
        ) {
            // 高度上限 + 可压缩的列表：任何字号/屏幕下按钮都不会被内容挤出可视区
            // （列表用 weight(1f, fill=false) 让位，操作行始终保留）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (screenHeightDp * 0.92f).toInt().dp)
            ) {
                Text(
                    "选择文件夹",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp
                    )
                )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    placeholder = { Text("搜索文件夹名称或路径") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空")
                            }
                        }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (scanning) {
                            // Visible progress while the MediaStore scan runs.
                            "正在重新扫描…（请稍候）"
                        } else {
                            buildString {
                                append("共 ${displayFolders.size} 个文件夹")
                                if (selectedPaths.isNotEmpty()) {
                                    append(" · 已选 ${selectedPaths.size}")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (scanning) {
                            // Accent text on the surface: use the contrast-safe
                            // accent, not the raw user colour.
                            LocalAccentColor.current
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
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
                    // 「重新扫描」是动作而不是排序条件：单独放在工具行右侧，
                    // 不再和排序 chip 混排（既容易被误当成排序项，也会把那一行
                    // 挤到换行、把下面的列表压矮）。
                    TextButton(
                        onClick = { startScan() },
                        enabled = !scanning && !loading
                    ) {
                        Icon(
                            Icons.Outlined.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (scanning) "扫描中…" else "重新扫描")
                    }
                }
                // 排序项单行横向滚动：窄屏（手机上）也不会折成两行。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = sortMode == 0,
                        onClick = { sortMode = 0 },
                        label = { Text("媒体多优先") }
                    )
                    FilterChip(
                        selected = sortMode == 1,
                        onClick = { sortMode = 1 },
                        label = { Text("名称排序") }
                    )
                    FilterChip(
                        selected = sortMode == 2,
                        onClick = { sortMode = 2 },
                        label = { Text("时间排序") }
                    )
                }
                Divider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                when {
                    loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "正在扫描文件夹...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    displayFolders.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                if (folders.isNullOrEmpty()) "未扫描到包含图片或视频的文件夹"
                                else "没有匹配的文件夹",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> Box(
                        // 列表可以压缩：内容放不下时先缩短列表，而不是把下面的
                        // 操作行挤出屏幕。
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            // Keep the row-end checkboxes clear of the fast
                            // scroller's right-edge hit strip.
                            contentPadding = PaddingValues(end = 28.dp)
                        ) {
                            items(displayFolders, key = { it.path }) { folder ->
                                val isSelected = folder.path in selectedPaths
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .clickable {
                                            selectedPaths = if (isSelected) selectedPaths - folder.path
                                            else selectedPaths + folder.path
                                        }
                                        .heightIn(min = 52.dp)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val sample = folder.sampleUris.firstOrNull()
                                    if (sample != null) {
                                        // Cache the request per folder so
                                        // selection/filter recompositions never
                                        // rebuild it; video samples get a real
                                        // thumbnail.
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
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            folder.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        // 同名文件夹（例如两个 wstest）只能靠上级
                                        // 路径区分，所以第二行在数量后面补上位置。
                                        val location = remember(folder.path) {
                                            folder.path
                                                .removePrefix("/storage/emulated/0/")
                                                .trim('/')
                                        }
                                        Text(
                                            buildString {
                                                append(
                                                    "${folder.imageCount} 张图片 · " +
                                                        "${folder.videoCount} 个视频"
                                                )
                                                if (location.isNotEmpty() && location != folder.name) {
                                                    append(" · $location")
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    Checkbox(checked = isSelected, onCheckedChange = null)
                                }
                            }
                        }

                        // Right-edge fast scroller: drag or tap the strip to
                        // jump through a long folder list proportionally.
                        ListFastScroller(
                            listState = listState,
                            itemCount = displayFolders.size,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                }
            }
            // 操作行紧跟列表：8dp 间距 + 按钮自身的高度即可，不再有框架留下的
            // 「文本区/按钮区」双层留白。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    enabled = selectedPaths.isNotEmpty(),
                    onClick = {
                        val selected = folders.orEmpty().filter { it.path in selectedPaths }
                        viewModel.importScannedFolders(groupId, selected)
                        onDismiss()
                    }
                ) { Text("导入所选 (${selectedPaths.size})") }
            }
            }
        }
    }
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
                            if (image.mediaType == MediaTypes.VIDEO) {
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
                        MediaTypes.VIDEO -> "将此视频设置为壁纸"
                        MediaTypes.GIF -> "将此 GIF 设置为壁纸"
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

/**
 * Right-edge fast scroller for the image grid: drag the thumb (or tap the
 * strip) to jump to any position proportionally. Covers the currently loaded
 * items; scrolling to the bottom triggers the paging load-more, so the whole
 * group can be traversed by dragging. Only shown while the grid can scroll.
 */
@Composable
private fun GridFastScroller(
    gridState: LazyGridState,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    if (itemCount <= 0) return
    val showScroller by remember {
        derivedStateOf { gridState.canScrollForward || gridState.canScrollBackward }
    }
    if (!showScroller) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Grid-derived thumb position (first visible item / loaded items).
    val scrollFraction by remember {
        derivedStateOf {
            if (itemCount <= 1) 0f
            else (gridState.firstVisibleItemIndex / (itemCount - 1f)).coerceIn(0f, 1f)
        }
    }
    // While dragging the thumb follows the finger; otherwise it tracks the
    // scroll position.
    val fraction = if (dragging) dragFraction else scrollFraction
    // AwaitPointerEventScope is @RestrictsSuspension: suspend calls like
    // scrollToItem must run in a regular coroutine, hence this scope.
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight()
            .pointerInput(itemCount) {
                fun fractionOf(y: Float): Float =
                    (y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    var lastY = down.position.y
                    dragFraction = fractionOf(lastY)
                    coroutineScope.launch {
                        gridState.scrollToItem((dragFraction * (itemCount - 1)).toInt())
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val change = event.changes.firstOrNull() ?: break
                        val y = change.position.y
                        if (y != lastY) {
                            lastY = y
                            change.consume()
                            dragFraction = fractionOf(y)
                            coroutineScope.launch {
                                gridState.scrollToItem((dragFraction * (itemCount - 1)).toInt())
                            }
                        }
                    }
                    dragging = false
                }
            }
    ) {
        val trackHeight = maxHeight - FastScrollerThumbHeight
        // Thumb pill: subtle at rest, primary color while dragging.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = trackHeight * fraction)
                .width(FastScrollerThumbWidth)
                .height(FastScrollerThumbHeight)
                .clip(RoundedCornerShape(FastScrollerThumbWidth / 2))
                .background(
                    if (dragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                )
        )
    }
}

/**
 * Right-edge fast scroller for a LazyColumn: drag the thumb (or tap the
 * strip) to jump to any position proportionally. Same interaction as
 * GridFastScroller, for scrollable lists — used by the folder picker dialog,
 * where a device with many media folders can produce a long list. Only shown
 * while the list can actually scroll.
 */
@Composable
private fun ListFastScroller(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    if (itemCount <= 0) return
    val showScroller by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }
    if (!showScroller) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // List-derived thumb position (first visible item / total items).
    val scrollFraction by remember {
        derivedStateOf {
            if (itemCount <= 1) 0f
            else (listState.firstVisibleItemIndex / (itemCount - 1f)).coerceIn(0f, 1f)
        }
    }
    // While dragging the thumb follows the finger; otherwise it tracks the
    // scroll position.
    val fraction = if (dragging) dragFraction else scrollFraction
    // AwaitPointerEventScope is @RestrictsSuspension: suspend calls like
    // scrollToItem must run in a regular coroutine, hence this scope.
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight()
            .pointerInput(itemCount) {
                fun fractionOf(y: Float): Float =
                    (y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    var lastY = down.position.y
                    dragFraction = fractionOf(lastY)
                    coroutineScope.launch {
                        listState.scrollToItem((dragFraction * (itemCount - 1)).toInt())
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val change = event.changes.firstOrNull() ?: break
                        val y = change.position.y
                        if (y != lastY) {
                            lastY = y
                            change.consume()
                            dragFraction = fractionOf(y)
                            coroutineScope.launch {
                                listState.scrollToItem((dragFraction * (itemCount - 1)).toInt())
                            }
                        }
                    }
                    dragging = false
                }
            }
    ) {
        val trackHeight = maxHeight - FastScrollerThumbHeight
        // Thumb pill: subtle at rest, primary color while dragging.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = trackHeight * fraction)
                .width(FastScrollerThumbWidth)
                .height(FastScrollerThumbHeight)
                .clip(RoundedCornerShape(FastScrollerThumbWidth / 2))
                .background(
                    if (dragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                )
        )
    }
}

private val FastScrollerThumbWidth = 5.dp
private val FastScrollerThumbHeight = 48.dp
