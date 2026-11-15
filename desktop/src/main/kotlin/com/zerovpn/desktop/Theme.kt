package com.zerovpn.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Zero VPN desktop theme v4 — "Neon Violet" (the user's mockup).
 *
 * v2.3.0: the identity now follows the mockup the user sent — a violet neon
 * look with a glowing connect ring, soft cards and rounded tiles. Deliberate
 * softness: the user asked for the mockup style WITHOUT a very dark theme
 * («لازم هم نیست خیلی تیره باشه تم فعلا»), so dark is a medium violet-navy
 * (not near-black) and stays the default from a fresh install; light keeps
 * the lavender-aurora surfaces.
 */
object ThemeState {
    /** true = dark theme, false = light theme. */
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

// Softened violet dark — deliberately NOT near-black (user request).
private val dark = ZeroThemeColors(
    bgBase = Color(0xFF1E1838),
    sidebarBg = Color(0xFF26204A),
    textPrimary = Color(0xFFF1EEFF),
    textSecondary = Color(0xFFA79FD4),
    cardBg = Color(0xFF2A2354),
    cardBorder = Color(0xFF3B3268),
    pillBg = Color(0xFF342B60),
    accent = Color(0xFF9D7BFF),
    accentSoft = Color(0xFFBDA4FF),
    accentDeep = Color(0xFF7A55F2),
    pingGood = Color(0xFF3ED9AC),
    pingMid = Color(0xFFFFB35C),
    pingBad = Color(0xFFFF6B81),
    bannerBg = Color(0xFF342B60),
    scrim = Color.Black.copy(alpha = 0.55f),
    fieldText = Color(0xFFF1EEFF),
    switchOffTrack = Color(0xFF43396F),
    hoverBg = Color(0xFF322A5E),
    selectedRowBg = Color(0xFF3A2F6E),
    divider = Color(0xFF352C60),
    statusOk = Color(0xFF38E0A8),
    statusIdle = Color(0xFF8F89BC),
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

/** Signature gradient — violet monochrome like the mockup ring. */
val heroGradient: Brush
    get() = Brush.linearGradient(
        listOf(
            currentTheme.accentSoft,
            currentTheme.accent,
            currentTheme.accentDeep,
        )
    )

/** Gradient used on the connect disc + status accents. */
val accentGradient: Brush
    get() = Brush.linearGradient(listOf(currentTheme.accent, currentTheme.accentDeep))

/** The mockup's ring: a sweep around the accent wheel (used by Canvas draws). */
val ringSweep: Brush
    get() = Brush.sweepGradient(
        listOf(
            currentTheme.accentDeep,
            currentTheme.accent,
            currentTheme.accentSoft,
            currentTheme.accentDeep,
        )
    )
