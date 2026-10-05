package com.wallpaperswitcher.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.engine.FloatingButtonContentPolicy
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.ui.theme.parseHexColor
import com.wallpaperswitcher.ui.theme.ThemeMode
import androidx.compose.ui.res.stringResource
import com.wallpaperswitcher.R
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import java.io.File
import kotlinx.coroutines.launch
import com.wallpaperswitcher.ui.theme.LocalAccentColor

/**
 * Preset colors for the floating switch button, as hex + string RESOURCE (the
 * names are translated, and a top-level list cannot call stringResource).
 */
internal val floatingButtonColors = listOf(
    "#1E88E5" to R.string.button_color_blue,
    "#43A047" to R.string.button_color_green,
    "#E53935" to R.string.button_color_red,
    "#FB8C00" to R.string.button_color_orange,
    "#8E24AA" to R.string.button_color_purple,
    "#00ACC1" to R.string.button_color_cyan,
    "#D81B60" to R.string.button_color_pink,
    "#FFFFFF" to R.string.button_color_white,
    "#212121" to R.string.button_color_black,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: WallpaperViewModel,
    /** 打开设置里的子页面（壁纸设置 / 切换方式 / 悬浮按钮 / 文件夹扫描 / 外观 /
     *  收藏 / 最近显示 / 存储）。 */
    onOpenScreen: (com.wallpaperswitcher.ui.Screen) -> Unit = {},
    /**
     * 滚动状态由调用方持有：进子页面再返回时这一页会被重建，状态放在这里才不会
     * 每次都滚回顶部（和订阅列表的 `listState` 同一个理由）。
     */
    scrollState: androidx.compose.foundation.ScrollState =
        androidx.compose.foundation.rememberScrollState(),
) {
    // Single combined state (see settingsUiState): entering this screen used
    // to subscribe to 13 separate Room-backed flows, each emitting on the main
    // thread at its own time -> up to 13 full-screen recompositions while the
    // tab transition animation was still running (the 首页↔设置 stutter). One
    // combined flow = one emission = one recomposition per settings change.
    val settingsUiState by viewModel.settingsUiState.collectAsStateWithLifecycle()
    // 场景规则
    val scenePauseOnPowerSave = settingsUiState.scenePauseOnPowerSave
    val scenePauseOnLowBattery = settingsUiState.scenePauseOnLowBattery
    // 订阅下载目录：默认应用私有目录，也可以选相册/文件管理器可见的文件夹。
    var rssDownloadDir by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { rssDownloadDir = viewModel.rssDownloadDirValue() }
    val rssDirPickerContext = LocalContext.current
    val rssDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            com.wallpaperswitcher.engine.RssDownloadDir.persist(rssDirPickerContext, uri)
            val value = uri.toString()
            rssDownloadDir = value
            viewModel.setRssDownloadDir(value)
        }
    }

    val serviceEnabled = settingsUiState.serviceEnabled
    val doubleTapEnabled = settingsUiState.doubleTapEnabled
    val unlockSwitchEnabled = settingsUiState.unlockSwitchEnabled
    val floatingButtonColor = settingsUiState.floatingButtonColor
    val floatingButtonAlpha = settingsUiState.floatingButtonAlpha
    val floatingButtonText = settingsUiState.floatingButtonText
    val floatingButtonImageUri = settingsUiState.floatingButtonImageUri
    val globalIntervalMs = settingsUiState.globalIntervalMs
    val globalSwitchMode = settingsUiState.globalSwitchMode
    val globalScaleMode = settingsUiState.globalScaleMode
    val clarityMode = settingsUiState.clarityMode
    val switchFadeEnabled = settingsUiState.switchFadeEnabled
    val videoSoundEnabled = settingsUiState.videoSoundEnabled
    val rotateMismatchEnabled = settingsUiState.rotateMismatchEnabled
    val rotateMismatchClockwise = settingsUiState.rotateMismatchClockwise
    val lockTimerEnabled = settingsUiState.lockTimerEnabled
    val lockIntervalMs = settingsUiState.lockIntervalMs
    val themeColor = settingsUiState.themeColor
    val themeMode = settingsUiState.themeMode
    val autoScanEnabled = settingsUiState.autoScanEnabled
    val autoScanIntervalMs = settingsUiState.autoScanIntervalMs
    val autoScanLastRunAt = settingsUiState.autoScanLastRunAt

    var showIntervalDialog by remember { mutableStateOf(false) }
    var showLockIntervalDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    // Its own collect: the root (MainActivity) is what applies the locale - this
    // screen only has to show which one is active.
    val localeTag by viewModel.locale.collectAsStateWithLifecycle()
    // Only used to recreate the Activity when the language changes.
    val languageContext = androidx.compose.ui.platform.LocalContext.current
    // Free colour choice for the floating button (hue x tone grid + translucency).
    var showButtonColorDialog by remember { mutableStateOf(false) }
    var showAutoScanIntervalDialog by remember { mutableStateOf(false) }
    // Custom picture for the floating button. OpenDocument (not the photo
    // picker) because only its URI can be PERSISTED: the button lives in the
    // wallpaper service and must still find the picture after a reboot.
    val floatingImageContext = LocalContext.current
    val floatingImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                floatingImageContext.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some providers do not offer persistable grants; the picture
                // still works for this session (the button falls back to the
                // text label when it cannot be read).
            }
            viewModel.setFloatingButtonImageUri(uri.toString())
        }
    }

    Column(
        modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scrollState)
        .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Global wallpaper settings
        // ==== 设置主界面 = 入口列表 ====
        // 每一项设置进自己的页面（见 SettingsPageScaffold）：主界面一眼看完，不用
        // 滚过上千行；每个设置项也各有自己的返回栈位置。
        SettingsSection(title = stringResource(R.string.title_settings)) {
            SettingsPageEntry(
                icon = Icons.Outlined.Wallpaper,
                title = stringResource(R.string.settings_page_wallpaper),
                subtitle = stringResource(R.string.settings_page_wallpaper_desc),
                onClick = { onOpenScreen(com.wallpaperswitcher.ui.Screen.WallpaperSettings) },
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsPageEntry(
                icon = Icons.Outlined.SwapVert,
                title = stringResource(R.string.settings_page_switch),
                subtitle = stringResource(R.string.settings_page_switch_desc),
                onClick = { onOpenScreen(com.wallpaperswitcher.ui.Screen.SwitchMethods) },
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsPageEntry(
                icon = Icons.Outlined.AdsClick,
                title = stringResource(R.string.settings_page_button),
                subtitle = stringResource(R.string.settings_page_button_desc),
                onClick = { onOpenScreen(com.wallpaperswitcher.ui.Screen.ButtonAppearance) },
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsPageEntry(
                icon = Icons.Outlined.FolderOpen,
                title = stringResource(R.string.settings_page_scan),
                subtitle = stringResource(R.string.settings_page_scan_desc),
                onClick = { onOpenScreen(com.wallpaperswitcher.ui.Screen.FolderScan) },
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsPageEntry(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_page_appearance),
                subtitle = stringResource(R.string.settings_page_appearance_desc),
                onClick = { onOpenScreen(com.wallpaperswitcher.ui.Screen.Appearance) },
            )
            // 收藏 / 最近显示 / 存储与流量原先只有页面、没有入口（只能从大图浏览等
            // 路径绕进去），统一挂到「外观」下面，设置页就是全部页面的索引。
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsPageEntry(
                icon = Icons.Outlined.FavoriteBorder,
                title = stringResource(R.string.favorites_title),
                subtitle = stringResource(R.string.settings_page_favorites_desc),
                onClick = { onOpenScreen(com.wallpaperswitcher.ui.Screen.Favorites) },
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsPageEntry(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.recent_title),
                subtitle = stringResource(R.string.settings_page_recent_desc),
                onClick = { onOpenScreen(com.wallpaperswitcher.ui.Screen.Recent) },
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsPageEntry(
                icon = Icons.Outlined.Storage,
                title = stringResource(R.string.storage_title),
                subtitle = stringResource(R.string.settings_page_storage_desc),
                onClick = { onOpenScreen(com.wallpaperswitcher.ui.Screen.Storage) },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 订阅下载目录：原来混在「文件自动扫描」里（与扫描无关），单独成组。
        SettingsSection(title = stringResource(R.string.settings_rss_download_dir)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { rssDirPicker.launch(null) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_rss_download_dir),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        if (rssDownloadDir.isBlank()) {
                            stringResource(R.string.settings_rss_download_dir_default)
                        } else {
                            com.wallpaperswitcher.engine.RssDownloadDir
                                .displayName(rssDownloadDir)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    stringResource(R.string.action_modify),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalAccentColor.current
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        SettingsSection(title = stringResource(R.string.settings_scene_rules)) {
            SettingsSwitchItem(
                icon = Icons.Outlined.BatterySaver,
                title = stringResource(R.string.settings_scene_power_save),
                subtitle = stringResource(R.string.settings_scene_hint),
                checked = scenePauseOnPowerSave,
                onCheckedChange = { viewModel.setScenePauseOnPowerSave(it) }
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSwitchItem(
                icon = Icons.Outlined.BatteryAlert,
                title = stringResource(R.string.settings_scene_low_battery),
                subtitle = stringResource(R.string.settings_scene_hint),
                checked = scenePauseOnLowBattery,
                onCheckedChange = { viewModel.setScenePauseOnLowBattery(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 配置导出 / 导入：分组 + 壁纸设置一次性导出成一个 JSON 文件。
        // 换机、重装、或者只是想留个备份时用（导入是"新增"语义，
        // 不会删掉现有的分组 —— 见 ConfigBackup.apply）。
        SettingsSection(title = stringResource(R.string.settings_config_backup)) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            val exportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/json")
            ) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch {
                    try {
                        val text = viewModel.exportConfigText()
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(text.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } ?: throw IllegalStateException("no output stream")
                        Toast.makeText(
                            context, R.string.toast_config_exported, Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Throwable) {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.toast_config_export_failed, e.message.orEmpty()
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        busy = false
                    }
                }
            }
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch {
                    try {
                        val text = context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes().toString(Charsets.UTF_8)
                        } ?: throw IllegalStateException("no input stream")
                        val result = viewModel.importConfigText(text)
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.toast_config_imported, result.groups, result.sources
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Throwable) {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.toast_config_import_failed, e.message.orEmpty()
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        busy = false
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) {
                        busy = true
                        exportLauncher.launch("wallpaper-switcher-config.json")
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Upload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    stringResource(R.string.settings_export_config),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) {
                        busy = true
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    stringResource(R.string.settings_import_config),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        SettingsSection(title = stringResource(R.string.settings_section_logs)) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val scope = rememberCoroutineScope()
            var exporting by remember { mutableStateOf(false) }
            // stringResource(R.string.action_save_to_phone): SAF create-document so the user picks the folder
            // (Downloads/Documents/...); no storage permission is required.
            val saveLogLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("text/plain")
            ) { uri: android.net.Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch {
                    try {
                        // Read the prepared report back (memory or cache file):
                        // the picker can outlive this composable, and writing an
                        // empty document used to leave a 0-byte log file.
                        val text = com.wallpaperswitcher.util.AppLog.readPendingExport(context)
                        if (text.isNullOrBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.toast_log_empty),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        val stream = context.contentResolver.openOutputStream(uri)
                        if (stream == null) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.toast_log_no_write),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        stream.use { out ->
                            out.write(bytes)
                            out.flush()
                        }
                        com.wallpaperswitcher.util.AppLog.clearPendingExport()
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.toast_log_saved, bytes.size / 1024),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.toast_save_failed, t.message.orEmpty()),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Description,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.settings_export_logs), modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_export_logs_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                // FlowRow: with Russian labels ("Экспорт и отправка" 628px +
                // "Очистить журнал") both buttons no longer fit on one 400dp
                // line, and the second one's label wrapped into a 134x240
                // single-column block inside its button. Wrapping the BUTTONS
                // instead keeps every label on one line.
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        enabled = !exporting,
                        onClick = {
                            exporting = true
                            scope.launch {
                                try {
                                    val header = viewModel.buildLogReportHeader()
                                    val file = com.wallpaperswitcher.util.AppLog.buildReport(context, header)
                                    shareLogFile(context, file)
                                } catch (t: Throwable) {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.toast_log_export_failed,
                                            t.message.orEmpty()
                                        ),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    exporting = false
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (exporting) stringResource(R.string.action_exporting) else stringResource(R.string.action_export_share))
                    }
                    OutlinedButton(
                        onClick = {
                            com.wallpaperswitcher.util.AppLog.clear()
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.toast_log_cleared),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Icon(Icons.Filled.DeleteSweep, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_clear_logs))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !exporting,
                    onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val header = viewModel.buildLogReportHeader()
                                // Prepared (and cached) BEFORE the picker opens:
                                // the callback no longer depends on this
                                // composable still holding the text.
                                val fileName = com.wallpaperswitcher.util.AppLog
                                .prepareExport(context, header)
                                saveLogLauncher.launch(fileName)
                            } catch (t: Throwable) {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.toast_log_build_failed,
                                        t.message.orEmpty()
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                exporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_save_to_phone))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Usage guide
        SettingsSection(title = stringResource(R.string.settings_section_guide)) {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.guide_title),
                subtitle = buildString {
                    // Describes the CURRENT flow: groups are filled from the
                    // folder picker, a group's 应用位置 decides which screen it
                    // feeds, and tapping a picture (not the ⋮ menu) is what sets
                    // it - the system dialog then asks for 主屏幕 / 两者.
                    appendLine(stringResource(R.string.guide_step_1))
                    appendLine(stringResource(R.string.guide_step_2))
                    appendLine(stringResource(R.string.guide_step_3))
                    appendLine(stringResource(R.string.guide_step_4))
                }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsInfoItem(
                icon = Icons.Outlined.Battery1Bar,
                title = stringResource(R.string.settings_section_power),
                subtitle = stringResource(R.string.settings_power_hint)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // About
        SettingsSection(title = stringResource(R.string.settings_section_about)) {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.about_version),
                subtitle = stringResource(R.string.about_desc)
            )
        }
        Spacer(modifier = Modifier.height(80.dp))


        // Interval picker dialog
        if (showIntervalDialog) {
            IntervalPickerDialog(
                currentMs = globalIntervalMs,
                onDismiss = { showIntervalDialog = false },
                onSelect = { viewModel.setGlobalInterval(it); showIntervalDialog = false }
            )
        }

        if (showLockIntervalDialog) {
            IntervalPickerDialog(
                currentMs = lockIntervalMs,
                onDismiss = { showLockIntervalDialog = false },
                onSelect = { viewModel.setLockInterval(it); showLockIntervalDialog = false }
            )
        }

        if (showColorDialog) {
            ThemeColorPickerDialog(
                currentHex = themeColor,
                onDismiss = { showColorDialog = false },
                onSelect = { viewModel.setThemeColor(it); showColorDialog = false }
            )
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(
                currentTag = localeTag,
                onDismiss = { showLanguageDialog = false },
                onSelect = { tag ->
                    // Synchronous mirror FIRST: the Activity is recreated below,
                    // and its attachBaseContext must already see the new tag (the
                    // database write is asynchronous).
                    AppLocale.store(languageContext, tag)
                    viewModel.setLocale(tag)
                    showLanguageDialog = false
                    // The locale is applied in attachBaseContext, so the Activity
                    // has to be recreated for it to take effect (the standard
                    // Android behaviour for an in-app language switch).
                    (languageContext as? android.app.Activity)?.recreate()
                }
            )
        }

        if (showButtonColorDialog) {
            // Same picker as the theme colour, plus the translucency slider that
            // drives the button's rest opacity (the 透明度 slider above shows the
            // same setting, so either control keeps the other in sync).
            ColorGridPickerDialog(
                title = stringResource(R.string.settings_button_color),
                currentHex = floatingButtonColor,
                withAlpha = true,
                alphaPercent = floatingButtonAlpha,
                // Same floor the setter clamps to, so the slider cannot display a
                // value that will not be stored (it ran 0..100 against MIN = 5).
                alphaMinPercent =
                com.wallpaperswitcher.data.SettingsKeys.FLOATING_BUTTON_ALPHA_MIN,
                onConfirmAlpha = { viewModel.setFloatingButtonAlpha(it) },
                onConfirm = { viewModel.setFloatingButtonColor(it) },
                onDismiss = { showButtonColorDialog = false }
            )
        }

        if (showAutoScanIntervalDialog) {
            AutoScanIntervalDialog(
                currentMs = autoScanIntervalMs,
                onDismiss = { showAutoScanIntervalDialog = false },
                onSelect = { ms ->
                    viewModel.toggleAutoScan(autoScanEnabled, ms)
                    showAutoScanIntervalDialog = false
                }
            )
        }
    }
}



/** 设置里"点一下进子页 / 触发一个动作"的条目（图标 + 标题 + 可选副标题 + 箭头）。 */
@Composable
fun SettingsClickableItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
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
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 设置主界面里"点一下进子页"的一行：图标 + 标题 + 副标题 + 箭头。 */
@Composable
private fun SettingsPageEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AutoScanIntervalDialog(
    currentMs: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    val options = listOf(
        1L * 60 * 60 * 1000 to stringResource(R.string.duration_1h),
        6L * 60 * 60 * 1000 to stringResource(R.string.duration_6h),
        12L * 60 * 60 * 1000 to stringResource(R.string.duration_12h),
        24L * 60 * 60 * 1000 to stringResource(R.string.duration_24h)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_scan_interval)) },
        text = {
            Column {
                options.forEach { (ms, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(ms) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentMs == ms, onClick = { onSelect(ms) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
                Text(
                    stringResource(R.string.settings_auto_scan_interval_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 标题前的短强调条
            Box(
                modifier = Modifier
                .size(width = 4.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(LocalAccentColor.current)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = LocalAccentColor.current,
                fontWeight = FontWeight.SemiBold
            )
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
        .fillMaxWidth()
        .clickable { onCheckedChange(!checked) }
        // 12dp instead of 16: the rows were ~10% taller than they needed to
        // be, and this screen has ~25 of them (a full extra screen of
        // scrolling). Icons and touch targets are unchanged.
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsInfoItem(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight
            )
        }
    }
}

@Composable
fun IntervalPickerDialog(
    currentMs: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    val options = listOf(
        10_000L to stringResource(R.string.duration_10s),
        30_000L to stringResource(R.string.duration_30s),
        60_000L to stringResource(R.string.duration_1m),
        300_000L to stringResource(R.string.duration_5m),
        900_000L to stringResource(R.string.duration_15m),
        1800_000L to stringResource(R.string.duration_30m),
        3600_000L to stringResource(R.string.duration_1h),
        7200_000L to stringResource(R.string.duration_2h),
        21600_000L to stringResource(R.string.duration_6h),
        43200_000L to stringResource(R.string.duration_12h),
        86400_000L to stringResource(R.string.duration_24h)
    )
    var customValue by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_switch_interval)) },
        text = {
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                options.forEach { (ms, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(ms) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentMs == ms, onClick = { onSelect(ms) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
                Divider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                // Custom input
                Text(
                    stringResource(R.string.interval_custom),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalAccentColor.current,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customValue,
                        // ASCII digits only: isDigit() also accepts non-ASCII digits
                        // (e.g. Arabic-Indic), which toLongOrNull() then rejects.
                        onValueChange = { customValue = it.filter { c -> c in '0'..'9' } },
                        label = { Text(stringResource(R.string.interval_seconds_label)) },
                        placeholder = { Text(stringResource(R.string.interval_seconds_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val seconds = customValue.toLongOrNull() ?: 0L
                            if (seconds >= 10) {
                                onSelect(seconds * 1000L)
                            }
                        },
                        enabled = (customValue.toLongOrNull() ?: 0L) >= 10
                    ) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
                Text(
                    stringResource(R.string.interval_min_10s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/**
 * Option chip used by [SettingsChoiceRow].
 *
 * The label is single-line: a chip that has to wrap its own text renders the
 * second line outside the pill (which is the "有些文字显示不全" the user saw in
 * Russian - "Перемешивание"). Chips are emitted as individual items of the
 * row's FlowRow so an over-long option moves to the next line INSTEAD of being
 * squeezed; that is what keeps every label on one line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsOptionChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
    )
}

/**
 * One settings row: "icon + label" on the FIRST line, the option chips on the
 * SECOND line, both starting at the row's left edge.
 *
 * This row went through two failed shapes before this one:
 *  1. `Row { Icon; Spacer; Text(weight(1f)); Row(chips) }` - the label was
 *     measured against "whatever is left after the chips", so long translations
 *     squeezed it to 0dp and a 0-wide Text wraps to ONE CHARACTER PER LINE
 *     (measured: the Russian 缩放模式 row sat 1825px below 切换模式, with the label
 *     not rendered at all).
 *  2. `FlowRow` with the label and chips sharing a line when they fit - nothing
 *     was squeezed any more, but the chips floated right after each label, so
 *     the option groups started at a different x per row (measured in Chinese:
 *     486 / 535 / 340 for three rows on the same page) and in long languages the
 *     chip labels still had to wrap inside their chips ("Перемешивание" became
 *     2 lines tall inside a 1-line chip).
 *
 * Two lines give every row the whole content width for its options, so no chip
 * label ever wraps or gets cut, the option groups all start at the same x, and
 * every label is guaranteed to be readable in every language. The price is one
 * extra line per row, which is what the user asked for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsChoiceRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    labelColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    choices: @Composable FlowRowScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().then(modifier)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon == null) {
                // Keep icon-less rows (the sub-row "旋转方向") on the same left
                // edge as every icon row: 24dp icon + 16dp gap.
                Spacer(modifier = Modifier.width(40.dp))
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Text(
                label,
                style = labelStyle,
                color = labelColor,
                // Two lines max: plenty for every translation shipping today,
                // and the label owns the whole line now, so it can never be
                // squeezed into a vertical column again.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            choices()
        }
    }
}

/**
 * Display name of a locale tag, written in that language (endonym): someone who
 * picked the wrong language by accident still recognises their own.
 */
@Composable
fun localeDisplayName(tag: String): String = when (tag) {
    SettingsKeys.LOCALE_SYSTEM -> stringResource(R.string.language_system)
    "zh" -> "简体中文"
    "zh-TW" -> "繁體中文"
    "en" -> "English"
    "ja" -> "日本語"
    "ko" -> "한국어"
    "es" -> "Español"
    "ru" -> "Русский"
    else -> tag
}

/**
 * Language picker: 跟随系统 plus every locale that actually ships a translation
 * ([SettingsKeys.TRANSLATED_LOCALES]). Selecting one writes the setting; the
 * root ([com.wallpaperswitcher.ui.ProvideAppLocale]) re-resolves the whole UI, so
 * the dialog closes already showing the new language.
 */
@Composable
fun LanguagePickerDialog(
    currentTag: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_picker_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.language_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LanguageOption(
                    label = stringResource(R.string.language_system),
                    selected = currentTag == SettingsKeys.LOCALE_SYSTEM
                ) { onSelect(SettingsKeys.LOCALE_SYSTEM) }
                SettingsKeys.TRANSLATED_LOCALES.forEach { tag ->
                    LanguageOption(
                        label = localeDisplayName(tag),
                        selected = currentTag == tag
                    ) { onSelect(tag) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ThemeColorPickerDialog(
    currentHex: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    // Material-style picker: hue x tone grid + the "follow the system" row.
    // "" keeps the system accent (Android 12+ paints the UI from the wallpaper's
    // palette - Monet); the grid covers everything else. Nothing is applied until
    // 保存, so 取消 really cancels.
    ColorGridPickerDialog(
        title = stringResource(R.string.settings_theme_color),
        currentHex = currentHex,
        systemOption = true,
        onConfirm = onSelect,
        onDismiss = onDismiss
    )
}

/** Share an exported runtime-log report through the system share sheet. */
private fun shareLogFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject))
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.log_share_text))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.log_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.toast_log_share_failed, file.absolutePath),
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
