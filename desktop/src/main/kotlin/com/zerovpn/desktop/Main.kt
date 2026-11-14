package com.zerovpn.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
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
import kotlinx.coroutines.launch
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import javax.imageio.ImageIO
import kotlin.system.exitProcess

// ---------------------------------------------------------------------------
// Zero VPN desktop v2 — clean rebuild. The v1.6.0 release shipped a real
// crash ("Unsupported concurrent change during composition") caused by
// writing Compose state during composition while IO coroutines wrote the
// same states from background threads. v2.0.0 rebuilds the app around a
// strict Main-thread state contract, a real desktop layout (sidebar), live
// traffic stats through the core's local stats API, a core watchdog, and
// proper single-instance handling that BRINGS THE RUNNING WINDOW TO FRONT
// instead of popping a dead-end dialog.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// App font — B Nazanin (the classic Persian UI font, user-requested), loaded
// straight from the bundled resources as bytes. Letter-joining (Persian
// shaping) comes from the font's own GSUB tables, so no text style may ever
// set a non-zero letterSpacing — it breaks Persian joining.
// ---------------------------------------------------------------------------
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
val zeroTypography: androidx.compose.material3.Typography by lazy {
    val d = androidx.compose.material3.Typography()
    androidx.compose.material3.Typography(
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
// Single-instance guard
//   * file lock prevents a second full instance (data corruption / port fight)
//   * a tiny loopback listener turns every double-launch into "show the
//     already-running window" — the v1.6.0 dialog told users to hunt for a
//     tray icon they could not find; v2.0.0 just resurfaces the window.
// ---------------------------------------------------------------------------
private const val IPC_PORT = 26851

private object InstanceLock {
    var channel: RandomAccessFile? = null
    var lock: FileLock? = null
    var server: ServerSocket? = null
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

/** Notify the running instance to show its window; true if it answered. */
private fun signalRunningInstance(): Boolean = try {
    Socket("127.0.0.1", IPC_PORT).use { s ->
        s.getOutputStream().write("SHOW\n".toByteArray())
        s.getOutputStream().flush()
        true
    }
} catch (_: Throwable) {
    false
}

/** Loopback listener: another ZeroVPN.exe asked us to come to the front. */
private fun startIpcListener(onShow: () -> Unit) {
    try {
        val server = ServerSocket(IPC_PORT, 4, java.net.InetAddress.getLoopbackAddress())
        InstanceLock.server = server
        Thread({
            while (!server.isClosed) {
                try {
                    server.accept().use { client ->
                        val buf = ByteArray(16)
                        try { client.getInputStream().read(buf) } catch (_: Throwable) { }
                        if (buf.decodeToString().contains("SHOW")) {
                            javax.swing.SwingUtilities.invokeLater(onShow)
                        }
                    }
                } catch (_: Throwable) {
                    break
                }
            }
        }, "ZeroVPN-IPC").isDaemon = true
    } catch (_: Throwable) { }
}

/** No-op placeholder — the listener already runs from main(). */
private fun startIpcListenerFallback() { }

/** Full exit — core, system proxy, then the process. Used by tray + hooks. */
fun exitApplication() {
    try { Engine.stop() } catch (_: Throwable) { }
    try { if (Store.isWindows) SysProxy.disable() } catch (_: Throwable) { }
    try { InstanceLock.server?.close() } catch (_: Throwable) { }
    exitProcess(0)
}

fun main() {
    // Crash diagnostics (v2.0.1): the compose-desktop default handler shows a
    // dead-end "Error" dialog and leaves nothing to debug from. Append the
    // full trace to %APPDATA%/ZeroVPN/crash.log so a user screenshot of the
    // log can pinpoint the failure — and keep the app process alive.
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
        try {
            val f = File(Store.dataDir, "crash.log")
            f.appendText(
                buildString {
                    append("----- crash @ ").append(java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                    ).format(java.util.Date())).append(" (thread: ").append(thread.name).append(") -----\n")
                    append(e.toString()).append('\n')
                    e.stackTrace.forEach { append("    at $it\n") }
                    var cause = e.cause
                    var depth = 0
                    while (cause != null && depth++ < 8) {
                        append("Caused by: ").append(cause.toString()).append('\n')
                        cause.stackTrace.take(20).forEach { append("    at $it\n") }
                        cause = cause.cause
                    }
                    append('\n')
                }
            )
        } catch (_: Throwable) { }
        try {
            javax.swing.SwingUtilities.invokeLater {
                Store.toast("یک خطای داخلی رخ داد — crash.log در پوشه داده‌ها ثبت شد")
            }
        } catch (_: Throwable) { }
    }

    Store.load()

    if (!acquireInstanceLock()) {
        // Already running — resurface the existing window, fall back to the
        // informational dialog only if the IPC handshake fails.
        if (!signalRunningInstance()) {
            try {
                javax.swing.JOptionPane.showMessageDialog(
                    null,
                    "Zero VPN از قبل در حال اجراست — آیکون آن کنار ساعت (system tray) است.",
                    "Zero VPN",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE
                )
            } catch (_: Throwable) { }
        }
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

    // Core version probe (IO) — fills the sidebar + settings rows. Written
    // via the Main-confined UI scope, never from a bare worker thread.
    Store.io.launch {
        val v = Engine.probeVersion()
        if (v != null) Store.ui.launch { Store.coreVersion = v }
    }

    // Loopback IPC: a second launch resurfaces this window (before any
    // composition — a one-time side effect, not a composition-time write).
    startIpcListener { Store.windowVisible = true }

    application {
        val icon = remember { loadLogoPainter() }
        val windowState = rememberWindowState(width = 1020.dp, height = 680.dp)

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

        startIpcListenerFallback()

        Window(
            onCloseRequest = {
                // X does not quit — the tunnel and tray icon stay alive
                // (like v2rayN). Real exit lives in the tray menu.
                Store.windowVisible = false
            },
            visible = Store.windowVisible,
            title = "Zero VPN $APP_VERSION",
            icon = icon,
            resizable = true,
            state = windowState,
        ) {
            // Min size keeps the sidebar layout intact; the window is now
            // freely resizable (the fixed 412×782 phone window was part of
            // the broken look users reported).
            window.minimumSize = java.awt.Dimension(880, 600)
            androidx.compose.material3.Surface(
                modifier = androidx.compose.ui.Modifier.fillMaxSize()
            ) {
                ZeroApp()
            }
        }

        // v2rayNG-style boot behaviour: connect the last server on launch
        LaunchedEffect(Unit) {
            if (Store.data.settings.autoConnect && Store.profiles.isNotEmpty() &&
                Store.status == ConnStatus.DISCONNECTED
            ) {
                Connector.connect()
            }
        }
    }
}
