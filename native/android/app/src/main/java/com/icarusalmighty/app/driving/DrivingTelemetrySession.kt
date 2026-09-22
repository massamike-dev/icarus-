package com.icarusalmighty.app.driving

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

object DrivingTelemetrySession {
    private val listeners = CopyOnWriteArraySet<(DrivingHudState) -> Unit>()
    @Volatile private var current = DrivingHudState()
    private var controller: DrivingTelemetryController? = null
    private var activeObdAddress: String? = null
    private var leases = 0

    @Synchronized
    fun acquire(context: Context, obdAddress: String?, listener: (DrivingHudState) -> Unit): Lease {
        val requested = obdAddress?.trim()?.takeIf { it.isNotBlank() }
        listeners += listener
        leases += 1
        if (controller == null || (activeObdAddress == null && requested != null)) {
            controller?.stop()
            activeObdAddress = requested ?: activeObdAddress
            controller = DrivingTelemetryController(context.applicationContext, activeObdAddress, ::publish).also { it.start() }
        }
        listener(current)
        return Lease(listener)
    }

    fun snapshot(): DrivingHudState = current

    @Synchronized
    private fun release(listener: (DrivingHudState) -> Unit) {
        listeners -= listener
        leases = (leases - 1).coerceAtLeast(0)
        if (leases == 0) {
            controller?.stop()
            controller = null
            activeObdAddress = null
            current = DrivingHudState()
        }
    }

    private fun publish(value: DrivingHudState) {
        current = value
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    class Lease internal constructor(private val listener: (DrivingHudState) -> Unit) : AutoCloseable {
        @Volatile private var closed = false

        fun refreshNavigationAccess() = DrivingTelemetrySession.controller?.refreshNavigationAccess()
        fun toggleDiagnostics() = DrivingTelemetrySession.controller?.toggleDiagnostics()
        fun toggleNavigation() = DrivingTelemetrySession.controller?.toggleNavigation()
        fun showEngineDetails() = DrivingTelemetrySession.controller?.showEngineDetails()
        fun setListening(value: Boolean) = DrivingTelemetrySession.controller?.setListening(value)
        fun applyVoiceCommand(transcript: String): Boolean =
            DrivingTelemetrySession.controller?.applyVoiceCommand(transcript) == true

        override fun close() {
            if (closed) return
            closed = true
            DrivingTelemetrySession.release(listener)
        }
    }
}
