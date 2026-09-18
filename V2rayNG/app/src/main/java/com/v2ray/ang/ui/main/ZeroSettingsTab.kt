package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.CoreUpdateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.ui.compose.LocalDarkTheme

/**
 * Zero VPN settings — a lean, in-place bottom-nav tab (same feel as Home and
 * Locations). Only the essentials are kept here: language, theme, connection
 * mode, per-app proxy, routing and the auto-update switch. Deep technical
 * preferences stay out of sight (the drawer still offers the full panel).
 */
@Composable
fun ZeroSettingsTab(
    isRunning: Boolean,
    onNavigate: (MainDestination) -> Unit,
    onModeChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = LocalDarkTheme.current

    var language by remember {
        mutableStateOf(
            MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE, "auto") ?: "auto"
        )
    }
    var uiModeNight by rememberMmkvString(AppConfig.PREF_UI_MODE_NIGHT, "0")
    var mode by rememberMmkvString(AppConfig.PREF_MODE, VPN)
    var autoUpdate by rememberMmkvBool(AppConfig.PREF_AUTO_UPDATE, true)

    val languageEntries = stringArrayResource(R.array.language_select).toList()
    val languageValues = stringArrayResource(R.array.language_select_value).toList()
    val uiModeNightEntries = stringArrayResource(R.array.ui_mode_night).toList()
    val uiModeNightValues = stringArrayResource(R.array.ui_mode_night_value).toList()
    val modeEntries = stringArrayResource(R.array.mode_entries).toList()
    val modeValues = stringArrayResource(R.array.mode_value).toList()

    // Embedded Xray core version for the status row.
    val coreVersion = remember {
        CoreUpdateManager.parseCoreVersion(CoreNativeManager.getLibVersion())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(6.dp))

        ZeroSettingsGroup(stringResource(R.string.zero_set_personalize)) {
            SettingsListItem(
                title = stringResource(R.string.title_language),
                entries = languageEntries,
                values = languageValues,
                selectedValue = language,
                onSelected = {
                    language = it
                    AppLocaleManager.setApplicationLanguage(it)
                }
            )
            SettingsListItem(
                title = stringResource(R.string.title_pref_ui_mode_night),
                entries = uiModeNightEntries,
                values = uiModeNightValues,
                selectedValue = uiModeNight,
                onSelected = {
                    uiModeNight = it
                    ThemeManager.setThemeMode(it)
                }
            )
        }

        ZeroSettingsGroup(stringResource(R.string.zero_set_connection)) {
            SettingsListItem(
                title = stringResource(R.string.title_mode),
                entries = modeEntries,
                values = modeValues,
                selectedValue = mode,
                onSelected = {
                    mode = it
                    if (isRunning) onModeChanged()
                }
            )
            SettingsMenuItem(
                title = stringResource(R.string.per_app_proxy_settings),
                subtitle = stringResource(R.string.zero_set_per_app_hint),
                onClick = { onNavigate(MainDestination.PerAppProxy) }
            )
            SettingsMenuItem(
                title = stringResource(R.string.routing_settings_title),
                subtitle = stringResource(R.string.zero_set_routing_hint),
                onClick = { onNavigate(MainDestination.Routing) }
            )
        }

        ZeroSettingsGroup(stringResource(R.string.zero_set_update)) {
            SettingsSwitchItem(
                title = stringResource(R.string.zero_auto_update),
                summary = stringResource(R.string.zero_auto_update_summary),
                checked = autoUpdate,
                onCheckedChange = { autoUpdate = it }
            )
            SettingsMenuItem(
                title = stringResource(R.string.update_check_for_update),
                onClick = { onNavigate(MainDestination.CheckUpdate) }
            )
            SettingsMenuItem(
                title = stringResource(R.string.zero_core_version),
                subtitle = coreVersion?.let {
                    stringResource(R.string.zero_core_version_value, it)
                } ?: stringResource(R.string.zero_core_version_unknown),
                onClick = { onNavigate(MainDestination.CheckUpdate) }
            )
        }

        ZeroSettingsGroup(stringResource(R.string.zero_set_more)) {
            SettingsMenuItem(
                title = stringResource(R.string.title_sub_setting),
                onClick = { onNavigate(MainDestination.Subscriptions) }
            )
            SettingsMenuItem(
                title = stringResource(R.string.title_configuration_backup_restore),
                onClick = { onNavigate(MainDestination.BackupRestore) }
            )
            SettingsMenuItem(
                title = stringResource(R.string.title_about),
                onClick = { onNavigate(MainDestination.About) }
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** One rounded group card with a small caption above the items. */
@Composable
private fun ZeroSettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    val isDarkTheme = LocalDarkTheme.current
    val cardBg = if (isDarkTheme) androidx.compose.ui.graphics.Color(0xFF12171F)
    else androidx.compose.ui.graphics.Color(0xFFFFFFFF)

    Text(
        text = title,
        color = if (isDarkTheme) androidx.compose.ui.graphics.Color(0xFF7C8CA6)
        else androidx.compose.ui.graphics.Color(0xFF5A6B85),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardBg)
    ) {
        content()
    }
}

/** Top bar shown when the Settings tab is active. */
@Composable
fun ZeroSettingsTopBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(58.dp)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.title_settings),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
    }
}
