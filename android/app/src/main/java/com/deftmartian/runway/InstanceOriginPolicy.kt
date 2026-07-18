package com.deftmartian.runway

import java.net.URI
import java.util.Locale

object InstanceOriginPolicy {
    fun belongsTo(candidateUrl: String, configuredOrigin: String): Boolean {
        val candidate = runCatching { URI(candidateUrl) }.getOrNull() ?: return false
        val configured = runCatching { URI(configuredOrigin) }.getOrNull() ?: return false
        if (candidate.userInfo != null || candidate.host == null || configured.host == null) return false

        return candidate.scheme?.lowercase(Locale.ROOT) == configured.scheme?.lowercase(Locale.ROOT) &&
            candidate.host.lowercase(Locale.ROOT) == configured.host.lowercase(Locale.ROOT) &&
            effectivePort(candidate) == effectivePort(configured)
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }
}
