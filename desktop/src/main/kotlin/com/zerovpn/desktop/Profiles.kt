package com.zerovpn.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
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
    /**
     * v2.1.0 — resilient subscription fetch for real-world (Iranian) networks.
     *
     * A single direct HTTPS GET is no longer the whole story: ISPs poison DNS
     * for filtered hosts and SNI-reset subscription domains, so the fetcher
     * walks a strategy ladder and keeps the first success:
     *
     *   1. through the running tunnel (SOCKS 127.0.0.1:port) — exactly the
     *      "subscription through proxy" behaviour v2rayNG has, most reliable
     *      whenever the VPN is already up;
     *   2. direct HTTPS (the old behaviour);
     *   3. direct HTTPS to a DoH-resolved IP (1.1.1.1/1.0.0.1 JSON DoH needs
     *      no working DNS at all) keeping the original SNI + Host header —
     *      defeats DNS poisoning;
     *   4. plain HTTP (some providers ship http:// subs).
     *
     * Every failure is recorded; the final error is a compact Persian summary
     * instead of a raw exception class name.
     */
    fun fetchSub(url: String, socksPort: Int? = null): SubFetchResult {
        val errors = ArrayList<String>()
        val strategies = buildList {
            if (socksPort != null) add("tunnel")
            add("direct")
            if (url.startsWith("https://", true)) add("doh-ip")
            if (url.startsWith("https://", true)) add("plain-http")
        }
        for (strategy in strategies) {
            try {
                val (body, info) = when (strategy) {
                    "tunnel" -> fetchWithInfo(url, socksPort = socksPort)
                    "direct" -> fetchWithInfo(url)
                    "doh-ip" -> fetchViaDohIp(url)
                    else -> fetchWithInfo(url.replaceFirst("https://", "http://", true))
                }
                val links = parseBody(body)
                if (links.isEmpty() && info == null) {
                    errors.add("${strategyLabel(strategy)}: هیچ سروری در پاسخ نبود")
                    continue
                }
                val fresh = links.map { p ->
                    ProfileRec(
                        id = subId(url, p.link),
                        name = p.name,
                        proto = p.proto,
                        link = p.link,
                        sub = url,
                    )
                }.distinctBy { it.id }
                return SubFetchResult(url = url, profiles = fresh, info = info)
            } catch (t: Throwable) {
                errors.add("${strategyLabel(strategy)}: ${humanizeError(t)}")
            }
        }
        return SubFetchResult(
            url = url,
            profiles = emptyList(),
            info = null,
            error = errors.joinToString(" | "),
        )
    }

    private fun strategyLabel(s: String): String = when (s) {
        "tunnel" -> "از طریق تونل"
        "direct" -> "مستقیم"
        "doh-ip" -> "با DNS جایگزین"
        else -> "HTTP ساده"
    }

    /** Compact, user-facing reason for a failed fetch attempt. */
    fun humanizeError(t: Throwable): String = when {
        t is java.net.UnknownHostException -> "دامنه پیدا نشد (DNS مسدود یا خراب)"
        t is java.net.SocketTimeoutException -> "مهلت اتصال تمام شد"
        t is java.net.ConnectException -> "اتصال برقرار نشد"
        t is javax.net.ssl.SSLException -> "خطای TLS (${(t.message ?: "").take(50)})"
        t.message?.startsWith("HTTP ") == true -> t.message ?: "HTTP error"
        else -> t.message?.take(80) ?: t.javaClass.simpleName
    }

    /**
     * Fetch the URL by TCP-connecting to a DoH-resolved IP while keeping the
     * original Host header + SNI. Defeats poisoned/censored local DNS for the
     * subscription host. Requires https://.
     */
    private fun fetchViaDohIp(url: String): Pair<String, SubRec?> {
        val u = URL(url)
        val host = u.host
        val ips = PingTest.dohResolve(host)
        if (ips.isEmpty()) throw java.net.UnknownHostException(host)
        var last: Throwable? = null
        for (ip in ips.take(3)) {
            try {
                val alt = URL("https://$ip${u.file}")
                val con = alt.openConnection() as javax.net.ssl.HttpsURLConnection
                con.connectTimeout = 15000
                con.readTimeout = 15000
                con.instanceFollowRedirects = true
                con.setRequestProperty("User-Agent", "v2rayNG/1.9.16")
                con.setRequestProperty("Accept", "*/*")
                con.setRequestProperty("Host", host)     // original host header
                con.sslSocketFactory = SniFactory(host)  // original SNI
                con.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                return readSubResponse(con, url)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: java.net.UnknownHostException(host)
    }

    /**
     * SSLSocketFactory that keeps the TLS SNI on the REAL hostname while the
     * socket dials the resolved IP (Java would otherwise send the IP as SNI,
     * which most CDNs reject).
     */
    private class SniFactory(private val sni: String) : javax.net.ssl.SSLSocketFactory() {
        private val delegate = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory

        private fun tune(s: Socket): Socket = try {
            (s as? javax.net.ssl.SSLSocket)?.let { sock ->
                val p = sock.sslParameters
                p.serverNames = listOf(javax.net.ssl.SNIHostName(sni))
                sock.sslParameters = p
            }
            s
        } catch (_: Throwable) {
            s
        }

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            tune(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(s: Socket, consumed: java.io.InputStream, autoClose: Boolean): Socket =
            tune(delegate.createSocket(s, consumed, autoClose))

        override fun createSocket(host: String, port: Int): Socket =
            tune(delegate.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
            tune(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
            tune(delegate.createSocket(host, port))

        override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket =
            tune(delegate.createSocket(address, port, localAddress, localPort))

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    }

    /** Read the body + subscription-userinfo header out of a live connection. */
    private fun readSubResponse(
        con: HttpURLConnection,
        url: String,
    ): Pair<String, SubRec?> {
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

    /** Fetch a URL body; also returns the subscription-userinfo header if present. */
    fun fetchWithInfo(url: String, timeoutMs: Int = 15000, socksPort: Int? = null): Pair<String, SubRec?> {
        val con = URL(url).openConnection(
            if (socksPort != null)
                java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socksPort))
            else java.net.Proxy.NO_PROXY
        ) as HttpURLConnection
        con.connectTimeout = timeoutMs
        con.readTimeout = timeoutMs
        con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", "v2rayNG/1.9.16")
        con.setRequestProperty("Accept", "*/*")
        return readSubResponse(con, url)
    }

    fun fetch(url: String, timeoutMs: Int = 15000): String = fetchWithInfo(url, timeoutMs).first

    // --- High level operations (pure — results applied by the caller) -------
    fun fetchSubLegacy(url: String): SubFetchResult = try {
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
