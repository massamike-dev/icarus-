package com.icarusalmighty.app

import com.icarusalmighty.app.driving.DrivingHudState
import com.icarusalmighty.xreal.XrealHudState
import com.icarusalmighty.xreal.XrealVehicleFreshness

/**
 * Live vehicle values replace the entire previous snapshot, including nulls.
 * Launch/update metadata is allowed only for the assistant and phone battery;
 * it must never replace unavailable vehicle or navigation measurements.
 */
internal fun stateFromDriving(
    live: DrivingHudState,
    metadata: XrealHudState,
    observedAtElapsedRealtimeMs: Long? = null,
) = XrealHudState(
    assistantStatus = metadata.assistantStatus,
    primaryText = metadata.primaryText,
    batteryPercent = metadata.batteryPercent,
    navigationInstruction = live.navigationInstruction,
    navigationDistance = live.navigationDistance,
    navigationSource = live.navigationSource,
    navigationAccessGranted = live.navigationAccessGranted,
    nextTurnDistanceFt = live.nextTurnDistanceFt,
    nextRoad = live.nextRoad,
    // The live notification source does not currently provide an ETA.
    eta = null,
    heading = live.heading,
    speedMph = live.speedMph,
    rpm = live.rpm,
    fuelPercent = live.fuelPercent,
    engineTempF = live.engineTempF,
    engineLoadPercent = live.engineLoadPercent,
    batteryVolts = live.batteryVolts,
    roadStatus = live.roadStatus,
    roadDetail = live.roadDetail,
    diagnosticsExpanded = live.diagnosticsExpanded,
    navigationExpanded = live.navigationExpanded,
    listening = live.listening,
    vehicleMoving = live.vehicleMoving,
    obdConnected = live.obdConnected,
    sourceLabel = live.sourceLabel,
    alertText = live.alertMessage,
    vehicleSnapshotAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
    vehicleSnapshotValidUntilElapsedRealtimeMs = XrealVehicleFreshness.deadlineFor(observedAtElapsedRealtimeMs),
    speedValidUntilElapsedRealtimeMs = live.speedValidUntilElapsedRealtimeMs,
)
