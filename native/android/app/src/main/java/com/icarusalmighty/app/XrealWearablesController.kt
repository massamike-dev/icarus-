package com.icarusalmighty.app

import android.app.Activity
import com.icarusalmighty.xreal.XrealLaunchResult
import com.icarusalmighty.xreal.XrealMode
import com.icarusalmighty.xreal.XrealModeController
import org.json.JSONObject

/** XREAL is an optional enhancement. When disabled the runtime controller is never constructed. */
class XrealWearablesController(
    private val activity: Activity,
    private val enabledProvider: () -> Boolean,
) {
    private var controller: XrealModeController? = null

    private fun enabledController(): XrealModeController? {
        if (!enabledProvider()) return null
        return controller ?: XrealModeController(activity).also { controller = it }
    }

    fun status(): JSONObject {
        val enabled = enabledProvider()
        val runtimeAvailable = enabledController()?.isRuntimeAvailable() == true
        return JSONObject()
            .put("provider", "xreal")
            .put("enabled", enabled)
            .put("sdkVersion", "3.1.0")
            .put("runtimeAvailable", runtimeAvailable)
            .put("architecture", "embedded_unity_module")
            .put("fallback", if (runtimeAvailable) "xreal" else "phone")
    }

    fun launch(mode: String): JSONObject {
        val requestedMode = runCatching { XrealMode.valueOf(mode.trim().uppercase()) }
            .getOrDefault(XrealMode.ASSISTANT)
        val activeController = enabledController()
            ?: return JSONObject()
                .put("provider", "xreal")
                .put("enabled", false)
                .put("mode", requestedMode.name.lowercase())
                .put("launched", false)
                .put("runtimeAvailable", false)
                .put("fallback", "phone")
                .put("error", "integration_disabled")

        val result = activeController.launch(requestedMode)
        val runtimeAvailable = result != XrealLaunchResult.RuntimeUnavailable
        return JSONObject()
            .put("provider", "xreal")
            .put("enabled", true)
            .put("mode", requestedMode.name.lowercase())
            .put("launched", result == XrealLaunchResult.Started)
            .put("runtimeAvailable", runtimeAvailable)
            .put("fallback", if (runtimeAvailable) "xreal" else "phone")
    }
}
