package com.icarusalmighty.app

import org.junit.Assert.*
import org.junit.Test

class HandsFreeActionPolicyTest {
    @Test fun deterministicShortcutsPreserveExistingBehavior() {
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("get_battery", false))
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("toggle_flashlight", false))
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("set_volume", false))
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("open_app", false))
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("go_home", false))
        assertFalse(HandsFreeActionPolicy.requiresConfirmation("stop_listening", false))
        assertTrue(HandsFreeActionPolicy.requiresConfirmation("navigate_to", false))
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

class HandsFreeAppLaunchTest {
    @Test fun announcesActualResolvedLabelForEachApp() {
        val installed = listOf("Facebook", "YouTube", "Spotify")
        for (app in installed) {
            val resolved = HandsFreeAppLaunch.resolve("  ${app.lowercase()}  ", installed) { it }
            assertEquals(app, resolved)
            assertEquals("Opening $app.", HandsFreeAppLaunch.announcement(checkNotNull(resolved)))
        }
    }

    @Test fun unresolvedOrAmbiguousAppsCannotBeAnnouncedAsResolved() {
        assertNull(HandsFreeAppLaunch.resolve("Facebook", listOf("YouTube")) { it })
        assertNull(HandsFreeAppLaunch.resolve("Facebook", listOf("Facebook", "facebook")) { it })
        assertNull(HandsFreeAppLaunch.resolve("Face", listOf("Facebook")) { it })
        assertNull(HandsFreeAppLaunch.resolve("my bank account", listOf("Facebook")) { it })
        assertNull(HandsFreeAppLaunch.resolve("", emptyList<String>()) { it })
    }
}

class AppLaunchCancellationGateTest {
    @Test fun silenceLaunchRequiresReadinessAndEntireWindow() {
        val gate = AppLaunchCancellationGate()
        val id = gate.start()
        assertEquals(3000L, AppLaunchCancellationGate.WINDOW_MS)
        assertFalse(gate.windowElapsed(id))
        assertTrue(gate.microphoneReady(id))
        assertFalse(gate.microphoneReady(id))
        assertTrue(gate.windowElapsed(id))
        assertFalse(gate.windowElapsed(id))
        assertTrue(gate.finishWithSilence(id))
        assertFalse(gate.finishWithSilence(id))
    }

    @Test fun earlySilenceFailsClosed() {
        val gate = AppLaunchCancellationGate()
        val id = gate.start()
        gate.microphoneReady(id)
        assertFalse(gate.finishWithSilence(id))
        assertFalse(gate.windowElapsed(id))
        assertFalse(gate.finishWithSilence(id))
    }

    @Test fun readinessFailureCannotLaunch() {
        val gate = AppLaunchCancellationGate()
        val id = gate.start()
        assertFalse(gate.finishWithSilence(id))
        assertFalse(gate.microphoneReady(id))
    }

    @Test fun cancelCloseDisableOrSpeechFailureInvalidatesTimers() {
        for (stage in 0..2) {
            val gate = AppLaunchCancellationGate()
            val id = gate.start()
            if (stage >= 1) gate.microphoneReady(id)
            if (stage >= 2) gate.windowElapsed(id)
            gate.cancel()
            assertFalse(gate.microphoneReady(id))
            assertFalse(gate.windowElapsed(id))
            assertFalse(gate.finishWithSilence(id))
        }
    }

    @Test fun lateRecognitionOrTimerFromPreviousCaptureCannotLaunchReplacement() {
        val gate = AppLaunchCancellationGate()
        val old = gate.start()
        gate.microphoneReady(old)
        val current = gate.start()
        assertFalse(gate.windowElapsed(old))
        assertFalse(gate.finishWithSilence(old))
        gate.heardSpeech(old)
        assertTrue(gate.microphoneReady(current))
        assertTrue(gate.windowElapsed(current))
        assertTrue(gate.finishWithSilence(current))
    }

    @Test fun speechAtEitherSideOfDeadlineWinsEvenWhenRecognizerReturnsNoMatch() {
        for (speechAfterDeadline in listOf(false, true)) {
            val gate = AppLaunchCancellationGate()
            val id = gate.start()
            gate.microphoneReady(id)
            if (!speechAfterDeadline) gate.heardSpeech(id)
            gate.windowElapsed(id)
            if (speechAfterDeadline) gate.heardSpeech(id)
            assertTrue(gate.awaitingSpeechResult(id))
            assertFalse(gate.finishWithSilence(id))
        }
    }
}
