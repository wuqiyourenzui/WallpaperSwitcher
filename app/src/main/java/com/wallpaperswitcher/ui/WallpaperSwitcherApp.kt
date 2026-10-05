package com.wallpaperswitcher.ui

import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wallpaperswitcher.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.ui.screens.*
import com.wallpaperswitcher.ui.theme.HiMotion
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.flow.collectLatest

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
    // 状态如果在页面内部 remember，从这里跳到「存储/收藏/最近」再返回就会新建一个
    // → 回到顶部。由这里持有，返回时停在原来的位置。
    val settingsScrollState = androidx.compose.foundation.rememberScrollState()

    // 订阅源列表的「多选」入口从列表上方那一行搬到了顶栏：那一行会在标题
    // 下方留出一整条空白（用户反馈「订阅与文章间有空白」）。顶栏点击后把
    // 计数 +1，列表页收到变化即进入多选模式。
    var sourceSelectionRequest by remember { mutableStateOf(0) }

    // 登录 / 编辑是从「某个源的文章列表」打开的：退出时要回到那个文章列表，
    // 而不是一路退到订阅的源列表（用户反馈）。
    var returnToSourceId by remember { mutableStateOf<Long?>(null) }
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
                            is Screen.Subscriptions,
                            is Screen.SubscriptionArticles ->
                                stringResource(R.string.title_subscriptions)
                            is Screen.RssLogin -> stringResource(R.string.rss_login_title)
                            is Screen.RssSourceEdit -> stringResource(R.string.rss_edit_title)
                            is Screen.Browse -> stringResource(R.string.browse_title)
                            is Screen.Favorites -> stringResource(R.string.favorites_title)
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
                        }
                    Crossfade(
                        targetState = titleText,
                        animationSpec = HiMotion.standard(HiMotion.ShortMs),
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
                        currentScreen is Screen.Browse ||
                        currentScreen is Screen.Favorites ||
                        currentScreen is Screen.Recent ||
                        currentScreen is Screen.Storage ||
                        currentScreen is Screen.WallpaperSettings ||
                        currentScreen is Screen.SwitchMethods ||
                        currentScreen is Screen.FolderScan ||
                        currentScreen is Screen.ButtonAppearance ||
                        currentScreen is Screen.Appearance
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
                                    is Screen.Appearance, is Screen.Favorites,
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
                            TextButton(onClick = {
                                returnToSourceId = screen.sourceId
                                currentScreen = Screen.RssLogin(screen.sourceId)
                            }) {
                                Text(stringResource(R.string.rss_login))
                            }
                            TextButton(onClick = {
                                returnToSourceId = screen.sourceId
                                currentScreen = Screen.RssSourceEdit(screen.sourceId)
                            }) {
                                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.rss_edit))
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
                currentScreen.isSettingsPage() -> 2
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
                        2 -> Screen.Settings
                        else -> Screen.Home
                    }
                },
            )
        }
    ) { padding ->
        // 全屏文章页（播放器 / 收集页）是叠层：返回键/手势必须先关它，
        // 不能漏到下面的常规返回处理（以前图片收集页按返回不退出就是这个原因：
        // 这里只判了视频那个状态，收集页的状态没人管）。
        if (rssArticle != null) {
            BackHandler { rssArticle = null }
        }
        // Handle system back button for non-home screens (only while the
        // article overlay is closed, so back never hits both at once).
        //
        // 「设置」和「订阅」是底栏的顶层标签：它们的返回键**直接退出应用**，
        // 不再跳到首页 —— 从首页跳过去、按返回又回到首页，看起来就像返回没生效。
        // 所以这两个标签不注册 BackHandler，交给系统默认行为（结束 Activity）。
        // 只有真正有父页面的子页面（分组详情 / 文章列表 / 登录 / 编辑 / 大图 /
        // 收藏 / 最近 / 存储）才拦截返回。
        val backHandled = when (currentScreen) {
            is Screen.Home, is Screen.Settings, is Screen.Subscriptions -> false
            else -> true
        }
        if (backHandled && rssArticle == null) {
            BackHandler {
                // Back goes to the logical parent, not always the home screen.
                when (currentScreen) {
                    is Screen.SubscriptionArticles -> currentScreen = Screen.Subscriptions
                    // 登录/编辑返回时回到打开它的文章列表（见 leaveSourceEditor）。
                    is Screen.RssLogin, is Screen.RssSourceEdit -> leaveSourceEditor()
                    is Screen.Recent, is Screen.Storage,
                    is Screen.WallpaperSettings, is Screen.SwitchMethods,
                    is Screen.FolderScan, is Screen.ButtonAppearance,
                    is Screen.Appearance -> currentScreen = Screen.Settings
                    is Screen.Favorites -> currentScreen = Screen.Settings
                    is Screen.Browse -> currentScreen = Screen.Home
                    else -> currentScreen = Screen.Home
                }
            }
        }

        Box(modifier = Modifier.padding(padding)) {
            // 只做「进场」动画、不做退场：旧页面立即释放，动画期间只有目标页
            // 在合成。历史版本用整屏 crossfade 让两个重页面同屏动画，部分机型
            // 掉帧（见 git 历史）；这里保留了那个教训，动效只落在轻元素上。
            AnimatedContent(
                targetState = currentScreen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val slideSpec = HiMotion.enter<IntOffset>()
                    val forward = screenDepth(targetState) > screenDepth(initialState)
                    val backward = screenDepth(targetState) < screenDepth(initialState)
                    val enter = when {
                        // 进入子页面：从右侧滑入一小段（HyperOS 的 push）。
                        forward -> fadeIn(HiMotion.enter()) +
                            slideInHorizontally(slideSpec) { it / 8 }
                        // 返回：从左侧滑回。
                        backward -> fadeIn(HiMotion.enter()) +
                            slideInHorizontally(slideSpec) { -it / 8 }
                        // 顶层标签之间：轻微上浮 + 淡入。
                        else -> fadeIn(HiMotion.enter()) +
                            slideInVertically(slideSpec) { it / 28 }
                    }
                    enter togetherWith ExitTransition.None
                },
                label = "hiScreen",
            ) { screen ->
            when (screen) {
                is Screen.Home -> HomeScreen(
                    viewModel = viewModel,
                    onGroupClick = { groupId ->
                        viewModel.selectGroup(groupId)
                        currentScreen = Screen.GroupDetail(groupId)
                    },
                    // 卡片上的「浏览」：直接进大图预览（不用先进九宫格）。
                    onGroupBrowse = { groupId -> currentScreen = Screen.Browse(groupId) },
                )
                is Screen.GroupDetail -> {
                    // Leaving the group screen releases its full media list: the
                    // detail screen loads EVERY media of the group (no paging, by
                    // design - the fast scroller needs the whole list), and until
                    // now that list stayed in memory until the process died or
                    // another group was opened.
                    DisposableEffect(screen.groupId) {
                        onDispose { viewModel.selectGroup(null) }
                    }
                    GroupDetailScreen(
                        viewModel = viewModel,
                        groupId = screen.groupId,
                        onBack = { currentScreen = Screen.Home },
                    )
                }
                is Screen.Browse -> MediaBrowseScreen(
                    viewModel = viewModel,
                    startGroupId = screen.groupId,
                    onBack = { currentScreen = Screen.GroupDetail(screen.groupId) },
                )
                is Screen.Favorites -> FavoritesScreen(
                    viewModel = viewModel,
                    // 收藏页现在只从设置主界面进（§4.9.148），退出的父级就是设置；
                    // 之前回首页和顶栏箭头 / 外层 BackHandler 的分支都不一致。
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.Recent -> RecentScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Settings },
                )
                is Screen.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    onOpenScreen = { currentScreen = it },
                    onOpenWizard = { showWizard = true },
                    scrollState = settingsScrollState,
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
                is Screen.Subscriptions -> SubscriptionScreen(
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
                is Screen.SubscriptionArticles -> SubscriptionArticlesScreen(
                    viewModel = viewModel,
                    sourceId = screen.sourceId,
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
                    onDone = { leaveSourceEditor() }
                )
            }
            }
            // 全屏文章页画在当前页面之上（不替换它）：替换会销毁文章列表，
            // 关掉之后列表滚动位置就没了，用户会回到源的顶部。
            val openArticle = rssArticle
            if (openArticle != null) {
                val openSource = viewModel.rssSources.value
                    .firstOrNull { it.id == openArticle.sourceId }
                OverlayEnter {
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
        }
    }
}

/**
 * 浏览器叠层的进场：淡入 + 轻微上浮。叠层关闭不做动画 —— 和页面切换同一个
 * 理由，WebView 很重，退出时立即释放比动画更重要。
 */
@Composable
private fun OverlayEnter(content: @Composable () -> Unit) {
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
    data class GroupDetail(val groupId: Long) : Screen()
    data object Settings : Screen()
    data object Subscriptions : Screen()
    data class SubscriptionArticles(val sourceId: Long) : Screen()
    data class RssLogin(val sourceId: Long) : Screen()
    data class RssSourceEdit(val sourceId: Long) : Screen()

    /** 大图浏览（Stories 式）：从某个分组的封面进去。 */
    data class Browse(val groupId: Long) : Screen()

    /** 收藏聚合页（跨分组）。 */
    data object Favorites : Screen()

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
}

/** 页面层级：顶层标签是 0，子页面是 1 —— 决定进场方向（push / pop / 切标签）。 */
private fun screenDepth(screen: Screen): Int = when (screen) {
    is Screen.Home, is Screen.Settings, is Screen.Subscriptions -> 0
    is Screen.GroupDetail,
    is Screen.SubscriptionArticles,
    is Screen.RssLogin,
    is Screen.RssSourceEdit,
    is Screen.Browse,
    is Screen.Favorites,
    is Screen.Recent,
    is Screen.Storage,
    is Screen.WallpaperSettings,
    is Screen.SwitchMethods,
    is Screen.FolderScan,
    is Screen.ButtonAppearance,
    is Screen.Appearance -> 1
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
            is Screen.Browse -> "browse:${screen.groupId}"
            is Screen.Favorites -> "favorites"
            is Screen.Recent -> "recent"
            is Screen.Storage -> "storage"
            is Screen.WallpaperSettings -> "set-wallpaper"
            is Screen.SwitchMethods -> "set-switch"
            is Screen.FolderScan -> "set-scan"
            is Screen.ButtonAppearance -> "set-button"
            is Screen.Appearance -> "set-appearance"
        }
    },
    restore = { value ->
        when {
            value == "home" -> Screen.Home
            value == "settings" -> Screen.Settings
            value == "subscriptions" -> Screen.Subscriptions
            value == "favorites" -> Screen.Favorites
            value == "recent" -> Screen.Recent
            value == "storage" -> Screen.Storage
            value == "set-wallpaper" -> Screen.WallpaperSettings
            value == "set-switch" -> Screen.SwitchMethods
            value == "set-scan" -> Screen.FolderScan
            value == "set-button" -> Screen.ButtonAppearance
            value == "set-appearance" -> Screen.Appearance
            value.startsWith("browse:") ->
                value.removePrefix("browse:").toLongOrNull()?.let { Screen.Browse(it) }
                    ?: Screen.Home
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
 * 悬浮按钮 / 外观 / 收藏 / 最近显示 / 存储与流量）。
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
    is Screen.Favorites,
    is Screen.Recent,
    is Screen.Storage,
    -> true
    else -> false
}

