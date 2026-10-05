package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wallpaperswitcher.R
import com.wallpaperswitcher.ui.theme.LocalAccentColor

/**
 * 多选操作栏：退出 / 全选 / 已选 n/m + 批量启用 / 批量停用 / 批量删除。
 *
 * 分组列表（HomeScreen）与订阅源列表（SubscriptionScreen）共用同一条栏；
 * 订阅源只支持批量删除，把 [onEnable]/[onDisable] 传 null 即可隐藏那两个按钮。
 * 选择集在这里以快照方式读取，勾选一项不会重组整屏。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
internal fun MultiSelectActionsBar(
    selectedMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Boolean>,
    allIds: List<Long>,
    onExit: () -> Unit,
    onDelete: () -> Unit,
    onEnable: (() -> Unit)? = null,
    onDisable: (() -> Unit)? = null,
) {
    val selectedCount = selectedMap.size
    val isAllSelected = allIds.isNotEmpty() && selectedCount == allIds.size
    // 两行布局：第一行「已选 n/m」在左、「全选 / 退出」在右，第二行「启用 / 停用 /
    // 删除」。计数与操作分居两端（用户要求：叉号和全选移到右边），长语言下计数
    // 仍有整行宽度可用，不会被压缩成"…"（俄语实测过的老问题）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            // 左右留 8dp：窄屏上「已选 n/m」原来紧贴屏幕边缘（截图里看着像被裁掉）。
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.selection_count, selectedCount, allIds.size),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalAccentColor.current,
                textAlign = TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                onClick = {
                    if (isAllSelected) {
                        selectedMap.clear()
                    } else {
                        selectedMap.clear()
                        allIds.forEach { selectedMap[it] = true }
                    }
                }
            ) {
                Icon(
                    if (isAllSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (isAllSelected) R.string.selection_none else R.string.selection_all
                    ),
                    maxLines = 1
                )
            }
            IconButton(modifier = Modifier.size(40.dp), onClick = onExit) {
                Icon(
                    Icons.Filled.Close,
                    stringResource(R.string.selection_exit),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (selectedCount > 0) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (onEnable != null) {
                    Button(
                        onClick = onEnable,
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_enable), maxLines = 1)
                    }
                }
                if (onDisable != null) {
                    FilledTonalButton(
                        onClick = onDisable,
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            Icons.Filled.RemoveCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_disable), maxLines = 1)
                    }
                }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete), maxLines = 1)
                }
            }
        }
    }
}
