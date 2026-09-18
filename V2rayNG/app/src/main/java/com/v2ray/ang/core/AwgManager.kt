package com.v2ray.ang.core

import android.app.Service
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.WgTunnelSettings
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import libv2ray.Libv2ray

/**
 * Zero VPN: manages the embedded AmneziaWG engine (libawg — amneziawg-go
 * compiled with gomobile).
 *
 * Xray-core's wireguard outbound does not implement the Amnezia obfuscation
 * wire format (Jc/Jmin/Jmax junk packets, S1-S4 padding, H1-H4 header
 * obfuscation), so profiles whose pasted configuration carries those
 * parameters are served by this engine instead: it runs directly on the
 * VpnService TUN descriptor. Profiles without obfuscation parameters keep
 * using the Xray core path unchanged.
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
     * Starts the engine on the VPN interface. Throws on failure so the caller
     * can report a normal start failure to the UI.
     */
    @Throws(Exception::class)
    fun start(service: Service, vpnInterface: ParcelFileDescriptor, config: ProfileItem) {
        stop()

        val conf = config.rawConf?.trim().takeUnless { it.isNullOrEmpty() }
            ?: error("AmneziaWG configuration text is missing")

        val newHandle = Libv2ray.awgTurnOn("awg0", vpnInterface.fd, conf)
        if (newHandle <= 0) {
            error("AWG engine failed to start")
        }
        handle = newHandle
        LogUtil.i(AppConfig.TAG, "StartCore-AWG: engine started, handle=$newHandle")

        protectEngineSockets()
    }

    /** Stops the engine. Safe to call when nothing is running. */
    fun stop() {
        val old = handle
        handle = 0L
        if (old != 0L) {
            try {
                Libv2ray.awgTurnOff(old)
                LogUtil.i(AppConfig.TAG, "StartCore-AWG: engine stopped")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-AWG: failed to stop engine", e)
            }
        }
    }

    /**
     * UNIX timestamp of the last completed handshake (0 = none). A small age
     * proves the AWG tunnel is alive.
     */
    fun lastHandshakeSec(): Long {
        val h = handle
        if (h == 0L) return 0L
        return try {
            Libv2ray.awgLastHandshakeSec(h)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Protects the engine's UDP sockets with VpnService.protect so the
     * encrypted transport never re-enters the VPN. The sockets are created
     * during device start; retry briefly in case they appear a moment late.
     */
    private fun protectEngineSockets() {
        val control = CoreServiceManager.socketProtector ?: return
        repeat(10) {
            var protectedAny = false
            try {
                val fd4 = Libv2ray.awgGetSocketV4(handle).toInt()
                if (fd4 > 0) protectedAny = protectedAny or control(fd4)
            } catch (_: Exception) {
                // IPv4 socket not ready yet on this attempt.
            }
            try {
                val fd6 = Libv2ray.awgGetSocketV6(handle).toInt()
                if (fd6 > 0) protectedAny = protectedAny or control(fd6)
            } catch (_: Exception) {
                // IPv6 socket not ready yet (or no IPv6 connectivity).
            }
            if (protectedAny) {
                LogUtil.i(AppConfig.TAG, "StartCore-AWG: engine sockets protected")
                return
            }
            Thread.sleep(200)
        }
        LogUtil.w(AppConfig.TAG, "StartCore-AWG: socket protection not confirmed")
    }
}
