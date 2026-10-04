package com.wallpaperswitcher.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState

/**
 * HyperOS / MIUI 设计令牌。
 *
 * 参考 HyperIsland（github.com/1812z/HyperIsland，界面基于 Miuix）提炼：
 * - 页面左右留白 16dp，卡片间距 12dp，卡片圆角 16dp；
 * - 设置行内边距 18dp / 14dp，行首图标与文字间距 16dp；
 * - 分区标题是小号强调色文字（不是大字标题）；
 * - 底栏是悬浮胶囊：外高 64dp、内高 56dp，距底 12dp，选中项是圆角药丸。
 */
object HiDims {
    val PageHorizontal = 16.dp
    val CardCorner = 16.dp
    val CardSpacing = 12.dp
    val RowHorizontal = 18.dp
    val RowVertical = 14.dp
    val IconGap = 16.dp
    val PageTop = 8.dp
    val PageBottom = 28.dp
    val NavOuterHeight = 64.dp
    val NavInnerHeight = 56.dp
}

/** 卡片底色：浅色下一层浅灰，深色下略亮于背景，和 Miuix Card 的观感一致。 */
@Composable
fun hiCardColor(): Color = if (isSystemInDarkTheme()) {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
} else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
}

/** Miuix 风格的圆角卡片（无阴影、无描边）。 */
@Composable
fun HiCard(
    modifier: Modifier = Modifier,
    color: Color = hiCardColor(),
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(HiDims.CardCorner)
    // 按压反馈：HyperOS/Miuix 的卡片按下时轻微收缩（配合默认涟漪），
    // 松手带一点回弹 —— 只用 graphicsLayer，不触发重新布局。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = HiMotion.press(),
        label = "hiCardPress",
    )
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .fillMaxWidth()
            .clip(shape)
            .background(color)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                } else Modifier
            )
            .padding(contentPadding),
        content = content,
    )
}

/** 分区标题：小号强调色文字 + 18/8 内边距（对应 Miuix `SmallTitle`）。 */
@Composable
fun HiSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LocalAccentColor.current.takeIf { it != Color.Unspecified }
            ?: MaterialTheme.colorScheme.primary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(
            start = HiDims.RowHorizontal,
            end = HiDims.RowHorizontal,
            top = 4.dp,
            bottom = 8.dp,
        ),
    )
}

/**
 * 一行设置项（对应 Miuix `BasicComponent`）：行首图标 + 标题/说明 + 行尾内容。
 * 多行放进同一个 [HiCard] 里就是 HyperOS 的设置分组。
 */
@Composable
fun HiRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = HiDims.RowHorizontal, vertical = HiDims.RowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(HiDims.IconGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        trailing?.let {
            Spacer(modifier = Modifier.width(12.dp))
            it()
        }
    }
}

/** 一条底部导航项。 */
data class HiNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * 统一的「加载中」提示：静态图标 + 一段文字。
 *
 * 全应用都刻意不用动画进度圈——捆绑的 animation-core 缺少 M3
 * `CircularProgressIndicator` 需要的方法（历史上的 NoSuchMethodError 崩溃，
 * 见 [com.wallpaperswitcher.ui.screens.GroupDetailScreen] 的扫描卡片注释）。
 * 这里把图标尺寸、间距与字色收敛成一处，各页只传文案。
 */
@Composable
fun HiLoadingHint(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.HourglassEmpty,
    iconSize: Dp = 16.dp,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = style, color = contentColor)
    }
}

/**
 * 页面 / 对话框级的居中加载态，与 [HiLoadingHint] 同构（图标 18dp、正文样式）。
 * 调用方负责给 [modifier] 传尺寸（`fillMaxSize()` / `weight(1f)` / 固定高度）。
 */
@Composable
fun HiLoadingState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        HiLoadingHint(
            text = text,
            iconSize = 18.dp,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 容器式空态（Home / 订阅 / 分组详情共用）：浅色圆角方块里的强调色图标 +
 * 标题 +（可选）说明。三处各写一份时已经漂移过一次（分组详情仍是 64dp 裸图标
 * + 40% 透明文字），抽到这里以后只改一处。
 */
@Composable
fun HiEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    icon: ImageVector = Icons.Outlined.Image,
) {
    val accent = LocalAccentColor.current.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(hiCardColor()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!hint.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 悬浮胶囊底栏（对应 Miuix 的 NavigationBar / LiquidGlassNavigationBar）：
 * 圆角 32dp 的浮动条，选中项是一枚药丸高亮。
 */
@Composable
fun HiNavigationBar(
    items: List<HiNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val accent = LocalAccentColor.current.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
            .fillMaxWidth()
            .height(HiDims.NavInnerHeight)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val unselectedTint = MaterialTheme.colorScheme.onSurfaceVariant
        items.forEachIndexed { index, item ->
            // key(): 每个标签各自持有动画状态，条目增删或换序都不会串。
            key(item.label) {
                val selected = index == selectedIndex
                val progress by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = HiMotion.selection(),
                    label = "hiNavProgress",
                )
                // 弹簧会轻微过冲（0→1 时超过 1，1→0 时低于 0），而
                // Color.copy(alpha = v) 对越界值直接抛 IllegalArgumentException
                // （真机上崩过一次）：给颜色用的进度必须先夹到 0..1。
                val p = progress.coerceIn(0f, 1f)
                // 药丸底色、图标/文字颜色、图标缩放共用同一个 progress，
                // 三者的节奏因此完全一致（Miuix 的选中动画就是这个做法）。
                val tint = lerp(unselectedTint, accent, p)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(accent.copy(alpha = 0.14f * p))
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                val s = 1f + 0.08f * p
                                scaleX = s
                                scaleY = s
                            },
                    )
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        color = tint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** HyperIsland 的绿/红状态配色，用于「运行中 / 未运行」这类一句话状态。 */
object HiStatusColors {
    val Active = Color(0xFF36D167)
    val ActiveBackground = Color(0xFFDFFAE4)
    val Inactive = Color(0xFFFF5A52)
    val InactiveBackground = Color(0xFFFFE5E3)
    val WarningBackground = Color(0xFFFFF3D6)
    val WarningContent = Color(0xFF704D00)
}

/** 首页/统计用的大数字卡片（对应 HyperIsland 的 StatCard）。 */
@Composable
fun HiStatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    HiCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 状态卡片：左侧大字状态 + 一行说明，配色取自 HyperIsland。 */
@Composable
fun HiStatusCard(
    active: Boolean,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val bg = if (active) HiStatusColors.ActiveBackground else HiStatusColors.InactiveBackground
    val fg = if (active) Color(0xFF101010) else HiStatusColors.Inactive
    HiCard(modifier = modifier, color = bg, onClick = onClick) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = fg)
            Text(
                text = summary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) Color(0xFF101010) else fg,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
