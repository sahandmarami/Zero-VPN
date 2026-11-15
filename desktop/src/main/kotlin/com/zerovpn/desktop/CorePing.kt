package com.zerovpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * CorePing (v2.2.0) — REAL per-server delay test through the Xray core.
 *
 * Why this exists: the old "test all" was a raw TCP handshake to the server's
 * host:port. On real Iranian networks that lies — a poisoned/blackholed
 * domain resolves to some unrelated host that ACCEPTS the TCP handshake fast
 * (great-looking ping!) while the actual tunnel can never work. The phone app
 * never had this problem because its "test all" measures REAL delay through
 * the core (CoreTestService → RealPingWorkerService → measureOutboundDelay).
 * This is the desktop port of that behaviour — the user asked for exactly
 * this: "برو همون نسخه اندروید رو تبدیل به ویندوز کن".
 *
 * How it works (no libv2ray on desktop — we spawn xray.exe instead):
 *
 *   - profiles are split into chunks of 12;
 *   - each chunk becomes ONE temp xray config: a private socks inbound on a
 *     unique loopback port per server, one outbound per server, and routing
 *     rules that map inbound t<i> → outbound o<i>;
 *   - the chunk core starts, every inbound is awaited (hard deadline), then
 *     each server is tested by HTTP GET of the delay-test URL THROUGH its
 *     own socks inbound (gstatic generate_204 → cloudflare fallback — the
 *     same URLs the connect-flow real ping uses);
 *   - v2.3.0: the old TCP PREFILTER IS GONE. It dialled host:port with the
 *     raw system resolver + first address only (none of the v1.5.7
 *     hardening) and marked servers "timeout" that ACTUALLY WORK — the
 *     connect flow never prefilters, which is exactly why the user saw
 *     "configs connect but pings lie". A dead server now just costs its own
 *     2×6 s deadline inside the chunk core instead of a false -1;
 *   - at most 2 chunk cores run concurrently, 4 tests in parallel per chunk
 *     (8 satiated weak uplinks and inflated the numbers).
 *   - every config/log/temp port lives only for the chunk's lifetime.
 *
 * Result semantics match the app-wide contract: ms > 0 = real delay through
 * that server; -1 = unreachable/failed (shown as تایم‌اوت).
 */
object CorePing {

    private const val CHUNK = 12
    private const val PARALLEL = 4
    private const val CHUNK_CONCURRENCY = 2
    private const val PORT_SCAN_MIN = 30000

    data class Target(
        val id: String,
        val name: String,
        val outbound: JsonObject,
        val host: String,
        val port: Int,
        val isQuic: Boolean,
    )

    /**
     * Test every profile with a real through-the-core delay measurement.
     * Runs on Dispatchers.IO; `record` is called per result (it posts to the
     * EDT itself) so rows fill in live while the batch runs.
     */
    suspend fun testAll(
        profiles: List<ProfileRec>,
        record: (String, Long) -> Unit,
        progress: (done: Int, total: Int) -> Unit,
    ) {
        val targets = profiles.mapNotNull { p ->
            val hp = ServerInfo.hostPort(p.link)
            val outbound = XrayConfig.buildOutbound(p.link) ?: return@mapNotNull null
            Target(
                id = p.id,
                name = p.name,
                outbound = outbound,
                host = hp?.first ?: "",
                port = hp?.second ?: 0,
                isQuic = p.proto == "hysteria2",
            )
        }
        if (targets.isEmpty()) return
        val total = targets.size
        val done = AtomicInteger(0)
        kotlinx.coroutines.coroutineScope {
            val sem = Semaphore(CHUNK_CONCURRENCY)
            val jobs = targets.chunked(CHUNK).map { chunk ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        runChunk(chunk) { id, ms ->
                            record(id, ms)
                            progress(done.incrementAndGet(), total)
                        }
                    }
                }
            }
            jobs.joinAll()
        }
    }

    /** Build + start one chunk core, test its servers, tear it down. */
    private suspend fun runChunk(chunk: List<Target>, record: (String, Long) -> Unit) {
        val ports = allocatePorts(chunk.size)
        if (ports == null) {
            chunk.forEach { record(it.id, -1L) }
            return
        }
        val stamp = System.nanoTime()
        val cfgFile = File(Store.dataDir, "ping-chunk-$stamp.json")
        val logFile = File(Store.dataDir, "ping-chunk-$stamp.log")
        var proc: Process? = null
        try {
            try {
                cfgFile.writeText(
                    XrayConfig.json.encodeToString(
                        JsonObject.serializer(), chunkConfig(chunk, ports)
                    )
                )
            } catch (_: Throwable) {
                chunk.forEach { record(it.id, -1L) }
                return
            }
            proc = startCore(cfgFile, logFile)
            if (proc == null) {
                // One malformed outbound can sink the whole chunk config (the
                // core refuses to start). Fall back to one core per server so
                // a single poisoned link only kills itself.
                runSingles(chunk, record)
                return
            }
            // Await every inbound of this chunk (hard cap ~8 s total).
            var allUp = true
            for (p in ports) {
                if (!waitForPort(p, 8000)) { allUp = false; break }
            }
            if (!allUp) {
                runSingles(chunk, record)
                return
            }
            val testSem = Semaphore(PARALLEL)
            kotlinx.coroutines.coroutineScope {
                chunk.mapIndexed { i, t ->
                    launch(Dispatchers.IO) {
                        testSem.withPermit { record(t.id, testOne(t, ports[i])) }
                    }
                }.joinAll()
            }
        } finally {
            try {
                proc?.destroy()
                if (proc?.isAlive == true && !proc!!.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (_: Throwable) { }
            try { cfgFile.delete() } catch (_: Throwable) { }
            try { logFile.delete() } catch (_: Throwable) { }
        }
    }

    /**
     * Per-server fallback: one temp core per target (port probe per server).
     * Used when the chunk config is rejected as a whole — a single poisoned
     * link (e.g. an unparsable outbound) then costs only itself.
     */
    private suspend fun runSingles(chunk: List<Target>, record: (String, Long) -> Unit) {
        kotlinx.coroutines.coroutineScope {
            val sem = Semaphore(3)
            chunk.map { t ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        val port = allocatePorts(1)?.firstOrNull()
                        if (port == null) {
                            record(t.id, -1L)
                            return@withPermit
                        }
                        val stamp = System.nanoTime()
                        val cfgFile = File(Store.dataDir, "ping-one-$stamp.json")
                        val logFile = File(Store.dataDir, "ping-one-$stamp.log")
                        var proc: Process? = null
                        try {
                            val cfg = chunkConfig(listOf(t), listOf(port))
                            cfgFile.writeText(
                                XrayConfig.json.encodeToString(JsonObject.serializer(), cfg)
                            )
                            proc = startCore(cfgFile, logFile)
                            if (proc == null || !waitForPort(port, 5000)) {
                                record(t.id, -1L)
                            } else {
                                record(t.id, testOne(t, port))
                            }
                        } catch (_: Throwable) {
                            record(t.id, -1L)
                        } finally {
                            try {
                                proc?.destroy()
                                if (proc?.isAlive == true && !proc!!.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                    proc.destroyForcibly()
                                }
                            } catch (_: Throwable) { }
                            try { cfgFile.delete() } catch (_: Throwable) { }
                            try { logFile.delete() } catch (_: Throwable) { }
                        }
                    }
                }
            }.joinAll()
        }
    }

    /**
     * One server: the REAL delay via its own chunk inbound. v2.3.0 — no TCP
     * prefilter: it dialled the host straight from the app with the raw
     * system resolver and marked working servers timeout (poisoned DNS /
     * dead-AAAA paths that the core's own dialling survives). The only
     * honest number is the through-the-core one.
     */
    private suspend fun testOne(t: Target, port: Int): Long {
        var ms = realPing(port, "https://www.gstatic.com/generate_204", 6000)
        if (ms < 0) ms = realPing(port, "https://cp.cloudflare.com/generate_204", 6000)
        return ms
    }

    /** generate_204 through one chunk inbound — the real per-server delay. */
    private fun realPing(port: Int, url: String, timeoutMs: Int): Long = try {
        val start = System.currentTimeMillis()
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val con = URL(url).openConnection(proxy) as HttpURLConnection
        con.connectTimeout = timeoutMs
        con.readTimeout = timeoutMs
        con.instanceFollowRedirects = false
        con.setRequestProperty("User-Agent", "ZeroVPN/$APP_VERSION")
        val code = con.responseCode
        con.disconnect()
        if (code in 200..399) System.currentTimeMillis() - start else -1L
    } catch (_: Throwable) {
        -1L
    }

    /**
     * Chunk config: n private socks inbounds, n outbounds, and routing rules
     * inboundTag t<i> → outboundTag o<i>. No stats/api machinery — this core
     * only measures delay.
     */
    private fun chunkConfig(chunk: List<Target>, ports: List<Int>): JsonObject = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", "error") })
        put("inbounds", buildJsonArray {
            chunk.indices.forEach { i ->
                add(buildJsonObject {
                    put("tag", "t$i")
                    put("listen", "127.0.0.1")
                    put("port", ports[i])
                    put("protocol", "socks")
                    put("settings", buildJsonObject {
                        put("auth", "noauth")
                        put("udp", false)
                    })
                })
            }
        })
        put("outbounds", buildJsonArray {
            chunk.indices.forEach { i ->
                add(buildJsonObject {
                    put("tag", "o$i")
                    chunk[i].outbound.forEach { (k, v) -> if (k != "tag") put(k, v) }
                })
            }
            add(buildJsonObject {
                put("tag", "direct")
                put("protocol", "freedom")
            })
        })
        put("routing", buildJsonObject {
            put("domainStrategy", "AsIs")
            put("rules", buildJsonArray {
                chunk.indices.forEach { i ->
                    add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("t$i")) })
                        put("outboundTag", "o$i")
                    })
                }
            })
        })
    }

    /** Start a detached xray process for the chunk (never Engine's singleton). */
    private fun startCore(cfgFile: File, logFile: File): Process? {
        val exeName = if (Store.isWindows) "xray.exe" else "xray"
        val exe = File(Store.coreDir, exeName)
        if (!exe.isFile) return null
        return try {
            logFile.parentFile?.mkdirs()
            ProcessBuilder(exe.absolutePath, "run", "-c", cfgFile.absolutePath)
                .directory(Store.coreDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .start()
        } catch (_: Throwable) {
            null
        }
    }

    /** n distinct free loopback ports (bind-probe, closed immediately). */
    private fun allocatePorts(n: Int): List<Int>? {
        val picked = LinkedHashSet<Int>()
        var attempts = 0
        while (picked.size < n && attempts++ < n * 12) {
            try {
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { s ->
                    val port = s.localPort
                    if (port >= PORT_SCAN_MIN) picked.add(port)
                }
            } catch (_: Throwable) { }
        }
        return if (picked.size == n) picked.toList() else null
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 500) }
                return true
            } catch (_: Throwable) { }
            Thread.sleep(200)
        }
        return false
    }
}
