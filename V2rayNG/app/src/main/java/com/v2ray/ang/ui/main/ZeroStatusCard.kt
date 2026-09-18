package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.CoreUpdateManager
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.colorDisconnectOrange
import com.v2ray.ang.ui.compose.colorDisconnectRed
import com.v2ray.ang.ui.compose.colorNeonBlue
import com.v2ray.ang.ui.compose.colorNeonCyan
import com.v2ray.ang.ui.compose.colorPing
import com.v2ray.ang.ui.compose.colorPingRed

/** Converts an ISO-3166 alpha-2 code to its regional-indicator flag emoji. */
private fun countryCodeToFlagEmoji(code: String?): String? {
    val c = code?.trim()?.uppercase() ?: return null
    if (!Regex("^[A-Z]{2}$").matches(c)) return null
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(c[0].code + base)) +
        String(Character.toChars(c[1].code + base))
}

private fun pingColor(delayMillis: Long): Color = when {
    delayMillis < 0 -> colorPingRed
    delayMillis < 500 -> colorPing
    delayMillis < 1500 -> Color(0xFFFBBF24) // amber
    else -> Color(0xFFFB923C) // orange
}

/**
 * Zero VPN status card pinned to the top of the config section:
 * a neon glass hero card with the connect button, live real-ping and exit country.
 */
@Composable
fun ZeroStatusCard(
    isRunning: Boolean,
    status: MainStatus,
    selectedServerName: String,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onTestAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = LocalDarkTheme.current
    val (statusLabel, dotColor) = when (status) {
        MainStatus.Disconnected -> stringResource(R.string.connection_not_connected) to
            MaterialTheme.colorScheme.outline
        MainStatus.Connected -> stringResource(R.string.connection_connected) to
            colorPing
        MainStatus.Testing, is MainStatus.TestProgress -> stringResource(R.string.zero_testing) to
            Color(0xFFFBBF24)
        is MainStatus.ConnectionTest ->
            if (status.result.delayMillis >= 0) stringResource(R.string.connection_connected) to colorPing
            else stringResource(R.string.connection_not_connected) to colorPingRed
    }

    // Real ping + country data (filled after a real-ping test of the current server).
    var pingText: String? = null
    var countryLabel: String? = null
    var countryFlag: String? = null
    var ipText: String? = null
    var pingOk = false
    if (status is MainStatus.ConnectionTest) {
        val result = status.result
        pingOk = result.delayMillis >= 0
        if (pingOk) {
            pingText = stringResource(R.string.server_test_delay_value, result.delayMillis)
        }
        countryFlag = countryCodeToFlagEmoji(result.country)
        countryLabel = result.country
        ipText = result.ipAddress
    }

    // Neon glass surface: translucent navy card with a glowing neon border.
    val cardShape = RoundedCornerShape(24.dp)
    val cardBackground = if (isDarkTheme) {
        Brush.verticalGradient(listOf(Color(0xB30E1526), Color(0x9915223B)))
    } else {
        Brush.verticalGradient(listOf(Color(0xF2FFFFFF), Color(0xE6FFFFFF)))
    }
    val cardBorder = if (isDarkTheme) {
        Brush.horizontalGradient(
            listOf(
                colorNeonCyan.copy(alpha = 0.55f),
                colorNeonBlue.copy(alpha = 0.30f),
                colorNeonCyan.copy(alpha = 0.55f)
            )
        )
    } else {
        Brush.horizontalGradient(
            listOf(
                Color(0xFF00A5D4).copy(alpha = 0.35f),
                Color(0xFF2979FF).copy(alpha = 0.20f),
                Color(0xFF00A5D4).copy(alpha = 0.35f)
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(
                elevation = 18.dp,
                shape = cardShape,
                ambientColor = colorNeonCyan.copy(alpha = 0.35f),
                spotColor = colorNeonBlue.copy(alpha = 0.35f)
            )
            .background(cardBackground, cardShape)
            .border(1.dp, cardBorder, cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Brand + status row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.zero_logo),
                contentDescription = null,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Hero row: country + real ping at the top.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (countryFlag != null) {
                Text(text = countryFlag, fontSize = 36.sp)
                Spacer(Modifier.width(12.dp))
            } else {
                Text(text = "🌐", fontSize = 30.sp)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                if (pingText != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = pingText,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = pingColor(
                                (status as? MainStatus.ConnectionTest)?.result?.delayMillis ?: -1L
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        if (countryLabel != null) {
                            Text(
                                text = countryLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.zero_ping_hint),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ipText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selectedServerName.isNotBlank()) {
                    Text(
                        text = selectedServerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Hero connect / disconnect button with a neon gradient.
        val connectShape = RoundedCornerShape(18.dp)
        val connectBrush = if (isRunning) {
            Brush.horizontalGradient(listOf(colorDisconnectRed, colorDisconnectOrange))
        } else {
            Brush.horizontalGradient(listOf(colorNeonCyan, colorNeonBlue))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (isRunning) 10.dp else 16.dp,
                    shape = connectShape,
                    ambientColor = if (isRunning) colorDisconnectRed else colorNeonCyan,
                    spotColor = if (isRunning) colorDisconnectRed else colorNeonBlue
                )
                .background(connectBrush, connectShape)
                .clip(connectShape)
                .clickable(onClick = onToggle)
                .height(54.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(
                        if (isRunning) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp
                    ),
                    contentDescription = null,
                    tint = Color(0xFF04121F),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (isRunning) R.string.zero_disconnect else R.string.zero_connect
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF04121F)
                )
            }
        }

        // Ping test actions: current server + all configs (real ping).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onTest,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp, vertical = 8.dp
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flash_on_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.zero_test_ping),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = onTestAll,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp, vertical = 8.dp
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qu_start_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.zero_test_all),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Non-intrusive banners shown on top of the config list when a newer app
 * build or a newer official Xray core is available (checked on every launch).
 */
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
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(14.dp)
            )
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onDismiss) {
            Icon(
                painter = painterResource(R.drawable.ic_zero_close_24dp),
                contentDescription = stringResource(R.string.zero_dismiss),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
