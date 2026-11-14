package com.zerovpn.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Zero VPN desktop theme v3 — "Aurora Violet".
 *
 * A completely new identity (user asked for a different look, v2.1.0):
 * the old neon-blue "sky" palette is replaced by a violet/indigo aurora —
 * lavender-tinted light surfaces, ink-violet dark surfaces, and a signature
 * three-stop gradient (violet → indigo → cyan) used on the hero, the active
 * nav pill and the connect disc. Shapes stay soft (16–20 dp radii).
 */
object ThemeState {
    /** true = dark theme, false = light theme (default). */
    var dark by mutableStateOf(false)

    fun apply(darkTheme: Boolean) {
        dark = darkTheme
    }
}

data class ZeroThemeColors(
    val bgBase: Color,
    val sidebarBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val pillBg: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentDeep: Color,
    val pingGood: Color,
    val pingMid: Color,
    val pingBad: Color,
    val bannerBg: Color,
    val scrim: Color,
    val fieldText: Color,
    val switchOffTrack: Color,
    val hoverBg: Color,
    val selectedRowBg: Color,
    val divider: Color,
    val statusOk: Color,
    val statusIdle: Color,
)

private val light = ZeroThemeColors(
    bgBase = Color(0xFFF5F4FC),
    sidebarBg = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF1B1830),
    textSecondary = Color(0xFF6B6A85),
    cardBg = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE6E3F5),
    pillBg = Color(0xFFEFEDFB),
    accent = Color(0xFF6D5CFF),
    accentSoft = Color(0xFF9A8DFF),
    accentDeep = Color(0xFF4F3ED9),
    pingGood = Color(0xFF0E9F8A),
    pingMid = Color(0xFFD97C06),
    pingBad = Color(0xFFE5484D),
    bannerBg = Color(0xFFFFFFFF),
    scrim = Color.Black.copy(alpha = 0.35f),
    fieldText = Color(0xFF1B1830),
    switchOffTrack = Color(0xFFD9D6EC),
    hoverBg = Color(0xFFF2F0FC),
    selectedRowBg = Color(0xFFECE8FF),
    divider = Color(0xFFECEAF7),
    statusOk = Color(0xFF12A594),
    statusIdle = Color(0xFF9AA0B5),
)

private val dark = ZeroThemeColors(
    bgBase = Color(0xFF0F0E1E),
    sidebarBg = Color(0xFF141329),
    textPrimary = Color(0xFFEAE8FA),
    textSecondary = Color(0xFF9A97BD),
    cardBg = Color(0xFF1A1836),
    cardBorder = Color(0xFF2A2752),
    pillBg = Color(0xFF211E42),
    accent = Color(0xFF8F7DFF),
    accentSoft = Color(0xFFB3A7FF),
    accentDeep = Color(0xFF6A54E8),
    pingGood = Color(0xFF37D9B8),
    pingMid = Color(0xFFFFB454),
    pingBad = Color(0xFFFF6B81),
    bannerBg = Color(0xFF211E42),
    scrim = Color.Black.copy(alpha = 0.55f),
    fieldText = Color(0xFFEAE8FA),
    switchOffTrack = Color(0xFF2E2A55),
    hoverBg = Color(0xFF201D40),
    selectedRowBg = Color(0xFF262052),
    divider = Color(0xFF25224A),
    statusOk = Color(0xFF2BD9A5),
    statusIdle = Color(0xFF6A6790),
)

val currentTheme: ZeroThemeColors
    get() = if (ThemeState.dark) dark else light

// --- Backwards-compatible top-level color accessors -------------------------
val colorCard: Color get() = currentTheme.cardBg
val colorCardBorder: Color get() = currentTheme.cardBorder
val colorTextSecondary: Color get() = currentTheme.textSecondary
val colorPill: Color get() = currentTheme.pillBg
val colorBgTop: Color get() = currentTheme.bgBase
val colorBgBottom: Color get() = currentTheme.bgBase
val colorTextPrimary: Color get() = currentTheme.textPrimary

/** Signature aurora gradient: violet → indigo → cyan. */
val heroGradient: Brush
    get() = Brush.linearGradient(
        listOf(
            currentTheme.accentSoft,
            currentTheme.accent,
            Color(0xFF35C2E8),
        )
    )

/** Gradient used on the connect disc + status accents. */
val accentGradient: Brush
    get() = Brush.linearGradient(listOf(currentTheme.accent, currentTheme.accentDeep))
