package com.icarusalmighty.app.driving

import android.content.Context
import com.icarusalmighty.xreal.hud.CinematicDrivingHudView
import com.icarusalmighty.xreal.hud.CinematicDrivingScene
import com.icarusalmighty.xreal.hud.DrivingHudAction
import com.icarusalmighty.xreal.hud.DrivingHudSurface

/** Phone controls adapt to the same cinematic renderer used by the XREAL vehicle activity. */
class DrivingHudView(context: Context, onAction: (HudAction) -> Unit) : CinematicDrivingHudView(
    context,
    surface = DrivingHudSurface.PHONE,
    onAction = { action ->
        onAction(when (action) {
            DrivingHudAction.TOGGLE_DIAGNOSTICS -> HudAction.TOGGLE_DIAGNOSTICS
            DrivingHudAction.TOGGLE_NAVIGATION -> HudAction.TOGGLE_NAVIGATION
            DrivingHudAction.SHOW_ENGINE -> HudAction.SHOW_ENGINE
            DrivingHudAction.VOICE -> HudAction.VOICE
            DrivingHudAction.EXIT -> HudAction.EXIT
        })
    },
) {
    fun render(value: DrivingHudState) = render(value.toCinematicScene())
}

internal fun DrivingHudState.toCinematicScene() = CinematicDrivingScene(
    speedMph = speedMph,
    heading = heading,
    rpm = rpm,
    fuelPercent = fuelPercent,
    engineTempF = engineTempF,
    batteryVolts = batteryVolts,
    engineLoadPercent = engineLoadPercent,
    nextTurnDistanceFt = nextTurnDistanceFt,
    nextRoad = nextRoad,
    navigationInstruction = navigationInstruction,
    navigationDistance = navigationDistance,
    navigationSource = navigationSource,
    navigationAccessGranted = navigationAccessGranted,
    roadStatus = roadStatus,
    roadDetail = roadDetail,
    diagnosticsExpanded = diagnosticsExpanded,
    navigationExpanded = navigationExpanded,
    listening = listening,
    vehicleMoving = vehicleMoving,
    obdConnected = obdConnected,
    sourceLabel = sourceLabel,
    alertMessage = alertMessage,
)
