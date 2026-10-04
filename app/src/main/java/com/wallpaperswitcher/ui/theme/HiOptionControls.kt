package com.wallpaperswitcher.ui.theme

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.wallpaperswitcher.ui.theme.HiMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.wallpaperswitcher.R
import androidx.compose.ui.res.stringResource

/**
 * 设置里一个「点开选一个」的选项行。
 *
 * 交互约定（整个设置区统一）：
 *  - 行右侧显示**当前值** + 上下箭头；
 *  - 点一下在行的下缘弹出浮层列表（[HiOptionPickerRow]），选完即关闭并保存；
 *  - 选项超过 3 个时面板可滚动，所以行本身的高度恒定 —— 列表不会因为某个
 *    设置选项多而被撑开。
 *
 * 浮层列表的形态对齐 HyperIsland（Miuix `WindowDropdownPreference`）：列表紧贴
 * 当前行（不会像 Material 的 DropdownMenu 那样盖住标题），行里的"当前值"始终可见。
 */
@Composable
fun HiOptionRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    /** 展开时把右侧箭头转 180°（有面板在下面的那一行才需要传）。 */
    expanded: Boolean = false,
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
            modifier = Modifier
                .size(20.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
}

/** [HiOptionRow] 浮层列表里的一个选项。 */
data class HiOption(val key: String, val label: String, val preview: Color? = null)

/**
 * [HiOption] 的纯数据版：文案只存资源 id，不碰 Compose 也不碰 Android 框架。
 *
 * 「某个设置面板里有哪些选项、当前值对应哪一项」是设置页最容易出错的地方
 * （漏一项 → 用户选不回自己原来的值；两行指向同一个字符串 → 显示串味），
 * 把这张表抽成纯数据之后，这些不变量就能直接写在单测里
 * （见 `app/src/test/java/com/wallpaperswitcher/ui/theme/HiOptionLogicTest.kt`）。
 */
internal data class HiOptionSpec(
    val key: String,
    @StringRes val labelRes: Int,
    val preview: Color? = null,
)

/** 把 [HiOptionSpec] 表解析成控件要的 [HiOption]，文案在这里才落地。 */
@Composable
internal fun hiOptions(specs: List<HiOptionSpec>): List<HiOption> =
    specs.map { HiOption(it.key, stringResource(it.labelRes), it.preview) }

/**
 * [specs] 里 [key] 对应的文案资源；[key] 不在表里时返回 null。
 *
 * 行右侧的"当前值"和面板里高亮的那一项都通过它取得，所以两者永远说的是同一件事
 * （调用方在算 key 时已经把未知值归一化到表里存在的值，见各页面的 `*KeyOf`）。
 */
internal fun hiOptionLabelRes(specs: List<HiOptionSpec>, key: String): Int? =
    specs.firstOrNull { it.key == key }?.labelRes

/**
 * 面板里是否有 [key] 这一项。
 *
 * 验收要求「当前值必须出现在面板里」：任何可能被存下来的值，归一化之后都要能在
 * 表里找到，否则用户看不到自己当前选的是什么，也没法把它改回来。
 */
internal fun hasHiOption(specs: List<HiOptionSpec>, key: String): Boolean =
    specs.any { it.key == key }

/**
 * 浮层选项列表里的一行（HyperIsland 用的 Miuix `DropdownImpl` 形态）。
 *
 * 选中项：正文用主题强调色 + 行尾 20dp 对勾；未选中项是普通正文色。
 * 首/末行 20dp 纵向留白、中间行 12dp，与 Miuix 的 `DropdownDefaults` 一致 ——
 * 这样列表上下两端看起来是"包住"内容的，而不是被裁掉。
 */
@Composable
private fun HiOptionPopupEntry(
    label: String,
    selected: Boolean,
    preview: Color?,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = if (isFirst) 20.dp else 12.dp,
                bottom = if (isLast) 20.dp else 12.dp,
            ),
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
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = LocalAccentColor.current,
                modifier = Modifier.size(20.dp),
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
    icon: ImageVector? = null,
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
            // 色值可能是"跟随系统"这类本地化文字（俄语/西语都很长）：溢出时省略，
            // 不要挤掉左边的标题，也不要被裁成半个词。
            overflow = TextOverflow.Ellipsis,
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

/**
 * 「当前值 + 箭头 → 浮层选项列表」的整套实现：设置页只需要给一张选项表和当前值。
 *
 * 形态对齐 HyperIsland（Miuix `WindowDropdownPreference`）：点一下行，弹出一条
 * **贴着这一行**的浮层列表（不是行内展开的面板），选中项用强调色 + 行尾对勾标出，
 * 点任意一项立即生效并关闭；点外部 / 返回键只关闭不改值。
 *
 * 行本身仍然显示当前值，所以打开了哪一行的列表、现在选的是什么，始终都在屏幕上。
 */
@Composable
internal fun HiOptionPickerRow(
    title: String,
    options: List<HiOptionSpec>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    // 开关状态每行各自持有；rememberSaveable 让转屏/进程重建后仍停在原来的行上。
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier = modifier) {
        HiOptionRow(
            title = title,
            value = hiOptionLabelRes(options, selectedKey)?.let { stringResource(it) }.orEmpty(),
            subtitle = subtitle,
            icon = icon,
            expanded = open,
            onClick = { open = !open },
        )
        if (open) {
            HiOptionDropdown(
                options = hiOptions(options),
                selectedKey = selectedKey,
                onSelect = { key ->
                    onSelect(key)
                    open = false
                },
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * 浮层列表本体：16dp 圆角、卡片底色（surfaceVariant）、带阴影，内容超高时可滚动
 * （Miuix `WindowListPopup` 的观感）。出现时从行的右上角轻微放大淡入。
 */
@Composable
private fun HiOptionDropdown(
    options: List<HiOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val positionProvider = remember(gapPx) { HiOptionPopupPositionProvider(gapPx) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(HiMotion.enter()) +
                scaleIn(
                    animationSpec = HiMotion.enter(),
                    initialScale = 0.92f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(min = 196.dp),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEachIndexed { index, option ->
                        HiOptionPopupEntry(
                            label = option.label,
                            selected = option.key == selectedKey,
                            preview = option.preview,
                            isFirst = index == 0,
                            isLast = index == options.lastIndex,
                            onClick = { onSelect(option.key) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 浮层紧贴行的下缘、与行右对齐（Miuix `PopupPositionProvider.Align.End`）；
 * 下方空间不够时翻到行上方，并始终夹在当前窗口内。
 */
private class HiOptionPopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxX)
        val below = anchorBounds.bottom + gapPx
        val y = if (below <= maxY) {
            below
        } else {
            (anchorBounds.top - popupContentSize.height - gapPx).coerceIn(0, maxY)
        }
        return IntOffset(x, y)
    }
}
