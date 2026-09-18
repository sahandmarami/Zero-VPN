package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.colorNeonCyan

/** Bottom destinations: home / locations / settings — no account section. */
enum class ZeroBottomTab { HOME, LOCATIONS, SETTINGS }

/**
 * Zero VPN bottom navigation modeled on the reference design:
 * the active item sits in a neon pill, inactive items are quiet icons.
 */
@Composable
fun ZeroBottomNav(
    selectedTab: ZeroBottomTab,
    onSelectTab: (ZeroBottomTab) -> Unit,
    onOpenSettings: () -> Unit,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val barBackground = if (isDarkTheme) Color(0xD90A1424) else Color(0xF2FFFFFF)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(barBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(66.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ZeroNavItem(
                iconRes = R.drawable.ic_zero_home_24dp,
                label = stringResource(R.string.zero_tab_home),
                selected = selectedTab == ZeroBottomTab.HOME,
                isDarkTheme = isDarkTheme,
                onClick = { onSelectTab(ZeroBottomTab.HOME) }
            )
            ZeroNavItem(
                iconRes = R.drawable.ic_zero_locations_24dp,
                label = stringResource(R.string.zero_tab_locations),
                selected = selectedTab == ZeroBottomTab.LOCATIONS,
                isDarkTheme = isDarkTheme,
                onClick = { onSelectTab(ZeroBottomTab.LOCATIONS) }
            )
            ZeroNavItem(
                iconRes = R.drawable.ic_settings_24dp,
                label = stringResource(R.string.zero_tab_settings),
                selected = false, // opens the settings screen; no tab state
                isDarkTheme = isDarkTheme,
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun ZeroNavItem(
    iconRes: Int,
    label: String,
    selected: Boolean,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pillColor by animateColorAsState(
        targetValue = if (selected) colorNeonCyan.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = spring(stiffness = 500f),
        label = "pillColor"
    )
    val contentColor = if (selected) colorNeonCyan
    else if (isDarkTheme) Color(0xFF7C8CA6) else Color(0xFF5A6B85)

    Column(
        modifier = Modifier
            .background(pillColor, RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
