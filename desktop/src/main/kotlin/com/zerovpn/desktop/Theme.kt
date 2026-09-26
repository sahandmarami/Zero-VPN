package com.zerovpn.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind

/**
 * Zero VPN desktop theme — mirrors the Android app's light/dark home palettes
 * (ZeroHomeScreen.kt light branch + MainScreen background glows).
 *
 * The desktop app previously shipped a hardcoded dark look; the phone app the
 * user compares against runs the pale-azure LIGHT theme, so light is now the
 * default and the choice persists in the settings file.
 */
object ThemeState {
    /** true = dark theme, false = light theme (default, like the phone). */
    var dark by mutableStateOf(false)

    fun apply(darkTheme: Boolean) {
        dark = darkTheme
    }
}

/**
 * Pale background wash — picked ONCE per app launch at random from azure /
 * mint / rose (user request: every time the app opens, a random pale blue,
 * red or green background tint appears). All three stay extremely pale so
 * the dark text keeps full contrast; the neon-blue accent, cards and pills
 * are untouched so the brand look survives on every wash.
 */
data class BgWash(val base: Color, val glowTop: Color, val glowLeft: Color, val glowBottom: Color)

private val WASH_AZURE = BgWash(
    base = Color(0xFFF4F8FE),
    glowTop = Color(0xFF35C6FF),
    glowLeft = Color(0xFF4D8DFF),
    glowBottom = Color(0xFF5AA8FF),
)

private val WASH_MINT = BgWash(
    base = Color(0xFFF2FBF6),
    glowTop = Color(0xFF3BD9A4),
    glowLeft = Color(0xFF2FBF8F),
    glowBottom = Color(0xFF43CFA0),
)

private val WASH_ROSE = BgWash(
    base = Color(0xFFFEF3F5),
    glowTop = Color(0xFFFF7A94),
    glowLeft = Color(0xFFFF8E7C),
    glowBottom = Color(0xFFFF9DA6),
)

/** Random pale wash for this launch — evaluated once per process. */
val launchWash: BgWash by lazy {
    listOf(WASH_AZURE, WASH_MINT, WASH_ROSE).random()
}

data class ZeroThemeColors(
    val bgBase: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val pillBg: Color,
    val accent: Color,
    val pingGood: Color,
    val pingMid: Color,
    val pingBad: Color,
    val navBarBg: Color,
    val navInactive: Color,
    val bannerBg: Color,
    val scrim: Color,
    val fieldText: Color,
    val switchOffTrack: Color,
)

private val light = ZeroThemeColors(
    bgBase = Color(0xFFF4F8FE),
    textPrimary = Color(0xFF0B1B33),
    textSecondary = Color(0xFF54678A),
    cardBg = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFD9E4F6),
    pillBg = Color(0xFFEBF2FD),
    accent = Color(0xFF0084D4),
    pingGood = Color(0xFF0091D6),
    pingMid = Color(0xFFDD8A00),
    pingBad = Color(0xFFE23A5F),
    navBarBg = Color(0xE6FFFFFF),
    navInactive = Color(0xFF8296B8),
    bannerBg = Color(0xF0FFFFFF),
    scrim = Color.Black.copy(alpha = 0.35f),
    fieldText = Color(0xFF0B1B33),
    switchOffTrack = Color(0xFFC6D6F0),
)

private val dark = ZeroThemeColors(
    bgBase = Color(0xFF070B11),
    textPrimary = Color.White,
    textSecondary = Color(0xFF7C8CA6),
    cardBg = Color(0xFF141A24),
    cardBorder = Color(0xFF212C3C),
    pillBg = Color(0xFF12171F),
    accent = colorZeroNeonSoft,
    pingGood = colorZeroNeonSoft,
    pingMid = Color(0xFFFFB020),
    pingBad = colorZeroFailure,
    navBarBg = Color(0xE60B1018),
    navInactive = Color(0xFF7C8CA6),
    bannerBg = Color(0xF01B2430),
    scrim = Color.Black.copy(alpha = 0.55f),
    fieldText = Color.White,
    switchOffTrack = Color(0xFF2A3547),
)

val currentTheme: ZeroThemeColors
    get() = if (ThemeState.dark) dark else light.copy(bgBase = launchWash.base)

// --- Backwards-compatible top-level color accessors -------------------------
// The codebase references colorCard / colorPill / ... as plain top-level vals.
// They are now theme-backed getters so every existing call site recomposes on
// theme flips without any edit.
val colorCard: Color get() = currentTheme.cardBg
val colorCardBorder: Color get() = currentTheme.cardBorder
val colorTextSecondary: Color get() = currentTheme.textSecondary
val colorPill: Color get() = currentTheme.pillBg
val colorBgTop: Color get() = currentTheme.bgBase
val colorBgBottom: Color get() = currentTheme.bgBase
val colorTextPrimary: Color get() = currentTheme.textPrimary

// --- Background: flat base + layered neon glows (port of MainScreen) -------
fun Modifier.zeroBackgroundGlow(): Modifier = drawBehind {
    val isDark = ThemeState.dark
    drawRect(if (isDark) Color(0xFF070B11) else launchWash.base)
    if (isDark) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF0A5E96).copy(alpha = 0.32f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.13f),
                radius = size.width * 0.95f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF35C6FF).copy(alpha = 0.09f), Color.Transparent),
                center = Offset(size.width * 0.1f, size.height * 0.04f),
                radius = size.width * 0.75f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF0A5E96).copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.72f),
                radius = size.width * 0.9f
            )
        )
    } else {
        val wash = launchWash
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(wash.glowTop.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.10f),
                radius = size.width * 1.0f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(wash.glowLeft.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.08f, size.height * 0.04f),
                radius = size.width * 0.8f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(wash.glowBottom.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.86f),
                radius = size.width * 0.95f
            )
        )
    }
}
