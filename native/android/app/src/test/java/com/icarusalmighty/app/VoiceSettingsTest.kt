package com.icarusalmighty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceSettingsTest {
    @Test fun namedProfilesRestoreTheirOwnValuesWhileKeepingSelectedVoice() {
        assertEquals(VoiceSettings(voiceName = "English voice"), VoiceSettings.create("deep_warm", "English voice", 1.4f, 1.3f))
        assertEquals(VoiceSettings("standard", "English voice", 1f, 1f), VoiceSettings.create("standard", "English voice", 0.6f, 0.7f))
    }

    @Test fun customRetainsVoiceAndBoundsBothControls() {
        assertEquals(VoiceSettings("custom", "", 0.5f, 1.5f), VoiceSettings.create("custom", "", -10f, 9f))
        assertEquals(VoiceSettings("custom", "en-US", 1.2f, 0.7f), VoiceSettings.create("custom", "en-US", 1.2f, 0.7f))
    }

    @Test fun invalidSettingsCannotBePersistedAsApparentlyValidPreferences() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { VoiceSettings.create("custom", "", value, 1f) }
            assertThrows(IllegalArgumentException::class.java) { VoiceSettings.create("deep_warm", "", 1f, value) }
        }
        assertThrows(IllegalArgumentException::class.java) { VoiceSettings.create("fake", "", 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { VoiceSettings.create("custom", "voice\nname", 1f, 1f) }
    }
}
