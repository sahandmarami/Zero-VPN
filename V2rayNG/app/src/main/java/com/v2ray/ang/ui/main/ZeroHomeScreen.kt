package com.v2ray.ang.ui.main

import android.os.Build
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.CoreUpdateManager
import com.v2ray.ang.handler.GeoLookupManager
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.rememberGooeyEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Blue palette replacing the reference's green — same shapes, same
// light/dark rhythm (کم‌رنگی و پررنگی) sampled from the Zero VPN icon.
// ---------------------------------------------------------------------------
val colorZeroNeon = Color(0xFF00A8F5)      // پررنگ — icon ring saturated blue
val colorZeroNeonSoft = Color(0xFF35C6FF)  // کم‌رنگ — light glow blue
val colorZeroConnected = Color(0xFF00A8F5)
val colorZeroIdle = Color(0xFF3A4A61)      // idle dim blue-gray
val colorZeroTesting = Color(0xFFFFB020)   // amber while testing
val colorZeroFailure = Color(0xFFFF5470)   // neon red
val colorZeroPingGood = Color(0xFF35C6FF)  // good ping — light blue
val colorZeroPingMid = Color(0xFFFFB020)   // slow ping — amber (reference orange)
val colorZeroPingBad = Color(0xFFFF5470)   // very slow — red

/** Converts an ISO-3166 alpha-2 code to its regional-indicator flag emoji. */
internal fun countryCodeToFlagEmoji(code: String?): String? {
    val c = code?.trim()?.uppercase() ?: return null
    if (!Regex("^[A-Z]{2}$").matches(c)) return null
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(c[0].code + base)) +
        String(Character.toChars(c[1].code + base))
}

/** Localized country name (follows the app language) for an ISO-3166 code. */
internal fun localizedCountryName(code: String): String? = try {
    java.util.Locale("", code).displayCountry.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/** Formats seconds as HH:MM:SS (or MM:SS below one hour). */
internal fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

// ---------------------------------------------------------------------------
// Session stats measured from real ping tests of the current server.
// ---------------------------------------------------------------------------
object ZeroStatsTracker {
    var lastPing by mutableStateOf<Long?>(null)
    var currentPing by mutableStateOf<Long?>(null)
    var jitterMs by mutableStateOf<Long?>(null)
    var attempts by mutableStateOf(0)
    var failures by mutableStateOf(0)
    var connectedSince by mutableStateOf<Long?>(null)

    val lossPercent: Int
        get() = if (attempts == 0) 0 else (failures * 100 + attempts / 2) / attempts

    /** Record a real-ping outcome for the current server (delay < 0 = failure). */
    fun record(delayMillis: Long) {
        attempts++
        if (delayMillis < 0) {
            failures++
            return
        }
        lastPing = currentPing
        currentPing = delayMillis
        lastPing?.let { prev ->
            val sample = abs(delayMillis - prev)
            jitterMs = (jitterMs?.let { old -> (old * 3 + sample) / 4 } ?: sample)
                .coerceAtLeast(0)
        }
    }
}

// ---------------------------------------------------------------------------
// Theme-aware home colors — flat dark surfaces like the reference shot.
// ---------------------------------------------------------------------------
private data class ZeroHomeColors(
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

@Composable
private fun zeroHomeColors(): ZeroHomeColors {
    val dark = LocalDarkTheme.current
    return if (dark) ZeroHomeColors(
        textPrimary = Color.White,
        textSecondary = Color(0xFF7C8CA6),
        cardBg = Color(0xFF141A24),   // solid charcoal like the reference cards
        cardBorder = Color(0xFF212C3C),
        pillBg = Color(0xFF12171F),   // solid dark pill
        accent = colorZeroNeonSoft,   // light glow blue
        pingGood = colorZeroPingGood,
        pingMid = colorZeroPingMid,
        pingBad = colorZeroPingBad,
    ) else ZeroHomeColors(
        textPrimary = Color(0xFF0B1B33),
        textSecondary = Color(0xFF54678A),
        cardBg = Color(0xFFFFFFFF),
        cardBorder = Color(0xFFD9E4F6),
        pillBg = Color(0xFFEBF2FD),   // pale azure pill
        accent = Color(0xFF0084D4),   // deeper neon for bright surfaces
        pingGood = Color(0xFF0091D6),
        pingMid = Color(0xFFDD8A00),
        pingBad = Color(0xFFE23A5F),
    )
}

// ---------------------------------------------------------------------------
// Home top bar: drawer menu, brand, settings gear.
// ---------------------------------------------------------------------------
@Composable
fun ZeroHomeTopBar(
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hc = zeroHomeColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(58.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(
                painter = painterResource(R.drawable.ic_menu_24dp),
                contentDescription = stringResource(R.string.acc_open_menu),
                tint = hc.textPrimary
            )
        }
        Spacer(Modifier.width(6.dp))
        Image(
            painter = painterResource(R.drawable.zero_logo),
            contentDescription = null,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.app_name),
            color = hc.textPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSettingsClick) {
            Icon(
                painter = painterResource(R.drawable.ic_settings_24dp),
                contentDescription = stringResource(R.string.zero_tab_settings),
                tint = hc.textPrimary
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Home screen — exact reference layout, blue instead of green:
// status word, gooey power ring with melting bolt droplet, big ping,
// comparison caption, stat pills, current-server card. No account section.
// ---------------------------------------------------------------------------
@Composable
fun ZeroHomeScreen(
    isRunning: Boolean,
    status: MainStatus,
    selectedServerName: String,
    selectedServerDelay: Long,
    selectedServerGeo: GeoLookupManager.ServerGeo? = null,
    onToggle: () -> Unit,
    onTestCurrent: () -> Unit,
    onTestAll: () -> Unit,
    onOpenLocations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Feed real-ping outcomes into the stats tracker.
    LaunchedEffect(status) {
        (status as? MainStatus.ConnectionTest)?.let { ZeroStatsTracker.record(it.result.delayMillis) }
    }
    // Track connection uptime.
    LaunchedEffect(isRunning) {
        if (isRunning && ZeroStatsTracker.connectedSince == null) {
            ZeroStatsTracker.connectedSince = System.currentTimeMillis()
        } else if (!isRunning) {
            ZeroStatsTracker.connectedSince = null
        }
    }
    var uptimeText by remember { mutableStateOf(formatUptime(0)) }
    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (isActive) {
                val since = ZeroStatsTracker.connectedSince
                uptimeText = formatUptime(
                    since?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
                )
                delay(1000)
            }
        } else {
            uptimeText = formatUptime(0)
        }
    }

    val isTesting = status is MainStatus.Testing || status is MainStatus.TestProgress
    val connected = isRunning
    val hc = zeroHomeColors()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(6.dp))

        // --- Status word -------------------------------------------------
        val statusLabel = when {
            isTesting -> stringResource(R.string.zero_testing)
            connected -> stringResource(R.string.zero_protected)
            else -> stringResource(R.string.zero_unprotected)
        }
        val statusColor = when {
            isTesting -> colorZeroTesting
            connected -> hc.accent
            else -> hc.textSecondary
        }
        Text(
            text = statusLabel,
            color = statusColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 5.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(18.dp))

        // --- Premium connect button ---------------------------------------
        ZeroConnectButton(
            isRunning = isRunning,
            isTesting = isTesting,
            onClick = onToggle
        )

        Spacer(Modifier.height(12.dp))

        // --- Real-ping test pill -------------------------------------------
        ZeroTestPill(
            connected = connected,
            isTesting = isTesting,
            onClick = { if (connected) onTestCurrent() else onTestAll() },
            hc = hc
        )

        Spacer(Modifier.height(14.dp))

        // --- Big ping ------------------------------------------------------
        val testDelay = (status as? MainStatus.ConnectionTest)?.result?.delayMillis
        val shownDelay = testDelay ?: selectedServerDelay.takeIf { it > 0 }
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
                text = if (isTesting) "…" else shownDelay?.takeIf { it >= 0 }?.toString() ?: "—",
                style = pingNumberStyle
            )
            if (!isTesting && shownDelay != null && shownDelay >= 0) {
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
            cmp.first > 0 -> stringResource(R.string.zero_ping_higher, cmp.first.toString())
            cmp.first < 0 -> stringResource(R.string.zero_ping_lower, abs(cmp.first).toString())
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
                label = stringResource(R.string.zero_jitter),
                valueColor = hc.accent,
                hc = hc,
                modifier = Modifier.weight(1f)
            )
            ZeroStatPill(
                value = "${ZeroStatsTracker.lossPercent}%",
                label = stringResource(R.string.zero_loss),
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
                label = stringResource(R.string.zero_uptime),
                valueColor = hc.accent,
                hc = hc,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        // --- Current server card -------------------------------------------
        // True exit country/IP come from a live tunnel test; the entry-side
        // geo lookup fills the card in before any test has run.
        val testResult = status as? MainStatus.ConnectionTest
        val exitCountry = testResult?.result?.country
        val exitIp = testResult?.result?.ipAddress
        val effCountryCode = exitCountry ?: selectedServerGeo?.countryCode
        val effIp = exitIp ?: selectedServerGeo?.ipAddress
        val flag = countryCodeToFlagEmoji(effCountryCode)
        val countryLine = listOf(
            effCountryCode?.let { localizedCountryName(it) ?: it },
            effIp
        ).filterNotNull().joinToString("  ·  ")
        ZeroServerCard(
            serverName = selectedServerName.ifBlank {
                stringResource(R.string.zero_no_server)
            },
            countryLabel = countryLine.takeIf { it.isNotBlank() },
            flagEmoji = flag,
            pingMillis = shownDelay?.takeIf { it >= 0 },
            onClick = onOpenLocations,
            hc = hc,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// Premium connect button — layered vector design, crisp at every density:
// ambient neon glow → deep glass disc (neon gradient when active, navy +
// ring when idle) → inner rim shading → glossy top highlight → power icon.
// Radar rings pulse while connected; an amber sweep arc spins while testing;
// press gives a liquid squish (bouncy spring).
// ---------------------------------------------------------------------------
private const val CONNECT_SIZE_DP = 208
private const val DISC_RADIUS_DP = 88

@Composable
fun ZeroConnectButton(
    isRunning: Boolean,
    isTesting: Boolean,
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
            val glowColor = if (isTesting) colorZeroTesting else colorZeroNeon
            val glowAlpha = when {
                isTesting -> 0.26f + 0.08f * sin(breatheT)
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
            painter = painterResource(R.drawable.ic_zero_power_24dp),
            contentDescription = stringResource(
                if (isRunning) R.string.zero_disconnect else R.string.zero_connect
            ),
            tint = if (isRunning || isTesting) Color.White else colorZeroNeonSoft,
            modifier = Modifier.size(64.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Real-ping test pill under the connect button.
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
            painter = painterResource(R.drawable.ic_flash_on_24dp),
            contentDescription = null,
            tint = if (isTesting) colorZeroTesting else hc.accent,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(
                if (connected) R.string.zero_test_ping else R.string.zero_test_all
            ),
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = hc.textSecondary,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
// Current server card — flat dark row: flag, name, country, colored ping.
// ---------------------------------------------------------------------------
@Composable
private fun ZeroServerCard(
    serverName: String,
    countryLabel: String?,
    flagEmoji: String?,
    pingMillis: Long?,
    onClick: () -> Unit,
    hc: ZeroHomeColors,
    modifier: Modifier = Modifier
) {
    val dark = LocalDarkTheme.current
    val cardShape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (dark) 0.dp else 10.dp,
                shape = cardShape,
                ambientColor = Color(0x2E0084D4),
                spotColor = Color(0x330084D4)
            )
            .background(hc.cardBg, cardShape)
            .border(1.dp, hc.cardBorder, cardShape)
            .clip(cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = flagEmoji ?: "🌐",
            fontSize = 26.sp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = serverName,
                color = hc.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = countryLabel
                    ?: stringResource(R.string.zero_open_locations),
                color = hc.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        pingMillis?.let {
            Text(
                text = stringResource(R.string.server_test_delay_value, it),
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
            painter = painterResource(R.drawable.ic_zero_chevron_right_24dp),
            contentDescription = null,
            tint = hc.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Update banners (app build / Xray core) — shown above the server card area.
// ---------------------------------------------------------------------------
@Composable
fun ZeroUpdateBanners(
    appUpdateVersion: String?,
    onUpdateApp: () -> Unit,
    coreUpdate: CoreUpdateManager.CoreUpdateResult?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (appUpdateVersion != null) {
        ZeroBannerRow(
            text = stringResource(R.string.zero_update_banner, appUpdateVersion),
            actionLabel = stringResource(R.string.update_now),
            onAction = onUpdateApp,
            onDismiss = onDismiss,
            modifier = modifier
        )
    } else if (coreUpdate != null && coreUpdate.hasUpdate) {
        ZeroBannerRow(
            text = stringResource(
                R.string.zero_core_banner,
                coreUpdate.latestVersion ?: "",
                coreUpdate.currentVersion ?: ""
            ),
            actionLabel = stringResource(R.string.update_now),
            onAction = onUpdateApp,
            onDismiss = onDismiss,
            modifier = modifier
        )
    }
}

@Composable
private fun ZeroBannerRow(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Color(0xFF141A24), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF212C3C), RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFDDE7F5),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onDismiss) {
            Icon(
                painter = painterResource(R.drawable.ic_zero_close_24dp),
                contentDescription = stringResource(R.string.zero_dismiss),
                tint = Color(0xFF7C8CA6),
                modifier = Modifier.size(16.dp)
            )
        }
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 6.dp
            )
        ) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}
