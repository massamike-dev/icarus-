package com.icarusalmighty.xreal

import android.content.Intent

/** Complete snapshots: omitted nullable extras clear old readings, never retain them. */
internal fun Intent.putHudState(state: XrealHudState): Intent = apply {
    putExtra(XrealModeController.EXTRA_ASSISTANT_STATUS, state.assistantStatus)
    removeExtra(XrealModeController.EXTRA_PRIMARY_TEXT)
    state.primaryText?.let { putExtra(XrealModeController.EXTRA_PRIMARY_TEXT, it) }
    removeExtra(XrealModeController.EXTRA_NAVIGATION_INSTRUCTION)
    state.navigationInstruction?.let { putExtra(XrealModeController.EXTRA_NAVIGATION_INSTRUCTION, it) }
    removeExtra(XrealModeController.EXTRA_NAVIGATION_DISTANCE)
    state.navigationDistance?.let { putExtra(XrealModeController.EXTRA_NAVIGATION_DISTANCE, it) }
    removeExtra(XrealModeController.EXTRA_ETA)
    state.eta?.let { putExtra(XrealModeController.EXTRA_ETA, it) }
    removeExtra(XrealModeController.EXTRA_HEADING)
    state.heading?.let { putExtra(XrealModeController.EXTRA_HEADING, it) }
    removeExtra(XrealModeController.EXTRA_SPEED_MPH)
    state.speedMph?.let { putExtra(XrealModeController.EXTRA_SPEED_MPH, it) }
    removeExtra(XrealModeController.EXTRA_RPM)
    state.rpm?.let { putExtra(XrealModeController.EXTRA_RPM, it) }
    removeExtra(XrealModeController.EXTRA_ENGINE_TEMP_F)
    state.engineTempF?.let { putExtra(XrealModeController.EXTRA_ENGINE_TEMP_F, it) }
    removeExtra(XrealModeController.EXTRA_BATTERY_PERCENT)
    state.batteryPercent?.let { putExtra(XrealModeController.EXTRA_BATTERY_PERCENT, it) }
    removeExtra(XrealModeController.EXTRA_ALERT_TEXT)
    state.alertText?.let { putExtra(XrealModeController.EXTRA_ALERT_TEXT, it) }
    removeExtra(XrealModeController.EXTRA_FUEL_PERCENT)
    state.fuelPercent?.let { putExtra(XrealModeController.EXTRA_FUEL_PERCENT, it) }
    removeExtra(XrealModeController.EXTRA_ENGINE_LOAD_PERCENT)
    state.engineLoadPercent?.let { putExtra(XrealModeController.EXTRA_ENGINE_LOAD_PERCENT, it) }
    removeExtra(XrealModeController.EXTRA_BATTERY_VOLTS)
    state.batteryVolts?.let { putExtra(XrealModeController.EXTRA_BATTERY_VOLTS, it) }
    removeExtra(XrealModeController.EXTRA_NEXT_TURN_DISTANCE_FT)
    state.nextTurnDistanceFt?.let { putExtra(XrealModeController.EXTRA_NEXT_TURN_DISTANCE_FT, it) }
    removeExtra(XrealModeController.EXTRA_NEXT_ROAD)
    state.nextRoad?.let { putExtra(XrealModeController.EXTRA_NEXT_ROAD, it) }
    removeExtra(XrealModeController.EXTRA_NAVIGATION_SOURCE)
    state.navigationSource?.let { putExtra(XrealModeController.EXTRA_NAVIGATION_SOURCE, it) }
    putExtra(XrealModeController.EXTRA_NAVIGATION_ACCESS_GRANTED, state.navigationAccessGranted)
    putExtra(XrealModeController.EXTRA_ROAD_STATUS, state.roadStatus)
    putExtra(XrealModeController.EXTRA_ROAD_DETAIL, state.roadDetail)
    putExtra(XrealModeController.EXTRA_DIAGNOSTICS_EXPANDED, state.diagnosticsExpanded)
    putExtra(XrealModeController.EXTRA_NAVIGATION_EXPANDED, state.navigationExpanded)
    putExtra(XrealModeController.EXTRA_LISTENING, state.listening)
    removeExtra(XrealModeController.EXTRA_VEHICLE_MOVING)
    state.vehicleMoving?.let { putExtra(XrealModeController.EXTRA_VEHICLE_MOVING, it) }
    putExtra(XrealModeController.EXTRA_OBD_CONNECTED, state.obdConnected)
    putExtra(XrealModeController.EXTRA_SOURCE_LABEL, state.sourceLabel)
    removeExtra(XrealModeController.EXTRA_VEHICLE_SNAPSHOT_AT_ELAPSED_REALTIME_MS)
    state.vehicleSnapshotAtElapsedRealtimeMs?.let { putExtra(XrealModeController.EXTRA_VEHICLE_SNAPSHOT_AT_ELAPSED_REALTIME_MS, it) }
    removeExtra(XrealModeController.EXTRA_VEHICLE_SNAPSHOT_VALID_UNTIL_ELAPSED_REALTIME_MS)
    state.vehicleSnapshotValidUntilElapsedRealtimeMs?.let { putExtra(XrealModeController.EXTRA_VEHICLE_SNAPSHOT_VALID_UNTIL_ELAPSED_REALTIME_MS, it) }
    removeExtra(XrealModeController.EXTRA_SPEED_VALID_UNTIL_ELAPSED_REALTIME_MS)
    state.speedValidUntilElapsedRealtimeMs?.let { putExtra(XrealModeController.EXTRA_SPEED_VALID_UNTIL_ELAPSED_REALTIME_MS, it) }
}

internal fun Intent.toHudState() = XrealHudState(
    assistantStatus = getStringExtra(XrealModeController.EXTRA_ASSISTANT_STATUS) ?: "ICARUS ONLINE",
    primaryText = getStringExtra(XrealModeController.EXTRA_PRIMARY_TEXT),
    navigationInstruction = getStringExtra(XrealModeController.EXTRA_NAVIGATION_INSTRUCTION),
    navigationDistance = getStringExtra(XrealModeController.EXTRA_NAVIGATION_DISTANCE),
    eta = getStringExtra(XrealModeController.EXTRA_ETA),
    heading = getStringExtra(XrealModeController.EXTRA_HEADING),
    speedMph = intExtraOrNull(XrealModeController.EXTRA_SPEED_MPH),
    rpm = intExtraOrNull(XrealModeController.EXTRA_RPM),
    engineTempF = intExtraOrNull(XrealModeController.EXTRA_ENGINE_TEMP_F),
    batteryPercent = intExtraOrNull(XrealModeController.EXTRA_BATTERY_PERCENT),
    alertText = getStringExtra(XrealModeController.EXTRA_ALERT_TEXT),
    fuelPercent = intExtraOrNull(XrealModeController.EXTRA_FUEL_PERCENT),
    engineLoadPercent = intExtraOrNull(XrealModeController.EXTRA_ENGINE_LOAD_PERCENT),
    batteryVolts = doubleExtraOrNull(XrealModeController.EXTRA_BATTERY_VOLTS),
    nextTurnDistanceFt = intExtraOrNull(XrealModeController.EXTRA_NEXT_TURN_DISTANCE_FT),
    nextRoad = getStringExtra(XrealModeController.EXTRA_NEXT_ROAD),
    navigationSource = getStringExtra(XrealModeController.EXTRA_NAVIGATION_SOURCE),
    navigationAccessGranted = getBooleanExtra(XrealModeController.EXTRA_NAVIGATION_ACCESS_GRANTED, false),
    roadStatus = getStringExtra(XrealModeController.EXTRA_ROAD_STATUS) ?: "ROAD DATA OFFLINE",
    roadDetail = getStringExtra(XrealModeController.EXTRA_ROAD_DETAIL) ?: "CONNECT A LIVE NAVIGATION SOURCE",
    diagnosticsExpanded = getBooleanExtra(XrealModeController.EXTRA_DIAGNOSTICS_EXPANDED, false),
    navigationExpanded = getBooleanExtra(XrealModeController.EXTRA_NAVIGATION_EXPANDED, false),
    listening = getBooleanExtra(XrealModeController.EXTRA_LISTENING, false),
    vehicleMoving = booleanExtraOrNull(XrealModeController.EXTRA_VEHICLE_MOVING),
    obdConnected = getBooleanExtra(XrealModeController.EXTRA_OBD_CONNECTED, false),
    sourceLabel = getStringExtra(XrealModeController.EXTRA_SOURCE_LABEL) ?: "LIVE DATA UNAVAILABLE",
    vehicleSnapshotAtElapsedRealtimeMs = longExtraOrNull(XrealModeController.EXTRA_VEHICLE_SNAPSHOT_AT_ELAPSED_REALTIME_MS),
    vehicleSnapshotValidUntilElapsedRealtimeMs = longExtraOrNull(XrealModeController.EXTRA_VEHICLE_SNAPSHOT_VALID_UNTIL_ELAPSED_REALTIME_MS),
    speedValidUntilElapsedRealtimeMs = longExtraOrNull(XrealModeController.EXTRA_SPEED_VALID_UNTIL_ELAPSED_REALTIME_MS),
)

private fun Intent.intExtraOrNull(key: String): Int? =
    if (hasExtra(key)) getIntExtra(key, 0) else null

private fun Intent.doubleExtraOrNull(key: String): Double? =
    if (hasExtra(key)) getDoubleExtra(key, Double.NaN).takeIf { it.isFinite() } else null

private fun Intent.booleanExtraOrNull(key: String): Boolean? =
    if (hasExtra(key)) getBooleanExtra(key, false) else null

private fun Intent.longExtraOrNull(key: String): Long? =
    if (hasExtra(key)) getLongExtra(key, 0L) else null
