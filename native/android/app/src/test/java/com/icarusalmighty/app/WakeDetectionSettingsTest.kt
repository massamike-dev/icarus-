package com.icarusalmighty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeDetectionSettingsTest {
    @Test fun defaultKeepsShippedKeywordBehavior() {
        val settings = WakeDetectionSettings.fromSensitivity(60)
        assertEquals(1.8f, settings.score, 0.0001f)
        assertEquals(0.30f, settings.threshold, 0.0001f)
    }

    @Test fun increasingSensitivityAlwaysMakesBothControlsEasier() {
        for (sensitivity in 25 until 90) {
            val lower = WakeDetectionSettings.fromSensitivity(sensitivity)
            val higher = WakeDetectionSettings.fromSensitivity(sensitivity + 1)
            assertTrue(higher.score > lower.score)
            assertTrue(higher.threshold < lower.threshold)
            assertTrue(higher.threshold in 0f..1f)
        }
    }

    @Test fun invalidPreferencesAreClamped() {
        assertEquals(WakeDetectionSettings.fromSensitivity(25), WakeDetectionSettings.fromSensitivity(-1))
        assertEquals(WakeDetectionSettings.fromSensitivity(90), WakeDetectionSettings.fromSensitivity(101))
    }
}
