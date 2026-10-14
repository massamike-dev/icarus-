package com.icarusalmighty.app

import com.icarusalmighty.xreal.XrealHudSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrealHudSessionTest {
    @Test fun closingWithoutALaunchDoesNotPretendAnActivityWasClosed() {
        val session = XrealHudSession()
        assertFalse(session.requestClose())
        assertFalse(session.activityOpen)
    }

    @Test fun aCloseBetweenStartActivityAndOnCreateCancelsThePendingLaunch() {
        val session = XrealHudSession()
        session.launchRequested()
        assertTrue(session.launchPending)
        assertTrue(session.requestClose())
        assertTrue(session.activityCreated())
        assertFalse(session.launchPending)
    }

    @Test fun closeOnlyBecomesInactiveAfterActivityDestruction() {
        val session = XrealHudSession()
        session.launchRequested()
        assertFalse(session.activityCreated())
        assertTrue(session.requestClose())
        assertTrue(session.activityOpen)
        session.activityDestroyed()
        assertFalse(session.activityOpen)
        assertFalse(session.requestClose())
    }

    @Test fun aNewLaunchDoesNotInheritAnOldCloseRequest() {
        val session = XrealHudSession()
        session.launchRequested()
        session.requestClose()
        session.activityCreated()
        session.activityDestroyed()
        session.launchRequested()
        assertFalse(session.activityCreated())
        assertTrue(session.activityOpen)
    }

    @Test fun aFailedLaunchClearsPendingState() {
        val session = XrealHudSession()
        session.launchRequested()
        session.launchFailed()
        assertFalse(session.launchPending)
        assertFalse(session.requestClose())
    }

    @Test fun aLaunchRequestDoesNotInventAnActivityDisplay() {
        val session = XrealHudSession()
        session.launchRequested()
        session.updateActualDisplayId(4)
        assertNull(session.actualDisplayId)
        assertFalse(session.activityOpen)
        session.activityCreated()
        assertNull(session.actualDisplayId)
        session.updateActualDisplayId(4)
        assertEquals(4, session.actualDisplayId)
    }

    @Test fun disappearingOrInvalidDisplaysClearTheObservation() {
        val session = XrealHudSession()
        session.activityCreated()
        session.updateActualDisplayId(0)
        assertEquals(0, session.actualDisplayId)
        session.updateActualDisplayId(null)
        assertNull(session.actualDisplayId)
        session.updateActualDisplayId(-1)
        assertNull(session.actualDisplayId)
    }

    @Test fun activityDestructionAndRecreationDoNotRetainAnOldDisplay() {
        val session = XrealHudSession()
        session.activityCreated()
        session.updateActualDisplayId(7)
        session.activityDestroyed()
        assertNull(session.actualDisplayId)
        session.updateActualDisplayId(7)
        assertNull(session.actualDisplayId)
        session.launchRequested()
        session.activityCreated()
        assertNull(session.actualDisplayId)
    }

    @Test fun aFailedRelaunchDoesNotEraseAStillLiveActivityObservation() {
        val session = XrealHudSession()
        session.activityCreated()
        session.updateActualDisplayId(2)
        session.launchRequested()
        session.launchFailed()
        assertTrue(session.activityOpen)
        assertEquals(2, session.actualDisplayId)
        assertFalse(session.launchPending)
    }
    @Test fun actualDestructionReleasesTheOwnersLeaseOnce() {
        val session = XrealHudSession()
        var closed = 0
        val listener: () -> Unit = { closed++ }
        session.setOnClosedListener(listener)
        session.launchRequested()
        session.activityCreated()
        session.activityDestroyed()
        session.activityDestroyed()
        org.junit.Assert.assertEquals(1, closed)
    }

    @Test fun pendingCloseWaitsForActualDestructionBeforeNotifyingTheOwner() {
        val session = XrealHudSession()
        var closed = 0
        val listener: () -> Unit = { closed++ }
        session.setOnClosedListener(listener)
        session.launchRequested()
        session.requestClose()
        org.junit.Assert.assertEquals(0, closed)
        assertTrue(session.activityCreated())
        session.activityDestroyed()
        org.junit.Assert.assertEquals(1, closed)
    }

    @Test fun aRemovedCloseListenerIsNotCalled() {
        val session = XrealHudSession()
        var closed = 0
        val listener: () -> Unit = { closed++ }
        session.setOnClosedListener(listener)
        session.setOnClosedListener(null)
        session.activityDestroyed()
        org.junit.Assert.assertEquals(0, closed)
    }
    @Test fun modeTracksTheActualActivityAndClearsWhenItCloses() {
        val session = XrealHudSession()
        session.launchRequested()
        org.junit.Assert.assertNull(session.activeMode)
        session.activityCreated(com.icarusalmighty.xreal.XrealMode.VEHICLE)
        org.junit.Assert.assertEquals(com.icarusalmighty.xreal.XrealMode.VEHICLE, session.activeMode)
        session.activityCreated(com.icarusalmighty.xreal.XrealMode.ASSISTANT)
        org.junit.Assert.assertEquals(com.icarusalmighty.xreal.XrealMode.ASSISTANT, session.activeMode)
        session.activityDestroyed()
        org.junit.Assert.assertNull(session.activeMode)
    }
    @Test fun finishingTheOldWindowDoesNotReleaseAReplacementPendingLaunch() {
        val session = XrealHudSession()
        var closed = 0
        val listener: () -> Unit = { closed++ }
        session.launchRequested()
        session.activityCreated(com.icarusalmighty.xreal.XrealMode.VEHICLE)
        session.requestClose()
        session.setOnClosedListener(listener)
        session.launchRequested()
        session.activityDestroyed()
        org.junit.Assert.assertEquals(0, closed)
        assertTrue(session.launchPending)
        assertFalse(session.activityCreated(com.icarusalmighty.xreal.XrealMode.VEHICLE))
        session.activityDestroyed()
        org.junit.Assert.assertEquals(1, closed)
    }
}
