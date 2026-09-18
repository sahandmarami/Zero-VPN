package com.v2ray.ang.ui.main

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.CoreUpdateManager
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
    zeroAppUpdate: Pair<String, String>? = null,
    zeroCoreUpdate: CoreUpdateManager.CoreUpdateResult? = null,
    onOpenZeroUpdate: () -> Unit = {},
    onDismissZeroUpdate: () -> Unit = {},
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
        // soft neon-blue glow behind the status area (light/dark rhythm).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkTheme) Color(0xFF070B11) else Color(0xFFF2F6FB))
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val glowColor = if (isDarkTheme) Color(0xFF0A5E96) else Color(0xFF35C6FF)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = if (isDarkTheme) 0.32f else 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height * 0.13f),
                        radius = size.width * 0.95f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = if (isDarkTheme) 0.10f else 0.08f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height * 0.72f),
                        radius = size.width * 0.9f
                    )
                )
            }

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
                topBar = {
                    when (currentTab) {
                        ZeroBottomTab.HOME -> ZeroHomeTopBar(
                            onMenuClick = { scope.launch { drawerState.open() } },
                            onSettingsClick = { onNavigate(MainDestination.Settings) }
                        )
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
                            onAction = onAction,
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
                        onOpenSettings = { onNavigate(MainDestination.Settings) },
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
                    when (currentTab) {
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
                                onToggle = { onAction(MainAction.ToggleService) },
                                onTestCurrent = { onAction(MainAction.TestCurrentServer) },
                                onTestAll = { onAction(MainAction.TestRealAllServers) },
                                onOpenLocations = { currentTab = ZeroBottomTab.LOCATIONS }
                            )
                        }
                        ZeroBottomTab.LOCATIONS -> {
                            if (groups.isNotEmpty()) {
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
                                        )
                                    )
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
