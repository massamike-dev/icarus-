package com.icarusalmighty.xreal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper

class XrealModeController(private val context: Context) {

    fun isRuntimeAvailable(): Boolean = runCatching {
        buildIntent(XrealMode.ASSISTANT, XrealHudState()).resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    fun isOpen(): Boolean = XrealHudActivity.session.activityOpen

    fun isVehicleMode(): Boolean = XrealHudActivity.session.activeMode == XrealMode.VEHICLE

    fun isLaunchPending(): Boolean = XrealHudActivity.session.launchPending

    /** Caller retains this callback; the process-wide session holds only a weak reference. */
    fun setOnClosedListener(listener: (() -> Unit)?) = XrealHudActivity.session.setOnClosedListener(listener)

    /** Logical output information only; it cannot identify or verify physical glasses. */
    fun outputDiagnostics(): XrealOutputSnapshot =
        XrealOutputDiagnostics.capture(context, XrealHudActivity.session.actualDisplayId)

    fun launch(mode: XrealMode, state: XrealHudState = XrealHudState()): XrealLaunchResult {
        if (Looper.myLooper() != Looper.getMainLooper()) return XrealLaunchResult.Failed("main_thread_required")
        if (context is Activity && (context.isFinishing || context.isDestroyed)) {
            return XrealLaunchResult.Failed("activity_unavailable")
        }
        val intent = buildIntent(mode, state)
        if (!isRuntimeAvailable()) {
            return XrealLaunchResult.RuntimeUnavailable
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        XrealHudActivity.session.launchRequested()
        return try {
            context.startActivity(intent)
            XrealLaunchResult.Started
        } catch (_: Exception) {
            XrealHudActivity.session.launchFailed()
            XrealLaunchResult.Failed("xreal_launch_failed")
        }
    }

    /** Returns whether a live or pending activity was actually asked to finish. */
    fun close(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return XrealHudActivity.requestClose()
    }

    fun update(state: XrealHudState): Boolean {
        if (!isOpen()) return false
        context.sendBroadcast(
            Intent(ACTION_UPDATE_XREAL)
                .setPackage(context.packageName)
                .putHudState(state)
        )
        return true
    }

    private fun buildIntent(mode: XrealMode, state: XrealHudState): Intent =
        Intent(context, XrealHudActivity::class.java)
            .setAction(ACTION_OPEN_XREAL)
            .putExtra(EXTRA_MODE, mode.name)
            .putHudState(state)

    companion object {
        const val ACTION_OPEN_XREAL = "com.icarusalmighty.action.OPEN_XREAL"
        const val ACTION_UPDATE_XREAL = "com.icarusalmighty.action.UPDATE_XREAL"
        const val EXTRA_MODE = "com.icarusalmighty.extra.XREAL_MODE"
        const val EXTRA_ASSISTANT_STATUS = "com.icarusalmighty.extra.ASSISTANT_STATUS"
        const val EXTRA_PRIMARY_TEXT = "com.icarusalmighty.extra.PRIMARY_TEXT"
        const val EXTRA_NAVIGATION_INSTRUCTION = "com.icarusalmighty.extra.NAVIGATION_INSTRUCTION"
        const val EXTRA_NAVIGATION_DISTANCE = "com.icarusalmighty.extra.NAVIGATION_DISTANCE"
        const val EXTRA_ETA = "com.icarusalmighty.extra.ETA"
        const val EXTRA_HEADING = "com.icarusalmighty.extra.HEADING"
        const val EXTRA_SPEED_MPH = "com.icarusalmighty.extra.SPEED_MPH"
        const val EXTRA_RPM = "com.icarusalmighty.extra.RPM"
        const val EXTRA_ENGINE_TEMP_F = "com.icarusalmighty.extra.ENGINE_TEMP_F"
        const val EXTRA_BATTERY_PERCENT = "com.icarusalmighty.extra.BATTERY_PERCENT"
        const val EXTRA_ALERT_TEXT = "com.icarusalmighty.extra.ALERT_TEXT"
        const val EXTRA_FUEL_PERCENT = "com.icarusalmighty.extra.FUEL_PERCENT"
        const val EXTRA_ENGINE_LOAD_PERCENT = "com.icarusalmighty.extra.ENGINE_LOAD_PERCENT"
        const val EXTRA_BATTERY_VOLTS = "com.icarusalmighty.extra.BATTERY_VOLTS"
        const val EXTRA_NEXT_TURN_DISTANCE_FT = "com.icarusalmighty.extra.NEXT_TURN_DISTANCE_FT"
        const val EXTRA_NEXT_ROAD = "com.icarusalmighty.extra.NEXT_ROAD"
        const val EXTRA_NAVIGATION_SOURCE = "com.icarusalmighty.extra.NAVIGATION_SOURCE"
        const val EXTRA_NAVIGATION_ACCESS_GRANTED = "com.icarusalmighty.extra.NAVIGATION_ACCESS_GRANTED"
        const val EXTRA_ROAD_STATUS = "com.icarusalmighty.extra.ROAD_STATUS"
        const val EXTRA_ROAD_DETAIL = "com.icarusalmighty.extra.ROAD_DETAIL"
        const val EXTRA_DIAGNOSTICS_EXPANDED = "com.icarusalmighty.extra.DIAGNOSTICS_EXPANDED"
        const val EXTRA_NAVIGATION_EXPANDED = "com.icarusalmighty.extra.NAVIGATION_EXPANDED"
        const val EXTRA_LISTENING = "com.icarusalmighty.extra.LISTENING"
        const val EXTRA_VEHICLE_MOVING = "com.icarusalmighty.extra.VEHICLE_MOVING"
        const val EXTRA_OBD_CONNECTED = "com.icarusalmighty.extra.OBD_CONNECTED"
        const val EXTRA_VEHICLE_SNAPSHOT_AT_ELAPSED_REALTIME_MS = "com.icarusalmighty.extra.VEHICLE_SNAPSHOT_AT_ELAPSED_REALTIME_MS"
        const val EXTRA_VEHICLE_SNAPSHOT_VALID_UNTIL_ELAPSED_REALTIME_MS = "com.icarusalmighty.extra.VEHICLE_SNAPSHOT_VALID_UNTIL_ELAPSED_REALTIME_MS"
        const val EXTRA_SPEED_VALID_UNTIL_ELAPSED_REALTIME_MS = "com.icarusalmighty.extra.SPEED_VALID_UNTIL_ELAPSED_REALTIME_MS"
        const val EXTRA_SOURCE_LABEL = "com.icarusalmighty.extra.SOURCE_LABEL"
    }
}

sealed interface XrealLaunchResult {
    data object Started : XrealLaunchResult
    data object RuntimeUnavailable : XrealLaunchResult
    data class Failed(val code: String) : XrealLaunchResult
}
