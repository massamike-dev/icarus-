package com.icarusalmighty.xreal

import android.app.Activity
import android.content.Context
import android.content.Intent

class XrealModeController(private val context: Context) {

    fun isRuntimeAvailable(): Boolean =
        buildIntent(XrealMode.ASSISTANT, XrealHudState()).resolveActivity(context.packageManager) != null

    fun launch(mode: XrealMode, state: XrealHudState = XrealHudState()): XrealLaunchResult {
        val intent = buildIntent(mode, state)
        if (intent.resolveActivity(context.packageManager) == null) {
            return XrealLaunchResult.RuntimeUnavailable
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return XrealLaunchResult.Started
    }

    fun update(state: XrealHudState) {
        context.sendBroadcast(
            Intent(ACTION_UPDATE_XREAL)
                .setPackage(context.packageName)
                .putHudState(state)
        )
    }

    private fun buildIntent(mode: XrealMode, state: XrealHudState): Intent =
        Intent(ACTION_OPEN_XREAL)
            .setPackage(context.packageName)
            .putExtra(EXTRA_MODE, mode.name)
            .putHudState(state)

    private fun Intent.putHudState(state: XrealHudState): Intent = apply {
        putExtra(EXTRA_ASSISTANT_STATUS, state.assistantStatus)
        state.primaryText?.let { putExtra(EXTRA_PRIMARY_TEXT, it) }
        state.navigationInstruction?.let { putExtra(EXTRA_NAVIGATION_INSTRUCTION, it) }
        state.navigationDistance?.let { putExtra(EXTRA_NAVIGATION_DISTANCE, it) }
        state.speedMph?.let { putExtra(EXTRA_SPEED_MPH, it) }
        state.engineTempF?.let { putExtra(EXTRA_ENGINE_TEMP_F, it) }
    }

    companion object {
        const val ACTION_OPEN_XREAL = "com.icarusalmighty.action.OPEN_XREAL"
        const val ACTION_UPDATE_XREAL = "com.icarusalmighty.action.UPDATE_XREAL"
        const val EXTRA_MODE = "com.icarusalmighty.extra.XREAL_MODE"
        const val EXTRA_ASSISTANT_STATUS = "com.icarusalmighty.extra.ASSISTANT_STATUS"
        const val EXTRA_PRIMARY_TEXT = "com.icarusalmighty.extra.PRIMARY_TEXT"
        const val EXTRA_NAVIGATION_INSTRUCTION = "com.icarusalmighty.extra.NAVIGATION_INSTRUCTION"
        const val EXTRA_NAVIGATION_DISTANCE = "com.icarusalmighty.extra.NAVIGATION_DISTANCE"
        const val EXTRA_SPEED_MPH = "com.icarusalmighty.extra.SPEED_MPH"
        const val EXTRA_ENGINE_TEMP_F = "com.icarusalmighty.extra.ENGINE_TEMP_F"
    }
}

sealed interface XrealLaunchResult {
    data object Started : XrealLaunchResult
    data object RuntimeUnavailable : XrealLaunchResult
}
