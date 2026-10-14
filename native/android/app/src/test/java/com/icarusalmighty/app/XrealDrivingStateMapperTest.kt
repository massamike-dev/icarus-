package com.icarusalmighty.app

import com.icarusalmighty.app.driving.DrivingHudState
import com.icarusalmighty.app.driving.toCinematicScene
import com.icarusalmighty.xreal.XrealHudState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrealDrivingStateMapperTest {
    @Test fun vehicleSnapshotCarriesEverySceneValueToTheSameRenderer() {
        val live = DrivingHudState(
            speedMph = 12, heading = "NW", rpm = 1300, fuelPercent = 48,
            engineTempF = 180, batteryVolts = 13.7, engineLoadPercent = 22,
            nextTurnDistanceFt = 300, nextRoad = "Lake Road",
            navigationInstruction = "Turn left onto Lake Road", navigationDistance = "300 ft",
            navigationSource = "Google Maps", navigationAccessGranted = true,
            roadStatus = "NAVIGATION GUIDANCE", roadDetail = "No road-safety assessment",
            diagnosticsExpanded = true, navigationExpanded = true, listening = true,
            vehicleMoving = true, obdConnected = true, sourceLabel = "LIVE OBD + GPS",
            alertMessage = "Live alert",
        )
        assertEquals(live.toCinematicScene(), stateFromDriving(live, XrealHudState()).toDrivingScene())
    }

    @Test fun expiredOrMissingLiveValuesCannotResurrectMetadataReadings() {
        val old = XrealHudState(
            speedMph = 0, heading = "N", rpm = 900, fuelPercent = 90,
            engineTempF = 185, batteryVolts = 14.0, engineLoadPercent = 10,
            navigationInstruction = "Turn right", navigationDistance = "100 ft",
            nextRoad = "Old Road", nextTurnDistanceFt = 100, eta = "12:00",
            vehicleMoving = false, obdConnected = true, alertText = "Old alert",
        )
        val unavailable = DrivingHudState()
        val cleared = stateFromDriving(unavailable, old)
        assertEquals(unavailable.toCinematicScene(), cleared.toDrivingScene())
        assertNull(cleared.speedMph)
        assertNull(cleared.vehicleMoving)
        assertNull(cleared.navigationInstruction)
        assertNull(cleared.eta)
    }

    @Test fun aRealZeroAndFalseMovementArePreservedRatherThanTreatedAsMissing() {
        val live = DrivingHudState(speedMph = 0, vehicleMoving = false, fuelPercent = 0, batteryVolts = 0.0)
        val mapped = stateFromDriving(live, XrealHudState(speedMph = 45, vehicleMoving = true))
        assertEquals(0, mapped.speedMph)
        assertEquals(false, mapped.vehicleMoving)
        assertEquals(0, mapped.fuelPercent)
        assertEquals(0.0, mapped.batteryVolts)
    }

    @Test fun assistantMetadataRemainsIndependentOfVehicleMeasurements() {
        val metadata = XrealHudState(assistantStatus = "Listening", primaryText = "Hello", batteryPercent = 61)
        val mapped = stateFromDriving(DrivingHudState(), metadata)
        assertEquals("Listening", mapped.assistantStatus)
        assertEquals("Hello", mapped.primaryText)
        assertEquals(61, mapped.batteryPercent)
        assertNull(mapped.speedMph)
    }
}
