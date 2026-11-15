package com.zerovpn.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// SERVERS — toolbar + group tabs + subscription card + server list
// ---------------------------------------------------------------------------
@Composable
fun ServersScreen(modifier: Modifier = Modifier, embedded: Boolean = false) {
    val t = currentTheme
    var showAddSub by remember { mutableStateOf(false) }
    var showAddManual by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var sortByPing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProfileRec?>(null) }
    var editTarget by remember { mutableStateOf<ProfileRec?>(null) }
    var editSubTarget by remember { mutableStateOf<SubRec?>(null) }
    var deleteSubTarget by remember { mutableStateOf<SubRec?>(null) }
    var showDelAllConfirm by remember { mutableStateOf(false) }

    val tabs = Store.groupTabs()
    // v2.0.0 crash fix: the stale-group fallback is COMPUTED, never written
    // during composition (v1.6.0 assigned Store.selectedGroup here, racing
    // IO writes and crashing Compose).
    val rememberedGroup = Store.selectedGroup
        ?.takeIf { Store.subscriptionFor(it) != null }
    val explicit = Store.groupChosenExplicit && rememberedGroup != null
    val activeGroup: String? =
        if (explicit) rememberedGroup else tabs.firstOrNull()?.first
    val sub = Store.subscriptionFor(activeGroup)
    val profiles = Store.profilesForGroup(activeGroup).filter {
        searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
    }.let { list ->
        if (sortByPing) list.sortedBy { p ->
            Store.pingMap[p.id] ?: Long.MAX_VALUE
        } else list
    }

    // Geo (flag + IP) prefetch for every host in the visible group.
    androidx.compose.runtime.LaunchedEffect(profiles.map { it.id }) {
        profiles.forEach { p ->
            ServerInfo.hostPort(p.link)?.first?.let { GeoLookup.ensureLookup(it) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (embedded) 14.dp else 22.dp, vertical = if (embedded) 8.dp else 18.dp)
    ) {
        // --- Toolbar ---------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!embedded) {
                Column(Modifier.weight(1f)) {
                    Text("سرورها", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = t.textPrimary)
                    Text(
                        "${Store.profiles.size} سرور در ${Store.subscriptions.size} اشتراک",
                        fontSize = 11.sp, color = t.textSecondary
                    )
                }
            } else {
                // v2.5.0 — embedded under the connect hero (Android layout):
                // a compact count line instead of the full title block.
                Column(Modifier.weight(1f)) {
                    Text(
                        "${Store.profiles.size} سرور",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary
                    )
                }
            }
            if (showSearch) {
                androidx.compose.material3.TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("جستجو…", fontSize = 12.sp, color = t.textSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = t.pillBg,
                        unfocusedContainerColor = t.pillBg,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = t.fieldText,
                        unfocusedTextColor = t.fieldText,
                        cursorColor = t.accent,
                    ),
                    modifier = Modifier.width(220.dp).height(50.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) searchQuery = ""
            }) {
                Icon(ZeroIcons.search, "جستجو", tint = if (showSearch) t.accent else t.textSecondary)
            }
            IconButton(onClick = { sortByPing = !sortByPing }) {
                Icon(
                    ZeroIcons.sort,
                    "مرتب‌سازی بر اساس پینگ",
                    tint = if (sortByPing) t.accent else t.textSecondary
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(ZeroIcons.add, "افزودن", tint = t.accent)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = t.cardBg,
                ) {
                    MenuRow("افزودن اشتراک") { showMenu = false; showAddSub = true }
                    MenuRow("افزودن دستی کانفیگ") { showMenu = false; showAddManual = true }
                    MenuRow("از کلیپ‌بورد") { showMenu = false; Connector.importClipboard() }
                    MenuRow("بروزرسانی اشتراک‌ها") { showMenu = false; Connector.updateSubs() }
                }
            }
            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(ZeroIcons.moreVert, "بیشتر", tint = t.textPrimary)
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    containerColor = t.cardBg,
                ) {
                    MenuRow("تست همه سرورها") { showMoreMenu = false; Connector.testAll() }
                    MenuRow("حذف همه سرورها") { showMoreMenu = false; showDelAllConfirm = true }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- Group tab bar ----------------------------------------------------
        if (tabs.size > 1 || Store.subscriptions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(t.cardBg)
                    .border(1.dp, t.cardBorder, RoundedCornerShape(14.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { (url, count) ->
                    val name = if (url == null) "بدون اشتراک"
                    else Store.subscriptionFor(url)?.name ?: "اشتراک"
                    val selected = url == activeGroup
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) t.selectedRowBg else Color.Transparent)
                            .clickable {
                                Store.selectedGroup = url
                                Store.groupChosenExplicit = true
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "$name ($count)",
                            color = if (selected) t.accent else t.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // --- Subscription card -------------------------------------------------
        if (sub != null) {
            SubscriptionCard(
                sub = sub,
                onRefresh = { Connector.refreshSingleSub(sub.url) },
                onEdit = { editSubTarget = sub },
                onDelete = { deleteSubTarget = sub },
            )
            Spacer(Modifier.height(10.dp))
        }

        // --- List ---------------------------------------------------------------
        if (profiles.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            Text(
                when {
                    Store.profiles.isEmpty() ->
                        "هنوز سروری ندارید\nبا دکمه + اشتراک یا کانفیگ اضافه کنید"
                    searchQuery.isNotBlank() -> "نتیجه‌ای برای «$searchQuery» پیدا نشد"
                    else -> "این گروه خالی است"
                },
                fontSize = 13.sp,
                color = t.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(profiles, key = { _, p -> p.id }) { idx, p ->
                    ServerRow(
                        profile = p,
                        selected = p.id == Store.selectedId,
                        onSelect = {
                            Store.select(p.id)
                            Connector.switchServer()
                        },
                        onShare = {
                            setClipboard(p.link)
                            Store.toast("لینک کانفیگ کپی شد")
                        },
                        onEdit = { editTarget = p },
                        onDelete = { deleteTarget = p },
                        onTest = { Connector.testOne(p) },
                        onMoveUp = if (idx > 0) {
                            { Store.swapProfiles(p.id, profiles[idx - 1].id) }
                        } else null,
                        onMoveDown = if (idx < profiles.lastIndex) {
                            { Store.swapProfiles(p.id, profiles[idx + 1].id) }
                        } else null,
                    )
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }

    // --- Dialogs --------------------------------------------------------------
    if (showAddSub) AddSubDialog(onDismiss = { showAddSub = false })
    if (showAddManual) AddManualDialog(onDismiss = { showAddManual = false })
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
                Store.groupChosenExplicit = false
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
                Store.groupChosenExplicit = false
                Store.toast("همه سرورها حذف شدند")
            }
        )
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    val t = currentTheme
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp, color = t.textPrimary) },
        onClick = onClick,
    )
}

// ---------------------------------------------------------------------------
// Subscription card
// ---------------------------------------------------------------------------
@Composable
private fun SubscriptionCard(
    sub: SubRec,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = currentTheme
    val hasQuota = sub.total > 0
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(t.accent.copy(alpha = 0.08f))
            .border(1.dp, t.accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                sub.name,
                Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = t.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onRefresh, Modifier.size(34.dp)) {
                Icon(ZeroIcons.refresh, "بروزرسانی", Modifier.size(19.dp), tint = t.accent)
            }
            IconButton(onClick = onEdit, Modifier.size(34.dp)) {
                Icon(ZeroIcons.edit, "ویرایش", Modifier.size(19.dp), tint = t.textSecondary)
            }
            IconButton(onClick = onDelete, Modifier.size(34.dp)) {
                Icon(ZeroIcons.delete, "حذف", Modifier.size(19.dp), tint = t.textSecondary)
            }
        }

        if (hasQuota) {
            Spacer(Modifier.height(4.dp))
            val progress = (sub.used.toFloat() / sub.total).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(t.accent.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(t.accentSoft, t.accentDeep)))
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${trafficString(sub.used)} از ${trafficString(sub.total)}",
                    fontSize = 12.sp,
                    color = t.textPrimary
                )
                Spacer(Modifier.weight(1f))
                val remaining = (sub.total - sub.used).coerceAtLeast(0)
                Text(
                    "${trafficString(remaining)} باقی مانده",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (remaining <= 0L) t.pingBad else t.accent
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
                color = t.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Server row
// ---------------------------------------------------------------------------
@Composable
private fun ServerRow(
    profile: ProfileRec,
    selected: Boolean,
    onSelect: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val t = currentTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) t.selectedRowBg else t.cardBg)
            .border(
                1.dp,
                if (selected) t.accent.copy(alpha = 0.45f) else t.cardBorder,
                RoundedCornerShape(14.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val host = remember(profile.id) { ServerInfo.hostPort(profile.link)?.first ?: "" }
                val geo = GeoLookup.byHost[host]
                geo?.cc?.let { cc -> ZeroFlag(countryCode = cc, width = 24.dp) }
                Spacer(Modifier.width(8.dp))
                Text(
                    profile.name,
                    Modifier.weight(1f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = t.textPrimary
                )
                // v2.5.0 — BOTH numbers per row, exactly like V2Box/v2rayNG:
                // «پینگ» = TCP handshake (tcpMap), «تأخیر واقعی» = through the
                // core (pingMap). A failed value (-1) shows تایم‌اوت — a dead
                // server can never keep a good number from an older test.
                @Composable
                fun testChip(ms: Long?, label: String) {
                    val color = when {
                        ms == null -> t.textSecondary
                        ms > 0 && ms <= 120 -> t.pingGood
                        ms > 0 && ms <= 400 -> t.pingMid
                        else -> t.pingBad
                    }
                    val text = when {
                        ms == null -> ""
                        ms > 0 -> if (label.isEmpty()) "$ms ms" else "$ms"
                        else -> "تایم‌اوت"
                    }
                    if (text.isNotEmpty()) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(color.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (label.isNotEmpty() && ms != null && ms > 0) {
                                Text(
                                    label,
                                    fontSize = 9.sp,
                                    color = color.copy(alpha = 0.8f)
                                )
                                Spacer(Modifier.width(3.dp))
                            }
                            Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                testChip(Store.tcpMap[profile.id], "TCP")
                testChip(Store.pingMap[profile.id], "واقعی")
                IconButton(onClick = onTest, Modifier.size(32.dp)) {
                    Icon(ZeroIcons.flash, "تست این سرور", Modifier.size(17.dp), tint = t.accent)
                }
                IconButton(onClick = onShare, Modifier.size(32.dp)) {
                    Icon(ZeroIcons.share, "کپی لینک", Modifier.size(17.dp), tint = t.textSecondary)
                }
                IconButton(onClick = onEdit, Modifier.size(32.dp)) {
                    Icon(ZeroIcons.edit, "ویرایش", Modifier.size(17.dp), tint = t.textSecondary)
                }
                IconButton(onClick = onDelete, Modifier.size(32.dp)) {
                    Icon(ZeroIcons.delete, "حذف", Modifier.size(17.dp), tint = t.textSecondary)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(t.accent.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        protoLabel(profile.proto),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.accent
                    )
                }
                Spacer(Modifier.width(8.dp))
                val hostPort = remember(profile.id) { ServerInfo.hostPort(profile.link) }
                val geo = hostPort?.first?.let { GeoLookup.byHost[it] }
                Text(
                    listOfNotNull(
                        hostPort?.first,
                        hostPort?.second?.toString(),
                        geo?.ip
                    ).joinToString("  ·  "),
                    fontSize = 11.sp,
                    color = t.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // v2.5.0 — the move buttons the user asked for: بیارمش پایین یا بالا.
        // Swap inside the visible list order (group views filter the master
        // list, so the swap lands there too and is persisted immediately).
        if (onMoveUp != null || onMoveDown != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { onMoveUp?.invoke() }, Modifier.size(28.dp), enabled = onMoveUp != null) {
                    Icon(ZeroIcons.arrowUp, "انتقال به بالا", Modifier.size(17.dp),
                        tint = if (onMoveUp != null) t.textSecondary else t.textSecondary.copy(alpha = 0.3f))
                }
                IconButton(onClick = { onMoveDown?.invoke() }, Modifier.size(28.dp), enabled = onMoveDown != null) {
                    Icon(ZeroIcons.arrowDown, "انتقال به پایین", Modifier.size(17.dp),
                        tint = if (onMoveDown != null) t.textSecondary else t.textSecondary.copy(alpha = 0.3f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SETTINGS
// ---------------------------------------------------------------------------
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val t = currentTheme
    var showPort by remember { mutableStateOf(false) }
    var showTorPort by remember { mutableStateOf(false) }
    var deleteSubTarget by remember { mutableStateOf<SubRec?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        Text("تنظیمات", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = t.textPrimary)
        Spacer(Modifier.height(14.dp))

        SettingsGroup("اتصال") {
            SettingsRow(
                title = "پورت SOCKS",
                value = "${Store.socksPort} (HTTP: ${Store.httpPort})",
                onClick = { showPort = true }
            )
            SettingsSwitch(
                title = "پراکسی سیستم هنگام اتصال",
                subtitle = "مرورگرها به‌صورت خودکار از تونل استفاده می‌کنند",
                checked = Store.autoProxy,
                onChange = { Store.setAutoProxy(it) }
            )
            SettingsSwitch(
                title = "اتصال خودکار هنگام باز شدن",
                subtitle = "سرور انتخاب‌شده با اجرای برنامه وصل می‌شود",
                checked = Store.data.settings.autoConnect,
                onChange = { Store.setAutoConnect(it) }
            )
            SettingsSwitch(
                title = "تعویض خودکار سرور",
                subtitle = "انتخاب سرور دیگر هنگام اتصال، تونل را به آن سرور می‌برد",
                checked = Store.data.settings.autoSwitch,
                onChange = { Store.setAutoSwitch(it) }
            )
            SettingsRow(
                title = "بروزرسانی همه اشتراک‌ها",
                value = "",
                onClick = { Connector.updateSubs() }
            )
        }

        Spacer(Modifier.height(14.dp))

        // v2.1.0 — Tor chain (v2rayNG/Orbot parity)
        SettingsGroup("زنجیره تور (Tor)") {
            SettingsSwitch(
                title = "عبور ترافیک از تور",
                subtitle = "ترافیک اول از Tor محلی عبور می‌کند و بعد به سرور می‌رسد",
                checked = Store.data.settings.torChain,
                onChange = { Store.setTorChain(it) }
            )
            SettingsRow(
                title = "پورت SOCKS تور",
                value = "${Store.data.settings.torPort} (خودکار: 9050/9150)",
                onClick = { showTorPort = true }
            )
            Text(
                "راهنما: روی گوشی Orbot را نصب و اجرا کنید؛ در ویندوز Tor Browser (پورت 9150) یا Tor Expert Bundle (9050) کافی است. اگر تور پیدا نشود، اتصال با پیام خطا متوقف می‌شود.",
                fontSize = 11.sp,
                color = t.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        SettingsGroup("ظاهر") {
            SettingsSwitch(
                title = "تم تیره",
                subtitle = "پوسته شبانه کم‌نور",
                checked = ThemeState.dark,
                onChange = { Store.setTheme(it) }
            )
        }

        Spacer(Modifier.height(14.dp))

        SettingsGroup("اشتراک‌ها") {
            if (Store.subscriptions.isEmpty()) {
                Text(
                    "هیچ اشتراکی ثبت نشده",
                    fontSize = 13.sp,
                    color = t.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
            Store.subscriptions.forEach { sub ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sub.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary)
                        if (sub.total > 0) {
                            Text(
                                "${trafficString(sub.used)} از ${trafficString(sub.total)}",
                                fontSize = 11.sp,
                                color = t.textSecondary
                            )
                        }
                        Text(
                            sub.url,
                            fontSize = 10.sp,
                            color = t.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { deleteSubTarget = sub }, Modifier.size(32.dp)) {
                        Icon(ZeroIcons.delete, "حذف اشتراک", Modifier.size(17.dp), tint = t.textSecondary)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsGroup("هسته و سیستم") {
            SettingsRow(
                title = "نسخه هسته",
                value = Store.coreVersion ?: "Xray-core رسمی",
                onClick = { }
            )
            SettingsRow(
                title = if (Store.updateVersion != null) "نسخه جدید موجود: ${Store.updateVersion}" else "بررسی بروزرسانی برنامه",
                value = if (Store.updateVersion != null) "دانلود" else "",
                onClick = {
                    if (Store.updateVersion != null) Connector.openReleasePage()
                    else {
                        Connector.checkForUpdate()
                        Store.toast("در حال بررسی نسخه جدید…")
                    }
                }
            )
            SettingsRow(
                title = "مشاهده گزارش هسته",
                value = "",
                onClick = { Store.view = "logs" }
            )
            SettingsRow(
                title = "پوشه داده‌ها",
                value = "",
                onClick = { openDataFolder() }
            )
        }

        Spacer(Modifier.height(14.dp))

        SettingsGroup("درباره") {
            SettingsRow(title = "نسخه برنامه", value = APP_VERSION, onClick = { })
            SettingsRow(title = "هسته", value = "Xray-core رسمی (XTLS/Xray-core)", onClick = { })
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showPort) PortDialog(onDismiss = { showPort = false })
    if (showTorPort) TorPortDialog(onDismiss = { showTorPort = false })
    deleteSubTarget?.let { target ->
        ConfirmDialog(
            title = "حذف اشتراک",
            message = "«${target.name}» و همه سرورهای آن حذف شود؟",
            confirmLabel = "حذف",
            onDismiss = { deleteSubTarget = null },
            onConfirm = {
                Store.removeSubscription(target.url)
                Store.groupChosenExplicit = false
                deleteSubTarget = null
                Store.toast("اشتراک حذف شد")
            }
        )
    }
}

private fun openDataFolder() {
    try {
        if (Store.isWindows) {
            ProcessBuilder("explorer", Store.dataDir.absolutePath).start()
        } else {
            ProcessBuilder("xdg-open", Store.dataDir.absolutePath).start()
        }
    } catch (_: Throwable) {
        Store.toast("باز کردن پوشه ممکن نشد: ${Store.dataDir.absolutePath}")
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val t = currentTheme
    Text(title, fontSize = 12.sp, color = t.textSecondary, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(t.cardBg)
            .border(1.dp, t.cardBorder, RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(title: String, value: String?, onClick: () -> Unit) {
    val t = currentTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp, color = t.textPrimary, modifier = Modifier.weight(1f))
        if (!value.isNullOrBlank()) {
            Text(value, fontSize = 13.sp, color = t.textSecondary)
        }
        Icon(
            ZeroIcons.chevron,
            contentDescription = null,
            tint = t.textSecondary,
            modifier = Modifier.size(16.dp).padding(start = 4.dp)
        )
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val t = currentTheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = t.textPrimary)
            Text(subtitle, fontSize = 11.sp, color = t.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = t.accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = t.switchOffTrack,
                uncheckedThumbColor = t.textSecondary,
                uncheckedBorderColor = Color.Transparent,
            )
        )
    }
}

// ---------------------------------------------------------------------------
// LOGS — core log viewer
// ---------------------------------------------------------------------------
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    val t = currentTheme
    var logText by remember { mutableStateOf("در حال خواندن گزارش…") }
    var reloadKey by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(reloadKey) {
        val lines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val f = Store.logFile
                val err = java.io.File(Store.dataDir, "xray-error.log")
                val sb = StringBuilder()
                if (err.isFile) {
                    sb.appendLine("— خطاهای هسته (xray-error.log) —")
                    err.readLines().takeLast(60).forEach { sb.appendLine(it) }
                    sb.appendLine()
                }
                if (f.isFile) {
                    sb.appendLine("— خروجی هسته (xray.log) —")
                    f.readLines().takeLast(240).forEach { sb.appendLine(it) }
                }
                if (sb.isBlank()) "گزارشی موجود نیست — هنوز اتصالی برقرار نشده."
                else sb.toString()
            } catch (e: Throwable) {
                "خواندن گزارش ممکن نشد: ${e.message}"
            }
        }
        logText = lines
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("گزارش هسته", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = t.textPrimary)
                Text(
                    Store.logFile.absolutePath,
                    fontSize = 10.sp, color = t.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { reloadKey++ }) {
                Icon(ZeroIcons.refresh, "بازخوانی", tint = t.accent)
            }
            IconButton(onClick = {
                setClipboard(logText)
                Store.toast("گزارش کپی شد")
            }) {
                Icon(ZeroIcons.copy, "کپی", tint = t.textSecondary)
            }
            IconButton(onClick = {
                Store.io.launch {
                    try {
                        Store.logFile.writeText("")
                        java.io.File(Store.dataDir, "xray-error.log").delete()
                    } catch (_: Throwable) { }
                }
                reloadKey++
                Store.toast("گزارش پاک شد")
            }) {
                Icon(ZeroIcons.delete, "پاک کردن", tint = t.textSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        ZeroCard {
            Box(Modifier.fillMaxWidth().height(420.dp)) {
                Text(
                    logText,
                    fontSize = 11.sp,
                    color = t.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
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
fun AddManualDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    DialogShell {
        Text("افزودن دستی کانفیگ", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "لینک کانفیگ را بچسبانید (چند خط هم امکان‌پذیر است)",
            fontSize = 11.sp,
            color = currentTheme.textSecondary
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = {
                Text("vmess://… یا vless://…", fontSize = 12.sp, color = currentTheme.textSecondary)
            },
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = currentTheme.pillBg,
                unfocusedContainerColor = currentTheme.pillBg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = currentTheme.fieldText,
                unfocusedTextColor = currentTheme.fieldText,
                cursorColor = currentTheme.accent,
            ),
            modifier = Modifier.fillMaxWidth().height(110.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZeroChip("انصراف", primary = false, modifier = Modifier.weight(1f)) { onDismiss() }
            ZeroChip("افزودن", primary = true, modifier = Modifier.weight(1f)) {
                if (text.isNotBlank()) {
                    onDismiss()
                    Connector.importManual(text)
                }
            }
        }
    }
}

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

@Composable
private fun TorPortDialog(onDismiss: () -> Unit) {
    var portText by remember { mutableStateOf(Store.data.settings.torPort.toString()) }
    DialogShell {
        Text("پورت SOCKS تور", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Orbot و Tor Expert Bundle روی 9050 و Tor Browser روی 9150 گوش می‌دهند. اگر این پورت پاسخ ندهد، پورت‌های پیش‌فرض خودکار امتحان می‌شوند.",
            fontSize = 11.sp,
            color = currentTheme.textSecondary
        )
        Spacer(Modifier.height(10.dp))
        ZeroTextField(
            value = portText,
            onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
            placeholder = "9050",
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZeroChip("انصراف", primary = false, modifier = Modifier.weight(1f)) { onDismiss() }
            ZeroChip("ذخیره", primary = true, modifier = Modifier.weight(1f)) {
                portText.toIntOrNull()?.let {
                    Store.setTorPort(it)
                    onDismiss()
                    Store.toast("پورت تور ذخیره شد")
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
        Spacer(Modifier.height(6.dp))
        Text(
            "پورت HTTP به‌صورت خودکار یکی بعد از آن تنظیم می‌شود. تغییر پورت از دفعه بعدِ اتصال اعمال می‌شود.",
            fontSize = 11.sp,
            color = currentTheme.textSecondary
        )
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
                    onDismiss()
                    Store.toast("پورت ذخیره شد")
                }
            }
        }
    }
}
