package com.nuvio.tv.livetv.multiview

/** DRM aliases and credential boundaries; transport follows ReSeq's single Live TV player. */
internal object MultiviewSourcePolicy {
    fun supportsDrmScheme(scheme: String): Boolean =
        scheme.trim().lowercase() in setOf("widevine", "playready", "clearkey")

    /** A license host receives stream context headers and only explicitly supplied credentials. */
    fun licenseHeaders(
        streamHeaders: Map<String, String>,
        explicitLicenseHeaders: Map<String, String>,
        caseInsensitiveOverrides: Boolean = false,
    ): Map<String, String> = LinkedHashMap<String, String>().apply {
        streamHeaders.forEach { (key, value) ->
            if (key.equals("User-Agent", true) || key.equals("Referer", true) || key.equals("Origin", true)) {
                put(key, value)
            }
        }
        explicitLicenseHeaders.forEach { (key, value) ->
            if (caseInsensitiveOverrides) keys.filter { it.equals(key, true) }.forEach { remove(it) }
            put(key, value)
        }
    }
}
