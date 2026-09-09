package com.icarusalmighty.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Crash-safe Meta Wearables shim.
 *
 * The DAT runtime is temporarily prevented from initializing during Android app startup.
 * This keeps the core ICARUS assistant usable while Meta DAT startup compatibility is
 * isolated on the affected device. Re-enable the full controller once runtime startup
 * has been verified independently.
 */
class MetaWearablesController(
    private val activity: MainActivity,
    private val dispatch: (String) -> Unit,
) {
    fun status(): JSONObject = JSONObject()
        .put("available", false)
        .put("registrationState", "SAFE_DISABLED")
        .put("deviceCount", 0)
        .put("sessionState", "STOPPED")
        .put("cameraState", "STOPPED")
        .put("displayState", "STOPPED")
        .put("developerMode", BuildConfig.DEBUG)
        .put("reason", "meta_dat_startup_guard")
        .put("capabilities", JSONArray())

    fun execute(action: String, requestId: String?, args: JSONObject): String {
        return JSONObject()
            .put("ok", false)
            .put("requestId", requestId ?: JSONObject.NULL)
            .put("action", action)
            .put("error", "meta_temporarily_disabled")
            .put("message", "Meta Wearables is temporarily disabled in this crash-safe ICARUS build.")
            .toString()
    }

    fun close() = Unit
}
