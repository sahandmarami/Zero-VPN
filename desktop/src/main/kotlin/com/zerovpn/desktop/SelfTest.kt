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
    val links = listOf(samples[0], samples[2], samples[3]).joinToString("\n")
    val b64 = java.util.Base64.getEncoder().encodeToString(links.toByteArray())
    val parsed = Profiles.parseBody(b64)
    println("OK    subscription decode: ${parsed.size} links (expect 3)")
    if (parsed.size == 3) ok++

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

    val expected = samples.size + 11
    println(
        if (ok == expected && full != null) "SELFTEST PASS ($ok/$expected)"
        else "SELFTEST FAIL ($ok expected $expected)"
    )
}
