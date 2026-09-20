package com.zerovpn.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

data class ParsedLink(val name: String, val proto: String, val link: String)

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
                    val p = it.split(":", limit = 2)
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

    // --- High level operations ----------------------------------------------
    fun updateSubscriptions(): Pair<Int, String> {
        val subs = Store.subscriptions
        if (subs.isEmpty()) return 0 to "هیچ اشتراکی ثبت نشده"
        var added = 0
        var err: String? = null
        for (sub in subs) {
            try {
                Store.busyMsg = "در حال دریافت: ${sub.name}"
                val (body, info) = fetchWithInfo(sub.url)
                val links = parseBody(body)
                val fresh = links.map { p ->
                    ProfileRec(
                        id = subId(sub.url, p.link),
                        name = p.name,
                        proto = p.proto,
                        link = p.link,
                        sub = sub.url,
                    )
                }.distinctBy { it.id }
                synchronized(Store) {
                    // Replace ONLY this subscription's profiles — every other
                    // group (subs + manual) stays untouched.
                    val others = Store.profiles.filter { it.sub != sub.url }
                    Store.data = Store.data.copy(profiles = others + fresh)
                    Store.data = Store.data.copy(
                        subscriptions = Store.subscriptions.map {
                            if (it.url == sub.url)
                                it.copy(
                                    lastFetch = System.currentTimeMillis(),
                                    used = info?.used ?: it.used,
                                    total = info?.total ?: it.total,
                                    expire = info?.expire ?: it.expire,
                                )
                            else it
                        }
                    )
                    if ((Store.selectedId == null ||
                            Store.profiles.none { it.id == Store.selectedId }) &&
                        Store.profiles.isNotEmpty()
                    ) {
                        Store.data = Store.data.copy(selected = Store.profiles.first().id)
                    }
                    Store.save()
                }
                added += fresh.size
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
            }
        }
        Store.busyMsg = null
        return if (err != null && added == 0) 0 to "خطا در دریافت: $err"
        else added to "$added سرور دریافت شد"
    }

    /** Refreshes ONE subscription (keeps other groups' profiles intact). */
    fun refreshSingleSub(url: String): Pair<Int, String> {
        val sub = Store.subscriptionFor(url) ?: return 0 to "اشتراک پیدا نشد"
        return try {
            Store.busyMsg = "در حال دریافت: ${sub.name}"
            val (body, info) = fetchWithInfo(sub.url)
            val links = parseBody(body)
            val fresh = links.map { p ->
                ProfileRec(
                    id = subId(sub.url, p.link),
                    name = p.name,
                    proto = p.proto,
                    link = p.link,
                    sub = sub.url,
                )
            }.distinctBy { it.id }
            synchronized(Store) {
                val others = Store.profiles.filter { it.sub != sub.url }
                Store.data = Store.data.copy(profiles = others + fresh)
                Store.data = Store.data.copy(
                    subscriptions = Store.subscriptions.map {
                        if (it.url == sub.url)
                            it.copy(
                                lastFetch = System.currentTimeMillis(),
                                used = info?.used ?: it.used,
                                total = info?.total ?: it.total,
                                expire = info?.expire ?: it.expire,
                            )
                        else it
                    }
                )
                if ((Store.selectedId == null ||
                        Store.profiles.none { it.id == Store.selectedId }) &&
                    Store.profiles.isNotEmpty()
                ) {
                    Store.data = Store.data.copy(selected = Store.profiles.first().id)
                }
                Store.save()
            }
            Store.busyMsg = null
            fresh.size to "${fresh.size} سرور دریافت شد"
        } catch (t: Throwable) {
            Store.busyMsg = null
            0 to "خطا در دریافت: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun importText(text: String): Int {
        val links = parseBody(text)
        var n = 0
        for (p in links) {
            Store.upsertProfile(
                ProfileRec(
                    id = stableId(p.link),
                    name = p.name,
                    proto = p.proto,
                    link = p.link,
                    sub = null,
                )
            )
            n++
        }
        return n
    }

    fun stableId(link: String): String =
        UUID.nameUUIDFromBytes(link.toByteArray(Charsets.UTF_8)).toString()

    /** Unique id per (subscription, link): the same server link inside two
     *  different subscriptions must be two independent rows, or selection
     *  and list keys collide ("the app picks a server by itself"). */
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
