package com.wallpaperswitcher.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.engine.ScannedFolder
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wallpaperswitcher.ui.theme.HiEmptyState
import com.wallpaperswitcher.ui.theme.HiLoadingHint
import com.wallpaperswitcher.ui.theme.HiLoadingState
import com.wallpaperswitcher.ui.theme.HiMotion
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
    onBack: () -> Unit,
    /** 进「大图浏览」：九宫格适合整理，那个页面适合一张一张挑。 */
    onBrowse: () -> Unit = {},
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
                uri.lastPathSegment ?: context.getString(R.string.item_untitled)
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
            viewModel.addImage(
                groupId,
                it,
                it.lastPathSegment ?: context.getString(R.string.item_untitled)
            )
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
                onIntervalChange = { ms -> viewModel.setGroupInterval(currentGroup.id, ms) },
                onWindowChange = { from, to ->
                    viewModel.setGroupActiveWindow(currentGroup.id, from, to)
                },
                onDaysChange = { mask ->
                    viewModel.setGroupActiveDays(currentGroup.id, mask)
                },
                onMediaChange = { filter ->
                    viewModel.setGroupMediaFilter(currentGroup.id, filter)
                },
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
            if (images.isNotEmpty()) {
                // 「大图浏览」入口：网格适合整理，一张一张挑要走这个。
                // 放在操作栏上方而不是塞进那一行 —— 那一行三个按钮已经排满，
                // 再加一个会把四语言的长标签挤成省略号（见下面的注释）。
                FilledTonalButton(
                    onClick = onBrowse,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                        .heightIn(min = 44.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        Icons.Outlined.PlayCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.browse_title))
                }
            }
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
                        shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.action_add_wallpaper),
                        // Two lines instead of an ellipsis: on a 400dp phone the
                        // English/Russian/Korean labels ("Add wallpapers",
                        // "Добавить обои", "배경 화면 추가") need ~280px while the
                        // button leaves ~230px for text - measured on the
                        // tablet, all three buttons rendered "Добавить об…".
                        // The row still uses one line per button; only the label
                        // wraps, and only in the languages that need it.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (images.isNotEmpty()) {
                    // 次要操作：批量操作
                    FilledTonalButton(
                        onClick = {
                            selectedMap.clear()
                            isSelectionMode = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Checklist,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.action_batch_ops),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 次要操作：清理失效
                    FilledTonalButton(
                        onClick = {
                            if (!cleaningBroken) {
                                cleaningBroken = true
                                coroutineScope.launch {
                                    val broken = viewModel.scanBrokenMedia(groupId)
                                    cleaningBroken = false
                                    if (broken.isEmpty()) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.toast_no_broken_media),
                                            Toast.LENGTH_SHORT
                                        ).show()
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
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.BrokenImage,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(
                                if (cleaningBroken) R.string.action_cleaning
                                else R.string.action_clean_broken
                            ),
                            maxLines = 2,
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
                HiLoadingState(
                    text = stringResource(R.string.state_loading),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp)
                )
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
                            onSetWallpaper = onSetWallpaper,
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
        // Any read-media grant is enough to list folders. Requiring ALL of them made
        // "扫描到的文件夹" unusable when the user granted photos but not videos, even
        // though the scan works with either - and Android 14's partial "选择照片"
        // flow grants only the image permission, so that path never opened the picker.
        if (grantResults.values.any { it }) {
            showAddDialog = false
            showFolderPicker = true
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.permission_scan_denied),
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
                val needed = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.READ_MEDIA_IMAGES)
                        add(Manifest.permission.READ_MEDIA_VIDEO)
                        // Android 14+ "选择部分照片": requesting it (and having it
                        // declared) is what keeps the partial grant usable - see
                        // the manifest's comment and MediaProbe.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        }
                    } else {
                        add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
                // ANY of them granted is enough to scan: that is the same rule
                // MediaProbe and the permission callback use, so a user who
                // granted only "选择部分照片" is not asked again.
                val anyGranted = needed.any {
                    ContextCompat.checkSelfPermission(context, it) ==
                        PackageManager.PERMISSION_GRANTED
                }
                val missing = needed.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty() || anyGranted) {
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
            title = { Text(stringResource(R.string.dialog_clean_broken_title)) },
            text = {
                Text(stringResource(R.string.dialog_clean_broken_message, broken.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteImages(broken)
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_broken_deleted, broken.size),
                            Toast.LENGTH_SHORT
                        ).show()
                        brokenMedia = null
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { brokenMedia = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
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
    onIntervalChange: (Long) -> Unit,
    onWindowChange: (Int, Int) -> Unit,
    onDaysChange: (Int) -> Unit,
    onMediaChange: (String) -> Unit,
    onRename: (String) -> Unit,
    onDeleteClick: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(com.wallpaperswitcher.ui.theme.HiDims.CardCorner),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                                pluralStringResource(
                                    R.plurals.group_image_count,
                                    imageCount,
                                    imageCount
                                ) +
                                    if (loadedCount in 1 until imageCount) {
                                        stringResource(R.string.group_loaded_count, loadedCount)
                                    } else {
                                        ""
                                    } +
                                    " · " +
                                    stringResource(
                                        WallpaperTarget.fromName(group.target).shortLabelRes
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 重命名 / 删除：紧凑图标按钮，按在右上角
                    // 48dp touch targets (audit a11y): the icons stay 20dp, the
                    // tappable box grows to the Material minimum.
                    IconButton(
                        onClick = { showRenameDialog = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            stringResource(R.string.action_rename),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            stringResource(R.string.action_delete),
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
                            stringResource(R.string.label_target),
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
                                    stringResource(option.shortLabelRes),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                // 分组独立节奏：间隔 / 模式 / 时间规则。每一项都有"跟随全局"
                // 状态（存的是 0 / "" / -1），所以默认轮换完全不变。
                GroupRhythmSection(
                    group = group,
                    onIntervalChange = onIntervalChange,
                    onWindowChange = onWindowChange,
                    onDaysChange = onDaysChange,
                    onMediaChange = onMediaChange,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.dialog_delete_group_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_group_message, group.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteClick()
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.dialog_rename_group_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.label_group_name)) },
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
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
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
@OptIn(ExperimentalLayoutApi::class)
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
    // 两行布局（与首页分组多选一致）：「退出 / 全选 / 已选 n/m」在第一行，
    // 动作按钮固定换到第二行。之前所有控件挤在一行，俄语下计数被压到 58px
    // （显示成"…"）；两行后每个控件都有足够宽度。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                onClick = onExit
            ) {
                Icon(
                    Icons.Filled.Close,
                    stringResource(R.string.cd_exit_selection),
                    modifier = Modifier.size(20.dp)
                )
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
                Text(
                    stringResource(if (isAllSelected) R.string.selection_none else R.string.selection_all)
                )
            }
        Text(
            stringResource(R.string.selection_count, selectedCount, totalCount),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalAccentColor.current,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        }
        if (selectedCount > 0) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onDelete,
                    modifier = Modifier.heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete_selected))
                }
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
    // 扫描进度卡的进出也做成展开收起：没有进度时完全不占位，出现/结束时
    // 不再整块闪现。
    AnimatedVisibility(
        visible = scanProgress.isNotEmpty(),
        enter = fadeIn(HiMotion.enter()) + expandVertically(HiMotion.enter()),
        exit = fadeOut(HiMotion.exit()) + shrinkVertically(HiMotion.exit()),
    ) {
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
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(
                if (isSelected)
                    Modifier.border(
                        2.5.dp,
                        LocalAccentColor.current,
                        RoundedCornerShape(12.dp),
                    )
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
                    if (image.mediaType == MediaTypes.VIDEO) {
                        stringResource(R.string.media_type_video)
                    } else {
                        "GIF"
                    },
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
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_more),
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
                        // Restored after the settings rewrite dropped it: the
                        // preview dialog (and this item) is the only way to set
                        // a *static* wallpaper from the grid - tapping the cell
                        // intentionally goes through the system live-wallpaper
                        // screen instead.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_set_as_wallpaper)) },
                            onClick = { showMenu = false; onSetWallpaper() },
                            leadingIcon = { Icon(Icons.Filled.Wallpaper, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
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
    // 与首页、订阅共用同一套容器式空态（原来是 64dp 裸图标 + 更淡的文字）。
    HiEmptyState(
        title = stringResource(R.string.empty_images_title),
        hint = stringResource(R.string.empty_images_hint),
        icon = Icons.Outlined.AddPhotoAlternate,
        modifier = Modifier.padding(vertical = 60.dp),
    )
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
        title = {
            Text(
                stringResource(R.string.dialog_add_wallpaper_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            // Miuix 式选项行：强调色图标 + 文字 + 行尾箭头，整行可点。
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AddWallpaperOption(
                    icon = Icons.Outlined.Image,
                    label = stringResource(R.string.action_pick_single),
                    onClick = onAddSingle,
                )
                AddWallpaperOption(
                    icon = Icons.Outlined.PhotoLibrary,
                    label = stringResource(R.string.action_pick_multiple),
                    onClick = onAddMultiple,
                )
                AddWallpaperOption(
                    icon = Icons.Outlined.FolderOpen,
                    label = stringResource(R.string.action_scan_folders),
                    onClick = onScanFolders,
                )
                AddWallpaperOption(
                    icon = Icons.Outlined.Folder,
                    label = stringResource(R.string.action_add_from_folder),
                    onClick = onAddFolder,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun AddWallpaperOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LocalAccentColor.current,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
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
                            context.getString(R.string.toast_scan_empty)
                        added > 0 ->
                            context.getString(
                                R.string.toast_scan_added,
                                result.size,
                                media,
                                added
                            )
                        else ->
                            context.getString(
                                R.string.toast_scan_no_change,
                                result.size,
                                media
                            )
                    },
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                // MediaScanner.scanFolders() never throws (returns emptyList on
                // failure); this is just a safety net.
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_scan_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
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
                    stringResource(R.string.title_select_folders),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp
                    )
                )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Miuix 式搜索框：填充底、无描边、14dp 圆角（原来是 Material
                // 描边输入框，和新的卡片语言不一致）。
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    placeholder = { Text(stringResource(R.string.hint_search_folder)) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_clear)
                                )
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = com.wallpaperswitcher.ui.theme.hiCardColor(),
                        unfocusedContainerColor = com.wallpaperswitcher.ui.theme.hiCardColor(),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    )
                )
                // FlowRow: "共 N 个文件夹 · 已选 M" + 全选 + 重新扫描 do not fit
                // on one 400dp line in Russian/Spanish ("Проверить снова"),
                // and the count used to lose that fight - it wrapped into a tall
                // single-character column. Now the buttons drop to a second line.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        if (scanning) {
                            // Visible progress while the MediaStore scan runs.
                            stringResource(R.string.state_rescanning)
                        } else {
                            pluralStringResource(
                                R.plurals.folder_count,
                                displayFolders.size,
                                displayFolders.size
                            ) +
                                if (selectedPaths.isNotEmpty()) {
                                    stringResource(
                                        R.string.folder_selected_count,
                                        selectedPaths.size
                                    )
                                } else {
                                    ""
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
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    if (displayFolders.isNotEmpty()) {
                        TextButton(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            onClick = {
                                selectedPaths = if (allVisibleSelected) {
                                    selectedPaths - displayFolders.map { it.path }.toSet()
                                } else {
                                    selectedPaths + displayFolders.map { it.path }
                                }
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (allVisibleSelected) R.string.selection_none
                                    else R.string.selection_all
                                )
                            )
                        }
                    }
                    // 「重新扫描」是动作而不是排序条件：单独放在工具行右侧，
                    // 不再和排序 chip 混排（既容易被误当成排序项，也会把那一行
                    // 挤到换行、把下面的列表压矮）。
                    TextButton(
                        onClick = { startScan() },
                        enabled = !scanning && !loading,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Icon(
                            Icons.Outlined.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(
                                if (scanning) R.string.action_scanning else R.string.action_rescan
                            )
                        )
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
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(stringResource(R.string.sort_media_first)) }
                    )
                    FilterChip(
                        selected = sortMode == 1,
                        onClick = { sortMode = 1 },
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(stringResource(R.string.sort_name)) }
                    )
                    FilterChip(
                        selected = sortMode == 2,
                        onClick = { sortMode = 2 },
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(stringResource(R.string.sort_time)) }
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
                        HiLoadingHint(
                            text = stringResource(R.string.state_scanning_folders),
                            icon = Icons.Outlined.Sync,
                            iconSize = 28.dp,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    displayFolders.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 对话框里的紧凑版容器式空态（与首页/订阅同一套观感）。
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(com.wallpaperswitcher.ui.theme.hiCardColor()),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp),
                                    tint = LocalAccentColor.current,
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                stringResource(
                                    if (folders.isNullOrEmpty()) R.string.folder_empty_scan
                                    else R.string.folder_empty_filter
                                ),
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
                            // 不钉高度：列表用满对话框剩下的空间（对话框本身有 92%
                            // 屏高的上限）。这里原来写死 `heightIn(max = 340.dp)`，
                            // 而右侧 ListFastScroller 是 fillMaxHeight —— 长列表时
                            // 滚动条把外层 Box 撑高、列表却停在 340dp，列表和操作行
                            // 之间就空出一大片（用户截图反馈）。
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            // Keep the row-end checkboxes clear of the fast
                            // scroller's right-edge hit strip.
                            contentPadding = PaddingValues(end = 28.dp, bottom = 8.dp)
                        ) {
                            items(displayFolders, key = { it.path }) { folder ->
                                val isSelected = folder.path in selectedPaths
                                // 选中底色跟随主题强调色，并用颜色过渡代替硬切。
                                val rowBackground by animateColorAsState(
                                    targetValue = if (isSelected) {
                                        LocalAccentColor.current.copy(alpha = 0.12f)
                                    } else {
                                        Color.Transparent
                                    },
                                    animationSpec = HiMotion.standard(),
                                    label = "folderRowBackground",
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(rowBackground)
                                        .clickable {
                                            selectedPaths = if (isSelected) selectedPaths - folder.path
                                            else selectedPaths + folder.path
                                        }
                                        .heightIn(min = 56.dp)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
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
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(10.dp))
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
                                            stringResource(
                                                R.string.folder_media_summary,
                                                folder.imageCount,
                                                folder.videoCount
                                            ) +
                                                if (location.isNotEmpty() && location != folder.name) {
                                                    " · $location"
                                                } else {
                                                    ""
                                                },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = null,
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = LocalAccentColor.current
                                        )
                                    )
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
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    enabled = selectedPaths.isNotEmpty(),
                    onClick = {
                        val selected = folders.orEmpty().filter { it.path in selectedPaths }
                        viewModel.importScannedFolders(groupId, selected)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.action_import_selected, selectedPaths.size))
                }
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
        title = { Text(stringResource(R.string.dialog_set_wallpaper_title)) },
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
                    stringResource(
                        when (image.mediaType) {
                            MediaTypes.VIDEO -> R.string.set_wallpaper_video
                            MediaTypes.GIF -> R.string.set_wallpaper_gif
                            else -> R.string.set_wallpaper_image
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
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
