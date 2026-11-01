package com.wallpaperswitcher.ui

import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.wallpaperswitcher.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.ui.screens.*
import com.wallpaperswitcher.ui.theme.HiMotion
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperSwitcherApp(
    viewModel: WallpaperViewModel,
    /** A pending ACTION_SEND payload (see MainActivity); null when there is none. */
    sharePayload: SharePayload? = null,
    /** Called once the payload has been taken over by the UI. */
    onShareHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    // Save the current screen so opening the system live-wallpaper picker
    // (or any activity recreation) returns to the same page instead of
    // falling back to the home/group list.
    var currentScreen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.Home)
    }
    // 订阅源文章的全屏页面（画在普通窗口里，视频只在非对话框窗口里能合成）。
    // 视频还是图片由 RssArticleScreen **按正文内容**判，打开时只管把文章交过去。
    var rssArticle by remember {
        mutableStateOf<com.wallpaperswitcher.data.RssArticle?>(null)
    }
    /**
     * 从**文章界面**点「编辑」打开的源编辑器（sourceId）。
     *
     * 它叠在文章之上，而不是替换当前页：用户从文章里点编辑，期望的是
     * 「原地进编辑页，返回还回到这篇文章」，而不是先退回订阅列表再进编辑
     * （那样既闪一下列表，返回也只能回到列表）。
     */
    var articleEditorSourceId by remember { mutableStateOf<Long?>(null) }
    // 分享入库: media opens the group picker; text (a subscription URL / 阅读
    // 分享链接) jumps to 订阅 and prefills the existing import dialog.
    var sharedMedia by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var sharedImportText by remember { mutableStateOf<String?>(null) }
    // 首启自检向导: auto-open once (a missing settings row reads as "not
    // done"), then re-openable from 设置 → 使用向导.
    val wizardDone by viewModel.setupWizardDone.collectAsStateWithLifecycle()
    var showWizard by rememberSaveable { mutableStateOf(false) }
    var wizardAutoShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(wizardDone) {
        if (!wizardDone && !wizardAutoShown) {
            wizardAutoShown = true
            showWizard = true
        }
    }
    LaunchedEffect(sharePayload) {
        when (val payload = sharePayload) {
            is SharePayload.Media -> {
                rssArticle = null
                sharedMedia = payload.uris
                onShareHandled()
            }
            is SharePayload.Link -> {
                rssArticle = null
                sharedImportText = payload.text
                currentScreen = Screen.Subscriptions
                onShareHandled()
            }
            null -> Unit
        }
    }

    // Toast 消息
    // 启动后预热订阅源图标（见 WallpaperViewModel.prefetchRssIcons）：让切到订阅页时
    // 首帧不用再同时发十几个图标请求 + 解码。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_500)
        viewModel.prefetchRssIcons()
    }

    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Long instructions (e.g. which button to tap in the system live-wallpaper
    // dialog). A normal toast is gone in ~2s - and the system dialog covers the
    // app immediately afterwards - so these are shown as a non-touchable
    // floating bubble for several seconds, with a repeated toast as fallback
    // when the overlay permission is not granted.
    LaunchedEffect(Unit) {
        viewModel.hintMessage.collectLatest { msg ->
            if (!com.wallpaperswitcher.wallpaper.HintOverlay.show(context, msg)) {
                com.wallpaperswitcher.wallpaper.HintOverlay.showLongToast(context, msg)
            }
        }
    }
    // 缓存 TTL + 一次性设置迁移 (both no-ops once done / while off).
    LaunchedEffect(Unit) {
        viewModel.sweepExpiredDownloads()
        viewModel.migrateLegacyQualityEnhance()
    }

    // HyperOS/MIUI 「动态壁纸服务」 is off: the system live-wallpaper screen closes
    // itself before it is drawn, so a tap on a picture looks like a no-op. Offer
    // the one page that holds the switch (see LiveWallpaperPermission).
    var liveWallpaperBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.liveWallpaperBlocked.collect { liveWallpaperBlocked = true }
    }
    if (liveWallpaperBlocked) {
        AlertDialog(
            onDismissRequest = { liveWallpaperBlocked = false },
            title = { Text(stringResource(R.string.live_wallpaper_permission_title)) },
            text = { Text(stringResource(R.string.live_wallpaper_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        liveWallpaperBlocked = false
                        com.wallpaperswitcher.engine.LiveWallpaperPermission
                            .openPermissionEditor(context)
                    }
                ) {
                    Text(stringResource(R.string.action_open_permission))
                }
            },
            dismissButton = {
                TextButton(onClick = { liveWallpaperBlocked = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (sharedMedia.isNotEmpty()) {
        ShareImportDialog(
            viewModel = viewModel,
            uris = sharedMedia,
            onDismiss = { sharedMedia = emptyList() },
        )
    }
    if (showWizard) {
        SetupWizardDialog(
            viewModel = viewModel,
            onOpenContent = {
                showWizard = false
                viewModel.markSetupWizardDone()
                currentScreen = Screen.Home
            },
            onDismiss = {
                showWizard = false
                viewModel.markSetupWizardDone()
            },
        )
    }

    // Drop the hint the moment we come back to the foreground: leaving the
    // system live-wallpaper dialog (after tapping 设为壁纸, or by cancelling)
    // resumes this activity, and the instruction is no longer useful then.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                com.wallpaperswitcher.wallpaper.HintOverlay.dismiss()
                // 一次性说明：带着已生效的动态壁纸回来、而「锁屏独立定时」还开着时，
                // 点明"锁屏跟随动态壁纸更省电"这个取舍（见
                // WallpaperViewModel.maybeShowLockFollowHint：条件不满足时它是空操作）。
                viewModel.maybeShowLockFollowHint()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Show the actual group name in the detail page's top bar instead of a
    // generic "分组详情" label.
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()

    // Owned here, not inside SubscriptionScreen: opening a source swaps that
    // screen out of the composition, and a locally remembered scroll state would
    // be thrown away - the card list jumped back to the top on every return.
    val subscriptionsListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 设置页的滚动位置同理（同一类问题）：它是一整条 verticalScroll 的 Column，
    // 状态如果在页面内部 remember，从这里跳到「存储/最近」再返回就会新建一个
    // → 回到顶部。由这里持有，返回时停在原来的位置。
    val settingsScrollState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 订阅源列表的「多选」入口从列表上方那一行搬到了顶栏：那一行会在标题
    // 下方留出一整条空白（用户反馈「订阅与文章间有空白」）。顶栏点击后把
    // 计数 +1，列表页收到变化即进入多选模式。
    var sourceSelectionRequest by remember { mutableStateOf(0) }

    // 登录 / 编辑是从「某个源的文章列表」打开的：退出时要回到那个文章列表，
    // 而不是一路退到订阅的源列表（用户反馈）。
    var returnToSourceId by remember { mutableStateOf<Long?>(null) }
    /** 编辑源保存成功一次 +1：文章列表据此重新抓取（阅读保存后也是重载）。 */
    var editorReload by remember { mutableStateOf(0) }
    // 编辑源的「保存」和「⋮」放在**应用顶栏**（阅读也在菜单栏）：编辑页自己再画
    // 一行会变成两处保存、标题也重复。编辑页把动作注册进来，顶栏按钮直接调用。
    var editorActions by remember {
        mutableStateOf<com.wallpaperswitcher.ui.screens.RssEditorActions?>(null)
    }
    var editorMenuOpen by remember { mutableStateOf(false) }
    fun leaveSourceEditor() {
        val target = returnToSourceId
        returnToSourceId = null
        currentScreen = target?.let { Screen.SubscriptionArticles(it) } ?: Screen.Subscriptions
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // HyperOS/Miuix 风格：左对齐大标题、透明底，内容从标题下方滚过。
            TopAppBar(
                title = {
                    // 标题随页面交叉淡入淡出（只动一段文字，代价可忽略）。
                    val titleText = when (currentScreen) {
                            is Screen.Home -> stringResource(R.string.title_home)
                            is Screen.GroupDetail -> selectedGroup?.name
                                ?: stringResource(R.string.title_group_detail)
                            is Screen.Settings -> stringResource(R.string.title_settings)
                            is Screen.WebDav -> stringResource(R.string.nav_webdav)
                            is Screen.Subscriptions,
                            is Screen.SubscriptionArticles ->
                                stringResource(R.string.title_subscriptions)
                            is Screen.RssLogin -> stringResource(R.string.rss_login_title)
                            is Screen.RssSourceEdit -> stringResource(R.string.rss_edit_title)
                            is Screen.Recent -> stringResource(R.string.recent_title)
                            is Screen.Storage -> stringResource(R.string.storage_title)
                            is Screen.WallpaperSettings ->
                                stringResource(R.string.settings_page_wallpaper)
                            is Screen.SwitchMethods ->
                                stringResource(R.string.settings_page_switch)
                            is Screen.FolderScan ->
                                stringResource(R.string.settings_page_scan)
                            is Screen.ButtonAppearance ->
                                stringResource(R.string.settings_page_button)
                            is Screen.Appearance ->
                                stringResource(R.string.settings_page_appearance)
                            is Screen.OnlineSources ->
                                stringResource(R.string.settings_online_sources)
                        }
                    Crossfade(
                        targetState = titleText,
                        // 进出分组时标题不做交叉淡入淡出（用户要求"进分组不要动画"）：
                        // 否则会出现"正文已是分组页、标题还是首页"的一帧。
                        animationSpec = if (currentScreen is Screen.GroupDetail) {
                            snap()
                        } else {
                            HiMotion.standard(HiMotion.ShortMs)
                        },
                        label = "hiTopBarTitle",
                    ) { text ->
                        Text(
                            text = text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                },
                navigationIcon = {
                    // 只有真正的子页面才显示返回箭头；「订阅」「设置」是底栏的
                    // 顶层标签，返回箭头会让它们看起来像详情页。
                    val isSubScreen = currentScreen is Screen.GroupDetail ||
                        currentScreen is Screen.SubscriptionArticles ||
                        currentScreen is Screen.RssLogin ||
                        currentScreen is Screen.RssSourceEdit ||
                        currentScreen is Screen.Recent ||
                        currentScreen is Screen.Storage ||
                        currentScreen is Screen.WallpaperSettings ||
                        currentScreen is Screen.SwitchMethods ||
                        currentScreen is Screen.FolderScan ||
                        currentScreen is Screen.ButtonAppearance ||
                        currentScreen is Screen.Appearance ||
                        currentScreen is Screen.OnlineSources
                    // 返回箭头随子页面淡入 + 轻微放大（Miuix 的出现方式）。
                    AnimatedVisibility(
                        visible = isSubScreen,
                        enter = fadeIn(HiMotion.enter()) +
                            scaleIn(initialScale = 0.8f, animationSpec = HiMotion.enter()),
                        exit = fadeOut(HiMotion.exit()) +
                            scaleOut(targetScale = 0.8f, animationSpec = HiMotion.exit()),
                    ) {
                        IconButton(onClick = {
                            if (rssArticle != null) {
                                // The browser overlay sits on top: close it first.
                                rssArticle = null
                            } else {
                                when (currentScreen) {
                                    is Screen.SubscriptionArticles ->
                                        currentScreen = Screen.Subscriptions
                                    // 从文章列表打开的登录/编辑：回到那个源。
                                    is Screen.RssLogin, is Screen.RssSourceEdit ->
                                        leaveSourceEditor()
                                    // 设置子页：顶栏箭头和系统返回键走同一条路
                                    // （都回设置页）。之前这里一律回首页，和页面
                                    // 自己注册的 BackHandler 行为不一致。
                                    is Screen.WallpaperSettings, is Screen.SwitchMethods,
                                    is Screen.FolderScan, is Screen.ButtonAppearance,
                                    is Screen.Appearance, is Screen.OnlineSources,
                                    is Screen.Recent, is Screen.Storage ->
                                        currentScreen = Screen.Settings
                                    else -> currentScreen = Screen.Home
                                }
                            }
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.action_back)
                            )
                        }
                    }
                },
                actions = {
                    when (val screen = currentScreen) {
                        is Screen.Subscriptions -> {
                            // 显示方式切换：卡片列表 ⇄ 缩略图网格（站点图标）。
                            // 持久化在设置里，重进/重启后保持。
                            val gridView by viewModel.rssGridView
                                .collectAsStateWithLifecycle()
                            IconButton(onClick = { viewModel.setRssGridView(!gridView) }) {
                                Icon(
                                    if (gridView) Icons.AutoMirrored.Filled.ViewList
                                    else Icons.Filled.GridView,
                                    contentDescription = stringResource(
                                        if (gridView) R.string.cd_rss_view_list
                                        else R.string.cd_rss_view_grid
                                    ),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            IconButton(onClick = { sourceSelectionRequest++ }) {
                                Icon(
                                    Icons.Filled.Checklist,
                                    contentDescription = stringResource(R.string.cd_select_sources),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        is Screen.SubscriptionArticles -> {
                            // 登录 / 编辑 属于「这个源」而不是列表，放进顶栏后
                            // 列表页不再需要它们占一整行。
                            val thisSource = viewModel.rssSources.value
                                .firstOrNull { it.id == screen.sourceId }
                            // 阅读的 `menu_login` 只在源配了 loginUrl 时可见：
                            // 没配登录的源点了只会打开源首页，是纯粹的误导。
                            if (thisSource?.let { rssHasLogin(it) } == true) {
                                TextButton(onClick = {
                                    // 全屏文章是**叠在当前页之上**的覆盖层（见下面
                                    // 的 OverlayEnter），不清掉它就会盖住新页面 ——
                                    // 从文章界面点「编辑」时编辑页被图片/视频挡住
                                    // 就是这个原因。返回箭头一直是对的，这两个按钮
                                    // 漏了（用户反馈）。
                                    rssArticle = null
                                    returnToSourceId = screen.sourceId
                                    currentScreen = Screen.RssLogin(screen.sourceId)
                                }) {
                                    Text(stringResource(R.string.rss_login))
                                }
                            }
                            // 切换布局：循环 1→2→3→4→1，并把结果存回源
                            // （`articleStyle`）。当前档位由 ViewModel 从库里现读，
                            // 所以连点也一次一档。**不弹提示**：列表自己就变了样，
                            // 再说一句「已切换为某某」是多余的。
                            IconButton(onClick = {
                                uiScope.launch {
                                    viewModel.rssCycleArticleStyle(screen.sourceId)
                                }
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = stringResource(R.string.rss_switch_layout),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            TextButton(onClick = {
                                // 文章开着的时候：把编辑器**叠在文章之上**，不切屏。
                                // 切屏会先露出订阅列表（闪一下），而且返回只能回到
                                // 列表 —— 用户的期望是返回还回到这篇文章。
                                if (rssArticle != null) {
                                    articleEditorSourceId = screen.sourceId
                                } else {
                                    returnToSourceId = screen.sourceId
                                    currentScreen = Screen.RssSourceEdit(screen.sourceId)
                                }
                            }) {
                                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.rss_edit))
                            }
                        }
                        is Screen.RssSourceEdit -> {
                            // 阅读的 `menu/source_edit.xml`：保存 + 溢出菜单都在这。
                            // 菜单以这个三点为锚点弹出，所以内容必须画在这里。
                            TextButton(
                                enabled = editorActions != null,
                                onClick = { editorActions?.save?.invoke() },
                            ) {
                                Text(stringResource(R.string.rss_edit_save))
                            }
                            Box {
                                IconButton(onClick = { editorMenuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = editorMenuOpen,
                                    onDismissRequest = { editorMenuOpen = false },
                                ) {
                                    // 阅读：`menu_login` 只在源配了 loginUrl 时可见。
                                    if (editorActions?.hasLogin?.invoke() == true) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.rss_login_title)) },
                                            onClick = {
                                                editorMenuOpen = false
                                                editorActions?.onLogin?.invoke()
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rss_edit_debug)) },
                                        onClick = {
                                            editorMenuOpen = false
                                            editorActions?.debug?.invoke()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rss_edit_variable)) },
                                        onClick = {
                                            editorMenuOpen = false
                                            editorActions?.variables?.invoke()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rss_edit_clear_cookie)) },
                                        onClick = {
                                            editorMenuOpen = false
                                            editorActions?.clearCookie?.invoke()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rss_edit_copy)) },
                                        onClick = {
                                            editorMenuOpen = false
                                            editorActions?.copy?.invoke()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rss_edit_paste)) },
                                        onClick = {
                                            editorMenuOpen = false
                                            editorActions?.paste?.invoke()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rss_edit_share)) },
                                        onClick = {
                                            editorMenuOpen = false
                                            editorActions?.share?.invoke()
                                        }
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            // 悬浮胶囊底栏（对应 Miuix 的 NavigationBar）。
            val onSubscriptions = currentScreen is Screen.Subscriptions ||
                currentScreen is Screen.SubscriptionArticles ||
                currentScreen is Screen.RssLogin ||
                currentScreen is Screen.RssSourceEdit
            val tabIndex = when {
                currentScreen.isSettingsPage() -> 3
                currentScreen is Screen.WebDav -> 2
                onSubscriptions -> 1
                else -> 0
            }
            com.wallpaperswitcher.ui.theme.HiNavigationBar(
                items = listOf(
                    com.wallpaperswitcher.ui.theme.HiNavItem(
                        label = stringResource(R.string.nav_home),
                        icon = Icons.Outlined.Home,
                        selectedIcon = Icons.Filled.Home,
                    ),
                    com.wallpaperswitcher.ui.theme.HiNavItem(
                        label = stringResource(R.string.nav_subscriptions),
                        icon = Icons.Outlined.MenuBook,
                        selectedIcon = Icons.Filled.MenuBook,
                    ),
                    com.wallpaperswitcher.ui.theme.HiNavItem(
                        label = stringResource(R.string.nav_webdav),
                        icon = Icons.Outlined.Cloud,
                        selectedIcon = Icons.Filled.Cloud,
                    ),
                    com.wallpaperswitcher.ui.theme.HiNavItem(
                        label = stringResource(R.string.nav_settings),
                        icon = Icons.Outlined.Settings,
                        selectedIcon = Icons.Filled.Settings,
                    ),
                ),
                selectedIndex = tabIndex,
                onSelect = { index ->
                    // Leaving a sub-screen also closes its browser overlay.
                    rssArticle = null
                    currentScreen = when (index) {
                        1 -> Screen.Subscriptions
                        2 -> Screen.WebDav
                        3 -> Screen.Settings
                        else -> Screen.Home
                    }
                },
            )
        }
    ) { padding ->
        // 全屏文章页（播放器 / 收集页）是叠层：返回键/手势必须先关它，
        // 不能漏到下面的常规返回处理（以前图片收集页按返回不退出就是这个原因：
        // 这里只判了视频那个状态，收集页的状态没人管）。
        //
        // 从文章里打开的**源编辑器**自己在叠层处注册了 BackHandler（见下），
        // 因为 BackHandler 是后注册者优先：写在这里会被编辑页内部的处理器抢先，
        // 那样返回会退回订阅列表，而不是回到那篇文章。
        if (rssArticle != null && articleEditorSourceId == null) {
            BackHandler { rssArticle = null }
        }
        // Handle system back button for non-home screens (only while the
        // article overlay is closed, so back never hits both at once).
        //
        // 「设置」和「订阅」是底栏的顶层标签：它们的返回键**直接退出应用**，
        // 不再跳到首页 —— 从首页跳过去、按返回又回到首页，看起来就像返回没生效。
        // 所以这两个标签不注册 BackHandler，交给系统默认行为（结束 Activity）。
        // 只有真正有父页面的子页面（分组详情 / 文章列表 / 登录 / 编辑 /
        // 最近 / 存储）才拦截返回。
        val backHandled = when (currentScreen) {
            is Screen.Home, is Screen.Settings, is Screen.Subscriptions,
            is Screen.WebDav -> false
            else -> true
        }
        if (backHandled && rssArticle == null && articleEditorSourceId == null) {
            BackHandler {
                com.wallpaperswitcher.util.AppLog.d(
                    "NavBack",
                    "generic back: currentScreen=$currentScreen editorOpen=${articleEditorSourceId != null}",
                )
                // Back goes to the logical parent, not always the home screen.
                when (currentScreen) {
                    is Screen.SubscriptionArticles -> currentScreen = Screen.Subscriptions
                    // 登录/编辑返回时回到打开它的文章列表（见 leaveSourceEditor）。
                    is Screen.RssLogin, is Screen.RssSourceEdit -> leaveSourceEditor()
                    is Screen.Recent, is Screen.Storage,
                    is Screen.WallpaperSettings, is Screen.SwitchMethods,
                    is Screen.FolderScan, is Screen.ButtonAppearance,
                    is Screen.Appearance -> currentScreen = Screen.Settings
                    else -> currentScreen = Screen.Home
                }
            }
        }

        // 记住上一个顶层 Tab：进入子页面时它要继续画，作为 push 动画的背景。
        // 否则切走的那一帧 tab 已隐藏、子页面还在滑入 → 中间会闪一帧空页。
        var lastTab by remember { mutableStateOf<Screen>(Screen.Home) }
        if (currentScreen.isTopLevelTab()) lastTab = currentScreen
        val subScreenShown = !currentScreen.isTopLevelTab()
        // 从子页面返回 Tab 时不再播 Tab 槽位的"进场缩放"：那一下会让底下的页面
        // （含缩略图）整体缩一下再回到原位，观感上就是"回闪一下"。
        val isTabNow = currentScreen.isTopLevelTab()
        var wasTabBefore by remember { mutableStateOf(true) }
        val returningFromSubPage = isTabNow && !wasTabBefore
        LaunchedEffect(currentScreen) { wasTabBefore = isTabNow }
        Box(modifier = Modifier.padding(padding)) {
            // 顶层 Tab 常驻组合：切 Tab 只切可见性，不再"销毁旧页 + 从零重建新页"。
            // 之前每次切换都要重跑页面骨架、LazyColumn 首屏与 Flow 冷启动，
            // 真机首帧 150–700ms；常驻后这些成本只在首次进入付一次。
            // 本轮先接 Home 与 Settings，其余 Tab 仍走下面的 AnimatedContent。
            TabSlot(
                settleIn = !returningFromSubPage,
                active = currentScreen is Screen.Home ||
                    (subScreenShown && lastTab is Screen.Home)
            ) {
                HomeScreen(
                    viewModel = viewModel,
                    onGroupClick = { groupId ->
                        // 先把首屏缩略图丢给 Coil，再导航：分组详情第一帧就不用等解码。
                        viewModel.prefetchGroupThumbnails(groupId)
                        viewModel.selectGroup(groupId)
                        // 把分组名与数量一起带过去：子页面首帧就能画出页头。
                        val g = viewModel.groups.value.firstOrNull { it.id == groupId }
                        currentScreen = Screen.GroupDetail(
                            groupId = groupId,
                            initialName = g?.name.orEmpty(),
                            initialCount = viewModel.mediaCounts.value[groupId] ?: 0,
                        )
                    },
                )
            }
            TabSlot(
                settleIn = !returningFromSubPage,
                active = currentScreen is Screen.Settings ||
                    (subScreenShown && lastTab is Screen.Settings)
            ) {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenScreen = { currentScreen = it },
                    onOpenWizard = { showWizard = true },
                    scrollState = settingsScrollState,
                )
            }
            TabSlot(
                settleIn = !returningFromSubPage,
                active = currentScreen is Screen.WebDav ||
                    (subScreenShown && lastTab is Screen.WebDav)
            ) {
                WebDavScreen(viewModel = viewModel)
            }
            TabSlot(
                settleIn = !returningFromSubPage,
                active = currentScreen is Screen.Subscriptions ||
                    (subScreenShown && lastTab is Screen.Subscriptions)
            ) {
                SubscriptionScreen(
                    viewModel = viewModel,
                    listState = subscriptionsListState,
                    selectionRequest = sourceSelectionRequest,
                    onSelectionRequestHandled = { sourceSelectionRequest = 0 },
                    // 分享入库（文本）：预填订阅导入对话框（见 SharePayload.Link）。
                    prefillImport = sharedImportText,
                    onPrefillConsumed = { sharedImportText = null },
                    onOpenArticles = { sourceId ->
                        currentScreen = Screen.SubscriptionArticles(sourceId)
                    },
                    // 单 URL / 网页型源：不把它当订阅源，直接用全屏浏览器打开。
                    onOpenBrowser = { source ->
                        rssArticle = com.wallpaperswitcher.data.RssArticle(
                            sourceId = source.id,
                            guid = "browse:${source.id}",
                            title = source.name,
                            link = source.url,
                            isRead = true,
                        )
                    },
                )
            }
            // 只做「进场」动画、不做退场：旧页面立即释放，动画期间只有目标页
            // 在合成。历史版本用整屏 crossfade 让两个重页面同屏动画，部分机型
            // 掉帧（见 git 历史）；这里保留了那个教训，动效只落在轻元素上。
            // 子页面（分组详情/文章/WebDAV 浏览）永远在四个常驻槽之上。
            AnimatedContent(
                targetState = currentScreen,
                // 子页面（分组详情 / 文章 / WebDAV 浏览）永远在四个常驻槽之上。
                // 子页面必须是**替换**而不是"盖在首页上"：给它一层不透明底色，
                // 滑入过程中看到的是新页面的背景，而不是底下的首页。
                // 顶层 Tab 时不画底色（否则会把四个常驻槽遮住）。
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .then(
                        if (currentScreen.isTopLevelTab()) Modifier
                        else Modifier.background(MaterialTheme.colorScheme.background)
                    ),
                transitionSpec = {
                    val fadeSpec = HiMotion.enter<Float>(HiMotion.MediumMs)
                    val riseSpec = HiMotion.enter<IntOffset>(HiMotion.MediumMs)
                    val forward = screenDepth(targetState) > screenDepth(initialState)
                    val backward = screenDepth(targetState) < screenDepth(initialState)
                    val enter = when {
                        // 分组页：**直接替换**（用户要求：点分组进入不要动画）。
                        // 它的媒体行是异步查的，滑动期间会看到"旧页 + 新页页头"混在一起。
                        targetState is Screen.GroupDetail ||
                            initialState is Screen.GroupDetail -> EnterTransition.None
                        // 其它子页面：淡入 + 轻微上浮。
                        // 淡入 + 轻微上浮（M3 fade-through 的轻量版）：不改变页面几何，
                        // 不会像横向滑动那样在过程中只露出半页/半张缩略图。
                        forward -> fadeIn(fadeSpec) + slideInVertically(riseSpec) { it / 48 }
                        // 返回：直接替换。淡入过程中会透出底下的 Tab 页（含图片），就是"回闪"。
                        backward -> EnterTransition.None
                        // 顶层标签之间：不过渡动画。
                        else -> EnterTransition.None
                    }
                    // 尺寸一律 snap：默认 SizeTransform 会把新页从旧尺寸"长"过去。
                    enter togetherWith ExitTransition.None using
                        SizeTransform(clip = false) { _, _ -> snap() }
                },
                label = "hiScreen",
            ) { screen ->
            when (screen) {
                // 顶层 Tab（首页 / 设置）已在上面常驻渲染，这里不再重复组合。
                is Screen.Home, is Screen.Settings, is Screen.Subscriptions,
                is Screen.WebDav -> Unit
                // 分组详情**不进 AnimatedContent**：它是"直接替换"的页面，放到下面单独画，
                // 彻底避开 AnimatedContent 的尺寸/对齐处理（真机上曾看到"右下角先出现一小块"）。
                is Screen.GroupDetail -> Unit
                is Screen.Recent -> RecentScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.Storage -> StorageScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.WallpaperSettings -> WallpaperSettingsScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.SwitchMethods -> SwitchMethodsScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.FolderScan -> FolderScanScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.ButtonAppearance -> ButtonAppearanceScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.Appearance -> AppearanceScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.OnlineSources -> OnlineSourcesScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.SubscriptionArticles -> SubscriptionArticlesScreen(
                    viewModel = viewModel,
                    sourceId = screen.sourceId,
                    // 编辑源保存后重新载入（阅读：`RESULT_OK` → 重取源 + 丢分类缓存 +
                    // 重建页签）。这里用一个计数器当 key，回到列表即重抓一次。
                    editorReloadKey = editorReload,
                    // 视频还是图片由文章正文决定（见 RssArticleScreen），
                    // 这里两条回调都指向同一个全屏页面。
                    onOpenWeb = { article -> rssArticle = article },
                    onOpenVideo = { article -> rssArticle = article },
                )
                is Screen.RssLogin -> RssLoginScreen(
                    viewModel = viewModel,
                    sourceId = screen.sourceId,
                    onDone = { leaveSourceEditor() }
                )
                is Screen.RssSourceEdit -> RssSourceEditScreen(
                    viewModel = viewModel,
                    sourceId = screen.sourceId,
                    onLogin = { id ->
                        returnToSourceId = id
                        currentScreen = Screen.RssLogin(id)
                    },
                    // 顶栏的「保存 / ⋮」由编辑页注册（见 editorActions）。
                    onActionsReady = { editorActions = it },
                    onDone = { saved ->
                        if (saved) editorReload++
                        leaveSourceEditor()
                    }
                )
            }
            }
            // 分组详情：作为独立的一层直接画（不经过 AnimatedContent）。
            // 这样进入分组没有任何过渡状态：要么还是首页，要么就是完整的分组页。
            (currentScreen as? Screen.GroupDetail)?.let { gd ->
                androidx.compose.runtime.key(gd.groupId) {
                    DisposableEffect(gd.groupId) {
                        onDispose { viewModel.selectGroup(null) }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(3f)
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        GroupDetailScreen(
                            viewModel = viewModel,
                            groupId = gd.groupId,
                            initialName = gd.initialName,
                            initialCount = gd.initialCount,
                            onBack = { currentScreen = Screen.Home },
                        )
                    }
                }
            }

            // 全屏文章页画在当前页面之上（不替换它）：替换会销毁文章列表，
            // 关掉之后列表滚动位置就没了，用户会回到源的顶部。
            //
            // **编辑器开着时不再画文章这一层**：两者是互斥的，文章画在后面会盖住
            // 编辑器（用户报的"点编辑后编辑页被图片/视频覆盖"）。文章状态本身留着，
            // 关掉编辑器就原样回来 —— 所以返回是回到那篇文章，不是回列表。
            val openArticle = rssArticle
            if (openArticle != null && articleEditorSourceId == null) {
                val openSource = viewModel.rssSources.value
                    .firstOrNull { it.id == openArticle.sourceId }
                // 覆盖层必须显式抬到 AnimatedContent(2f) 与分组页(3f) 之上：
                // 之前没设 zIndex，结果文章页画在下面 → 点文章"没反应"。
                OverlayEnter(zIndex = 4f) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        if (openArticle.guid.startsWith("browse:")) {
                            // 网页型源（单 URL / JS 源）：**直接打开原文**，
                            // 不走文章分类（那种源根本没有可抓的正文）。
                            RssArticleBrowserScreen(
                                article = openArticle,
                                onBack = { rssArticle = null },
                            )
                        } else {
                            // 视频 → 播放器；图片 → 收集页；抓不到正文 → 浏览器。
                            RssArticleScreen(
                                viewModel = viewModel,
                                source = openSource,
                                article = openArticle,
                                onBack = { rssArticle = null },
                                onUseImages = { urls, headers, selected ->
                                    // 「用这些图」：交回文章列表，由它打开选择器。
                                    viewModel.setRssBrowserResult(urls)
                                    viewModel.rssBrowserHeaders = headers
                                    viewModel.rssBrowserSelected = selected
                                    viewModel.rssBrowserArticle = openArticle
                                    rssArticle = null
                                },
                            )
                        }
                    }
                }
            }
            // 从文章界面进的源编辑器：画在**最后**，也就是压在最上面。
            // 关掉这一层就回到刚才那篇文章。
            articleEditorSourceId?.let { editingId ->
                // 返回键：只关掉编辑器这一层，回到下面那篇文章。
                // 必须注册在**这里**（叠层内部），后注册者优先 —— 写在叠层外面会被
                // 通用的返回处理抢先（那个走 leaveSourceEditor()，会退回订阅列表）。
                BackHandler {
                    com.wallpaperswitcher.util.AppLog.d("NavBack", "editor overlay -> back to article")
                    articleEditorSourceId = null
                }
                OverlayEnter(zIndex = 5f) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        RssSourceEditScreen(
                            viewModel = viewModel,
                            sourceId = editingId,
                            // 编辑器里的「登录」仍走整页流程（登录页本身是全屏
                            // WebView，不适合再叠一层），登录完回到列表再进编辑。
                            onLogin = { id ->
                                articleEditorSourceId = null
                                returnToSourceId = id
                                currentScreen = Screen.RssLogin(id)
                            },
                            onActionsReady = { editorActions = it },
                            onDone = { saved ->
                                if (saved) editorReload++
                                // 返回文章：文章还在下面，没有被销毁。
                                articleEditorSourceId = null
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 浏览器叠层的进场：淡入 + 轻微上浮。叠层关闭不做动画 —— 和页面切换同一个
 * 理由：WebView 很重，退出时立即释放比动画更重要。
 *
 * 叠层之间的**前后顺序由代码里的先后决定**（后写的画在上面）：文章叠层在前、
 * 从文章里打开的源编辑器在后，所以编辑器压住文章。反过来的话编辑器会被
 * 文章盖住 —— 那正是"点编辑后编辑页被图片/视频覆盖"这个 bug 的成因。
 */
@Composable
private fun OverlayEnter(zIndex: Float = 1f, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = HiMotion.enter(),
        label = "hiOverlayAlpha",
    )
    val lift by animateFloatAsState(
        targetValue = if (shown) 0f else 1f,
        animationSpec = HiMotion.enter(),
        label = "hiOverlayLift",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(zIndex)
            .graphicsLayer {
                this.alpha = alpha
                translationY = size.height * 0.03f * lift
            },
    ) {
        content()
    }
}

sealed class Screen {
    data object Home : Screen()
    data class GroupDetail(
        val groupId: Long,
        /** 点进来的那一刻就知道的名字/数量：子页面首帧直接用，不必等 Flow。 */
        val initialName: String = "",
        val initialCount: Int = 0,
    ) : Screen()
    data object Settings : Screen()
    data object Subscriptions : Screen()

    /** 底栏「WebDAV」：网盘来源的浏览、查看与下载。 */
    data object WebDav : Screen()
    data class SubscriptionArticles(val sourceId: Long) : Screen()
    data class RssLogin(val sourceId: Long) : Screen()
    data class RssSourceEdit(val sourceId: Long) : Screen()

    /** 最近显示（回滚）。 */
    data object Recent : Screen()

    /** 存储与流量守门。 */
    data object Storage : Screen()

    // --- 设置的分页（每一项设置都进自己的页面，主界面只列入口） ---
    /** 壁纸设置：切换模式 / 缩放 / 清晰度 / 方向适配。 */
    data object WallpaperSettings : Screen()
    /** 切换方式：定时、解锁、双击、悬浮按钮入口、过渡动画、视频。 */
    data object SwitchMethods : Screen()
    /** 文件夹自动扫描。 */
    data object FolderScan : Screen()
    /** 悬浮按钮：开关与外观（颜色 / 透明度 / 文字 / 图片）。 */
    data object ButtonAppearance : Screen()
    /** 外观：语言 / 主题模式 / 主题颜色。 */
    data object Appearance : Screen()
    /** 在线壁纸源：Bing 每日 / NASA APOD / Wikimedia / 彼岸图网 / ioliu。 */
    data object OnlineSources : Screen()
}

/** 页面层级：顶层标签是 0，子页面是 1 —— 决定进场方向（push / pop / 切标签）。 */
private fun screenDepth(screen: Screen): Int = when (screen) {
    is Screen.Home, is Screen.Settings, is Screen.Subscriptions,
    is Screen.WebDav -> 0
    is Screen.GroupDetail,
    is Screen.SubscriptionArticles,
    is Screen.RssLogin,
    is Screen.RssSourceEdit,
    is Screen.Recent,
    is Screen.Storage,
    is Screen.WallpaperSettings,
    is Screen.SwitchMethods,
    is Screen.FolderScan,
    is Screen.ButtonAppearance,
    is Screen.Appearance,
    is Screen.OnlineSources -> 1
}

private val ScreenSaver = Saver<Screen, String>(
    save = { screen ->
        when (screen) {
            is Screen.Home -> "home"
            is Screen.GroupDetail -> "group:${screen.groupId}"
            is Screen.Settings -> "settings"
            is Screen.Subscriptions -> "subscriptions"
            is Screen.SubscriptionArticles -> "rss:${screen.sourceId}"
            is Screen.RssLogin -> "rsslogin:${screen.sourceId}"
            is Screen.RssSourceEdit -> "rssedit:${screen.sourceId}"
            is Screen.Recent -> "recent"
            is Screen.Storage -> "storage"
            is Screen.WallpaperSettings -> "set-wallpaper"
            is Screen.SwitchMethods -> "set-switch"
            is Screen.FolderScan -> "set-scan"
            is Screen.ButtonAppearance -> "set-button"
            is Screen.Appearance -> "set-appearance"
            is Screen.OnlineSources -> "set-online-sources"
            is Screen.WebDav -> "webdav"
        }
    },
    restore = { value ->
        when {
            value == "home" -> Screen.Home
            value == "webdav" -> Screen.WebDav
            value == "settings" -> Screen.Settings
            value == "subscriptions" -> Screen.Subscriptions
            value == "recent" -> Screen.Recent
            value == "storage" -> Screen.Storage
            value == "set-wallpaper" -> Screen.WallpaperSettings
            value == "set-switch" -> Screen.SwitchMethods
            value == "set-scan" -> Screen.FolderScan
            value == "set-button" -> Screen.ButtonAppearance
            value == "set-appearance" -> Screen.Appearance
            value == "set-online-sources" -> Screen.OnlineSources
            value.startsWith("rss:") ->
                value.removePrefix("rss:").toLongOrNull()
                    ?.let { Screen.SubscriptionArticles(it) }
                    ?: Screen.Subscriptions
            value.startsWith("rsslogin:") ->
                value.removePrefix("rsslogin:").toLongOrNull()
                    ?.let { Screen.RssLogin(it) }
                    ?: Screen.Subscriptions
            value.startsWith("rssedit:") ->
                value.removePrefix("rssedit:").toLongOrNull()
                    ?.let { Screen.RssSourceEdit(it) }
                    ?: Screen.Subscriptions
            value.startsWith("group:") ->
                value.removePrefix("group:").toLongOrNull()?.let { Screen.GroupDetail(it) }
                    ?: Screen.Home
            else -> Screen.Home
        }
    }
)

/**
 * 设置页 = 设置主界面 + 它下面的所有子页（壁纸设置 / 切换方式 / 文件夹扫描 /
 * 悬浮按钮 / 外观 / 最近显示 / 存储与流量）。
 *
 * 底栏 tab 只按这份集合判断：之前这里只认 `Screen.Settings` 本身，所以从设置
 * 点进任何一个子页，底栏都会从「设置」跳回「首页」（用户反馈）。
 */
internal fun Screen.isSettingsPage(): Boolean = when (this) {
    is Screen.Settings,
    is Screen.WallpaperSettings,
    is Screen.SwitchMethods,
    is Screen.FolderScan,
    is Screen.ButtonAppearance,
    is Screen.Appearance,
    is Screen.Recent,
    is Screen.Storage,
    // 在线壁纸源是从「设置」点进去的，漏了它底栏就会跳回「首页」（用户反馈）。
    is Screen.OnlineSources,
    -> true
    else -> false
}

/**
 * 阅读的 `menu_login` 只在源配了 `loginUrl` 时出现
 * （`RssSortActivity.kt:301-305`）：没配登录的源点了只会打开源首页。
 */
internal fun rssHasLogin(source: com.wallpaperswitcher.data.RssSource): Boolean =
    com.wallpaperswitcher.engine.legado.RssSourceEditor
        .fieldValue(source.rawJson, "loginUrl")
        .isNotBlank()

/**
 * 常驻的顶层 Tab 容器。
 *
 * 非激活时**不画**（`drawWithContent`）+ 摘掉语义树 + 吞掉输入，但仍然留在组合里，
 * 切回来不用重建页面；激活时做一次 150ms 的"轻微起浮"（只有 transform，没有 alpha），
 * 手感上不再是硬切，也不会因为图层 alpha 让无障碍读不到内容。
 * 并在 `PointerEventPass.Initial` 阶段吞掉输入 —— 否则隐藏页会抢到下层的点击。
 */
@Composable
private fun TabSlot(
    active: Boolean,
    /** 进入时是否播"缩放+上浮"的落定动画；从子页面返回时传 false。 */
    settleIn: Boolean = true,
    content: @Composable () -> Unit,
) {
    // 进场只用 transform（缩放 + 位移），不用 alpha：alpha 图层会让无障碍看不到内容。
    val settle = remember { Animatable(0f) }
    LaunchedEffect(active, settleIn) {
        if (active) {
            if (!settleIn) {
                // 从子页面返回：直接到位，不播缩放/上浮（否则底下页面会整体缩一下）。
                settle.snapTo(1f)
                return@LaunchedEffect
            }
            settle.snapTo(0f)
            // 弹簧而不是 tween：被打断（连点 Tab）时位置/缩放连续，不会"重头再来"。
            settle.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 800f))
        }
    }
    Box(
        modifier = Modifier
            // 激活页必须置顶：否则它会被排在后面的隐藏槽盖住，点击先被隐藏槽吞掉
            // （真机表现：首页/设置里的按钮点了没反应）。
            .zIndex(if (active) 1f else 0f)
            .fillMaxSize()
            // 非激活时：不画、并把整棵语义树摘掉（否则 TalkBack/uiautomator 会读到隐藏页），
            // 同时在 Initial 阶段吞掉输入。用 drawWithContent 而非图层 alpha，
            // 是为了让"激活页"仍然有完整的无障碍节点。
            .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
            .graphicsLayer {
                val t = settle.value
                val s = 0.985f + 0.015f * t
                scaleX = s
                scaleY = s
                translationY = (1f - t) * 12f
            }
            .drawWithContent { if (active) drawContent() }
            // 隐藏页不能吃触摸：在 Initial 阶段把手势全部消费掉。
            .pointerInput(active) {
                if (!active) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { change -> change.consume() }
                        }
                    }
                }
            }
    ) {
        content()
    }
}

/** 顶栏四个常驻页之一（它们不在 AnimatedContent 里渲染）。 */
internal fun Screen.isTopLevelTab(): Boolean = when (this) {
    is Screen.Home, is Screen.Settings, is Screen.WebDav, is Screen.Subscriptions -> true
    else -> false
}
