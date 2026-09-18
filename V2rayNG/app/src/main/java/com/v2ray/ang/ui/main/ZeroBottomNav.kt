package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.rememberGooeyEffect

/** Bottom destinations: home / locations / settings — no account section. */
enum class ZeroBottomTab { HOME, LOCATIONS, SETTINGS }

private val NAV_BLOB_HEIGHT = 50.dp
private val NAV_BAR_HEIGHT = 72.dp

/**
 * Zero VPN bottom navigation modeled on the reference shot:
 * the active item sits on a solid neon blob that slides between tabs as a
 * liquid — two gooey blobs (blur 6 / contrast 18) stretch and melt into each
 * other while the icons stay crisp on a layer above.
 */
@Composable
fun ZeroBottomNav(
    selectedTab: ZeroBottomTab,
    onSelectTab: (ZeroBottomTab) -> Unit,
    onOpenSettings: () -> Unit,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val barBackground = if (isDarkTheme) Color(0xE60B1018) else Color(0xF2FFFFFF)
    val inactiveColor = if (isDarkTheme) Color(0xFF7C8CA6) else Color(0xFF5A6B85)
    val activeIndex = when (selectedTab) {
        ZeroBottomTab.HOME -> 0
        ZeroBottomTab.LOCATIONS -> 1
        ZeroBottomTab.SETTINGS -> 0
    }
    val gooEffect = rememberGooeyEffect(blurDp = 6f, contrast = 18f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(barBackground)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(NAV_BAR_HEIGHT)
        ) {
            val itemWidth: Dp = maxWidth / 3
            val blobWidth = itemWidth - 30.dp
            val blobTargetX = itemWidth * activeIndex + (itemWidth - blobWidth) / 2

            // Lead blob — bouncy spring (550 ms cubic-bezier(0.34,1.56,0.64,1)).
            val leadX by animateDpAsState(
                targetValue = blobTargetX,
                animationSpec = spring(
                    dampingRatio = 0.55f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "leadBlobX"
            )
            // Trail blob — softer spring, lags behind so the two merge and
            // stretch while travelling (liquid melt between tabs).
            val trailX by animateDpAsState(
                targetValue = blobTargetX,
                animationSpec = spring(
                    dampingRatio = 0.75f,
                    stiffness = Spring.StiffnessLow
                ),
                label = "trailBlobX"
            )

            // --- Gooey blob layer (blurred + alpha-thresholded) --------------
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        renderEffect = gooEffect
                    }
            ) {
                NavBlob(
                    offsetX = trailX,
                    blobWidth = blobWidth,
                    alpha = 0.85f
                )
                NavBlob(
                    offsetX = leadX,
                    blobWidth = blobWidth,
                    alpha = 1f
                )
            }

            // --- Crisp content layer ------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZeroNavItem(
                    iconRes = R.drawable.ic_zero_home_24dp,
                    label = stringResource(R.string.zero_tab_home),
                    selected = selectedTab == ZeroBottomTab.HOME,
                    inactiveColor = inactiveColor,
                    onClick = { onSelectTab(ZeroBottomTab.HOME) },
                    modifier = Modifier.weight(1f)
                )
                ZeroNavItem(
                    iconRes = R.drawable.ic_zero_locations_24dp,
                    label = stringResource(R.string.zero_tab_locations),
                    selected = selectedTab == ZeroBottomTab.LOCATIONS,
                    inactiveColor = inactiveColor,
                    onClick = { onSelectTab(ZeroBottomTab.LOCATIONS) },
                    modifier = Modifier.weight(1f)
                )
                ZeroNavItem(
                    iconRes = R.drawable.ic_settings_24dp,
                    label = stringResource(R.string.zero_tab_settings),
                    selected = false, // opens the settings screen; no tab state
                    inactiveColor = inactiveColor,
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** One gooey liquid blob — neon-blue gradient (پررنگ → کم‌رنگی). */
@Composable
private fun NavBlob(offsetX: Dp, blobWidth: Dp, alpha: Float) {
    Box(
        modifier = Modifier
            .offset(x = offsetX, y = (NAV_BAR_HEIGHT - NAV_BLOB_HEIGHT) / 2)
            .width(blobWidth)
            .height(NAV_BLOB_HEIGHT)
            .alphaIf(alpha)
            .background(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF35C6FF), Color(0xFF0084D4))
                ),
                shape = RoundedCornerShape(25.dp)
            )
    )
}

private fun Modifier.alphaIf(alpha: Float): Modifier =
    if (alpha >= 1f) this else this.then(Modifier.graphicsLayer { this.alpha = alpha })

@Composable
private fun ZeroNavItem(
    iconRes: Int,
    label: String,
    selected: Boolean,
    inactiveColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val contentColor = if (selected) Color.White else inactiveColor

    Column(
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
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
