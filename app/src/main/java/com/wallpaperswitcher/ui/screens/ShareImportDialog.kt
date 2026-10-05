package com.wallpaperswitcher.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.R
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/**
 * 分享入库: pick the group a shared image/video batch goes into.
 *
 * The actual copy runs in the ViewModel (it can take a moment for a video), so
 * the dialog only picks the target and dismisses immediately; the outcome
 * arrives as a toast.
 */
@Composable
fun ShareImportDialog(
    viewModel: WallpaperViewModel,
    uris: List<Uri>,
    onDismiss: () -> Unit,
) {
    val home by viewModel.homeUiState.collectAsStateWithLifecycle()
    val groups = home.groups
    var selectedId by remember(groups) { mutableStateOf(groups.firstOrNull()?.id) }
    var favorite by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.share_import_count, uris.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (groups.isEmpty()) {
                    Text(
                        stringResource(R.string.share_import_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.share_import_group),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(groups, key = { it.id }) { group ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedId = group.id }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedId == group.id,
                                    onClick = { selectedId = group.id },
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        group.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.storage_files,
                                            home.mediaCounts[group.id] ?: 0,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { favorite = !favorite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = favorite, onCheckedChange = { favorite = it })
                    Text(stringResource(R.string.share_import_favorite))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = groups.isNotEmpty() && selectedId != null,
                onClick = {
                    val groupId = selectedId ?: return@TextButton
                    viewModel.importSharedMedia(uris, groupId, favorite)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.share_import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
