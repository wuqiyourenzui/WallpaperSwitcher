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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
    val images by viewModel.loadedImages.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalImageCount.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<WallpaperImage?>(null) }
    var selectedImages by remember { mutableStateOf(setOf<Long>()) }
    var isSelectionMode by remember { mutableStateOf(false) }

    // Refresh images when screen becomes visible
    LaunchedEffect(groupId) {
        viewModel.refreshImages()
    }

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

    val currentGroup = group

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
                itemsIndexed(images, key = { _, image -> image.id }) { index, image ->
                    // Load more when near the end
                    if (index >= images.size - 12 && images.size < totalCount && !isLoadingMore) {
                        LaunchedEffect(Unit) { viewModel.loadImages(groupId) }
                    }
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
                        onSetWallpaper = { previewImage = image }
                    )
                }
                // Loading indicator at the bottom
                if (isLoadingMore) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }

    // Add wallpaper dialog
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
            }
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
                    "$imageCount images${if (loadedCount < imageCount) " (loaded $loadedCount)" else ""}",
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
    onAddFolder: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Wallpaper") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onAddSingle,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Image, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Select Image", modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onAddMultiple,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Select Multiple", modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onAddFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Add from Folder", modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
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
        title = { Text("Set as Wallpaper") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Image preview
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Uri.parse(image.uri))
                        .crossfade(true)
                        .build(),
                    contentDescription = image.displayName,
                    contentScale = ContentScale.Fit,
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
                    "This will set this image as your wallpaper.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
