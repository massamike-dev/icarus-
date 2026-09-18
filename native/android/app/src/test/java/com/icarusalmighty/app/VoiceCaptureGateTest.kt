package com.icarusalmighty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureGateTest {
    @Test fun resultFollowedByDestroyErrorCannotFinishTheNextPhase() {
        val gate = VoiceCaptureGate()
        val capture = gate.start()
        assertTrue(gate.consume(capture))
        assertFalse(gate.consume(capture))
    }

    @Test fun oldRecognizerCannotCancelConfirmationCapture() {
        val gate = VoiceCaptureGate()
        val command = gate.start()
        val confirmation = gate.start()
        assertFalse(gate.consume(command))
        assertTrue(gate.consume(confirmation))
    }

    @Test fun timeoutOrCloseRejectsLateRecognitionResults() {
        val gate = VoiceCaptureGate()
        val expired = gate.start()
        gate.cancel()
        assertFalse(gate.consume(expired))
        val nextTurn = gate.start()
        assertFalse(gate.consume(expired))
        assertTrue(gate.consume(nextTurn))
    }
}
