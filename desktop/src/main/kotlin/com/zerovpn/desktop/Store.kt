package com.zerovpn.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext

@Serializable
data class ProfileRec(
    val id: String,
    val name: String,
    val proto: String,
    val link: String,
    val sub: String? = null,
)

@Serializable
data class SubRec(
    val url: String,
    val name: String,
    val lastFetch: Long = 0,
    // subscription-userinfo: bytes used / total / expire epoch-sec (0 = unknown)
    val used: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
)

@Serializable
data class SettingsRec(
    val socksPort: Int = 10808,
    val autoProxy: Boolean = true,
    // "light" | "dark" — v2.3.0: dark violet (the mockup look) is the default
    // on a fresh install; existing users keep their saved choice.
    val theme: String = "dark",
    // v2rayNG-style boot behaviour: connect the selected server at launch
    val autoConnect: Boolean = false,
    // v2rayNG-style behaviour: selecting another server while connected
    // restarts the tunnel with the new server — no manual disconnect needed.
    val autoSwitch: Boolean = true,
    // v2.1.0 — chain the tunnel through a local Tor SOCKS port (Orbot on
    // Android, Tor Expert Bundle / Tor Browser on Windows) before the proxy
    // server. 0 = disabled.
    val torChain: Boolean = false,
    val torPort: Int = 9050,
)

@Serializable
data class DataFile(
    val profiles: List<ProfileRec> = emptyList(),
    val subscriptions: List<SubRec> = emptyList(),
    val settings: SettingsRec = SettingsRec(),
    val selected: String? = null,
    val pings: Map<String, Long> = emptyMap(),
)

enum class ConnStatus { DISCONNECTED, CONNECTING, CONNECTED }

const val APP_VERSION = "2.3.0"

/** Release page opened by the update banner. */
const val RELEASES_URL = "https://github.com/sahandmarami/Zero-VPN/releases/latest"

/**
 * Posts an observable-state write onto the EDT — always DEFERRED, never
 * synchronous. Even when the caller is already on the EDT (composable bodies
 * run on it INSIDE the composition's own snapshot), the block is queued to run
 * in a LATER EDT event, after the running composition applied.
 *
 * Why this exists (v2.0.1 — the "Unsupported concurrent change during
 * composition" crash when opening the Servers screen):
 *
 *   Compose Desktop aborts a composition with
 *   "Unsupported concurrent change during composition" when its snapshot
 *   fails to apply — which happens if the SAME state object was written by
 *   the composition AND, while that composition was running, from somewhere
 *   else (another thread or another snapshot). v2.0.0 still had exactly that
 *   pair on FlagImages.byCode: ZeroFlag -> ensure() wrote the map directly
 *   from composable bodies (composition side), while the flag fetch fallback
 *   wrote it from Dispatchers.IO (outside). Both removed here: every write to
 *   a snapshot map (byCode / byHost / pingMap) now goes through this helper —
 *   one writer (EDT), always outside the composition phase.
 */
fun postStateWrite(block: () -> Unit) {
    javax.swing.SwingUtilities.invokeLater(block)
}

/**
 * Dispatches blocks on the AWT Event Dispatch Thread — the thread that runs
 * Compose Desktop composition. kotlinx-coroutines-swing is NOT on the
 * classpath (Dispatchers.Main throws "Module with the Main dispatcher is
 * missing"), so the app ships this tiny dispatcher instead. Every observable
 * state write is confined here.
 */
val EdtDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) block.run()
        else javax.swing.SwingUtilities.invokeLater(block)
    }
}

// ---------------------------------------------------------------------------
// Store — the single source of truth.
//
// THREADING CONTRACT (v2.0.0, tightened in v2.0.1 — the "Unsupported
// concurrent change during composition" crash fix):
//
//   * Compose Desktop runs composition on the AWT Event Dispatch Thread.
//   * EVERY write to the observable states below happens on Main (EDT) —
//     either directly inside UI event handlers, or inside coroutines
//     launched on [uiScope] / continued with `withContext(Dispatchers.Main)`.
//   * Background (Dispatchers.IO) code computes results and POSTS them back
//     to Main before touching any state. File/network work stays on IO.
//   * NOTHING is ever written during composition itself (the v1.6.0 bug:
//     LocationsScreen assigned Store.selectedGroup inside the composable
//     body, racing the IO coroutines that also wrote Store — Compose threw
//     "Unsupported concurrent change during composition" and the app became
//     unusable).
//   * v2.0.1: writes that may be issued from composable bodies or IO worker
//     threads go through [postStateWrite] — a DEFERRED EDT post (never runs
//     inline, even on the EDT), so a write can never land inside a running
//     composition. This closes the v2.0.0 leftover: FlagImages.byCode was
//     still written by composable bodies (disk-cache/bundled paths) while
//     its network fallback wrote the same map from IO — the exact conflict
//     Compose aborts compositions for, hitting everyone who opened the
//     Servers screen. Store.pingMap (testAll) and GeoLookup.byHost
//     (ensureLookup) got the same treatment.
//
// With one-writer-thread discipline and deferred posts this whole crash
// class is gone.
// ---------------------------------------------------------------------------
object Store {

    /** UI-scope: all observable-state writes are confined here (EDT). */
    val ui = CoroutineScope(SupervisorJob() + EdtDispatcher)
    /** Worker pool for network/file/core operations. */
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    var data by mutableStateOf(DataFile())

    // --- UI state ----------------------------------------------------------
    var view by mutableStateOf("home")            // home | servers | settings | logs
    var status by mutableStateOf(ConnStatus.DISCONNECTED)
    var errorMsg by mutableStateOf<String?>(null)
    var busyMsg by mutableStateOf<String?>(null)  // transient progress line
    var ping by mutableStateOf<Long?>(null)
    var proxyOn by mutableStateOf(false)
    var connectedSince by mutableStateOf<Long?>(null)
    var toast by mutableStateOf<String?>(null)
    var testing by mutableStateOf(false)          // a ping test is in flight
    var windowVisible by mutableStateOf(true)
    /** Servers-screen selected group tab: subscription URL or null = Default. */
    var selectedGroup by mutableStateOf<String?>(null)
    /** true once the user taps a group tab — keeps the choice across screens. */
    var groupChosenExplicit by mutableStateOf(false)
    /** xray.exe version string — filled once at startup by the version probe. */
    var coreVersion by mutableStateOf<String?>(null)
    /** Newer release tag found by the startup update check (null = none). */
    var updateVersion by mutableStateOf<String?>(null)
    /** HTML URL of the newer release (fallback: RELEASES_URL). */
    var updateUrl by mutableStateOf<String?>(null)

    val pingMap = mutableStateMapOf<String, Long>()

    var profiles: List<ProfileRec>
        get() = data.profiles
        set(value) { data = data.copy(profiles = value) }

    var selectedId: String?
        get() = data.selected
        set(value) { data = data.copy(selected = value) }

    val socksPort get() = data.settings.socksPort
    val httpPort get() = data.settings.socksPort + 1
    /** Local API port for the xray stats service (socks + 2). */
    val apiPort get() = data.settings.socksPort + 2
    val autoProxy get() = data.settings.autoProxy

    val selectedProfile: ProfileRec?
        get() = profiles.firstOrNull { it.id == selectedId }

    /** Quota of the subscription the selected profile belongs to (null = none). */
    val selectedQuota: Pair<Long, Long>?
        get() = selectedProfile?.sub?.let { url ->
            subscriptions.firstOrNull { it.url == url }
        }?.takeIf { it.total > 0 }?.let { it.used to it.total }

    // --- Paths -------------------------------------------------------------
    val isWindows: Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    val dataDir: File by lazy {
        val base = System.getenv("APPDATA")
        val dir = if (isWindows && base != null) File(base, "ZeroVPN")
        else File(System.getProperty("user.home"), ".zerovpn")
        dir.mkdirs(); dir
    }

    val logFile: File get() = File(dataDir, "xray.log").also { it.parentFile.mkdirs() }
    val configFile: File get() = File(dataDir, "config.json")

    /** Install root: parent of the folder containing the running jar. */
    val installDir: File by lazy {
        try {
            val src = Store::class.java.protectionDomain.codeSource.location.toURI()
            var dir = File(src).parentFile           // .../app  (or build/classes dir)
            dir?.parentFile?.let { parent ->
                if (File(parent, "core").isDirectory) return@lazy parent
            }
            File(".").absoluteFile
        } catch (_: Throwable) {
            File(".").absoluteFile
        }
    }

    val coreDir: File by lazy {
        val candidates = listOf(File(installDir, "core"), installDir, File("."))
        candidates.firstOrNull { d ->
            File(d, if (isWindows) "xray.exe" else "xray").isFile
        } ?: File(installDir, "core")
    }

    // --- Persistence -------------------------------------------------------
    fun load() {
        try {
            val f = File(dataDir, "data.json")
            if (f.isFile) data = json.decodeFromString(f.readText())
        } catch (_: Throwable) { }
        data = data.copy(profiles = data.profiles.distinctBy { it.id })
        if (profiles.isNotEmpty() && profiles.none { it.id == data.selected }) {
            data = data.copy(selected = profiles.first().id)
        }
        data.pings.forEach { (k, v) -> if (v > 0) pingMap[k] = v }
        // Restore the last known ping of the selected server so the home
        // hero shows a number right after launch instead of "—".
        data.selected?.let { sel -> ping = pingMap[sel]?.takeIf { it > 0 } }
        ThemeState.apply(data.settings.theme == "dark")
        GeoLookup.loadCache()
        profiles.forEach { p ->
            ServerInfo.hostPort(p.link)?.first?.let { GeoLookup.ensureLookup(it, p.name) }
        }
    }

    /** Persist the CURRENT data snapshot — IO only, no state mutation here. */
    fun save() {
        val snapshot = data.copy(pings = pingMap.filterValues { it > 0 })
        io.launch {
            try {
                File(dataDir, "data.json").writeText(json.encodeToString(snapshot))
            } catch (_: Throwable) { }
        }
    }

    fun toast(msg: String) {
        toast = msg
        ui.launch {
            kotlinx.coroutines.delay(3200)
            if (toast == msg) toast = null
        }
    }

    // --- Mutations (Main only) ----------------------------------------------
    fun setSocksPort(p: Int) {
        val port = p.coerceIn(1024, 65530)
        data = data.copy(settings = data.settings.copy(socksPort = port))
        save()
    }

    fun setAutoProxy(v: Boolean) {
        data = data.copy(settings = data.settings.copy(autoProxy = v))
        save()
    }

    fun setAutoConnect(v: Boolean) {
        data = data.copy(settings = data.settings.copy(autoConnect = v))
        save()
    }

    /** autoSwitch only applies from the NEXT start of the tunnel. */
    fun setAutoSwitch(v: Boolean) {
        data = data.copy(settings = data.settings.copy(autoSwitch = v))
        save()
    }

    fun setTorChain(v: Boolean) {
        data = data.copy(settings = data.settings.copy(torChain = v))
        save()
    }

    fun setTorPort(p: Int) {
        val port = p.coerceIn(1024, 65530)
        data = data.copy(settings = data.settings.copy(torPort = port))
        save()
    }

    fun setTheme(dark: Boolean) {
        data = data.copy(settings = data.settings.copy(theme = if (dark) "dark" else "light"))
        save()
        ThemeState.apply(dark)
    }

    /** Subscription record for a group URL (null = Default group). */
    fun subscriptionFor(url: String?): SubRec? =
        if (url == null) null else subscriptions.firstOrNull { it.url == url }

    /** Profiles belonging to a group: null group = manually added ones. */
    fun profilesForGroup(url: String?): List<ProfileRec> =
        profiles.filter { it.sub == url }

    /** All group tabs in display order: subscriptions first, Default last. */
    fun groupTabs(): List<Pair<String?, Int>> {
        val subs = subscriptions.map { it.url to profiles.count { p -> p.sub == it.url } }
        val def = null to profiles.count { it.sub == null }
        return subs + def
    }

    fun renameProfile(id: String, newName: String) {
        profiles = profiles.map { if (it.id == id) it.copy(name = newName) else it }
        save()
    }

    fun updateSubscription(url: String, name: String) {
        data = data.copy(
            subscriptions = subscriptions.map {
                if (it.url == url) it.copy(name = name) else it
            }
        )
        save()
    }

    fun select(id: String?) {
        selectedId = id
        ping = pingMap[id]
        save()
    }

    fun upsertProfile(rec: ProfileRec) {
        profiles = profiles.filterNot { it.id == rec.id } + rec
        save()
    }

    fun deleteProfile(id: String) {
        profiles = profiles.filterNot { it.id == id }
        if (selectedId == id) selectedId = profiles.firstOrNull()?.id
        save()
    }

    fun clearProfiles() {
        profiles = emptyList()
        selectedId = null
        save()
    }

    fun addSubscription(url: String, name: String) {
        val rec = SubRec(url = url, name = name)
        data = data.copy(subscriptions = data.subscriptions.filterNot { it.url == url } + rec)
        save()
    }

    fun removeSubscription(url: String) {
        data = data.copy(subscriptions = data.subscriptions.filterNot { it.url == url })
        profiles = profiles.filterNot { it.sub == url }
        save()
    }

    val subscriptions get() = data.subscriptions

    /** ms > 0 = real delay; ms <= 0 = failed/timeout — recorded as -1 so a
     *  stale number from an earlier test never survives a dead server.
     *  (v2.0.1: posted to the EDT — testAll used to call this from IO worker
     *  threads, a leftover violation of the Main-thread state contract.) */
    fun recordPing(id: String, ms: Long) {
        postStateWrite {
            pingMap[id] = if (ms > 0) ms else -1
            if (id == selectedId) ping = ms.takeIf { it > 0 }
        }
    }
}

/** Human readable byte count, matching the Android traffic formatting. */
fun trafficString(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.size - 1) { v /= 1024.0; i++ }
    return if (i == 0) "$bytes B"
    else String.format("%.1f %s", v, units[i])
}
