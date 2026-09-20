package com.zerovpn.desktop

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Connector — orchestrates xray lifecycle + system proxy + real-ping tests
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
            val ms = withContext(Dispatchers.IO) { PingTest.viaSocks(Store.socksPort) }
            Store.recordPing(p.id, ms)
            ZeroStatsTracker.record(ms)
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

    /** Real ping of the current server through the live tunnel. */
    fun pingCurrent() {
        if (Store.testing) return
        if (Store.status != ConnStatus.CONNECTED) {
            testAll()  // like the phone app: offline pill tests every server
            return
        }
        Store.testing = true
        Store.scope.launch {
            Store.busyMsg = "در حال تست پینگ…"
            val ms = withContext(Dispatchers.IO) { PingTest.viaSocks(Store.socksPort) }
            Store.busyMsg = null
            Store.selectedId?.let { id ->
                Store.recordPing(id, ms)
                ZeroStatsTracker.record(ms)
            }
            Store.testing = false
            if (ms < 0) Store.toast("تست پینگ ناموفق بود")
        }
    }

    /** TCP handshake ping of every server (offline behaviour of the test pill). */
    fun testAll() {
        if (Store.testing || Store.profiles.isEmpty()) return
        Store.testing = true
        Store.scope.launch {
            Store.busyMsg = "در حال تست پینگ همه سرورها…"
            for (p in Store.profiles) {
                val addr = ServerInfo.hostPort(p.link) ?: continue
                val ms = withContext(Dispatchers.IO) { PingTest.tcp(addr.first, addr.second) }
                if (ms > 0) Store.recordPing(p.id, ms)
            }
            Store.busyMsg = null
            Store.testing = false
            Store.toast("تست همه سرورها تمام شد")
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
                val (body, info) = withContext(Dispatchers.IO) { Profiles.fetchWithInfo(url) }
                val links = withContext(Dispatchers.IO) { Profiles.parseBody(body) }
                if (links.isEmpty()) {
                    Store.busyMsg = null
                    Store.toast("هیچ سرور معتبری در اشتراک پیدا نشد")
                    return@launch
                }
                Store.addSubscription(url, "اشتراک ${Store.subscriptions.size + 1}")
                info?.let { inf ->
                    Store.data = Store.data.copy(
                        subscriptions = Store.subscriptions.map {
                            if (it.url == url) it.copy(used = inf.used, total = inf.total, expire = inf.expire) else it
                        }
                    )
                    Store.save()
                }
                val fresh = links.map { p ->
                    ProfileRec(
                        id = Profiles.subId(url, p.link),
                        name = p.name,
                        proto = p.proto,
                        link = p.link,
                        sub = url,
                    )
                }.distinctBy { it.id }
                synchronized(Store) {
                    // Replace ONLY this subscription's profiles — other subs
                    // and manual configs stay untouched (mixing bug fix).
                    val others = Store.profiles.filter { it.sub != url }
                    Store.data = Store.data.copy(profiles = others + fresh)
                    if ((Store.selectedId == null ||
                            Store.profiles.none { it.id == Store.selectedId }) &&
                        Store.profiles.isNotEmpty()
                    ) {
                        Store.data = Store.data.copy(selected = Store.profiles.first().id)
                    }
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
// Theme-aware home colors — follows the app theme (light = the phone look)
// ---------------------------------------------------------------------------
data class ZeroHomeColors(
    val textPrimary: Color,
    val textSecondary: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val pillBg: Color,
    val accent: Color,
    val pingGood: Color,
    val pingMid: Color,
    val pingBad: Color,
)

val zeroHomeColors: ZeroHomeColors
    get() {
        val t = currentTheme
        return ZeroHomeColors(
            textPrimary = t.textPrimary,
            textSecondary = t.textSecondary,
            cardBg = t.cardBg,
            cardBorder = t.cardBorder,
            pillBg = t.pillBg,
            accent = t.accent,
            pingGood = t.pingGood,
            pingMid = t.pingMid,
            pingBad = t.pingBad,
        )
    }

/** Formats seconds as HH:MM:SS (or MM:SS below one hour). */
fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

fun protoLabel(proto: String): String = when (proto) {
    "vmess" -> "VMess"
    "vless" -> "VLESS"
    "trojan" -> "Trojan"
    "ss" -> "Shadowsocks"
    else -> proto.uppercase()
}

fun pingColor(ms: Long?): Color? = when {
    ms == null -> null
    ms <= 0 -> zeroHomeColors.pingBad
    ms <= 120 -> zeroHomeColors.pingGood
    ms <= 400 -> zeroHomeColors.pingMid
    else -> zeroHomeColors.pingBad
}

// ---------------------------------------------------------------------------
// Root — content above the liquid-goo bottom nav, exactly like the phone
// ---------------------------------------------------------------------------
@Composable
fun ZeroApp() {
    var showAddSubGlobal by remember { mutableStateOf(false) }
    LaunchedEffect(Store.pendingAddSub) {
        if (Store.pendingAddSub) {
            Store.pendingAddSub = false
            showAddSubGlobal = true
        }
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalTextStyle provides TextStyle(fontFamily = fontFamilyVazir, color = colorTextPrimary),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zeroBackgroundGlow()
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (Store.view) {
                        "locations" -> LocationsScreen()
                        "settings" -> SettingsScreen()
                        else -> HomeScreen()
                    }
                }
                ZeroBottomNav()
            }

            // Side drawer (servers-screen hamburger)
            if (Store.drawerOpen) AppDrawer()

            Store.busyMsg?.let { msg ->
                BusyBanner(msg, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            Store.toast?.let { msg ->
                ToastBanner(msg, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            }
        }
        if (showAddSubGlobal) AddSubDialog(onDismiss = { showAddSubGlobal = false })
    }
}

// ---------------------------------------------------------------------------
// Home top bar — brand only at the start edge (the hamburger's old spot;
// the brand takes its place exactly like the phone app).
// ---------------------------------------------------------------------------
@Composable
fun ZeroHomeTopBar(modifier: Modifier = Modifier) {
    val hc = zeroHomeColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val logoPainter = remember { loadLogoPainter() }
        logoPainter?.let {
            Image(painter = it, contentDescription = null, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Zero VPN",
            color = hc.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---------------------------------------------------------------------------
// Home screen — exact port of ZeroHomeScreen:
// status word, gooey power ring, big ping, comparison caption, stat pills,
// current-server card.
// ---------------------------------------------------------------------------
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val hc = zeroHomeColors

    // Track connection uptime.
    var uptimeText by remember { mutableStateOf(formatUptime(0)) }
    LaunchedEffect(Store.status == ConnStatus.CONNECTED) {
        if (Store.status == ConnStatus.CONNECTED) {
            while (isActive) {
                val since = Store.connectedSince
                uptimeText = formatUptime(
                    since?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
                )
                delay(1000)
            }
        } else {
            uptimeText = formatUptime(0)
        }
    }

    val isTesting = Store.testing
    val connected = Store.status == ConnStatus.CONNECTED

    // --- Connecting phase --------------------------------------------------
    // From the tap on the power button until the tunnel is actually up the
    // core gives no signal, so the UI owns the state: a colored comet arc
    // spins around the button and the status word reads "connecting". It is
    // cleared the moment the tunnel is up, and self-expires on failure.
    var isConnecting by remember { mutableStateOf(false) }
    LaunchedEffect(connected) {
        if (connected) isConnecting = false
    }
    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            delay(15000)
            isConnecting = false
        }
    }
    val handleToggle: () -> Unit = {
        when {
            connected -> {
                isConnecting = false
                Connector.toggle()
            }
            isConnecting -> Unit // start already in flight — swallow double taps
            else -> {
                isConnecting = true
                Connector.toggle()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ZeroHomeTopBar()

        Spacer(Modifier.height(6.dp))

        // --- Status word (only while testing/connecting — the
        // protected/unprotected wording was removed on request) -------------
        Box(
            modifier = Modifier.height(22.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isTesting || isConnecting) {
                val statusLabel = when {
                    isTesting -> "در حال تست…"
                    else -> "در حال اتصال…"
                }
                val statusColor = when {
                    isTesting -> colorZeroTesting
                    else -> colorZeroNeon
                }
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 5.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // --- Premium connect button ---------------------------------------
        ZeroConnectButton(
            isRunning = connected,
            isTesting = isTesting,
            isConnecting = isConnecting,
            onClick = handleToggle
        )

        Spacer(Modifier.height(12.dp))

        // --- Real-ping test pill -------------------------------------------
        ZeroTestPill(
            connected = connected,
            isTesting = isTesting,
            onClick = { if (connected) Connector.pingCurrent() else Connector.testAll() },
            hc = hc
        )

        Spacer(Modifier.height(14.dp))

        // --- Big ping ------------------------------------------------------
        val shownDelay = Store.ping?.takeIf { it >= 0 }
        // Neon gradient on the number while the tunnel is alive.
        val pingNumberStyle = TextStyle(
            fontSize = 54.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 58.sp
        ).let { base ->
            if (connected || isTesting) {
                base.copy(
                    brush = Brush.horizontalGradient(
                        listOf(hc.accent, colorZeroNeonSoft)
                    )
                )
            } else {
                base.copy(color = hc.textPrimary)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (isTesting) "…" else shownDelay?.toString() ?: "—",
                style = pingNumberStyle
            )
            if (!isTesting && shownDelay != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "ms",
                    color = hc.textSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
        }

        // --- Ping comparison ----------------------------------------------
        val cmp = ZeroStatsTracker.currentPing?.let { cur ->
            ZeroStatsTracker.lastPing?.let { prev ->
                Triple(cur - prev, cur, prev)
            }
        }
        val comparisonText = when {
            cmp == null -> null
            cmp.first > 0 -> "${cmp.first} میلی‌ثانیه بیشتر از قبل"
            cmp.first < 0 -> "${abs(cmp.first)} میلی‌ثانیه کمتر از قبل"
            else -> null
        }
        if (comparisonText != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = comparisonText,
                color = hc.textSecondary,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(18.dp))

        // --- Stat pills: JITTER / LOSS / UPTIME ----------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ZeroStatPill(
                value = ZeroStatsTracker.jitterMs?.toString() ?: "—",
                label = "جیتر",
                valueColor = hc.accent,
                hc = hc,
                modifier = Modifier.weight(1f)
            )
            ZeroStatPill(
                value = "${ZeroStatsTracker.lossPercent}%",
                label = "اتلاف بسته",
                valueColor = when {
                    ZeroStatsTracker.lossPercent == 0 -> hc.pingGood
                    ZeroStatsTracker.lossPercent <= 25 -> hc.pingMid
                    else -> hc.pingBad
                },
                hc = hc,
                modifier = Modifier.weight(1f)
            )
            ZeroStatPill(
                value = uptimeText,
                label = "زمان اتصال",
                valueColor = hc.accent,
                hc = hc,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        // --- Current server card -------------------------------------------
        val sel = Store.selectedProfile
        val host = sel?.let { ServerInfo.hostPort(it.link)?.first }
        val geo = host?.let { GeoLookup.byHost[it] }
        val countryLine = listOf(geo?.ip ?: host).filterNotNull().joinToString("  ·  ")
        ZeroServerCard(
            serverName = sel?.name ?: "هیچ سروری انتخاب نشده",
            flagCode = geo?.cc,
            countryLabel = countryLine,
            pingMillis = sel?.let { Store.pingMap[it.id] },
            quota = Store.selectedQuota,
            onClick = { Store.view = "locations" },
            hc = hc,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        // --- Error line ----------------------------------------------------
        Store.errorMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = colorZeroFailure, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// Premium connect button — exact port from the Android app:
// ambient neon glow → deep glass disc (neon gradient when active, navy +
// ring when idle) → inner rim shading → glossy top highlight → power icon.
// Radar rings pulse while connected; an amber sweep arc spins while testing;
// a neon comet arc chases around the rim while connecting; press gives a
// liquid squish (bouncy spring).
// ---------------------------------------------------------------------------
private const val CONNECT_SIZE_DP = 208
private const val DISC_RADIUS_DP = 88

@Composable
fun ZeroConnectButton(
    isRunning: Boolean,
    isTesting: Boolean,
    isConnecting: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "connectPress"
    )

    val pulse = rememberInfiniteTransition(label = "connectPulse")
    val radarT by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarT"
    )
    val spin by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "connectSpin"
    )
    val breatheT by pulse.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI.toFloat()),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "connectBreathe"
    )
    // Connecting comet: fast rotation + breathing sweep length.
    val connectSpin by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "connectingSpin"
    )
    val connectSweepT by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "connectingSweep"
    )

    Box(
        modifier = modifier
            .size(CONNECT_SIZE_DP.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // --- State glow + radar + test arc ----------------------------------
        Canvas(modifier = Modifier.matchParentSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = DISC_RADIUS_DP.dp.toPx()

            // Ambient glow behind the disc
            val glowColor = when {
                isTesting -> colorZeroTesting
                isConnecting -> colorZeroNeonSoft
                else -> colorZeroNeon
            }
            val glowAlpha = when {
                isTesting -> 0.26f + 0.08f * sin(breatheT)
                isConnecting -> 0.22f + 0.10f * sin(breatheT)
                isRunning -> 0.30f + 0.06f * sin(breatheT)
                else -> 0.16f
            }
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = glowAlpha),
                        Color.Transparent
                    ),
                    center = c,
                    radius = r * 1.5f
                )
            )

            // Radar rings while connected
            if (isRunning && !isTesting) {
                for (phase in 0..1) {
                    val t = (radarT + phase * 0.5f) % 1f
                    drawCircle(
                        color = colorZeroNeonSoft.copy(alpha = (1f - t) * 0.30f),
                        radius = r + t * 26.dp.toPx(),
                        center = c,
                        style = Stroke(width = (2.4f - 1.5f * t).dp.toPx())
                    )
                }
            }

            // Testing sweep arc
            if (isTesting) {
                val arcR = r + 8.dp.toPx()
                drawArc(
                    color = colorZeroTesting,
                    startAngle = spin,
                    sweepAngle = 95f,
                    useCenter = false,
                    topLeft = Offset(c.x - arcR, c.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Connecting comet arc — a neon comet chases around the rim
            // while the tunnel is being established. Head bright, tail fading.
            if (isConnecting && !isTesting) {
                val arcR = r + 8.dp.toPx()
                val sweep = 50f + 170f * connectSweepT
                val brush = Brush.sweepGradient(
                    0.00f to colorZeroNeonSoft.copy(alpha = 0.0f),
                    0.45f to colorZeroNeonSoft.copy(alpha = 0.55f),
                    0.80f to colorZeroNeon,
                    1.00f to Color(0xFF0084D4),
                    center = c
                )
                drawArc(
                    brush = brush,
                    startAngle = connectSpin,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(c.x - arcR, c.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = Stroke(width = 4.6.dp.toPx(), cap = StrokeCap.Round)
                )
                // Bright head dot leading the comet
                val headRad = Math.toRadians((connectSpin + sweep).toDouble())
                drawCircle(
                    color = Color(0xFF4ED8FF),
                    radius = 3.4.dp.toPx(),
                    center = Offset(
                        c.x + arcR * cos(headRad).toFloat(),
                        c.y + arcR * sin(headRad).toFloat()
                    )
                )
            }
        }

        // --- Glass disc -------------------------------------------------------
        Canvas(modifier = Modifier.matchParentSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = DISC_RADIUS_DP.dp.toPx()

            // Ground shadow under the disc
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.40f),
                        Color.Transparent
                    ),
                    center = Offset(c.x, c.y + 16.dp.toPx()),
                    radius = r * 1.08f
                ),
                radius = r * 1.08f,
                center = Offset(c.x, c.y + 16.dp.toPx())
            )

            // Main disc
            if (isRunning || isTesting) {
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF4ED8FF), Color(0xFF0068D2)),
                        startY = c.y - r,
                        endY = c.y + r
                    ),
                    radius = r,
                    center = c
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = r,
                    center = c,
                    style = Stroke(width = 1.6.dp.toPx())
                )
            } else {
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF13243C), Color(0xFF0B1728)),
                        startY = c.y - r,
                        endY = c.y + r
                    ),
                    radius = r,
                    center = c
                )
                drawCircle(
                    color = colorZeroNeon.copy(alpha = 0.85f),
                    radius = r,
                    center = c,
                    style = Stroke(width = 2.2.dp.toPx())
                )
                drawCircle(
                    color = colorZeroNeon.copy(alpha = 0.16f),
                    radius = r + 7.dp.toPx(),
                    center = c,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // Inner rim shading (bottom inner shadow)
            drawArc(
                color = Color.Black.copy(alpha = 0.26f),
                startAngle = 30f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
            )

            // Glossy highlight on the top half
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.30f),
                        Color.White.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    center = Offset(c.x, c.y - r * 0.42f),
                    radius = r * 0.95f
                ),
                radius = r,
                center = c
            )
        }

        // --- Power icon -------------------------------------------------------
        Icon(
            imageVector = ZeroIcons.power,
            contentDescription = if (isRunning) "قطع اتصال" else "اتصال",
            tint = if (isRunning || isTesting) Color.White else colorZeroNeonSoft,
            modifier = Modifier.size(64.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Real-ping test pill under the connect button — port of ZeroTestPill.
// ---------------------------------------------------------------------------
@Composable
private fun ZeroTestPill(
    connected: Boolean,
    isTesting: Boolean,
    onClick: () -> Unit,
    hc: ZeroHomeColors,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(hc.pillBg)
            .border(1.dp, hc.cardBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = ZeroIcons.flash,
            contentDescription = null,
            tint = if (isTesting) colorZeroTesting else hc.accent,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = if (connected) "تست پینگ واقعی" else "تست پینگ همه",
            color = hc.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------------------
// Stat pill (value + label) — flat dark surface like the reference.
// ---------------------------------------------------------------------------
@Composable
private fun ZeroStatPill(
    value: String,
    label: String,
    valueColor: Color,
    hc: ZeroHomeColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(hc.pillBg, RoundedCornerShape(14.dp))
            .border(1.dp, hc.cardBorder.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = value,
            color = valueColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = label,
            color = hc.textSecondary,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Current server card — flat dark row: flag, name, country, colored ping.
// Port of ZeroServerCard.
// ---------------------------------------------------------------------------
@Composable
private fun ZeroServerCard(
    serverName: String,
    countryLabel: String?,
    pingMillis: Long?,
    quota: Pair<Long, Long>? = null,
    onClick: () -> Unit,
    hc: ZeroHomeColors,
    modifier: Modifier = Modifier,
    flagCode: String? = null,
) {
    val cardShape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 0.dp, shape = cardShape)
            .background(hc.cardBg, cardShape)
            .border(1.dp, hc.cardBorder, cardShape)
            .clip(cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Real flag image (Windows has no flag-emoji glyphs) — globe fallback.
        if (flagCode != null) {
            ZeroFlag(countryCode = flagCode, width = 34.dp)
        } else {
            Text(
                text = "🌐",
                fontSize = 26.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = serverName,
                color = hc.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = countryLabel ?: "برای انتخاب سرور بزنید",
                color = hc.textSecondary,
                fontSize = 12.sp,
                maxLines = 1
            )
            // Subscription quota chip — used / total traffic.
            if (quota != null && quota.second > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "⚡ " + trafficString(quota.first) + " از " + trafficString(quota.second),
                    color = hc.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
        pingMillis?.let {
            Text(
                text = "$it ms",
                color = when {
                    it <= 120 -> hc.pingGood
                    it <= 400 -> hc.pingMid
                    else -> hc.pingBad
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = ZeroIcons.chevron,
            contentDescription = null,
            tint = hc.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
