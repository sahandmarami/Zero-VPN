package com.zerovpn.desktop

import androidx.compose.runtime.mutableStateMapOf
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
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Zero VPN geo lookup — v2.3.0, V2Box-grade country resolution.
 *
 * Why v2.2.0 showed WRONG countries on Iranian machines:
 *  1. the host was resolved with the SYSTEM resolver — Iranian DNS poisons
 *     filtered server domains (10.10.34.36-class answers), so the "server IP"
 *     was a fake, usually landing in Iran → the wrong flag next to a server
 *     that actually lives in Germany/Netherlands/…;
 *  2. the FIRST geo source was plain-HTTP ip-api.com — injectable/hijackable
 *     on Iranian ISPs, again feeding wrong countries;
 *  3. nothing was pinned to NO_PROXY, so with a stale Windows system proxy
 *     registered every lookup silently died (v2.2.0 fixed exactly that class
 *     for subscriptions/DoH/update-check but Geo.kt was missed).
 *
 * The pipeline now (like V2Box, which showed the real flag + IP):
 *  1. resolve the host with DNS-over-HTTPS FIRST (IP-hosted endpoints, no
 *     DNS needed) — the poisoned system resolver only runs as fallback;
 *     while the tunnel is up the DoH query itself goes through the tunnel,
 *     because 1.1.1.1/8.8.8.8 are filtered on the very networks this app
 *     serves;
 *  2. query HTTPS-only geo sources, pinned NO_PROXY (or through the tunnel
 *     when connected — the geo APIs are commonly blocked too);
 *  3. fall back to a REMARK hint: real subscriptions name their servers
 *     ("🇩🇪 Germany 12", "DE-01", "آلمان …") — parsed flag emoji / country
 *     words / ISO tokens give a sensible flag when every geo API is
 *     unreachable. Marked `hint` so a later successful lookup overwrites it.
 */

object GeoLookup {

    @Serializable
    data class ServerGeo(
        val cc: String? = null,
        val ip: String? = null,
        val ts: Long = 0,
        // v2.3.0: country came from the remark name (geo APIs unreachable),
        // not from the real IP — safe to re-resolve later.
        val hint: Boolean = false,
    )

    private const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000 // one week

    private val json = Json { ignoreUnknownKeys = true }
    private val sessionCache = ConcurrentHashMap<String, ServerGeo?>()
    private val failedHosts = ConcurrentHashMap.newKeySet<String>()

    /** Hosts with an in-flight lookup — dedupes launches (plain set, not state). */
    private val lookingUp = ConcurrentHashMap.newKeySet<String>()

    /** host -> geo, observed by the UI (snapshot state map — every write is
     *  posted to the EDT through [postStateWrite], never during composition
     *  and never from an IO worker thread — v2.0.1 crash-class fix). */
    val byHost = mutableStateMapOf<String, ServerGeo>()

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
                    if (geo != null) postStateWrite { byHost[host] = geo }
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

    /** Kicks off (or reuses) a background lookup for the given host.
     *  `nameHint` = the profile's remark — used only when every geo source
     *  fails (blocked networks), so the flag at least matches the intent.
     *  All writes go through [postStateWrite] (deferred EDT) — never inside
     *  a running composition (v2.0.1 crash class). */
    fun ensureLookup(host: String?, nameHint: String? = null, force: Boolean = false) {
        val h = host?.trim() ?: return
        if (h.isEmpty() || isPrivateHost(h)) return
        if (force) {
            // v2.5.0 — V2Box parity: while the tunnel was down a lookup may
            // have failed or only produced a remark hint; once the tunnel
            // carries traffic the same host gets a REAL re-lookup (the geo
            // sources and DoH ride the tunnel). A real cached result stays.
            failedHosts.remove(h)
            val cached = sessionCache[h]
            if (cached != null && cached.hint != true) return
            sessionCache.remove(h)
            lookingUp.remove(h)
        }
        if (lookingUp.contains(h)) return
        if (h in failedHosts) return
        val cached = sessionCache[h]
        if (cached != null) {
            if (byHost[h] != cached) postStateWrite { byHost[h] = cached }
            return
        }
        if (sessionCache.containsKey(h)) return // null-marked: failed this session
        lookingUp.add(h)
        Store.io.launch {
            val geo = withContext(Dispatchers.IO) { lookupHost(h) }
            val resolved = geo ?: CountryHint.fromName(nameHint)?.let { cc ->
                ServerGeo(cc = cc, ip = null, ts = System.currentTimeMillis(), hint = true)
            }
            lookingUp.remove(h)
            if (resolved != null) {
                sessionCache[h] = resolved
                postStateWrite { byHost[h] = resolved }
                saveCache()
            } else {
                sessionCache[h] = null
                failedHosts.add(h)
            }
        }
    }

    /**
     * v2.5.0 — re-run every lookup that failed or only produced a remark
     * hint. Called the moment the tunnel comes up AND actually carries
     * traffic (V2Box resolves its flags through the working tunnel; on
     * Iranian networks the geo APIs are blocked without it, which is why
     * flags previously stayed wrong until the next full restart).
     */
    fun refreshUnresolved() {
        val hosts = (sessionCache.filterValues { it == null || it.hint == true }.keys +
                failedHosts.toSet()).toList()
        hosts.forEach { h ->
            failedHosts.remove(h)
            sessionCache.remove(h)
            lookingUp.remove(h)
            ensureLookup(h)
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

    /**
     * v2.3.0 — DoH FIRST, system DNS as fallback. The system resolver is the
     * poisoned path on Iranian ISPs and produced the wrong-country class.
     * While connected, the DoH query rides the tunnel (1.1.1.1/8.8.8.8 are
     * filtered locally); otherwise it dials direct with its IP-hosted
     * endpoints that need no working DNS at all.
     */
    private fun resolveHost(host: String): String? {
        // Tunnel up → the DoH query rides the tunnel (1.1.1.1 / 8.8.8.8 are
        // filtered on the very networks this app serves). Direct otherwise.
        val socks = if (Store.status == ConnStatus.CONNECTED) Store.socksPort else null
        dohResolve(host, socks).firstOrNull()?.let { return it }
        return systemResolve(host)
    }

    fun dohResolve(host: String, socksPort: Int?): List<String> =
        PingTest.dohResolve(host, socksPort).mapNotNull { it.hostAddress }

    private fun systemResolve(host: String): String? = try {
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

    /**
     * Queries several free geo-IP sources until one answers. HTTPS-only
     * (plain-HTTP ip-api was injectable on the target networks), NO_PROXY
     * pinned, or through the tunnel when it is up — these APIs are commonly
     * blocked together with the rest of the internet on the user's ISP.
     */
    private fun lookupIp(ip: String): ServerGeo? {
        val socks = if (Store.status == ConnStatus.CONNECTED) Store.socksPort else null
        val direct = listOf(
            "https://ipwho.is/$ip",
            "https://api.ip.sb/geoip/$ip",
            "https://ipapi.co/$ip/json/",
            "https://freeipapi.com/api/json/$ip",
            "https://reallyfreegeoip.org/json/$ip",
            "https://ipinfo.io/$ip/json",
        )
        val sources = direct
        sources.forEach { url ->
            try {
                val body = httpGet(url, socks) ?: return@forEach
                parseGeoBody(body)?.let { return it }
            } catch (_: Throwable) { }
        }
        return null
    }

    /** Pure parser — self-tested. Tolerates every source's field naming. */
    internal fun parseGeoBody(body: String): ServerGeo? {
        val root = try { json.parseToJsonElement(body).jsonObjectSafe() } catch (_: Throwable) { return null }
        val cc = root.str("countryCode") ?: root.str("country_code") ?: root.str("cc")
            ?: root.str("countryCode2") ?: root.str("country")
            ?.takeIf { Regex("^[A-Z]{2}$").matches(it) }
        val country = root.str("country") ?: root.str("country_name")
        val ip = root.str("query") ?: root.str("ip") ?: root.str("ipAddress") ?: root.str("ip_addr")
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

    private fun httpGet(url: String, socksPort: Int?, timeoutMs: Int = 6000): String? = try {
        val proxy = if (socksPort != null)
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        else Proxy.NO_PROXY
        val conn = URL(url).openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        // freeipapi answers its own first hop with a 307 (Cloudflare) — follow
        // same-scheme redirects instead of discarding the source.
        conn.instanceFollowRedirects = true
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

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe(): kotlinx.serialization.json.JsonObject =
        this as kotlinx.serialization.json.JsonObject

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}

// ---------------------------------------------------------------------------
// CountryHint — remark-name → country-code fallback (v2.3.0)
// ---------------------------------------------------------------------------

/**
 * Parses the subscription remark for a country. Real subscriptions name
 * servers "🇩🇪 Germany 12", "DE-01", "آلمان#3", "Dubai 2" — when every geo
 * API is unreachable this still yields the intended flag, matching what
 * V2Box shows from the same remarks.
 */
object CountryHint {

    /** lowercase word → ISO-2 (countries + a few city aliases subs use).
     *  NOTE: latin keys that double as common English words are deliberately
     *  ABSENT (no/is/id/am/it/in/at/be/my — "No.1 server", "this is fast",
     *  "my tunnel" would all false-positive). Those countries stay reachable
     *  through their Persian names, flag emoji and UPPERCASE ISO tokens. */
    private val names: Map<String, String> = buildMap {
        fun put(vararg keys: String, cc: String) { keys.forEach { put(it.lowercase(), cc) } }
        put("de", "germany", "germania", "deutschland", "alman", "المان", "آلمان", "فرانکفورت", "frankfurt", cc = "DE")
        put("nl", "netherlands", "holland", "hollanda", "holand", "هلند", "هالند", "amsterdam", "آمستردام", cc = "NL")
        put("us", "usa", "united states", "america", "امریکا", "آمریکا", "امريكا", "los angeles", "dallas", "miami", "new york", "سنت لوئیس", cc = "US")
        put("uk", "gb", "united kingdom", "britain", "england", "انگلیس", "انگلستان", "بریتانیا", "لندن", "london", cc = "GB")
        put("tr", "turkey", "turkiye", "ترکیه", "استانبول", "استنبول", "istanbul", "izmir", cc = "TR")
        put("fr", "france", "فرانسه", "پاریس", "paris", cc = "FR")
        put("ca", "canada", "کانادا", "toronto", "montreal", cc = "CA")
        put("ae", "uae", "emirates", "dubai", "امارات", "دبی", "دوبی", "abu dhabi", cc = "AE")
        put("ru", "russia", "روسیه", "مسکو", "moscow", cc = "RU")
        put("ua", "ukraine", "اوکراین", "کیف", "kyiv", "kiev", cc = "UA")
        put("se", "sweden", "سوئد", "استکهلم", "stockholm", cc = "SE")
        put("ch", "switzerland", "سوئیس", "سوییس", "زوریخ", "zurich", cc = "CH")
        put("austria", "اتریش", "وین", "vienna", "wien", cc = "AT")
        put("finland", "فنلاند", "helsinki", cc = "FI")
        put("denmark", "دانمارک", "copenhagen", cc = "DK")
        put("belgium", "بلژیک", "brussels", cc = "BE")
        put("italy", "ایتالیا", "milan", "rome", cc = "IT")
        put("spain", "اسپانیا", "مادرید", "madrid", cc = "ES")
        put("pl", "poland", "لهستان", "warsaw", cc = "PL")
        put("sg", "singapore", "سنگاپور", cc = "SG")
        put("jp", "japan", "ژاپن", "توکیو", "tokyo", cc = "JP")
        put("kr", "korea", "south korea", "کره", "سئول", "seoul", cc = "KR")
        put("hk", "hong kong", "هنگ کنگ", "هنگکنگ", cc = "HK")
        put("cn", "china", "چین", cc = "CN")
        put("india", "هند", cc = "IN")
        put("au", "australia", "استرالیا", "sydney", cc = "AU")
        put("br", "brazil", "برزیل", cc = "BR")
        put("ie", "ireland", "ایرلند", "dublin", cc = "IE")
        put("pt", "portugal", "پرتغال", cc = "PT")
        put("ro", "romania", "رومانی", "bucharest", cc = "RO")
        put("md", "moldova", "مولداوی", cc = "MD")
        put("lv", "latvia", "لتونی", cc = "LV")
        put("lt", "lithuania", "لیتوانی", cc = "LT")
        put("ee", "estonia", "استونی", cc = "EE")
        put("cz", "czech", "چک", "prague", cc = "CZ")
        put("sk", "slovakia", "اسلواکی", cc = "SK")
        put("hu", "hungary", "مجارستان", "budapest", cc = "HU")
        put("bg", "bulgaria", "بلغارستان", cc = "BG")
        put("gr", "greece", "یونان", cc = "GR")
        put("cy", "cyprus", "قبرس", cc = "CY")
        put("lu", "luxembourg", "لوکزامبورگ", cc = "LU")
        put("iceland", "ایسلند", cc = "IS")
        put("norway", "نروژ", "oslo", cc = "NO")
        put("sa", "saudi", "arabia", "عربستان", cc = "SA")
        put("qa", "qatar", "قطر", cc = "QA")
        put("kw", "kuwait", "کویت", cc = "KW")
        put("ir", "iran", "ایران", cc = "IR")
        put("argentina", "آرژانتین", cc = "AR")
        put("mx", "mexico", "مکزیک", cc = "MX")
        put("za", "south africa", "آفریقای جنوبی", cc = "ZA")
        put("kz", "kazakhstan", "قزاقستان", cc = "KZ")
        put("ge", "georgia", "گرجستان", "tbilisi", cc = "GE")
        put("armenia", "ارمنستان", "yerevan", cc = "AM")
        put("az", "azerbaijan", "آذربایجان", "baku", cc = "AZ")
        put("malaysia", "مالزی", cc = "MY")
        put("vn", "vietnam", "ویتنام", cc = "VN")
        put("th", "thailand", "تایلند", cc = "TH")
        put("ph", "philippines", "فیلیپین", cc = "PH")
        put("indonesia", "اندونزی", cc = "ID")
    }

    /** Sorted longest-first so "united states" wins over "us". */
    private val keysByLength = names.keys.sortedByDescending { it.length }

    /** remark → ISO-2 or null. Order: flag emoji → country/city word → ISO token. */
    fun fromName(name: String?): String? {
        val n = name?.trim() ?: return null
        if (n.isEmpty()) return null
        // 1) flag emoji (regional indicator pair)
        val high = n.codePoints().toArray()
        var i = 0
        while (i < high.size - 1) {
            val a = high[i]; val b = high[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                // a flag emoji IS a regional-indicator ISO pair by construction
                return String(charArrayOf(('A' + (a - 0x1F1E6)).toChar(), ('A' + (b - 0x1F1E6)).toChar()))
            }
            i++
        }
        // 2) known country/city words (longest match first). Latin keys ≤3
        //    chars need word boundaries ("Node 1" must not match "de").
        val lower = n.lowercase()
        for (k in keysByLength) {
            if (k.length < 2) continue
            val hit = if (k.length <= 3 && k.all { it in 'a'..'z' })
                Regex("(?<![a-z])" + Regex.escape(k) + "(?![a-z])").containsMatchIn(lower)
            else
                lower.contains(k)
            if (hit) return names[k]
        }
        // 3) bare UPPERCASE ISO token in the ORIGINAL name: "DE-01", "US #3",
        //    "(NL)". Not uppercased first — "this is fast" must stay silent.
        Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])").findAll(n).forEach { m ->
            val cc = m.groupValues[1]
            if (names.values.contains(cc)) return cc
        }
        return null
    }
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

/** Persian country names for the flag chips (JVM displayCountry has no fa
 *  data on many Windows boxes — an explicit map is deterministic). */
private val faCountryNames = mapOf(
    "DE" to "آلمان", "NL" to "هلند", "US" to "آمریکا", "GB" to "انگلیس",
    "TR" to "ترکیه", "FR" to "فرانسه", "CA" to "کانادا", "AE" to "امارات",
    "RU" to "روسیه", "UA" to "اوکراین", "SE" to "سوئد", "CH" to "سوئیس",
    "AT" to "اتریش", "FI" to "فنلاند", "DK" to "دانمارک", "NO" to "نروژ",
    "BE" to "بلژیک", "IT" to "ایتالیا", "ES" to "اسپانیا", "PL" to "لهستان",
    "SG" to "سنگاپور", "JP" to "ژاپن", "KR" to "کره جنوبی", "HK" to "هنگ‌کنگ",
    "CN" to "چین", "IN" to "هند", "AU" to "استرالیا", "BR" to "برزیل",
    "IE" to "ایرلند", "PT" to "پرتغال", "RO" to "رومانی", "MD" to "مولداوی",
    "LV" to "لتونی", "LT" to "لیتوانی", "EE" to "استونی", "CZ" to "چک",
    "SK" to "اسلواکی", "HU" to "مجارستان", "BG" to "بلغارستان", "GR" to "یونان",
    "CY" to "قبرس", "LU" to "لوکزامبورگ", "IS" to "ایسلند", "SA" to "عربستان",
    "QA" to "قطر", "KW" to "کویت", "IR" to "ایران", "AR" to "آرژانتین",
    "MX" to "مکزیک", "ZA" to "آفریقای جنوبی", "KZ" to "قزاقستان", "GE" to "گرجستان",
    "AM" to "ارمنستان", "AZ" to "آذربایجان", "MY" to "مالزی", "VN" to "ویتنام",
    "TH" to "تایلند", "PH" to "فیلیپین", "ID" to "اندونزی", "EU" to "اروپا",
    "IL" to "اسرائیل", "NZ" to "نیوزیلند", "CL" to "شیلی", "CO" to "کلمبیا",
)

/** Localized country name: Persian map first, JVM displayCountry fallback. */
fun localizedCountryName(code: String?): String? {
    if (code.isNullOrBlank()) return null
    val c = code.trim().uppercase()
    faCountryNames[c]?.let { return it }
    return try {
        java.util.Locale("", c).displayCountry.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}
