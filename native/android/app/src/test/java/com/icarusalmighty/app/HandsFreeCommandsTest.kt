package com.icarusalmighty.app
import org.junit.Assert.*
import org.junit.Test
class HandsFreeCommandsTest {
    @Test fun parsesOfflineDeviceActions() {
        assertEquals(HandsFreeCommands.Command("make_call", "Trisha"), HandsFreeCommands.parse("Call Trisha"))
        assertEquals(HandsFreeCommands.Command("set_timer", "120"), HandsFreeCommands.parse("set a timer for 2 minutes"))
        assertEquals(HandsFreeCommands.Command("toggle_flashlight", "off"), HandsFreeCommands.parse("turn off the flashlight"))
    }
    @Test fun closeAndHomeNeverNeedTheModel() {
        for (phrase in listOf("Go home", "go to the home screen", "return home", "close this app", "close", "yes go home", "yes, go home")) {
            assertEquals(phrase, HandsFreeCommands.Command("go_home"), HandsFreeCommands.parse(phrase))
        }
        assertTrue("go_home" in HandsFreeCommands.supported)
        assertEquals(HandsFreeCommands.Command("close_app", "Facebook"), HandsFreeCommands.parse("yes close Facebook"))
        assertEquals(HandsFreeCommands.Command("close_app", "YouTube"), HandsFreeCommands.parse("Close YouTube."))
        assertEquals(HandsFreeCommands.Command("unsupported_force_stop"), HandsFreeCommands.parse("force stop Facebook"))
        assertEquals(HandsFreeCommands.Command("unsupported_force_stop"), HandsFreeCommands.parse("force-stop Facebook"))
        assertFalse("unsupported_force_stop" in HandsFreeCommands.supported)
    }
    @Test fun appNamesRemainDynamicAndTerminalPunctuationIsNotPartOfName() {
        for (app in listOf("Facebook", "YouTube", "Spotify")) {
            for (prefix in listOf("", "Yes ", "YES, ")) {
                assertEquals(HandsFreeCommands.Command("open_app", app), HandsFreeCommands.parse("${prefix}Open $app."))
            }
        }
        assertNull(HandsFreeCommands.parse("open"))
    }
    @Test fun refusesAmbiguousAndOutOfRangeCommands() {
        assertNull(HandsFreeCommands.parse("set volume to 101"))
        assertNull(HandsFreeCommands.parse("timer for 999999999999999999999 minutes"))
        assertNull(HandsFreeCommands.parse("send all my files"))
        assertFalse(HandsFreeCommands.confirms("yes but don't call"))
        assertTrue(HandsFreeCommands.confirms("Yes."))
    }
    @Test fun distinguishesCancelFromDurableDisable() {
        assertEquals("cancel",HandsFreeCommands.parse("stop")?.action)
        assertEquals("stop_listening",HandsFreeCommands.parse("stop listening")?.action)
    }
}
