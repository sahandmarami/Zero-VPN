package com.zerovpn.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

data class ParsedLink(val name: String, val proto: String, val link: String)

/** Result of a subscription fetch — applied to the Store on Main. */
data class SubFetchResult(
    val url: String,
    val profiles: List<ProfileRec>,
    val info: SubRec?,
    val error: String? = null,
)

/**
 * Pure data operations — no Store/state writes here. Callers run these on
 * Dispatchers.IO and post the results to the Store on Main.
 */
object Profiles {

    private val supported = setOf("vmess", "vless", "trojan", "ss")

    /** Decode a subscription body: base64 blob or plain link lines. */
    fun parseBody(body: String): List<ParsedLink> {
        val text = body.trim()
        val lines = tryBase64Lines(text) ?: text.lineSequence().map { it.trim() }.toList()
        val out = ArrayList<ParsedLink>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue
            val scheme = line.substringBefore("://").lowercase()
            if (scheme !in supported) continue
            val name = linkName(line) ?: "$scheme-server"
            out.add(ParsedLink(name = name, proto = scheme, link = line))
        }
        return out
    }

    private fun tryBase64Lines(text: String): List<String>? {
        if (text.startsWith("vmess://") || text.startsWith("vless://") ||
            text.startsWith("trojan://") || text.startsWith("ss://")) return null
        val clean = text.replace(Regex("\\s+"), "")
        if (clean.isEmpty()) return null
        val decoders = listOf(
            { s: String -> Base64.getDecoder().decode(s) },
            { s: String -> Base64.getUrlDecoder().decode(s) }
        )
        for (dec in decoders) {
            try {
                val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
                val decoded = String(dec(padded), Charsets.UTF_8)
                if (decoded.contains("://")) return decoded.lines()
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun linkName(link: String): String? {
        val hash = link.lastIndexOf('#')
        if (hash < 0) return null
        return try {
            URLDecoder.decode(link.substring(hash + 1), "UTF-8").take(80)
        } catch (_: Throwable) { link.substring(hash + 1).take(80) }
    }

    // --- Network ------------------------------------------------------------
    /** Fetch a URL body; also returns the subscription-userinfo header if present. */
    fun fetchWithInfo(url: String, timeoutMs: Int = 15000): Pair<String, SubRec?> {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = timeoutMs
        con.readTimeout = timeoutMs
        con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", "v2rayNG/1.9.16")
        con.setRequestProperty("Accept", "*/*")
        val code = con.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        val body = con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        var info: SubRec? = null
        try {
            val raw = con.headerFields.entries
                .firstOrNull { it.key?.equals("subscription-userinfo", true) == true }
                ?.value?.joinToString(";")
            if (!raw.isNullOrBlank()) {
                val map = raw.split(";").mapNotNull {
                    val p = it.split("=", limit = 2)
                    if (p.size == 2) p[0].trim().lowercase() to p[1].trim().toLongOrNull() else null
                }.toMap()
                info = SubRec(
                    url = url,
                    name = "",
                    used = (map["upload"] ?: 0) + (map["download"] ?: 0),
                    total = map["total"] ?: 0,
                    expire = map["expire"] ?: 0,
                )
            }
        } catch (_: Throwable) { }
        return body to info
    }

    fun fetch(url: String, timeoutMs: Int = 15000): String = fetchWithInfo(url, timeoutMs).first

    // --- High level operations (pure — results applied by the caller) -------
    fun fetchSub(url: String): SubFetchResult = try {
        val (body, info) = fetchWithInfo(url)
        val links = parseBody(body)
        val fresh = links.map { p ->
            ProfileRec(
                id = subId(url, p.link),
                name = p.name,
                proto = p.proto,
                link = p.link,
                sub = url,
            )
        }.distinctBy { it.id }
        SubFetchResult(url = url, profiles = fresh, info = info)
    } catch (t: Throwable) {
        SubFetchResult(url = url, profiles = emptyList(), info = null,
            error = t.message ?: t.javaClass.simpleName)
    }

    fun importText(text: String): List<ProfileRec> =
        parseBody(text).map { p ->
            ProfileRec(
                id = stableId(p.link),
                name = p.name,
                proto = p.proto,
                link = p.link,
                sub = null,
            )
        }

    fun stableId(link: String): String =
        UUID.nameUUIDFromBytes(link.toByteArray(Charsets.UTF_8)).toString()

    /** Unique id per (subscription, link): the same server link inside two
     *  different subscriptions must be two independent rows. */
    fun subId(subUrl: String, link: String): String =
        stableId(subUrl + "|" + link)

    /** Read system clipboard text (best effort). */
    fun clipboardText(): String? = try {
        val tool = java.awt.Toolkit.getDefaultToolkit()
        val clip = tool.systemClipboard
        val contents = clip.getContents(null)
        contents?.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    } catch (_: Throwable) { null }

    fun saveFile(name: String, bytes: ByteArray): File =
        File(Store.dataDir, name).apply { writeBytes(bytes) }
}
