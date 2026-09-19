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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// LOCATIONS — server list, styled after the Android server rows:
// selection bar on the side, name + copy/delete icons, protocol + ping line.
// ---------------------------------------------------------------------------
@Composable
fun LocationsScreen(modifier: Modifier = Modifier) {
    var showAddSub by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProfileRec?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        // --- Header (like the Android locations top bar) --------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "سرورها",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) searchQuery = ""
            }) {
                Icon(ZeroIcons.search, "جستجو", tint = zeroHomeColors.textSecondary)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(ZeroIcons.add, "افزودن", tint = zeroHomeColors.textPrimary)
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
        }
        if (showSearch) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("جستجو…", fontSize = 13.sp, color = colorTextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colorPill,
                    unfocusedContainerColor = colorPill,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = colorZeroNeon,
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(4.dp))

        val profiles = Store.profiles.filter {
            searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
        }
        if (profiles.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text(
                if (Store.profiles.isEmpty())
                    "هنوز سروری ندارید\nبا دکمه + اشتراک یا کانفیگ اضافه کنید"
                else "نتیجه‌ای برای «$searchQuery» پیدا نشد",
                fontSize = 13.sp,
                color = colorTextSecondary,
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
                        onCopy = {
                            setClipboard(p.link)
                            Store.toast("لینک کانفیگ کپی شد")
                        },
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
}

@Composable
private fun ZeroDropdownItem(label: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp, color = Color.White) },
        onClick = onClick,
    )
}

/** Android ServerListItem look: side selection bar + name + icons + ping. */
@Composable
private fun ServerRow(
    profile: ProfileRec,
    selected: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
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
                            .background(colorZeroNeon)
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
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onCopy, Modifier.size(36.dp)) {
                    Icon(
                        ZeroIcons.copy,
                        "کپی لینک",
                        Modifier.size(20.dp),
                        tint = colorTextSecondary
                    )
                }
                IconButton(onClick = onDelete, Modifier.size(36.dp)) {
                    Icon(
                        ZeroIcons.delete,
                        "حذف",
                        Modifier.size(20.dp),
                        tint = colorTextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    protoLabel(profile.proto),
                    Modifier.weight(1f, fill = false),
                    fontSize = 12.sp,
                    color = colorZeroNeonSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                val host = remember(profile.id) { ServerInfo.hostPort(profile.link)?.first ?: "" }
                Text(
                    host,
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = colorTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val ms = Store.pingMap[profile.id]
                Text(
                    text = if (ms != null && ms > 0) "$ms ms" else "",
                    fontSize = 12.sp,
                    color = pingColor(ms) ?: colorTextSecondary,
                    maxLines = 1
                )
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
                    Text("پراکسی سیستم هنگام اتصال", fontSize = 14.sp)
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
                        uncheckedTrackColor = Color(0xFF2A3547),
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
// Drawer (home hamburger) — slides over from the right edge, exactly like
// the phone's navigation drawer in RTL.
// ---------------------------------------------------------------------------
@Composable
fun AppDrawer() {
    Row(Modifier.fillMaxSize()) {
        // Panel occupies the trailing side in RTL — place it first so RTL
        // mirroring puts it on the right, exactly like the phone drawer.
        Column(
            modifier = Modifier
                .width(290.dp)
                .fillMaxHeight()
                .background(colorCard)
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
                Text("Zero VPN", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text("v$APP_VERSION", fontSize = 11.sp, color = colorTextSecondary)
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.HorizontalDivider(color = colorCardBorder)

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = colorZeroNeonSoft)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp)
    }
}

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
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
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
            .background(Color(0xFF16283E).copy(alpha = 0.95f))
            .border(1.dp, colorCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(msg, fontSize = 12.sp, color = colorZeroNeonSoft)
    }
}

@Composable
fun ToastBanner(msg: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B2430).copy(alpha = 0.97f))
            .border(1.dp, colorCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(msg, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}
