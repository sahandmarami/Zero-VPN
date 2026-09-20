package com.zerovpn.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
import java.io.File
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

val fontFamilyVazir: FontFamily by lazy {
    try {
        val cl = Store::class.java.classLoader
        val fontDir = File(Store.dataDir, "fonts").apply { mkdirs() }
        fun dump(res: String, name: String): File? {
            val bytes = cl.getResourceAsStream(res)?.readBytes() ?: return null
            return File(fontDir, name).apply { writeBytes(bytes) }
        }
        val regular = dump("font/Vazirmatn-Regular.ttf", "Vazirmatn-Regular.ttf")
        val bold = dump("font/Vazirmatn-Bold.ttf", "Vazirmatn-Bold.ttf")
        when {
            regular != null && bold != null -> FontFamily(
                PlatformFont(regular, FontWeight.Normal, FontStyle.Normal),
                PlatformFont(bold, FontWeight.Bold, FontStyle.Normal),
            )
            regular != null -> FontFamily(PlatformFont(regular, FontWeight.Normal, FontStyle.Normal))
            else -> FontFamily.Default
        }
    } catch (_: Throwable) {
        FontFamily.Default
    }
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
        colorScheme = darkColorScheme(
            primary = colorZeroNeon,
            background = colorBgTop,
            surface = colorCard,
            onBackground = colorTextPrimary,
            onSurface = colorTextPrimary,
        ),
        content = content
    )
}
