package com.v2ray.ang.ui.main

import androidx.compose.ui.graphics.Color

/**
 * Zero VPN launch wash — every app start randomly picks one of three pale
 * background tints (azure / mint / rose) for the main backdrop AND the
 * liquid-goo bottom nav capsules (user request: "هر بار که برنامه رو باز
 * می‌کنیم به صورت رندوم یکی از رنگ‌های آبی و قرمز و سبز بیاد").
 * Applied in the light theme only; dark keeps the classic neon-blue look.
 * All bases stay extremely pale so text contrast is unaffected.
 */
data class ZeroWash(
    val base: Color,
    val glowTop: Color,
    val glowLeft: Color,
    val glowBottom: Color,
    val blobTop: Color,
    val blobBottom: Color,
)

val ZERO_WASH_AZURE = ZeroWash(
    base = Color(0xFFF4F8FE),
    glowTop = Color(0xFF35C6FF),
    glowLeft = Color(0xFF4D8DFF),
    glowBottom = Color(0xFF5AA8FF),
    blobTop = Color(0xFF35C6FF),
    blobBottom = Color(0xFF0084D4),
)

val ZERO_WASH_MINT = ZeroWash(
    base = Color(0xFFF2FBF6),
    glowTop = Color(0xFF3BD9A4),
    glowLeft = Color(0xFF2FBF8F),
    glowBottom = Color(0xFF43CFA0),
    blobTop = Color(0xFF3BE3A7),
    blobBottom = Color(0xFF00A865),
)

val ZERO_WASH_ROSE = ZeroWash(
    base = Color(0xFFFEF3F5),
    glowTop = Color(0xFFFF7A94),
    glowLeft = Color(0xFFFF8E7C),
    glowBottom = Color(0xFFFF9DA6),
    blobTop = Color(0xFFFF7A94),
    blobBottom = Color(0xFFD8274C),
)

/** Random pale wash for this launch — evaluated once per process. */
val launchWash: ZeroWash by lazy {
    listOf(ZERO_WASH_AZURE, ZERO_WASH_MINT, ZERO_WASH_ROSE).random()
}
