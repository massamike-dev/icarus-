package com.icarusalmighty.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Crash-safe Meta Wearables boundary.
 *
 * The user-level integration toggle is checked before any Meta runtime work is allowed.
 * 1.4.4 keeps the DAT runtime itself guarded until startup compatibility is validated on
 * target devices; importantly, a disabled Meta integration never initializes the SDK.
 */
class MetaWearablesController(
    private val activity: MainActivity,
    private val dispatch: (String) -> Unit,
    private val enabledProvider: () -> Boolean,
) {
    fun status(): JSONObject {
        val enabled = enabledProvider()
        return JSONObject()
            .put("provider", "meta")
            .put("enabled", enabled)
            .put("available", false)
            .put("registrationState", if (enabled) "SAFE_DISABLED" else "DISABLED_BY_USER")
            .put("deviceCount", 0)
            .put("sessionState", "STOPPED")
            .put("cameraState", "STOPPED")
            .put("displayState", "STOPPED")
            .put("developerMode", BuildConfig.DEBUG)
            .put("reason", if (enabled) "meta_dat_startup_guard" else "integration_disabled")
            .put("capabilities", JSONArray())
    }

    fun execute(action: String, requestId: String?, args: JSONObject): String {
        if (!enabledProvider()) {
            return JSONObject()
                .put("ok", false)
                .put("requestId", requestId ?: JSONObject.NULL)
                .put("action", action)
                .put("error", "integration_disabled")
                .put("message", "Meta Integration is turned off in ICARUS settings.")
                .toString()
        }
        return JSONObject()
            .put("ok", false)
            .put("requestId", requestId ?: JSONObject.NULL)
            .put("action", action)
            .put("error", "meta_temporarily_guarded")
            .put("message", "Meta Integration is enabled, but the DAT runtime remains crash-guarded in ICARUS 1.4.4 until device validation is complete.")
            .toString()
    }

    fun close() = Unit
}
