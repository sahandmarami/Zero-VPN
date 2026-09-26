package com.zerovpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Base64

// ---------------------------------------------------------------------------
// Xray configuration generation from a share link (vmess/vless/trojan/ss)
// ---------------------------------------------------------------------------
object XrayConfig {

    val json = Json { encodeDefaults = true; prettyPrint = true }

    fun buildFullConfig(link: String, socksPort: Int, httpPort: Int): JsonObject? {
        val outbound = buildOutbound(link) ?: return null
        return buildJsonObject {
            put("log", buildJsonObject {
                put("loglevel", "warning")
                put("error", File(Store.dataDir, "xray-error.log").absolutePath)
            })
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("tag", "socks-in")
                    put("listen", "127.0.0.1")
                    put("port", socksPort)
                    put("protocol", "socks")
                    put("settings", buildJsonObject {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                })
                add(buildJsonObject {
                    put("tag", "http-in")
                    put("listen", "127.0.0.1")
                    put("port", httpPort)
                    put("protocol", "http")
                    put("settings", buildJsonObject { })
                })
            })
            put("outbounds", buildJsonArray {
                add(outbound)
                add(buildJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                })
                add(buildJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                })
            })
            put("routing", buildJsonObject {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "field")
                        put("ip", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("geoip:private")) })
                        put("outboundTag", "direct")
                    })
                })
            })
        }
    }

    // --- link → outbound ----------------------------------------------------
    fun buildOutbound(link: String): JsonObject? = try {
        when {
            link.startsWith("vmess://") -> vmess(link)
            link.startsWith("vless://") -> vless(link)
            link.startsWith("trojan://") -> trojan(link)
            link.startsWith("ss://") -> shadowsocks(link)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun vmess(link: String): JsonObject? {
        val b64 = link.removePrefix("vmess://").replace(Regex("\\s+"), "")
        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
        val body = try {
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        } catch (_: Throwable) {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }
        val j = json.parseToJsonElement(body).let { it as? JsonObject } ?: return null
        fun s(k: String) = j[k]?.toString()?.trim('"') ?: ""
        fun i(k: String) = j[k]?.toString()?.trim('"')?.toIntOrNull() ?: 0

        val net = s("net").ifBlank { "tcp" }
        val security = if (s("tls") == "tls") "tls" else "none"
        val stream = streamSettings(
            net = net,
            type = s("type"),
            host = s("host"),
            path = s("path"),
            serviceName = s("path"),          // grpc uses path field in some subs
            security = security,
            sni = s("sni").ifBlank { s("host") },
            alpn = s("alpn"),
            fp = s("fp"),
            pbk = "", sid = "", spx = "",
            allowInsecure = false,
        )
        return buildJsonObject {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", s("add"))
                        put("port", i("port"))
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("id", s("id"))
                                put("alterId", i("aid"))
                                put("security", s("scy").ifBlank { "auto" })
                                put("level", 0)
                            })
                        })
                    })
                })
            })
            put("streamSettings", stream)
            put("mux", buildJsonObject { put("enabled", false); put("concurrency", -1) })
        }
    }

    private fun vless(link: String): JsonObject {
        val uri = URI(link.replace(" ", "%20"))
        val q = query(uri.rawQuery)
        val userInfo = URLDecoder.decode(uri.rawUserInfo ?: "", "UTF-8")
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        val net = (q["type"] ?: q["network"] ?: "tcp").lowercase()
        val security = (q["security"] ?: if (port == 443) "tls" else "none").lowercase()
        val stream = streamSettings(
            net = net,
            type = q["headerType"] ?: "",
            host = q["host"] ?: "",
            path = q["path"] ?: "",
            serviceName = q["serviceName"] ?: "",
            security = security,
            sni = q["sni"] ?: q["host"] ?: host,
            alpn = q["alpn"] ?: "",
            fp = q["fp"] ?: "",
            pbk = q["pbk"] ?: "",
            sid = q["sid"] ?: "",
            spx = q["spx"] ?: "",
            allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
        )
        return buildJsonObject {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", host)
                        put("port", port)
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("id", userInfo)
                                put("encryption", "none")
                                put("flow", q["flow"] ?: "")
                                put("level", 0)
                            })
                        })
                    })
                })
            })
            put("streamSettings", stream)
            put("mux", buildJsonObject { put("enabled", false); put("concurrency", -1) })
        }
    }

    private fun trojan(link: String): JsonObject {
        val uri = URI(link.replace(" ", "%20"))
        val q = query(uri.rawQuery)
        val pass = URLDecoder.decode(uri.rawUserInfo ?: "", "UTF-8")
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        val net = (q["type"] ?: "tcp").lowercase()
        val stream = streamSettings(
            net = net,
            type = q["headerType"] ?: "",
            host = q["host"] ?: "",
            path = q["path"] ?: "",
            serviceName = q["serviceName"] ?: "",
            security = (q["security"] ?: "tls").lowercase(),
            sni = q["sni"] ?: q["peer"] ?: q["host"] ?: host,
            alpn = q["alpn"] ?: "",
            fp = q["fp"] ?: "",
            pbk = q["pbk"] ?: "",
            sid = q["sid"] ?: "",
            spx = q["spx"] ?: "",
            allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
        )
        return buildJsonObject {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", host)
                        put("port", port)
                        put("password", pass)
                        put("level", 0)
                    })
                })
            })
            put("streamSettings", stream)
        }
    }

    private fun shadowsocks(link: String): JsonObject {
        val body = link.removePrefix("ss://")
        val hash = body.indexOf('#')
        val main = if (hash >= 0) body.substring(0, hash) else body
        val name = if (hash >= 0) try {
            URLDecoder.decode(body.substring(hash + 1), "UTF-8").take(80)
        } catch (_: Throwable) { "ss" } else "ss"

        // Form 1: base64(method:password)@host:port?plugin=...
        // Form 2: base64(method:password@host:port)
        var method = ""; var password = ""; var host = ""; var port = 443
        val atIdx = main.lastIndexOf('@')
        if (atIdx > 0) {
            val userInfo = main.substring(0, atIdx)
            val server = main.substring(atIdx + 1)
            val decoded = tryDecodeBase64(userInfo) ?: userInfo
            val idx = decoded.indexOf(':')
            method = if (idx >= 0) decoded.substring(0, idx) else decoded
            password = if (idx >= 0) decoded.substring(idx + 1) else ""
            val qIdx = server.indexOf('?')
            val hostPort = if (qIdx >= 0) server.substring(0, qIdx) else server
            host = hostPort.substringBeforeLast(':')
            port = hostPort.substringAfterLast(':').toIntOrNull() ?: 443
        } else {
            val decoded = tryDecodeBase64(main) ?: main
            val m = Regex("^(.+?):(.+)@(.+):(\\d+)$").find(decoded)
            if (m != null) {
                method = m.groupValues[1]; password = m.groupValues[2]
                host = m.groupValues[3]; port = m.groupValues[4].toIntOrNull() ?: 443
            }
        }
        return buildJsonObject {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", host)
                        put("port", port)
                        put("method", method)
                        put("password", password)
                        put("ota", false)
                        put("level", 0)
                        put("name", name)
                    })
                })
            })
        }
    }

    private fun tryDecodeBase64(s: String): String? {
        val clean = s.replace(Regex("\\s+"), "")
        return try {
            String(Base64.getUrlDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4)), Charsets.UTF_8)
        } catch (_: Throwable) {
            try {
                String(Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4)), Charsets.UTF_8)
            } catch (_: Throwable) { null }
        }
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null
            else try {
                URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
            } catch (_: Throwable) { null }
        }.toMap()
    }

    private fun streamSettings(
        net: String, type: String, host: String, path: String, serviceName: String,
        security: String, sni: String, alpn: String, fp: String,
        pbk: String, sid: String, spx: String, allowInsecure: Boolean,
    ): JsonObject = buildJsonObject {
        put("network", when (net) {
            "h2", "h2c" -> "http"
            "splithttp" -> "xhttp"
            else -> net
        })
        put("security", security)
        if (security == "tls") {
            put("tlsSettings", buildJsonObject {
                put("serverName", sni)
                put("allowInsecure", allowInsecure)
                if (alpn.isNotBlank()) put("alpn", buildJsonArray { alpn.split(',').forEach { add(kotlinx.serialization.json.JsonPrimitive(it.trim())) } })
                if (fp.isNotBlank()) put("fingerprint", fp)
            })
        } else if (security == "reality") {
            put("realitySettings", buildJsonObject {
                put("serverName", sni)
                put("fingerprint", fp.ifBlank { "chrome" })
                put("publicKey", pbk)
                put("shortId", sid)
                if (spx.isNotBlank()) put("spiderX", spx)
            })
        }
        when (net) {
            "ws", "websocket" -> put("wsSettings", buildJsonObject {
                put("path", if (path.isBlank()) "/" else path)
                if (host.isNotBlank()) put("headers", buildJsonObject { put("Host", host) })
            })
            "grpc" -> put("grpcSettings", buildJsonObject {
                put("serviceName", serviceName)
                put("multiMode", false)
            })
            "h2", "http", "h2c" -> put("httpSettings", buildJsonObject {
                put("path", if (path.isBlank()) "/" else path)
                if (host.isNotBlank()) put("host", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(host)) })
            })
            "httpupgrade" -> put("httpupgradeSettings", buildJsonObject {
                put("path", if (path.isBlank()) "/" else path)
                if (host.isNotBlank()) put("host", host)
            })
            "xhttp", "splithttp" -> put("xhttpSettings", buildJsonObject {
                put("path", if (path.isBlank()) "/" else path)
                if (host.isNotBlank()) put("host", host)
            })
            "tcp" -> if (type.equals("http", true)) put("tcpSettings", buildJsonObject {
                put("header", buildJsonObject {
                    put("type", "http")
                    put("request", buildJsonObject {
                        put("version", "1.1")
                        put("method", "GET")
                        put("path", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(path.ifBlank { "/" })) })
                        put("headers", buildJsonObject {
                            put("Host", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(host.ifBlank { sni.ifBlank { "www.example.com" } })) })
                            put("User-Agent", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")) })
                            put("Accept-Encoding", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("gzip, deflate")) })
                            put("Connection", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("keep-alive")) })
                            put("Pragma", "no-cache")
                        })
                    })
                })
            })
        }
    }
}

// ---------------------------------------------------------------------------
// Xray process runner
// ---------------------------------------------------------------------------
object Engine {

    @Volatile private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    fun start(configFile: File): Boolean {
        stop()
        val exeName = if (Store.isWindows) "xray.exe" else "xray"
        val exe = File(Store.coreDir, exeName)
        if (!exe.isFile) return false
        return try {
            val pb = ProcessBuilder(exe.absolutePath, "run", "-c", configFile.absolutePath)
                .directory(Store.coreDir)
                .redirectErrorStream(true)
                .redirectOutput(RedirectMode.file(Store.logFile))
            Store.logFile.parentFile?.mkdirs()
            if (Store.logFile.exists()) Store.logFile.writeText("")
            val p = pb.start()
            process = p
            Thread.sleep(600)
            p.isAlive
        } catch (_: Throwable) {
            false
        }
    }

    /** java.lang.ProcessBuilder.Redirect helper — type-safe wrapper. */
    private object RedirectMode {
        fun file(f: File): ProcessBuilder.Redirect = ProcessBuilder.Redirect.to(f)
    }

    fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500)
                    return true
                }
            } catch (_: Throwable) { }
            Thread.sleep(250)
        }
        return false
    }

    fun stop() {
        try { process?.destroy() } catch (_: Throwable) { }
        try { process?.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) { }
        if (process?.isAlive == true) try { process?.destroyForcibly() } catch (_: Throwable) { }
        process = null
    }
}

// ---------------------------------------------------------------------------
// Windows system proxy (WinINET) via reg.exe + rundll32 refresh
// ---------------------------------------------------------------------------
object SysProxy {

    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    private val OVERRIDE = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;" +
        "172.2?.*;172.30.*;172.31.*;192.168.*;<local>"

    fun enable(httpPort: Int): Boolean = run {
        val ok =
            reg("add", KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f") &&
            reg("add", KEY, "/v", "ProxyServer", "/t", "REG_SZ", "/d", "127.0.0.1:$httpPort", "/f") &&
            reg("add", KEY, "/v", "ProxyOverride", "/t", "REG_SZ", "/d", OVERRIDE, "/f")
        refresh()
        ok
    }

    fun disable(): Boolean = run {
        refresh()
        reg("add", KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f")
    }

    private fun reg(vararg args: String): Boolean = try {
        if (!Store.isWindows) true
        else ProcessBuilder("reg", *args).start().waitFor() == 0
    } catch (_: Throwable) { false }

    private fun refresh() {
        if (!Store.isWindows) return
        try {
            ProcessBuilder("rundll32", "wininet.dll,InternetSetOption", "0", "39", "0", "0")
                .start().waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            ProcessBuilder("rundll32", "wininet.dll,InternetSetOption", "0", "37", "0", "0")
                .start().waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { }
    }
}

// ---------------------------------------------------------------------------
// Real-ping through the local SOCKS inbound (gstatic generate_204)
// ---------------------------------------------------------------------------
object PingTest {

    fun viaSocks(
        port: Int,
        timeoutMs: Int = 7000,
        url: String = "https://www.gstatic.com/generate_204",
    ): Long = try {
        val start = System.currentTimeMillis()
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val con = URL(url).openConnection(proxy) as HttpURLConnection
        con.connectTimeout = timeoutMs
        con.readTimeout = timeoutMs
        con.instanceFollowRedirects = false
        con.setRequestProperty("User-Agent", "ZeroVPN/$APP_VERSION")
        val code = con.responseCode
        con.disconnect()
        if (code in 200..399) System.currentTimeMillis() - start else -1
    } catch (_: Throwable) {
        -1
    }

    /** Direct TCP handshake to the server (used by "test all pings" while offline). */
    fun tcp(host: String, port: Int, timeoutMs: Int = 3000): Long = try {
        val start = System.currentTimeMillis()
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
        }
        System.currentTimeMillis() - start
    } catch (_: Throwable) {
        -1
    }

    /**
     * Deadline-bounded TCP ping used by the batch test. Hardened against the
     * "some servers time out on Windows but ping fine on Android" report:
     *
     * 1. ALL resolved addresses are tried, IPv4 first — the plain path used
     *    getByName, which on v6-capable Windows boxes happily returns an AAAA
     *    address whose routing is broken; the socket then hangs on a dead v6
     *    route and the whole ping reports timeout even though the v4 address
     *    is perfectly reachable (Android's resolver typically answers with
     *    the v4 address, which is exactly why the phone worked).
     * 2. When the system resolver returns nothing at all (poisoned/dead ISP
     *    DNS — common for filtered server domains), a DNS-over-HTTPS JSON
     *    lookup (Google → Cloudflare) provides a second resolution path.
     * 3. Every step runs behind a hard withTimeoutOrNull deadline; a stuck
     *    connect or lookup is abandoned (-1) and can never stall the batch.
     * Never blocks longer than timeoutMs + 2.5 s per server.
     */
    suspend fun tcpBounded(host: String, port: Int, timeoutMs: Int = 5000): Long =
        withTimeoutOrNull(timeoutMs + 2500L) {
            var addrs = withContext(Dispatchers.IO) { resolveAll(host) }
            if (addrs.isEmpty()) {
                addrs = withContext(Dispatchers.IO) { dohResolve(host) }
            }
            if (addrs.isEmpty()) return@withTimeoutOrNull -1L
            // IPv4 first — dead v6 routes are the #1 false-timeout cause.
            val ordered = addrs.filter { it is java.net.Inet4Address } +
                    addrs.filterNot { it is java.net.Inet4Address }
            for (addr in ordered) {
                val ms = withTimeoutOrNull(timeoutMs.toLong()) {
                    val start = System.currentTimeMillis()
                    try {
                        Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) }
                        System.currentTimeMillis() - start
                    } catch (_: Throwable) { -1L }
                } ?: -1L
                if (ms > 0) return@withTimeoutOrNull ms
            }
            -1L
        } ?: -1L

    private fun resolveAll(host: String): List<java.net.InetAddress> = try {
        java.net.InetAddress.getAllByName(host).toList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** Best-effort DNS-over-HTTPS (JSON) — only used when system DNS fails. */
    private fun dohResolve(host: String): List<java.net.InetAddress> {
        val q = java.net.URLEncoder.encode(host, "UTF-8")
        val endpoints = listOf(
            "https://dns.google/resolve?name=$q&type=A",
            "https://cloudflare-dns.com/dns-query?name=$q&type=A",
        )
        for (url in endpoints) {
            try {
                val body = viaSocksDohGet(url) ?: continue
                val answers = Regex("\"data\"\\s*:\\s*\"(\\d{1,3}(?:\\.\\d{1,3}){3})\"")
                    .findAll(body).map { it.groupValues[1] }.toList()
                val ips = answers.mapNotNull {
                    runCatching { java.net.InetAddress.getByName(it) }.getOrNull()
                }
                if (ips.isNotEmpty()) return ips
            } catch (_: Throwable) { }
        }
        return emptyList()
    }

    private fun viaSocksDohGet(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3500
        conn.readTimeout = 3500
        conn.setRequestProperty("Accept", "application/dns-json")
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
}

// ---------------------------------------------------------------------------
// Server address extraction from a share link (host + port for TCP pings)
// ---------------------------------------------------------------------------
object ServerInfo {

    fun hostPort(link: String): Pair<String, Int>? = try {
        when {
            link.startsWith("vmess://") -> vmessAddr(link)
            link.startsWith("vless://") || link.startsWith("trojan://") ->
                Regex("://[^@/]+@([^:/?#]+):(\\d+)").find(link)?.let {
                    (it.groupValues[1] to (it.groupValues[2].toIntOrNull() ?: 443))
                }
            link.startsWith("ss://") -> ssAddr(link)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun decodeB64(s: String): String? {
        val clean = s.replace(Regex("\\s+"), "")
        return try {
            String(java.util.Base64.getUrlDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4)), Charsets.UTF_8)
        } catch (_: Throwable) {
            try {
                String(java.util.Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4)), Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun vmessAddr(link: String): Pair<String, Int>? {
        val body = decodeB64(link.removePrefix("vmess://")) ?: return null
        val j = Json.parseToJsonElement(body).let { it as? JsonObject } ?: return null
        fun s(k: String) = j[k]?.toString()?.trim('"') ?: ""
        val host = s("add")
        val port = s("port").toIntOrNull() ?: 443
        return if (host.isNotBlank()) host to port else null
    }

    private fun ssAddr(link: String): Pair<String, Int>? {
        val main = link.removePrefix("ss://").substringBefore('#')
        val atIdx = main.lastIndexOf('@')
        return if (atIdx > 0) {
            val server = main.substring(atIdx + 1).substringBefore('?')
            val host = server.substringBeforeLast(':')
            val port = server.substringAfterLast(':').toIntOrNull() ?: 443
            if (host.isNotBlank()) host to port else null
        } else {
            val decoded = decodeB64(main) ?: return null
            Regex("^(.+?):(.+)@(.+):(\\d+)$").find(decoded)?.let {
                (it.groupValues[3] to (it.groupValues[4].toIntOrNull() ?: 443))
            }
        }
    }
}
