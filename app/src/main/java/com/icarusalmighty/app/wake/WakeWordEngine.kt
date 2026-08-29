package com.icarusalmighty.app.wake

/** Adapter point for an offline wake-word SDK. Audio should remain on-device. */
interface WakeWordEngine {
    fun start(onWake: () -> Unit): Result<Unit>
    fun stop()
}
