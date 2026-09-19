package com.zerovpn.desktop

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Connector — orchestrates xray lifecycle + system proxy
// ---------------------------------------------------------------------------
object Connector {

    fun toggle() = when (Store.status) {
        ConnStatus.DISCONNECTED -> connect()
        ConnStatus.CONNECTED -> disconnect()
        ConnStatus.CONNECTING -> { }
    }

    fun connect() {
        val p = Store.selectedProfile
        if (p == null) {
            Store.toast("هیچ سروری انتخاب نشده — از بخش «سرورها» اضافه کنید")
            return
        }
        Store.status = ConnStatus.CONNECTING
        Store.errorMsg = null
        Store.scope.launch {
            val cfg = XrayConfig.buildFullConfig(p.link, Store.socksPort, Store.httpPort)
            if (cfg == null) {
                Store.status = ConnStatus.DISCONNECTED
                Store.toast("این نوع کانفیگ پشتیبانی نمی‌شود (vmess/vless/trojan/ss)")
                return@launch
            }
            try {
                Store.configFile.writeText(XrayConfig.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), cfg))
            } catch (t: Throwable) {
                Store.status = ConnStatus.DISCONNECTED
                Store.toast("نوشتن کانفیگ ممکن نشد: ${t.message}")
                return@launch
            }
            if (!Engine.start(Store.configFile)) {
                Store.status = ConnStatus.DISCONNECTED
                Store.toast("اجرای هسته ممکن نشد — core/xray.exe پیدا نشد")
                return@launch
            }
            if (!Engine.waitForPort(Store.socksPort, 12000)) {
                Engine.stop()
                Store.status = ConnStatus.DISCONNECTED
                Store.toast("اتصال برقرار نشد — سرور در دسترس نیست یا پورت اشغال است")
                return@launch
            }
            if (Store.autoProxy && Store.isWindows) {
                Store.proxyOn = SysProxy.enable(Store.httpPort)
                if (!Store.proxyOn) Store.toast("پراکسی سیستم تنظیم نشد — پراکسی را دستی تنظیم کنید")
            }
            Store.status = ConnStatus.CONNECTED
            Store.connectedSince = System.currentTimeMillis()
            val ms = PingTest.viaSocks(Store.socksPort)
            Store.recordPing(p.id, ms)
        }
    }

    fun disconnect() {
        Store.scope.launch {
            Engine.stop()
            if (Store.proxyOn && Store.isWindows) {
                SysProxy.disable()
                Store.proxyOn = false
            }
            Store.status = ConnStatus.DISCONNECTED
            Store.connectedSince = null
        }
    }

    fun pingCurrent() {
        if (Store.status != ConnStatus.CONNECTED) {
            Store.toast("برای تست پینگ اول وصل شوید")
            return
        }
        Store.scope.launch {
            Store.busyMsg = "در حال تست پینگ…"
            val ms = withContext(Dispatchers.IO) { PingTest.viaSocks(Store.socksPort) }
            Store.busyMsg = null
            Store.selectedId?.let { Store.recordPing(it, ms) }
            if (ms < 0) Store.toast("تست پینگ ناموفق بود")
        }
    }

    fun updateSubs() {
        Store.scope.launch {
            val (n, msg) = withContext(Dispatchers.IO) { Profiles.updateSubscriptions() }
            Store.toast(msg)
        }
    }

    fun addSub(url: String) {
        Store.scope.launch {
            Store.busyMsg = "در حال دریافت اشتراک…"
            try {
                val body = withContext(Dispatchers.IO) { Profiles.fetch(url) }
                val links = withContext(Dispatchers.IO) { Profiles.parseBody(body) }
                if (links.isEmpty()) {
                    Store.busyMsg = null
                    Store.toast("هیچ سرور معتبری در اشتراک پیدا نشد")
                    return@launch
                }
                Store.addSubscription(url, "اشتراک ${Store.subscriptions.size + 1}")
                val fresh = links.map { p ->
                    ProfileRec(
                        id = Profiles.stableId(p.link),
                        name = p.name,
                        proto = p.proto,
                        link = p.link,
                        sub = url,
                    )
                }
                synchronized(Store) {
                    val customs = Store.profiles.filter { it.sub == null }
                    Store.data = Store.data.copy(profiles = customs + fresh)
                    if (Store.selectedId == null) Store.data = Store.data.copy(selected = fresh.first().id)
                    Store.save()
                }
                Store.busyMsg = null
                Store.toast("${fresh.size} سرور از اشتراک اضافه شد")
            } catch (t: Throwable) {
                Store.busyMsg = null
                Store.toast("خطا در دریافت اشتراک: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun importClipboard() {
        val text = Profiles.clipboardText()
        if (text.isNullOrBlank()) {
            Store.toast("کلیپ‌بورد خالی است")
            return
        }
        Store.scope.launch {
            val n = withContext(Dispatchers.IO) { Profiles.importText(text) }
            if (n == 0) Store.toast("هیچ کانفیگ معتبری در کلیپ‌بورد نبود")
            else Store.toast("$n کانفیگ از کلیپ‌بورد اضافه شد")
        }
    }
}

// ---------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------
@Composable
fun ZeroApp() {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalTextStyle provides TextStyle(fontFamily = fontFamilyVazir, color = Color.White),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(colorBgTop, colorBgBottom)))
        ) {
            when (Store.view) {
                "locations" -> LocationsView()
                "settings" -> SettingsView()
                else -> HomeView()
            }
            Store.busyMsg?.let { msg ->
                BusyBanner(msg, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            Store.toast?.let { msg ->
                ToastBanner(msg, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// HOME
// ---------------------------------------------------------------------------
@Composable
fun HomeView() {
    var uptimeText by remember { mutableStateOf("00:00") }
    LaunchedEffect(Store.status) {
        if (Store.status == ConnStatus.CONNECTED) {
            while (true) {
                val since = Store.connectedSince
                val sec = since?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
                uptimeText = formatUptime(sec)
                delay(1000)
            }
        } else {
            uptimeText = "00:00"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))
        // --- Top bar ---------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val logoPainter = remember { loadLogoPainter() }
            logoPainter?.let {
                Image(painter = it, contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Zero VPN", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "1.4.9",
                fontSize = 11.sp,
                color = colorTextSecondary,
                modifier = Modifier
                    .background(colorPill, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        Spacer(Modifier.height(22.dp))

        // --- Status word -------------------------------------------------------
        val connected = Store.status == ConnStatus.CONNECTED
        val connecting = Store.status == ConnStatus.CONNECTING
        val statusWord = when {
            connected -> "محافظت‌شده"
            connecting -> "در حال اتصال…"
            else -> "بدون محافظت"
        }
        Text(
            text = statusWord,
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            style = if (connected) TextStyle(
                fontFamily = fontFamilyVazir,
                brush = Brush.horizontalGradient(listOf(colorZeroNeonSoft, colorZeroDeep)),
            ) else LocalTextStyle.current.copy(color = Color.White, fontWeight = FontWeight.Black),
        )

        Spacer(Modifier.height(4.dp))

        // --- Ping ---------------------------------------------------------------
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = Store.ping?.takeIf { it >= 0 }?.toString() ?: "—",
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                color = pingColor(Store.ping) ?: Color.White,
            )
            if (Store.ping != null && Store.ping!! >= 0) {
                Spacer(Modifier.width(6.dp))
                Text("ms", fontSize = 15.sp, color = colorTextSecondary, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        Text(
            text = "پینگ واقعی از داخل تونل",
            fontSize = 11.sp,
            color = colorTextSecondary,
        )

        Spacer(Modifier.height(10.dp))

        // --- Connect button -------------------------------------------------------
        ZeroConnectButton(
            status = Store.status,
            onClick = { Connector.toggle() },
        )

        Spacer(Modifier.height(14.dp))

        // --- Stat pills ------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZeroStatPill("مدت", uptimeText, Modifier.weight(1f))
            ZeroStatPill(
                "پراکسی سیستم",
                when {
                    !Store.isWindows -> "غیرفعال"
                    Store.proxyOn -> "روشن"
                    else -> "خاموش"
                },
                Modifier.weight(1f),
                valueColor = if (Store.proxyOn) colorZeroNeonSoft else colorTextSecondary,
            )
            ZeroStatPill("پورت", Store.socksPort.toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))

        // --- Current server card ------------------------------------------------------
        val sel = Store.selectedProfile
        ZeroServerCard(
            name = sel?.name ?: "هیچ سروری انتخاب نشده",
            subLine = sel?.let { "${protoLabel(it.proto)}  ·  ${it.link.substringAfter("@", "").substringBefore(":").take(30)}" },
            ping = sel?.let { Store.pingMap[it.id] },
            onClick = { Store.view = "locations" },
            modifier = Modifier.fillMaxWidth(),
        )

        // --- Error line -------------------------------------------------------------
        Store.errorMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = colorZeroFailure, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(14.dp))

        // --- Bottom actions -----------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorPill)
                    .border(1.dp, colorCardBorder, RoundedCornerShape(14.dp))
                    .clickable { Connector.pingCurrent() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("تست پینگ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colorZeroNeonSoft)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(colorZeroDeep, colorZeroNeon)))
                    .clickable { Store.view = "locations" }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("سرورها", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

// ---------------------------------------------------------------------------
// LOCATIONS
// ---------------------------------------------------------------------------
@Composable
fun LocationsView() {
    var showAddSub by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colorPill)
                    .clickable { Store.view = "home" },
                contentAlignment = Alignment.Center,
            ) {
                Text("→", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text("سرورها", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZeroChip("افزودن اشتراک", primary = true, modifier = Modifier.weight(1f)) { showAddSub = true }
            ZeroChip("کلیپ‌بورد", primary = false, modifier = Modifier.weight(1f)) { Connector.importClipboard() }
            ZeroChip("بروزرسانی", primary = false, modifier = Modifier.weight(1f)) { Connector.updateSubs() }
        }

        Spacer(Modifier.height(10.dp))

        val profiles = Store.profiles
        if (profiles.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text(
                "هنوز سروری ندارید\nبا «افزودن اشتراک» لینک اشتراک را وارد کنید",
                fontSize = 13.sp,
                color = colorTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.id }) { p ->
                    ServerRow(
                        profile = p,
                        selected = p.id == Store.selectedId,
                        onSelect = { Store.select(p.id) },
                        onDelete = { Store.deleteProfile(p.id) },
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colorPill)
                            .clickable { Store.view = "settings" }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("تنظیمات", fontSize = 13.sp, color = colorTextSecondary)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showAddSub) {
        AddSubDialog(onDismiss = { showAddSub = false })
    }
}

@Composable
private fun ServerRow(
    profile: ProfileRec,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF16283E) else colorCard)
            .border(
                1.dp,
                if (selected) colorZeroNeon.copy(alpha = 0.7f) else colorCardBorder,
                RoundedCornerShape(16.dp),
            )
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // radio dot
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) colorZeroNeon else Color.Transparent)
                .border(2.dp, if (selected) colorZeroNeon else colorTextSecondary.copy(alpha = 0.5f), CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            val ms = Store.pingMap[profile.id]
            Text(
                text = protoLabel(profile.proto) + if (ms != null && ms > 0) "  ·  $ms ms" else "",
                fontSize = 11.sp,
                color = pingColor(ms) ?: colorTextSecondary,
            )
        }
        Text(
            "حذف",
            fontSize = 11.sp,
            color = colorZeroFailure.copy(alpha = 0.8f),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onDelete() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// SETTINGS
// ---------------------------------------------------------------------------
@Composable
fun SettingsView() {
    var portText by remember { mutableStateOf(Store.socksPort.toString()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colorPill)
                    .clickable { Store.view = "home" },
                contentAlignment = Alignment.Center,
            ) {
                Text("→", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text("تنظیمات", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(18.dp))
        Text("پورت SOCKS", fontSize = 13.sp, color = colorTextSecondary)
        Spacer(Modifier.height(6.dp))
        ZeroTextField(value = portText, onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) })

        Spacer(Modifier.height(6.dp))
        ZeroPrimaryButton("ذخیره پورت") {
            val v = portText.toIntOrNull()
            if (v != null) {
                Store.setSocksPort(v)
                Store.toast("پورت ذخیره شد")
            }
        }

        Spacer(Modifier.height(18.dp))
        ToggleRow(
            label = "پراکسی سیستم ویندوز هنگام اتصال",
            checked = Store.autoProxy,
            onChange = { Store.setAutoProxy(it) },
        )

        Spacer(Modifier.height(6.dp))
        Text(
            "تمام ترافیک مرورگر از پورت ${Store.socksPort} (HTTP: ${Store.httpPort}) عبور می‌کند؛ مرورگرها به‌صورت خودکار از پراکسی سیستم استفاده می‌کنند.",
            fontSize = 11.sp, color = colorTextSecondary,
        )

        Spacer(Modifier.height(24.dp))
        Text("اشتراک‌ها", fontSize = 13.sp, color = colorTextSecondary)
        Spacer(Modifier.height(8.dp))
        Store.subscriptions.forEach { sub ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorCard)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(sub.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(sub.url, fontSize = 10.sp, color = colorTextSecondary, maxLines = 1)
                }
                Text(
                    "حذف",
                    fontSize = 11.sp,
                    color = colorZeroFailure.copy(alpha = 0.8f),
                    modifier = Modifier.clickable { Store.removeSubscription(sub.url) }.padding(4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(20.dp))
        var confirmClear by remember { mutableStateOf(false) }
        ZeroDangerButton(if (confirmClear) "مطمئنید؟ همه حذف شود" else "پاک کردن همه سرورها") {
            if (confirmClear) {
                Connector.disconnect()
                Store.clearProfiles()
                confirmClear = false
                Store.toast("همه سرورها پاک شدند")
            } else {
                confirmClear = true
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Zero VPN 1.4.9 برای ویندوز\nهسته: Xray (core/xray.exe)\nلاگ: ${Store.logFile.absolutePath}",
            fontSize = 10.sp,
            color = colorTextSecondary,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// Dialogs / banners
// ---------------------------------------------------------------------------
@Composable
private fun AddSubDialog(onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colorCard)
                .border(1.dp, colorCardBorder, RoundedCornerShape(20.dp))
                .clickable(enabled = false) { }
                .padding(16.dp),
        ) {
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
}

@Composable
private fun BusyBanner(msg: String, modifier: Modifier = Modifier) {
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
private fun ToastBanner(msg: String, modifier: Modifier = Modifier) {
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

// ---------------------------------------------------------------------------
// Connect button — same neon language as the Android app:
// ambient glow → glass disc → power icon; radar rings while connected, a
// neon comet arc spinning around the rim while connecting.
// ---------------------------------------------------------------------------
private const val BTN_SIZE_DP = 196
private const val DISC_RADIUS_DP = 80

@Composable
fun ZeroConnectButton(
    status: ConnStatus,
    onClick: () -> Unit,
) {
    val connected = status == ConnStatus.CONNECTED
    val connecting = status == ConnStatus.CONNECTING

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val radarT by pulse.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Restart),
        label = "radar",
    )
    val spin by pulse.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1050, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val breatheT by pulse.animateFloat(
        0f, (2f * Math.PI.toFloat()),
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "breathe",
    )
    val connectSpin by pulse.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "connectSpin",
    )
    val connectSweepT by pulse.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(750, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "connectSweep",
    )

    Box(
        modifier = Modifier
            .size(BTN_SIZE_DP.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = DISC_RADIUS_DP.dp.toPx()

            // Ambient glow
            val glowColor = when {
                connecting -> colorZeroNeonSoft
                connected -> colorZeroNeon
                else -> colorZeroIdle
            }
            val glowAlpha = when {
                connecting -> 0.22f + 0.10f * sin(breatheT)
                connected -> 0.30f + 0.06f * sin(breatheT)
                else -> 0.14f
            }
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor.copy(alpha = glowAlpha), Color.Transparent),
                    center = c,
                    radius = r * 1.55f,
                )
            )

            // Radar rings while connected
            if (connected) {
                for (phase in 0..1) {
                    val t = (radarT + phase * 0.5f) % 1f
                    drawCircle(
                        color = colorZeroNeonSoft.copy(alpha = (1f - t) * 0.30f),
                        radius = r + t * 26.dp.toPx(),
                        center = c,
                        style = Stroke(width = (2.4f - 1.5f * t).dp.toPx()),
                    )
                }
            }

            // Comet arc while connecting
            if (connecting) {
                val arcR = r + 10.dp.toPx()
                val sweepDeg = 60f + 150f * connectSweepT
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            colorZeroNeonDeepColor(),
                            colorZeroNeonSoft,
                            colorZeroNeonDeepColor(),
                            Color.Transparent,
                        ),
                        center = c,
                    ),
                    startAngle = connectSpin,
                    sweepAngle = sweepDeg,
                    useCenter = false,
                    topLeft = Offset(c.x - arcR, c.y - arcR),
                    size = androidx.compose.ui.geometry.Size(arcR * 2f, arcR * 2f),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
                // comet head
                val headAngle = Math.toRadians((connectSpin + sweepDeg).toDouble())
                val hx = c.x + arcR * kotlin.math.cos(headAngle).toFloat()
                val hy = c.y + arcR * kotlin.math.sin(headAngle).toFloat()
                drawCircle(
                    color = colorZeroNeonSoft,
                    radius = 5.dp.toPx(),
                    center = Offset(hx, hy),
                )
            }

            // Disc
            val discBrush = if (connected) Brush.radialGradient(
                colors = listOf(colorZeroNeonSoft, colorZeroDeep),
                center = Offset(c.x, c.y - r * 0.25f),
                radius = r * 1.35f,
            ) else Brush.radialGradient(
                colors = listOf(Color(0xFF16263C), Color(0xFF0D1826)),
                center = Offset(c.x, c.y - r * 0.25f),
                radius = r * 1.35f,
            )
            drawCircle(brush = discBrush, radius = r, center = c)
            if (!connected) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.07f),
                    radius = r,
                    center = c,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }

            // Inner rim shading
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )

            // Glossy top highlight
            drawOval(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                ),
                topLeft = Offset(c.x - r * 0.72f, c.y - r * 0.92f),
                size = androidx.compose.ui.geometry.Size(r * 1.44f, r * 0.86f),
            )

            // Power icon: arc with a gap on top + vertical stem
            val iconColor = if (connected || connecting) Color.White else Color(0xFF8FA3BE)
            val iconR = r * 0.42f
            val stroke = 6.dp.toPx()
            drawArc(
                color = iconColor,
                startAngle = -55f,
                sweepAngle = 290f,
                useCenter = false,
                topLeft = Offset(c.x - iconR, c.y - iconR),
                size = androidx.compose.ui.geometry.Size(iconR * 2f, iconR * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawLine(
                color = iconColor,
                start = Offset(c.x, c.y - iconR + stroke * 0.2f),
                end = Offset(c.x, c.y - iconR - iconR * 0.45f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun colorZeroNeonDeepColor(): Color = colorZeroDeep

// ---------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------
@Composable
private fun ZeroStatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = colorZeroNeonSoft,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colorPill)
            .border(1.dp, colorCardBorder, RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = colorTextSecondary)
    }
}

@Composable
private fun ZeroServerCard(
    name: String,
    subLine: String?,
    ping: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(colorCard)
            .border(1.dp, colorCardBorder, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(colorZeroDeep, colorZeroNeon))),
            contentAlignment = Alignment.Center,
        ) {
            Text("Z", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            subLine?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = 10.sp, color = colorTextSecondary, maxLines = 1)
            }
        }
        ping?.let {
            if (it > 0) {
                Text("$it ms", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pingColor(it) ?: colorZeroNeonSoft)
                Spacer(Modifier.width(8.dp))
            }
        }
        Text("‹", fontSize = 18.sp, color = colorTextSecondary)
    }
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
                else Brush.horizontalGradient(listOf(colorPill, colorPill))
            )
            .border(
                1.dp,
                if (primary) Color.Transparent else colorCardBorder,
                RoundedCornerShape(12.dp),
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.White else colorZeroNeonSoft,
        )
    }
}

@Composable
private fun ZeroPrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(colorZeroDeep, colorZeroNeon)))
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ZeroDangerButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2A1218))
            .border(1.dp, colorZeroFailure.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colorZeroFailure)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colorCard)
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        val knob by animateDpAsState(
            targetValue = if (checked) 20.dp else 2.dp,
            label = "knob",
        )
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) colorZeroNeon else Color(0xFF2A3444)),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = knob)
                    .size(20.dp)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun ZeroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            placeholder?.let { Text(it, fontSize = 12.sp, color = colorTextSecondary) }
        },
        textStyle = TextStyle(fontFamily = fontFamilyVazir, fontSize = 12.sp, color = Color.White),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colorPill,
            unfocusedContainerColor = colorPill,
            focusedIndicatorColor = colorZeroNeon.copy(alpha = 0.6f),
            unfocusedIndicatorColor = colorCardBorder,
            cursorColor = colorZeroNeon,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------
private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

private fun protoLabel(proto: String): String = when (proto) {
    "vmess" -> "VMess"
    "vless" -> "VLESS"
    "trojan" -> "Trojan"
    "ss" -> "Shadowsocks"
    else -> proto.uppercase()
}

private fun pingColor(ms: Long?): Color? = when {
    ms == null || ms < 0 -> null
    ms < 300 -> colorZeroPingGood()
    ms < 800 -> Color(0xFFFFB020)
    else -> colorZeroFailure
}

private fun colorZeroPingGood(): Color = colorZeroNeonSoft
