package com.icarusalmighty.app

import org.junit.Assert.*
import org.junit.Test

class HandsFreeActionPolicyTest {
    @Test fun deterministicShortcutsPreserveExistingBehavior() {
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("get_battery", false))
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("toggle_flashlight", false))
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("set_volume", false))
        assertTrue(HandsFreeActionPolicy.requiresConfirmation("make_call", false))
        assertTrue(HandsFreeActionPolicy.requiresConfirmation("set_timer", false))
    }

    @Test fun allModelOriginMutationsNeedSpokenConfirmation() {
        for (action in HandsFreeCommands.supported - setOf("get_battery", "cancel")) {
            assertTrue(action, HandsFreeActionPolicy.requiresConfirmation(action, true))
        }
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("get_battery", true))
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("cancel", true))
    }
}
