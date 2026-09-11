package com.icarusalmighty.app

import java.net.URI
import java.util.Locale

/**
 * Keeps privileged Android web messaging scoped to the configured ICARUS
 * HTTPS origin.
 */
object TrustedWebPolicy {
    fun isTrustedUrl(candidate: String?, configuredBaseUrl: String?): Boolean {
        if (candidate.isNullOrBlank() || configuredBaseUrl.isNullOrBlank()) return false
        val candidateUri = parse(candidate) ?: return false
        val baseUri = parse(configuredBaseUrl) ?: return false
        if (!candidateUri.scheme.equals("https", ignoreCase = true)) return false
        if (!baseUri.scheme.equals("https", ignoreCase = true)) return false
        if (!candidateUri.userInfo.isNullOrBlank() || !baseUri.userInfo.isNullOrBlank()) return false

        return candidateUri.host?.lowercase(Locale.US) == baseUri.host?.lowercase(Locale.US) &&
            effectivePort(candidateUri) == effectivePort(baseUri)
    }

    fun originRule(configuredBaseUrl: String?): String? {
        if (configuredBaseUrl.isNullOrBlank()) return null
        val uri = parse(configuredBaseUrl) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !uri.userInfo.isNullOrBlank()) return null
        val host = uri.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        val port = effectivePort(uri)
        return if (port == 443) "https://$host" else "https://$host:$port"
    }

    private fun parse(value: String): URI? = try {
        URI(value.trim())
    } catch (_: Exception) {
        null
    }

    private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port
}
