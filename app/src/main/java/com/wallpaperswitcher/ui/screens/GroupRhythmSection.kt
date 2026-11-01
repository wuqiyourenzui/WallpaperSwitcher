package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.engine.GroupRules
import kotlinx.coroutines.flow.first

/**
 * 分组独立节奏 (interval / 时间规则) inside the group header.
 *
 * Both options have an explicit "跟随全局" / "全天" state, which is what the
 * stored 0 / -1 values mean - so the default rotation stays exactly as it was
 * until the user opts a group out. The switch MODE is intentionally global only
 * (see the group header docs): mixing a per-group mode with the global one kept
 * confusing users, so a group only carries its interval and its time window.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GroupRhythmSection(
    group: WallpaperGroup,
    /** 时段 + 星期一次保存（两次写入会互相覆盖，见 GroupController）。 */
    onWindowSave: (Int, Int, Int) -> Unit,
    onMediaChange: (String) -> Unit,
) {
    var showWindow by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }

    val windowText = if (group.activeFromMinute !in 0..1439 ||
        group.activeToMinute !in 0..1439
    ) {
        stringResource(R.string.group_window_all_day)
    } else {
        GroupRules.formatMinuteOfDay(group.activeFromMinute).orEmpty() +
            " – " + GroupRules.formatMinuteOfDay(group.activeToMinute).orEmpty()
    }
    val daysText = when {
        group.activeDays <= 0 || group.activeDays and GroupRules.ALL_DAYS == GroupRules.ALL_DAYS ->
            stringResource(R.string.group_days_every_day)
        group.activeDays == (1 shl 5) or (1 shl 6) ->
            stringResource(R.string.group_days_weekend)
        group.activeDays == (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 3) or (1 shl 4) ->
            stringResource(R.string.group_days_weekdays)
        else -> {
            val label = StringBuilder()
            for (index in 0..6) {
                if ((group.activeDays shr index) and 1 == 1) label.append(weekdayShortLabel(index))
            }
            label.toString()
        }
    }
    val mediaText = when (GroupRules.mediaFilter(group)) {
        GroupRules.MEDIA_IMAGE -> stringResource(R.string.group_media_image)
        GroupRules.MEDIA_VIDEO -> stringResource(R.string.group_media_video)
        else -> stringResource(R.string.group_media_both)
    }

    FlowRow(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 分组自己的"间隔"芯片已下线：切换节奏统一由「设置 → 定时切换」调度
        // （每个分组仍然有自己的时段/星期/素材类型/主题这些规则）。
        AssistChip(
            onClick = { showWindow = true },
            label = {
                Text(
                    stringResource(R.string.group_window_label) + ": " + windowText +
                        " · " + daysText,
                    style = MaterialTheme.typography.labelMedium
                )
            },
            leadingIcon = {
                Icon(Icons.Outlined.Schedule, contentDescription = null, Modifier.size(16.dp))
            },
            modifier = Modifier.height(30.dp)
        )
        AssistChip(
            onClick = { showMedia = true },
            label = {
                Text(
                    stringResource(R.string.group_media_label) + ": " + mediaText,
                    style = MaterialTheme.typography.labelMedium
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Collections,
                    contentDescription = null,
                    Modifier.size(16.dp)
                )
            },
            modifier = Modifier.height(30.dp)
        )
    }

    if (showWindow) {
        GroupWindowDialog(
            fromMinute = group.activeFromMinute,
            toMinute = group.activeToMinute,
            daysMask = group.activeDays,
            onDismiss = { showWindow = false },
            onSelect = { from, to, days ->
                showWindow = false
                onWindowSave(from, to, days)
            }
        )
    }
    if (showMedia) {
        GroupMediaDialog(
            current = GroupRules.mediaFilter(group),
            onDismiss = { showMedia = false },
            onSelect = {
                showMedia = false
                onMediaChange(it)
            }
        )
    }
}

/** 分组素材类型: 两者 / 仅图片 / 仅视频（GIF 归入视频类动图）。 */
@Composable
fun GroupMediaDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val options = listOf(
        "" to stringResource(R.string.group_media_both),
        GroupRules.MEDIA_IMAGE to stringResource(R.string.group_media_image),
        GroupRules.MEDIA_VIDEO to stringResource(R.string.group_media_video)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_media_label)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.group_media_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableRow { onSelect(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == value, onClick = { onSelect(value) })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * 时间规则 dialog: 星期 + 全天 + 开始/结束两行时间。点任一行弹出「时 / 分」
 * 双列滚轮（MIUI 闹钟样式，见 [WheelTimeDialog]），保存时把时段和星期**一次**
 * 写回（见 GroupController.setGroupActiveWindowAndDays）。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun GroupWindowDialog(
    fromMinute: Int,
    toMinute: Int,
    daysMask: Int,
    onDismiss: () -> Unit,
    onSelect: (Int, Int, Int) -> Unit
) {
    // 输入源是父级的 group 行：保存后父级会带着新值重组，所以这几个 remember 必须
    // **以传入值为 key**。不设 key 时它们会一直保留对话框第一次打开时的旧值 ——
    // 用户改完保存、再打开看到的还是老时间，看起来就像"要设置两次才生效"。
    val hadWindow = fromMinute in 0..1439 && toMinute in 0..1439 &&
        fromMinute != toMinute
    var fromPick by remember(fromMinute, toMinute, daysMask) {
        mutableIntStateOf(fromMinute.takeIf { it in 0..1439 } ?: DEFAULT_FROM_MINUTE)
    }
    var toPick by remember(fromMinute, toMinute, daysMask) {
        mutableIntStateOf(toMinute.takeIf { it in 0..1439 } ?: DEFAULT_TO_MINUTE)
    }
    // Empty / full mask = 每天; the chips start fully ticked in that case.
    var days by remember(daysMask) {
        mutableIntStateOf(if (daysMask <= 0) GroupRules.ALL_DAYS else daysMask)
    }
    // 「全天」不是另存一个值：两个滑杆推到同一分钟就等价于全天
    // （见 GroupRules.windowContains：from == to 视为不限制），
    // 所以开关状态由此推导，不额外存一份会漂移的状态。
    var allDay by remember(fromMinute, toMinute, daysMask) { mutableStateOf(!hadWindow) }
    // 正在用滚轮编辑哪个时间（null = 没在编辑）。点「开始 / 结束」行打开。
    var editing by remember(fromMinute, toMinute, daysMask) {
        mutableStateOf<WindowTimeField?>(null)
    }
    val from = if (allDay) -1 else fromPick
    val to = if (allDay) -1 else toPick
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_window_label)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 星期: multi-select; all seven = 每天 (stored as 0).
                // 「全周」放在标题行右侧：8 个 chip 挤一行时，窄屏上日期单字会被
                // chip 边缘裁掉（手机实测），标题行的按钮还能一键恢复每天。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.group_days_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { days = GroupRules.ALL_DAYS },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(
                            stringResource(R.string.group_days_all),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 等宽铺满整行：7 个星期 chip 左对齐时右边会留大片空白（平板尤其
                // 明显），等宽之后一眼就能看出"这是一周七天"，窄屏也不会被裁掉。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (0..6).forEach { index ->
                        val on = (days shr index) and 1 == 1
                        FilterChip(
                            selected = on,
                            onClick = {
                                val next = if (on) days and (1 shl index).inv()
                                else days or (1 shl index)
                                days = next and GroupRules.ALL_DAYS
                            },
                            label = {
                                Text(
                                    weekdayShortLabel(index),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.group_window_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.group_window_all_day),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                if (!allDay) {
                    // 时间用「开始 / 结束」两行 + 滚轮选择（MIUI 闹钟样式）：
                    // 时 / 分各一列、选中值在中间放大；整分钟可选 —— 旧滑杆只有
                    // 5 分钟一档，23:56–23:59 这种时间根本选不到。
                    Spacer(modifier = Modifier.height(4.dp))
                    TimePickerRow(
                        label = stringResource(R.string.group_window_from),
                        minute = fromPick,
                        onClick = { editing = WindowTimeField.FROM },
                    )
                    Divider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    TimePickerRow(
                        label = stringResource(R.string.group_window_to),
                        minute = toPick,
                        onClick = { editing = WindowTimeField.TO },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                // An empty weekday selection would lock the group out forever.
                enabled = days != 0,
                onClick = { onSelect(from, to, days) }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
    val field = editing
    if (field != null) {
        WheelTimeDialog(
            title = stringResource(
                if (field == WindowTimeField.FROM) R.string.group_window_from
                else R.string.group_window_to
            ),
            initialMinute = if (field == WindowTimeField.FROM) fromPick else toPick,
            onDismiss = { editing = null },
            onConfirm = { minute ->
                if (field == WindowTimeField.FROM) fromPick = minute else toPick = minute
                editing = null
            },
        )
    }
}

/** 滚轮里正在编辑的字段（点开始 / 结束行打开对应的滚轮）。 */
private enum class WindowTimeField { FROM, TO }

/** 默认窗口（从 22:00 到 06:00），只在源值不是有效分钟时兜底。 */
private const val DEFAULT_FROM_MINUTE = 22 * 60
private const val DEFAULT_TO_MINUTE = 6 * 60

/**
 * 一行「开始 / 结束  HH:mm」：点击打开 [WheelTimeDialog] 选时间。
 *
 * 跨越午夜是允许的（`from > to` 即 22:00→06:00，见 [GroupRules.windowContains]），
 * 所以两行互相独立，不强制先后。
 */
@Composable
private fun TimePickerRow(label: String, minute: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            // 这里一定有值（滚轮只会产生 0..1439），格式化不会失败。
            GroupRules.formatMinuteOfDay(minute) ?: "--:--",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 滚轮里一行的高度；上下各留两行的间距，让选中值停在正中间。 */
private val WheelItemHeight = 40.dp

/** 滚轮上下各留的空行数（选中行停在正中，需要 [WheelPaddingRows] 行）。 */
private const val WheelPaddingRows = 2

/**
 * 「时 / 分」双列滚轮（MIUI 闹钟样式）：吸附滚动，中间一行放大显示，确定才回写。
 * 整分钟可选（00:00–23:59）。
 */
@Composable
private fun WheelTimeDialog(
    title: String,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val start = initialMinute.coerceIn(0, 1439)
    var hour by remember { mutableIntStateOf(start / 60) }
    var minute by remember { mutableIntStateOf(start % 60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    WheelHeader(
                        text = stringResource(R.string.time_unit_hour),
                        modifier = Modifier.weight(1f),
                    )
                    WheelHeader(
                        text = stringResource(R.string.time_unit_minute),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    WheelColumn(
                        values = 0..23,
                        selected = hour,
                        onSelected = { hour = it },
                        modifier = Modifier.weight(1f),
                    )
                    WheelColumn(
                        values = 0..59,
                        selected = minute,
                        onSelected = { minute = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour * 60 + minute) }) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 滚轮表头（时 / 分）。 */
@Composable
private fun WheelHeader(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一列数字滚轮：上下各留两行，中间一行即选中值。
 *
 * 选中项 = 「离滚轮视觉中心最近的那一行」：把 layoutInfo 的行偏移换算到视觉
 * 坐标（偏移以内容区起点为原点，见 [WheelPaddingRows] 与下面的换算注释），初始
 * 值则在首帧布局后用像素 delta（scrollBy）滚到正中，不猜
 * initialFirstVisibleItemIndex 的落点。吸附由 [rememberSnapFlingBehavior] 保证
 * 停稳时正好一行对齐中心。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelColumn(
    values: IntRange,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = values.count()
    val initialIndex = (selected - values.first).coerceIn(0, count - 1)
    val state = rememberLazyListState()
    var heightPx by remember { mutableIntStateOf(0) }
    // 初始定位完成前不往外回写选中值，免得把调用方的初始值改成"还没滚过去的"
    // 第 0 项。
    var positioned by remember { mutableStateOf(false) }
    val centeredIndex by remember {
        derivedStateOf {
            if (!positioned) {
                initialIndex
            } else {
                val info = state.layoutInfo
                if (heightPx <= 0 || info.visibleItemsInfo.isEmpty()) {
                    initialIndex
                } else {
                    // layoutInfo 的 item offset 是相对**内容区起点**（顶部空行之后）
                    // 的：滚轮视觉中心换算到这个坐标系 = heightPx/2 - 顶部空行高度。
                    // 用 heightPx/2 直接比较会整体偏 [WheelPaddingRows] 行（真机实测）。
                    val itemPx = info.visibleItemsInfo.first().size
                    val center = heightPx / 2f - itemPx * WheelPaddingRows
                    info.visibleItemsInfo.minByOrNull {
                        kotlin.math.abs(it.offset + it.size / 2f - center)
                    }?.index ?: initialIndex
                }
            }
        }
    }
    // 首帧布局后把初始值滚到正中间：按像素滚 initialIndex 行，任何
    // contentPadding / 初始索引的语义差异都会被这一步校正掉。
    LaunchedEffect(Unit) {
        val (layout, _) = snapshotFlow { state.layoutInfo to heightPx }
            .first { (layout, height) -> height > 0 && layout.visibleItemsInfo.isNotEmpty() }
        val itemPx = layout.visibleItemsInfo.first().size
        if (itemPx > 0 && initialIndex > 0) {
            state.scrollBy(initialIndex * itemPx.toFloat())
        }
        positioned = true
    }
    // 滚到哪一行，外层状态就跟到哪一行；点「完成」时写入的就是当前正中的值。
    LaunchedEffect(centeredIndex) {
        onSelected(values.first + centeredIndex.coerceIn(0, count - 1))
    }
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = WheelItemHeight * WheelPaddingRows),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(WheelItemHeight * 5)
            .onSizeChanged { heightPx = it.height },
    ) {
        items(count) { index ->
            val isSelected = index == centeredIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WheelItemHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%02d".format(values.first + index),
                    style = if (isSelected) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    },
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}
/** Localised one-character weekday label, Monday first. */
@Composable
internal fun weekdayShortLabel(index: Int): String = stringResource(
    when (index) {
        0 -> R.string.day_mon
        1 -> R.string.day_tue
        2 -> R.string.day_wed
        3 -> R.string.day_thu
        4 -> R.string.day_fri
        5 -> R.string.day_sat
        else -> R.string.day_sun
    }
)

/** Row-level click without the ripple covering the radio button's own tap. */
private fun Modifier.selectableRow(onClick: () -> Unit): Modifier =
    clickable { onClick() }
