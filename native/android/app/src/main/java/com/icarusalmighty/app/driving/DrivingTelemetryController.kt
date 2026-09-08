package com.icarusalmighty.app.driving

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.icarusalmighty.app.ObdManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.sin

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
        publish(state)
        if (!obdAddress.isNullOrBlank() && hasBluetoothPermission()) {
            startObdLoop(obdAddress)
        } else {
            startSimulationLoop()
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
        copy(
            diagnosticsExpanded = true,
            alertMessage = "ENGINE ${engineTempF}°F  •  LOAD ${engineLoadPercent}%  •  ${"%.1f".format(batteryVolts)}V"
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
                mutate { copy(navigationExpanded = true, alertMessage = "ROUTE GUIDANCE EXPANDED") }; true
            }
            ("hide" in normalized || "close" in normalized) && ("navigation" in normalized || "route" in normalized) -> {
                mutate { copy(navigationExpanded = false, alertMessage = null) }; true
            }
            "road status" in normalized || "road clear" in normalized -> {
                mutate { copy(alertMessage = "$roadStatus • $roadDetail") }; true
            }
            "clear alert" in normalized || "dismiss" in normalized -> {
                mutate { copy(alertMessage = null) }; true
            }
            else -> false
        }
    }

    private fun startSimulationLoop() {
        val started = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                if (!running.get()) return
                val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                val speed = (43 + sin(seconds / 2.1) * 3.5).roundToInt().coerceAtLeast(0)
                val rpm = (1850 + sin(seconds * 1.4) * 210).roundToInt().coerceAtLeast(700)
                val temp = (198 + sin(seconds / 5.0) * 2.2).roundToInt()
                val load = (34 + sin(seconds / 1.8) * 8).roundToInt().coerceIn(5, 95)
                val turn = (450 - ((seconds * 5).roundToInt() % 380)).coerceAtLeast(70)
                state = state.copy(
                    speedMph = speed,
                    rpm = rpm,
                    engineTempF = temp,
                    engineLoadPercent = load,
                    nextTurnDistanceFt = turn,
                    parked = speed < 2,
                    obdConnected = false,
                    sourceLabel = "SIMULATION"
                )
                publish(state)
                mainHandler.postDelayed(this, 120L)
            }
        }
        mainHandler.post(tick)
    }

    private fun startObdLoop(address: String) {
        executor.execute {
            val obd = ObdManager(context.applicationContext)
            try {
                obd.connect(address)
                mutateFromWorker { copy(obdConnected = true, sourceLabel = "OBD LIVE", alertMessage = "OBD CONNECTED") }
                while (running.get()) {
                    val snap = obd.snapshot()
                    val speed = snap.optDouble("speedMph", state.speedMph.toDouble()).roundToInt().coerceAtLeast(0)
                    val rpm = snap.optDouble("rpm", state.rpm.toDouble()).roundToInt().coerceAtLeast(0)
                    val coolant = snap.optDouble("coolantF", state.engineTempF.toDouble()).roundToInt()
                    val fuel = snap.optDouble("fuelPercent", state.fuelPercent.toDouble()).roundToInt().coerceIn(0, 100)
                    val load = snap.optDouble("engineLoadPercent", state.engineLoadPercent.toDouble()).roundToInt().coerceIn(0, 100)
                    val volts = snap.optDouble("voltage", state.batteryVolts)
                    val warning = coolant >= 235
                    mutateFromWorker {
                        copy(
                            speedMph = speed,
                            rpm = rpm,
                            engineTempF = coolant,
                            fuelPercent = fuel,
                            engineLoadPercent = load,
                            batteryVolts = volts,
                            parked = speed < 2,
                            obdConnected = true,
                            sourceLabel = "OBD LIVE",
                            roadStatus = if (warning) "ENGINE TEMP HIGH" else "ROAD CLEAR",
                            roadDetail = if (warning) "REDUCE LOAD AND CHECK COOLING SYSTEM" else "GOOD CONDITIONS AHEAD",
                            alertMessage = if (warning) "COOLANT ${coolant}°F" else alertMessage
                        )
                    }
                    Thread.sleep(900L)
                }
            } catch (e: Exception) {
                mutateFromWorker {
                    copy(
                        obdConnected = false,
                        sourceLabel = "SIMULATION",
                        alertMessage = "OBD UNAVAILABLE • ${e.message ?: "CONNECTION FAILED"}"
                    )
                }
                mainHandler.post { if (running.get()) startSimulationLoop() }
            } finally {
                runCatching { obd.disconnect() }
            }
        }
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
