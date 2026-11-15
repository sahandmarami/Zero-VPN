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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
// Root shell — sidebar navigation + content. A real desktop layout (no more
// phone-style bottom goo bar): RTL puts the sidebar on the right edge.
// ---------------------------------------------------------------------------
enum class ZeroTab(val key: String, val label: String, val icon: ImageVector) {
    MAIN("main", "سرورها", ZeroIcons.locations),
    SETTINGS("settings", "تنظیمات", ZeroIcons.settings),
    LOGS("logs", "گزارش", ZeroIcons.logFile),
}

/**
 * v2.5.0 — the app shell is now the ANDROID layout (user: رابط کاربری مثل
 * نسخهٔ اندروید): the main screen = connect card on top + server list below,
 * a bottom navigation bar with the three v2rayNG tabs (سرورها / تنظیمات /
 * گزارش) instead of the desktop sidebar.
 */
@Composable
fun ZeroApp() {
    ZeroTheme {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalTextStyle provides TextStyle(fontFamily = zeroFontFamily, color = colorTextPrimary),
        ) {
            Column(Modifier.fillMaxSize().background(currentTheme.bgBase)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        Store.view == "settings" -> SettingsScreen()
                        Store.view == "logs" -> LogsScreen()
                        else -> MainScreen() // "main" + legacy "home"/"servers"
                    }

                    Store.busyMsg?.let { msg ->
                        BusyBanner(msg, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                    }
                    Store.toast?.let { msg ->
                        ToastBanner(msg, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                    }
                }
                ZeroBottomNav()
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
// Bottom navigation — the v2rayNG Android tabs (سرورها / تنظیمات / گزارش)
// ---------------------------------------------------------------------------
@Composable
fun ZeroBottomNav() {
    val t = currentTheme
    NavigationBar(
        containerColor = t.sidebarBg,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
    ) {
        ZeroTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = Store.view == tab.key ||
                        (tab == ZeroTab.MAIN && Store.view !in setOf("settings", "logs")),
                onClick = { Store.view = tab.key },
                icon = {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (Store.view == tab.key ||
                            (tab == ZeroTab.MAIN && Store.view !in setOf("settings", "logs"))
                        ) t.accent else t.textSecondary
                    )
                },
                label = { Text(tab.label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = t.accent.copy(alpha = 0.14f)
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Main screen — v2rayNG Android layout: connect hero on top, server list below
// ---------------------------------------------------------------------------
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ConnectHero()
        Box(Modifier.fillMaxWidth().height(1.dp).background(currentTheme.divider))
        ServersScreen(embedded = true, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------
// Connect hero — the v2rayNG Android top card: connect button, status, the
// selected server and the live stats row, with the server list right below.
// ---------------------------------------------------------------------------
@Composable
fun ConnectHero(modifier: Modifier = Modifier) {
    val t = currentTheme

    // Connection uptime ticker.
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

    val connected = Store.status == ConnStatus.CONNECTED
    val isTesting = Store.testing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        // --- Update banner ---------------------------------------------------
        Store.updateVersion?.let { ver ->
            UpdateBanner(version = ver, onDownload = { Connector.openReleasePage() })
            Spacer(Modifier.height(10.dp))
        }

        // --- Connection hero card -------------------------------------------
        ZeroCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZeroConnectButton(
                    connected = connected,
                    isConnecting = Store.status == ConnStatus.CONNECTING,
                    isTesting = isTesting,
                    onClick = { Connector.toggle() }
                )
                Spacer(Modifier.width(20.dp))
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val (word, wordColor) = when {
                        isTesting -> "در حال تست…" to t.pingMid
                        Store.status == ConnStatus.CONNECTING -> "در حال اتصال…" to t.accent
                        connected -> "متصل" to t.statusOk
                        else -> "قطع" to t.textSecondary
                    }
                    Text(word, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = wordColor)

                    Spacer(Modifier.height(4.dp))

                    // Real delay of the selected server (through the tunnel
                    // when connected; the stored core test result otherwise).
                    val delay = Store.ping
                    val delayColor = when {
                        delay == null -> t.textSecondary
                        delay > 0 && delay <= 120 -> t.pingGood
                        delay > 0 && delay <= 400 -> t.pingMid
                        delay > 0 -> t.pingBad
                        else -> t.pingBad
                    }
                    Text(
                        text = when {
                            isTesting -> "…"
                            delay == null -> "تأخیر واقعی: —"
                            delay > 0 -> "تأخیر واقعی: $delay ms"
                            else -> "تأخیر واقعی: تایم‌اوت"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = delayColor
                    )

                    Spacer(Modifier.height(6.dp))
                    val sel = Store.selectedProfile
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val host = sel?.let { ServerInfo.hostPort(it.link)?.first }
                        val geo = host?.let { GeoLookup.byHost[it] }
                        geo?.cc?.let { cc -> ZeroFlag(countryCode = cc, width = 22.dp) }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            sel?.name ?: "هیچ سروری انتخاب نشده",
                            fontSize = 13.sp,
                            color = t.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (Store.data.settings.torChain) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(heroGradient)
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "از طریق تور",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- Stats row (compact, v2rayNG-style) ------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                icon = ZeroIcons.download,
                title = "دریافت",
                value = trafficSpeed(TrafficTracker.downSpeed),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = ZeroIcons.upload,
                title = "ارسال",
                value = trafficSpeed(TrafficTracker.upSpeed),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = ZeroIcons.speed,
                title = "تأخیر",
                value = Store.ping?.takeIf { it > 0 }?.let { "$it ms" } ?: "—",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = ZeroIcons.clock,
                title = "زمان",
                value = uptimeText,
                modifier = Modifier.weight(1f)
            )
        }

        Store.errorMsg?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, color = t.pingBad, textAlign = TextAlign.Center)
        }
    }
}

fun trafficSpeed(bytesPerSec: Long): String =
    if (bytesPerSec <= 0) "—" else "${trafficString(bytesPerSec)}/s"

// ---------------------------------------------------------------------------
// Connect button — clean neon disc, comet arc while connecting
// ---------------------------------------------------------------------------
@Composable
fun ZeroConnectButton(
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
    val pressed = interaction.collectIsPressedAsState().value
    val iconScale = if (pressed) 0.9f else 1f

    val sizeDp = 118.dp
    Box(
        modifier = Modifier
            .size(sizeDp)
            .drawBehind {
                // Ambient glow while connected
                if (connected) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                t.accent.copy(alpha = 0.35f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.width * 0.62f
                        )
                    )
                }
            }
            .clip(CircleShape)
            .background(
                brush = when {
                    connected -> heroGradient
                    isConnecting -> Brush.verticalGradient(
                        listOf(t.pillBg, t.pillBg)
                    )
                    else -> Brush.verticalGradient(
                        listOf(t.cardBg, t.pillBg)
                    )
                }
            )
            .border(
                width = if (connected) 0.dp else 2.dp,
                color = if (connected) Color.Transparent else t.cardBorder,
                shape = CircleShape
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            if (isConnecting || isTesting) {
                // Comet arc sweeping the rim
                val sweep = 80f
                val start = phase * 360f
                drawArc(
                    color = if (isTesting) t.pingMid else t.accent,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            } else if (connected) {
                // Slow breathing ring
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f),
                    radius = size.minDimension / 2f - 5.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        Icon(
            imageVector = ZeroIcons.power,
            contentDescription = if (connected) "قطع اتصال" else "اتصال",
            tint = if (connected) Color.White else t.accent,
            modifier = Modifier
                .size(46.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
    }
}

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
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.06f))
            .clip(RoundedCornerShape(18.dp))
            .background(t.cardBg)
            .border(1.dp, t.cardBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
        content = content
    )
}

@Composable
fun StatCard(
    icon: ImageVector,
    title: String,
    value: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
) {
    val t = currentTheme
    ZeroCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(t.pillBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = t.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 11.sp, color = t.textSecondary)
                Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textPrimary)
                sub?.let {
                    Text(it, fontSize = 10.sp, color = t.textSecondary)
                }
            }
        }
    }
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

@Composable
fun ZeroServerCard(
    serverName: String,
    flagCode: String?,
    countryLabel: String,
    pingMillis: Long?,
    quota: Pair<Long, Long>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = currentTheme
    ZeroCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("سرور فعلی", fontSize = 11.sp, color = t.textSecondary)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZeroFlag(countryCode = flagCode, width = 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        serverName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (countryLabel.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(countryLabel, fontSize = 11.sp, color = t.textSecondary)
                }
            }
            pingMillis?.let { ms ->
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
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
                }
            }
        }

        quota?.let { (used, total) ->
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
                        .background(accentGradient)
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
