package com.zerovpn.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zero VPN flag images — Windows (unlike Android) has no font glyphs for the
 * regional-indicator flag emoji, so the flag is rendered as a real image
 * (flagcdn.com PNG) with a persistent on-disk cache. Falls back to the ISO
 * code chip while loading / offline, matching the Android chip look.
 */
object FlagImages {

    /** cc (lowercase ISO) -> decoded bitmap, observed by the UI. */
    val byCode = mutableStateMapOf<String, ImageBitmap>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Codes with an in-flight resolve — dedupes launches between "started"
     *  and "the deferred byCode write landed" (plain set, not state). */
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun flagsDir(): File = File(Store.dataDir, "flags").apply { mkdirs() }

    // -----------------------------------------------------------------------
    // v2.0.1 crash fix — "Unsupported concurrent change during composition":
    // ZeroFlag (a composable) calls ensure() DURING COMPOSITION, and the old
    // code then wrote byCode[code] directly (disk-cache and bundled paths)
    // while the network fallback wrote the SAME map from an IO worker thread.
    // A composition whose snapshot also modified byCode then failed to apply
    // and Compose aborted with the error dialog — every time the Servers
    // screen composed many flags at once. Now: ensure() NEVER writes state.
    // Resolution (disk cache -> bundled resource -> network) runs on IO, and
    // the single observable write is posted to the EDT, deferred — it can
    // never land inside a running composition.
    // -----------------------------------------------------------------------

    /** Kicks off (or reuses) a background resolve for the given country code. */
    fun ensure(cc: String?) {
        val code = cc?.trim()?.lowercase()?.takeIf { Regex("^[a-z]{2}$").matches(it) } ?: return
        if (byCode.containsKey(code) || failed.contains(code) || pending.contains(code)) return
        pending.add(code)
        Store.io.launch {
            val bmp = withContext(Dispatchers.IO) { resolve(code) }
            pending.remove(code)
            if (bmp != null) {
                postStateWrite { byCode[code] = bmp }
            } else {
                failed.add(code)
            }
        }
    }

    /** Where a flag bitmap comes from, in priority order. IO only. */
    private fun resolve(code: String): ImageBitmap? {
        // 1) Persistent disk cache from an earlier fetch (any age — better
        //    than the code pill; the TTL refresh happens via flagcdn below).
        cachedFile(code)?.let { return it }
        // 2) Bundled resource copy (v1.5.7): flagcdn used to be the only
        //    source and it is unreachable on some Iranian connections, so
        //    servers stuck on the ISO-code pill forever. Every ISO-3166 flag
        //    ships inside the jar — instant, offline-correct.
        bundled(code)?.let { return it }
        // 3) flagcdn refresh (codes missing from the bundle, e.g. "eu").
        return fetch(code)
    }

    /** SelfTest hook: bundled flag resources must be resolvable offline. */
    internal fun bundledAccessible(code: String): Boolean = try {
        Store::class.java.classLoader.getResourceAsStream("flags/$code.png") != null
    } catch (_: Throwable) {
        false
    }

    /** Flag PNG shipped inside the jar under /flags/{cc}.png. */
    private fun bundled(code: String): ImageBitmap? = try {
        val stream = Store::class.java.classLoader.getResourceAsStream("flags/$code.png")
        stream?.readBytes()?.let { decode(it) }
    } catch (_: Throwable) {
        null
    }

    private fun cachedFile(code: String): ImageBitmap? = try {
        val f = File(flagsDir(), "$code.png")
        if (f.isFile && f.length() > 0) decode(f.readBytes()) else null
    } catch (_: Throwable) {
        null
    }

    private fun fetch(code: String): ImageBitmap? {
        val dest = File(flagsDir(), "$code.png")
        // Try the disk cache first (also when stale — better than nothing).
        cachedFile(code)?.let { return it }
        val sources = listOf(
            "https://flagcdn.com/w80/$code.png",
        )
        for (url in sources.take(1)) {
            try {
                val bytes = httpGet(url) ?: continue
                val bmp = decode(bytes) ?: continue
                try {
                    dest.writeBytes(bytes)
                } catch (_: Throwable) {
                }
                return bmp
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun decode(bytes: ByteArray): ImageBitmap? = try {
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Throwable) {
        null
    }

    private fun httpGet(url: String, timeoutMs: Int = 8000): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "ZeroVPN/$APP_VERSION")
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        null
    }
}

/**
 * Country flag image (rounded) — optionally with the ISO code next to it,
 * exactly like the Android "US 🇺🇸" chip. Falls back to the code chip while
 * loading / offline (Windows has no flag-emoji glyphs).
 */
@Composable
fun ZeroFlag(
    countryCode: String?,
    width: Dp = 26.dp,
    showCode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (countryCode.isNullOrBlank()) return
    val code = countryCode.trim().uppercase()
    FlagImages.ensure(countryCode)
    val bmp = FlagImages.byCode[code.lowercase()]
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (bmp != null) {
            // flagcdn w80 images are 4:3 — keep that ratio at the requested width.
            val h = width * 0.75f
            Image(
                bitmap = bmp,
                contentDescription = code,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .size(width, h)
                    .clip(RoundedCornerShape(3.dp))
            )
            if (showCode) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = code,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = currentTheme.accent
                )
            }
        } else {
            // Fallback: ISO code chip (same as the Android "US" pill).
            val hc = currentTheme
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(hc.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = code,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = hc.accent
                )
            }
        }
    }
}
