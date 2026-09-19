package com.icarusalmighty.app

import com.icarusalmighty.xreal.XrealHudSession
import org.junit.Assert.assertFalse
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
}
