package com.icarusalmighty.app

import android.content.Context

/** Native source of truth for whether optional wearable integrations may initialize. */
class IntegrationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): IntegrationFlags = IntegrationFlags(
        metaEnabled = prefs.getBoolean(KEY_META, false),
        xrealEnabled = prefs.getBoolean(KEY_XREAL, false),
    )

    fun set(provider: String, enabled: Boolean): IntegrationFlags {
        val key = when (provider.trim().lowercase()) {
            "meta" -> KEY_META
            "xreal" -> KEY_XREAL
            else -> throw IllegalArgumentException("unsupported_integration")
        }
        prefs.edit().putBoolean(key, enabled).apply()
        return snapshot()
    }

    companion object {
        private const val PREFS_NAME = "icarus_optional_integrations"
        private const val KEY_META = "meta_enabled"
        private const val KEY_XREAL = "xreal_enabled"
    }
}
