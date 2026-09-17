package com.icarusalmighty.app
import org.junit.Assert.*
import org.junit.Test
class HandsFreeCommandsTest {
    @Test fun parsesOfflineDeviceActions() {
        assertEquals(HandsFreeCommands.Command("make_call", "Trisha"), HandsFreeCommands.parse("Call Trisha"))
        assertEquals(HandsFreeCommands.Command("set_timer", "120"), HandsFreeCommands.parse("set a timer for 2 minutes"))
        assertEquals(HandsFreeCommands.Command("toggle_flashlight", "off"), HandsFreeCommands.parse("turn off the flashlight"))
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
