package com.icarusalmighty.app.driving

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
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
    private val locationManager = context.getSystemService(LocationManager::class.java)
    @Volatile private var state = DrivingHudState()
    @Volatile private var gpsSpeedMph: Int? = null
    @Volatile private var gpsHeading: String? = null
    @Volatile private var gpsMoving: Boolean? = null
    private var navigationSubscription: AutoCloseable? = null
    private var locationStarted = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = applyLocation(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val locationPermissionRetry = object : Runnable {
        override fun run() {
            if (!running.get() || locationStarted) return
            startLocationUpdates()
            if (!locationStarted && running.get()) mainHandler.postDelayed(this, 5000L)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        navigationSubscription = NavigationFeed.subscribe(::applyNavigation)
        refreshNavigationAccess()
        startLocationUpdates()
        if (!locationStarted) mainHandler.postDelayed(locationPermissionRetry, 5000L)
        when {
            obdAddress.isNullOrBlank() -> mutate {
                copy(
                    obdConnected = false,
                    sourceLabel = if (hasLocationPermission()) "PHONE GPS WAITING" else "LOCATION PERMISSION REQUIRED",
                    alertMessage = if (hasLocationPermission()) null else "ALLOW LOCATION FOR SPEED & HEADING"
                )
            }
            !hasBluetoothPermission() -> mutate {
                copy(
                    obdConnected = false,
                    sourceLabel = if (hasLocationPermission()) "PHONE GPS" else "LOCATION PERMISSION REQUIRED",
                    alertMessage = "BLUETOOTH PERMISSION IS REQUIRED FOR OBD DATA"
                )
            }
            else -> startObdLoop(obdAddress)
        }
    }

    fun stop() {
        running.set(false)
        navigationSubscription?.close()
        navigationSubscription = null
        if (hasLocationPermission()) runCatching { locationManager?.removeUpdates(locationListener) }
        locationStarted = false
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    fun toggleDiagnostics() = mutate { copy(diagnosticsExpanded = !diagnosticsExpanded) }
    fun toggleNavigation() = mutate { copy(navigationExpanded = !navigationExpanded) }
    fun setListening(value: Boolean) = mutate { copy(listening = value) }

    fun refreshNavigationAccess() = mutate {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        copy(
            navigationAccessGranted = granted,
            navigationInstruction = navigationInstruction.takeIf { granted },
            navigationDistance = navigationDistance.takeIf { granted },
            navigationSource = navigationSource.takeIf { granted },
            nextRoad = nextRoad.takeIf { granted },
            roadStatus = when { granted && navigationInstruction != null -> "ROUTE ACTIVE"; granted -> "ROUTE STANDBY"; else -> "NAVIGATION ACCESS OFF" },
            roadDetail = when { granted && navigationInstruction != null -> navigationSource ?: "LIVE NAVIGATION"; granted -> "START GOOGLE MAPS OR WAZE NAVIGATION"; else -> "ENABLE IN ICARUS BEFORE DRIVING" },
        )
    }

    fun showEngineDetails() = mutate {
        val temp = engineTempF?.let { "$it°F" } ?: "—"
        val load = engineLoadPercent?.let { "$it%" } ?: "—"
        val volts = batteryVolts?.let { "%.1fV".format(it) } ?: "—"
        copy(diagnosticsExpanded = true, alertMessage = "ENGINE $temp  •  LOAD $load  •  $volts")
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

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (!running.get() || locationStarted || !hasLocationPermission() || locationManager == null) return
        var registered = false
        val providers = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                add(LocationManager.GPS_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
        }.distinct()
        for (provider in providers) {
            if (!runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)) continue
            runCatching {
                locationManager.requestLocationUpdates(provider, 750L, 1f, locationListener, Looper.getMainLooper())
                locationManager.getLastKnownLocation(provider)?.let(::applyLocation)
                registered = true
            }
        }
        locationStarted = registered
        if (registered) mutate {
            copy(
                sourceLabel = if (obdConnected) sourceLabel else "PHONE GPS",
                alertMessage = alertMessage?.takeUnless { it.contains("LOCATION", ignoreCase = true) }
            )
        }
    }

    private fun applyLocation(location: Location) {
        val speed = location.takeIf { it.hasSpeed() }?.speed?.let(DrivingTelemetryMath::speedMph)
        val heading = location.takeIf { it.hasBearing() }?.bearing?.let(DrivingTelemetryMath::cardinalHeading)
        gpsSpeedMph = speed ?: gpsSpeedMph
        gpsHeading = heading ?: gpsHeading
        gpsMoving = gpsSpeedMph?.let { it >= 2 }
        mutate {
            copy(
                speedMph = if (obdConnected) speedMph else gpsSpeedMph,
                heading = gpsHeading,
                vehicleMoving = if (obdConnected) vehicleMoving else gpsMoving,
                sourceLabel = if (obdConnected) sourceLabel else "PHONE GPS",
                alertMessage = alertMessage?.takeUnless { it.contains("LOCATION", ignoreCase = true) }
            )
        }
    }

    private fun startObdLoop(address: String) {
        executor.execute {
            val obd = ObdManager(context.applicationContext)
            try {
                mutateFromWorker { copy(sourceLabel = "CONNECTING OBD…", alertMessage = "CONNECTING TO OBD ADAPTER") }
                obd.connect(address)
                mutateFromWorker { copy(obdConnected = true, sourceLabel = "OBD + GPS LIVE", alertMessage = "OBD CONNECTED", heading = gpsHeading) }
                while (running.get()) {
                    val snap = obd.snapshot()
                    val obdSpeed = snap.valueOrNull("speedMph")?.roundToInt()?.coerceAtLeast(0)
                    val speed = obdSpeed ?: gpsSpeedMph
                    val rpm = snap.valueOrNull("rpm")?.roundToInt()?.coerceAtLeast(0)
                    val coolant = snap.valueOrNull("coolantF")?.roundToInt()
                    val fuel = snap.valueOrNull("fuelPercent")?.roundToInt()?.coerceIn(0, 100)
                    val load = snap.valueOrNull("engineLoadPercent")?.roundToInt()?.coerceIn(0, 100)
                    val volts = snap.valueOrNull("voltage")
                    val warning = coolant?.let { it >= 235 } == true
                    mutateFromWorker {
                        copy(
                            speedMph = speed,
                            heading = gpsHeading,
                            rpm = rpm,
                            engineTempF = coolant,
                            fuelPercent = fuel,
                            engineLoadPercent = load,
                            batteryVolts = volts,
                            vehicleMoving = speed?.let { it >= 2 },
                            obdConnected = true,
                            sourceLabel = "OBD + GPS LIVE",
                            alertMessage = if (warning) "COOLANT ${coolant}°F" else alertMessage
                        )
                    }
                    Thread.sleep(900L)
                }
            } catch (e: Exception) {
                mutateFromWorker {
                    copy(
                        speedMph = gpsSpeedMph,
                        heading = gpsHeading,
                        rpm = null,
                        engineTempF = null,
                        fuelPercent = null,
                        engineLoadPercent = null,
                        batteryVolts = null,
                        vehicleMoving = gpsMoving,
                        obdConnected = false,
                        sourceLabel = if (gpsSpeedMph != null || gpsHeading != null) "PHONE GPS" else "OBD OFFLINE",
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

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun applyNavigation(snapshot: NavigationSnapshot) = mutate {
        copy(
            navigationInstruction = snapshot.instruction,
            navigationDistance = snapshot.distance,
            navigationSource = snapshot.source,
            nextRoad = snapshot.instruction,
            roadStatus = if (snapshot.active) "ROUTE ACTIVE" else if (navigationAccessGranted) "ROUTE STANDBY" else "NAVIGATION ACCESS OFF",
            roadDetail = if (snapshot.active) snapshot.source ?: "LIVE NAVIGATION" else if (navigationAccessGranted) "START GOOGLE MAPS OR WAZE NAVIGATION" else "ENABLE IN ICARUS BEFORE DRIVING",
            navigationExpanded = snapshot.active || navigationExpanded,
        )
    }

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
