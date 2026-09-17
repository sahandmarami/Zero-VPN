package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.CoreUpdateManager
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
    delayMillis < 1500 -> Color(0xFFF59E0B) // amber
    else -> Color(0xFFF97316) // orange
}

/**
 * Zero VPN status card pinned to the top of the config section:
 * a prominent connect button plus the live real-ping and exit country.
 */
@Composable
fun ZeroStatusCard(
    isRunning: Boolean,
    status: MainStatus,
    selectedServerName: String,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (statusLabel, dotColor) = when (status) {
        MainStatus.Disconnected -> stringResource(R.string.connection_not_connected) to
            MaterialTheme.colorScheme.outline
        MainStatus.Connected -> stringResource(R.string.connection_connected) to
            colorPing
        MainStatus.Testing, is MainStatus.TestProgress -> stringResource(R.string.zero_testing) to
            Color(0xFFF59E0B)
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(20.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onTest,
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 6.dp
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flash_on_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.zero_test_ping), style = MaterialTheme.typography.labelMedium)
            }
        }

        // Hero row: country + real ping.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (countryFlag != null) {
                Text(text = countryFlag, fontSize = 34.sp)
                Spacer(Modifier.width(10.dp))
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
                        text = stringResource(R.string.zero_test_ping),
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

        // Connect / disconnect button at the top of the config section.
        Button(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primary,
                contentColor = if (isRunning) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = stringResource(
                    if (isRunning) R.string.zero_disconnect else R.string.zero_connect
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
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
