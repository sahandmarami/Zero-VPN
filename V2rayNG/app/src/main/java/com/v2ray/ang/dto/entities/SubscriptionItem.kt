package com.v2ray.ang.dto.entities

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    var updateInterval: Long = 1440, // in minutes, default to 24 hours
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    var requestHeaders: String? = null,
    // Zero VPN: traffic quota reported by the provider through the standard
    // "subscription-userinfo" response header (all values in bytes, expire in
    // epoch seconds). total == 0 means the provider did not report a quota.
    var upload: Long = 0,
    var download: Long = 0,
    var total: Long = 0,
    var expire: Long = 0,
) {
    /** Total consumed traffic in bytes (upload + download). */
    val usedBytes: Long
        get() = upload + download
}

