package com.icarusalmighty.xreal

data class XrealHudState(
    val assistantStatus: String = "Idle",
    val primaryText: String? = null,
    val navigationInstruction: String? = null,
    val navigationDistance: String? = null,
    val eta: String? = null,
    val heading: String? = null,
    val speedMph: Int? = null,
    val rpm: Int? = null,
    val engineTempF: Int? = null,
    val batteryPercent: Int? = null,
    val alertText: String? = null,
)

enum class XrealMode {
    ASSISTANT,
    VEHICLE,
}
