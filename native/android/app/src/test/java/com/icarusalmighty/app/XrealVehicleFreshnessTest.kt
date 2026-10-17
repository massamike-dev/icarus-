package com.icarusalmighty.app

import com.icarusalmighty.xreal.XrealHudState
import com.icarusalmighty.xreal.XrealVehicleFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class XrealVehicleFreshnessTest {
    private fun snapshot() = XrealHudState(
        speedMph = 0, vehicleMoving = false, rpm = 800, fuelPercent = 50,
        engineTempF = 185, engineLoadPercent = 12, batteryVolts = 13.8,
        obdConnected = true, heading = "N", sourceLabel = "OBD LIVE",
        navigationInstruction = "Turn left", navigationDistance = "300 ft",
        navigationSource = "Maps", roadStatus = "ROUTE ACTIVE",
        vehicleSnapshotAtElapsedRealtimeMs = 10_000L,
        vehicleSnapshotValidUntilElapsedRealtimeMs = 15_000L,
        speedValidUntilElapsedRealtimeMs = 12_000L,
    )

    @Test fun freshSnapshotPreservesMeasuredZeroAndStoppedState() {
        val state = snapshot()
        assertEquals(state, XrealVehicleFreshness.sanitize(state, 10_500L))
        assertEquals(12_000L, XrealVehicleFreshness.nextExpiryAt(state, 10_500L))
    }

    @Test fun exactSpeedDeadlineClearsStoppedEvidenceWithoutExtendingItsAge() {
        val fresh = XrealVehicleFreshness.sanitize(snapshot(), 12_000L)
        assertNull(fresh.speedMph)
        assertNull(fresh.vehicleMoving)
        assertNull(fresh.speedValidUntilElapsedRealtimeMs)
        assertEquals(800, fresh.rpm)
        assertEquals("Turn left", fresh.navigationInstruction)
        assertEquals(15_000L, XrealVehicleFreshness.nextExpiryAt(snapshot(), 12_000L))
    }

    @Test fun noFurtherMessagesClearsAllVehicleAndRouteValuesAtTransportDeadline() {
        val expired = XrealVehicleFreshness.sanitize(snapshot(), 15_000L)
        assertNull(expired.speedMph)
        assertNull(expired.vehicleMoving)
        assertNull(expired.rpm)
        assertNull(expired.fuelPercent)
        assertNull(expired.engineTempF)
        assertNull(expired.engineLoadPercent)
        assertNull(expired.batteryVolts)
        assertNull(expired.heading)
        assertNull(expired.navigationInstruction)
        assertNull(expired.navigationDistance)
        assertFalse(expired.obdConnected)
        assertEquals("LIVE TELEMETRY UNAVAILABLE", expired.sourceLabel)
        assertNull(XrealVehicleFreshness.nextExpiryAt(snapshot(), 15_000L))
    }

    @Test fun missingFutureNegativeAndOverlongTransportTimesFailClosed() {
        val base = snapshot()
        val cases = listOf(
            base.copy(vehicleSnapshotAtElapsedRealtimeMs = null),
            base.copy(vehicleSnapshotValidUntilElapsedRealtimeMs = null),
            base.copy(vehicleSnapshotAtElapsedRealtimeMs = 11_001L),
            base.copy(vehicleSnapshotAtElapsedRealtimeMs = -1L),
            base.copy(vehicleSnapshotValidUntilElapsedRealtimeMs = 15_001L),
            base.copy(vehicleSnapshotValidUntilElapsedRealtimeMs = 9_999L),
        )
        cases.forEach { candidate ->
            assertNull(XrealVehicleFreshness.sanitize(candidate, 11_000L).speedMph)
            assertNull(XrealVehicleFreshness.sanitize(candidate, 11_000L).rpm)
            assertNull(XrealVehicleFreshness.nextExpiryAt(candidate, 11_000L))
        }
        // A timestamp from a later pre-reboot uptime is invalid at the new uptime.
        assertNull(XrealVehicleFreshness.sanitize(base, 1_000L).navigationInstruction)
        assertNull(XrealVehicleFreshness.sanitize(base, -1L).speedMph)
    }

    @Test fun missingOrImplausiblyLongSpeedDeadlineNeverPermitsStoppedEvidence() {
        for (deadline in listOf(null, 16_000L)) {
            val candidate = snapshot().copy(speedValidUntilElapsedRealtimeMs = deadline)
            val sanitized = XrealVehicleFreshness.sanitize(candidate, 11_000L)
            assertNull(sanitized.speedMph)
            assertNull(sanitized.vehicleMoving)
        }
    }

    @Test fun assistantMetadataCannotRenewATransportOrMeasurementDeadline() {
        val updated = snapshot().copy(assistantStatus = "Listening", primaryText = "Updated message")
        assertEquals(15_000L, updated.vehicleSnapshotValidUntilElapsedRealtimeMs)
        assertNull(XrealVehicleFreshness.sanitize(updated, 15_000L).speedMph)
        assertEquals("Updated message", XrealVehicleFreshness.sanitize(updated, 15_000L).primaryText)
    }

    @Test fun routeOnlySnapshotRemainsVisibleDuringFreshProducerHeartbeat() {
        val heartbeat = snapshot().copy(
            speedMph = null, vehicleMoving = null, speedValidUntilElapsedRealtimeMs = null,
            vehicleSnapshotAtElapsedRealtimeMs = 14_000L,
            vehicleSnapshotValidUntilElapsedRealtimeMs = 19_000L,
        )
        val sanitized = XrealVehicleFreshness.sanitize(heartbeat, 16_000L)
        assertEquals("Turn left", sanitized.navigationInstruction)
        assertNull(sanitized.speedMph)
        assertEquals(19_000L, XrealVehicleFreshness.nextExpiryAt(heartbeat, 16_000L))
    }

    @Test fun deadlineCreationIsBoundedAndRejectsOverflow() {
        assertEquals(5_000L, XrealVehicleFreshness.deadlineFor(0L))
        assertNull(XrealVehicleFreshness.deadlineFor(null))
        assertNull(XrealVehicleFreshness.deadlineFor(-1L))
        assertNull(XrealVehicleFreshness.deadlineFor(Long.MAX_VALUE))
    }
}
