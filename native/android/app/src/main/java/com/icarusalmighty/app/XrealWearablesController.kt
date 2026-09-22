package com.icarusalmighty.app

import android.app.Activity
import com.icarusalmighty.app.driving.DrivingHudState
import com.icarusalmighty.app.driving.DrivingObdDiscovery
import com.icarusalmighty.app.driving.DrivingTelemetrySession
import com.icarusalmighty.xreal.XrealHudState
import com.icarusalmighty.xreal.XrealLaunchResult
import com.icarusalmighty.xreal.XrealMode
import com.icarusalmighty.xreal.XrealModeController
import org.json.JSONObject
import kotlin.math.roundToInt

/** XREAL is opt-in. Cleanup can close the bundled overlay without enabling the integration. */
class XrealWearablesController(
    private val activity: Activity,
    private val enabledProvider: () -> Boolean,
) {
    private var controller: XrealModeController? = null
    private var telemetryLease: DrivingTelemetrySession.Lease? = null

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
            .put("target", "bundled")
            .put("installed", true)
            .put("runtimeAvailable", runtimeAvailable)
            .put("architecture", "bundled_native_overlay")
            .put("displayMode", "screen_fixed_overlay")
            .put("tracking", "none")
            .put("glassesConnectionVerified", false)
            .put("isOpen", controller?.isOpen() == true)
            .put("launchPending", controller?.isLaunchPending() == true)
            .put("hudModes", org.json.JSONArray(listOf("assistant", "vehicle")))
            .put("liveHudUpdates", true)
            .put("fallback", if (runtimeAvailable) "xreal" else "phone")
    }

    fun launch(mode: String, args: JSONObject = JSONObject()): JSONObject {
        val requestedMode = when (mode.trim().lowercase()) {
            "vehicle", "driver", "driving", "navigation" -> XrealMode.VEHICLE
            "", "assistant" -> XrealMode.ASSISTANT
            else -> return JSONObject().put("launched", false).put("error", "invalid_hud_mode")
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

        val requestedState = stateFrom(args)
        if (requestedMode == XrealMode.VEHICLE) {
            telemetryLease?.close()
            val obdAddress = DrivingObdDiscovery.findSingleKnownAddress(activity)
            telemetryLease = DrivingTelemetrySession.acquire(activity, obdAddress) { live ->
                controller?.takeIf { it.isOpen() }?.update(stateFromDriving(live, requestedState))
            }
        } else {
            telemetryLease?.close()
            telemetryLease = null
        }
        val launchState = if (requestedMode == XrealMode.VEHICLE) {
            stateFromDriving(DrivingTelemetrySession.snapshot(), requestedState)
        } else requestedState
        val result = activeController.launch(requestedMode, launchState)
        if (result != XrealLaunchResult.Started) {
            telemetryLease?.close()
            telemetryLease = null
        }
        val runtimeAvailable = activeController.isRuntimeAvailable()
        return JSONObject()
            .put("provider", "xreal")
            .put("enabled", true)
            .put("mode", requestedMode.name.lowercase())
            .put("launched", result == XrealLaunchResult.Started)
            .put("launchRequested", result == XrealLaunchResult.Started)
            .put("target", "bundled")
            .put("displayMode", "screen_fixed_overlay")
            .put("tracking", "none")
            .put("glassesConnectionVerified", false)
            .put("runtimeAvailable", runtimeAvailable)
            .put("liveHudUpdates", true)
            .put("fallback", if (result == XrealLaunchResult.Started) "xreal" else "phone")
            .apply {
                when (result) {
                    XrealLaunchResult.RuntimeUnavailable -> put("error", "xreal_runtime_unavailable")
                    is XrealLaunchResult.Failed -> put("error", result.code)
                    XrealLaunchResult.Started -> Unit
                }
            }
    }

    fun closeHud(): JSONObject {
        // Closing is allowed after disabling the integration, and never enables it.
        val closeRequested = (controller ?: XrealModeController(activity)).close()
        telemetryLease?.close()
        telemetryLease = null
        return JSONObject()
            .put("provider", "xreal")
            .put("target", "bundled")
            .put("closeRequested", closeRequested)
            .put("alreadyClosed", !closeRequested)
    }

    fun update(args: JSONObject): JSONObject {
        val activeController = enabledController()
            ?: return JSONObject()
                .put("provider", "xreal")
                .put("updated", false)
                .put("error", "integration_disabled")
        val sent = activeController.update(stateFrom(args))
        return JSONObject()
            .put("provider", "xreal")
            .put("target", "bundled")
            .put("updateRequested", sent)
            .put("updated", false) // Broadcast delivery/rendering has no acknowledgement.
            .apply { if (!sent) put("error", "xreal_hud_not_open") }
    }

    private fun stateFromDriving(live: DrivingHudState, fallback: XrealHudState): XrealHudState = XrealHudState(
        assistantStatus = fallback.assistantStatus,
        primaryText = fallback.primaryText,
        navigationInstruction = live.navigationInstruction ?: fallback.navigationInstruction,
        navigationDistance = live.navigationDistance ?: fallback.navigationDistance,
        eta = fallback.eta,
        heading = live.heading ?: fallback.heading,
        speedMph = live.speedMph ?: fallback.speedMph,
        rpm = live.rpm ?: fallback.rpm,
        engineTempF = live.engineTempF ?: fallback.engineTempF,
        batteryPercent = fallback.batteryPercent,
        alertText = live.alertMessage ?: fallback.alertText,
    )

    private fun stateFrom(args: JSONObject): XrealHudState = XrealHudState(
        assistantStatus = firstString(args, "assistantStatus", "status").ifBlank { "ICARUS ONLINE" },
        primaryText = firstString(args, "primaryText", "text", "message").ifBlank { null },
        navigationInstruction = firstString(args, "navigationInstruction", "instruction", "maneuver").ifBlank { null },
        navigationDistance = firstString(args, "navigationDistance", "distance").ifBlank { null },
        eta = firstString(args, "eta", "arrivalTime").ifBlank { null },
        heading = firstString(args, "heading", "cardinalHeading").ifBlank { null },
        speedMph = numberOrNull(args, "speedMph", "speed")?.roundToInt(),
        rpm = numberOrNull(args, "rpm", "engineRpm")?.roundToInt(),
        engineTempF = numberOrNull(args, "engineTempF", "coolantTempF", "temperature")?.roundToInt(),
        batteryPercent = numberOrNull(args, "batteryPercent", "phoneBattery")?.roundToInt(),
        alertText = firstString(args, "alertText", "alert", "warning").ifBlank { null },
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
