package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.engine.GroupRules

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
    onWindowChange: (Int, Int) -> Unit,
    onDaysChange: (Int) -> Unit,
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
                onWindowChange(from, to)
                if (days != group.activeDays) onDaysChange(days)
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
 * 时间规则 dialog: a `HH:mm` window (or 全天). The two fields are validated with
 * [GroupRules.parseMinuteOfDay]; the confirm button stays disabled until both
 * parse, so a typo can never write a broken window.
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
    var fromText by remember {
        mutableStateOf(GroupRules.formatMinuteOfDay(fromMinute) ?: "22:00")
    }
    var toText by remember {
        mutableStateOf(GroupRules.formatMinuteOfDay(toMinute) ?: "06:00")
    }
    // Empty / full mask = 每天; the chips start fully ticked in that case.
    var days by remember {
        mutableStateOf(if (daysMask <= 0) GroupRules.ALL_DAYS else daysMask)
    }
    val from = GroupRules.parseMinuteOfDay(fromText)
    val to = GroupRules.parseMinuteOfDay(toText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_window_label)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 星期: multi-select; all seven = 每天 (stored as 0).
                Text(
                    stringResource(R.string.group_days_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 等宽铺满整行：7 个星期 chip 以前左对齐挤在左边，右边留一大片
                // 空白（平板/大屏尤其明显）。等宽之后一眼就能看出"这是一周七天"。
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
                            label = { Text(weekdayShortLabel(index)) },
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
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        label = { Text(stringResource(R.string.group_window_from)) },
                        singleLine = true,
                        isError = from == null,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = { Text(stringResource(R.string.group_window_to)) },
                        singleLine = true,
                        isError = to == null,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { onSelect(-1, -1, GroupRules.ALL_DAYS) }) {
                    Text(stringResource(R.string.group_window_all_day))
                }
            }
        },
        confirmButton = {
            TextButton(
                // An empty weekday selection would lock the group out forever.
                enabled = from != null && to != null && days != 0,
                onClick = { onSelect(from ?: 0, to ?: 0, days) }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
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
