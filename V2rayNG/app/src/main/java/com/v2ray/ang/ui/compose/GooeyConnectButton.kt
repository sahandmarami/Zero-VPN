package com.v2ray.ang.ui.compose

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Zero VPN liquid-gooey connect button.
 *
 * Re-creates the `liquid-gooey` metaball effect natively: the button and its
 * satellite blobs are drawn blurred and thresholded inside an offscreen layer,
 * so they merge and split like liquid. Motion follows an overshooting spring,
 * equivalent to cubic-bezier(0.34, 1.56, 0.64, 1) over ~550 ms.
 */
@Composable
fun GooeyConnectButton(
    isRunning: Boolean,
    isDarkTheme: Boolean,
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = if (isRunning) colorFabActive
    else if (isDarkTheme) colorFabInactiveDark
    else colorFabInactiveLight,
    contentColor: Color = Color.White
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Press squish (liquid deform).
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale"
    )

    // Burst progress: 0 = blobs merged inside, 1 = blobs flung out.
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

    val blobColor = containerColor
    val satelliteColor = MaterialTheme.colorScheme.secondary

    val gooeyEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // Blur the blobs, then threshold the alpha -> liquid metaball merge.
                val blur = RenderEffect.createBlurEffect(14f, 14f, Shader.TileMode.CLAMP)
                val cm = android.graphics.ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, 0f,
                        0f, 0f, 0f, 30f, -640f
                    )
                )
                val threshold = android.graphics.ColorMatrixColorFilter(cm)
                RenderEffect.createColorFilterEffect(threshold, blur).asComposeRenderEffect()
            } catch (_: Exception) {
                null
            }
        } else null
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Gooey blob layer.
        Canvas(
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    renderEffect = gooeyEffect
                }
        ) {
            val c = center
            val baseRadius = 30.dp.toPx() * pressScale
            val pulseR = if (isRunning) 1.2.dp.toPx() * sin(pulseT) else 0f

            // Main blob.
            drawCircle(color = blobColor, radius = baseRadius + pulseR, center = c)

            // Satellite blobs — merge on idle, fling out when running.
            if (burst > 0.01f) {
                val travel = 26.dp.toPx() * burst
                val sRadius = 9.dp.toPx()
                for (angleDeg in listOf(200f, 340f)) {
                    val angle = Math.toRadians(
                        (angleDeg + 8f * sin(pulseT + angleDeg)).toDouble()
                    )
                    val offset = Offset(
                        x = c.x + travel * cos(angle).toFloat(),
                        y = c.y + travel * sin(angle).toFloat()
                    )
                    drawCircle(
                        color = if (isRunning) satelliteColor else blobColor,
                        radius = sRadius * (1f - 0.25f * burst),
                        center = offset
                    )
                }
            }
        }

        // Foreground icon.
        Box(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
