package com.icarusalmighty.app

import com.icarusalmighty.app.driving.DrivingTelemetryMath
import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingTelemetryMathTest {
    @Test fun convertsPhoneGpsMetersPerSecondToMph() {
        assertEquals(0, DrivingTelemetryMath.speedMph(0f))
        assertEquals(22, DrivingTelemetryMath.speedMph(10f))
        assertEquals(60, DrivingTelemetryMath.speedMph(26.8224f))
    }

    @Test fun mapsBearingToCardinalHeading() {
        assertEquals("N", DrivingTelemetryMath.cardinalHeading(0f))
        assertEquals("NE", DrivingTelemetryMath.cardinalHeading(44f))
        assertEquals("E", DrivingTelemetryMath.cardinalHeading(90f))
        assertEquals("SW", DrivingTelemetryMath.cardinalHeading(225f))
        assertEquals("N", DrivingTelemetryMath.cardinalHeading(359f))
        assertEquals("W", DrivingTelemetryMath.cardinalHeading(-90f))
    }
}
