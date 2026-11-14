package com.zerovpn.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Zero VPN desktop theme v2 — a clean, flat, professional desktop palette.
 * The v1.x "random wash + liquid goo" phone aesthetic is gone: solid soft
 * surfaces, one neon-blue accent, generous whitespace. Light is the default;
 * dark is a proper low-glare slate (not pure black).
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
    bgBase = Color(0xFFF4F6FA),
    sidebarBg = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF14213A),
    textSecondary = Color(0xFF64748E),
    cardBg = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE3E9F2),
    pillBg = Color(0xFFEFF3F9),
    accent = Color(0xFF0087DB),
    accentSoft = Color(0xFF35C6FF),
    accentDeep = Color(0xFF0069B8),
    pingGood = Color(0xFF0091D6),
    pingMid = Color(0xFFD97706),
    pingBad = Color(0xFFE23A5F),
    bannerBg = Color(0xFFFFFFFF),
    scrim = Color.Black.copy(alpha = 0.35f),
    fieldText = Color(0xFF14213A),
    switchOffTrack = Color(0xFFCBD5E4),
    hoverBg = Color(0xFFF1F5FB),
    selectedRowBg = Color(0xFFE7F2FC),
    divider = Color(0xFFEDF1F7),
    statusOk = Color(0xFF12B76A),
    statusIdle = Color(0xFF94A3B8),
)

private val dark = ZeroThemeColors(
    bgBase = Color(0xFF0D131F),
    sidebarBg = Color(0xFF111827),
    textPrimary = Color(0xFFE7EDF7),
    textSecondary = Color(0xFF8A99B4),
    cardBg = Color(0xFF151D2C),
    cardBorder = Color(0xFF223047),
    pillBg = Color(0xFF1A2435),
    accent = Color(0xFF2FBBFF),
    accentSoft = Color(0xFF6FD3FF),
    accentDeep = Color(0xFF0E86D4),
    pingGood = Color(0xFF4CC3FF),
    pingMid = Color(0xFFFFB020),
    pingBad = Color(0xFFFF5470),
    bannerBg = Color(0xFF1A2435),
    scrim = Color.Black.copy(alpha = 0.55f),
    fieldText = Color(0xFFE7EDF7),
    switchOffTrack = Color(0xFF2A3547),
    hoverBg = Color(0xFF1B2536),
    selectedRowBg = Color(0xFF152C42),
    divider = Color(0xFF1E293C),
    statusOk = Color(0xFF2EDB8B),
    statusIdle = Color(0xFF5E6E8C),
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

/** Neon gradient used on the connect disc + status accents. */
val accentGradient: Brush
    get() = Brush.verticalGradient(listOf(currentTheme.accentSoft, currentTheme.accentDeep))
