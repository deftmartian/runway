package com.deftmartian.runway

import java.net.URI
import java.net.InetAddress
import java.util.Locale

object InstanceOriginPolicy {
    fun normalizeOrigin(candidate: String, allowPrivateCleartext: Boolean): String? {
        val trimmed = candidate.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT) ?: return null
        if (
            uri.userInfo != null || uri.query != null || uri.fragment != null ||
            (!uri.path.isNullOrEmpty() && uri.path != "/") ||
            (uri.port != -1 && uri.port !in 1..65535)
        ) return null
        if (scheme != "https" && (scheme != "http" || !allowPrivateCleartext || !isPrivateHost(host))) {
            return null
        }
        val normalizedPort = when {
            uri.port == -1 -> -1
            scheme == "https" && uri.port == 443 -> -1
            scheme == "http" && uri.port == 80 -> -1
            else -> uri.port
        }
        return runCatching { URI(scheme, null, host, normalizedPort, null, null, null).toString() }
            .getOrNull()
    }

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

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (':' in host && isPrivateIpv6(host)) return true
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
        val first = octets[0] ?: return false
        val second = octets[1] ?: return false
        return first == 10 || first == 127 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }

    private fun isPrivateIpv6(host: String): Boolean = runCatching {
        val address = InetAddress.getByName(host)
        val bytes = address.address
        bytes.size == 16 && (
            address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                ((bytes[0].toInt() and 0xfe) == 0xfc)
            )
    }.getOrDefault(false)
}
