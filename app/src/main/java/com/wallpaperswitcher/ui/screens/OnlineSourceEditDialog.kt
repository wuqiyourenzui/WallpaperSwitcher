package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.OnlineSource
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.launch

/**
 * 网盘来源的「新建 / 编辑」对话框。
 *
 * 一个对话框同时承担两种来源：WebDAV（`http://IP:5244/dav`）和 OpenList
 * （`http://IP:5244` 走它自己的 HTTP API）。两者的输入项完全一样（地址 + 目录 +
 * 账号），只有地址填写方式和测试探针不同，所以用一个类型选择器切换，不拆两个页面。
 *
 * 密码留空 = 不修改（编辑已有来源时不必重输）；新建时留空表示匿名访问。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun OnlineSourceEditDialog(
    viewModel: WallpaperViewModel,
    /** null = 新建。 */
    source: OnlineSource?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editing = source != null
    var name by remember(source?.id) { mutableStateOf(source?.name.orEmpty()) }
    var baseUrl by remember(source?.id) {
        mutableStateOf(source?.webdavUrl?.ifBlank { null } ?: source?.url.orEmpty())
    }
    var path by remember(source?.id) { mutableStateOf(source?.webdavPath.orEmpty()) }
    var user by remember(source?.id) { mutableStateOf(source?.username.orEmpty()) }
    var password by remember(source?.id) { mutableStateOf("") }
    var interval by remember(source?.id) {
        mutableStateOf((source?.intervalMinutes ?: 60).toString())
    }
    var keep by remember(source?.id) {
        mutableStateOf((source?.keepCount ?: 30).toString())
    }
    var wifiOnly by remember(source?.id) { mutableStateOf(source?.wifiOnly ?: true) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (editing) stringResource(R.string.online_netdisk_edit_title)
                else stringResource(R.string.online_netdisk_add)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 只支持 WebDAV：OpenList 的专用 API 通道已下线（它的 /dav 端点就是
                // 标准 WebDAV，用同一套代码，少一条链路少一处坑）。
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.online_netdisk_url_webdav)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.online_netdisk_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.online_netdisk_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.online_netdisk_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.online_netdisk_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { v -> interval = v.filter { it.isDigit() } },
                        label = { Text(stringResource(R.string.online_netdisk_interval)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = keep,
                        onValueChange = { v -> keep = v.filter { it.isDigit() } },
                        label = { Text(stringResource(R.string.online_netdisk_keep)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = wifiOnly,
                        onClick = { wifiOnly = !wifiOnly },
                        label = { Text(stringResource(R.string.online_netdisk_wifi_only)) },
                    )

                }
                TextButton(
                    enabled = !testing && baseUrl.isNotBlank(),
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = viewModel.testOnlineSource(
                                baseUrl = baseUrl,
                                path = path,
                                username = user,
                                // 编辑时留空 = 用库里存的密文，测试要拿真密码
                                password = if (password.isEmpty() && source != null) {
                                    com.wallpaperswitcher.engine.OnlineSecretStore
                                        .decrypt(source.passwordCipher)
                                } else {
                                    password
                                },
                            )
                            testResult = if (result.ok) {
                                // 在协程里不能用 pluralStringResource（@Composable）：
                                // 直接走 resources.getQuantityString。
                                context.resources.getQuantityString(
                                    R.plurals.online_netdisk_test_ok,
                                    result.entries,
                                    result.entries,
                                )
                            } else {
                                context.getString(
                                    R.string.online_netdisk_test_fail, result.code
                                )
                            }
                            testing = false
                        }
                    },
                ) {
                    Text(
                        if (testing) stringResource(R.string.online_netdisk_testing)
                        else stringResource(R.string.online_netdisk_test)
                    )
                }
                testResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = baseUrl.isNotBlank() && !testing,
                onClick = {
                    scope.launch {
                        val id = viewModel.saveOnlineSource(
                            id = source?.id ?: 0L,
                            type = OnlineSource.TYPE_WEBDAV,
                            name = name,
                            baseUrl = baseUrl,
                            path = path,
                            username = user,
                            password = password,
                            intervalMinutes = interval.toIntOrNull() ?: 60,
                            keepCount = keep.toIntOrNull() ?: 30,
                            wifiOnly = wifiOnly,
                            chargingOnly = source?.chargingOnly ?: false,
                            enabled = source?.enabled ?: true,
                        )
                        if (id > 0L) onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (editing) {
                    TextButton(onClick = {
                        scope.launch {
                            viewModel.deleteOnlineSource(source!!.id)
                            onDismiss()
                        }
                    }) { Text(stringResource(R.string.action_delete)) }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
