package com.icarusalmighty.app

import android.app.Activity
import com.icarusalmighty.xreal.XrealHudState
import com.icarusalmighty.xreal.XrealLaunchResult
import com.icarusalmighty.xreal.XrealMode
import com.icarusalmighty.xreal.XrealModeController
import org.json.JSONObject
import kotlin.math.roundToInt

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
            .put("architecture", "native_hud_with_unity_upgrade_path")
            .put("hudModes", org.json.JSONArray(listOf("assistant", "vehicle")))
            .put("liveHudUpdates", true)
            .put("fallback", if (runtimeAvailable) "xreal" else "phone")
    }

    fun launch(mode: String, args: JSONObject = JSONObject()): JSONObject {
        val requestedMode = when (mode.trim().lowercase()) {
            "vehicle", "driver", "driving", "navigation" -> XrealMode.VEHICLE
            else -> XrealMode.ASSISTANT
        }
        val activeController = enabledController()
            ?: return JSONObject()
                .put("provider", "xreal")
                .put("enabled", false)
                .put("mode", requestedMode.name.lowercase())
                .put("launched", false)
                .put("runtimeAvailable", false)
                .put("fallback", "phone")
                .put("error", "integration_disabled")

        val result = activeController.launch(requestedMode, stateFrom(args))
        val runtimeAvailable = result != XrealLaunchResult.RuntimeUnavailable
        return JSONObject()
            .put("provider", "xreal")
            .put("enabled", true)
            .put("mode", requestedMode.name.lowercase())
            .put("launched", result == XrealLaunchResult.Started)
            .put("runtimeAvailable", runtimeAvailable)
            .put("liveHudUpdates", true)
            .put("fallback", if (runtimeAvailable) "xreal" else "phone")
    }

    fun update(args: JSONObject): JSONObject {
        val activeController = enabledController()
            ?: return JSONObject()
                .put("provider", "xreal")
                .put("updated", false)
                .put("error", "integration_disabled")
        activeController.update(stateFrom(args))
        return JSONObject()
            .put("provider", "xreal")
            .put("updated", true)
    }

    private fun stateFrom(args: JSONObject): XrealHudState = XrealHudState(
        assistantStatus = firstString(args, "assistantStatus", "status").ifBlank { "ICARUS ONLINE" },
        primaryText = firstString(args, "primaryText", "text", "message").ifBlank { null },
        navigationInstruction = firstString(args, "navigationInstruction", "instruction", "maneuver").ifBlank { null },
        navigationDistance = firstString(args, "navigationDistance", "distance").ifBlank { null },
        speedMph = numberOrNull(args, "speedMph", "speed")?.roundToInt(),
        engineTempF = numberOrNull(args, "engineTempF", "coolantTempF", "temperature")?.roundToInt(),
    )

    private fun firstString(args: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            if (args.has(key) && !args.isNull(key)) return args.optString(key, "")
        }
        return ""
    }

    private fun numberOrNull(args: JSONObject, vararg keys: String): Double? {
        keys.forEach { key ->
            if (args.has(key) && !args.isNull(key)) {
                return args.optDouble(key).takeUnless { it.isNaN() }
            }
        }
        return null
    }
}
