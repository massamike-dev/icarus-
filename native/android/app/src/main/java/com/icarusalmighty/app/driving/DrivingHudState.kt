package com.icarusalmighty.app.driving

import android.os.SystemClock
import kotlin.math.roundToInt

data class DrivingHudState(
    val speedMph: Int? = null,
    val heading: String? = null,
    val rpm: Int? = null,
    val fuelPercent: Int? = null,
    val engineTempF: Int? = null,
    val batteryVolts: Double? = null,
    val engineLoadPercent: Int? = null,
    val nextTurnDistanceFt: Int? = null,
    val nextRoad: String? = null,
    val navigationInstruction: String? = null,
    val navigationDistance: String? = null,
    val navigationSource: String? = null,
    val navigationAccessGranted: Boolean = false,
    val roadStatus: String = "ROAD DATA OFFLINE",
    val roadDetail: String = "CONNECT A LIVE NAVIGATION SOURCE",
    val diagnosticsExpanded: Boolean = true,
    val navigationExpanded: Boolean = true,
    val listening: Boolean = false,
    val vehicleMoving: Boolean? = null,
    val speedValidUntilElapsedRealtimeMs: Long? = null,
    val obdConnected: Boolean = false,
    val sourceLabel: String = "PHONE GPS WAITING",
    val alertMessage: String? = null,
    val lastVoiceCommand: String? = null
) {
    // Check at action time too: a delayed main-loop expiry callback must never unlock controls.
    val parkedControlsAllowed: Boolean get() = parkedControlsAllowedAt(SystemClock.elapsedRealtime())

    fun parkedControlsAllowedAt(nowElapsedRealtimeMs: Long): Boolean {
        val validUntil = speedValidUntilElapsedRealtimeMs ?: return false
        return vehicleMoving == false && speedMph == 0 && nowElapsedRealtimeMs >= 0L &&
            nowElapsedRealtimeMs < validUntil
    }
}

enum class HudAction {
    TOGGLE_DIAGNOSTICS,
    TOGGLE_NAVIGATION,
    SHOW_ENGINE,
    VOICE,
    EXIT
}

object DrivingTelemetryMath {
    private val headings = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun speedMph(metersPerSecond: Float): Int =
        (metersPerSecond.coerceAtLeast(0f) * 2.2369363f).roundToInt()

    fun cardinalHeading(degrees: Float): String {
        val normalized = ((degrees % 360f) + 360f) % 360f
        return headings[((normalized + 22.5f) / 45f).toInt() % headings.size]
    }
}
