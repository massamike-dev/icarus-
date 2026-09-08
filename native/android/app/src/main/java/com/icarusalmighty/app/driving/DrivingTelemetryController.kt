package com.icarusalmighty.app.driving

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.icarusalmighty.app.ObdManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class DrivingTelemetryController(
    private val context: Context,
    private val obdAddress: String?,
    private val onState: (DrivingHudState) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var state = DrivingHudState()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        when {
            obdAddress.isNullOrBlank() -> mutate {
                copy(
                    obdConnected = false,
                    sourceLabel = "NO LIVE VEHICLE DATA",
                    alertMessage = "CONNECT AN OBD ADAPTER FOR LIVE TELEMETRY"
                )
            }
            !hasBluetoothPermission() -> mutate {
                copy(
                    obdConnected = false,
                    sourceLabel = "BLUETOOTH PERMISSION REQUIRED",
                    alertMessage = "BLUETOOTH PERMISSION IS REQUIRED FOR OBD DATA"
                )
            }
            else -> startObdLoop(obdAddress)
        }
    }

    fun stop() {
        running.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    fun toggleDiagnostics() = mutate { copy(diagnosticsExpanded = !diagnosticsExpanded) }
    fun toggleNavigation() = mutate { copy(navigationExpanded = !navigationExpanded) }
    fun setListening(value: Boolean) = mutate { copy(listening = value) }

    fun showEngineDetails() = mutate {
        val temp = engineTempF?.let { "$it°F" } ?: "—"
        val load = engineLoadPercent?.let { "$it%" } ?: "—"
        val volts = batteryVolts?.let { "%.1fV".format(it) } ?: "—"
        copy(
            diagnosticsExpanded = true,
            alertMessage = "ENGINE $temp  •  LOAD $load  •  $volts"
        )
    }

    fun applyVoiceCommand(transcript: String): Boolean {
        val normalized = transcript.lowercase().trim()
        mutate { copy(lastVoiceCommand = transcript, listening = false) }
        return when {
            "show" in normalized && ("diagnostic" in normalized || "scan" in normalized) -> {
                mutate { copy(diagnosticsExpanded = true, alertMessage = "VEHICLE DIAGNOSTICS EXPANDED") }; true
            }
            ("hide" in normalized || "close" in normalized) && "diagnostic" in normalized -> {
                mutate { copy(diagnosticsExpanded = false, alertMessage = null) }; true
            }
            "engine" in normalized && ("temp" in normalized || "temperature" in normalized) -> {
                showEngineDetails(); true
            }
            "show" in normalized && ("navigation" in normalized || "route" in normalized) -> {
                mutate { copy(navigationExpanded = true, alertMessage = "ROUTE PANEL EXPANDED") }; true
            }
            ("hide" in normalized || "close" in normalized) && ("navigation" in normalized || "route" in normalized) -> {
                mutate { copy(navigationExpanded = false, alertMessage = null) }; true
            }
            "road status" in normalized -> {
                mutate { copy(alertMessage = "$roadStatus • $roadDetail") }; true
            }
            "clear alert" in normalized || "dismiss" in normalized -> {
                mutate { copy(alertMessage = null) }; true
            }
            else -> false
        }
    }

    private fun startObdLoop(address: String) {
        executor.execute {
            val obd = ObdManager(context.applicationContext)
            try {
                mutateFromWorker { copy(sourceLabel = "CONNECTING OBD…", alertMessage = "CONNECTING TO OBD ADAPTER") }
                obd.connect(address)
                mutateFromWorker { copy(obdConnected = true, sourceLabel = "OBD LIVE", alertMessage = "OBD CONNECTED") }
                while (running.get()) {
                    val snap = obd.snapshot()
                    val speed = snap.valueOrNull("speedMph")?.roundToInt()?.coerceAtLeast(0)
                    val rpm = snap.valueOrNull("rpm")?.roundToInt()?.coerceAtLeast(0)
                    val coolant = snap.valueOrNull("coolantF")?.roundToInt()
                    val fuel = snap.valueOrNull("fuelPercent")?.roundToInt()?.coerceIn(0, 100)
                    val load = snap.valueOrNull("engineLoadPercent")?.roundToInt()?.coerceIn(0, 100)
                    val volts = snap.valueOrNull("voltage")
                    val warning = coolant?.let { it >= 235 } == true
                    mutateFromWorker {
                        copy(
                            speedMph = speed,
                            rpm = rpm,
                            engineTempF = coolant,
                            fuelPercent = fuel,
                            engineLoadPercent = load,
                            batteryVolts = volts,
                            vehicleMoving = speed?.let { it >= 2 },
                            obdConnected = true,
                            sourceLabel = "OBD LIVE",
                            alertMessage = if (warning) "COOLANT ${coolant}°F" else alertMessage
                        )
                    }
                    Thread.sleep(900L)
                }
            } catch (e: Exception) {
                mutateFromWorker {
                    copy(
                        speedMph = null,
                        rpm = null,
                        engineTempF = null,
                        fuelPercent = null,
                        engineLoadPercent = null,
                        batteryVolts = null,
                        vehicleMoving = null,
                        obdConnected = false,
                        sourceLabel = "OBD OFFLINE",
                        alertMessage = "OBD UNAVAILABLE • ${e.message ?: "CONNECTION FAILED"}"
                    )
                }
            } finally {
                runCatching { obd.disconnect() }
            }
        }
    }

    private fun org.json.JSONObject.valueOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).takeUnless { it.isNaN() }
    }

    private fun hasBluetoothPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun mutate(block: DrivingHudState.() -> DrivingHudState) {
        state = state.block()
        publish(state)
    }

    @Synchronized
    private fun mutateFromWorker(block: DrivingHudState.() -> DrivingHudState) {
        state = state.block()
        publish(state)
    }

    private fun publish(value: DrivingHudState) {
        if (Looper.myLooper() == Looper.getMainLooper()) onState(value)
        else mainHandler.post { onState(value) }
    }
}
