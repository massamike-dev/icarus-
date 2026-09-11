package com.icarusalmighty.app

import java.net.URI
import java.util.Locale

/**
 * Keeps the privileged Android JavaScript bridge scoped to the configured
 * ICARUS HTTPS origin. External pages belong in the user's browser, never in
 * the privileged WebView.
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

    private fun parse(value: String): URI? = try {
        URI(value.trim())
    } catch (_: Exception) {
        null
    }

    private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port
}
