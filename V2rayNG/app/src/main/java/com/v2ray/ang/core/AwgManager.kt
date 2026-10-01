package com.v2ray.ang.core

import android.app.Service
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.WgTunnelSettings
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * Zero VPN: AmneziaWG engine manager.
 *
 * The new core build (PattNG-compatible Xray) does not bundle the embedded
 * AmneziaWG engine, so AWG profiles (configurations with Amnezia obfuscation
 * parameters) now fail through the normal start-failure path — the same
 * behavior as PattNG, which has no AWG support either. The manager stays so
 * the service lifecycle keeps treating AWG profiles safely (they are never
 * "running", the TUN builder ignores their settings and the user gets a
 * clear start-failure toast instead of a crash).
 */
object AwgManager {

    @Volatile
    private var handle: Long = 0L

    fun isActive(): Boolean = handle != 0L

    /** True when the currently selected profile must run through the AWG engine. */
    fun isAwgProfileSelected(): Boolean {
        return try {
            val guid = MmkvManager.getSelectServer() ?: return false
            val config = MmkvManager.decodeServerConfig(guid) ?: return false
            WireguardFmt.hasAwgParams(config)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * TUN-level settings (addresses / DNS / AllowedIPs / MTU) of the selected
     * AWG profile, used by [com.v2ray.ang.service.CoreVpnService] to build the
     * VPN interface. Null when the selected profile is not an AWG profile.
     */
    fun currentTunnelSettings(): WgTunnelSettings? {
        return try {
            val guid = MmkvManager.getSelectServer() ?: return null
            val config = MmkvManager.decodeServerConfig(guid) ?: return null
            val conf = config.rawConf ?: return null
            WireguardFmt.extractTunnelSettings(conf)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Starts the engine on the VPN interface. The new core build no longer
     * bundles the AmneziaWG engine, so this always throws — the caller reports
     * it through the normal start-failure UI.
     */
    @Throws(Exception::class)
    fun start(service: Service, vpnInterface: ParcelFileDescriptor, config: ProfileItem) {
        stop()

        val conf = config.rawConf?.trim().takeUnless { it.isNullOrEmpty() }
            ?: error("AmneziaWG configuration text is missing")

        error("AmneziaWG engine is not available in this build")
    }

    /** Stops the engine. Safe to call when nothing is running. */
    fun stop() {
        handle = 0L
    }

    /**
     * UNIX timestamp of the last completed handshake (0 = none). Always 0 —
     * the engine is not available in this build.
     */
    fun lastHandshakeSec(): Long {
        return 0L
    }
}
