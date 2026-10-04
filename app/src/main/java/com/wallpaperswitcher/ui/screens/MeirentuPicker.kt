package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperswitcher.R
import com.wallpaperswitcher.engine.OnlineFetcher
import com.wallpaperswitcher.engine.OnlineSourceRules
import com.wallpaperswitcher.ui.theme.HiLoadingState
import com.wallpaperswitcher.ui.theme.LocalAccentColor
import com.wallpaperswitcher.ui.theme.hiCardColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 美人图 picker: browse the listing's albums, open one and tick the exact
 * full-size images to use. The ticked URLs are stored on the source
 * (`selectedImages`) and the sync then downloads exactly those instead of
 * scraping the newest albums.
 *
 * Thumbnails are the full-size files (the CDN has no separate thumbnail for
 * them); they are loaded with the Referer header the CDN requires and Coil
 * downsamples them for the grid.
 */
@Composable
fun MeirentuPickerDialog(
    listingUrl: String,
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val referer = remember(listingUrl) { OnlineSourceRules.originOf(listingUrl) }

    var albums by remember { mutableStateOf<List<OnlineSourceRules.MeirentuAlbum>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var openAlbum by remember { mutableStateOf<OnlineSourceRules.MeirentuAlbum?>(null) }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(initialSelection.toSet()) }

    LaunchedEffect(listingUrl) {
        try {
            albums = withContext(Dispatchers.IO) {
                OnlineFetcher.listMeirentuAlbums(listingUrl)
            }
        } catch (t: Throwable) {
            error = OnlineFetcher.classify(t)
        }
    }

    LaunchedEffect(openAlbum) {
        val album = openAlbum ?: return@LaunchedEffect
        images = emptyList()
        page = 1
        noMore = false
        loadingMore = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                OnlineFetcher.listMeirentuAlbumImages(listingUrl, album.id, 1)
            }
            images = loaded
            if (loaded.isEmpty()) noMore = true
        } catch (t: Throwable) {
            error = OnlineFetcher.classify(t)
        }
        loadingMore = false
    }

    fun toggle(url: String) {
        selection = if (url in selection) selection - url else selection + url
    }

    fun loadMore() {
        val album = openAlbum ?: return
        loadingMore = true
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    OnlineFetcher.listMeirentuAlbumImages(listingUrl, album.id, page + 1)
                }
                if (loaded.isEmpty()) {
                    noMore = true
                } else {
                    page += 1
                    images = (images + loaded).distinct()
                }
            } catch (_: Throwable) {
                noMore = true
            }
            loadingMore = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Miuix 式顶栏：圆形返回/关闭按钮 + 左对齐标题 + 「已选」药丸
                // （点药丸即清空）+ 主操作按钮。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (openAlbum != null) openAlbum = null else onDismiss() }
                    ) {
                        Icon(
                            if (openAlbum != null) Icons.AutoMirrored.Filled.ArrowBack
                            else Icons.Filled.Close,
                            contentDescription = stringResource(
                                if (openAlbum != null) R.string.action_back
                                else R.string.action_cancel
                            ),
                        )
                    }
                    Text(
                        openAlbum?.label?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.online_picker_title),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (selection.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(LocalAccentColor.current.copy(alpha = 0.12f))
                                .clickable { selection = emptySet() }
                                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.online_meirentu_selected, selection.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = LocalAccentColor.current,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.online_meirentu_clear),
                                tint = LocalAccentColor.current,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    FilledTonalButton(
                        onClick = { onConfirm(selection.toList()) },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.online_picker_done))
                    }
                }

                when {
                    error != null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.online_picker_failed, onlineErrorText(error!!)),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }

                    openAlbum == null -> {
                        val list = albums
                        if (list == null) {
                            // Unified loading state (static icon + text; no
                            // spinner — see HiLoadingHint).
                            HiLoadingState(
                                text = stringResource(R.string.online_picker_loading),
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // 相册列表：Miuix 卡片行（无分割线，12dp 间距）+ 右侧箭头。
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(list, key = { it.id }) { album ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(hiCardColor())
                                            .clickable { openAlbum = album }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AlbumThumb(album.coverUrl, referer)
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                album.label.ifBlank { album.id.toString() },
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                stringResource(R.string.online_picker_albums) +
                                                    " · " + album.id,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            Icons.Outlined.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(images, key = { it }) { url ->
                                val isSelected = url in selection
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(0.66f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .then(
                                            if (isSelected) Modifier.border(
                                                2.5.dp,
                                                LocalAccentColor.current,
                                                RoundedCornerShape(12.dp),
                                            ) else Modifier
                                        )
                                        .clickable { toggle(url) }
                                ) {
                                    AsyncImage(
                                        model = imageRequest(context, url, referer),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isSelected) {
                                        // 选中：轻压暗 + 强调色实心角标（Miuix 的多选观感；
                                        // 原来是黑色蒙层 + 白色对勾图标）。
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.18f))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp)
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(LocalAccentColor.current),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            item(span = { GridItemSpan(3) }) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        FilledTonalButton(
                                            onClick = { selection = selection + images.toSet() },
                                            shape = RoundedCornerShape(14.dp),
                                        ) {
                                            Text(stringResource(R.string.online_picker_select_page))
                                        }
                                        if (!noMore) {
                                            FilledTonalButton(
                                                enabled = !loadingMore,
                                                onClick = { loadMore() },
                                                shape = RoundedCornerShape(14.dp),
                                            ) {
                                                Text(
                                                    stringResource(
                                                        if (loadingMore) R.string.online_picker_loading
                                                        else R.string.online_picker_load_more
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    if (noMore) {
                                        Text(
                                            stringResource(R.string.online_picker_no_more),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 10.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumThumb(url: String, referer: String?) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(width = 60.dp, height = 84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = imageRequest(context, url, referer),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

internal fun imageRequest(
    context: android.content.Context,
    url: String,
    referer: String?,
    headers: Map<String, String> = emptyMap(),
): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .apply {
        // 阅读 (GlideHeaders) hands the source's own headers to every image
        // request; sites that check Referer/UA then serve the image instead of
        // stalling or 403-ing.
        var hasReferer = false
        for ((name, value) in headers) {
            if (name.equals("Referer", ignoreCase = true)) {
                hasReferer = true
                addHeader("Referer", value)
                continue
            }
            try {
                addHeader(name, value)
            } catch (_: Exception) {
            }
        }
        if (!hasReferer && referer != null) addHeader("Referer", referer)
    }
    // Article thumbs are decode/draw heavy; the global loader keeps software
    // bitmaps for compatibility, but these never need read-back.
    .allowHardware(true)
    .crossfade(false)
    .build()
