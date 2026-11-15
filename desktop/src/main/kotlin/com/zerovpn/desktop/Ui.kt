package com.zerovpn.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive

// ---------------------------------------------------------------------------
// Root shell — sidebar navigation + content. A real desktop layout (RTL puts
// the sidebar on the right edge). The home screen follows the user's mockup:
// status pill → glowing connect ring → server card → stats strip → tiles.
// ---------------------------------------------------------------------------
enum class ZeroTab(val key: String, val label: String, val icon: ImageVector) {
    HOME("home", "خانه", ZeroIcons.home),
    SERVERS("servers", "سرورها", ZeroIcons.locations),
    SETTINGS("settings", "تنظیمات", ZeroIcons.settings),
    LOGS("logs", "گزارش", ZeroIcons.logFile),
}

@Composable
fun ZeroApp() {
    ZeroTheme {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalTextStyle provides TextStyle(fontFamily = zeroFontFamily, color = colorTextPrimary),
        ) {
            Row(Modifier.fillMaxSize().background(currentTheme.bgBase)) {
                ZeroSidebar()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (Store.view) {
                        "servers" -> ServersScreen()
                        "settings" -> SettingsScreen()
                        "logs" -> LogsScreen()
                        else -> HomeScreen()
                    }

                    Store.busyMsg?.let { msg ->
                        BusyBanner(msg, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                    }
                    Store.toast?.let { msg ->
                        ToastBanner(msg, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ZeroTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = if (ThemeState.dark)
            androidx.compose.material3.darkColorScheme(
                primary = currentTheme.accent,
                background = currentTheme.bgBase,
                surface = currentTheme.cardBg,
                onBackground = currentTheme.textPrimary,
                onSurface = currentTheme.textPrimary,
            )
        else
            androidx.compose.material3.lightColorScheme(
                primary = currentTheme.accent,
                background = currentTheme.bgBase,
                surface = currentTheme.cardBg,
                onBackground = currentTheme.textPrimary,
                onSurface = currentTheme.textPrimary,
            ),
        typography = zeroTypography,
        content = content
    )
}

// ---------------------------------------------------------------------------
// Sidebar
// ---------------------------------------------------------------------------
@Composable
fun ZeroSidebar() {
    val t = currentTheme
    Column(
        modifier = Modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(t.sidebarBg)
            .border(width = 1.dp, color = t.cardBorder)
            .padding(14.dp)
    ) {
        // Brand — gradient plate behind the logo (mockup identity)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(heroGradient)
                    .padding(5.dp)
            ) {
                val logoPainter = remember { loadLogoPainter() }
                logoPainter?.let {
                    Image(painter = it, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text("Zero VPN", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = t.textPrimary)
                Text(
                    "نسخه $APP_VERSION",
                    fontSize = 10.sp,
                    color = t.textSecondary
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Live status line
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (dotColor, label) = when (Store.status) {
                ConnStatus.CONNECTED -> t.statusOk to "متصل"
                ConnStatus.CONNECTING -> t.pingMid to "در حال اتصال…"
                ConnStatus.DISCONNECTED -> t.statusIdle to "متصل نیست"
            }
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, color = t.textSecondary)
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(t.divider)
        )
        Spacer(Modifier.height(12.dp))

        ZeroTab.entries.forEach { tab ->
            ZeroNavRow(
                tab = tab,
                selected = Store.view == tab.key,
                onClick = { Store.view = tab.key }
            )
        }

        Spacer(Modifier.weight(1f))

        // Core provenance footer
        Column(Modifier.fillMaxWidth()) {
            Text(
                "هسته: فورک PattNG از Xray",
                fontSize = 10.sp,
                color = t.textSecondary
            )
            Store.coreVersion?.let {
                Text("نسخه هسته: $it", fontSize = 10.sp, color = t.textSecondary)
            }
        }
    }
}

@Composable
private fun ZeroNavRow(tab: ZeroTab, selected: Boolean, onClick: () -> Unit) {
    val t = currentTheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) heroGradient else SolidColor(Color.Transparent))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = if (selected) Color.White else t.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            tab.label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else t.textPrimary
        )
        Spacer(Modifier.weight(1f))
        if (tab == ZeroTab.SERVERS && Store.profiles.isNotEmpty()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White.copy(alpha = 0.25f) else t.pillBg)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    Store.profiles.size.toString(),
                    fontSize = 11.sp,
                    color = if (selected) Color.White else t.textSecondary
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Home screen — the user's mockup: centered pill, big glowing ring, server
// card, stats strip, tiles. Every element below the ring is one idea, airy
// spacing, nothing cramped.
// ---------------------------------------------------------------------------
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val t = currentTheme
    val connected = Store.status == ConnStatus.CONNECTED
    val isTesting = Store.testing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Update banner ---------------------------------------------------
        Store.updateVersion?.let { ver ->
            UpdateBanner(version = ver, onDownload = { Connector.openReleasePage() })
            Spacer(Modifier.height(12.dp))
        }

        // --- Status pill (متصل / در حال اتصال… / متصل نیست + uptime) ---------
        ZeroStatusPill()
        Spacer(Modifier.height(16.dp))

        // --- The glowing connect ring -----------------------------------------
        ZeroConnectRing(
            connected = connected,
            isConnecting = Store.status == ConnStatus.CONNECTING,
            isTesting = isTesting,
            onClick = { Connector.toggle() }
        )
        Spacer(Modifier.height(16.dp))

        // --- Current server card (mockup: flag + country + chevron) ----------
        ZeroCurrentServerCard(onClick = { Store.view = "servers" })
        Spacer(Modifier.height(12.dp))

        // --- Real ping action ---------------------------------------------------
        Row(modifier = Modifier.fillMaxWidth()) {
            ZeroActionButton(
                icon = ZeroIcons.flash,
                label = if (connected) "تست پینگ فعلی" else "پینگ واقعی همه سرورها",
                primary = false,
                onClick = {
                    if (connected) Connector.pingCurrent() else Connector.testAll()
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // --- Stats strip: دریافت / ارسال / پینگ (mockup row) ------------------
        ZeroStatsStrip()
        Spacer(Modifier.height(12.dp))

        // --- Tiles grid (mockup menu tiles) -----------------------------------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZeroTile(
                icon = ZeroIcons.locations,
                label = "سرورها",
                sub = if (Store.profiles.isEmpty()) "افزودن اشتراک یا کانفیگ" else "${Store.profiles.size} سرور",
                onClick = { Store.view = "servers" },
                modifier = Modifier.weight(1f)
            )
            ZeroTile(
                icon = ZeroIcons.settings,
                label = "تنظیمات",
                sub = "پورت، پراکسی، تم",
                onClick = { Store.view = "settings" },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZeroTile(
                icon = ZeroIcons.logFile,
                label = "گزارش هسته",
                sub = "خروجی و خطاهای xray",
                onClick = { Store.view = "logs" },
                modifier = Modifier.weight(1f)
            )
            ZeroTile(
                icon = ZeroIcons.shield,
                label = "درباره",
                sub = "نسخه $APP_VERSION",
                onClick = { Store.view = "settings" },
                modifier = Modifier.weight(1f)
            )
        }

        Store.errorMsg?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, color = t.pingBad, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// Status pill — self-contained uptime ticker (mockup top pill)
// ---------------------------------------------------------------------------
@Composable
fun ZeroStatusPill(modifier: Modifier = Modifier) {
    val t = currentTheme
    var uptimeText by remember { mutableStateOf(formatUptime(0)) }
    androidx.compose.runtime.LaunchedEffect(Store.status == ConnStatus.CONNECTED) {
        if (Store.status == ConnStatus.CONNECTED) {
            while (isActive) {
                val since = Store.connectedSince
                uptimeText = formatUptime(
                    since?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
                )
                kotlinx.coroutines.delay(1000)
            }
        } else {
            uptimeText = formatUptime(0)
        }
    }
    val (dotColor, label) = when (Store.status) {
        ConnStatus.CONNECTED -> t.statusOk to "متصل  ·  $uptimeText"
        ConnStatus.CONNECTING -> t.pingMid to "در حال اتصال…"
        ConnStatus.DISCONNECTED -> t.statusIdle to "متصل نیست"
    }
    Row(
        modifier = modifier
            .shadow(3.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(t.pillBg)
            .border(1.dp, t.cardBorder, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary)
        if (Store.data.settings.torChain) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(heroGradient)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("تور", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Connect ring — the mockup centerpiece: a thick violet comet ring around a
// dark disc, ambient glow when connected, sweeping arc while busy.
// ---------------------------------------------------------------------------
@Composable
fun ZeroConnectRing(
    connected: Boolean,
    isConnecting: Boolean,
    isTesting: Boolean,
    onClick: () -> Unit,
) {
    val t = currentTheme
    val interaction = remember { MutableInteractionSource() }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulsePhase"
    )
    val breathe by pulse.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )
    val pressed = interaction.collectIsPressedAsState().value
    val iconScale = if (pressed) 0.93f else 1f
    val sizeDp = 204.dp

    Box(
        modifier = Modifier
            .size(sizeDp)
            .drawBehind {
                // Ambient glow (stronger + breathing while connected)
                val alpha = when {
                    connected -> 0.34f * breathe
                    isConnecting || isTesting -> 0.22f
                    else -> 0.14f
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            t.accent.copy(alpha = alpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.width * 0.62f
                    )
                )
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val ringW = 9.dp.toPx()
            val radius = size.minDimension / 2f - ringW - 2.dp.toPx()
            // faint base ring
            drawCircle(
                color = t.accent.copy(alpha = 0.18f),
                radius = radius,
                style = Stroke(width = ringW)
            )
            // violet comet ring with a gap (mockup look)
            drawArc(
                brush = ringSweep,
                startAngle = -50f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = ringW, cap = StrokeCap.Round)
            )
            // sweeping highlight while busy
            if (isConnecting || isTesting) {
                drawArc(
                    color = Color.White.copy(alpha = 0.9f),
                    startAngle = phase * 360f,
                    sweepAngle = 62f,
                    useCenter = false,
                    style = Stroke(width = ringW + 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        // inner disc
        Box(
            Modifier
                .size(148.dp)
                .clip(CircleShape)
                .background(
                    brush = if (connected) Brush.radialGradient(
                        listOf(t.accentDeep.copy(alpha = 0.55f), t.pillBg)
                    ) else SolidColor(t.pillBg)
                )
                .border(1.dp, t.cardBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = ZeroIcons.power,
                    contentDescription = if (connected) "قطع اتصال" else "اتصال",
                    tint = if (connected) Color.White else t.accent,
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = when {
                        isTesting -> "در حال تست…"
                        isConnecting -> "در حال اتصال…"
                        connected -> "برای قطع بزنید"
                        else -> "برای اتصال بزنید"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (connected) Color.White.copy(alpha = 0.9f) else t.textSecondary
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Stats strip — دریافت / ارسال / پینگ in one divided card (mockup row)
// ---------------------------------------------------------------------------
@Composable
fun ZeroStatsStrip(modifier: Modifier = Modifier) {
    val t = currentTheme
    ZeroCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StripCell(
                icon = ZeroIcons.download,
                value = trafficSpeed(TrafficTracker.downSpeed),
                label = "دریافت",
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.width(1.dp).height(44.dp).background(t.divider))
            StripCell(
                icon = ZeroIcons.upload,
                value = trafficSpeed(TrafficTracker.upSpeed),
                label = "ارسال",
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.width(1.dp).height(44.dp).background(t.divider))
            StripCell(
                icon = ZeroIcons.speed,
                value = Store.ping?.takeIf { it > 0 }?.let { "$it ms" } ?: "—",
                label = "پینگ",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StripCell(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val t = currentTheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = t.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textPrimary)
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = t.textSecondary)
    }
}

// ---------------------------------------------------------------------------
// Menu tile — mockup's Statistics/Servers/Settings/About cards
// ---------------------------------------------------------------------------
@Composable
fun ZeroTile(
    icon: ImageVector,
    label: String,
    sub: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = currentTheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(t.cardBg)
            .border(1.dp, t.cardBorder, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(heroGradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary)
            sub?.let { Text(it, fontSize = 10.sp, color = t.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(
            ZeroIcons.chevron,
            contentDescription = null,
            tint = t.textSecondary,
            modifier = Modifier.size(15.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Current server card — mockup server selector (flag + country + chevron)
// ---------------------------------------------------------------------------
@Composable
fun ZeroCurrentServerCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = currentTheme
    val sel = Store.selectedProfile
    val host = sel?.let { ServerInfo.hostPort(it.link)?.first }
    val geo = host?.let { GeoLookup.byHost[it] }
    val countryName = localizedCountryName(geo?.cc)
    ZeroCard(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (geo?.cc != null) {
                ZeroFlag(countryCode = geo.cc, width = 30.dp)
                Spacer(Modifier.width(11.dp))
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(t.pillBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(ZeroIcons.locations, contentDescription = null, tint = t.accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(11.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    countryName ?: sel?.name ?: "هیچ سروری انتخاب نشده",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = t.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (countryName != null) sel?.name ?: ""
                    else if (sel != null) "سرور فعلی" else "از «سرورها» یک کانفیگ انتخاب یا اضافه کنید",
                    fontSize = 11.sp,
                    color = t.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val ms = sel?.let { Store.pingMap[it.id] }
            if (ms != null) {
                val color = when {
                    ms > 0 && ms <= 120 -> t.pingGood
                    ms > 0 && ms <= 400 -> t.pingMid
                    else -> t.pingBad
                }
                val label = if (ms > 0) "$ms ms" else "تایم‌اوت"
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(color.copy(alpha = 0.12f))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
                }
                Spacer(Modifier.width(6.dp))
            }
            Icon(ZeroIcons.chevron, contentDescription = null, tint = t.textSecondary, modifier = Modifier.size(16.dp))
        }

        Store.selectedQuota?.let { (used, total) ->
            Spacer(Modifier.height(12.dp))
            val progress = (used.toFloat() / total).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(t.pillBg)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(heroGradient)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "${trafficString(used)} از ${trafficString(total)}",
                    fontSize = 11.sp, color = t.textPrimary
                )
                Spacer(Modifier.weight(1f))
                val remaining = (total - used).coerceAtLeast(0)
                Text(
                    "${trafficString(remaining)} باقی مانده",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (remaining <= 0L) t.pingBad else t.accent
                )
            }
        }
    }
}

fun trafficSpeed(bytesPerSec: Long): String =
    if (bytesPerSec <= 0) "—" else "${trafficString(bytesPerSec)}/s"

// ---------------------------------------------------------------------------
// Shared components
// ---------------------------------------------------------------------------
@Composable
fun ZeroCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val t = currentTheme
    Column(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.06f))
            .clip(RoundedCornerShape(18.dp))
            .background(t.cardBg)
            .border(1.dp, t.cardBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
        content = content
    )
}

@Composable
fun ZeroActionButton(
    icon: ImageVector,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = currentTheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                brush = if (primary) accentGradient else SolidColor(t.cardBg)
            )
            .border(
                1.dp,
                if (primary) Color.Transparent else t.cardBorder,
                RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (primary) Color.White else t.accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (primary) Color.White else t.textPrimary
        )
    }
}

// ---------------------------------------------------------------------------
// Banners
// ---------------------------------------------------------------------------
/** Newer release banner — gradient-edged card, one-tap download. */
@Composable
fun UpdateBanner(version: String, onDownload: () -> Unit) {
    val t = currentTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(t.accent.copy(alpha = 0.10f))
            .border(1.dp, t.accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(heroGradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(ZeroIcons.download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "نسخه جدید $version منتشر شد",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = t.textPrimary
            )
            Text(
                "برای رفع خطاها و بهبود سرعت، بروزرسانی کنید",
                fontSize = 11.sp,
                color = t.textSecondary
            )
        }
        ZeroChip("دانلود", primary = true) { onDownload() }
    }
}

@Composable
fun BusyBanner(msg: String, modifier: Modifier = Modifier) {
    val t = currentTheme
    Row(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(t.bannerBg)
            .border(1.dp, t.cardBorder, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
            drawCircle(
                color = t.accent,
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(msg, fontSize = 12.sp, color = t.textPrimary)
    }
}

@Composable
fun ToastBanner(msg: String, modifier: Modifier = Modifier) {
    val t = currentTheme
    Row(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(t.bannerBg)
            .border(1.dp, t.cardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .widthInMax(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(msg, fontSize = 12.sp, color = t.textPrimary, maxLines = 2)
    }
}

private fun Modifier.widthInMax(): Modifier = this

// ---------------------------------------------------------------------------
// Dialog shell — a themed, centered card on a scrim
// ---------------------------------------------------------------------------
@Composable
fun DialogShell(content: @Composable () -> Unit) {
    val t = currentTheme
    Box(
        Modifier
            .fillMaxSize()
            .background(t.scrim),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(420.dp)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(t.cardBg)
                .padding(20.dp)
        ) {
            content()
        }
    }
}

@Composable
fun ZeroChip(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = currentTheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(brush = if (primary) accentGradient else SolidColor(t.pillBg))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (primary) Color.White else t.textPrimary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ZeroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(placeholder, fontSize = 13.sp, color = currentTheme.textSecondary)
        },
        singleLine = true,
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
        modifier = modifier.fillMaxWidth()
    )
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
        Text(message, fontSize = 13.sp, color = currentTheme.textSecondary)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZeroChip("انصراف", primary = false, modifier = Modifier.weight(1f)) { onDismiss() }
            ZeroChip(confirmLabel, primary = true, modifier = Modifier.weight(1f)) {
                onDismiss()
                onConfirm()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
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
    "hysteria2" -> "Hysteria2"
    else -> proto.uppercase()
}

fun pingColor(ms: Long?): Color? = when {
    ms == null -> null
    ms <= 0 -> currentTheme.pingBad
    ms <= 120 -> currentTheme.pingGood
    ms <= 400 -> currentTheme.pingMid
    else -> currentTheme.pingBad
}

fun setClipboard(text: String) {
    try {
        val tool = java.awt.Toolkit.getDefaultToolkit()
        val clip = tool.systemClipboard
        clip.setContents(java.awt.datatransfer.StringSelection(text), null)
    } catch (_: Throwable) { }
}
