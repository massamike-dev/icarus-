package com.icarusalmighty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationPolicyTest {
    @Test
    fun optionalIntegrationsDefaultOff() {
        val flags = IntegrationFlags()
        assertFalse(IntegrationPolicy.shouldInitializeMeta(flags))
        assertFalse(IntegrationPolicy.shouldInitializeXreal(flags))
        assertFalse(IntegrationPolicy.capabilities(flags).any { it.startsWith("meta_") })
        assertFalse(IntegrationPolicy.capabilities(flags).any { it.startsWith("xreal_") || it == "open_xreal_hud" })
    }

    @Test
    fun phoneDrivingModeIsTheFallbackWhenEverythingOptionalIsOff() {
        val surface = IntegrationPolicy.drivingSurface(IntegrationFlags(), xrealRuntimeAvailable = false)
        assertEquals(DrivingSurface.PHONE, surface)
        assertTrue(IntegrationPolicy.capabilities(IntegrationFlags()).contains("navigate_to"))
        assertTrue(IntegrationPolicy.capabilities(IntegrationFlags()).contains("get_location"))
    }

    @Test
    fun xrealIsAdvertisedOnlyWhenEnabled() {
        val off = IntegrationPolicy.capabilities(IntegrationFlags(xrealEnabled = false))
        val on = IntegrationPolicy.capabilities(IntegrationFlags(xrealEnabled = true))
        assertFalse(off.contains("open_xreal_hud"))
        assertTrue(on.contains("open_xreal_hud"))
        assertTrue(on.contains("xreal_status"))
    }

    @Test
    fun xrealFailureFallsBackToPhone() {
        val flags = IntegrationFlags(xrealEnabled = true)
        assertEquals(DrivingSurface.PHONE, IntegrationPolicy.drivingSurface(flags, xrealRuntimeAvailable = false))
        assertEquals(DrivingSurface.XREAL, IntegrationPolicy.drivingSurface(flags, xrealRuntimeAvailable = true))
    }

    @Test
    fun metaCapabilitiesAreIndependentFromXreal() {
        val metaOnly = IntegrationPolicy.capabilities(IntegrationFlags(metaEnabled = true, xrealEnabled = false))
        assertTrue(metaOnly.contains("meta_status"))
        assertFalse(metaOnly.contains("open_xreal_hud"))
    }
}
