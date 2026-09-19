package com.icarusalmighty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialHudTargetTest {
    @Test fun bundledIsTheDefaultAndNeverRequiresTheLegacyCompanion() {
        assertEquals(SpatialHudTarget.BUNDLED, SpatialHudTarget.parse(""))
        assertEquals(SpatialHudTarget.BUNDLED, SpatialHudTarget.parse("bundled"))
    }

    @Test fun unityCompanionMustBeExplicitlySelected() {
        assertEquals(SpatialHudTarget.COMPANION, SpatialHudTarget.parse("companion"))
        assertEquals(SpatialHudTarget.COMPANION, SpatialHudTarget.parse(" COMPANION "))
    }

    @Test fun unknownTargetsCannotSilentlyLaunchAnotherSurface() {
        assertNull(SpatialHudTarget.parse("beam"))
        assertNull(SpatialHudTarget.parse("3dof"))
    }
}
