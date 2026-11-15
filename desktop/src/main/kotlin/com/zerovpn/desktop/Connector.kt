package com.zerovpn.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Connector — the tunnel lifecycle orchestrator.
 *
 * v2.0.0 responsibilities, all with the Main-thread state contract:
 *  - connect / disconnect / auto-switch (select another server while up)
 *  - watchdog: detects an unexpected core exit, restores the UI + system
 *    proxy and surfaces the core's own error line
 *  - live traffic loop: polls the core's stats API every second while up
 *  - ping tests (current through the tunnel, all servers via bounded TCP)
 *  - subscription / clipboard imports (IO work, Main-thread apply)
 */
object Connector {

    private var watchdog: Job? = null
    private var statsLoop: Job? = null

    fun toggle() = when (Store.status) {
        ConnStatus.DISCONNECTED -> connect()
        ConnStatus.CONNECTED -> disconnect()
        ConnStatus.CONNECTING -> { }
    }

    // -------------------------------------------------------------------------
    // Connect
    // -------------------------------------------------------------------------
    fun connect() {
        val p = Store.selectedProfile
        if (p == null) {
            Store.toast("هیچ سروری انتخاب نشده — از بخش «سرورها» اضافه کنید")
            return
        }
        if (Store.status == ConnStatus.CONNECTING) return
        Store.status = ConnStatus.CONNECTING
        Store.errorMsg = null
        Store.ui.launch {
            val socks = Store.socksPort
            val http = Store.httpPort
            val api = Store.apiPort
            // v2.1.0 — Tor chain: if enabled, a REAL local Tor SOCKS port must
            // answer a SOCKS5 handshake before we build the config; the tunnel
            // then dials through Tor (Orbot / Tor Expert Bundle / Tor Browser).
            var torPort: Int? = null
            if (Store.data.settings.torChain) {
                val alive = withContext(Dispatchers.IO) {
                    TorProbe.findAliveSocks(Store.data.settings.torPort)
                }
                if (alive == null) {
                    Store.status = ConnStatus.DISCONNECTED
                    Store.toast(
                        "تور پیدا نشد — Orbot یا Tor را اجرا کنید (پورت 9050/9150) " +
                            "و مطمئن شوید گزینه \u200cاجازه اتصال محلی\u200c فعال است"
                    )
                    return@launch
                }
                torPort = alive
            }
            val cfg = withContext(Dispatchers.IO) {
                XrayConfig.buildFullConfig(p.link, socks, http, api, torPort)
            }
            if (cfg == null) {
                Store.status = ConnStatus.DISCONNECTED
                Store.toast("این نوع کانفیگ پشتیبانی نمی‌شود (vmess/vless/trojan/ss)")
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    Store.configFile.writeText(
                        XrayConfig.json.encodeToString(
                            kotlinx.serialization.json.JsonObject.serializer(), cfg
                        )
                    )
                }
            } catch (t: Throwable) {
                Store.status = ConnStatus.DISCONNECTED
                Store.toast("نوشتن کانفیگ ممکن نشد: ${t.message}")
                return@launch
            }
            // v2.4.0 — PREFLIGHT: an orphan xray.exe of a force-killed
            // previous session owns our fixed ports → the fresh core cannot
            // bind, the UI says "connected" and the browser talks into the
            // dead orphan tunnel (the "وصل میشه ولی کار نمیکنه" report).
            // Sweep the leftovers first; a FOREIGN occupier is reported
            // instead of silently losing the bind race.
            val foreignPort = withContext(Dispatchers.IO) { PortGuard.freeOurPorts() }
            if (foreignPort != null) {
                Store.status = ConnStatus.DISCONNECTED
                Store.toast("پورت $foreignPort توسط برنامهٔ دیگری اشغال است — پورت را در تنظیمات عوض کنید")
                return@launch
            }
            val proc = withContext(Dispatchers.IO) { Engine.start(Store.configFile) }
            if (proc == null) {
                Store.status = ConnStatus.DISCONNECTED
                Store.toast("اجرای هسته ممکن نشد — xray در پوشه core پیدا نشد")
                return@launch
            }
            if (!Engine.waitForPort(socks, 12000)) {
                withContext(Dispatchers.IO) { Engine.stop() }
                Store.status = ConnStatus.DISCONNECTED
                val cause = withContext(Dispatchers.IO) { Engine.lastCoreError() }
                Store.toast(
                    if (cause != null) "اتصال برقرار نشد — $cause"
                    else "اتصال برقرار نشد — سرور در دسترس نیست یا پورت اشغال است"
                )
                return@launch
            }
            // v2.4.0 — OWNERSHIP CHECK. The port wait only proves SOMETHING
            // listens on the socks port; if an orphan xray.exe of a
            // force-killed session won the bind race, the "connected" state
            // would be the dead orphan's tunnel and no traffic would flow
            // (the exact "وصل میشه ولی کار نمیکنه" report). The listener PID
            // must be OUR core process — otherwise kill the impostor (when
            // it is another xray.exe) and restart once.
            val listenPid = withContext(Dispatchers.IO) { PortGuard.listenerPid(socks) }
            if (listenPid != null && listenPid != proc.pid()) {
                withContext(Dispatchers.IO) {
                    if (PortGuard.imageOf(listenPid) == "xray.exe") {
                        PortGuard.killPid(listenPid)
                        Engine.stop()
                        PortGuard.freeOurPorts()
                    }
                }
                val retry = withContext(Dispatchers.IO) { Engine.start(Store.configFile) }
                if (retry == null || !Engine.waitForPort(socks, 12000)) {
                    withContext(Dispatchers.IO) { Engine.stop() }
                    Store.status = ConnStatus.DISCONNECTED
                    Store.toast("اتصال برقرار نشد — پورت $socks در اختیار برنامهٔ دیگری است؛ پورت را در تنظیمات عوض کنید")
                    return@launch
                }
                val recheck = withContext(Dispatchers.IO) { PortGuard.listenerPid(socks) }
                if (recheck != null && recheck != retry.pid()) {
                    withContext(Dispatchers.IO) { Engine.stop() }
                    Store.status = ConnStatus.DISCONNECTED
                    Store.toast("اتصال برقرار نشد — پورت $socks در اختیار برنامهٔ دیگری است؛ پورت را در تنظیمات عوض کنید")
                    return@launch
                }
            }
            if (Store.autoProxy && Store.isWindows) {
                val ok = withContext(Dispatchers.IO) { SysProxy.enable(http) }
                Store.proxyOn = ok
                if (!ok) Store.toast("پراکسی سیستم تنظیم نشد — پراکسی را دستی تنظیم کنید")
            }
            Store.status = ConnStatus.CONNECTED
            Store.connectedSince = System.currentTimeMillis()
            TrafficTracker.reset()
            startWatchdog()
            startStatsLoop()
            // First real ping through the fresh tunnel (gstatic → cloudflare fallback).
            var ms = withContext(Dispatchers.IO) { PingTest.viaSocks(socks) }
            if (ms < 0) {
                ms = withContext(Dispatchers.IO) {
                    PingTest.viaSocks(socks, timeoutMs = 5000, url = "https://cp.cloudflare.com/generate_204")
                }
            }
            Store.recordPing(p.id, ms)
            ZeroStatsTracker.record(ms)
            // v2.4.0 — HONEST HEALTH CHECK. "Connected" so far only meant the
            // inbound port answers; it says nothing about the server actually
            // carrying traffic — precisely the silent "وصل میشه ولی کار
            // نمیکنه" state. When the fresh tunnel cannot fetch its own
            // generate_204, SAY SO instead of pretending everything is fine.
            if (ms < 0) {
                Store.toast(
                    "اتصال برقرار شد اما ترافیک از سرور عبور نمی‌کند — " +
                        "این سرور را عوض کنید یا «تست پینگ» را بگیرید"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Disconnect (user action) — cancel watchdog + stats first
    // -------------------------------------------------------------------------
    fun disconnect() {
        Store.ui.launch {
            watchdog?.cancel(); watchdog = null
            statsLoop?.cancel(); statsLoop = null
            withContext(Dispatchers.IO) { Engine.stop() }
            if (Store.proxyOn && Store.isWindows) {
                withContext(Dispatchers.IO) { SysProxy.disable() }
                Store.proxyOn = false
            }
            Store.status = ConnStatus.DISCONNECTED
            Store.connectedSince = null
            TrafficTracker.reset()
        }
    }

    /** v2rayNG behaviour: switch server while the tunnel is up → hot restart. */
    fun switchServer() {
        if (Store.status != ConnStatus.CONNECTED) return
        if (!Store.data.settings.autoSwitch) return
        Store.ui.launch {
            watchdog?.cancel(); watchdog = null
            statsLoop?.cancel(); statsLoop = null
            withContext(Dispatchers.IO) { Engine.stop() }
            if (Store.proxyOn && Store.isWindows) {
                withContext(Dispatchers.IO) { SysProxy.disable() }
                Store.proxyOn = false
            }
            TrafficTracker.reset()
            // connect() sets CONNECTING itself and picks the just-selected profile.
            connect()
        }
    }

    // -------------------------------------------------------------------------
    // Watchdog — core died unexpectedly (crash / kill / network stack error)
    // -------------------------------------------------------------------------
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = Store.ui.launch {
            val proc = withContext(Dispatchers.IO) {
                Engine.waitForExit(Long.MAX_VALUE)
            }
            // Engine.waitForExit resolves on process exit; if the user (or
            // switchServer/disconnect) already tore the tunnel down, the
            // status has moved on and we do nothing.
            if (Store.status == ConnStatus.CONNECTED) {
                watchdog = null
                statsLoop?.cancel(); statsLoop = null
                if (Store.proxyOn && Store.isWindows) {
                    withContext(Dispatchers.IO) { SysProxy.disable() }
                    Store.proxyOn = false
                }
                Store.status = ConnStatus.DISCONNECTED
                Store.connectedSince = null
                TrafficTracker.reset()
                val cause = withContext(Dispatchers.IO) { Engine.lastCoreError() }
                Store.toast(if (cause != null) "ارتباط با هسته قطع شد — $cause" else "ارتباط با هسته قطع شد")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Live traffic — 1 s polls of the core's local stats API
    // -------------------------------------------------------------------------
    private fun startStatsLoop() {
        statsLoop?.cancel()
        statsLoop = Store.ui.launch {
            while (isActive && Store.status == ConnStatus.CONNECTED) {
                val sample = withContext(Dispatchers.IO) {
                    StatsClient.queryInboundTraffic(Store.apiPort)
                }
                if (sample != null) TrafficTracker.sample(sample.first, sample.second)
                delay(1000)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Ping tests
    // -------------------------------------------------------------------------
    /** Real ping of the current server through the live tunnel. */
    fun pingCurrent() {
        if (Store.testing) return
        if (Store.status != ConnStatus.CONNECTED) {
            testAll()  // like the phone app: offline pill tests every server
            return
        }
        Store.testing = true
        Store.ui.launch {
            Store.busyMsg = "در حال تست پینگ…"
            val ms = withContext(Dispatchers.IO) { PingTest.viaSocks(Store.socksPort) }
            Store.busyMsg = null
            Store.selectedId?.let { id ->
                Store.recordPing(id, ms)
                ZeroStatsTracker.record(ms)
            }
            Store.testing = false
            if (ms < 0) Store.toast("تست پینگ ناموفق بود")
        }
    }

    /** hysteria2 profiles have no TCP listener (UDP/QUIC-only) — only they
     *  are measured through the core; everything else is a plain TCP dial. */
    fun usesCorePing(p: ProfileRec): Boolean = p.proto == "hysteria2"

    /**
     * v2.4.0 — V2Box/v2rayNG-STYLE LIST PING. The user rejected the
     * through-the-core numbers («پینگ رو اشتباه میده و عدد خیلی بالایی
     * میده»): a generate_204 fetch includes TLS-to-target + HTTP round trip,
     * which is a page-load time, not a ping — other apps show the TCP
     * handshake RTT and that is what this list now shows too:
     *
     *  - every TCP-based profile: PingTest.tcpBounded — the server's REAL
     *    address (DoH answered FIRST, poisoned system resolver second), all
     *    IPs IPv4-first, 5 s deadline per dial, 6 servers in parallel;
     *  - hysteria2 profiles: NO TCP listener (UDP/QUIC-only) — they keep the
     *    through-the-core delay via CorePing (chunked official core), so a
     *    hy2 row never shows a fake تایم‌اوت;
     *  - a failed dial records -1 (تایم‌اوت) — a stale number from an older
     *    test never survives a dead server.
     */
    fun testAll() {
        if (Store.testing || Store.profiles.isEmpty()) return
        Store.testing = true
        Store.ui.launch {
            val total = Store.profiles.size
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            fun bump() {
                val d = done.incrementAndGet()
                postStateWrite { Store.busyMsg = "در حال تست پینگ سرورها… $d/$total" }
            }
            postStateWrite { Store.busyMsg = "در حال تست پینگ سرورها… 0/$total" }
            try {
                val tcp = Store.profiles.filter { !usesCorePing(it) }
                val hy2 = Store.profiles.filter { usesCorePing(it) }
                kotlinx.coroutines.coroutineScope {
                    val sem = Semaphore(6)
                    val tcpJobs = tcp.mapNotNull { p ->
                        ServerInfo.hostPort(p.link)?.let { (host, port) ->
                            launch(Dispatchers.IO) {
                                sem.withPermit {
                                    Store.recordPing(p.id, PingTest.tcpBounded(host, port))
                                    bump()
                                }
                            }
                        }
                    }
                    val hy2Job = if (hy2.isEmpty()) null else launch(Dispatchers.IO) {
                        CorePing.testAll(
                            hy2,
                            record = { id, ms -> Store.recordPing(id, ms) },
                            progress = { _, _ -> bump() },
                        )
                    }
                    tcpJobs.joinAll()
                    hy2Job?.join()
                }
            } catch (t: Throwable) {
                Store.toast("خطا در تست سرورها: ${t.message?.take(80) ?: t.javaClass.simpleName}")
            }
            postStateWrite { Store.busyMsg = null }
            Store.testing = false
            Store.save()
            Store.toast("تست پینگ تمام شد")
        }
    }

    // -------------------------------------------------------------------------
    // Subscriptions / clipboard imports
    // -------------------------------------------------------------------------
    fun updateSubs() {
        if (Store.testing) return
        Store.testing = true
        Store.ui.launch {
            Store.busyMsg = "بروزرسانی اشتراک‌ها…"
            val subs = Store.subscriptions.toList()
            var added = 0
            var err: String? = null
            val viaTunnel = Store.status == ConnStatus.CONNECTED
            for (sub in subs) {
                Store.busyMsg = "در حال دریافت: ${sub.name}"
                val res = withContext(Dispatchers.IO) {
                    Profiles.fetchSub(sub.url, socksPort = if (viaTunnel) Store.socksPort else null)
                }
                if (res.error != null) {
                    err = res.error
                } else {
                    applySubResult(res)
                    added += res.profiles.size
                }
            }
            Store.busyMsg = null
            Store.testing = false
            Store.toast(
                when {
                    added > 0 && err == null -> "$added سرور دریافت شد"
                    added > 0 -> "$added سرور دریافت شد (یک اشتراک خطا داد: $err)"
                    else -> "خطا در دریافت: $err"
                }
            )
        }
    }

    fun refreshSingleSub(url: String) {
        val sub = Store.subscriptionFor(url) ?: return
        Store.testing = true
        Store.ui.launch {
            Store.busyMsg = "در حال دریافت: ${sub.name}"
            val res = withContext(Dispatchers.IO) {
                Profiles.fetchSub(
                    url,
                    socksPort = if (Store.status == ConnStatus.CONNECTED) Store.socksPort else null,
                )
            }
            Store.busyMsg = null
            Store.testing = false
            if (res.error != null) {
                Store.toast("خطا در دریافت: ${res.error}")
            } else {
                applySubResult(res)
                Store.toast("${res.profiles.size} سرور دریافت شد")
            }
        }
    }

    fun addSub(url: String) {
        Store.testing = true
        Store.ui.launch {
            Store.busyMsg = "در حال دریافت اشتراک…"
            val res = withContext(Dispatchers.IO) {
                Profiles.fetchSub(
                    url,
                    socksPort = if (Store.status == ConnStatus.CONNECTED) Store.socksPort else null,
                )
            }
            if (res.error != null || res.profiles.isEmpty()) {
                Store.busyMsg = null
                Store.testing = false
                Store.toast(
                    if (res.error != null) "خطا در دریافت اشتراک: ${res.error}"
                    else "هیچ سرور معتبری در اشتراک پیدا نشد"
                )
                return@launch
            }
            Store.addSubscription(url, "اشتراک ${Store.subscriptions.size + 1}")
            applySubResult(res)
            Store.busyMsg = null
            Store.testing = false
            Store.toast("${res.profiles.size} سرور از اشتراک اضافه شد")
        }
    }

    /** Merge one subscription's result into the Store (Main thread). */
    private fun applySubResult(res: SubFetchResult) {
        // Replace ONLY this subscription's profiles — other groups untouched.
        val others = Store.profiles.filter { it.sub != res.url }
        Store.profiles = others + res.profiles
        Store.data = Store.data.copy(
            subscriptions = Store.subscriptions.map {
                if (it.url == res.url) it.copy(
                    lastFetch = System.currentTimeMillis(),
                    used = res.info?.used ?: it.used,
                    total = res.info?.total ?: it.total,
                    expire = res.info?.expire ?: it.expire,
                ) else it
            }
        )
        if ((Store.selectedId == null || Store.profiles.none { it.id == Store.selectedId }) &&
            Store.profiles.isNotEmpty()
        ) {
            Store.selectedId = Store.profiles.first().id
        }
        Store.save()
    }

    fun importClipboard() {
        val text = Profiles.clipboardText()
        if (text.isNullOrBlank()) {
            Store.toast("کلیپ‌بورد خالی است")
            return
        }
        Store.ui.launch {
            val recs = withContext(Dispatchers.IO) { Profiles.importText(text) }
            if (recs.isEmpty()) Store.toast("هیچ کانفیگ معتبری در کلیپ‌بورد نبود")
            else {
                recs.forEach { Store.upsertProfile(it) }
                Store.toast("${recs.size} کانفیگ از کلیپ‌بورد اضافه شد")
            }
        }
    }

    fun importManual(text: String) {
        Store.ui.launch {
            val recs = withContext(Dispatchers.IO) { Profiles.importText(text) }
            if (recs.isEmpty()) Store.toast("کانفیگ معتبری پیدا نشد (vmess/vless/trojan/ss)")
            else {
                recs.forEach { Store.upsertProfile(it) }
                Store.toast("${recs.size} کانفیگ اضافه شد")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Update check (v2.1.0) — the desktop app had NO updater at all, so users
    // kept running builds whose bugs were already fixed in newer releases
    // (the persistent "servers screen errors" report was v2.0.0's crash that
    // v2.0.1 had already fixed). Every launch now checks the GitHub latest
    // release silently; the Home screen shows a banner when a newer build
    // exists, and Settings has a manual re-check row.
    // -------------------------------------------------------------------------
    fun checkForUpdate() {
        Store.io.launch {
            val found = UpdateChecker.probe()
            if (found != null) {
                Store.ui.launch {
                    Store.updateVersion = found.first
                    Store.updateUrl = found.second
                }
            }
        }
    }

    fun openReleasePage() {
        val url = Store.updateUrl ?: RELEASES_URL
        try {
            if (Store.isWindows) {
                ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            } else if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            } else {
                setClipboard(url)
                Store.toast("لینک دانلود کپی شد")
            }
        } catch (_: Throwable) {
            setClipboard(url)
            Store.toast("لینک دانلود کپی شد")
        }
    }
}

/**
 * GitHub latest-release probe — silent, short-deadline, never throws.
 * api.github.com may itself be unreachable on some networks; the check is
 * best-effort and the manual "بررسی بروزرسانی" row in Settings retries it.
 */
object UpdateChecker {
    fun probe(): Pair<String, String>? = try {
        // v2.2.0: NO_PROXY — the update check used to inherit the Windows
        // system proxy; with a stale proxy registered it never reported a
        // newer release (or worse, failed noisily).
        val con = java.net.URL("https://api.github.com/repos/sahandmarami/Zero-VPN/releases/latest")
            .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        con.connectTimeout = 8000
        con.readTimeout = 8000
        con.setRequestProperty("User-Agent", "ZeroVPN/$APP_VERSION")
        con.setRequestProperty("Accept", "application/vnd.github+json")
        if (con.responseCode !in 200..299) null
        else {
            val body = con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = kotlinx.serialization.json.Json.parseToJsonElement(body)
                .let { it as? kotlinx.serialization.json.JsonObject }
            val tag = json?.get("tag_name")?.toString()?.trim('"')
            val html = json?.get("html_url")?.toString()?.trim('"')
            val cleanTag = tag?.removePrefix("v")
            if (!cleanTag.isNullOrBlank() && isNewer(cleanTag, APP_VERSION)) {
                cleanTag to (html?.takeIf { it.isNotBlank() } ?: RELEASES_URL)
            } else null
        }
    } catch (_: Throwable) {
        null
    }

    /** Semantic-ish compare: "2.1.0" > "2.0.1". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

// ---------------------------------------------------------------------------
// Connection stats — ping tracker (jitter / loss), ported from the Android
// ZeroStatsTracker so the home cards show the same numbers as the phone.
// ---------------------------------------------------------------------------
object ZeroStatsTracker {
    var lastPing by mutableStateOf<Long?>(null)
    var currentPing by mutableStateOf<Long?>(null)
    var jitterMs by mutableStateOf<Long?>(null)
    var attempts by mutableStateOf(0)
    var failures by mutableStateOf(0)

    val lossPercent: Int
        get() = if (attempts == 0) 0 else (failures * 100 + attempts / 2) / attempts

    /** Record a real-ping outcome for the current server (delay < 0 = failure). */
    fun record(delayMillis: Long) {
        attempts++
        if (delayMillis < 0) {
            failures++
            return
        }
        lastPing = currentPing
        currentPing = delayMillis
        lastPing?.let { prev ->
            val sample = kotlin.math.abs(delayMillis - prev)
            jitterMs = (jitterMs?.let { old -> (old * 3 + sample) / 4 } ?: sample)
                .coerceAtLeast(0)
        }
    }

    fun reset() {
        lastPing = null; currentPing = null; jitterMs = null
        attempts = 0; failures = 0
    }
}
