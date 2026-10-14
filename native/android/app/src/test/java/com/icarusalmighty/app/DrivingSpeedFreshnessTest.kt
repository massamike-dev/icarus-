package com.icarusalmighty.app

import com.icarusalmighty.app.driving.DrivingHudState
import com.icarusalmighty.app.driving.DrivingSpeedFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingSpeedFreshnessTest {
    private fun DrivingSpeedFreshness.hudAt(now: Long): DrivingHudState {
        val reading = current(now)
        return DrivingHudState(
            speedMph = reading.speedMph,
            vehicleMoving = reading.vehicleMoving,
            speedValidUntilElapsedRealtimeMs = reading.validUntilElapsedRealtimeMs,
        )
    }

    @Test fun absentEvidenceCannotUnlockControls() {
        val policy = DrivingSpeedFreshness()
        assertNull(policy.current(10_000L).speedMph)
        assertNull(policy.current(10_000L).vehicleMoving)
        assertFalse(policy.hudAt(10_000L).parkedControlsAllowedAt(10_000L))
        assertFalse(DrivingHudState(speedMph = 0, vehicleMoving = false).parkedControlsAllowedAt(10_000L))
    }

    @Test fun freshExactZeroExpiresWithoutAnotherCallback() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(0.0, 10_000L, 10_000L)
        assertTrue(policy.hudAt(12_999L).parkedControlsAllowedAt(12_999L))
        assertNull(policy.current(13_000L).speedMph)
        assertNull(policy.current(13_000L).vehicleMoving)
        assertFalse(policy.hudAt(13_000L).parkedControlsAllowedAt(13_000L))
    }

    @Test fun cachedZeroUsesMeasurementAgeNotReceiptAge() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(0.0, 7_000L, 10_000L)
        assertNull(policy.current(10_000L).speedMph)
        policy.recordGps(0.0, 9_000L, 10_000L)
        assertEquals(12_000L, policy.current(10_000L).validUntilElapsedRealtimeMs)
        assertNull(policy.current(12_000L).speedMph)
    }

    @Test fun missingSpeedReplacesOldZeroWithUnknown() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(0.0, 10_000L, 10_000L)
        policy.recordGps(null, 10_750L, 10_750L)
        assertNull(policy.current(10_750L).speedMph)
        assertFalse(policy.hudAt(10_750L).parkedControlsAllowedAt(10_750L))
    }

    @Test fun invalidSpeedNeverBecomesZeroOrRetainsPreviousZero() {
        for (badSpeed in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            val policy = DrivingSpeedFreshness()
            policy.recordGps(0.0, 10_000L, 10_000L)
            policy.recordGps(badSpeed, 10_750L, 10_750L)
            assertNull("invalid speed $badSpeed", policy.current(10_750L).speedMph)
            assertFalse(policy.hudAt(10_750L).parkedControlsAllowedAt(10_750L))
        }
    }

    @Test fun invalidFutureZeroAndOverflowTimestampsFailClosed() {
        for (timestamp in listOf(-1L, 0L, 10_001L, Long.MAX_VALUE)) {
            val policy = DrivingSpeedFreshness()
            policy.recordGps(0.0, timestamp, 10_000L)
            assertNull(policy.current(10_000L).speedMph)
        }
        assertFalse(DrivingSpeedFreshness.isFresh(Long.MAX_VALUE - 1L, Long.MAX_VALUE))
    }

    @Test fun positiveUnroundedSpeedCannotUnlockEvenWhenDisplayRoundsToZero() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(0.01, 10_000L, 10_000L)
        val state = policy.hudAt(10_000L)
        assertEquals(0, state.speedMph)
        assertEquals(true, state.vehicleMoving)
        assertFalse(state.parkedControlsAllowedAt(10_000L))
        policy.recordGps(1.0, 10_750L, 10_750L)
        assertFalse(policy.hudAt(10_750L).parkedControlsAllowedAt(10_750L))
    }

    @Test fun freshMovementOverridesConflictingZeroInEitherSource() {
        val gpsMoving = DrivingSpeedFreshness()
        gpsMoving.recordGps(35.0, 10_000L, 10_000L)
        gpsMoving.recordObd(0.0, 10_100L, 10_100L)
        assertEquals(35, gpsMoving.current(10_100L).speedMph)
        assertEquals(DrivingSpeedFreshness.Source.GPS, gpsMoving.current(10_100L).source)
        assertFalse(gpsMoving.hudAt(10_100L).parkedControlsAllowedAt(10_100L))
        val obdMoving = DrivingSpeedFreshness()
        obdMoving.recordGps(0.0, 10_000L, 10_000L)
        obdMoving.recordObd(35.0, 10_100L, 10_100L)
        assertEquals(35, obdMoving.current(10_100L).speedMph)
        assertEquals(DrivingSpeedFreshness.Source.OBD, obdMoving.current(10_100L).source)
        assertFalse(obdMoving.hudAt(10_100L).parkedControlsAllowedAt(10_100L))
    }

    @Test fun repeatedDeliveryCannotRefreshMeasurementLifetime() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(0.0, 10_000L, 10_000L)
        policy.recordGps(0.0, 10_000L, 12_000L)
        assertEquals(13_000L, policy.current(12_000L).validUntilElapsedRealtimeMs)
        assertNull(policy.current(13_000L).speedMph)
    }

    @Test fun olderZeroCannotOverwriteNewerMovingOrMissingReading() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(30.0, 10_750L, 10_750L)
        policy.recordGps(0.0, 10_000L, 11_000L)
        assertEquals(30, policy.current(11_000L).speedMph)
        policy.recordGps(null, 11_100L, 11_100L)
        policy.recordGps(0.0, 10_900L, 11_200L)
        assertNull(policy.current(11_200L).speedMph)
    }

    @Test fun conflictingValuesAtIdenticalMeasurementTimeFailClosed() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(0.0, 10_000L, 10_000L)
        policy.recordGps(10.0, 10_000L, 10_500L)
        assertNull(policy.current(10_500L).speedMph)
        policy.recordGps(0.0, 10_000L, 10_600L)
        assertNull(policy.current(10_600L).speedMph)
    }

    @Test fun providerOrPermissionLossRejectsCachedAndInFlightGpsSamples() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(0.0, 10_000L, 10_000L)
        policy.clearGps(10_750L)
        assertNull(policy.current(10_750L).speedMph)
        policy.recordGps(0.0, 10_000L, 11_000L)
        policy.recordGps(0.0, 10_500L, 11_100L)
        assertNull(policy.current(11_100L).speedMph)
        policy.recordGps(0.0, 11_500L, 11_500L)
        assertTrue(policy.hudAt(11_500L).parkedControlsAllowedAt(11_500L))
    }

    @Test fun disconnectRejectsInFlightObdBatchAndUsesOnlyFreshGps() {
        val policy = DrivingSpeedFreshness()
        policy.recordObd(0.0, 10_000L, 10_000L)
        policy.recordGps(28.0, 10_500L, 10_500L)
        policy.clearObd(10_750L)
        policy.recordObd(0.0, 10_600L, 11_000L)
        assertEquals(DrivingSpeedFreshness.Source.GPS, policy.current(11_000L).source)
        assertEquals(28, policy.current(11_000L).speedMph)
        assertNull(policy.current(13_500L).speedMph)
    }

    @Test fun slowObdBatchCannotReTimestampEarlyZeroAtCompletion() {
        val policy = DrivingSpeedFreshness()
        policy.recordObd(0.0, 10_000L, 13_000L)
        assertNull(policy.current(13_000L).speedMph)
        policy.recordGps(25.0, 12_500L, 13_000L)
        assertEquals(25, policy.current(13_000L).speedMph)
        assertEquals(DrivingSpeedFreshness.Source.GPS, policy.current(13_000L).source)
    }

    @Test fun stuckObdWorkerExpiresAndGpsFallbackAlsoExpires() {
        val policy = DrivingSpeedFreshness()
        policy.recordObd(0.0, 10_000L, 10_000L)
        policy.recordGps(0.0, 11_000L, 11_000L)
        assertEquals(DrivingSpeedFreshness.Source.OBD, policy.current(12_999L).source)
        assertEquals(DrivingSpeedFreshness.Source.GPS, policy.current(13_000L).source)
        assertTrue(policy.hudAt(13_000L).parkedControlsAllowedAt(13_000L))
        assertNull(policy.current(14_000L).vehicleMoving)
    }

    @Test fun missingObdSpeedDiscardsOldZeroWithoutExtendingGpsFallback() {
        val policy = DrivingSpeedFreshness()
        policy.recordObd(0.0, 10_000L, 10_000L)
        policy.recordGps(0.0, 10_500L, 10_500L)
        policy.recordObd(null, 11_000L, 11_000L)
        assertEquals(DrivingSpeedFreshness.Source.GPS, policy.current(11_000L).source)
        assertEquals(13_500L, policy.current(11_000L).validUntilElapsedRealtimeMs)
        assertNull(policy.current(13_500L).speedMph)
    }

    @Test fun staleUiSnapshotChecksDeadlineAtTapTimeEvenBeforeWatchdogRuns() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(0.0, 10_000L, 10_000L)
        val oldUiState = policy.hudAt(10_000L)
        assertTrue(oldUiState.parkedControlsAllowedAt(12_999L))
        assertFalse(oldUiState.parkedControlsAllowedAt(13_000L))
        assertFalse(oldUiState.parkedControlsAllowedAt(80_000L))
        assertFalse(oldUiState.parkedControlsAllowedAt(-1L))
    }
    @Test fun staleOrFutureGpsCannotEraseMotionAndExposeConflictingObdZero() {
        for (badTimestamp in listOf(1_000L, 12_000L, 0L)) {
            val policy = DrivingSpeedFreshness()
            policy.recordGps(10.0, 10_000L, 10_000L)
            policy.recordObd(0.0, 9_900L, 10_000L)
            policy.recordGps(0.0, badTimestamp, 10_100L)
            assertFalse(policy.hudAt(10_100L).parkedControlsAllowedAt(10_100L))
        }
    }

    @Test fun missingInvalidOrConflictingGpsCannotEraseFreshMovementVeto() {
        for (badSpeed in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val policy = DrivingSpeedFreshness()
            policy.recordGps(0.1, 10_000L, 10_000L)
            policy.recordObd(0.0, 10_100L, 10_100L)
            policy.recordGps(badSpeed, 10_500L, 10_500L)
            assertFalse(policy.hudAt(10_500L).parkedControlsAllowedAt(10_500L))
            assertEquals(true, policy.current(10_500L).vehicleMoving)
            // The veto expires at its original measurement deadline, not at invalid-input receipt.
            assertTrue(policy.hudAt(13_000L).parkedControlsAllowedAt(13_000L))
        }
        val conflict = DrivingSpeedFreshness()
        conflict.recordGps(10.0, 10_000L, 10_000L)
        conflict.recordObd(0.0, 10_100L, 10_100L)
        conflict.recordGps(0.0, 10_000L, 10_500L)
        assertFalse(conflict.hudAt(10_500L).parkedControlsAllowedAt(10_500L))
    }

    @Test fun sourceLossCannotUseOtherZeroToEraseRecentMotion() {
        val policy = DrivingSpeedFreshness()
        policy.recordObd(0.1, 10_000L, 10_000L)
        policy.recordGps(0.0, 10_100L, 10_100L)
        policy.clearObd(10_500L)
        assertFalse(policy.hudAt(10_500L).parkedControlsAllowedAt(10_500L))
        assertTrue(policy.hudAt(13_000L).parkedControlsAllowedAt(13_000L))
    }

    @Test fun newerValidZeroMayClearOnlyItsOwnMovementVeto() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(20.0, 10_000L, 10_000L)
        policy.recordObd(10.0, 10_100L, 10_100L)
        policy.recordGps(0.0, 10_500L, 10_500L)
        assertFalse(policy.hudAt(10_500L).parkedControlsAllowedAt(10_500L))
        policy.recordObd(0.0, 10_600L, 10_600L)
        assertTrue(policy.hudAt(10_600L).parkedControlsAllowedAt(10_600L))
    }

    @Test fun zeroThenPositiveAtSameTimestampCannotExposeAnotherZero() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(0.0, 10_000L, 10_000L)
        policy.recordObd(0.0, 10_100L, 10_100L)
        policy.recordGps(0.1, 10_000L, 10_500L)
        assertFalse(policy.hudAt(10_500L).parkedControlsAllowedAt(10_500L))
    }

    @Test fun networkProviderCannotClearGpsProvidersMovingEvidence() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(20.0, 10_000L, 10_000L, "gps")
        policy.recordGps(0.0, 10_100L, 10_100L, "network")
        assertEquals(20, policy.current(10_100L).speedMph)
        assertFalse(policy.hudAt(10_100L).parkedControlsAllowedAt(10_100L))
        policy.recordGps(null, 10_500L, 10_500L, "network")
        assertEquals(20, policy.current(10_500L).speedMph)
    }

    @Test fun providerAddedAfterInvalidationCannotRestoreEarlierCachedZero() {
        val policy = DrivingSpeedFreshness()
        policy.clearGps(10_500L)
        policy.recordGps(0.0, 10_000L, 11_000L, "network")
        assertNull(policy.current(11_000L).speedMph)
        policy.recordGps(0.0, 11_500L, 11_500L, "network")
        assertTrue(policy.hudAt(11_500L).parkedControlsAllowedAt(11_500L))
    }

    @Test fun delayedFreshMotionStillVetoesZeroAfterNewerMissingPacket() {
        val policy = DrivingSpeedFreshness()
        policy.recordGps(null, 11_000L, 11_000L)
        policy.recordObd(0.0, 11_100L, 11_100L)
        policy.recordGps(10.0, 10_500L, 11_200L)
        assertFalse(policy.hudAt(11_200L).parkedControlsAllowedAt(11_200L))
        assertFalse(policy.hudAt(13_499L).parkedControlsAllowedAt(13_499L))
        assertTrue(policy.hudAt(13_500L).parkedControlsAllowedAt(13_500L))
    }

}
