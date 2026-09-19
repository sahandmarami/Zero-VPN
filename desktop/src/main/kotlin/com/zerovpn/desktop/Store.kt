package com.zerovpn.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ProfileRec(
    val id: String,
    val name: String,
    val proto: String,
    val link: String,
    val sub: String? = null,
)

@Serializable
data class SubRec(val url: String, val name: String, val lastFetch: Long = 0)

@Serializable
data class SettingsRec(val socksPort: Int = 10808, val autoProxy: Boolean = true)

@Serializable
data class DataFile(
    val profiles: List<ProfileRec> = emptyList(),
    val subscriptions: List<SubRec> = emptyList(),
    val settings: SettingsRec = SettingsRec(),
    val selected: String? = null,
    val pings: Map<String, Long> = emptyMap(),
)

enum class ConnStatus { DISCONNECTED, CONNECTING, CONNECTED }

/** Singleton app state, persisted as JSON in the user data directory. */
object Store {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    var data = DataFile()

    // --- UI state ----------------------------------------------------------
    var view by mutableStateOf("home")            // home | locations | settings
    var status by mutableStateOf(ConnStatus.DISCONNECTED)
    var errorMsg by mutableStateOf<String?>(null)
    var busyMsg by mutableStateOf<String?>(null)  // transient progress line
    var ping by mutableStateOf<Long?>(null)
    var proxyOn by mutableStateOf(false)
    var connectedSince by mutableStateOf<Long?>(null)
    var toast by mutableStateOf<String?>(null)

    val pingMap = mutableStateMapOf<String, Long>()

    var profiles: List<ProfileRec>
        get() = data.profiles
        set(value) { data = data.copy(profiles = value) }

    var selectedId: String?
        get() = data.selected
        set(value) { data = data.copy(selected = value) }

    val socksPort get() = data.settings.socksPort
    val httpPort get() = data.settings.socksPort + 1
    val autoProxy get() = data.settings.autoProxy

    val selectedProfile: ProfileRec?
        get() = profiles.firstOrNull { it.id == selectedId }

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
                // In the shipped layout the jar lives in <root>/app/
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
        data.pings.forEach { (k, v) -> if (v > 0) pingMap[k] = v }
    }

    fun save() {
        try {
            data = data.copy(pings = pingMap.filterValues { it > 0 })
            File(dataDir, "data.json").writeText(json.encodeToString(data))
        } catch (_: Throwable) { }
    }

    fun toast(msg: String) {
        toast = msg
        scope.launch {
            kotlinx.coroutines.delay(3500)
            if (toast == msg) toast = null
        }
    }

    // --- Mutations ----------------------------------------------------------
    fun setSocksPort(p: Int) {
        val port = p.coerceIn(1024, 65530)
        data = data.copy(settings = data.settings.copy(socksPort = port))
        save()
    }

    fun setAutoProxy(v: Boolean) {
        data = data.copy(settings = data.settings.copy(autoProxy = v))
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

    fun recordPing(id: String, ms: Long) {
        if (ms > 0) {
            pingMap[id] = ms
            if (id == selectedId) ping = ms
        }
    }
}
