package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.engine.MediaProbe
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import com.wallpaperswitcher.ui.theme.HiEmptyState
import com.wallpaperswitcher.ui.theme.HiLoadingHint
import com.wallpaperswitcher.ui.theme.HiMotion
import com.wallpaperswitcher.ui.theme.LocalAccentColor

/** How often the home screen re-checks whether the live wallpaper engine is alive. */
private const val ENGINE_STATE_POLL_MS = 5_000L

/** Upper bound of the custom 暂停 duration, in minutes (7 days). */
private const val MAX_SNOOZE_MINUTES = 7L * 24 * 60

@Composable
fun HomeScreen(
    viewModel: WallpaperViewModel,
    onGroupClick: (Long) -> Unit,
    /** 卡片上的「浏览」小按钮：进大图预览（点卡片仍然是进分组网格）。 */
    onGroupBrowse: (Long) -> Unit = {},
) {
    // Single combined state: entering the home screen subscribes to ONE flow
    // and recomposes once, instead of once per Room-backed flow (the media
    // count query + groups + service toggle used to emit separately, which
    // recomposed the whole screen several times during the tab transition).
    val homeUiState by viewModel.homeUiState.collectAsStateWithLifecycle()
    val groups = homeUiState.groups
    val mediaCounts = homeUiState.mediaCounts
    val serviceEnabled = homeUiState.serviceEnabled
    val lockTimerEnabled = homeUiState.lockTimerEnabled
    // 一键暂停 state is read on its own: the homeUiState combine already uses
    // its five-flow overload, and its "已暂停到 HH:mm" line recomposes on its
    // own with this flow.
    val pauseUntil by viewModel.pauseUntil.collectAsStateWithLifecycle()
    val mediaStoreRowCount = homeUiState.mediaStoreRowCount
    val context = androidx.compose.ui.platform.LocalContext.current
    // Missing READ_MEDIA_* is invisible otherwise: the switch simply keeps the
    // old wallpaper (or shows nothing for a lock group) and the only trace is a
    // line in the exported log. Re-checked on resume AND by the same poll that
    // watches the engine, so the card disappears right after the user grants the
    // permission in system settings.
    var mediaPermissionMissing by remember {
        mutableStateOf(!MediaProbe.hasReadMediaPermission(context))
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    // 分组多选：批量删除 / 批量启用。The selection map is read per card (a
    // snapshot read) so ticking one group only recomposes that card.
    var groupSelectionMode by remember { mutableStateOf(false) }
    val selectedGroupIds =
        remember { androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Boolean>() }
    var confirmDeleteGroups by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 服务总开关卡片
        ServiceControlCard(
            serviceEnabled = serviceEnabled,
            lockTimerEnabled = lockTimerEnabled,
            pauseUntil = pauseUntil,
            onToggle = { viewModel.toggleService(it) },
            onSwitchNow = { viewModel.switchNow() },
            onSnooze = { viewModel.snooze(it) },
            onSnoozeUntilMorning = { viewModel.snoozeUntilMorning() },
            onResumeNow = { viewModel.resumeNow() },
            onPreviewNext = { viewModel.previewNext() }
        )

        // The live wallpaper engine is what actually receives timer /
        // double-tap / unlock switches. If it is not running (wallpaper never
        // applied, or a different live wallpaper was selected), switching
        // appears dead. Surface that state here instead of silently failing.
        // NOTE the text is precise about what really needs the engine: the
        // timed switch also works WITHOUT it (static wallpapers); only the
        // double-tap gesture needs the live wallpaper window to receive touches.
        // Refresh on every ON_RESUME (e.g. after returning from the system
        // live-wallpaper picker) so the warning disappears once the engine
        // actually starts.
        val lifecycleOwner = LocalLifecycleOwner.current
        var engineRunning by remember { mutableStateOf(LiveWallpaperService.engineRunning) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    engineRunning = LiveWallpaperService.engineRunning
                    mediaPermissionMissing = !MediaProbe.hasReadMediaPermission(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        // The engine can also die (or come back) while the app stays in the
        // foreground - MIUI kills the wallpaper process in the background. A
        // slow poll while this screen is RESUMED keeps the warning card honest
        // instead of showing a stale state until the user leaves and returns.
        LaunchedEffect(lifecycleOwner, serviceEnabled, lockTimerEnabled) {
            if (!serviceEnabled && !lockTimerEnabled) return@LaunchedEffect
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val running = LiveWallpaperService.engineRunning
                    if (running != engineRunning) engineRunning = running
                    val missing = !MediaProbe.hasReadMediaPermission(context)
                    if (missing != mediaPermissionMissing) mediaPermissionMissing = missing
                    delay(ENGINE_STATE_POLL_MS)
                }
            }
        }

        // Only when there really is media that needs the permission: a library
        // imported through SAF keeps working without it.
        if (mediaPermissionMissing && mediaStoreRowCount > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.home_permission_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            // App details, not a runtime request: it also covers
                            // "don't ask again" and the Android 14 partial grant,
                            // where the user has to change the selection anyway.
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null)
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(intent)
                            } catch (t: Throwable) {
                                AppLog.w("HomeScreen", "app details intent failed", t)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.action_grant_permission))
                    }
                }
            }
        }
        // 警告卡：条件出现/消失时展开收起，不再整块突然闪现（HyperOS 的做法）。
        AnimatedVisibility(
            visible = serviceEnabled && !engineRunning,
            enter = fadeIn(HiMotion.enter()) + expandVertically(HiMotion.enter()),
            exit = fadeOut(HiMotion.exit()) + shrinkVertically(HiMotion.exit()),
        ) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.home_engine_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // A group whose 应用位置 includes 桌面 is what feeds the home screen. If
        // none exists (every group is lock-only), the desktop shows the app's
        // placeholder bitmap - tell the user instead of leaving them puzzled.
        val noHomeGroup = groups.none {
            it.isEnabled && WallpaperTarget.fromName(it.target).includesHome
        }
        AnimatedVisibility(
            visible = noHomeGroup && groups.isNotEmpty() && engineRunning,
            enter = fadeIn(HiMotion.enter()) + expandVertically(HiMotion.enter()),
            exit = fadeOut(HiMotion.exit()) + shrinkVertically(HiMotion.exit()),
        ) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.home_no_home_group_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 分组列表标题：HyperOS 风格的小号强调色标题 + 计数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    stringResource(R.string.home_groups_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = LocalAccentColor.current.takeIf { it != Color.Unspecified }
                        ?: MaterialTheme.colorScheme.primary
                )
                Text(
                    pluralStringResource(
                        R.plurals.home_groups_count,
                        groups.size,
                        groups.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 多选入口（图标按钮，窄屏也不挤压「新建分组」）
                if (groups.isNotEmpty() && !groupSelectionMode) {
                    IconButton(
                        onClick = {
                            selectedGroupIds.clear()
                            groupSelectionMode = true
                        }
                    ) {
                        Icon(
                            Icons.Filled.Checklist,
                            stringResource(R.string.cd_select_groups),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                FilledTonalButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_new_group))
                }
            }
        }

        // 多选工具栏：与分组详情里的选择栏同一套交互（退出 / 全选 / 已选 / 动作）
        AnimatedVisibility(
            visible = groupSelectionMode,
            enter = fadeIn(HiMotion.enter()) + expandVertically(HiMotion.enter()),
            exit = fadeOut(HiMotion.exit()) + shrinkVertically(HiMotion.exit()),
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                MultiSelectActionsBar(
                    selectedMap = selectedGroupIds,
                    allIds = groups.map { it.id },
                    onExit = {
                        selectedGroupIds.clear()
                        groupSelectionMode = false
                    },
                    onEnable = {
                        viewModel.setGroupsEnabled(selectedGroupIds.keys.toSet(), true)
                        selectedGroupIds.clear()
                        groupSelectionMode = false
                    },
                    // 不启用 (batch disable): the counterpart of 启用. Media stays in
                    // the group, it just leaves the rotation.
                    onDisable = {
                        viewModel.setGroupsEnabled(selectedGroupIds.keys.toSet(), false)
                        selectedGroupIds.clear()
                        groupSelectionMode = false
                    },
                    onDelete = { confirmDeleteGroups = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
            EmptyGroupsHint()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(groups, key = { it.id }) { group ->
                    GroupCard(
                        group = group,
                        mediaCount = mediaCounts[group.id] ?: 0,
                        selectionMode = groupSelectionMode,
                        isSelected = selectedGroupIds.containsKey(group.id),
                        onClick = {
                            if (groupSelectionMode) {
                                if (selectedGroupIds.containsKey(group.id)) {
                                    selectedGroupIds.remove(group.id)
                                } else {
                                    selectedGroupIds[group.id] = true
                                }
                            } else {
                                onGroupClick(group.id)
                            }
                        },
                        // Long-press enters multi-select with this group ticked -
                        // the same gesture the media grid would use.
                        onLongClick = {
                            if (!groupSelectionMode) {
                                selectedGroupIds.clear()
                                selectedGroupIds[group.id] = true
                                groupSelectionMode = true
                            }
                        },
                        onToggle = { viewModel.toggleGroupEnabled(group.id, it) },
                        onBrowse = { onGroupBrowse(group.id) },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                // Insert on the ViewModel scope so a rotation/recreation
                // mid-insert cannot cancel it and silently drop the group.
                viewModel.createGroupAndOpen(name) { groupId ->
                    onGroupClick(groupId)
                }
            }
        )
    }

    if (confirmDeleteGroups) {
        val count = selectedGroupIds.size
        AlertDialog(
            onDismissRequest = { confirmDeleteGroups = false },
            title = { Text(stringResource(R.string.dialog_delete_groups_title)) },
            text = {
                Text(
                    // Plural, not a plain string: the English text used to read
                    // "Delete the selected 1 groups?" for a single group.
                    pluralStringResource(
                        R.plurals.dialog_delete_groups_message, count, count
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteGroups(selectedGroupIds.keys.toSet())
                        selectedGroupIds.clear()
                        groupSelectionMode = false
                        confirmDeleteGroups = false
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteGroups = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 下一张预览: shows the media the next automatic switch would display and
    // offers to apply it right away. Read-only until "设为壁纸" is tapped.
    val previewVisible by viewModel.previewVisible.collectAsStateWithLifecycle()
    val previewImage by viewModel.previewImage.collectAsStateWithLifecycle()
    val previewLoading by viewModel.previewLoading.collectAsStateWithLifecycle()
    if (previewVisible) {
        val image = previewImage
        AlertDialog(
            onDismissRequest = { viewModel.dismissPreview() },
            title = { Text(stringResource(R.string.preview_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    when {
                        previewLoading && image == null -> {
                            // Unified loading hint (static icon + text; see
                            // HiLoadingHint for why no spinner is used).
                            HiLoadingHint(
                                text = stringResource(R.string.preview_loading),
                                iconSize = 20.dp,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        image == null -> Text(stringResource(R.string.preview_empty))
                        else -> {
                            // Coil renders images, GIFs and (through the video
                            // decoder) a video's first frame - the same request
                            // the group grid uses, at a larger size.
                            val request = coil.request.ImageRequest.Builder(context)
                                .data(android.net.Uri.parse(image.uri))
                                .size(640, 640)
                                .crossfade(120)
                                .apply {
                                    if (image.mediaType == com.wallpaperswitcher.engine.MediaTypes.VIDEO) {
                                        decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                                    }
                                }
                                .build()
                            coil.compose.AsyncImage(
                                model = request,
                                contentDescription = image.displayName,
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                image.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = image != null,
                    onClick = { viewModel.applyPreview() }
                ) { Text(stringResource(R.string.preview_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPreview() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun ServiceControlCard(
    serviceEnabled: Boolean,
    lockTimerEnabled: Boolean,
    pauseUntil: Long,
    onToggle: (Boolean) -> Unit,
    onSwitchNow: () -> Unit,
    onSnooze: (Long) -> Unit,
    onSnoozeUntilMorning: () -> Unit,
    onResumeNow: () -> Unit,
    onPreviewNext: () -> Unit
) {
    // Recompose once a minute so the "已暂停到 14:30" line stays honest without
    // a permanent ticker (a paused timer changes nothing in between).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pauseUntil) {
        while (pauseUntil > System.currentTimeMillis()) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
        nowMs = System.currentTimeMillis()
    }
    val paused = pauseUntil > nowMs
    var showSnoozeDialog by remember { mutableStateOf(false) }
    // 自定义暂停时长（分钟），空串 = 未输入；上限 7 天（与 ViewModel 的钳制一致）。
    var customMinutes by remember { mutableStateOf("") }
    // 运行 / 停止的底色不再硬切：用一个 0→1 的进度在两组颜色之间插值，
    // 开关时卡片和状态点一起平滑过渡（Miuix 的状态迁移）。
    //
    // 运行底色改为跟随主题色：从容器色（= 卡内 tonal 按钮的颜色，左上）
    // 渐变到一个更深的强调色调（右下）。之前浅色主题下用固定的薄荷绿，
    // 与蓝/紫色系的按钮和标题撞色（绿底 + 蓝字 + 蓝按钮），观感很"脏"；
    // 绿色状态语义现在只由左侧的实心状态点承担。
    //
    // 渐变终点必须偏离容器色：起点用容器色是为了和文字同色系，但如果整张卡
    // 都是容器色，同色的 tonal 按钮就会"消失"在底色里（自绘卡片没有 M3 的
    // elevation 色调差）。往 primary 方向压 25% 后，按钮在卡片中下部仍然是
    // 一枚可辨认的浅色药丸。
    val active by animateFloatAsState(
        targetValue = if (serviceEnabled) 1f else 0f,
        animationSpec = HiMotion.enter(),
        label = "serviceActive",
    )
    val runningStart = MaterialTheme.colorScheme.secondaryContainer
    val runningEnd = lerp(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.primary,
        0.25f,
    )
    val background = Brush.linearGradient(
        listOf(
            lerp(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                runningStart,
                active,
            ),
            lerp(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                runningEnd,
                active,
            ),
        )
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(com.wallpaperswitcher.ui.theme.HiDims.CardCorner),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 状态点：运行时实心绿点，停止时空心灰环 —— 两者之间
                            // 用颜色过渡代替硬切。
                            val dotFill by animateColorAsState(
                                targetValue = if (serviceEnabled)
                                    com.wallpaperswitcher.ui.theme.HiStatusColors.Active
                                else Color.Transparent,
                                animationSpec = HiMotion.standard(),
                                label = "serviceDotFill",
                            )
                            val dotRing by animateColorAsState(
                                targetValue = if (serviceEnabled) Color.Transparent
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                animationSpec = HiMotion.standard(),
                                label = "serviceDotRing",
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(dotFill)
                                    .border(2.dp, dotRing, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when {
                                    serviceEnabled && lockTimerEnabled ->
                                        stringResource(R.string.home_status_home_lock)
                                    serviceEnabled -> stringResource(R.string.home_status_home)
                                    lockTimerEnabled -> stringResource(R.string.home_status_lock)
                                    else -> stringResource(R.string.home_status_stopped)
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (serviceEnabled || lockTimerEnabled)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (paused) stringResource(
                                R.string.pause_active_until,
                                java.text.SimpleDateFormat(
                                    "HH:mm", java.util.Locale.getDefault()
                                ).format(java.util.Date(pauseUntil))
                            )
                            else if (serviceEnabled) stringResource(R.string.home_hint_running)
                            else if (lockTimerEnabled) stringResource(R.string.home_hint_lock_only)
                            else stringResource(R.string.home_hint_stopped),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (serviceEnabled)
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                    Switch(
                        checked = serviceEnabled,
                        onCheckedChange = onToggle,
                        thumbContent = {
                            Icon(
                                if (serviceEnabled) Icons.Filled.PlayArrow else Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    )
                }

                // The manual button also works in static mode, and it is useful
                // whenever either timer runs.
                AnimatedVisibility(
                    visible = serviceEnabled || lockTimerEnabled,
                    enter = fadeIn(HiMotion.enter()) + expandVertically(HiMotion.enter()),
                    exit = fadeOut(HiMotion.exit()) + shrinkVertically(HiMotion.exit()),
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(14.dp))
                        FilledTonalButton(
                            onClick = onSwitchNow,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_switch_now))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // 一键暂停 / 下一张预览: both are one tap away from the
                        // home screen because they are the two things users do
                        // while looking at the wallpaper right now.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    if (paused) onResumeNow() else showSnoozeDialog = true
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (paused) stringResource(R.string.pause_resume_now)
                                    else stringResource(R.string.home_pause_button)
                                )
                            }
                            FilledTonalButton(
                                onClick = onPreviewNext,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Filled.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.home_preview_button))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSnoozeDialog) {
        AlertDialog(
            onDismissRequest = { showSnoozeDialog = false },
            title = { Text(stringResource(R.string.pause_dialog_title)) },
            text = {
                Column {
                    listOf(
                        15 * 60_000L to stringResource(R.string.pause_15m),
                        30 * 60_000L to stringResource(R.string.pause_30m),
                        60 * 60_000L to stringResource(R.string.pause_1h),
                        2 * 60 * 60_000L to stringResource(R.string.pause_2h)
                    ).forEach { (duration, label) ->
                        Text(
                            label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSnoozeDialog = false
                                    onSnooze(duration)
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                    Text(
                        stringResource(R.string.pause_until_morning),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSnoozeDialog = false
                                onSnoozeUntilMorning()
                            }
                            .padding(vertical = 12.dp)
                    )
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    // 自定义时长：和「切换间隔」对话框里的自定义秒数同一套做法
                    // （只收 ASCII 数字，越界由按钮的 enabled 拦住）。
                    Text(
                        stringResource(R.string.pause_custom_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = LocalAccentColor.current
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customMinutes,
                            onValueChange = { input ->
                                // ASCII digits only: isDigit() also accepts
                                // non-ASCII digits, which toIntOrNull() rejects.
                                customMinutes = input.filter { c -> c in '0'..'9' }
                                    .take(5)
                            },
                            label = { Text(stringResource(R.string.pause_custom_minutes)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            enabled = (customMinutes.toLongOrNull() ?: 0L) in 1..MAX_SNOOZE_MINUTES,
                            onClick = {
                                val minutes = customMinutes.toLongOrNull() ?: return@FilledTonalButton
                                showSnoozeDialog = false
                                customMinutes = ""
                                onSnooze(minutes * 60_000L)
                            }
                        ) { Text(stringResource(R.string.action_ok)) }
                    }
                    Text(
                        stringResource(R.string.pause_custom_hint, MAX_SNOOZE_MINUTES),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSnoozeDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(
    group: WallpaperGroup,
    mediaCount: Int,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggle: (Boolean) -> Unit,
    onBrowse: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Long-press is the usual way into a multi-select list; a plain tap
            // still opens the group (or ticks it while selecting).
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        // HyperOS/Miuix：16dp 圆角、纯色卡片、无描边无阴影，状态靠底色区分。
        shape = RoundedCornerShape(com.wallpaperswitcher.ui.theme.HiDims.CardCorner),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                group.isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = com.wallpaperswitcher.ui.theme.HiDims.RowHorizontal,
                    vertical = com.wallpaperswitcher.ui.theme.HiDims.RowVertical,
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标：HyperOS 风格的圆角方块（不再是渐变圆底）。
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (group.isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    // The group "type" column is a legacy artifact (groups mix
                    // images/videos/GIFs now and nothing writes it any more), so
                    // the card icon is a single generic one.
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = if (group.isEnabled)
                        LocalAccentColor.current.takeIf { it != Color.Unspecified }
                            ?: MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(com.wallpaperswitcher.ui.theme.HiDims.IconGap))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color = if (group.isEnabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    if (mediaCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        // 媒体数小徽章
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "$mediaCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (mediaCount > 0) {
                        stringResource(
                            WallpaperTarget.fromName(group.target).shortLabelRes
                        ).let { targetLabel ->
                            pluralStringResource(
                                R.plurals.group_media_count,
                                mediaCount,
                                mediaCount,
                                targetLabel
                            )
                        }
                    } else {
                        stringResource(R.string.group_no_media)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (selectionMode) {
                // Ticking is handled by the card's own click - the box is a
                // read-only indicator here, so tapping it does not fight the
                // card's selection toggle.
                Checkbox(checked = isSelected, onCheckedChange = null)
            } else {
                // 「浏览」：点卡片进九宫格（整理），点这个小按钮进大图预览
                // （一张一张挑）。放在开关左边，避免和"启用"这个开关抢注意力。
                if (mediaCount > 0) {
                    IconButton(onClick = onBrowse) {
                        Icon(
                            Icons.Outlined.PlayCircleOutline,
                            contentDescription = stringResource(R.string.browse_entry),
                        )
                    }
                }
                Switch(
                    checked = group.isEnabled,
                    onCheckedChange = onToggle
                )
            }
        }
    }
}


@Composable
private fun EmptyGroupsHint() {
    // HyperOS/Miuix 容器式空态，与订阅、分组详情共用（见 HiUi.HiEmptyState）。
    HiEmptyState(
        title = stringResource(R.string.empty_groups_title),
        hint = stringResource(R.string.empty_groups_hint),
        icon = Icons.Outlined.Image,
        modifier = Modifier.padding(vertical = 60.dp),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_group_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.new_group_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
