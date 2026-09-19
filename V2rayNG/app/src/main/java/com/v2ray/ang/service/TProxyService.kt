package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Manages the tun2socks process that handles VPN traffic
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val isRunningProvider: () -> Boolean,
    private val restartCallback: () -> Unit
) : Tun2SocksControl {
    companion object {
        private const val NATIVE_LIB_NAME = "hev-socks5-tunnel"

        @Volatile
        private var nativeLoadChecked = false

        @Volatile
        private var nativeLoadOk = false

        /**
         * Zero VPN: lazily probes for the native tunnel library instead of
         * loading it inside the companion initializer. A missing or
         * incompatible .so used to throw UnsatisfiedLinkError out of class
         * initialization (crash dialog / NoClassDefFoundError on the next
         * touch). Now the failure is remembered and reported so callers can
         * fall back to the Xray built-in TUN engine gracefully.
         */
        @JvmStatic
        fun isNativeLibraryAvailable(): Boolean {
            if (!nativeLoadChecked) {
                synchronized(this) {
                    if (!nativeLoadChecked) {
                        nativeLoadOk = try {
                            System.loadLibrary(NATIVE_LIB_NAME)
                            true
                        } catch (t: Throwable) {
                            LogUtil.e(
                                AppConfig.TAG,
                                "HevSocks5Tunnel: native library '$NATIVE_LIB_NAME' unavailable: ${t.message}"
                            )
                            false
                        }
                        nativeLoadChecked = true
                    }
                }
            }
            return nativeLoadOk
        }

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?
    }

    /**
     * Starts the tun2socks process with the appropriate parameters.
     */
    override fun startTun2Socks() {
//        LogUtil.i(AppConfig.TAG, "Starting HevSocks5Tunnel via JNI")

        // Zero VPN: never touch the JNI entry points when the native library
        // is missing — that would raise UnsatisfiedLinkError inside the VPN
        // service and tear the whole tunnel down.
        if (!isNativeLibraryAvailable()) {
            LogUtil.e(AppConfig.TAG, "HevSocks5Tunnel: start skipped, native library unavailable")
            return
        }

        val configContent = buildConfig()
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }
//        LogUtil.i(AppConfig.TAG, "Config file created: ${configFile.absolutePath}")
        LogUtil.d(AppConfig.TAG, "HevSocks5Tunnel Config content:\n$configContent")

        try {
//            LogUtil.i(AppConfig.TAG, "TProxyStartService...")
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "HevSocks5Tunnel exception: ${e.message}")
        }
    }

    private fun buildConfig(): String {
        val socksPort = SettingsManager.getSocksPort()
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val escapedSocksUsername = socksUsername?.replace("'", "''")
        val escapedSocksPassword = socksPassword?.replace("'", "''")
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)) {
                appendLine("  ipv6: '${vpnConfig.ipv6Client}'")
            }

            appendLine("socks5:")
            appendLine("  port: ${socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")
            if (escapedSocksUsername != null && escapedSocksPassword != null) {
                appendLine("  username: '${escapedSocksUsername}'")
                appendLine("  password: '${escapedSocksPassword}'")
            }

            // Read-write timeout settings
            val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) ?: AppConfig.HEVTUN_RW_TIMEOUT
            val parts = timeoutSetting.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
            val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60

            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
            appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
            appendLine("  log-level: ${MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL, AppConfig.DEFAULT_HEV_TUNNEL_LOGLEVEL)}")
        }
    }

    /**
     * Stops the tun2socks process
     */
    override fun stopTun2Socks() {
        // Zero VPN: if the library never loaded, the JNI call would throw
        // UnsatisfiedLinkError — treat stop as a no-op instead.
        if (!isNativeLibraryAvailable()) return
        try {
            LogUtil.i(AppConfig.TAG, "TProxyStopService...")
            TProxyStopService()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to stop hev-socks5-tunnel", e)
        }
    }
}
