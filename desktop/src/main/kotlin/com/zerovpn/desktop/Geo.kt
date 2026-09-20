package com.zerovpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Zero VPN geo lookup — port of the Android GeoLookupManager: resolves each
 * server host to (country code, country name, IP) via DNS + free geo-IP APIs,
 * with a persistent JSON cache so the flag chips fill in instantly on later
 * launches. The UI uses it for the flag chip next to every server name, the
 * country line on the home card — the same as the phone app.
 */
object GeoLookup {

    @Serializable
    data class ServerGeo(
        val cc: String? = null,
        val ip: String? = null,
        val ts: Long = 0,
    )

    private const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000 // one week

    private val json = Json { ignoreUnknownKeys = true }
    private val sessionCache = ConcurrentHashMap<String, ServerGeo?>()
    private val failedHosts = ConcurrentHashMap.newKeySet<String>()

    /** host -> geo, observed by the UI. */
    val byHost = ConcurrentHashMap<String, ServerGeo>()

    private fun cacheFile(): File = File(Store.dataDir, "geo_cache.json")

    fun loadCache() {
        try {
            val f = cacheFile()
            if (!f.exists()) return
            val map = json.decodeFromString<Map<String, ServerGeo>>(f.readText())
            val now = System.currentTimeMillis()
            map.forEach { (host, geo) ->
                if (now - geo.ts <= TTL_MILLIS) {
                    sessionCache[host] = geo
                    geo.let { byHost[host] = it }
                }
            }
        } catch (_: Throwable) { }
    }

    private fun saveCache() {
        try {
            val out = HashMap<String, ServerGeo>()
            sessionCache.forEach { (h, g) -> if (g != null) out[h] = g }
            cacheFile().writeText(json.encodeToString(out))
        } catch (_: Throwable) { }
    }

    /** Kicks off (or reuses) a background lookup for the given host. */
    fun ensureLookup(host: String?) {
        val h = host?.trim() ?: return
        if (h.isEmpty() || isPrivateHost(h) || failedHosts.contains(h)) return
        if (sessionCache.containsKey(h)) {
            sessionCache[h]?.let { byHost[h] = it }
            return
        }
        Store.scope.launch {
            val geo = withContext(Dispatchers.IO) { lookupHost(h) }
            if (geo != null) {
                sessionCache[h] = geo
                byHost[h] = geo
                saveCache()
            } else {
                sessionCache[h] = null
                failedHosts.add(h)
            }
        }
    }

    private fun lookupHost(host: String): ServerGeo? {
        val ip = if (isIpLiteral(host)) host else resolveHost(host) ?: return null
        return lookupIp(ip)
    }

    fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan")) return true
        if (!isIpLiteral(h)) return false
        val v4 = h.split('.').takeIf { it.size == 4 } ?: return true
        val o = v4.mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return true
        return when {
            o[0] == 10 || o[0] == 127 || o[0] == 0 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 169 && o[1] == 254 -> true
            o[0] == 100 && o[1] in 64..127 -> true
            else -> false
        }
    }

    private fun resolveHost(host: String): String? = try {
        kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(4500L) {
                withContext(Dispatchers.IO) {
                    InetAddress.getByName(host).hostAddress?.takeIf { it.isNotBlank() }
                }
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** Queries several free geo-IP sources until one answers. */
    private fun lookupIp(ip: String): ServerGeo? {
        val sources = listOf(
            "http://ip-api.com/json/$ip?fields=status,country,countryCode,query",
            "https://ipwho.is/$ip",
            "https://api.ip.sb/geoip/$ip",
        )
        sources.forEach { url ->
            try {
                val body = httpGet(url) ?: return@forEach
                parseGeoResponse(body)?.let { return it }
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun httpGet(url: String, timeoutMs: Int = 6000): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "ZeroVPN/$APP_VERSION")
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        null
    }

    private fun parseGeoResponse(body: String): ServerGeo? {
        val root = try { json.parseToJsonElement(body).jsonObjectSafe() } catch (_: Throwable) { return null }
        val cc = root.str("countryCode") ?: root.str("country_code")
        val country = root.str("country") ?: root.str("country_name")
        val ip = root.str("query") ?: root.str("ip") ?: root.str("ip_addr")
        val success = root.str("success")
        if (success != null && success.equals("false", ignoreCase = true)) return null
        val ccClean = cc?.trim()?.uppercase()?.takeIf { Regex("^[A-Z]{2}$").matches(it) }
        if (ccClean == null && ip == null) return null
        return ServerGeo(
            cc = ccClean,
            ip = ip?.takeIf { it.isNotBlank() },
            ts = System.currentTimeMillis(),
        )
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe(): kotlinx.serialization.json.JsonObject =
        this as kotlinx.serialization.json.JsonObject

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}

/** ISO-3166 alpha-2 -> regional-indicator flag emoji (null for invalid codes). */
fun countryCodeToFlagEmoji(code: String?): String? {
    if (code.isNullOrBlank()) return null
    val c = code.trim().uppercase()
    if (!Regex("^[A-Z]{2}$").matches(c)) return null
    return c.map { ch ->
        Character.toChars(0x1F1E6 - 'A'.code + ch.code)
    }.joinToString("") { String(it) }
}

/** Localized (Persian when the app locale allows) country name for a code. */
fun localizedCountryName(code: String?): String? = try {
    if (code.isNullOrBlank()) null
    else java.util.Locale("", code.trim().uppercase()).displayCountry.takeIf { it.isNotBlank() }
} catch (_: Throwable) {
    null
}
