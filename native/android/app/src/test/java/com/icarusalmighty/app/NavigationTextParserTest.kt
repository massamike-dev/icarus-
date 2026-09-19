package com.icarusalmighty.app

import com.icarusalmighty.app.driving.NavigationTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTextParserTest {
    @Test fun extractsOnlyActualNotificationManeuverAndDistance() {
        val result = NavigationTextParser.parse(listOf("Navigation", "Turn right onto 172nd St", "500 ft", "Google Maps"), "GOOGLE MAPS")
        assertTrue(result.active)
        assertEquals("Turn right onto 172nd St", result.instruction)
        assertEquals("500 ft", result.distance)
        assertEquals("GOOGLE MAPS", result.source)
    }

    @Test fun emptyOrGenericNotificationNeverInventsARoute() {
        val result = NavigationTextParser.parse(listOf("Navigation", "Google Maps"), "GOOGLE MAPS")
        assertFalse(result.active)
        assertNull(result.instruction)
        assertNull(result.distance)
    }

    @Test fun distanceWithoutAManeuverNeverBecomesAnInstruction() {
        val result = NavigationTextParser.parse(listOf("Navigation", "500 ft", "Google Maps"), "GOOGLE MAPS")
        assertFalse(result.active)
        assertNull(result.instruction)
    }
}
