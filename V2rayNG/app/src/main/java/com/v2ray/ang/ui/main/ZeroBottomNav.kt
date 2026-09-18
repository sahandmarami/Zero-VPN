package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R

/** Bottom destinations: home / locations / settings — no account section. */
enum class ZeroBottomTab { HOME, LOCATIONS, SETTINGS }

private val NAV_BLOB_HEIGHT = 60.dp
private val NAV_BAR_HEIGHT = 78.dp

// Neon blue gradient shared by the capsules and the liquid neck so the
// silhouettes blend seamlessly (same colors over the same Y span).
private val BLOB_GRADIENT_TOP = Color(0xFF35C6FF)
private val BLOB_GRADIENT_BOTTOM = Color(0xFF0084D4)

/**
 * Zero VPN bottom navigation — premium liquid-goo bar.
 *
 * The old build rendered the goo through a blur + alpha-threshold [RenderEffect];
 * the threshold amplified 8-bit quantization into staircase edges ("pixelated"
 * look) and banding. This build replaces it with a fully vector metaball:
 * two anti-aliased capsules plus a tangent-continuous cubic-bezier neck drawn
 * on a hardware-accelerated Canvas. Every edge is GPU anti-aliased at native
 * screen resolution — crisp on any density, cheaper per frame, and the melt
 * itself looks cleaner because the silhouette is exact geometry, not a filter.
 */
@Composable
fun ZeroBottomNav(
    selectedTab: ZeroBottomTab,
    onSelectTab: (ZeroBottomTab) -> Unit,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val barBackground = if (isDarkTheme) Color(0xE60B1018) else Color(0xF2FFFFFF)
    val inactiveColor = if (isDarkTheme) Color(0xFF7C8CA6) else Color(0xFF5A6B85)
    val activeIndex = when (selectedTab) {
        ZeroBottomTab.HOME -> 0
        ZeroBottomTab.LOCATIONS -> 1
        ZeroBottomTab.SETTINGS -> 2
    }

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

            // Lead blob — bouncy spring (fast approach, jelly overshoot).
            val leadX by animateDpAsState(
                targetValue = blobTargetX,
                animationSpec = spring(
                    dampingRatio = 0.55f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "leadBlobX"
            )
            // Trail blob — softer spring, lags behind so the two separate,
            // stretch and melt back together while travelling between tabs.
            val trailX by animateDpAsState(
                targetValue = blobTargetX,
                animationSpec = spring(
                    dampingRatio = 0.75f,
                    stiffness = Spring.StiffnessLow
                ),
                label = "trailBlobX"
            )

            // --- Liquid neck (vector metaball bridge, behind capsules) -----
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawGooNeck(
                    leadX = leadX.toPx(),
                    trailX = trailX.toPx(),
                    blobWidth = blobWidth.toPx(),
                    barHeightPx = NAV_BAR_HEIGHT.toPx(),
                    blobHeightPx = NAV_BLOB_HEIGHT.toPx()
                )
            }

            // --- Capsule blobs (opaque, hardware anti-aliased) --------------
            NavBlob(offsetX = trailX, blobWidth = blobWidth)
            NavBlob(offsetX = leadX, blobWidth = blobWidth)

            // --- Crisp content layer ------------------------------------------
            // The content row occupies the exact vertical span of the capsules
            // (same offset, same height), so icons + labels sit geometrically
            // centered inside the blue goo (user feedback).
            Row(
                modifier = Modifier
                    .offset(y = (NAV_BAR_HEIGHT - NAV_BLOB_HEIGHT) / 2)
                    .height(NAV_BLOB_HEIGHT)
                    .fillMaxWidth()
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
                    selected = selectedTab == ZeroBottomTab.SETTINGS,
                    inactiveColor = inactiveColor,
                    onClick = { onSelectTab(ZeroBottomTab.SETTINGS) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** One gooey liquid capsule — neon-blue gradient (پررنگ → کم‌رنگی). */
@Composable
private fun NavBlob(offsetX: Dp, blobWidth: Dp) {
    Box(
        modifier = Modifier
            .offset(
                x = offsetX,
                y = (NAV_BAR_HEIGHT - NAV_BLOB_HEIGHT) / 2
            )
            .width(blobWidth)
            .height(NAV_BLOB_HEIGHT)
            .background(
                brush = Brush.verticalGradient(
                    listOf(BLOB_GRADIENT_TOP, BLOB_GRADIENT_BOTTOM)
                ),
                shape = RoundedCornerShape(NAV_BLOB_HEIGHT / 2)
            )
    )
}

// ---------------------------------------------------------------------------
// Vector metaball neck — the actual "liquid" between the two capsules.
//
// Geometry: each capsule end is a semicircular cap of radius r. The neck is a
// closed path between the two facing caps, built from two cubic beziers whose
// control points start exactly on the caps' tangent lines, so the bridge flows
// out of the blobs with G1 (tangent) continuity — no crease at the joints.
//
// As the edge gap g grows (0 → gMax):
//   - the attach angle θ slides from the cap poles (θ=90°, fat seamless melt)
//     toward the facing poles (θ=30°, stretched thin waist),
//   - the handle length h keeps the bezier waist pinched toward the center,
//   - alpha fades out just before the bridge snaps (2-tab jumps).
// ---------------------------------------------------------------------------

private const val NECK_MAX_GAP_R = 1.35f   // max gap (in cap radii) with a live neck
private const val NECK_THETA_END = (Math.PI / 6).toFloat()  // 30° attach angle when stretched
private const val NECK_HANDLE_START = 0.55f // bezier handle (× r) when merged
private const val NECK_HANDLE_END = 0.68f   // bezier handle (× r) when stretched

private fun DrawScope.drawGooNeck(
    leadX: Float,
    trailX: Float,
    blobWidth: Float,
    barHeightPx: Float,
    blobHeightPx: Float
) {
    val r = blobHeightPx / 2f
    val cy = barHeightPx / 2f
    val blobTop = cy - r

    val leftBlobX = minOf(leadX, trailX)
    val rightBlobX = maxOf(leadX, trailX)
    // Facing cap-circle centers (capsule corners are exact semicircles).
    val cl = leftBlobX + blobWidth - r
    val cr = rightBlobX + r
    val gap = (cr - cl) - 2f * r

    // Nothing to bridge while merged or fully separated.
    if (gap < 0.5f || gap >= NECK_MAX_GAP_R * r) return

    val t = (gap / (NECK_MAX_GAP_R * r)).coerceIn(0f, 1f)
    val theta = lerpF(Math.PI.toFloat() / 2f, NECK_THETA_END, t)
    val handle = lerpF(NECK_HANDLE_START, NECK_HANDLE_END, t) * r
    val sinT = kotlin.math.sin(theta)
    val cosT = kotlin.math.cos(theta)

    // Attach points on the two cap circles (upper + lower, mirrored on cy).
    val axL = cl + r * cosT
    val axR = cr - r * cosT
    val ayUp = cy - r * sinT
    val ayDown = cy + r * sinT

    val p1t = Offset(axL, ayUp)     // left cap, upper attach
    val p2t = Offset(axR, ayUp)     // right cap, upper attach
    val p1 = Offset(axL, ayDown)    // left cap, lower attach
    val p2 = Offset(axR, ayDown)    // right cap, lower attach

    // Control points: tangent to the caps, pulling the waist toward the
    // center line — this creates the liquid pinch.
    val cp1t = Offset(p1t.x + handle * sinT, p1t.y + handle * cosT)
    val cp2t = Offset(p2t.x - handle * sinT, p2t.y + handle * cosT)
    val cp1 = Offset(p1.x + handle * sinT, p1.y - handle * cosT)
    val cp2 = Offset(p2.x - handle * sinT, p2.y - handle * cosT)

    // Fade the bridge out just before it snaps so large jumps never blink.
    val alpha = 1f - smoothstep(0.88f, 1f, t)

    val path = Path()
    path.moveTo(p1t.x, p1t.y)
    path.cubicTo(cp1t.x, cp1t.y, cp2t.x, cp2t.y, p2t.x, p2t.y)
    path.arcTo(
        rect = Rect(left = cr - r, top = cy - r, right = cr + r, bottom = cy + r),
        startAngleDegrees = 180f + Math.toDegrees(theta.toDouble()).toFloat(),
        sweepAngleDegrees = -2f * Math.toDegrees(theta.toDouble()).toFloat(),
        forceMoveTo = false
    )
    path.cubicTo(cp2.x, cp2.y, cp1.x, cp1.y, p1.x, p1.y)
    path.arcTo(
        rect = Rect(left = cl - r, top = cy - r, right = cl + r, bottom = cy + r),
        startAngleDegrees = Math.toDegrees(theta.toDouble()).toFloat(),
        sweepAngleDegrees = -2f * Math.toDegrees(theta.toDouble()).toFloat(),
        forceMoveTo = false
    )
    path.close()

    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(BLOB_GRADIENT_TOP, BLOB_GRADIENT_BOTTOM),
            startY = blobTop,
            endY = blobTop + blobHeightPx
        ),
        alpha = alpha
    )
}

private fun lerpF(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val v = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

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
    // Colors glide between tabs instead of snapping — matches the blob travel.
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.White else inactiveColor,
        animationSpec = tween(durationMillis = 220),
        label = "navItemColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "navItemScale"
    )

    Column(
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
