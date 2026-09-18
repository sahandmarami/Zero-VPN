package com.v2ray.ang.ui.compose

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Zero VPN liquid-gooey engine — native Compose port of the `liquid-gooey`
 * recipe (libraries.dev/gooey.html):
 *
 *   <Liquid blur={6} contrast={18} fill="...">
 *     <Liquid.Item x={...} y={...} transition="bouncy" />
 *
 * Blurring the layer then re-sharpening the alpha channel with a color matrix
 * makes touching shapes melt into each other (metaball neck), exactly like the
 * SVG filter goo trick, while icons drawn on a crisp layer above stay sharp.
 *
 * Returns null below Android 12 (S) — callers must degrade gracefully by
 * drawing plain shapes (ring + droplet still look right, just no melt neck).
 */
@Composable
fun rememberGooeyEffect(
    blurDp: Float = 6f,
    contrast: Float = 18f,
    threshold: Float = 0.40f,
): RenderEffect? {
    val density = LocalDensity.current
    return remember(blurDp, contrast, threshold, density.density, density.fontScale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val radiusPx = with(density) { blurDp.dp.toPx() }
                val blur = android.graphics.RenderEffect.createBlurEffect(
                    radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP
                )
                // alpha' = contrast * alpha - contrast * threshold
                val bias = -contrast * threshold
                val matrix = ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, 0f,
                        0f, 0f, 0f, contrast, bias
                    )
                )
                android.graphics.RenderEffect.createColorFilterEffect(
                    ColorMatrixColorFilter(matrix), blur
                ).asComposeRenderEffect()
            } catch (_: Exception) {
                null
            }
        } else null
    }
}

/**
 * Bouncy spring used by every Liquid.Item transition — the Compose twin of
 * 550 ms cubic-bezier(0.34, 1.56, 0.64, 1): fast approach, jelly overshoot.
 */
val LiquidBouncySpring: androidx.compose.animation.core.AnimationSpec<Float> =
    androidx.compose.animation.core.spring(
        dampingRatio = 0.45f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
    )