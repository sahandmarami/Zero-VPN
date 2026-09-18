package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.extension.removeWhiteSpace
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import com.google.gson.JsonObject
import java.net.URI

object WireguardFmt : FmtBase() {
    /**
     * Parses a URI string into a ProfileItem object.
     *
     * @param str the URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.WIREGUARD)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.rawQuery.isNullOrEmpty()) return null
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()

        config.secretKey = uri.userInfo.orEmpty()
        config.localAddress = queryParam["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.publicKey = queryParam["publickey"].orEmpty()
        config.preSharedKey = queryParam["presharedkey"]?.nullIfBlank()
        config.mtu = Utils.parseInt(queryParam["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.reserved = queryParam["reserved"] ?: "0,0,0"

        return config
    }

    /**
     * Detects AmneziaWG obfuscation parameters (Jc/Jmin/Jmax, S1-S4, H1-H4,
     * I1-I5) inside a WireGuard configuration text.
     */
    private val awgParamRegex = Regex("(?im)^\\s*(Jc|Jmin|Jmax|S1|S2|S3|S4|H1|H2|H3|H4|I1|I2|I3|I4|I5)\\s*=")

    fun hasAwgParams(item: ProfileItem): Boolean {
        if (item.configType != EConfigType.WIREGUARD) return false
        val conf = item.rawConf ?: return false
        return awgParamRegex.containsMatchIn(conf)
    }

    /**
     * Parses a Wireguard configuration file string into a ProfileItem object.
     * Tolerates a UTF-8 BOM, leading whitespace and IPv6 bracketed endpoints.
     *
     * The full configuration text is preserved in [ProfileItem.rawConf] so the
     * AmneziaWG engine (DNS, AllowedIPs, obfuscation parameters, keepalive)
     * can run it verbatim.
     *
     * @param str the Wireguard configuration file string to parse
     * @return the parsed ProfileItem object
     */
    fun parseWireguardConfFile(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.WIREGUARD)
        val normalized = str.replace("\uFEFF", "").trim()
        config.rawConf = normalized

        val interfaceParams: MutableMap<String, String> = mutableMapOf()
        val peerParams: MutableMap<String, String> = mutableMapOf()

        var currentSection: String? = null

        normalized.lines().forEach { line ->
            val trimmedLine = line.trim()

            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                return@forEach
            }

            when {
                trimmedLine.startsWith("[Interface]", ignoreCase = true) -> currentSection = "Interface"
                trimmedLine.startsWith("[Peer]", ignoreCase = true) -> currentSection = "Peer"
                else -> {
                    if (currentSection != null) {
                        val parts = trimmedLine.split("=", limit = 2).map { it.trim() }
                        if (parts.size == 2) {
                            val key = parts[0].lowercase()
                            val value = parts[1]
                            when (currentSection) {
                                "Interface" -> interfaceParams[key] = value
                                "Peer" -> peerParams[key] = value
                            }
                        }
                    }
                }
            }
        }

        config.secretKey = interfaceParams["privatekey"].orEmpty()
        config.remarks = System.currentTimeMillis().toString()
        config.localAddress = interfaceParams["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.mtu = Utils.parseInt(interfaceParams["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.publicKey = peerParams["publickey"].orEmpty()
        config.preSharedKey = peerParams["presharedkey"]?.nullIfBlank()
        val endpoint = peerParams["endpoint"].orEmpty()
        // Handles both "host:port" and bracketed IPv6 "[v6addr]:port".
        val endpointParts = if (endpoint.startsWith("[")) {
            val close = endpoint.indexOf(']')
            if (close > 0 && endpoint.length > close + 1 && endpoint[close + 1] == ':') {
                listOf(endpoint.substring(1, close), endpoint.substring(close + 2))
            } else {
                listOf(endpoint)
            }
        } else {
            endpoint.split(":", limit = 2)
        }
        if (endpointParts.size == 2) {
            config.server = endpointParts[0]
            config.serverPort = endpointParts[1]
        } else {
            config.server = endpoint
            config.serverPort = ""
        }
        config.reserved = peerParams["reserved"] ?: "0,0,0"

        return config
    }

    /**
     * Extracts the TUN-level settings needed to run a WireGuard/AmneziaWG
     * configuration through the embedded AmneziaWG engine: interface
     * addresses, DNS servers, AllowedIPs (routes) and MTU.
     *
     * @param conf the raw [Interface]/[Peer] configuration text
     * @return the extracted settings, or null when the text has no [Interface]
     */
    fun extractTunnelSettings(conf: String): WgTunnelSettings? {
        var current: MutableMap<String, String>? = null
        val iface = mutableMapOf<String, String>()
        val peer = mutableMapOf<String, String>()

        conf.replace("\uFEFF", "").lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            when {
                trimmed.startsWith("[Interface]", ignoreCase = true) -> current = iface
                trimmed.startsWith("[Peer]", ignoreCase = true) -> current = peer
                else -> {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2 && current != null) {
                        current!![parts[0].trim().lowercase()] = parts[1].trim()
                    }
                }
            }
        }

        if (iface.isEmpty()) return null

        val addresses = iface["address"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val dnsServers = iface["dns"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val allowedIps = peer["allowedips"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val mtu = iface["mtu"]?.toIntOrNull()

        return WgTunnelSettings(addresses, dnsServers, allowedIps, mtu)
    }

    /**
     * Rebuilds the stored configuration text after the user edited a profile
     * in the WireGuard editor: edited fields (keys, address, MTU, endpoint)
     * override the stored values while DNS, obfuscation parameters,
     * AllowedIPs and keepalive are carried over from the previous text.
     *
     * Returns null when there is no previous configuration (plain WireGuard
     * profiles run through the Xray core and need no stored text).
     */
    fun rebuildRawConf(
        previous: String?,
        secretKey: String?,
        publicKey: String?,
        preSharedKey: String?,
        localAddress: String?,
        mtu: Int?,
        server: String?,
        serverPort: String?
    ): String? {
        if (previous.isNullOrBlank()) return null

        var current: MutableMap<String, String>? = null
        val iface = mutableMapOf<String, String>()
        val peer = mutableMapOf<String, String>()
        previous.replace("\uFEFF", "").lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            when {
                trimmed.startsWith("[Interface]", ignoreCase = true) -> current = iface
                trimmed.startsWith("[Peer]", ignoreCase = true) -> current = peer
                else -> {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2 && current != null) {
                        current!![parts[0].trim().lowercase()] = parts[1].trim()
                    }
                }
            }
        }

        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        // Edited values win; fall back to the stored ones.
        val privateKey = secretKey?.trim().takeUnless { it.isNullOrEmpty() } ?: iface["privatekey"]
        if (!privateKey.isNullOrEmpty()) sb.appendLine("PrivateKey = $privateKey")
        val address = localAddress?.trim().takeUnless { it.isNullOrEmpty() } ?: iface["address"]
        if (!address.isNullOrEmpty()) sb.appendLine("Address = $address")
        iface["dns"]?.let { if (it.isNotBlank()) sb.appendLine("DNS = $it") }
        val mtuValue = mtu ?: iface["mtu"]?.toIntOrNull()
        if (mtuValue != null && mtuValue > 0) sb.appendLine("MTU = $mtuValue")
        // Preserve every AmneziaWG obfuscation parameter line verbatim.
        previous.replace("\uFEFF", "").lines().forEach { line ->
            if (awgParamRegex.containsMatchIn(line)) {
                sb.appendLine(line.trim())
            }
        }

        sb.appendLine()
        sb.appendLine("[Peer]")
        val pubKey = publicKey?.trim().takeUnless { it.isNullOrEmpty() } ?: peer["publickey"]
        if (!pubKey.isNullOrEmpty()) sb.appendLine("PublicKey = $pubKey")
        val psk = preSharedKey?.trim().takeUnless { it.isNullOrEmpty() } ?: peer["presharedkey"]
        if (!psk.isNullOrEmpty()) sb.appendLine("PresharedKey = $psk")
        val allowed = peer["allowedips"]
        if (!allowed.isNullOrEmpty()) sb.appendLine("AllowedIPs = $allowed")
        val endpoint = if (!server.isNullOrEmpty()) {
            Utils.getIpv6Address(server) + ":" + serverPort.orEmpty()
        } else {
            peer["endpoint"]
        }
        if (!endpoint.isNullOrEmpty()) sb.appendLine("Endpoint = $endpoint")
        val keepalive = peer["persistentkeepalive"]
        if (!keepalive.isNullOrEmpty()) sb.appendLine("PersistentKeepalive = $keepalive")

        return sb.toString().trim() + "\n"
    }

    /**
     * Extracts the embedded WireGuard .conf text from an AmneziaVPN backup
     * JSON (the ".vpn" export / shared text):
     * `{"containers":[{"awg"|"wg":{"last_config":"{ ... \"config\": \"[Interface]...\" }"}}]}`
     *
     * Liberal matching: scans every container entry for a `last_config` field
     * whose decoded `config` looks like a WireGuard conf, so plain wg, awg and
     * future container namings all work.
     *
     * @param text the pasted Amnezia backup JSON text
     * @return the embedded [Interface]/[Peer] conf text, or null when absent
     */
    fun extractAmneziaWireguardConf(text: String): String? {
        return try {
            val root = JsonUtil.parseString(text) ?: return null
            val containers = root.getAsJsonArray("containers") ?: return null
            for (element in containers) {
                val container = element as? JsonObject ?: continue
                for ((_, value) in container.entrySet()) {
                    val entry = value as? JsonObject ?: continue
                    val lcAny = entry.get("last_config") ?: continue
                    val lastConfig = when {
                        lcAny.isJsonObject -> lcAny.asJsonObject
                        lcAny.isJsonPrimitive -> JsonUtil.parseString(lcAny.asString)
                        else -> null
                    } ?: continue
                    val conf = lastConfig.get("config")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.replace("\uFEFF", "")
                    if (!conf.isNullOrBlank() && conf.contains("[Interface]", ignoreCase = true)) {
                        return conf
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }


    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()

        dicQuery["publickey"] = config.publicKey.orEmpty()
        if (config.reserved != null) {
            dicQuery["reserved"] = config.reserved.removeWhiteSpace().orEmpty()
        }
        dicQuery["address"] = config.localAddress.removeWhiteSpace().orEmpty()
        if (config.mtu != null) {
            dicQuery["mtu"] = config.mtu.toString()
        }
        if (config.preSharedKey != null) {
            dicQuery["presharedkey"] = config.preSharedKey.removeWhiteSpace().orEmpty()
        }

        return toUri(config, config.secretKey, dicQuery)
    }
}

/**
 * TUN-level settings extracted from a WireGuard/AmneziaWG configuration text,
 * used to configure [android.net.VpnService.Builder] for profiles that run
 * through the embedded AmneziaWG engine.
 */
data class WgTunnelSettings(
    /** Interface addresses in CIDR form, e.g. "172.16.0.2/32". */
    val addresses: List<String>,
    /** DNS servers from the [Interface] section. */
    val dnsServers: List<String>,
    /** AllowedIPs of the first peer, used as VPN routes. */
    val allowedIps: List<String>,
    /** MTU from the [Interface] section, null when absent. */
    val mtu: Int?
)
