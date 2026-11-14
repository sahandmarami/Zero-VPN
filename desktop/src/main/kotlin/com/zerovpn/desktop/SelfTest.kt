package com.zerovpn.desktop

import kotlinx.serialization.json.JsonObject

/**
 * Command-line self test: java -cp ZeroVPN.jar com.zerovpn.desktop.SelfTestKt
 * Validates link parsing + config generation without the GUI.
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

    // full config for the vless link
    val full = XrayConfig.buildFullConfig(samples[1], 10808, 10809)
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

    // --- v1.6.0 parity assertions -------------------------------------------
    // 1) Unencrypted (PattNG-style) vless on port 443 WITHOUT a security param
    //    must stay plain — no tlsSettings in the outbound.
    val plainVless = "vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?type=tcp#Plain"
    val plainOut = XrayConfig.buildOutbound(plainVless)?.toString() ?: ""
    val plainOk = plainOut.contains("\"security\"") && !plainOut.contains("tlsSettings")
    println(if (plainOk) "OK    vless-443 default stays plain (no tlsSettings)" else "FAIL  vless-443 default security")
    if (plainOk) ok++

    // 2) cs= share-link parameter must map into tlsSettings.cipherSuites
    val csVless = "vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?security=tls&sni=cdn.example.com&cs=TLS_AES_128_GCM_SHA256&type=tcp#CS"
    val csOut = XrayConfig.buildOutbound(csVless)?.toString() ?: ""
    val csOk = csOut.contains("cipherSuites") && csOut.contains("TLS_AES_128_GCM_SHA256")
    println(if (csOk) "OK    vless cs= maps to tlsSettings.cipherSuites" else "FAIL  vless cs= mapping")
    if (csOk) ok++

    // 3) trojan with explicit security=none must stay plain (special configs)
    val plainTrojan = "trojan://password123@example.com:443?security=none&type=tcp#PlainTrojan"
    val ptOut = XrayConfig.buildOutbound(plainTrojan)?.toString() ?: ""
    val ptOk = !ptOut.contains("tlsSettings")
    println(if (ptOk) "OK    trojan security=none stays plain" else "FAIL  trojan security=none")
    if (ptOk) ok++

    // 4) trojan without a query keeps the classic tls default
    val defTrojan = "trojan://password123@example.com:443#DefaultTrojan"
    val dtOut = XrayConfig.buildOutbound(defTrojan)?.toString() ?: ""
    val dtOk = dtOut.contains("tlsSettings")
    println(if (dtOk) "OK    trojan no-query defaults to tls" else "FAIL  trojan tls default")
    if (dtOk) ok++

    // 5) unsafe fingerprint passes through (anti-NIN picker parity)
    val unsafeVless = "vless://b831b3c0-0123-4567-89ab-cdef01234567@example.com:443?security=tls&fp=unsafe&type=tcp#UnsafeFP"
    val ufOut = XrayConfig.buildOutbound(unsafeVless)?.toString() ?: ""
    val ufOk = ufOut.contains("\"fingerprint\":\"unsafe\"")
    println(if (ufOk) "OK    fp=unsafe passes through" else "FAIL  fp=unsafe passthrough")
    if (ufOk) ok++

    println(
        if (ok == samples.size + 5 && full != null && parsed.size == 3) "SELFTEST PASS"
        else "SELFTEST FAIL ($ok expected ${samples.size + 5})"
    )
}
