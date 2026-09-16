package com.icarusalmighty.app.driving

data class DrivingHudState(
    val speedMph: Int? = null,
    val rpm: Int? = null,
    val fuelPercent: Int? = null,
    val engineTempF: Int? = null,
    val batteryVolts: Double? = null,
    val engineLoadPercent: Int? = null,
    val nextTurnDistanceFt: Int? = null,
    val nextRoad: String? = null,
    val roadStatus: String = "ROAD DATA OFFLINE",
    val roadDetail: String = "CONNECT A LIVE NAVIGATION SOURCE",
    val diagnosticsExpanded: Boolean = true,
    val navigationExpanded: Boolean = true,
    val listening: Boolean = false,
    val vehicleMoving: Boolean? = null,
    val obdConnected: Boolean = false,
    val sourceLabel: String = "NO LIVE VEHICLE DATA",
    val alertMessage: String? = null,
    val lastVoiceCommand: String? = null
) {
    val parkedControlsAllowed: Boolean get() = vehicleMoving == false
}

enum class HudAction {
    TOGGLE_DIAGNOSTICS,
    TOGGLE_NAVIGATION,
    SHOW_ENGINE,
    VOICE,
    EXIT
}
