package com.wallpaperswitcher.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.R
import com.wallpaperswitcher.engine.LiveWallpaperPermission
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import com.wallpaperswitcher.wallpaper.LiveWallpaperService

/**
 * 首启自检向导: one full-screen dialog, three live-checked steps.
 *
 * Everything it shows is re-read on ON_RESUME, so the user can go grant the
 * media permission / the MIUI 「动态壁纸服务」 op / set the live wallpaper and
 * come back to see the step turn green - no "restart the app to continue".
 */
@Composable
fun SetupWizardDialog(
    viewModel: WallpaperViewModel,
    onOpenContent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val home by viewModel.homeUiState.collectAsStateWithLifecycle()
    val sources by viewModel.rssSources.collectAsStateWithLifecycle()
    var permissionsOk by remember { mutableStateOf(hasMediaPermission(context)) }
    var pickerAllowed by remember {
        mutableStateOf(LiveWallpaperPermission.isSystemPickerAllowed(context))
    }
    var engineRunning by remember {
        mutableStateOf(LiveWallpaperService.isHomeLiveWallpaper(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsOk = hasMediaPermission(context)
    }

    // Re-check every step when the user comes back from a system page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsOk = hasMediaPermission(context)
                pickerAllowed = LiveWallpaperPermission.isSystemPickerAllowed(context)
                engineRunning = LiveWallpaperService.isHomeLiveWallpaper(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasContent = home.groups.any { (home.mediaCounts[it.id] ?: 0) > 0 } ||
        sources.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        stringResource(R.string.wizard_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.wizard_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WizardStep(
                        title = stringResource(R.string.wizard_perm_title),
                        done = permissionsOk,
                        detail = stringResource(
                            if (permissionsOk) R.string.wizard_perm_done
                            else R.string.wizard_perm_todo
                        ),
                        actionLabel = if (permissionsOk) null
                        else stringResource(R.string.action_open_permission),
                        onAction = { permissionLauncher.launch(mediaPermissions()) },
                    )
                    WizardStep(
                        title = stringResource(R.string.wizard_content_title),
                        done = hasContent,
                        detail = stringResource(
                            if (hasContent) R.string.wizard_content_done
                            else R.string.wizard_content_todo
                        ),
                        actionLabel = if (hasContent) null
                        else stringResource(R.string.action_add_wallpaper),
                        onAction = onOpenContent,
                    )
                    WizardStep(
                        title = stringResource(R.string.wizard_engine_title),
                        done = engineRunning && pickerAllowed,
                        detail = stringResource(
                            when {
                                !pickerAllowed -> R.string.wizard_engine_blocked
                                engineRunning -> R.string.wizard_engine_done
                                else -> R.string.wizard_engine_todo
                            }
                        ),
                        actionLabel = when {
                            engineRunning && pickerAllowed -> null
                            !pickerAllowed -> stringResource(R.string.action_open_permission)
                            else -> stringResource(R.string.wizard_engine_action)
                        },
                        onAction = {
                            if (!pickerAllowed) {
                                LiveWallpaperPermission.openPermissionEditor(context)
                            } else {
                                viewModel.openLiveWallpaperPicker()
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.wizard_skip))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.wizard_done))
                    }
                }
            }
        }
    }
}

/** One numbered step: green check when satisfied, otherwise an action button. */
@Composable
private fun WizardStep(
    title: String,
    done: Boolean,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.CheckCircle
                else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actionLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** Same all-or-nothing rule as the folder picker: any media grant counts. */
private fun hasMediaPermission(context: Context): Boolean {
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
