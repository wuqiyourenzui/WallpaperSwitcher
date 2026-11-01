package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.WallpaperGroup

/**
 * 网盘浏览页用的三个弹层：选分组、新建分组、添加进度。
 *
 * 单独放在这里，是因为 [OnlineBrowserDialog] 里原本把浏览、搜索、下载、分组、进度
 * 五种互不相干的 UI 塞在同一个 Composable 里（一个逻辑改动要动同一个千行文件）。
 */

/** 选分组：列出全部分组（可选任意分组），末尾给「新建分组…」。 */
@Composable
internal fun GroupPickerDialog(
    groups: List<WallpaperGroup>,
    onDismiss: () -> Unit,
    onPick: (groupId: Long) -> Unit,
    onCreateGroup: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_viewer_group_pick)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                items(groups, key = { it.id }) { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(group.id) }
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Divider()
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onCreateGroup)
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.online_group_create),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 新建分组：填入名字后建组并立刻把这一批下进去。 */
@Composable
internal fun CreateGroupDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_group_create)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.online_group_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = onConfirm) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 添加进度：文件名 + 已下载/总大小 + 百分比条（视频动辄几百 MB，必须有反馈）。 */
@Composable
internal fun AddToGroupProgressDialog(
    name: String,
    doneBytes: Long,
    totalBytes: Long,
) {
    val fraction = if (totalBytes > 0L) {
        (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(
                        R.string.online_group_progress,
                        formatSize(doneBytes),
                        if (totalBytes > 0L) formatSize(totalBytes) else "?",
                        (fraction * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
        confirmButton = {},
    )
}

/** 字节数 → 人类可读（KB/MB/GB）。进度弹层与列表行共用。 */
internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
