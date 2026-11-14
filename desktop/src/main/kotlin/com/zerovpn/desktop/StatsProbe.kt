package com.zerovpn.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * Local integration probe (dev only): starts the patterniha xray fork with a
 * freedom outbound + the v2.0.0 stats/api wiring, pushes real traffic through
 * the SOCKS inbound, then verifies StatsClient's hand-rolled gRPC h2c client
 * against the live core.
 * Run: java -cp ZeroVPN-all.jar com.zerovpn.desktop.StatsProbeKt <path-to-xray>
 */
fun main(args: Array<String>) {
    val xrayPath = args.firstOrNull() ?: "/tmp/xray-linux"
    val work = File("/tmp/statsprobe").apply { mkdirs() }
    val socks = 20808; val http = 20809; val api = 20810

    val cfg = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", "warning") })
        put("stats", buildJsonObject { })
        put("api", buildJsonObject {
            put("tag", "api")
            put("services", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("StatsService")) })
        })
        put("policy", buildJsonObject {
            put("system", buildJsonObject {
                put("statsInboundUplink", true)
                put("statsInboundDownlink", true)
            })
        })
        put("inbounds", buildJsonArray {
            add(buildJsonObject {
                put("tag", "socks-in")
                put("listen", "127.0.0.1"); put("port", socks)
                put("protocol", "socks")
                put("settings", buildJsonObject { put("auth", "noauth"); put("udp", true) })
            })
            add(buildJsonObject {
                put("tag", "http-in")
                put("listen", "127.0.0.1"); put("port", http)
                put("protocol", "http")
                put("settings", buildJsonObject { })
            })
            add(buildJsonObject {
                put("tag", "api-in")
                put("listen", "127.0.0.1"); put("port", api)
                put("protocol", "dokodemo-door")
                put("settings", buildJsonObject { put("address", "127.0.0.1") })
            })
        })
        put("outbounds", buildJsonArray {
            add(buildJsonObject { put("tag", "direct"); put("protocol", "freedom") })
        })
        put("routing", buildJsonObject {
            put("rules", buildJsonArray {
                add(buildJsonObject {
                    put("type", "field")
                    put("inboundTag", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("api-in")) })
                    put("outboundTag", "api")
                })
            })
        })
    }
    val cfgFile = File(work, "config.json")
    cfgFile.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), cfg))

    println("starting $xrayPath …")
    val proc = ProcessBuilder(xrayPath, "run", "-c", cfgFile.absolutePath)
        .directory(work).redirectErrorStream(true)
        .redirectOutput(File(work, "xray.log")).start()
    Thread.sleep(1200)
    check(proc.isAlive) { "xray died — log: ${File(work, "xray.log").readText()}" }
    check(Socket().use { it.connect(InetSocketAddress("127.0.0.1", socks), 800); true }) { "socks port not up" }
    println("core is up (socks=$socks api=$api)")

    // Push REAL traffic through the SOCKS inbound (direct to internet).
    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks))
    var bytes = 0L
    for (url in listOf("http://example.com", "https://www.gstatic.com/generate_204")) {
        try {
            val con = URL(url).openConnection(proxy) as java.net.HttpURLConnection
            con.connectTimeout = 8000; con.readTimeout = 8000
            con.setRequestProperty("User-Agent", "ZeroVPN-probe")
            val body = con.inputStream.use { it.readBytes() }
            bytes += body.size.toLong()
            println("fetched $url -> ${con.responseCode} (${body.size} B)")
        } catch (t: Throwable) {
            println("fetch $url failed: ${t.message}")
        }
    }
    println("total pushed: $bytes B")

    runBlocking {
        repeat(2) {
            val r = StatsClient.queryInboundTraffic(api)
            println("query #$it -> $r")
            kotlinx.coroutines.delay(600)
        }
        // second traffic burst then re-query to verify counters move
        try {
            val con = URL("http://example.com").openConnection(proxy) as java.net.HttpURLConnection
            con.inputStream.use { it.readBytes() }
        } catch (_: Throwable) { }
        val r2 = StatsClient.queryInboundTraffic(api)
        println("query after burst -> $r2")
        if (r2 != null && r2.first > 0 && r2.second > 0) println("STATSPROBE PASS")
        else println("STATSPROBE FAIL")
    }
    proc.destroy()
}
