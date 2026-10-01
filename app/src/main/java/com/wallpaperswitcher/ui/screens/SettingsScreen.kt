package com.wallpaperswitcher.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import java.io.File
import kotlinx.coroutines.launch

/** Preset colors for the floating switch button. */
private val floatingButtonColors = listOf(
    "#1E88E5" to "蓝色",
    "#43A047" to "绿色",
    "#E53935" to "红色",
    "#FB8C00" to "橙色",
    "#8E24AA" to "紫色",
    "#00ACC1" to "青色",
    "#D81B60" to "粉色",
    "#FFFFFF" to "白色",
    "#212121" to "黑色",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: WallpaperViewModel) {
    // Single combined state (see settingsUiState): entering this screen used
    // to subscribe to 13 separate Room-backed flows, each emitting on the main
    // thread at its own time -> up to 13 full-screen recompositions while the
    // tab transition animation was still running (the 首页↔设置 stutter). One
    // combined flow = one emission = one recomposition per settings change.
    val settingsUiState by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val serviceEnabled = settingsUiState.serviceEnabled
    val doubleTapEnabled = settingsUiState.doubleTapEnabled
    val unlockSwitchEnabled = settingsUiState.unlockSwitchEnabled
    val floatingButtonEnabled = settingsUiState.floatingButtonEnabled
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
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Global wallpaper settings
        SettingsSection(title = "壁纸设置") {
            // 排版：选项组统一"标签 + 选项同一行"（与下方"旋转方向"一致）。
            // 之前标签独占一行、选项另起一行，每组多花约 90px，设置页要滚很久。
            Row(
                modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Shuffle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text("切换模式", modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = globalSwitchMode == mode,
                            onClick = { viewModel.setGlobalSwitchMode(mode) },
                            label = { Text(when (mode) { SwitchMode.RANDOM -> "随机"; SwitchMode.SEQUENTIAL -> "顺序"; SwitchMode.SHUFFLE -> "洗牌" }) }
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Scale mode (same "label + options in one row" layout as 切换模式).
            Row(
                modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.AspectRatio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text("缩放模式", modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScaleMode.entries.forEach { mode ->
                        FilterChip(
                            selected = globalScaleMode == mode,
                            onClick = { viewModel.setGlobalScaleMode(mode) },
                            label = { Text(when (mode) { ScaleMode.FILL -> "填充"; ScaleMode.FIT -> "适应"; ScaleMode.STRETCH -> "拉伸" }) }
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Clarity enhancement for low-res media (default "auto" keeps the
            // current behavior; the option lets users tune it on-device).
            Row(
                modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.HighQuality, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text("清晰度增强", modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "自动", "off" to "关闭", "strong" to "增强").forEach { (mode, label) ->
                        FilterChip(
                            selected = clarityMode == mode,
                            onClick = { viewModel.setClarityMode(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.AutoMirrored.Outlined.RotateRight,
                title = "自动旋转适配",
                subtitle = "方向与屏幕不符时旋转 90°（适应模式显示面积会变大）",
                checked = rotateMismatchEnabled,
                onCheckedChange = { viewModel.toggleRotateMismatch(it) }
            )

            if (rotateMismatchEnabled) {
                Row(
                    modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "旋转方向",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = rotateMismatchClockwise,
                        onClick = { viewModel.setRotateMismatchClockwise(true) },
                        label = { Text("顺时针") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = !rotateMismatchClockwise,
                        onClick = { viewModel.setRotateMismatchClockwise(false) },
                        label = { Text("逆时针") }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Timer, double tap and unlock switches. They are fully independent.
        // 排版：「切换间隔」紧跟「定时切换」——它的值属于这个开关，之前被放在
        // 页面最上面的"壁纸设置"里，开关和它的间隔隔着两个区块，很难对应。
        SettingsSection(title = "切换方式") {
            SettingsSwitchItem(
                icon = Icons.Outlined.PlayCircle,
                title = "定时切换",
                subtitle = "按设定间隔自动切换壁纸",
                checked = serviceEnabled,
                onCheckedChange = { viewModel.toggleService(it) }
            )

            if (serviceEnabled) {
                // Only meaningful while the timer runs: hidden otherwise so the
                // list stays short (the value is kept, turning the timer back on
                // restores it).
                Row(
                    modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showIntervalDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Timer,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("切换间隔")
                        Text(
                            formatInterval(globalIntervalMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "修改",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.Outlined.LockOpen,
                title = "解锁切换",
                subtitle = "每次解锁屏幕时自动切换壁纸",
                checked = unlockSwitchEnabled,
                onCheckedChange = { viewModel.toggleUnlockSwitch(it) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.Outlined.TouchApp,
                title = "双击切换",
                subtitle = "双击屏幕切换壁纸（需设置动态壁纸）",
                checked = doubleTapEnabled,
                onCheckedChange = { viewModel.toggleDoubleTap(it) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.Outlined.AdsClick,
                title = "悬浮切换按钮",
                subtitle = "点一下悬浮按钮即切换（部分启动器不转发双击）",
                checked = floatingButtonEnabled,
                onCheckedChange = { viewModel.toggleFloatingButton(it) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.Outlined.AutoAwesome,
                title = "切换过渡动画",
                subtitle = "切换壁纸时淡入显示",
                checked = switchFadeEnabled,
                onCheckedChange = { viewModel.setSwitchFadeEnabled(it) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.Outlined.MusicNote,
                title = "视频壁纸播放声音",
                subtitle = "桌面可见时播放视频声音（进应用/熄屏/锁屏静音）",
                checked = videoSoundEnabled,
                onCheckedChange = { viewModel.setVideoSoundEnabled(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 锁屏切换：与桌面的定时/双击/解锁完全独立（Paperize 双屏思路）。
        // 只从分组「应用位置」包含锁屏的分组里取图，并且有自己的间隔、自己的
        // 双击/解锁触发，桌面锁屏可以各换各的。
        // 排版：紧跟"切换方式/悬浮按钮外观"之后——它们都是"什么时候触发切换"，
        // 锁屏只是其中一条独立触发线。
        SettingsSection(title = "锁屏切换（独立于桌面）") {
            SettingsSwitchItem(
                icon = Icons.Outlined.Timer,
                title = "锁屏定时切换",
                subtitle = "按下方间隔单独更换锁屏壁纸",
                checked = lockTimerEnabled,
                onCheckedChange = { viewModel.toggleLockTimer(it) }
            )

            if (lockTimerEnabled) {
                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                Row(
                    modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLockIntervalDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("锁屏切换间隔")
                        Text(
                            formatInterval(lockIntervalMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "修改",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Folder auto-scan
        SettingsSection(title = "文件夹自动扫描") {
            SettingsSwitchItem(
                icon = Icons.Outlined.Sync,
                title = "自动扫描文件夹",
                subtitle = buildString {
                    append("定期把已导入文件夹中的新增图片/视频自动加入分组")
                    if (autoScanEnabled) {
                        // Direct answer to "is it actually running?" - the
                        // periodic job can be deferred by aggressive ROMs, and
                        // opening the app now catches up (see the ViewModel).
                        append("（上次扫描：${formatAgo(autoScanLastRunAt)}）")
                    }
                },
                checked = autoScanEnabled,
                onCheckedChange = { viewModel.toggleAutoScan(it, autoScanIntervalMs) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            Row(
                modifier = Modifier
                .fillMaxWidth()
                .clickable { showAutoScanIntervalDialog = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("扫描间隔", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        formatInterval(autoScanIntervalMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Floating button appearance
        SettingsSection(title = "悬浮按钮外观") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Opacity,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("透明度", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "数值越低按钮越透明（默认 10%）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "$floatingButtonAlpha%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                // While the finger is on the slider, the local value tracks the
                // drag and the persisted value must NOT snap the thumb back
                // mid-drag: the debounced DB write re-emits through the flow,
                // and syncing during a drag used to yank the thumb to the last
                // committed value.
                var sliderAlpha by remember { mutableFloatStateOf(floatingButtonAlpha.toFloat()) }
                var alphaDragging by remember { mutableStateOf(false) }
                LaunchedEffect(floatingButtonAlpha) {
                    if (!alphaDragging) sliderAlpha = floatingButtonAlpha.toFloat()
                }
                Slider(
                    value = sliderAlpha,
                    onValueChange = {
                        alphaDragging = true
                        sliderAlpha = it
                        viewModel.setFloatingButtonAlpha(it.toInt())
                    },
                    onValueChangeFinished = { alphaDragging = false },
                    valueRange = SettingsKeys.FLOATING_BUTTON_ALPHA_MIN.toFloat()..100f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("按钮颜色", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    floatingButtonColors.forEach { (hex, name) ->
                        val isSelected = floatingButtonColor.equals(hex, ignoreCase = true)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { viewModel.setFloatingButtonColor(hex) }
                        ) {
                            Box(
                                modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(hex) ?: Color.Gray)
                                .then(
                                    if (isSelected) Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape
                                    ) else Modifier
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        // The white swatch needs a dark check mark.
                                        tint = if (hex.equals("#FFFFFF", ignoreCase = true))
                                        MaterialTheme.colorScheme.onSurface
                                        else
                                        Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    // Free choice from the hue x tone grid (see ColorGridPicker),
                    // for colours the fixed palette above does not cover.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showButtonColorDialog = true }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.sweepGradient(
                                        listOf(
                                            Color(0xFFF44336), Color(0xFFFFEB3B),
                                            Color(0xFF4CAF50), Color(0xFF00BCD4),
                                            Color(0xFF2196F3), Color(0xFF9C27B0),
                                            Color(0xFFF44336)
                                        )
                                    )
                                )
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Colorize,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("自定义", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Custom label / custom picture. A picture REPLACES the label
            // (FloatingButtonContentPolicy), which is what the user asked for.
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("按钮文字", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "最多 4 个字；留空恢复为「${SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT}」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Local draft so typing does not fight the DB round-trip; the
                // value is capped to the button's capacity by code points, so an
                // emoji is never cut in half.
                var textDraft by remember { mutableStateOf(floatingButtonText) }
                LaunchedEffect(floatingButtonText) { textDraft = floatingButtonText }
                OutlinedTextField(
                    value = textDraft,
                    onValueChange = { raw ->
                        val capped = FloatingButtonContentPolicy.capText(raw)
                        textDraft = capped
                        viewModel.setFloatingButtonText(capped)
                    },
                    singleLine = true,
                    label = { Text("自定义文字") },
                    supportingText = {
                        Text(
                            if (floatingButtonImageUri.isNotEmpty()) {
                                "已设置自定义图片：按钮只显示图片，不显示文字"
                            } else {
                                "显示在悬浮按钮上的文字"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

            }
            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自定义图片", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (floatingButtonImageUri.isEmpty()) {
                                "选一张图片当按钮（自动裁成圆形，文字不再显示）"
                            } else {
                                "已设置：按钮显示图片，文字隐藏"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (floatingButtonImageUri.isNotEmpty()) {
                        AsyncImage(
                            model = floatingButtonImageUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { floatingImagePicker.launch(arrayOf("image/*")) }
                    ) {
                        Text(if (floatingButtonImageUri.isEmpty()) "选择图片" else "更换图片")
                    }
                    if (floatingButtonImageUri.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { viewModel.setFloatingButtonImageUri(null) }) {
                            Text("清除图片")
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Theme
        SettingsSection(title = "外观") {
            // 浅色/深色：跟随系统 or 强制其中一种
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Brightness4,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("主题模式", modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = ThemeMode.from(themeMode) == mode,
                            onClick = { viewModel.setThemeMode(mode.value) },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            Row(
                modifier = Modifier
                .fillMaxWidth()
                .clickable { showColorDialog = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("主题颜色", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            themeColor.isNotEmpty() -> themeColor
                            // Android 12+ paints the whole UI from the wallpaper's
                            // palette (Monet); older versions fall back to the
                            // built-in scheme. Say which one is in effect.
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "跟随系统（Monet）"
                            else -> "跟随系统"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val previewColor = if (themeColor.isNotEmpty()) parseHexColor(themeColor)
                else MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(previewColor ?: MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Runtime log export: captures engine/service/UI logs so OEM-specific
        // issues (black video/GIF, rotation problems) can be analysed without
        // adb.
        SettingsSection(title = "运行日志") {
            val context = androidx.compose.ui.platform.LocalContext.current
            val scope = rememberCoroutineScope()
            var exporting by remember { mutableStateOf(false) }
            // "保存到手机": SAF create-document so the user picks the folder
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
                                context, "日志内容为空，请重新导出", android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        val stream = context.contentResolver.openOutputStream(uri)
                        if (stream == null) {
                            android.widget.Toast.makeText(
                                context, "保存失败：无法写入所选位置", android.widget.Toast.LENGTH_LONG
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
                            "日志已保存（${bytes.size / 1024} KB）",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(
                            context, "保存失败：${t.message}", android.widget.Toast.LENGTH_LONG
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
                    Text("导出运行日志", modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "包含设备信息、当前设置与最近的运行日志（含动态壁纸引擎），导出后可直接分享给开发者排查问题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
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
                                        "导出失败：${t.message}",
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
                        Text(if (exporting) "导出中…" else "导出并分享")
                    }
                    OutlinedButton(
                        onClick = {
                            com.wallpaperswitcher.util.AppLog.clear()
                            android.widget.Toast.makeText(
                                context, "日志已清空", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Icon(Icons.Filled.DeleteSweep, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("清空日志")
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
                                    "生成日志失败：${t.message}",
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
                    Text("保存到手机")
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Usage guide
        SettingsSection(title = "使用指南") {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = "如何使用",
                subtitle = buildString {
                    // Describes the CURRENT flow: groups are filled from the
                    // folder picker, a group's 应用位置 decides which screen it
                    // feeds, and tapping a picture (not the ⋮ menu) is what sets
                    // it - the system dialog then asks for 主屏幕 / 两者.
                    appendLine("1. 首页「新建分组」，进分组点「添加壁纸」（可多选，或整文件夹导入）。")
                    appendLine("2. 在分组顶部设置「应用位置」：桌面 / 锁屏 / 两者。")
                    appendLine("3. 点任意一张图会打开系统动态壁纸界面，按提示选「主屏幕」或「主屏幕和锁定屏幕」才会生效（取消则不变）。")
                    appendLine("4. 需要自动换：设置 →「切换方式」开定时 / 双击 / 解锁 / 悬浮按钮；只换锁屏在「锁屏切换（独立于桌面）」里单独开启。")
                }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsInfoItem(
                icon = Icons.Outlined.Battery1Bar,
                title = "电量消耗",
                subtitle = "使用协程调度，电量消耗极低。"
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // About
        SettingsSection(title = "关于") {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = "壁纸切换 v1.1",
                subtitle = "轻量级壁纸自动切换工具，支持分组管理和多种切换模式。"
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

        if (showButtonColorDialog) {
            // Same picker as the theme colour, plus the translucency slider that
            // drives the button's rest opacity (the 透明度 slider above shows the
            // same setting, so either control keeps the other in sync).
            ColorGridPickerDialog(
                title = "按钮颜色",
                currentHex = floatingButtonColor,
                withAlpha = true,
                alphaPercent = floatingButtonAlpha,
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



@Composable
private fun AutoScanIntervalDialog(
    currentMs: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    val options = listOf(
        1L * 60 * 60 * 1000 to "1 小时",
        6L * 60 * 60 * 1000 to "6 小时",
        12L * 60 * 60 * 1000 to "12 小时",
        24L * 60 * 60 * 1000 to "24 小时"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动扫描间隔") },
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
                    "系统周期任务最短约 15 分钟，实际执行时间由系统调度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SettingsSection(
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
                .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
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
private fun SettingsSwitchItem(
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
private fun SettingsInfoItem(
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
        10_000L to "10 秒",
        30_000L to "30 秒",
        60_000L to "1 分钟",
        300_000L to "5 分钟",
        900_000L to "15 分钟",
        1800_000L to "30 分钟",
        3600_000L to "1 小时",
        7200_000L to "2 小时",
        21600_000L to "6 小时",
        43200_000L to "12 小时",
        86400_000L to "24 小时"
    )
    var customValue by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换间隔") },
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
                    "自定义时间",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customValue,
                        onValueChange = { customValue = it.filter { c -> c.isDigit() } },
                        label = { Text("秒数") },
                        placeholder = { Text("例如: 45") },
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
                        Text("确定")
                    }
                }
                Text(
                    "最少 10 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
        title = "主题颜色",
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
            putExtra(Intent.EXTRA_SUBJECT, "壁纸切换运行日志")
            putExtra(Intent.EXTRA_TEXT, "壁纸切换运行日志，请发送给开发者分析")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        android.widget.Toast.makeText(
            context,
            "分享失败，日志文件：${file.absolutePath}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
