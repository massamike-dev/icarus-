package com.icarusalmighty.xreal.hud

/**
 * Render-only snapshot shared by the optical HUD and the phone adapter.
 * No app, sensor, camera or hardware dependencies belong in this model.
 * Null means unavailable; the renderer never synthesizes replacement readings.
 */
data class CinematicDrivingScene(
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
    val diagnosticsExpanded: Boolean = false,
    val navigationExpanded: Boolean = false,
    val listening: Boolean = false,
    val vehicleMoving: Boolean? = null,
    val obdConnected: Boolean = false,
    val sourceLabel: String = "LIVE DATA UNAVAILABLE",
    val alertMessage: String? = null,
)

enum class DrivingHudSurface { XREAL, PHONE }

enum class DrivingHudAction {
    TOGGLE_DIAGNOSTICS,
    TOGGLE_NAVIGATION,
    SHOW_ENGINE,
    VOICE,
    EXIT,
}
