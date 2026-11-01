package com.wallpaperswitcher.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.R
import com.wallpaperswitcher.engine.legado.RssSourceEditor
import com.wallpaperswitcher.ui.theme.HiLoadingState
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.launch

/**
 * 编辑页暴露给**应用顶栏**的动作。顶栏画「保存」和「⋮」，菜单项则以三点为锚点弹出；
 * 编辑页负责把每个动作注册进来（`LaunchedEffect` 里 `onActionsReady(...)`），
 * 离开时注册 null 清掉，避免顶栏点到已销毁页面的回调。
 */
class RssEditorActions(
    val save: () -> Unit,
    val hasLogin: () -> Boolean,
    val onLogin: () -> Unit,
    val debug: () -> Unit,
    val variables: () -> Unit,
    val clearCookie: () -> Unit,
    val copy: () -> Unit,
    val paste: () -> Unit,
    val share: () -> Unit,
)

/**
 * 订阅源编辑器，字段分组照抄 阅读 的 `RssSourceEditActivity`：**四个页签**
 * （基础 / 起始 / 列表 / WEB_VIEW）。保存与溢出菜单（登录 / 调试源 / 源变量 /
 * 清 Cookie / 复制 / 粘贴 / 分享）在**应用顶栏**，与阅读一致。
 *
 * 与阅读的差异（有意）：字段值直接编辑源 JSON（`rawJson`），未知字段原样保留，
 * 所以「其余字段」兜底区能把源里任何键都显示出来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssSourceEditScreen(
    viewModel: WallpaperViewModel,
    sourceId: Long,
    /** [saved] = 这次退出前是否保存成功（阅读据此重载列表）。 */
    onDone: (saved: Boolean) -> Unit,
    /** 菜单「登录」：打开该源的登录界面。 */
    onLogin: (Long) -> Unit = {},
    /** 把「保存 / 菜单」注册给应用顶栏；离开时用 null 注销。 */
    onActionsReady: (RssEditorActions?) -> Unit = {},
) {
    val sources by viewModel.rssSources.collectAsStateWithLifecycle()
    val source = sources.firstOrNull { it.id == sourceId }

    if (source == null) {
        HiLoadingState(
            text = stringResource(R.string.online_picker_loading),
            modifier = Modifier.fillMaxSize().padding(24.dp),
        )
        return
    }

    var name by remember(sourceId) { mutableStateOf(source.name) }
    var url by remember(sourceId) { mutableStateOf(source.url) }
    var sourceType by remember(sourceId) { mutableStateOf(source.type) }
    var articleStyle by remember(sourceId) { mutableStateOf(source.articleStyle) }
    var enabled by remember(sourceId) { mutableStateOf(source.enabled) }
    var cookieJar by remember(sourceId) {
        mutableStateOf(RssSourceEditor.fieldValue(source.rawJson, "enabledCookieJar") != "false")
    }
    var group by remember(sourceId) {
        mutableStateOf(RssSourceEditor.fieldValue(source.rawJson, "sourceGroup"))
    }
    val fields = remember(sourceId) { mutableStateMapOf<String, String>() }
    val labelled = remember {
        RssSourceEditor.TAB_BASE_FIELDS + RssSourceEditor.TAB_START_FIELDS +
            RssSourceEditor.TAB_LIST_FIELDS + RssSourceEditor.TAB_WEB_FIELDS +
            RssSourceEditor.WEB_BOOL_FIELDS
    }
    for (key in labelled) {
        if (!fields.containsKey(key)) fields[key] = RssSourceEditor.fieldValue(source.rawJson, key)
    }
    // 源 JSON 里其余的字段：平铺出来，保证编辑器能看到、能改到源的**全部**信息。
    var extraKeys by remember(sourceId) {
        mutableStateOf(RssSourceEditor.remainingKeys(source.rawJson))
    }
    for (key in extraKeys) {
        if (!fields.containsKey(key)) fields[key] = RssSourceEditor.fieldValue(source.rawJson, key)
    }
    var newFieldKey by remember(sourceId) { mutableStateOf("") }
    var rawMode by remember(sourceId) { mutableStateOf(false) }
    var rawText by remember(sourceId) { mutableStateOf(source.rawJson) }
    var error by remember(sourceId) { mutableStateOf<String?>(null) }
    var saving by remember(sourceId) { mutableStateOf(false) }
    var confirmDelete by remember(sourceId) { mutableStateOf(false) }
    var tab by remember(sourceId) { mutableStateOf(0) }
    // 全屏编辑：阅读把当前输入框的内容丢给一个整屏编辑器；这里用一个大号对话框。
    var fullscreenKey by remember { mutableStateOf<String?>(null) }
    var fullscreenText by remember { mutableStateOf("") }
    var variableDialog by remember { mutableStateOf(false) }
    // 阅读的「调试源」：抓一页、跑一遍规则，把结果摊开给用户看（不写库）。
    var debugOpen by remember { mutableStateOf(false) }
    var debugReport by remember { mutableStateOf<String?>(null) }
    var variableText by remember(sourceId) {
        mutableStateOf(RssSourceEditor.fieldValue(source.rawJson, "variable"))
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasLogin = remember(source.rawJson) {
        RssSourceEditor.fieldValue(source.rawJson, "loginUrl").isNotBlank()
    }

    val labelOf: (String) -> Int = { key ->
        when (key) {
            // 基础
            "sortUrl" -> R.string.rss_edit_sort_url
            "sourceIcon" -> R.string.rss_edit_icon
            "sourceComment" -> R.string.rss_edit_comment
            "searchUrl" -> R.string.rss_edit_search_url
            "coverDecodeJs" -> R.string.rss_edit_cover_decode
            "variableComment" -> R.string.rss_edit_variable_comment
            "concurrentRate" -> R.string.rss_edit_concurrent_rate
            // 起始
            "startHtml" -> R.string.rss_edit_start_html
            "startStyle" -> R.string.rss_edit_start_style
            "startJs" -> R.string.rss_edit_start_js
            "preloadJs" -> R.string.rss_edit_preload_js
            // 列表
            "ruleArticles" -> R.string.rss_edit_rule_articles
            "ruleNextPage" -> R.string.rss_edit_rule_next
            "ruleTitle" -> R.string.rss_edit_rule_title
            "ruleLink" -> R.string.rss_edit_rule_link
            "ruleImage" -> R.string.rss_edit_rule_image
            "ruleDescription" -> R.string.rss_edit_rule_description
            "rulePubDate" -> R.string.rss_edit_rule_pubdate
            // WEB_VIEW
            "ruleContent" -> R.string.rss_edit_rule_content
            "style" -> R.string.rss_edit_style
            "injectJs" -> R.string.rss_edit_inject_js
            "contentWhitelist" -> R.string.rss_edit_whitelist
            "contentBlacklist" -> R.string.rss_edit_blacklist
            "shouldOverrideUrlLoading" -> R.string.rss_edit_override_url
            "enableJs" -> R.string.rss_edit_enable_js
            "loadWithBaseUrl" -> R.string.rss_edit_load_base
            "showWebLog" -> R.string.rss_edit_show_web_log
            "cacheFirst" -> R.string.rss_edit_cache_first
            // 登录 / 其它
            "loginUrl" -> R.string.rss_edit_login_url
            "loginUi" -> R.string.rss_edit_login_ui
            "loginCheckJs" -> R.string.rss_edit_login_check
            "jsLib" -> R.string.rss_edit_js_lib
            "variable" -> R.string.rss_edit_variable_field
            "header" -> R.string.rss_edit_header
            else -> R.string.rss_edit_other
        }
    }

    /** 保存：把当前表单（含页签字段）写回源。返回错误信息，null = 成功。 */
    suspend fun save(): String? {
        val changes = HashMap<String, String?>()
        for ((key, value) in fields) changes[key] = value
        changes["sourceGroup"] = group
        changes["enabledCookieJar"] = if (cookieJar) "true" else "false"
        changes["articleStyle"] = articleStyle.toString()
        return viewModel.rssSourceSave(
            source = source,
            name = name,
            url = url,
            type = sourceType,
            enabled = enabled,
            changes = changes,
            rawOverride = if (rawMode) rawText else null,
        )
    }

    fun copyToClipboard() {
        val text = if (rawMode) rawText else source.rawJson
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("rssSource", text))
        viewModel.showToast(context.getString(R.string.rss_edit_copied))
    }

    fun shareSource() {
        val text = if (rawMode) rawText else source.rawJson
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }

    /**
     * 粘贴整份源 JSON：填进各字段并切到原始 JSON 模式。
     * 不是源 JSON 时报一条错（不覆盖当前编辑内容）。
     */
    fun pasteFromClipboard() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = manager?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        val pasted = com.wallpaperswitcher.engine.LegadoImport.parse(text).sources.firstOrNull()
        if (pasted == null) {
            error = context.getString(R.string.rss_edit_paste_invalid)
            return
        }
        rawText = pasted.rawJson
        rawMode = true
        for (key in labelled + extraKeys) {
            fields[key] = RssSourceEditor.fieldValue(pasted.rawJson, key)
        }
        name = pasted.name.ifBlank { name }
        url = pasted.url.ifBlank { url }
        articleStyle = pasted.articleStyle
    }

        Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏由调用方（WallpaperSwitcherApp）提供，这里注册动作给它，
        // 自己不再画标题/保存/菜单那一行。
        LaunchedEffect(sourceId, hasLogin, labelled.size) {
            onActionsReady(
                RssEditorActions(
                    save = {
                        saving = true
                        error = null
                        scope.launch {
                            val message = save()
                            saving = false
                            if (message == null) onDone(true) else error = message
                        }
                    },
                    hasLogin = { hasLogin },
                    onLogin = {
                        scope.launch {
                            // 阅读也是先 save 再 startActivity。
                            save()
                            onLogin(sourceId)
                        }
                    },
                    debug = {
                        debugOpen = true
                        debugReport = null
                        scope.launch {
                            // 先保存，调试的是用户当前看到的规则。
                            save()
                            debugReport = viewModel.rssDebugSource(source)
                        }
                    },
                    variables = { variableDialog = true },
                    clearCookie = {
                        scope.launch {
                            viewModel.rssClearCookie(source)
                            viewModel.showToast(
                                context.getString(R.string.rss_edit_cookie_cleared)
                            )
                        }
                    },
                    copy = { copyToClipboard() },
                    paste = { pasteFromClipboard() },
                    share = { shareSource() },
                )
            )
        }
        DisposableEffect(sourceId) {
            onDispose { onActionsReady(null) }
        }

        // —— 四个页签（阅读：基础 / 起始 / 列表 / WEB_VIEW）——
        val tabs = listOf(
            R.string.rss_edit_tab_base,
            R.string.rss_edit_tab_start,
            R.string.rss_edit_tab_list,
            R.string.rss_edit_tab_web,
        )
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(title)) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (tab) {
                0 -> {
                    // ===== 基础 =====
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.rss_edit_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.rss_edit_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = group,
                        onValueChange = { group = it },
                        label = { Text(stringResource(R.string.rss_edit_group)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SectionTitle(stringResource(R.string.rss_edit_type))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for ((value, labelRes) in listOf(
                            0 to R.string.rss_edit_type_web,
                            1 to R.string.rss_edit_type_image,
                            2 to R.string.rss_edit_type_video,
                        )) {
                            FilterChip(
                                selected = sourceType == value,
                                onClick = { sourceType = value },
                                label = { Text(stringResource(labelRes)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    // 列表排版：与顶栏「切换布局」的循环同一套取值（1 列表 / 2 两列 /
                    // 3 瀑布流 / 4 三列）。0 是「没设过」的旧值，也留一格，
                    // 否则没选中的 chips 会在保存时把用户原来的 0 偷偷改成别的。
                    SectionTitle(stringResource(R.string.rss_edit_style_label))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for ((value, labelRes) in listOf(
                            0 to R.string.rss_style_none,
                            1 to R.string.rss_style_list,
                            2 to R.string.rss_style_grid2,
                            3 to R.string.rss_style_staggered,
                            4 to R.string.rss_style_grid3,
                        )) {
                            FilterChip(
                                selected = articleStyle == value,
                                onClick = { articleStyle = value },
                                label = { Text(stringResource(labelRes)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.rss_edit_enabled),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.rss_edit_cookie_jar),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = cookieJar, onCheckedChange = { cookieJar = it })
                    }
                    Divider()
                    for (key in RssSourceEditor.TAB_BASE_FIELDS) {
                        if (key == "sourceName" || key == "sourceUrl" || key == "sourceGroup") {
                            continue
                        }
                        FieldEditor(
                            labelRes = labelOf(key),
                            value = fields[key].orEmpty(),
                            onChange = { fields[key] = it },
                            onFullscreen = {
                                fullscreenKey = key
                                fullscreenText = fields[key].orEmpty()
                            },
                        )
                    }
                }

                1 -> {
                    // ===== 起始 =====
                    for (key in RssSourceEditor.TAB_START_FIELDS) {
                        FieldEditor(
                            labelRes = labelOf(key),
                            value = fields[key].orEmpty(),
                            onChange = { fields[key] = it },
                            onFullscreen = {
                                fullscreenKey = key
                                fullscreenText = fields[key].orEmpty()
                            },
                        )
                    }
                }

                2 -> {
                    // ===== 列表 =====
                    for (key in RssSourceEditor.TAB_LIST_FIELDS) {
                        FieldEditor(
                            labelRes = labelOf(key),
                            value = fields[key].orEmpty(),
                            onChange = { fields[key] = it },
                            onFullscreen = {
                                fullscreenKey = key
                                fullscreenText = fields[key].orEmpty()
                            },
                        )
                    }
                }

                else -> {
                    // ===== WEB_VIEW =====
                    for (key in RssSourceEditor.WEB_BOOL_FIELDS) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(labelOf(key)),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = fields[key].orEmpty()
                                    .equals("true", ignoreCase = true),
                                onCheckedChange = { fields[key] = if (it) "true" else "false" }
                            )
                        }
                    }
                    Divider()
                    for (key in RssSourceEditor.TAB_WEB_FIELDS) {
                        FieldEditor(
                            labelRes = labelOf(key),
                            value = fields[key].orEmpty(),
                            onChange = { fields[key] = it },
                            onFullscreen = {
                                fullscreenKey = key
                                fullscreenText = fields[key].orEmpty()
                            },
                        )
                    }
                }
            }

            Divider()
            SectionTitle(stringResource(R.string.rss_edit_all_fields))
            Text(
                stringResource(R.string.rss_edit_all_fields_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (key in extraKeys) {
                val value = fields[key].orEmpty()
                OutlinedTextField(
                    value = value,
                    onValueChange = { fields[key] = it },
                    label = { Text(key) },
                    minLines = if (value.length > 60) 3 else 1,
                    singleLine = value.length <= 60,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newFieldKey,
                    onValueChange = { newFieldKey = it },
                    label = { Text(stringResource(R.string.rss_edit_field_key)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = newFieldKey.isNotBlank() && newFieldKey !in extraKeys,
                    onClick = {
                        val key = newFieldKey.trim()
                        if (key.isNotEmpty()) {
                            extraKeys = extraKeys + key
                            fields[key] = ""
                            newFieldKey = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.rss_edit_add_field))
                }
            }

            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.rss_edit_raw_mode),
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = rawMode, onCheckedChange = { rawMode = it })
            }
            if (rawMode) {
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text(stringResource(R.string.rss_edit_raw)) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 表单底部不再放「保存 / 取消」：保存已经在顶栏（阅读也在菜单栏），
            // 取消 = 顶栏返回箭头。底部再放一个就是第二处保存。
            Divider()
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.rss_delete_title),
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    fullscreenKey?.let { key ->
        AlertDialog(
            onDismissRequest = { fullscreenKey = null },
            title = {
                Text(
                    stringResource(R.string.rss_edit_fullscreen_title) + " · " +
                        stringResource(labelOf(key))
                )
            },
            text = {
                OutlinedTextField(
                    value = fullscreenText,
                    onValueChange = { fullscreenText = it },
                    minLines = 10,
                    maxLines = 16,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    fields[key] = fullscreenText
                    fullscreenKey = null
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { fullscreenKey = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (debugOpen) {
        AlertDialog(
            onDismissRequest = { debugOpen = false },
            title = { Text(stringResource(R.string.rss_debug_title)) },
            text = {
                val report = debugReport
                if (report == null) {
                    Text(
                        stringResource(R.string.rss_debug_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            report,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { debugOpen = false }) {
                    Text(stringResource(R.string.rss_debug_close))
                }
            },
            dismissButton = {
                debugReport?.let { report ->
                    TextButton(onClick = {
                        val manager =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        manager?.setPrimaryClip(ClipData.newPlainText("rssDebug", report))
                        viewModel.showToast(context.getString(R.string.rss_debug_copied))
                    }) {
                        Text(stringResource(R.string.rss_debug_copy))
                    }
                }
            }
        )
    }

    if (variableDialog) {
        val variableComment = RssSourceEditor.fieldValue(source.rawJson, "variableComment")
        AlertDialog(
            onDismissRequest = { variableDialog = false },
            title = { Text(stringResource(R.string.rss_edit_variable)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (variableComment.isBlank()) {
                            stringResource(R.string.rss_edit_variable_hint)
                        } else {
                            "$variableComment\n${stringResource(R.string.rss_edit_variable_hint)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = variableText,
                        onValueChange = { variableText = it },
                        label = { Text(stringResource(R.string.rss_edit_variable)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    fields["variable"] = variableText
                    variableDialog = false
                    scope.launch {
                        // 阅读是「保存 + setVariable」：这里同样落到源 JSON 的 variable。
                        val message = save()
                        if (message == null) {
                            viewModel.showToast(context.getString(R.string.rss_edit_variable_saved))
                        } else {
                            error = message
                        }
                    }
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { variableDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.rss_delete_title)) },
            text = { Text(stringResource(R.string.rss_delete_message, source.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteRssSource(source)
                    // 源已经删了，不需要（也不该）再触发列表重载。
                    onDone(false)
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun FieldEditor(
    labelRes: Int,
    value: String,
    onChange: (String) -> Unit,
    onFullscreen: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(labelRes)) },
            minLines = 1,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        if (onFullscreen != null && value.length > 40) {
            TextButton(onClick = onFullscreen) {
                Text(
                    stringResource(R.string.rss_edit_fullscreen),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
