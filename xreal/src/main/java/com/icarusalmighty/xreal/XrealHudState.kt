package com.icarusalmighty.xreal

data class XrealHudState(
    val assistantStatus: String = "Idle",
    val primaryText: String? = null,
    val navigationInstruction: String? = null,
    val navigationDistance: String? = null,
    val speedMph: Int? = null,
    val engineTempF: Int? = null,
)

enum class XrealMode {
    ASSISTANT,
    VEHICLE,
}
