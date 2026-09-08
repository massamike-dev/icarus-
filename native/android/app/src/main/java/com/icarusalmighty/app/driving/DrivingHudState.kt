package com.icarusalmighty.app.driving

data class DrivingHudState(
    val speedMph: Int = 43,
    val rpm: Int = 1850,
    val fuelPercent: Int = 63,
    val engineTempF: Int = 198,
    val batteryVolts: Double = 14.2,
    val engineLoadPercent: Int = 34,
    val nextTurnDistanceFt: Int = 450,
    val nextRoad: String = "Ridgeview Blvd",
    val roadStatus: String = "ROAD CLEAR",
    val roadDetail: String = "GOOD CONDITIONS AHEAD",
    val diagnosticsExpanded: Boolean = true,
    val navigationExpanded: Boolean = true,
    val listening: Boolean = false,
    val parked: Boolean = false,
    val obdConnected: Boolean = false,
    val sourceLabel: String = "SIMULATION",
    val alertMessage: String? = null,
    val lastVoiceCommand: String? = null
)

enum class HudAction {
    TOGGLE_DIAGNOSTICS,
    TOGGLE_NAVIGATION,
    SHOW_ENGINE,
    VOICE,
    EXIT
}
