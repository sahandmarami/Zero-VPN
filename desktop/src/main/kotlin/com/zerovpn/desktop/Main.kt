package com.zerovpn.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.platform.Font as PlatformFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import javax.imageio.ImageIO
import kotlin.system.exitProcess

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

// ---------------------------------------------------------------------------
// Single-instance guard — a held file lock in the data dir. Prevents the
// classic double-launch mess: second instance steals the SOCKS port (core
// start fails with "address already in use") and both windows fight over the
// system-proxy registry keys.
// ---------------------------------------------------------------------------
private object InstanceLock {
    var channel: RandomAccessFile? = null
    var lock: FileLock? = null
}

private fun acquireInstanceLock(): Boolean = try {
    val f = File(Store.dataDir, "zerovpn.lock")
    val ch = RandomAccessFile(f, "rw")
    val l = ch.channel.tryLock()
    if (l != null) {
        InstanceLock.channel = ch
        InstanceLock.lock = l
        true
    } else {
        ch.close()
        false
    }
} catch (_: Throwable) {
    true // never block the app because of a lock-file hiccup
}

/** Full exit — core, system proxy, then the process. Used by tray + hooks. */
fun exitApplication() {
    try { Engine.stop() } catch (_: Throwable) { }
    try { if (Store.isWindows) SysProxy.disable() } catch (_: Throwable) { }
    try { Store.save() } catch (_: Throwable) { }
    exitProcess(0)
}

fun main() {
    Store.load()

    if (!acquireInstanceLock()) {
        try {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "Zero VPN از قبل در حال اجراست — آیکون آن کنار ساعت (system tray) است.",
                "Zero VPN",
                javax.swing.JOptionPane.INFORMATION_MESSAGE
            )
        } catch (_: Throwable) { }
        exitProcess(0)
    }

    // Clean up leftovers from a previous session
    Engine.stop()
    if (Store.isWindows) SysProxy.disable()
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            Engine.stop()
            if (Store.isWindows) SysProxy.disable()
        } catch (_: Throwable) { }
    })

    application {
        val icon = remember { loadLogoPainter() }

        // --- System tray — the command center when the window is closed ----
        if (icon != null) {
            Tray(
                icon = icon,
                tooltip = "Zero VPN $APP_VERSION",
                onAction = { Store.windowVisible = true },
                menu = {
                    Item("باز کردن Zero VPN", onClick = { Store.windowVisible = true })
                    when (Store.status) {
                        ConnStatus.CONNECTED -> Item("قطع اتصال", onClick = { Connector.disconnect() })
                        ConnStatus.CONNECTING -> Item("در حال اتصال…", onClick = { })
                        ConnStatus.DISCONNECTED -> Item("اتصال", onClick = { Connector.connect() })
                    }
                    Item("تست همه سرورها", onClick = { Connector.testAll() })
                    Separator()
                    Item("خروج", onClick = { exitApplication() })
                }
            )
        }

        Window(
            onCloseRequest = {
                // X does not quit — the tunnel and tray icon stay alive
                // (like v2rayN). Real exit lives in the tray menu.
                Store.windowVisible = false
            },
            visible = Store.windowVisible,
            title = "Zero VPN",
            icon = icon,
            resizable = false,
            state = rememberWindowState(width = 412.dp, height = 782.dp),
        ) {
            Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                ZeroApp()
            }
        }

        // v2rayNG-style boot behaviour: connect the last server on launch
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (Store.data.settings.autoConnect && Store.profiles.isNotEmpty() &&
                Store.status == ConnStatus.DISCONNECTED
            ) {
                Connector.connect()
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
