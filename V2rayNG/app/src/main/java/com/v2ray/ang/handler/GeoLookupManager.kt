package com.v2ray.ang.handler

import android.util.Patterns
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Zero VPN: resolves the country + IP for server hosts so the UI can show a
 * flag, country and exit IP next to every server name.
 *
 * The lookup is a plain (non-proxied) DNS resolve followed by a geo-IP query.
 * Results are cached persistently (MMKV) keyed by host with a TTL, so the
 * server list fills in instantly on later launches.
 */
object GeoLookupManager {

    data class ServerGeo(
        val countryCode: String?,
        val countryName: String?,
        val ipAddress: String?,
    )

    private const val CACHE_PREFIX = "cache_geo_"
    private const val CACHE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000 // one week

    /** Session-level negative cache so dead hosts are not retried every reload. */
    private val failedHosts = ConcurrentHashMap.newKeySet<String>()

    // ------------------------------------------------------------------ API

    /**
     * Resolves a host (domain or IP literal) to its geo information.
     * Returns null when the host is private/unresolvable or every source failed.
     */
    suspend fun lookupHostCached(host: String): ServerGeo? {
        val h = host.trim()
        if (h.isEmpty() || isPrivateHost(h) || failedHosts.contains(h)) return null

        cachedGeo(h)?.let { return it }

        return withContext(Dispatchers.IO) {
            val ip = if (isIpLiteral(h)) h else resolveHost(h)
            if (ip.isNullOrBlank()) {
                failedHosts.add(h)
                return@withContext null
            }
            val geo = lookupIp(ip)
            if (geo != null) {
                storeCache(h, geo)
                failedHosts.remove(h)
            } else {
                failedHosts.add(h)
            }
            geo
        }
    }

    // -------------------------------------------------------------- helpers

    fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Patterns.IP_ADDRESS.matcher(host).matches()

    /** Private / loopback / link-local targets carry no meaningful geo info. */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan")) return true
        if (!GeoLookupManager.isIpLiteral(h)) return false
        val v4 = h.split('.').takeIf { it.size == 4 } ?: return true // v6 literals: skip
        val o = v4.mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return true
        return when {
            o[0] == 10 || o[0] == 127 || o[0] == 0 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 169 && o[1] == 254 -> true
            o[0] == 100 && o[1] in 64..127 -> true // CGNAT
            else -> false
        }
    }

    private suspend fun resolveHost(host: String): String? = try {
        withTimeoutOrNull(4500L) {
            withContext(Dispatchers.IO) {
                InetAddress.getByName(host).hostAddress?.takeIf { it.isNotBlank() }
            }
        }
    } catch (e: Exception) {
        LogUtil.d(AppConfig.TAG, "GeoLookup: DNS resolve failed for $host")
        null
    }

    // ----------------------------------------------------------- geo sources

    /** Queries several free geo-IP sources until one answers. */
    private fun lookupIp(ip: String): ServerGeo? {
        val sources = listOf(
            "http://ip-api.com/json/$ip?fields=status,country,countryCode,query",
            "https://ipwho.is/$ip",
            "https://api.ip.sb/geoip/$ip",
        )
        sources.forEach { url ->
            try {
                val body = HttpUtil.getUrlContent(
                    com.v2ray.ang.dto.UrlContentRequest(url = url, timeout = 6000)
                ) ?: return@forEach
                parseGeoResponse(body)?.let { return it }
            } catch (e: Exception) {
                LogUtil.d(AppConfig.TAG, "GeoLookup: source failed ($url): ${e.message}")
            }
        }
        return null
    }

    /** Extracts country + IP from any of the supported response shapes. */
    private fun parseGeoResponse(body: String): ServerGeo? {
        val root = JsonUtil.parseString(body) ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject

        // ip-api / ipwho.is answer "success": false for failures
        val success = obj.get("success")?.takeIf { it.isJsonPrimitive }?.asString
        if (success != null && success.equals("false", ignoreCase = true)) return null

        val cc = listOf("countryCode", "country_code", "location.country_code")
            .firstNotNullOfOrNull { flatString(obj, it) }
        val country = listOf("country", "country_name")
            .firstNotNullOfOrNull { flatString(obj, it) }
        val ip = listOf("query", "ip", "ip_addr", "clientIp")
            .firstNotNullOfOrNull { flatString(obj, it) }

        val ccClean = cc?.trim()?.uppercase()?.takeIf { Regex("^[A-Z]{2}$").matches(it) }
        if (ccClean == null && ip == null) return null
        return ServerGeo(
            countryCode = ccClean,
            countryName = country?.takeIf { it.isNotBlank() },
            ipAddress = ip?.takeIf { it.isNotBlank() },
        )
    }

    private fun flatString(obj: com.google.gson.JsonObject, path: String): String? {
        var node: com.google.gson.JsonElement = obj
        for (part in path.split('.')) {
            node = node.takeIf { it.isJsonObject }?.asJsonObject?.get(part) ?: return null
        }
        return node.takeIf { it.isJsonPrimitive }?.asString
    }

    // ---------------------------------------------------------------- cache

    fun cachedGeo(host: String): ServerGeo? {
        val raw = MmkvManager.decodeSettingsString(CACHE_PREFIX + host.trim()) ?: return null
        val parts = raw.split('|')
        if (parts.size < 3) return null
        val ts = parts[2].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - ts > CACHE_TTL_MILLIS) return null
        val cc = parts[0].takeIf { it.isNotBlank() }
        return ServerGeo(
            countryCode = cc,
            countryName = null,
            ipAddress = parts[1].takeIf { it.isNotBlank() },
        )
    }

    private fun storeCache(host: String, geo: ServerGeo) {
        val value = "${geo.countryCode.orEmpty()}|${geo.ipAddress.orEmpty()}|" +
                System.currentTimeMillis()
        MmkvManager.encodeSettings(CACHE_PREFIX + host.trim(), value)
    }
}
