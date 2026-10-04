package com.wallpaperswitcher.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wallpaperswitcher.R
import androidx.compose.ui.res.stringResource

/**
 * 设置里一个「点开选一个」的选项行（对应界面参考的图 1）。
 *
 * 交互约定（整个设置区统一）：
 *  - 行右侧显示**当前值** + 上下箭头；
 *  - 点一下从下方弹出选项面板（[HiOptionSheet]），选完即关闭并保存；
 *  - 选项超过 3 个时面板可滚动，所以行本身的高度恒定 —— 列表不会因为某个
 *    设置选项多而被撑开。
 *
 * 为什么不用 Material 的 DropdownMenu：它在长列表里会盖住当前行、失去"这个值
 * 属于哪一行"的视觉联系，而且没有"当前值"这一列（参考图里那一列是主要信息）。
 */
@Composable
fun HiOptionRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalAccentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** [HiOptionRow] 弹出面板里的一个选项。 */
data class HiOption(val key: String, val label: String, val preview: Color? = null)

/**
 * 选项面板的内容（图 1 的弹出层）：每行「选项名 + 预览色块（可选）+ 勾选」。
 *
 * 自己画而不是用 Material 的 DropdownMenu：需要在选项里带颜色预览（信息架构里的
 * 主题色/按钮色就是这个形态），并且要能容纳 6~8 个选项而不滚动。
 */
@Composable
fun HiOptionSheet(
    options: List<HiOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    defaultLabel: String? = null,
    onDefault: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
    ) {
        defaultLabel?.let { label ->
            HiOptionSheetEntry(
                label = label,
                selected = false,
                preview = null,
                onClick = { onDefault?.invoke() },
            )
        }
        options.forEach { option ->
            HiOptionSheetEntry(
                label = option.label,
                selected = option.key == selectedKey,
                preview = option.preview,
                onClick = { onSelect(option.key) },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun HiOptionSheetEntry(
    label: String,
    selected: Boolean,
    preview: Color?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (preview != null) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(preview)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            Spacer(modifier = Modifier.width(14.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = LocalAccentColor.current,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 主题色 / 按钮色这类"颜色值"的选项行：右侧显示当前颜色的小圆点 + 色值。
 *
 * 颜色是唯一一种"光看文字说不清"的设置，所以这一行额外画一个色块。
 */
@Composable
fun HiColorRow(
    title: String,
    hex: String,
    color: Color,
    onClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            hex,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        if (onClear != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(R.string.action_delete),
                style = MaterialTheme.typography.labelMedium,
                color = LocalAccentColor.current,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 面板里"没有选项可选"时的占位（避免空面板看起来像卡住）。 */
@Composable
fun HiOptionSheetEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 供 [HiOptionSheet] 的调用方在同一个 FlowRow 里摆放：保持间距一致。 */
@Composable
fun HiOptionSpacer() = Spacer(modifier = Modifier.height(4.dp))

/** 一组选项面板的容器（BottomSheet 的替代：这版用一个带圆角的卡片）。 */
@Composable
fun HiOptionPanelCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 8.dp),
        )
        content()
    }
}

/** 面板里各选项之间的分隔间距（统一，防止每个页面自己写一套）。 */
@Composable
fun HiOptionGap() = Spacer(modifier = Modifier.height(2.dp))

/** 选项行的通用内边距常数（供子页面对齐使用）。 */
val HiOptionRowPadding = Arrangement.spacedBy(0.dp)
