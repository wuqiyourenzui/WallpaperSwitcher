package com.wallpaperswitcher.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.ui.theme.HiEmptyState
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

// Grid thumbnails decode deliberately below the ~312px cell (3x density) so
// memory traffic and decode time stay tiny; same numbers as the group grid
// (see GroupDetailScreen.buildGridThumbnailRequest).
private const val FAVORITE_THUMBNAIL_DECODE_SIZE = 176

/**
 * 收藏聚合页：跨分组的收藏放在一起看（收藏本来只是分组内的一个过滤器）。
 *
 * 交互按"收藏是最常用的那几张"来定：
 *  - 点一张 = 直接设为壁纸（`viewModel.setImageAsWallpaper`）。这里是"我挑好了，
 *    就用它"的页面，点开还要再确认一次的话，收藏页就只是另一个九宫格了；
 *    大图浏览页才是"一张一张挑"的地方（那边双击收藏、长按删除）。
 *  - 长按 = 取消收藏（`viewModel.setFavorite(id, false)`），取消后本页立刻少一张
 *    （favorites 是 Room Flow）。不用"点开详情再取消"是因为取消收藏是这里的
 *    整理动作，两步操作会让人懒得整理。
 *
 * 数据全部走 ViewModel（`viewModel.favorites`：跨分组、按 uri 去重、新的在前），
 * 不直连 DAO。
 */
@Composable
fun FavoritesScreen(
    viewModel: WallpaperViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    // 系统返回键/手势退出（整屏页面自己处理）。
    BackHandler { onBack() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部只有返回箭头：应用顶栏其实不显示这个页面的返回键（收藏是从首页
            // 右上角进来的），所以这里自己补一个；标题也不重复写，顶栏已经是大字
            // 的「收藏」了。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        stringResource(R.string.action_back),
                    )
                }
            }

            if (favorites.isEmpty()) {
                HiEmptyState(
                    title = stringResource(R.string.favorites_empty),
                    icon = Icons.Outlined.FavoriteBorder,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 48.dp),
                )
            } else {
                LazyVerticalGrid(
                    // 与分组详情页的网格同一套参数（3 列手机 / 平板自适应）。
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(favorites, key = { image -> image.id }) { image ->
                        FavoriteGridItem(
                            image = image,
                            onClick = { viewModel.setImageAsWallpaper(image) },
                            onLongClick = {
                                viewModel.setFavoriteByUri(image.uri, false)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.browse_unfavorited),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一张收藏：缩略图 + 视频/GIF 角标。点 = 设壁纸，长按 = 取消收藏。
 *
 * 长的两个 clickLabel 是给读屏用的 —— 这个格子只有手势、没有按钮，
 * 不给标签的话读屏用户只知道"可以双击"，不知道双击会发生什么。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteGridItem(
    image: WallpaperImage,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageRequest = remember(image.uri, image.mediaType, context) {
        favoriteThumbnailRequest(context, image)
    }
    val placeholderPainter = remember { ColorPainter(Color(0xFFE0E0E0)) }
    val errorPainter = remember { ColorPainter(Color(0xFFBDBDBD)) }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClickLabel = stringResource(R.string.preview_apply),
                onLongClickLabel = stringResource(R.string.media_favorite_remove),
                onLongClick = onLongClick,
                onClick = onClick,
            )
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = image.displayName.ifBlank { null },
            contentScale = ContentScale.Crop,
            placeholder = placeholderPainter,
            error = errorPainter,
            modifier = Modifier.fillMaxSize(),
        )

        // 视频/GIF 角标（与分组网格一致：这类素材只显示首帧，得让人看出来）。
        if (MediaTypes.isMotion(image.mediaType)) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (image.mediaType == MediaTypes.VIDEO) Icons.Filled.Videocam else Icons.Filled.Gif,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    if (image.mediaType == MediaTypes.VIDEO) {
                        stringResource(R.string.media_type_video)
                    } else {
                        "GIF"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

private fun favoriteThumbnailRequest(context: Context, image: WallpaperImage): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(image.uri))
        .size(FAVORITE_THUMBNAIL_DECODE_SIZE, FAVORITE_THUMBNAIL_DECODE_SIZE)
        // No crossfade: scrolling a grid would otherwise start a fade per new cell.
        .crossfade(0)
        // ARGB_8888 + hardware bitmaps: the RGB_565 default is incompatible with
        // hardware bitmaps, which forced software copies and re-uploads per draw.
        .allowHardware(true)
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        .apply {
            if (image.mediaType == MediaTypes.VIDEO) {
                decoderFactory(VideoFrameDecoder.Factory())
            }
        }
        .build()
