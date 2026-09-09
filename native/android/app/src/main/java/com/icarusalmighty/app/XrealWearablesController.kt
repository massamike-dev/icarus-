package com.icarusalmighty.app

import android.app.Activity
import com.icarusalmighty.xreal.XrealLaunchResult
import com.icarusalmighty.xreal.XrealMode
import com.icarusalmighty.xreal.XrealModeController
import org.json.JSONObject

class XrealWearablesController(private val activity: Activity) {
    private val controller = XrealModeController(activity)

    fun status(): JSONObject = JSONObject()
        .put("provider", "xreal")
        .put("sdkVersion", "3.1.0")
        .put("runtimeAvailable", controller.isRuntimeAvailable())
        .put("architecture", "embedded_unity_module")

    fun launch(mode: String): JSONObject {
        val requestedMode = runCatching { XrealMode.valueOf(mode.trim().uppercase()) }
            .getOrDefault(XrealMode.ASSISTANT)
        val result = controller.launch(requestedMode)
        return JSONObject()
            .put("provider", "xreal")
            .put("mode", requestedMode.name.lowercase())
            .put("launched", result == XrealLaunchResult.Started)
            .put("runtimeAvailable", result != XrealLaunchResult.RuntimeUnavailable)
    }
}
