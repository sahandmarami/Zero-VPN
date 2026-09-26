package com.v2ray.ang.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.CoreUpdateManager
import com.v2ray.ang.ui.compose.InputDialog
import com.v2ray.ang.ui.compose.InputField
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.QRCodeDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
    selectedServerName: String = "",
    selectedServerGeo: com.v2ray.ang.handler.GeoLookupManager.ServerGeo? = null,
    zeroAppUpdate: Pair<String, String>? = null,
    zeroCoreUpdate: CoreUpdateManager.CoreUpdateResult? = null,
    onOpenZeroUpdate: () -> Unit = {},
    onDismissZeroUpdate: () -> Unit = {},
    onEditSubscription: (String) -> Unit = {},
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    val isDarkTheme = LocalDarkTheme.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf(ZeroBottomTab.HOME) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf<String?>(null) }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    // Zero VPN: add-subscription dialog + delete-subscription confirmation.
    var showAddSubDialog by remember { mutableStateOf(false) }
    var deleteSubTarget by remember { mutableStateOf<String?>(null) }
    // Zero VPN: quota info of every subscription (used / total / expire).
    val subscriptionInfo by mainViewModel.subscriptionInfo.collectAsStateWithLifecycle()
    val removeServer: (String) -> Unit = { guid ->
        if (confirmRemove) showRemoveConfirm = guid else onAction(MainAction.RemoveServer(guid))
    }

    // Real delay of the selected server from its group rows (shown on the home card).
    val selectedGroupState by mainViewModel
        .serverGroupState(uiState.selectedGroupId)
        .collectAsStateWithLifecycle()
    val selectedServerDelay = remember(selectedGroupState, selectedGuid) {
        selectedGroupState.rows
            .firstOrNull { it.guid == selectedGuid }?.testDelayMillis ?: -1L
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    // Zero VPN: dialog to add a subscription by pasting its sub link.
    if (showAddSubDialog) {
        var subUrl by remember { mutableStateOf("") }
        var subRemarks by remember { mutableStateOf("") }
        InputDialog(
            title = stringResource(R.string.zero_menu_add_subscription),
            fields = listOf(
                InputField(
                    label = stringResource(R.string.zero_subscription_url_hint),
                    value = subUrl,
                ),
                InputField(
                    label = stringResource(R.string.zero_subscription_remarks_hint),
                    value = subRemarks,
                ),
            ),
            onFieldChange = { index, value ->
                if (index == 0) subUrl = value else subRemarks = value
            },
            confirmText = stringResource(R.string.action_ok),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                showAddSubDialog = false
                mainViewModel.addSubscription(url = subUrl, remarks = subRemarks)
            },
            onDismiss = { showAddSubDialog = false }
        )
    }
    if (deleteSubTarget != null) {
        val subId = deleteSubTarget!!
        DeleteConfirmDialog(
            message = stringResource(R.string.zero_subscription_delete_confirm),
            onConfirm = {
                deleteSubTarget = null
                mainViewModel.deleteSubscription(subId)
            },
            onDismiss = { deleteSubTarget = null }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                drawerState = drawerState,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                }
            )
        }
    ) {
        // Zero VPN: clean reference-style backdrop — deep charcoal-navy with a
        // soft neon-blue glow behind the status area. The light mode gets a
        // pale azure sky with layered neon glows for a livelier feel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkTheme) Color(0xFF070B11) else launchWash.base)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                if (isDarkTheme) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF0A5E96).copy(alpha = 0.32f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.13f),
                            radius = size.width * 0.95f
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF35C6FF).copy(alpha = 0.09f), Color.Transparent),
                            center = Offset(size.width * 0.1f, size.height * 0.04f),
                            radius = size.width * 0.75f
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF0A5E96).copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.72f),
                            radius = size.width * 0.9f
                        )
                    )
                } else {
                    val wash = launchWash
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(wash.glowTop.copy(alpha = 0.30f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.10f),
                            radius = size.width * 1.0f
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(wash.glowLeft.copy(alpha = 0.16f), Color.Transparent),
                            center = Offset(size.width * 0.08f, size.height * 0.04f),
                            radius = size.width * 0.8f
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(wash.glowBottom.copy(alpha = 0.14f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.86f),
                            radius = size.width * 0.95f
                        )
                    )
                }
            }

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
                topBar = {
                    when (currentTab) {
                        ZeroBottomTab.HOME -> ZeroHomeTopBar()
                        ZeroBottomTab.LOCATIONS -> MainTopBar(
                            isLoading = isLoading,
                            showSearch = showSearch,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { query: String ->
                                searchQuery = query
                                onAction(MainAction.Search(query))
                            },
                            onSearchClose = {
                                searchQuery = ""
                                onAction(MainAction.Search(""))
                                showSearch = false
                            },
                            onSearchToggle = { show: Boolean -> showSearch = show },
                            onMenuClick = { scope.launch { drawerState.open() } },
                            onAction = { action ->
                                // Zero VPN: the add-subscription entry opens a
                                // dialog here instead of routing to the Activity.
                                if (action is MainAction.ImportSubscription) {
                                    showAddSubDialog = true
                                } else {
                                    onAction(action)
                                }
                            },
                            onMoreMenuAction = { action ->
                                when (action) {
                                    MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                                    MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                                    MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                                    MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                                    MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                                    MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                                    MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                                    MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                                    MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                                    MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                                }
                            }
                        )
                        ZeroBottomTab.SETTINGS -> ZeroSettingsTopBar()
                        else -> {}
                    }
                },
                bottomBar = {
                    ZeroBottomNav(
                        selectedTab = currentTab,
                        onSelectTab = { tab ->
                            currentTab = tab
                            if (tab == ZeroBottomTab.LOCATIONS) {
                                showSearch = false
                            }
                        },
                        isDarkTheme = isDarkTheme
                    )
                },
                floatingActionButton = {},
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // Directional slide + fade between tabs — keeps the switch
                    // feeling liquid and premium, in sync with the goo bar.
                    AnimatedContent(
                        targetState = currentTab,
                        transitionSpec = {
                            val forward = targetState.ordinal >= initialState.ordinal
                            val dir = if (forward) 1 else -1
                            (
                                slideInHorizontally(
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) { full -> dir * full / 5 } +
                                    fadeIn(tween(300, easing = LinearOutSlowInEasing))
                                ) togetherWith (
                                slideOutHorizontally(
                                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                                ) { full -> -dir * full / 6 } +
                                    fadeOut(tween(160))
                                )
                        },
                        label = "zeroTabContent"
                    ) { tab ->
                    when (tab) {
                        ZeroBottomTab.HOME -> {
                            ZeroUpdateBanners(
                                appUpdateVersion = zeroAppUpdate?.first,
                                onUpdateApp = onOpenZeroUpdate,
                                coreUpdate = zeroCoreUpdate,
                                onDismiss = onDismissZeroUpdate
                            )
                            ZeroHomeScreen(
                                isRunning = isRunning,
                                status = uiState.status,
                                selectedServerName = selectedServerName,
                                selectedServerDelay = selectedServerDelay,
                                selectedServerGeo = selectedServerGeo,
                                selectedServerQuota = mainViewModel.selectedServerQuota.collectAsStateWithLifecycle().value,
                                onToggle = { onAction(MainAction.ToggleService) },
                                onTestCurrent = { onAction(MainAction.TestCurrentServer) },
                                onTestAll = { onAction(MainAction.TestRealAllServers) },
                                onOpenLocations = { currentTab = ZeroBottomTab.LOCATIONS }
                            )
                        }
                        ZeroBottomTab.LOCATIONS -> {
                            if (groups.isNotEmpty()) {
                                // Zero VPN fix (v1.4.5): GroupTabBar and the pager were
                                // siblings inside AnimatedContent's content lambda, which
                                // stacks children in a Box — the tab row and the page
                                // (subscription header card) rendered on top of each other.
                                // A single group hid the bug because the tab bar wasn't
                                // emitted at all. Wrap them in a Column to stack properly.
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (groups.size > 1) {
                                        GroupTabBar(
                                            groups = groups,
                                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                                            mainViewModel = mainViewModel,
                                            onTabClick = { targetIndex ->
                                                scope.launch {
                                                    pagerState.navigateToPageOptimized(
                                                        targetPage = targetIndex,
                                                        animateAdjacentPage = true
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxSize(),
                                        userScrollEnabled = true,
                                        beyondViewportPageCount = 1,
                                        key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                                    ) { page ->
                                        val group = groups.getOrNull(page) ?: return@HorizontalPager

                                        GroupPagerPage(
                                            groupId = group.id,
                                            mainViewModel = mainViewModel,
                                            selectedGuid = selectedGuid,
                                            locateTarget = uiState.locateTarget,
                                            doubleColumnDisplay = doubleColumnDisplay,
                                            searchQuery = searchQuery,
                                            lazyListStates = lazyListStates,
                                            lazyGridStates = lazyGridStates,
                                            onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                                            onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                                            onShareServer = { guid, profile ->
                                                shareTarget = Triple(guid, profile, false)
                                            },
                                            onMoreServer = { guid, profile ->
                                                shareTarget = Triple(guid, profile, true)
                                            },
                                            onRemoveServer = removeServer,
                                            contentPadding = PaddingValues(
                                                start = 0.dp,
                                                top = 0.dp,
                                                end = 0.dp,
                                                bottom = 16.dp
                                            ),
                                            subscriptionItem = subscriptionInfo[group.id],
                                            onUpdateSubscription = { onAction(MainAction.UpdateSubscriptions) },
                                            onEditSubscription = { onEditSubscription(group.id) },
                                            onDeleteSubscription = { deleteSubTarget = group.id },
                                        )
                                    }
                                }
                            }
                        }
                        ZeroBottomTab.SETTINGS -> {
                            ZeroSettingsTab(
                                isRunning = isRunning,
                                onNavigate = onNavigate,
                                onModeChanged = { onAction(MainAction.RestartService) }
                            )
                        }
                        else -> {}
                    }
                    }
                }
            }
        }
    }
}
