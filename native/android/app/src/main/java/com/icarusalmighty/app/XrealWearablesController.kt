package com.icarusalmighty.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.icarusalmighty.app.driving.DrivingObdDiscovery
import com.icarusalmighty.app.driving.DrivingTelemetrySession
import com.icarusalmighty.xreal.XrealHudState
import com.icarusalmighty.xreal.XrealLaunchResult
import com.icarusalmighty.xreal.XrealMode
import com.icarusalmighty.xreal.XrealModeController
import com.icarusalmighty.xreal.XrealOutputDiagnostics
import org.json.JSONObject
import kotlin.math.roundToInt

/** XREAL is opt-in. Cleanup can close the bundled overlay without enabling the integration. */
class XrealWearablesController(
    private val activity: Activity,
    private val enabledProvider: () -> Boolean,
) {
    private var controller: XrealModeController? = null
    private var telemetryLease: DrivingTelemetrySession.Lease? = null
    private var hudMetadata = XrealHudState()
    private var lastVehicleObservedAtElapsedRealtimeMs: Long? = null
    private val onHudClosed: () -> Unit = { releaseTelemetry() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val telemetryHeartbeat = object : Runnable {
        override fun run() {
            val lease = telemetryLease ?: return
            if (controller?.isOpen() != true && controller?.isLaunchPending() != true) {
                releaseTelemetry()
                return
            }
            // Revalidate source evidence before repeating unchanged guidance.
            // This renews transport liveness, never sensor measurement deadlines.
            lease.refreshSnapshot()
            if (telemetryLease != null) mainHandler.postDelayed(this, 1_000L)
        }
    }

    private fun releaseTelemetry() {
        mainHandler.removeCallbacks(telemetryHeartbeat)
        telemetryLease?.close()
        telemetryLease = null
        lastVehicleObservedAtElapsedRealtimeMs = null
    }

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
            .put("outputVerification", "unverified")
            .put("outputPath", "os_managed_2d_window")
            .put("outputDiagnostics", (controller?.outputDiagnostics() ?: XrealOutputDiagnostics.capture(activity, null)).toJson())
            .put("isOpen", controller?.isOpen() == true)
            .put("launchPending", controller?.isLaunchPending() == true)
            .put("hudModes", org.json.JSONArray(listOf("assistant", "vehicle")))
            .put("liveHudUpdates", true)
            .put("fallback", if (runtimeAvailable) "os_managed_output_unverified" else "phone")
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

        activeController.setOnClosedListener(onHudClosed)
        val requestedState = stateFrom(args)
        hudMetadata = requestedState
        if (requestedMode == XrealMode.VEHICLE) {
            val hasLocation = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasLocation) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    7042,
                )
            }
            releaseTelemetry()
            val obdAddress = DrivingObdDiscovery.findSingleKnownAddress(activity)
            telemetryLease = DrivingTelemetrySession.acquire(activity, obdAddress) { live ->
                // Only a live producer callback may renew receiver liveness.
                val observedAt = SystemClock.elapsedRealtime()
                lastVehicleObservedAtElapsedRealtimeMs = observedAt
                controller?.takeIf { it.isOpen() }?.update(stateFromDriving(live, hudMetadata, observedAt))
            }
        } else {
            releaseTelemetry()
        }
        val launchState = if (requestedMode == XrealMode.VEHICLE) {
            stateFromDriving(DrivingTelemetrySession.snapshot(), requestedState, lastVehicleObservedAtElapsedRealtimeMs)
        } else requestedState
        val result = activeController.launch(requestedMode, launchState)
        if (result != XrealLaunchResult.Started) {
            releaseTelemetry()
        } else if (telemetryLease != null) {
            mainHandler.postDelayed(telemetryHeartbeat, 1_000L)
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
            .put("outputVerification", "unverified")
            .put("outputPath", "os_managed_2d_window")
            .put("outputDiagnostics", (controller?.outputDiagnostics() ?: XrealOutputDiagnostics.capture(activity, null)).toJson())
            .put("runtimeAvailable", runtimeAvailable)
            .put("liveHudUpdates", true)
            .put("fallback", if (result == XrealLaunchResult.Started) "os_managed_output_unverified" else "phone")
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
        releaseTelemetry()
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
        hudMetadata = stateFrom(args)
        // In a vehicle session, the live snapshot is authoritative even when a
        // measurement expires. An update must not resurrect launch-time values.
        val snapshot = if (activeController.isVehicleMode() || telemetryLease != null) {
            stateFromDriving(DrivingTelemetrySession.snapshot(), hudMetadata, lastVehicleObservedAtElapsedRealtimeMs)
        } else hudMetadata
        val sent = activeController.update(snapshot)
        return JSONObject()
            .put("provider", "xreal")
            .put("target", "bundled")
            .put("updateRequested", sent)
            .put("updated", false) // Broadcast delivery/rendering has no acknowledgement.
            .apply { if (!sent) put("error", "xreal_hud_not_open") }
    }

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
        fuelPercent = numberOrNull(args, "fuelPercent", "fuel")?.roundToInt(),
        engineLoadPercent = numberOrNull(args, "engineLoadPercent", "engineLoad")?.roundToInt(),
        batteryVolts = numberOrNull(args, "batteryVolts", "voltage"),
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
                return args.optDouble(key).takeIf { it.isFinite() }
            }
        }
        return null
    }
}
