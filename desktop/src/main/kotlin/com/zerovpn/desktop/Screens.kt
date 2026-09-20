package com.zerovpn.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// LOCATIONS — 1:1 port of the Android servers screen: group tab pills,
// subscription card (quota bar / expiry / refresh), server rows with a
// country-flag chip + share/edit/delete actions.
// ---------------------------------------------------------------------------
@Composable
fun LocationsScreen(modifier: Modifier = Modifier) {
    var showAddSub by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProfileRec?>(null) }
    var editTarget by remember { mutableStateOf<ProfileRec?>(null) }
    var editSubTarget by remember { mutableStateOf<SubRec?>(null) }
    var deleteSubTarget by remember { mutableStateOf<SubRec?>(null) }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var groupChosen by remember { mutableStateOf(false) }

    val hc = zeroHomeColors
    val tabs = Store.groupTabs()
    val activeGroup: String? = if (groupChosen) Store.selectedGroup else tabs.firstOrNull()?.first
    val sub = Store.subscriptionFor(activeGroup)
    val profiles = Store.profilesForGroup(activeGroup).filter {
        searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
    }

    // Geo (flag + IP) prefetch for every host in the visible group.
    LaunchedEffect(profiles.map { it.id }) {
        profiles.forEach { p ->
            ServerInfo.hostPort(p.link)?.first?.let { GeoLookup.ensureLookup(it) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        // --- Header (like the Android locations top bar):
        // hamburger (drawer) | title | search + add + overflow ----------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { Store.drawerOpen = true }) {
                Icon(ZeroIcons.menu, "منو", tint = hc.textPrimary)
            }
            Text(
                "فایل کانفیگ",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) searchQuery = ""
            }) {
                Icon(ZeroIcons.search, "جستجو", tint = hc.textSecondary)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(ZeroIcons.add, "افزودن", tint = hc.textPrimary)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = colorCard,
                ) {
                    ZeroDropdownItem("افزودن اشتراک") {
                        showMenu = false
                        showAddSub = true
                    }
                    ZeroDropdownItem("از کلیپ‌بورد") {
                        showMenu = false
                        Connector.importClipboard()
                    }
                    ZeroDropdownItem("بروزرسانی اشتراک‌ها") {
                        showMenu = false
                        Connector.updateSubs()
                    }
                }
            }
            // Overflow (three-dot) menu — same entries as the Android app.
            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(ZeroIcons.moreVert, "بیشتر", tint = hc.textPrimary)
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    containerColor = colorCard,
                ) {
                    ZeroDropdownItem("تست همه سرورها") {
                        showMoreMenu = false
                        Connector.testAll()
                    }
                    ZeroDropdownItem("بروزرسانی اشتراک‌ها") {
                        showMoreMenu = false
                        Connector.updateSubs()
                    }
                    ZeroDropdownItem("حذف همه سرورها") {
                        showMoreMenu = false
                        showDelAllConfirm = true
                    }
                }
            }
        }
        if (showSearch) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("جستجو…", fontSize = 13.sp, color = hc.textSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colorPill,
                    unfocusedContainerColor = colorPill,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = hc.textPrimary,
                    unfocusedTextColor = hc.textPrimary,
                    cursorColor = colorZeroNeon,
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(4.dp))

        // --- Group tab bar (port of GroupTabBar) ----------------------------
        if (tabs.size > 1 || Store.subscriptions.isNotEmpty()) {
            val tabScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colorCard.copy(alpha = 0.92f))
                    .horizontalScroll(tabScroll)
                    .padding(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { (url, count) ->
                    val name = if (url == null) "Default" else Store.subscriptionFor(url)?.name ?: "اشتراک"
                    val selected = url == activeGroup
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) hc.accent.copy(alpha = 0.18f) else Color.Transparent
                            )
                            .clickable {
                                Store.selectedGroup = url
                                groupChosen = true
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = "$name ($count)",
                            color = if (selected) hc.accent else hc.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // --- Subscription card (port of the Zero sub card) ------------------
        if (sub != null) {
            SubscriptionCard(
                sub = sub,
                onRefresh = {
                    Store.scope.launch {
                        val (n, msg) = withContext(Dispatchers.IO) { Profiles.refreshSingleSub(sub.url) }
                        Store.toast(msg)
                    }
                },
                onEdit = { editSubTarget = sub },
                onDelete = { deleteSubTarget = sub },
            )
            Spacer(Modifier.height(4.dp))
        }

        if (profiles.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text(
                if (Store.profiles.isEmpty())
                    "هنوز سروری ندارید\nبا دکمه + اشتراک یا کانفیگ اضافه کنید"
                else if (searchQuery.isNotBlank()) "نتیجه‌ای برای «$searchQuery» پیدا نشد"
                else "این گروه خالی است",
                fontSize = 13.sp,
                color = hc.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(profiles, key = { it.id }) { p ->
                    ServerRow(
                        profile = p,
                        selected = p.id == Store.selectedId,
                        onSelect = { Store.select(p.id) },
                        onShare = {
                            setClipboard(p.link)
                            Store.toast("لینک کانفیگ کپی شد")
                        },
                        onEdit = { editTarget = p },
                        onDelete = { deleteTarget = p },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colorCardBorder.copy(alpha = 0.6f))
                        )
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    if (showAddSub) AddSubDialog(onDismiss = { showAddSub = false })
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "حذف سرور",
            message = "«${target.name}» حذف شود؟",
            confirmLabel = "حذف",
            onDismiss = { deleteTarget = null },
            onConfirm = {
                Store.deleteProfile(target.id)
                deleteTarget = null
                Store.toast("سرور حذف شد")
            }
        )
    }
    editTarget?.let { target ->
        RenameDialog(
            title = "ویرایش سرور",
            initial = target.name,
            onDismiss = { editTarget = null },
            onConfirm = { newName ->
                Store.renameProfile(target.id, newName)
                editTarget = null
                Store.toast("نام سرور ذخیره شد")
            }
        )
    }
    editSubTarget?.let { target ->
        RenameDialog(
            title = "ویرایش اشتراک",
            initial = target.name,
            onDismiss = { editSubTarget = null },
            onConfirm = { newName ->
                Store.updateSubscription(target.url, newName)
                editSubTarget = null
                Store.toast("نام اشتراک ذخیره شد")
            }
        )
    }
    deleteSubTarget?.let { target ->
        ConfirmDialog(
            title = "حذف اشتراک",
            message = "«${target.name}» و همه سرورهای آن حذف شود؟",
            confirmLabel = "حذف",
            onDismiss = { deleteSubTarget = null },
            onConfirm = {
                Store.removeSubscription(target.url)
                Store.selectedGroup = null
                groupChosen = false
                deleteSubTarget = null
                Store.toast("اشتراک حذف شد")
            }
        )
    }
    if (showDelAllConfirm) {
        ConfirmDialog(
            title = "حذف همه سرورها",
            message = "همه کانفیگ‌ها و اشتراک‌ها حذف شوند؟ این عمل قابل بازگشت نیست.",
            confirmLabel = "حذف",
            onDismiss = { showDelAllConfirm = false },
            onConfirm = {
                showDelAllConfirm = false
                Store.clearProfiles()
                Store.selectedGroup = null
                groupChosen = false
                Store.toast("همه سرورها حذف شدند")
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Drawer (servers-screen hamburger) — slides over from the start (right) edge,
// exactly like the phone's navigation drawer in RTL.
// ---------------------------------------------------------------------------
@Composable
fun AppDrawer() {
    val hc = zeroHomeColors
    Row(Modifier.fillMaxSize()) {
        // Panel occupies the trailing side in RTL — place it first so RTL
        // mirroring puts it on the right, exactly like the phone drawer.
        Column(
            modifier = Modifier
                .width(290.dp)
                .fillMaxHeight()
                .background(hc.cardBg)
                .padding(16.dp)
        ) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val logoPainter = remember { loadLogoPainter() }
                logoPainter?.let {
                    androidx.compose.foundation.Image(
                        painter = it, contentDescription = null, modifier = Modifier.size(34.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("Zero VPN", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = hc.textPrimary)
            }
            Spacer(Modifier.height(8.dp))
            Text("v$APP_VERSION", fontSize = 11.sp, color = hc.textSecondary)
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.HorizontalDivider(color = hc.cardBorder)

            Spacer(Modifier.height(10.dp))
            DrawerItem(ZeroIcons.refresh, "بروزرسانی اشتراک‌ها") {
                Store.drawerOpen = false
                Connector.updateSubs()
            }
            DrawerItem(ZeroIcons.add, "افزودن اشتراک") {
                Store.drawerOpen = false
                Store.pendingAddSub = true
            }
            DrawerItem(ZeroIcons.copy, "از کلیپ‌بورد") {
                Store.drawerOpen = false
                Connector.importClipboard()
            }
            DrawerItem(ZeroIcons.settings, "تنظیمات") {
                Store.drawerOpen = false
                Store.view = "settings"
            }
            DrawerItem(ZeroIcons.locations, "سرورها") {
                Store.drawerOpen = false
                Store.view = "locations"
            }
        }
        // Scrim
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable { Store.drawerOpen = false }
        )
    }
}

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val hc = zeroHomeColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = hc.accent)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, color = hc.textPrimary)
    }
}

/** Group subscription card — quota progress, remaining, expiry, actions. */
@Composable
private fun SubscriptionCard(
    sub: SubRec,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val hc = zeroHomeColors
    val cardShape = RoundedCornerShape(16.dp)
    val hasQuota = sub.total > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(cardShape)
            .background(hc.accent.copy(alpha = 0.10f))
            .border(1.dp, hc.accent.copy(alpha = 0.25f), cardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                sub.name,
                Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = hc.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onRefresh, Modifier.size(34.dp)) {
                Icon(ZeroIcons.refresh, "بروزرسانی", Modifier.size(20.dp), tint = hc.accent)
            }
            IconButton(onClick = onEdit, Modifier.size(34.dp)) {
                Icon(ZeroIcons.edit, "ویرایش", Modifier.size(20.dp), tint = hc.textSecondary)
            }
            IconButton(onClick = onDelete, Modifier.size(34.dp)) {
                Icon(ZeroIcons.delete, "حذف", Modifier.size(20.dp), tint = hc.textSecondary)
            }
        }

        if (hasQuota) {
            val progress = (sub.used.toFloat() / sub.total).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(hc.accent.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(listOf(colorZeroNeonSoft, colorZeroDeep))
                        )
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${trafficString(sub.used)} از ${trafficString(sub.total)}",
                    fontSize = 12.sp,
                    color = hc.textPrimary
                )
                Spacer(Modifier.weight(1f))
                val remaining = (sub.total - sub.used).coerceAtLeast(0)
                Text(
                    text = "${trafficString(remaining)} باقی مانده",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (remaining <= 0L) hc.pingBad else hc.accent
                )
            }
        }

        val expireText = if (sub.expire > 0) {
            val expired = sub.expire * 1000L < System.currentTimeMillis()
            if (expired) "منقضی شده"
            else "انقضا: " + java.text.SimpleDateFormat(
                "yyyy/MM/dd", java.util.Locale.getDefault()
            ).format(java.util.Date(sub.expire * 1000L))
        } else ""
        val updatedText = if (sub.lastFetch > 0) {
            "بروزرسانی: " + java.text.SimpleDateFormat(
                "yyyy/MM/dd HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date(sub.lastFetch))
        } else ""
        val meta = listOf(expireText, updatedText).filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                meta,
                fontSize = 11.sp,
                color = hc.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ZeroDropdownItem(label: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp, color = colorTextPrimary) },
        onClick = onClick,
    )
}

/** Android ServerListItem look: side selection bar + name + flag chip + actions. */
@Composable
private fun ServerRow(
    profile: ProfileRec,
    selected: Boolean,
    onSelect: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val hc = zeroHomeColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .clickable { onSelect() }
    ) {
        // Selection bar (renders on the leading side, mirrored in RTL — same
        // as the Android app in Persian).
        Box(
            Modifier
                .width(10.dp)
                .fillMaxHeight()
        ) {
            if (selected) {
                Row {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .padding(vertical = 10.dp)
                            .background(hc.accent)
                    )
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    Modifier.weight(1f),
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = hc.textPrimary
                )
                // Country flag chip right next to the server name (like Android).
                val host = remember(profile.id) { ServerInfo.hostPort(profile.link)?.first ?: "" }
                val geo = GeoLookup.byHost[host]
                geo?.cc?.let { cc -> ZeroGeoChip(countryCode = cc) }
                IconButton(onClick = onShare, Modifier.size(36.dp)) {
                    Icon(ZeroIcons.share, "اشتراک لینک", Modifier.size(20.dp), tint = hc.textSecondary)
                }
                IconButton(onClick = onEdit, Modifier.size(36.dp)) {
                    Icon(ZeroIcons.edit, "ویرایش", Modifier.size(20.dp), tint = hc.textSecondary)
                }
                IconButton(onClick = onDelete, Modifier.size(36.dp)) {
                    Icon(ZeroIcons.delete, "حذف", Modifier.size(20.dp), tint = hc.textSecondary)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val hostPort = remember(profile.id) { ServerInfo.hostPort(profile.link) }
                Text(
                    listOfNotNull(hostPort?.first, hostPort?.second?.toString()).joinToString(":"),
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = hc.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    protoLabel(profile.proto),
                    Modifier.weight(1f, fill = false),
                    fontSize = 12.sp,
                    color = hc.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val host = remember(profile.id) { ServerInfo.hostPort(profile.link)?.first ?: "" }
                val geo = GeoLookup.byHost[host]
                geo?.ip?.let { ip ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ip,
                        fontSize = 12.sp,
                        color = hc.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                val ms = Store.pingMap[profile.id]
                Text(
                    text = if (ms != null && ms > 0) "$ms ms" else "",
                    fontSize = 12.sp,
                    color = pingColor(ms) ?: hc.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/** Tiny country chip (flag + ISO code) — port of the Android ZeroGeoChip. */
@Composable
private fun ZeroGeoChip(countryCode: String) {
    val hc = zeroHomeColors
    val flag = countryCodeToFlagEmoji(countryCode)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(hc.accent.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (flag != null) {
            Text(text = flag, fontSize = 12.sp)
            Spacer(Modifier.width(3.dp))
        }
        Text(
            text = countryCode.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = hc.accent
        )
    }
}

/** Simple rename dialog used for server + subscription editing. */
@Composable
private fun RenameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    DialogShell {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        ZeroTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "نام",
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZeroChip("انصراف", primary = false, modifier = Modifier.weight(1f)) { onDismiss() }
            ZeroChip("ذخیره", primary = true, modifier = Modifier.weight(1f)) {
                val t = text.trim()
                if (t.isNotEmpty()) {
                    onDismiss()
                    onConfirm(t)
                }
            }
        }
    }
}

fun setClipboard(text: String) {
    try {
        val tool = java.awt.Toolkit.getDefaultToolkit()
        val clip = tool.systemClipboard
        clip.setContents(java.awt.datatransfer.StringSelection(text), null)
    } catch (_: Throwable) { }
}

// ---------------------------------------------------------------------------
// SETTINGS — lean groups, same feel as the Android settings tab
// ---------------------------------------------------------------------------
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var showPort by remember { mutableStateOf(false) }
    var deleteSubTarget by remember { mutableStateOf<SubRec?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("تنظیمات", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        ZeroSettingsGroup("شخصی‌سازی") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("حالت نمایش", fontSize = 14.sp, color = colorTextPrimary)
                    Text(
                        "همان پوسته روشن برنامه اندروید",
                        fontSize = 11.sp,
                        color = colorTextSecondary
                    )
                }
                Switch(
                    checked = ThemeState.dark,
                    onCheckedChange = { Store.setTheme(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = colorZeroNeon,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = currentTheme.switchOffTrack,
                        uncheckedThumbColor = colorTextSecondary,
                        uncheckedBorderColor = Color.Transparent,
                    )
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        ZeroSettingsGroup("اتصال") {
            ZeroSettingsRow(
                title = "پورت SOCKS",
                value = Store.socksPort.toString(),
                onClick = { showPort = true }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("پراکسی سیستم هنگام اتصال", fontSize = 14.sp, color = colorTextPrimary)
                    Text(
                        "مرورگرها به‌صورت خودکار از تونل استفاده می‌کنند",
                        fontSize = 11.sp,
                        color = colorTextSecondary
                    )
                }
                Switch(
                    checked = Store.autoProxy,
                    onCheckedChange = { Store.setAutoProxy(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = colorZeroNeon,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = currentTheme.switchOffTrack,
                        uncheckedThumbColor = colorTextSecondary,
                        uncheckedBorderColor = Color.Transparent,
                    )
                )
            }
            ZeroSettingsRow(
                title = "بروزرسانی همه اشتراک‌ها",
                onClick = { Connector.updateSubs() }
            )
        }

        Spacer(Modifier.height(14.dp))
        ZeroSettingsGroup("اشتراک‌ها") {
            if (Store.subscriptions.isEmpty()) {
                Text(
                    "هیچ اشتراکی ثبت نشده",
                    fontSize = 13.sp,
                    color = colorTextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
            Store.subscriptions.forEach { sub ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sub.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (sub.total > 0) {
                            Text(
                                "⚡ " + trafficString(sub.used) + " از " + trafficString(sub.total),
                                fontSize = 11.sp,
                                color = colorTextSecondary
                            )
                        }
                        Text(
                            sub.url,
                            fontSize = 10.sp,
                            color = colorTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { deleteSubTarget = sub }, Modifier.size(32.dp)) {
                        Icon(ZeroIcons.delete, "حذف اشتراک", Modifier.size(18.dp), tint = colorTextSecondary)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        ZeroSettingsGroup("درباره") {
            ZeroSettingsRow(title = "نسخه برنامه", value = APP_VERSION, onClick = {})
            ZeroSettingsRow(title = "هسته", value = "Xray (core/xray.exe)", onClick = {})
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (confirmClear) {
                            Connector.disconnect()
                            Store.clearProfiles()
                            confirmClear = false
                            Store.toast("همه سرورها پاک شدند")
                        } else {
                            confirmClear = true
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (confirmClear) "مطمئنید؟ همه سرورها حذف شود" else "پاک کردن همه سرورها",
                    fontSize = 14.sp,
                    color = if (confirmClear) colorZeroTesting else colorZeroFailure
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "لاگ هسته: ${Store.logFile.absolutePath}",
            fontSize = 10.sp,
            color = colorTextSecondary.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(96.dp))
    }

    if (showPort) {
        PortDialog(onDismiss = { showPort = false })
    }
    deleteSubTarget?.let { target ->
        ConfirmDialog(
            title = "حذف اشتراک",
            message = "«${target.name}» و همه سرورهای آن حذف شود؟",
            confirmLabel = "حذف",
            onDismiss = { deleteSubTarget = null },
            onConfirm = {
                Store.removeSubscription(target.url)
                deleteSubTarget = null
                Store.toast("اشتراک حذف شد")
            }
        )
    }
}

@Composable
private fun ZeroSettingsGroup(title: String, content: @Composable () -> Unit) {
    Text(title, fontSize = 12.sp, color = colorTextSecondary)
    Spacer(Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colorCard)
            .border(1.dp, colorCardBorder, RoundedCornerShape(14.dp))
    ) {
        content()
    }
}

@Composable
private fun ZeroSettingsRow(title: String, value: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, fontSize = 13.sp, color = colorTextSecondary)
        }
    }
}

// ---------------------------------------------------------------------------
// (The home drawer was removed — the hamburger is gone and every entry now
// lives in the bottom tabs and the + menu, like the phone app.)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Dialogs / banners
// ---------------------------------------------------------------------------
@Composable
fun AddSubDialog(onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    DialogShell {
        Text("افزودن اشتراک", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        ZeroTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = "https://... (لینک اشتراک)",
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZeroChip("انصراف", primary = false, modifier = Modifier.weight(1f)) { onDismiss() }
            ZeroChip("دریافت", primary = true, modifier = Modifier.weight(1f)) {
                val u = url.trim()
                if (u.startsWith("http")) {
                    onDismiss()
                    Connector.addSub(u)
                } else {
                    Store.toast("یک لینک http معتبر وارد کنید")
                }
            }
        }
    }
}

@Composable
private fun PortDialog(onDismiss: () -> Unit) {
    var portText by remember { mutableStateOf(Store.socksPort.toString()) }
    DialogShell {
        Text("پورت SOCKS", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        ZeroTextField(
            value = portText,
            onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
            placeholder = "10808",
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZeroChip("انصراف", primary = false, modifier = Modifier.weight(1f)) { onDismiss() }
            ZeroChip("ذخیره", primary = true, modifier = Modifier.weight(1f)) {
                portText.toIntOrNull()?.let {
                    Store.setSocksPort(it)
                    Store.toast("پورت ذخیره شد")
                    onDismiss()
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DialogShell {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 13.sp, color = colorTextSecondary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZeroChip("انصراف", primary = false, modifier = Modifier.weight(1f)) { onDismiss() }
            ZeroChip(confirmLabel, primary = true, modifier = Modifier.weight(1f)) {
                onDismiss()
                onConfirm()
            }
        }
    }
}

@Composable
private fun DialogShell(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colorCard)
                .border(1.dp, colorCardBorder, RoundedCornerShape(20.dp))
                .clickable(enabled = false) { }
                .padding(16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ZeroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, fontSize = 13.sp, color = colorTextSecondary) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colorPill,
            unfocusedContainerColor = colorPill,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = currentTheme.fieldText,
            unfocusedTextColor = currentTheme.fieldText,
            cursorColor = colorZeroNeon,
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ZeroChip(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (primary) Brush.horizontalGradient(listOf(colorZeroDeep, colorZeroNeon))
                else SolidColor(colorPill)
            )
            .border(
                1.dp,
                if (primary) Color.Transparent else colorCardBorder,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.White else colorTextSecondary,
        )
    }
}

@Composable
fun BusyBanner(msg: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(currentTheme.bannerBg)
            .border(1.dp, colorCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(msg, fontSize = 12.sp, color = colorZeroDeep)
    }
}

@Composable
fun ToastBanner(msg: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(currentTheme.bannerBg)
            .border(1.dp, colorCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(msg, fontSize = 12.sp, textAlign = TextAlign.Center, color = colorTextPrimary)
    }
}
