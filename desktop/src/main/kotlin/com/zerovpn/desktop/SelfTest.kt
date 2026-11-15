package com.zerovpn.desktop

import kotlinx.serialization.json.JsonObject

/**
 * Command-line self test: java -cp ZeroVPN.jar com.zerovpn.desktop.SelfTestKt
 * Validates link parsing + config generation + stats plumbing without the GUI.
 */
fun main(args: Array<String>) {
    val samples = listOf(
        // vmess (base64 JSON)
        "vmess://eyJ2IjoiMiIsInBzIjoiVGVzdCBWTWVzcyIsImFkZCI6ImV4YW1wbGUuY29tIiwicG9ydCI6IjQ0MyIsImlkIjoiYjgzMWIzYzAtMDEyMy00NTY3LTg5YWItY2RlZjAxMjM0NTY3IiwiYWlkIjoiMCIsIm5ldCI6IndzIiwidHlwZSI6IiIsImhvc3QiOiJjZG4uZXhhbXBsZS5jb20iLCJwYXRoIjoiL3BhdGgiLCJ0bHMiOiJ0bHMiLCJzbmkiOiJjZG4uZXhhbXBsZS5jb20iLCJmcCI6ImNocm9tZSJ9",
        // vless reality
        "vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179&type=tcp&flow=xtls-rprx-vision#Test%20VLESS",
        // trojan ws
        "trojan://password123@example.com:443?security=tls&sni=cdn.example.com&type=ws&host=cdn.example.com&path=%2Fws#Test%20Trojan",
        // ss SIP002 with base64 userinfo
        "ss://" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:secretpass".toByteArray()) + "@1.2.3.4:8388#Test%20SS",
    )

    var ok = 0
    for (link in samples) {
        val outbound = XrayConfig.buildOutbound(link)
        val proto = link.substringBefore("://")
        if (outbound == null) {
            println("FAIL  $proto — no outbound generated")
        } else {
            val json = XrayConfig.json.encodeToString(JsonObject.serializer(), outbound)
            println("OK    $proto (${json.length} bytes)")
            ok++
        }
    }

    // full config for the vless link (v2.0.0: apiPort + stats + api inbound)
    val full = XrayConfig.buildFullConfig(samples[1], 10808, 10809, 10810)
    println(if (full != null) "OK    full-config (${full.toString().length} bytes)" else "FAIL  full-config")
    full?.let {
        java.io.File("selftest-config.json").writeText(XrayConfig.json.encodeToString(JsonObject.serializer(), it))
        println("      -> selftest-config.json written")
    }

    // subscription body decode
    val links = listOf(samples[0], samples[2], samples[3],
        "hysteria2://secretpass@hy2.example.com:443?sni=cdn.example.com#HY2").joinToString("\n")
    val b64 = java.util.Base64.getEncoder().encodeToString(links.toByteArray())
    val parsed = Profiles.parseBody(b64)
    println("OK    subscription decode: ${parsed.size} links (expect 4)")
    if (parsed.size == 4) ok++

    // --- v2.0.0 assertions -----------------------------------------------------
    // 1) Unencrypted (PattNG-style) vless on 443 WITHOUT a security param stays plain.
    val plainVless = "vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?type=tcp#Plain"
    val plainOut = XrayConfig.buildOutbound(plainVless)?.toString() ?: ""
    val plainOk = plainOut.contains("\"security\"") && !plainOut.contains("tlsSettings")
    println(if (plainOk) "OK    vless-443 default stays plain (no tlsSettings)" else "FAIL  vless-443 default security")
    if (plainOk) ok++

    // 2) cs= share-link parameter maps into tlsSettings.cipherSuites
    val csVless = "vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?security=tls&sni=cdn.example.com&cs=TLS_AES_128_GCM_SHA256&type=tcp#CS"
    val csOut = XrayConfig.buildOutbound(csVless)?.toString() ?: ""
    val csOk = csOut.contains("cipherSuites") && csOut.contains("TLS_AES_128_GCM_SHA256")
    println(if (csOk) "OK    vless cs= maps to tlsSettings.cipherSuites" else "FAIL  vless cs= mapping")
    if (csOk) ok++

    // 3) trojan with explicit security=none stays plain (special configs)
    val ptOut = XrayConfig.buildOutbound("trojan://password123@example.com:443?security=none&type=tcp#PlainTrojan")?.toString() ?: ""
    val ptOk = !ptOut.contains("tlsSettings")
    println(if (ptOk) "OK    trojan security=none stays plain" else "FAIL  trojan security=none")
    if (ptOk) ok++

    // 4) trojan without a query keeps the classic tls default
    val dtOut = XrayConfig.buildOutbound("trojan://password123@example.com:443#DefaultTrojan")?.toString() ?: ""
    val dtOk = dtOut.contains("tlsSettings")
    println(if (dtOk) "OK    trojan no-query defaults to tls" else "FAIL  trojan tls default")
    if (dtOk) ok++

    // 5) fp=unsafe passes through (anti-NIN picker parity)
    val ufOut = XrayConfig.buildOutbound("vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?security=tls&fp=unsafe&type=tcp#UnsafeFP")?.toString() ?: ""
    val ufOk = ufOut.contains("\"fingerprint\":\"unsafe\"")
    println(if (ufOk) "OK    fp=unsafe passes through" else "FAIL  fp=unsafe passthrough")
    if (ufOk) ok++

    // 6) full config carries the stats machinery (live traffic)
    val cfg = full?.toString() ?: ""
    val statsOk = cfg.contains("\"stats\"") && cfg.contains("StatsService") &&
        cfg.contains("statsInboundUplink") && cfg.contains("api-in") &&
        cfg.contains("10810") && cfg.contains("dokodemo-door")
    println(if (statsOk) "OK    full config carries stats + api inbound" else "FAIL  stats config wiring")
    if (statsOk) ok++

    // 7) QueryStatsResponse protobuf parse — synthetic wire bytes:
    //    stat{ name="inbound>>>socks-in>>>traffic>>>uplink", value=1000 }
    //    stat{ name="inbound>>>socks-in>>>traffic>>>downlink", value=2000 }
    //    stat{ name="inbound>>>http-in>>>traffic>>>uplink", value=5 }
    fun protoStat(name: String, value: Long): List<Byte> {
        val nb = name.toByteArray(Charsets.UTF_8)
        // varint-encode the value
        val vbytes = mutableListOf<Byte>()
        var v = value
        while (true) {
            if (v < 0x80) { vbytes.add(v.toByte()); break }
            vbytes.add(((v and 0x7F) or 0x80).toByte()); v = v shr 7
        }
        val out = mutableListOf<Byte>()
        out.add(0x0A)                                     // field 1, wiretype 2
        val innerLen = 2 + nb.size + 1 + vbytes.size      // name tag+len, name, value tag, value
        out.add(innerLen.toByte())
        out.add(0x0A); out.add(nb.size.toByte()); out.addAll(nb.map { it.toByte() })
        out.add(0x10)                                     // field 2, varint
        out.addAll(vbytes)
        return out
    }
    val wire = protoStat("inbound>>>socks-in>>>traffic>>>uplink", 1000) +
            protoStat("inbound>>>socks-in>>>traffic>>>downlink", 2000) +
            protoStat("inbound>>>http-in>>>traffic>>>uplink", 5) +
            protoStat("user>>>x@example.com>>>traffic>>>uplink", 999)
    val agg = StatsClient.parseStats(wire.toByteArray())
    val statsParseOk = agg != null && agg.first == 1005L && agg.second == 2000L
    println(if (statsParseOk) "OK    stats protobuf parse (${agg?.first} up / ${agg?.second} down)" else "FAIL  stats protobuf parse: $agg")
    if (statsParseOk) ok++

    // 8) TrafficTracker folds deltas into speeds + totals
    TrafficTracker.reset()
    TrafficTracker.sample(1000, 5000)
    TrafficTracker.sample(1600, 5400)
    val trackerOk = TrafficTracker.upSpeed == 600L && TrafficTracker.downSpeed == 400L &&
        TrafficTracker.upTotal == 600L && TrafficTracker.downTotal == 400L
    println(if (trackerOk) "OK    TrafficTracker delta folding" else "FAIL  TrafficTracker: up=${TrafficTracker.upSpeed}/${TrafficTracker.upTotal} down=${TrafficTracker.downSpeed}/${TrafficTracker.downTotal}")
    if (trackerOk) ok++

    // 9) v2.0.1: recordPing is a DEFERRED EDT write (the "Unsupported
    //    concurrent change during composition" crash-class fix) — the ping
    //    lands via invokeLater on the AWT EDT, never inline from the caller.
    Store.recordPing("selftest-deferred-id", 123)
    var landed = false
    val deadline = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < deadline && !landed) {
        Thread.sleep(50)
        landed = Store.pingMap["selftest-deferred-id"] == 123L
    }
    println(if (landed) "OK    recordPing deferred EDT write lands" else "FAIL  recordPing deferred write (timeout)")
    if (landed) ok++

    // 10) v2.0.1: flag resolution — a bundled ISO code resolves from resources
    //     without touching the network (the path that used to write byCode
    //     during composition; it must now only produce the bitmap, and the
    //     observable write is deferred elsewhere).
    val bundledOk = FlagImages.bundledAccessible("de")
    println(if (bundledOk) "OK    bundled flag resource resolvable" else "FAIL  bundled flag resource")
    if (bundledOk) ok++

    // 11) v2.1.0: Tor chain — buildFullConfig with a Tor port wraps the main
    //     outbound behind a tor-chain SOCKS outbound (proxySettings.tag).
    val torCfg = XrayConfig.buildFullConfig(samples[1], 10808, 10809, 10810, 9050)
    val torOut = torCfg?.get("outbounds").toString()
    val torOk = torOut.contains("\"tor-chain\"") && torOut.contains("\"proxySettings\"") &&
        torOut.contains("127.0.0.1")
    println(if (torOk) "OK    tor-chain config (proxySettings + socks outbound)" else "FAIL  tor-chain config")
    if (torOk) ok++

    // 12) v2.1.0: plain (no-Tor) config must NOT contain the chain
    val plainCfg = XrayConfig.buildFullConfig(samples[1], 10808, 10809, 10810)?.toString() ?: ""
    val plainOk2 = !plainCfg.contains("tor-chain")
    println(if (plainOk2) "OK    non-tor config has no chain" else "FAIL  non-tor config leaked chain")
    if (plainOk2) ok++

    // 13) v2.1.0: Tor port candidates — preferred first, then the two well-known\n    //     defaults, deduplicated (9150 Tor Browser, 9050 Orbot/Expert Bundle)
    val cand = TorProbe.candidatePorts(9150)
    val candOk = cand == listOf(9150, 9050)
    println(if (candOk) "OK    tor probe port candidates (deduped, preferred first)" else "FAIL  tor probe candidates: $cand")
    if (candOk) ok++

    // 14) v2.1.0: update-check version comparison
    val verOk = UpdateChecker.isNewer("2.1.0", "2.0.1") && !UpdateChecker.isNewer("2.0.1", "2.1.0") &&
        UpdateChecker.isNewer("2.10.0", "2.9.9") && !UpdateChecker.isNewer("2.1.0", "2.1.0")
    println(if (verOk) "OK    update version compare" else "FAIL  update version compare")
    if (verOk) ok++

    // --- v2.2.0 assertions -----------------------------------------------------
    // 15) hysteria2 import: the line must survive parseBody (v2.1.0 dropped
    //     it — hy2-only subs added ZERO servers on Windows).
    val hy2PinB64 = java.util.Base64.getEncoder().encodeToString(
        ByteArray(32) { it.toByte() }
    )   // hex: 000102...1f
    val hy2PinHex = (0 until 32).joinToString("") { "%02x".format(it) }
    val hy2Link = "hysteria2://secretpass@hy2.example.com:443?sni=cdn.example.com&insecure=1" +
        "&pinSHA256=" + java.net.URLEncoder.encode(hy2PinB64, "UTF-8") +
        "&obfs=salamander&obfs-password=obfspass&mport=20000-30000#Test%20HY2"
    val hy2Parsed = Profiles.parseBody("vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?type=tcp#A\n" + hy2Link)
    val hy2ImportOk = hy2Parsed.size == 2 && hy2Parsed.any { it.proto == "hysteria2" }
    println(if (hy2ImportOk) "OK    hysteria2 import survives parseBody" else "FAIL  hysteria2 import (${hy2Parsed.size})")
    if (hy2ImportOk) ok++

    // 16) hysteria2 outbound shape = the Android CoreOutboundBuilder JSON,
    //     with the core-26.9.27 allowInsecure migration applied:
    //     allowInsecure=true is a FATAL config error in this core build, so
    //     it must never appear; pinSHA256 maps to pinnedPeerCertSha256.
    val hy2Out = XrayConfig.buildOutbound(hy2Link)
    val hy2Json = (hy2Out?.toString() ?: "").replace(" ", "")
    val hy2ShapeOk = hy2Out != null &&
        hy2Json.contains("\"protocol\":\"hysteria\"") &&
        hy2Json.contains("\"version\":2") &&
        hy2Json.contains("\"auth\":\"secretpass\"") &&
        hy2Json.contains("\"hysteriaSettings\"") &&
        hy2Json.contains("\"network\":\"hysteria\"") &&
        hy2Json.contains("h3") &&
        hy2Json.contains("salamander") &&
        hy2Json.contains("20000-30000") &&
        hy2Json.contains("pinnedPeerCertSha256") &&
        hy2Json.contains(hy2PinHex) &&
        !hy2Json.contains("\"allowInsecure\":true")
    println(if (hy2ShapeOk) "OK    hysteria2 outbound (hysteriaSettings + finalmask + pin migration)" else "FAIL  hysteria2 outbound shape")
    if (hy2ShapeOk) ok++

    // 16b) allowInsecure=1 WITHOUT a pin must ALSO never produce
    //      allowInsecure:true (the core rejects the whole config).
    val insOut = XrayConfig.buildOutbound(
        "vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?security=tls&insecure=1&type=tcp#InsecNoPin"
    )?.toString()?.replace(" ", "") ?: ""
    val insOk = insOut.contains("\"allowInsecure\":false") && !insOut.contains("\"allowInsecure\":true")
    println(if (insOk) "OK    insecure link never emits allowInsecure:true" else "FAIL  insecure link migration")
    if (insOk) ok++

    // 17) ServerInfo.hostPort covers hysteria2 (geo chip + prefilter input)
    val hy2Hp = ServerInfo.hostPort(hy2Link)
    val hy2HpOk = hy2Hp == ("hy2.example.com" to 443)
    println(if (hy2HpOk) "OK    hysteria2 host/port extraction" else "FAIL  hysteria2 host/port: $hy2Hp")
    if (hy2HpOk) ok++

    // 18) Manual redirect chain + userinfo merge (the desktop fetch used to
    //     die on cross-protocol hops and lose the quota header). A raw
    //     loopback server answers /sub with 301 → /final → base64 body +
    //     subscription-userinfo.
    var chainOk = false
    var chainErr = ""
    try {
        val srv = java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        val port = srv.localPort
        val linksBlob = java.util.Base64.getEncoder().encodeToString(
            "vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?type=tcp#ChainServer".toByteArray()
        )
        fun readRequest(c: java.net.Socket): String {
            val sb = StringBuilder()
            val ins = c.getInputStream()
            while (true) {
                val b = ins.read()
                if (b < 0) break
                sb.append(b.toChar())
                if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) break
            }
            return sb.toString()
        }
        val worker = Thread {
            try {
                srv.soTimeout = 4000
                // hop 1: 301
                srv.accept().use { c ->
                    readRequest(c)
                    c.getOutputStream().write(
                        ("HTTP/1.1 301 Moved Permanently\r\nLocation: /final\r\n" +
                            "Subscription-Userinfo: upload=10; download=20; total=1000; expire=0\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                }
                // hop 2: 200 + body + (later-wins) userinfo
                srv.accept().use { c ->
                    readRequest(c)
                    val body = linksBlob
                    c.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nSubscription-Userinfo: upload=100; download=200; total=5000; expire=0\r\n" +
                            "Content-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray()
                    )
                }
            } catch (_: Throwable) { }
        }
        worker.isDaemon = true
        worker.start()
        val res = Profiles.fetchSub("http://127.0.0.1:$port/sub")
        srv.close()
        chainOk = res.error == null && res.profiles.size == 1 &&
            res.info?.total == 5000L && res.info?.used == 300L
        chainErr = res.error ?: ""
    } catch (t: Throwable) {
        chainErr = t.message ?: t.javaClass.simpleName
    }
    println(if (chainOk) "OK    redirect chain follows hops + merges userinfo" else "FAIL  redirect chain: $chainErr")
    if (chainOk) ok++

    // 19) CorePing re-tagging contract: every buildOutbound result carries a
    //     tag the chunk builder copies/replaces (o<i>).
    val chunkCfg = XrayConfig.buildOutbound(samples[1])?.get("tag")?.toString() == "\"proxy\""
    println(if (chunkCfg) "OK    chunk outbound base (tag proxy present)" else "FAIL  chunk outbound base")
    if (chunkCfg) ok++

    // --- v2.3.0 assertions -----------------------------------------------------
    // 20) CountryHint — remark → country (the V2Box-style flag fallback):
    //     flag emoji, Persian/English country words, city aliases, ISO token.
    val hintOk = CountryHint.fromName("🇩🇪 Germany 12") == "DE" &&
        CountryHint.fromName("آلمان #3") == "DE" &&
        CountryHint.fromName("DE-01 fast") == "DE" &&
        CountryHint.fromName("Dubai 2") == "AE" &&
        CountryHint.fromName("Netherlands Premium") == "NL" &&
        CountryHint.fromName("سرور تستی بدون کشور") == null
    println(if (hintOk) "OK    country hint parsing (emoji/fa/en/iso)" else "FAIL  country hint parsing")
    if (hintOk) ok++

    // 21) parseGeoBody tolerates every geo source's field naming; junk → null.
    val geo1 = GeoLookup.parseGeoBody("{\"ip\":\"1.2.3.4\",\"success\":true,\"country\":\"Germany\",\"country_code\":\"DE\"}")
    val geo2 = GeoLookup.parseGeoBody("{\"countryCode\":\"DE\",\"country\":\"Germany\",\"ip\":\"1.2.3.4\"}")
    val geo3 = GeoLookup.parseGeoBody("{\"countryCode\":\"DE\",\"countryName\":\"Germany\",\"ipAddress\":\"1.2.3.4\"}")
    val geo4 = GeoLookup.parseGeoBody("{\"success\":false}")
    val geo5 = GeoLookup.parseGeoBody("not json at all")
    val geoOk = geo1?.cc == "DE" && geo2?.cc == "DE" && geo3?.cc == "DE" &&
        geo4 == null && geo5 == null && geo1?.ip == "1.2.3.4"
    println(if (geoOk) "OK    geo response parser (ipwho/ip.sb/freeipapi + failures)" else "FAIL  geo response parser: $geo1 $geo2 $geo3 $geo4")
    if (geoOk) ok++

    // 22) Persian country names (the flag chips next to every config)
    val faName = localizedCountryName("DE") == "آلمان" && localizedCountryName("US") == "آمریکا" &&
        localizedCountryName(null) == null
    println(if (faName) "OK    persian country display names" else "FAIL    persian country names")
    if (faName) ok++

    val expected = samples.size + 24
    println(
        if (ok == expected && full != null) "SELFTEST PASS ($ok/$expected)"
        else "SELFTEST FAIL ($ok expected $expected)"
    )
}
