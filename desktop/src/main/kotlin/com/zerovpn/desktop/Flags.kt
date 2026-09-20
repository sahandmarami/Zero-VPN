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

    private const val TTL_MILLIS = 30L * 24 * 60 * 60 * 1000 // one month

    /** cc (lowercase ISO) -> decoded bitmap, observed by the UI. */
    val byCode = mutableStateMapOf<String, ImageBitmap>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun flagsDir(): File = File(Store.dataDir, "flags").apply { mkdirs() }

    /** Kicks off (or reuses) a background fetch for the given country code. */
    fun ensure(cc: String?) {
        val code = cc?.trim()?.lowercase()?.takeIf { Regex("^[a-z]{2}$").matches(it) } ?: return
        if (byCode.containsKey(code) || failed.contains(code)) return
        // Serve from the disk cache first, then refresh in the background.
        val cached = cachedFile(code)
        if (cached != null) {
            byCode[code] = cached
            if (System.currentTimeMillis() - cachedFileTime(code) <= TTL_MILLIS) return
        }
        Store.scope.launch {
            val bmp = withContext(Dispatchers.IO) { fetch(code) }
            if (bmp != null) {
                byCode[code] = bmp
                failed.remove(code)
            } else if (!byCode.containsKey(code)) {
                failed.add(code)
            }
        }
    }

    private fun cachedFile(code: String): ImageBitmap? = try {
        val f = File(flagsDir(), "$code.png")
        if (f.isFile && f.length() > 0) decode(f.readBytes()) else null
    } catch (_: Throwable) {
        null
    }

    private fun cachedFileTime(code: String): Long = try {
        File(flagsDir(), "$code.png").lastModified()
    } catch (_: Throwable) {
        0L
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
                    color = zeroHomeColors.accent
                )
            }
        } else {
            // Fallback: ISO code chip (same as the Android "US" pill).
            val hc = zeroHomeColors
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
