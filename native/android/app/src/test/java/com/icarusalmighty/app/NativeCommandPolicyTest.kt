package com.icarusalmighty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCommandPolicyTest {
    @Test
    fun unsupportedActionFallsBackToUnknown() {
        assertEquals("unknown", NativeCommandPolicy.normalizeAction("factory_reset"))
    }

    @Test
    fun allowListedActionIsPreserved() {
        assertEquals("set_timer", NativeCommandPolicy.normalizeAction("set_timer"))
    }

    @Test
    fun sensitiveActionsRequireConfirmation() {
        assertTrue(NativeCommandPolicy.requiresConfirmation("send_sms"))
        assertTrue(NativeCommandPolicy.requiresConfirmation("make_call"))
        assertTrue(NativeCommandPolicy.requiresConfirmation("navigate_to"))
        assertFalse(NativeCommandPolicy.requiresConfirmation("get_battery"))
    }

    @Test
    fun smsWithoutRecipientRequiresClarification() {
        assertEquals(
            "Who would you like me to text?",
            NativeCommandPolicy.clarificationFor("send_sms", "", "On my way"),
        )
    }

    @Test
    fun smsWithoutMessageRequiresClarification() {
        assertEquals(
            "What would you like me to text Trisha?",
            NativeCommandPolicy.clarificationFor("send_sms", "Trisha", ""),
        )
    }

    @Test
    fun completeSafeCommandNeedsNoClarification() {
        assertEquals("", NativeCommandPolicy.clarificationFor("set_timer", "", ""))
    }
}
