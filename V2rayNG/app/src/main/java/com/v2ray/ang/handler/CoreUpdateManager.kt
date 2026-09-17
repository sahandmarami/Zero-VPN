package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zero VPN — keeps the VPN core in sync with the official Xray-core releases.
 *
 * The Xray core is embedded into the native library shipped with the APK, so a
 * core upgrade is delivered through an app update (built automatically by CI
 * against the latest official core). On every launch the app compares the
 * embedded core version with the latest official Xray-core release and surfaces
 * an update banner when a newer core exists.
 */
object CoreUpdateManager {

    data class CoreUpdateResult(
        val hasUpdate: Boolean,
        val currentVersion: String?,
        val latestVersion: String?
    )

    /** Official Xray-core releases, checked directly. */
    private const val XRAY_CORE_API_URL = "https://api.github.com/repos/XTLS/Xray-core/releases/latest"

    /**
     * Extracts the Xray-core version from the combined library version string,
     * e.g. "Lib v26.9.9, Xray-core v25.9.11" -> "25.9.11".
     */
    fun parseCoreVersion(libVersionText: String?): String? {
        if (libVersionText.isNullOrBlank()) return null
        val part = libVersionText.split(",").firstOrNull { it.contains("Xray-core") } ?: return null
        return part.substringAfter("Xray-core", "").removePrefix("v").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Compares two dotted version strings. Returns >0 if v1 > v2, <0 if v1 < v2, 0 if equal.
     */
    fun compareVersions(v1: String, v2: String): Int {
        val s1 = v1.removePrefix("v").trim()
        val s2 = v2.removePrefix("v").trim()
        val p1 = s1.split(".")
        val p2 = s2.split(".")
        for (i in 0 until maxOf(p1.size, p2.size)) {
            val n1 = p1.getOrNull(i)?.toIntOrNull() ?: 0
            val n2 = p2.getOrNull(i)?.toIntOrNull() ?: 0
            if (n1 != n2) return n1 - n2
        }
        return 0
    }

    /**
     * Checks the official Xray-core repository for a newer release than the
     * embedded core. Never throws — failures return a "no update" result.
     */
    suspend fun checkCoreUpdate(): CoreUpdateResult = withContext(Dispatchers.IO) {
        try {
            val libVersionText = CoreNativeManager.getLibVersion()
            val currentVersion = parseCoreVersion(libVersionText)
            if (currentVersion.isNullOrBlank()) {
                return@withContext CoreUpdateResult(false, null, null)
            }

            val response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = XRAY_CORE_API_URL,
                    timeout = 5000
                )
            )
            if (response.isNullOrEmpty()) {
                return@withContext CoreUpdateResult(false, currentVersion, null)
            }

            val latestRelease = JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
                ?: return@withContext CoreUpdateResult(false, currentVersion, null)
            val latestVersion = latestRelease.tagName.removePrefix("v").trim()
            if (latestVersion.isEmpty()) {
                return@withContext CoreUpdateResult(false, currentVersion, null)
            }

            LogUtil.i(
                AppConfig.TAG,
                "Core check: embedded=$currentVersion official=$latestVersion"
            )

            CoreUpdateResult(
                hasUpdate = compareVersions(latestVersion, currentVersion) > 0,
                currentVersion = currentVersion,
                latestVersion = latestVersion
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Core update check failed", e)
            CoreUpdateResult(false, null, null)
        }
    }
}
