package com.icarusalmighty.app.driving

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val speedFreshness = DrivingSpeedFreshness()
    private var gpsHeading: String? = null
    private var gpsHeadingObservedAtMs: Long? = null
    private var obdSnapshotStartedAtMs: Long? = null
    private var obdTransportConnected = false
    private var navigationSubscription: AutoCloseable? = null
    private var registeredProviders = emptySet<String>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = applyLocation(location)
        override fun onProviderEnabled(provider: String) = runOnMain {
            refreshLocationRegistration()
            mutate { this }
        }
        override fun onProviderDisabled(provider: String) = runOnMain {
            clearGpsEvidence()
            refreshLocationRegistration()
            mutate { this }
        }
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = runOnMain {
            if (status != LocationProvider.AVAILABLE) {
                clearGpsEvidence()
                mutate { this }
            }
        }
    }

    // Independent of location callbacks and the potentially blocked Bluetooth worker.
    private val freshnessWatchdog = object : Runnable {
        override fun run() {
            if (!running.get()) return
            refreshLocationRegistration()
            mutate { this }
            mainHandler.postDelayed(this, DrivingSpeedFreshness.EXPIRY_CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        navigationSubscription = NavigationFeed.subscribe(::applyNavigation)
        refreshNavigationAccess()
        runOnMain {
            startLocationUpdates()
            mainHandler.post(freshnessWatchdog)
        }
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
        stopLocationUpdates()
        clearGpsEvidence()
        speedFreshness.clearObd(SystemClock.elapsedRealtime())
        obdSnapshotStartedAtMs = null
        obdTransportConnected = false
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    // XREAL's bounded transport heartbeat may resend a snapshot, but never renew sensor age.
    fun refreshSnapshot() = runOnMain {
        refreshLocationRegistration()
        mutate(publishUnchanged = true) { this }
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

    private fun enabledLocationProviders(): Set<String> {
        if (!hasLocationPermission() || locationManager == null) return emptySet()
        return buildSet {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                add(LocationManager.GPS_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
        }.filterTo(mutableSetOf()) { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    private fun refreshLocationRegistration() {
        if (registeredProviders != enabledLocationProviders()) {
            clearGpsEvidence()
            stopLocationUpdates()
            startLocationUpdates()
        }
        if (!hasLocationPermission() || registeredProviders.isEmpty()) clearGpsEvidence()
    }

    @Suppress("MissingPermission")
    private fun stopLocationUpdates() {
        // Revocation may make removal throw. It must still discard all local evidence.
        runCatching { locationManager?.removeUpdates(locationListener) }
        registeredProviders = emptySet()
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (!running.get() || !hasLocationPermission() || locationManager == null) return
        val registered = mutableSetOf<String>()
        for (provider in enabledLocationProviders()) {
            runCatching {
                // Stationary fixes must also arrive; a distance filter can suppress zero-speed refreshes.
                locationManager.requestLocationUpdates(provider, 750L, 0f, locationListener, Looper.getMainLooper())
                registered += provider
                // applyLocation uses the measurement's elapsed time, never cache retrieval time.
                locationManager.getLastKnownLocation(provider)?.let(::applyLocation)
            }
        }
        registeredProviders = registered
    }

    private fun clearGpsEvidence() {
        speedFreshness.clearGps(SystemClock.elapsedRealtime())
        gpsHeading = null
        gpsHeadingObservedAtMs = null
    }

    private fun applyLocation(location: Location) = runOnMain {
        if (!hasLocationPermission() || location.provider !in enabledLocationProviders()) {
            clearGpsEvidence()
            mutate { this }
            return@runOnMain
        }
        val now = SystemClock.elapsedRealtime()
        val observedAt = location.elapsedRealtimeNanos / 1_000_000L
        val speed = location.takeIf { it.hasSpeed() }?.speed?.toDouble()?.times(2.2369363)
        speedFreshness.recordGps(speed, observedAt, now, location.provider ?: return@runOnMain)
        if (!DrivingSpeedFreshness.isFresh(observedAt, now)) {
            gpsHeading = null
            gpsHeadingObservedAtMs = null
        } else if (observedAt >= (gpsHeadingObservedAtMs ?: 0L)) {
            gpsHeading = location.takeIf { it.hasBearing() }?.bearing?.takeIf { it.isFinite() }
                ?.let(DrivingTelemetryMath::cardinalHeading)
            gpsHeadingObservedAtMs = observedAt
        }
        mutate {
            copy(alertMessage = alertMessage?.takeUnless { it.contains("LOCATION", ignoreCase = true) })
        }
    }

    private fun startObdLoop(address: String) {
        executor.execute {
            val obd = ObdManager(context.applicationContext)
            try {
                mutateFromWorker { copy(sourceLabel = "CONNECTING OBD…", alertMessage = "CONNECTING TO OBD ADAPTER") }
                obd.connect(address)
                runOnMain {
                    obdTransportConnected = true
                    // Connection alone supplies no speed/parked evidence.
                    mutate { copy(alertMessage = "OBD CONNECTED • WAITING FOR TELEMETRY") }
                }
                while (running.get()) {
                    if (!hasBluetoothPermission()) error("BLUETOOTH PERMISSION REQUIRED")
                    // snapshot() reads seven PIDs sequentially and uses wall time internally. Its
                    // completion timestamp is NOT the speed measurement time. Starting earlier is
                    // conservative: a slow/stuck batch cannot make its early speed read fresh again.
                    val snapshotStartedAt = SystemClock.elapsedRealtime()
                    val snap = obd.snapshot()
                    val obdSpeed = snap.valueOrNull("speedMph")
                    val rpm = snap.valueOrNull("rpm")?.roundToInt()?.coerceAtLeast(0)
                    val coolant = snap.valueOrNull("coolantF")?.roundToInt()
                    val fuel = snap.valueOrNull("fuelPercent")?.roundToInt()?.coerceIn(0, 100)
                    val load = snap.valueOrNull("engineLoadPercent")?.roundToInt()?.coerceIn(0, 100)
                    val volts = snap.valueOrNull("voltage")
                    runOnMain {
                        if (hasBluetoothPermission()) {
                            speedFreshness.recordObd(obdSpeed, snapshotStartedAt, SystemClock.elapsedRealtime())
                            obdSnapshotStartedAtMs = snapshotStartedAt
                            obdTransportConnected = true
                        } else {
                            clearObdEvidence()
                        }
                        mutate {
                            copy(
                                rpm = rpm,
                                engineTempF = coolant,
                                fuelPercent = fuel,
                                engineLoadPercent = load,
                                batteryVolts = volts,
                                alertMessage = if (coolant != null && coolant >= 235 &&
                                    DrivingSpeedFreshness.isFresh(snapshotStartedAt, SystemClock.elapsedRealtime())) {
                                    "COOLANT ${coolant}°F"
                                } else alertMessage,
                            )
                        }
                    }
                    Thread.sleep(900L)
                }
            } catch (e: Exception) {
                runOnMain {
                    clearObdEvidence()
                    mutate { copy(alertMessage = "OBD UNAVAILABLE • ${e.message ?: "CONNECTION FAILED"}") }
                }
            } finally {
                runCatching { obd.disconnect() }
            }
        }
    }

    private fun clearObdEvidence() {
        speedFreshness.clearObd(SystemClock.elapsedRealtime())
        obdSnapshotStartedAtMs = null
        obdTransportConnected = false
    }

    private fun org.json.JSONObject.valueOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).takeIf { it.isFinite() }
    }

    private fun withFreshTelemetry(value: DrivingHudState): DrivingHudState {
        val now = SystemClock.elapsedRealtime()
        if (!hasLocationPermission()) clearGpsEvidence()
        if (!hasBluetoothPermission()) clearObdEvidence()
        val reading = speedFreshness.current(now)
        val obdFresh = obdTransportConnected && DrivingSpeedFreshness.isFresh(obdSnapshotStartedAtMs, now)
        val heading = gpsHeading.takeIf { DrivingSpeedFreshness.isFresh(gpsHeadingObservedAtMs, now) }
        return value.copy(
            speedMph = reading.speedMph,
            vehicleMoving = reading.vehicleMoving,
            speedValidUntilElapsedRealtimeMs = reading.validUntilElapsedRealtimeMs,
            heading = heading,
            obdConnected = obdFresh,
            rpm = value.rpm.takeIf { obdFresh },
            engineTempF = value.engineTempF.takeIf { obdFresh },
            fuelPercent = value.fuelPercent.takeIf { obdFresh },
            engineLoadPercent = value.engineLoadPercent.takeIf { obdFresh },
            batteryVolts = value.batteryVolts.takeIf { obdFresh },
            sourceLabel = when {
                obdFresh && reading.source == DrivingSpeedFreshness.Source.OBD -> "OBD LIVE"
                obdFresh && reading.source == DrivingSpeedFreshness.Source.GPS -> "OBD + PHONE GPS"
                obdFresh -> "OBD LIVE • SPEED UNKNOWN"
                reading.source == DrivingSpeedFreshness.Source.GPS -> "PHONE GPS"
                !hasLocationPermission() -> "LOCATION PERMISSION REQUIRED"
                obdTransportConnected -> "OBD STALE • SPEED UNKNOWN"
                value.sourceLabel == "CONNECTING OBD…" -> value.sourceLabel
                else -> "SPEED UNKNOWN"
            },
        )
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

    // Serialize input updates, policy decisions, and delivery on the main thread. A worker must
    // never queue a precomputed state that could overwrite a newer expiry/permission-loss state.
    private fun mutate(
        publishUnchanged: Boolean = false,
        block: DrivingHudState.() -> DrivingHudState,
    ) = runOnMain {
        val next = withFreshTelemetry(withFreshTelemetry(state).block())
        if (next != state || publishUnchanged) {
            state = next
            onState(next)
        }
    }

    private fun mutateFromWorker(block: DrivingHudState.() -> DrivingHudState) = mutate(block = block)

    private fun runOnMain(block: () -> Unit) {
        if (!running.get()) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { if (running.get()) block() }
    }
}
