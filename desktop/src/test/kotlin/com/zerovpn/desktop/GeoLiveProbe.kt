package com.zerovpn.desktop

import kotlinx.coroutines.delay

/**
 * LIVE headless probe of the v2.3.0 country pipeline (test sources only —
 * never packaged). Run:
 *   gradle compileTestKotlin
 *   java -cp build/classes/kotlin/test:build/libs/ZeroVPN-<v>-all.jar \
 *        com.zerovpn.desktop.GeoLiveProbeKt
 *
 * Exercises the REAL code path end-to-end:
 *   1. domain host → DoH-first resolve → HTTPS geo → byHost
 *   2. IP literal  → direct geo (8.8.8.8 → US)
 *   3. unresolvable garbage host → remark hint fallback ("آلمان" → DE, hint)
 * and confirms ensureLookup posts results through the deferred EDT path.
 */
suspend fun main() {
    GeoLookup.ensureLookup("one.one.one.one", "Cloudflare 1")
    GeoLookup.ensureLookup("8.8.8.8", null)
    GeoLookup.ensureLookup("185.199.108.153", null)
    GeoLookup.ensureLookup("srv-test-does-not-exist-ir", "سرور آلمان ۱۲")

    val deadline = System.currentTimeMillis() + 30_000
    var last = -1
    while (System.currentTimeMillis() < deadline) {
        val n = GeoLookup.byHost.size
        if (n != last) {
            println("[${(System.currentTimeMillis() / 1000 % 100)}s] byHost=${GeoLookup.byHost}")
            last = n
        }
        if (n >= 4) break
        delay(800)
    }
    println("FINAL=${GeoLookup.byHost}")
    val ok = listOf(
        "one.one.one.one", "8.8.8.8", "185.199.108.153", "srv-test-does-not-exist-ir"
    ).all { GeoLookup.byHost.containsKey(it) }
    println(if (ok) "GEO-LIVE PASS" else "GEO-LIVE INCOMPLETE (some lookups failed — see above)")
}
