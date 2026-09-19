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

    fun isLaunchPending(): Boolean = XrealHudActivity.session.launchPending

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

    private fun Intent.putHudState(state: XrealHudState): Intent = apply {
        putExtra(EXTRA_ASSISTANT_STATUS, state.assistantStatus)
        state.primaryText?.let { putExtra(EXTRA_PRIMARY_TEXT, it) }
        state.navigationInstruction?.let { putExtra(EXTRA_NAVIGATION_INSTRUCTION, it) }
        state.navigationDistance?.let { putExtra(EXTRA_NAVIGATION_DISTANCE, it) }
        state.eta?.let { putExtra(EXTRA_ETA, it) }
        state.heading?.let { putExtra(EXTRA_HEADING, it) }
        state.speedMph?.let { putExtra(EXTRA_SPEED_MPH, it) }
        state.rpm?.let { putExtra(EXTRA_RPM, it) }
        state.engineTempF?.let { putExtra(EXTRA_ENGINE_TEMP_F, it) }
        state.batteryPercent?.let { putExtra(EXTRA_BATTERY_PERCENT, it) }
        state.alertText?.let { putExtra(EXTRA_ALERT_TEXT, it) }
    }

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
    }
}

sealed interface XrealLaunchResult {
    data object Started : XrealLaunchResult
    data object RuntimeUnavailable : XrealLaunchResult
    data class Failed(val code: String) : XrealLaunchResult
}
