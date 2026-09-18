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
     * Parses a Wireguard configuration file string into a ProfileItem object.
     * Tolerates a UTF-8 BOM, leading whitespace and IPv6 bracketed endpoints.
     *
     * @param str the Wireguard configuration file string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parseWireguardConfFile(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.WIREGUARD)

        val interfaceParams: MutableMap<String, String> = mutableMapOf()
        val peerParams: MutableMap<String, String> = mutableMapOf()

        var currentSection: String? = null

        str.replace("\uFEFF", "").lines().forEach { line ->
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
