package com.zerovpn.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.platform.Font as PlatformFont
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import javax.imageio.ImageIO

// ---------------------------------------------------------------------------
// Zero VPN neon palette (matches the Android app) — theme surfaces live in
// Theme.kt so the app can switch between the phone's light look and dark.
// ---------------------------------------------------------------------------
val colorZeroNeon = Color(0xFF00A8F5)
val colorZeroNeonSoft = Color(0xFF35C6FF)
val colorZeroDeep = Color(0xFF0084D4)
val colorZeroIdle = Color(0xFF3A4A61)
val colorZeroTesting = Color(0xFFFFB020)
val colorZeroFailure = Color(0xFFFF5470)

/**
 * App font — B Nazanin (the classic Persian UI font, user-requested), loaded
 * straight from the bundled resources as bytes. Vazirmatn is kept as a
 * fallback in case the resource is ever missing. Letter-joining (Persian
 * shaping) comes from the font's own GSUB init/medi/fina tables, so no text
 * style may ever set a non-zero letterSpacing — it breaks Persian joining.
 */
val zeroFontFamily: FontFamily by lazy {
    try {
        val cl = Store::class.java.classLoader
        fun resource(path: String): ByteArray? = try {
            cl.getResourceAsStream(path)?.readBytes()
        } catch (_: Throwable) { null }
        val bnRegular = resource("font/BNazanin.ttf")
        val bnBold = resource("font/BNazanin-Bold.ttf")
        val vRegular = resource("font/Vazirmatn-Regular.ttf")
        val vBold = resource("font/Vazirmatn-Bold.ttf")
        when {
            bnRegular != null && bnBold != null -> FontFamily(
                PlatformFont("bnazanin", bnRegular, FontWeight.Normal, FontStyle.Normal),
                PlatformFont("bnazanin-bold", bnBold, FontWeight.Bold, FontStyle.Normal),
            )
            bnRegular != null -> FontFamily(PlatformFont("bnazanin", bnRegular, FontWeight.Normal, FontStyle.Normal))
            vRegular != null && vBold != null -> FontFamily(
                PlatformFont("vazirmatn", vRegular, FontWeight.Normal, FontStyle.Normal),
                PlatformFont("vazirmatn-bold", vBold, FontWeight.Bold, FontStyle.Normal),
            )
            vRegular != null -> FontFamily(PlatformFont("vazirmatn", vRegular, FontWeight.Normal, FontStyle.Normal))
            else -> FontFamily.Default
        }
    } catch (_: Throwable) {
        FontFamily.Default
    }
}

/** Material3 typography with B Nazanin on every style (menus, text fields…). */
val zeroTypography: Typography by lazy {
    val d = Typography()
    Typography(
        displayLarge = d.displayLarge.copy(fontFamily = zeroFontFamily),
        displayMedium = d.displayMedium.copy(fontFamily = zeroFontFamily),
        displaySmall = d.displaySmall.copy(fontFamily = zeroFontFamily),
        headlineLarge = d.headlineLarge.copy(fontFamily = zeroFontFamily),
        headlineMedium = d.headlineMedium.copy(fontFamily = zeroFontFamily),
        headlineSmall = d.headlineSmall.copy(fontFamily = zeroFontFamily),
        titleLarge = d.titleLarge.copy(fontFamily = zeroFontFamily),
        titleMedium = d.titleMedium.copy(fontFamily = zeroFontFamily),
        titleSmall = d.titleSmall.copy(fontFamily = zeroFontFamily),
        bodyLarge = d.bodyLarge.copy(fontFamily = zeroFontFamily),
        bodyMedium = d.bodyMedium.copy(fontFamily = zeroFontFamily),
        bodySmall = d.bodySmall.copy(fontFamily = zeroFontFamily),
        labelLarge = d.labelLarge.copy(fontFamily = zeroFontFamily),
        labelMedium = d.labelMedium.copy(fontFamily = zeroFontFamily),
        labelSmall = d.labelSmall.copy(fontFamily = zeroFontFamily),
    )
}

fun loadLogoPainter(): Painter? = try {
    val stream = Store::class.java.classLoader.getResourceAsStream("icon/logo.png") ?: return null
    val img = ImageIO.read(stream)
    BitmapPainter(img.toComposeImageBitmap())
} catch (_: Throwable) {
    null
}

fun main() {
    Store.load()
    // Clean up leftovers from a previous session
    Engine.stop()
    if (Store.isWindows) SysProxy.disable()
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            Engine.stop()
            if (Store.isWindows && Store.proxyOn) SysProxy.disable()
        } catch (_: Throwable) { }
    })
    application {
        val icon = remember { loadLogoPainter() }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Zero VPN",
            icon = icon,
            resizable = false,
            state = rememberWindowState(width = 412.dp, height = 782.dp),
        ) {
            Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                ZeroApp()
            }
        }
    }
}

@Composable
fun ZeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (ThemeState.dark) darkColorScheme(
            primary = colorZeroNeon,
            background = colorBgTop,
            surface = colorCard,
            onBackground = colorTextPrimary,
            onSurface = colorTextPrimary,
        ) else lightColorScheme(
            primary = colorZeroDeep,
            background = colorBgTop,
            surface = colorCard,
            onBackground = colorTextPrimary,
            onSurface = colorTextPrimary,
        ),
        typography = zeroTypography,
        content = content
    )
}
