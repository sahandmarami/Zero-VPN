package com.v2ray.ang.ui.main

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.CoreUpdateManager
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.colorNeonBlue
import com.v2ray.ang.ui.compose.colorNeonCyan
import com.v2ray.ang.ui.compose.colorNeonGlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Palette derived from the real Zero VPN icon (neon ring #00A8F5 on navy).
// ---------------------------------------------------------------------------
val colorZeroNeon = Color(0xFF00A8F5)      // icon ring neon blue
val colorZeroNeonSoft = Color(0xFF35C6FF)  // lighter glow
val colorZeroConnected = Color(0xFF00A8F5)
val colorZeroIdle = Color(0xFF55637C)      // idle ring gray-blue
val colorZeroTesting = Color(0xFFFBBF24)   // amber while testing
val colorZeroFailure = Color(0xFFFF5470)   // neon red
val colorZeroPingGood = Color(0xFF35E08C)  // good ping green

/** Converts an ISO-3166 alpha-2 code to its regional-indicator flag emoji. */
internal fun countryCodeToFlagEmoji(code: String?): String? {
    val c = code?.trim()?.uppercase() ?: return null
    if (!Regex("^[A-Z]{2}$").matches(c)) return null
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(c[0].code + base)) +
        String(Character.toChars(c[1].code + base))
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
// Theme-aware home colors: the neon-blue identity stays, text/panels adapt
// so the home screen stays readable in light mode too.
// ---------------------------------------------------------------------------
private data class ZeroHomeColors(
    val textPrimary: Color,
    val textSecondary: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val pillBg: Color,
    val boltBg: Color,
)

@Composable
private fun zeroHomeColors(): ZeroHomeColors {
    val dark = LocalDarkTheme.current
    return if (dark) ZeroHomeColors(
        textPrimary = Color.White,
        textSecondary = Color(0xFF8296B4),
        cardBg = Color(0x7312233E),
        cardBorder = Color(0x3D35C6FF),
        pillBg = Color(0x6612233E),
        boltBg = Color(0xFF12233E),
    ) else ZeroHomeColors(
        textPrimary = Color(0xFF12192A),
        textSecondary = Color(0xFF5A6B85),
        cardBg = Color(0xF2FFFFFF),
        cardBorder = Color(0x330077E0),
        pillBg = Color(0xE6FFFFFF),
        boltBg = Color(0xFFFFFFFF),
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
// Home screen — layout modeled on the requested reference:
// status word, big gooey power ring, bolt test, big ping, stat pills,
// current-server card. Neon-blue like the icon. No account section.
// ---------------------------------------------------------------------------
@Composable
fun ZeroHomeScreen(
    isRunning: Boolean,
    status: MainStatus,
    selectedServerName: String,
    selectedServerDelay: Long,
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
        Spacer(Modifier.height(4.dp))

        // --- Status word -------------------------------------------------
        val statusLabel = when {
            isTesting -> stringResource(R.string.zero_testing)
            connected -> stringResource(R.string.zero_protected)
            else -> stringResource(R.string.zero_unprotected)
        }
        val statusColor = when {
            isTesting -> colorZeroTesting
            connected -> colorZeroConnected
            else -> colorZeroIdle
        }
        Text(
            text = statusLabel,
            color = statusColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 5.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(22.dp))

        // --- Big gooey power button --------------------------------------
        GooeyPowerButton(
            isRunning = isRunning,
            isTesting = isTesting,
            onClick = onToggle
        )

        Spacer(Modifier.height(10.dp))

        // --- Bolt: real ping test ----------------------------------------
        BoltTestButton(
            isTesting = isTesting,
            connected = connected,
            onClick = { if (connected) onTestCurrent() else onTestAll() }
        )

        Spacer(Modifier.height(18.dp))

        // --- Big ping ------------------------------------------------------
        val testDelay = (status as? MainStatus.ConnectionTest)?.result?.delayMillis
        val shownDelay = testDelay ?: selectedServerDelay.takeIf { it > 0 }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (isTesting) "…" else shownDelay?.takeIf { it >= 0 }?.toString() ?: "—",
                color = hc.textPrimary,
                fontSize = 54.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 58.sp
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
                hc = hc,
                modifier = Modifier.weight(1f)
            )
            ZeroStatPill(
                value = "${ZeroStatsTracker.lossPercent}%",
                label = stringResource(R.string.zero_loss),
                hc = hc,
                modifier = Modifier.weight(1f)
            )
            ZeroStatPill(
                value = uptimeText,
                label = stringResource(R.string.zero_uptime),
                hc = hc,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        // --- Current server card -------------------------------------------
        val country = (status as? MainStatus.ConnectionTest)?.result?.country
        val flag = countryCodeToFlagEmoji(country)
        ZeroServerCard(
            serverName = selectedServerName.ifBlank {
                stringResource(R.string.zero_no_server)
            },
            countryLabel = country,
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
// Gooey power button — native liquid-gooey metaball remake (blur + threshold),
// spring overshoot cubic-bezier(0.34, 1.56, 0.64, 1) ~550 ms.
// ---------------------------------------------------------------------------
@Composable
fun GooeyPowerButton(
    isRunning: Boolean,
    isTesting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Liquid squish on press.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale"
    )

    // Burst: satellites merge inside when idle, fling out when running.
    var burstTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(isRunning) { burstTarget = if (isRunning) 1f else 0f }
    val burst by animateFloatAsState(
        targetValue = burstTarget,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "burst"
    )

    // Gentle liquid pulse while connected.
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseT by pulse.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI.toFloat()),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseT"
    )
    // Slow rotation for the testing arc.
    val spin by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    val blobColor = if (isRunning) colorZeroNeon else Color(0xFF16233B)
    val satelliteColor = colorZeroNeonSoft
    val ringColor = when {
        isTesting -> colorZeroTesting
        isRunning -> colorZeroNeon
        else -> colorZeroIdle
    }

    val gooeyEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val blur = RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP)
                val cm = android.graphics.ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, 0f,
                        0f, 0f, 0f, 30f, -640f
                    )
                )
                RenderEffect.createColorFilterEffect(
                    android.graphics.ColorMatrixColorFilter(cm), blur
                ).asComposeRenderEffect()
            } catch (_: Exception) {
                null
            }
        } else null
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Glowing outer ring (crisp layer, outside the goo blur).
        Canvas(modifier = Modifier.size(196.dp)) {
            val c = center
            val stroke = 3.5.dp.toPx()
            // soft glow
            drawCircle(
                color = ringColor.copy(alpha = 0.22f),
                radius = 82.dp.toPx(),
                center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 9.dp.toPx())
            )
            // testing sweep arc
            if (isTesting) {
                drawArc(
                    color = ringColor,
                    startAngle = spin,
                    sweepAngle = 92f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        c.x - 82.dp.toPx(), c.y - 82.dp.toPx()
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        82.dp.toPx() * 2, 82.dp.toPx() * 2
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
            } else {
                drawCircle(
                    color = ringColor.copy(alpha = if (isRunning) 0.9f else 0.75f),
                    radius = 82.dp.toPx(),
                    center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                )
            }
        }

        // Gooey blob layer.
        Canvas(
            modifier = Modifier
                .size(196.dp)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    renderEffect = gooeyEffect
                }
        ) {
            val c = center
            val baseRadius = 56.dp.toPx() * pressScale
            val pulseR = if (isRunning) 2.2.dp.toPx() * sin(pulseT) else 0f

            drawCircle(color = blobColor, radius = baseRadius + pulseR, center = c)

            if (burst > 0.01f) {
                val travel = 62.dp.toPx() * burst
                val sRadius = 15.dp.toPx()
                for (angleDeg in listOf(200f, 340f)) {
                    val angle = Math.toRadians(
                        (angleDeg + 8f * sin(pulseT + angleDeg)).toDouble()
                    )
                    drawCircle(
                        color = if (isRunning) satelliteColor else blobColor,
                        radius = sRadius * (1f - 0.3f * burst),
                        center = androidx.compose.ui.geometry.Offset(
                            c.x + travel * cos(angle).toFloat(),
                            c.y + travel * sin(angle).toFloat()
                        )
                    )
                }
            }
        }

        // Power icon (clickable).
        Box(
            modifier = Modifier
                .size(112.dp)
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
            Icon(
                painter = painterResource(R.drawable.ic_zero_power_24dp),
                contentDescription = stringResource(
                    if (isRunning) R.string.zero_disconnect else R.string.zero_connect
                ),
                tint = if (isRunning) Color(0xFF041224) else Color(0xFFB9C9E2),
                modifier = Modifier.size(58.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bolt test button.
// ---------------------------------------------------------------------------
@Composable
private fun BoltTestButton(
    isTesting: Boolean,
    connected: Boolean,
    onClick: () -> Unit
) {
    val hc = zeroHomeColors()
    Box(
        modifier = Modifier
            .size(46.dp)
            .shadow(8.dp, CircleShape, spotColor = colorZeroNeon.copy(alpha = 0.5f))
            .background(
                if (isTesting) Color(0xFF3A2E05) else hc.boltBg,
                CircleShape
            )
            .border(1.dp, colorZeroNeon.copy(alpha = 0.45f), CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_flash_on_24dp),
            contentDescription = stringResource(
                if (connected) R.string.zero_test_ping else R.string.zero_test_all
            ),
            tint = if (isTesting) colorZeroTesting else colorZeroNeonSoft,
            modifier = Modifier.size(22.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Stat pill (value + label).
// ---------------------------------------------------------------------------
@Composable
private fun ZeroStatPill(
    value: String,
    label: String,
    hc: ZeroHomeColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(hc.pillBg, RoundedCornerShape(14.dp))
            .border(1.dp, hc.cardBorder, RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = value,
            color = hc.textPrimary,
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
// Current server card.
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(18.dp), spotColor = Color(0x6600A8F5))
            .background(hc.cardBg, RoundedCornerShape(18.dp))
            .border(1.dp, hc.cardBorder, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
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
                color = if (it < 500) colorZeroPingGood
                else if (it < 1500) Color(0xFFFBBF24) else colorZeroFailure,
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
            .background(
                Color(0xFF12233E),
                RoundedCornerShape(14.dp)
            )
            .border(1.dp, Color(0x3D35C6FF), RoundedCornerShape(14.dp))
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
                tint = Color(0xFF8296B4),
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
