package com.wallpaperswitcher.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch

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
            .heightIn(max = 420.dp)
            // heightIn 先把面板夹到 420dp，verticalScroll 再把超出的部分变成可滚动
            // 的内容：否则选项多（或系统字体调到最大）时，最后几项会被裁在屏幕外，
            // 用户永远点不到 —— 而"面板可滚动"正是这个控件文档里承诺的行为。
            .verticalScroll(rememberScrollState()),
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

/**
 * 「当前值 + 下拉 → 弹出选项面板」的**整套**实现：把 [HiOptionRow]、[HiOptionSheet]、
 * [HiOptionPanelCard] 和弹出状态焊在一起，设置页只需要给一张选项表和当前值。
 *
 * 为什么不用 Material 的 `DropdownMenu`（和 [HiOptionRow] 的注释同一个理由）：
 * 下拉菜单会盖住当前行、没有"当前值"这一列，而且它挂在行上，滚动列表时位置会飘。
 *
 * 为什么用 [ModalBottomSheet]：
 *  - 它是**独立窗口**，不受设置页 `verticalScroll` 的裁剪，也不会被列表项的
 *    `clickable` 区域限制；点遮罩、按返回键、往下拖都能关闭，系统返回键的
 *    行为与系统设置页一致（`onDismissRequest`）；
 *  - 面板里选项超过 4~5 个时，行的高度不变（[HiOptionSheet] 自己滚），列表
 *    不会因为某个设置选项多而被撑开 —— 这正是参考图里的形态；
 *  - 它是 Material3 的 `@ExperimentalMaterial3Api`，本项目其它地方
 *    （`FilterChip` 等）已经在用同一套 opt-in，没有引入新的风险面。
 *
 * 弹出层是独立窗口，但内容 lambda 仍在调用方的组合里执行，所以
 * [LocalAccentColor]、[MaterialTheme] 和主题色都照常生效。
 *
 * @param options 选项表（纯数据，便于单测）。
 * @param selectedKey 当前值；调用方负责归一化（未知值要落到表里的某一项）。
 * @param onSelect 用户选了某一项。只有用户真的点了才会回调 —— 打开/关闭面板不会写库。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // 记住"面板开着"这件事：转屏/进程被杀重建之后面板还在，用户不会丢上下文。
    var open by rememberSaveable { mutableStateOf(false) }
    HiOptionRow(
        title = title,
        value = hiOptionLabelRes(options, selectedKey)?.let { stringResource(it) }.orEmpty(),
        modifier = modifier,
        subtitle = subtitle,
        icon = icon,
        onClick = { open = true },
    )
    if (open) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            // 和 [HiOptionPanelCard] 同色，面板从拖拽条到卡片是一整块，不会出现
            // 上下两截颜色。
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            HiOptionPanelCard(title = title) {
                HiOptionSheet(
                    options = hiOptions(options),
                    selectedKey = selectedKey,
                    onSelect = { key ->
                        onSelect(key)
                        // 先播完收起动画再离开组合（Material 的写法）。完成回调里
                        // 无条件关闭：即使动画被手势打断，面板也绝不会卡在打开状态。
                        scope.launch { sheetState.hide() }.invokeOnCompletion { open = false }
                    },
                )
            }
        }
    }
}

/** 选项行的通用内边距常数（供子页面对齐使用）。 */
val HiOptionRowPadding = Arrangement.spacedBy(0.dp)
