package com.wallpaperswitcher.ui.screens

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Full editor for an imported 阅读 (Legado) subscription source, mirroring
 * Legado's source editor: every field the fetching/reading path understands can
 * be changed, and the raw JSON stays editable for anything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssSourceEditScreen(
    viewModel: WallpaperViewModel,
    sourceId: Long,
    onDone: () -> Unit,
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
    var type by remember(sourceId) { mutableStateOf(source.type) }
    var enabled by remember(sourceId) { mutableStateOf(source.enabled) }
    var cookieJar by remember(sourceId) {
        mutableStateOf(RssSourceEditor.fieldValue(source.rawJson, "enabledCookieJar") != "false")
    }
    var group by remember(sourceId) {
        mutableStateOf(RssSourceEditor.fieldValue(source.rawJson, "sourceGroup"))
    }
    val fields = remember(sourceId) { mutableStateMapOf<String, String>() }
    for (key in RssSourceEditor.RULE_FIELDS + RssSourceEditor.LOGIN_FIELDS + RssSourceEditor.OTHER_FIELDS) {
        if (!fields.containsKey(key)) fields[key] = RssSourceEditor.fieldValue(source.rawJson, key)
    }
    // 源 JSON 里其余的字段（enableJs / sourceIcon / style / …）：平铺出来，
    // 保证编辑器能看到、能改到源的**全部**信息。
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
    val scope = rememberCoroutineScope()

    val labelOf: (String) -> Int = { key ->
        when (key) {
            "sortUrl" -> R.string.rss_edit_sort_url
            "ruleArticles" -> R.string.rss_edit_rule_articles
            "ruleNextPage" -> R.string.rss_edit_rule_next
            "ruleTitle" -> R.string.rss_edit_rule_title
            "ruleLink" -> R.string.rss_edit_rule_link
            "ruleImage" -> R.string.rss_edit_rule_image
            "ruleDescription" -> R.string.rss_edit_rule_description
            "rulePubDate" -> R.string.rss_edit_rule_pubdate
            "ruleContent" -> R.string.rss_edit_rule_content
            "loginUrl" -> R.string.rss_edit_login_url
            "loginUi" -> R.string.rss_edit_login_ui
            "loginCheckJs" -> R.string.rss_edit_login_check
            "jsLib" -> R.string.rss_edit_js_lib
            "variable" -> R.string.rss_edit_variable
            "header" -> R.string.rss_edit_header
            else -> R.string.rss_edit_other
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(stringResource(R.string.rss_edit_basic))
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
        Text(stringResource(R.string.rss_edit_type), style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            for ((value, labelRes) in listOf(
                0 to R.string.rss_edit_type_web,
                1 to R.string.rss_edit_type_image,
                2 to R.string.rss_edit_type_video,
            )) {
                FilterChip(
                    selected = type == value,
                    onClick = { type = value },
                    label = { Text(stringResource(labelRes)) }
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
        SectionTitle(stringResource(R.string.rss_edit_rules))
        for (key in RssSourceEditor.RULE_FIELDS) {
            FieldEditor(
                labelRes = labelOf(key),
                value = fields[key].orEmpty(),
                onChange = { fields[key] = it },
            )
        }

        Divider()
        SectionTitle(stringResource(R.string.rss_edit_login))
        for (key in RssSourceEditor.LOGIN_FIELDS) {
            FieldEditor(
                labelRes = labelOf(key),
                value = fields[key].orEmpty(),
                onChange = { fields[key] = it },
            )
        }

        Divider()
        SectionTitle(stringResource(R.string.rss_edit_other))
        for (key in RssSourceEditor.OTHER_FIELDS.filter { it != "header" }) {
            FieldEditor(
                labelRes = labelOf(key),
                value = fields[key].orEmpty(),
                onChange = { fields[key] = it },
            )
        }
        FieldEditor(
            labelRes = labelOf("header"),
            value = fields["header"].orEmpty(),
            onChange = { fields["header"] = it },
        )

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    error = null
                    val changes = HashMap<String, String?>()
                    for ((key, value) in fields) changes[key] = value
                    changes["sourceGroup"] = group
                    // Write the flag explicitly: leaving it untouched kept a
                    // stored `false` even after the user switched it on.
                    changes["enabledCookieJar"] = if (cookieJar) "true" else "false"
                    scope.launch {
                        val message = viewModel.rssSourceSave(
                            source = source,
                            name = name,
                            url = url,
                            type = type,
                            enabled = enabled,
                            changes = changes,
                            rawOverride = if (rawMode) rawText else null,
                        )
                        saving = false
                        if (message == null) onDone() else error = message
                    }
                }
            ) {
                Text(stringResource(R.string.rss_edit_save))
            }
        }

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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.rss_delete_title)) },
            text = { Text(stringResource(R.string.rss_delete_message, source.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteRssSource(source)
                    onDone()
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
private fun FieldEditor(labelRes: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        minLines = 1,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
}
