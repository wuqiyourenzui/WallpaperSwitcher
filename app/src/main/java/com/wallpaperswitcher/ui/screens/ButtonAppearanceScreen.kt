package com.wallpaperswitcher.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.ui.theme.HiMotion
import com.wallpaperswitcher.ui.theme.parseHexColor
import com.wallpaperswitcher.ui.theme.LocalAccentColor
import com.wallpaperswitcher.engine.FloatingButtonContentPolicy
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/**
 * settings_page_button：设置主界面的一个子页（外壳见 [SettingsPageScaffold]）。
 * 内容与原设置页的同一分区逐字一致，只是搬进了自己的页面。
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun ButtonAppearanceScreen(
    viewModel: WallpaperViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val floatingButtonColor = state.floatingButtonColor
    val floatingButtonAlpha = state.floatingButtonAlpha
    val floatingButtonText = state.floatingButtonText
    val floatingButtonImageUri = state.floatingButtonImageUri
    var showButtonColorDialog by remember { mutableStateOf(false) }

    // 自定义图片用 OpenDocument（不是相册选择器）：只有它的 URI 能**持久化** ——
    // 按钮活在壁纸服务里，重启之后仍然要能读到这张图。
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
                // 有些 provider 不提供可持久化授权；这次会话仍然可用。
            }
            viewModel.setFloatingButtonImageUri(uri.toString())
        }
    }

    SettingsPageScaffold(onBack = onBack, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_page_button)) {
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
                            Text(stringResource(R.string.settings_button_alpha), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_button_alpha_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "$floatingButtonAlpha%",
                            style = MaterialTheme.typography.titleMedium,
                            color = LocalAccentColor.current
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
                        Text(stringResource(R.string.settings_button_color), style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        floatingButtonColors.forEach { (hex, nameRes) ->
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
                                Text(
                                    stringResource(nameRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
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
                            Text(stringResource(R.string.button_color_custom), style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
                            Text(stringResource(R.string.settings_button_text), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(
                                    R.string.settings_button_text_hint,
                                    SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT
                                ),
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
                        label = { Text(stringResource(R.string.settings_button_text_custom)) },
                        supportingText = {
                            Text(
                                if (floatingButtonImageUri.isNotEmpty()) {
                                    stringResource(R.string.settings_button_image_only)
                                } else {
                                    stringResource(R.string.settings_button_text_slot)
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
                            Text(stringResource(R.string.settings_button_image), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (floatingButtonImageUri.isEmpty()) {
                                    stringResource(R.string.settings_button_image_hint)
                                } else {
                                    stringResource(R.string.settings_button_image_set)
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
                            Text(if (floatingButtonImageUri.isEmpty()) stringResource(R.string.action_choose_image) else stringResource(R.string.action_replace_image))
                        }
                        if (floatingButtonImageUri.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { viewModel.setFloatingButtonImageUri(null) }) {
                                Text(stringResource(R.string.action_clear_image))
                            }
                        }
                    }
                }
        }
    }

    if (showButtonColorDialog) {
        // 与主题色同一个取色器，额外带透明度滑杆（它驱动按钮的静止透明度 ——
        // 页内那个「透明度」滑杆是同一个设置，两个控件互相保持同步）。
        ColorGridPickerDialog(
            title = stringResource(R.string.settings_button_color),
            currentHex = floatingButtonColor,
            withAlpha = true,
            alphaPercent = floatingButtonAlpha,
            alphaMinPercent = SettingsKeys.FLOATING_BUTTON_ALPHA_MIN,
            onConfirmAlpha = { viewModel.setFloatingButtonAlpha(it) },
            onConfirm = { viewModel.setFloatingButtonColor(it) },
            onDismiss = { showButtonColorDialog = false }
        )
    }
}
