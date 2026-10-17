package com.icarusalmighty.app

import com.icarusalmighty.xreal.XrealLogicalDisplay
import com.icarusalmighty.xreal.XrealOutputSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrealOutputSnapshotTest {
    @Test fun noVisibleDisplayDoesNotClaimDisconnectionOrVerification() {
        val json = XrealOutputSnapshot(displayEnumerationSucceeded = true).toJson()
        assertEquals("unverified", json.getString("outputVerification"))
        assertEquals("os_managed_2d_window", json.getString("outputPath"))
        assertFalse(json.getBoolean("glassesConnectionVerified"))
        assertTrue(json.isNull("actualActivityDisplayId"))
        assertEquals(0, json.getJSONArray("accessibleLogicalDisplays").length())
        assertTrue(json.getBoolean("displayEnumerationSucceeded"))
        assertFalse(json.has("connected"))
        assertFalse(json.has("disconnected"))
    }

    @Test fun aSecondaryDisplayNeverBecomesPhysicalGlassesProof() {
        val observed = XrealLogicalDisplay(
            id = 4,
            name = "XREAL display name is not an identity guarantee",
            isDefault = false,
            isPresentation = true,
            isPrivate = false,
            flags = 8,
            state = 2,
            isValid = true,
        )
        val json = XrealOutputSnapshot(4, listOf(observed), true).toJson()
        assertEquals(4, json.getInt("actualActivityDisplayId"))
        assertEquals("unverified", json.getString("outputVerification"))
        assertFalse(json.getBoolean("glassesConnectionVerified"))
        val display = json.getJSONArray("accessibleLogicalDisplays").getJSONObject(0)
        assertEquals(4, display.getInt("id"))
        assertEquals("unknown", display.getString("type"))
        assertFalse(display.getBoolean("isDefault"))
        assertTrue(display.getBoolean("isPresentation"))
    }

    @Test fun enumerationFailureIsDistinctFromASuccessfulEmptyObservation() {
        val json = XrealOutputSnapshot(
            displayEnumerationSucceeded = false,
            diagnosticError = "display_enumeration_failed",
        ).toJson()
        assertFalse(json.getBoolean("displayEnumerationSucceeded"))
        assertEquals("display_enumeration_failed", json.getString("diagnosticError"))
        assertEquals("unverified", json.getString("outputVerification"))
        assertFalse(json.getBoolean("glassesConnectionVerified"))
    }
}
