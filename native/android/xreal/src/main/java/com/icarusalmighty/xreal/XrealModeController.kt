package com.icarusalmighty.xreal

import android.app.Activity
import android.content.Context
import android.content.Intent

class XrealModeController(private val context: Context) {

    fun isRuntimeAvailable(): Boolean =
        buildIntent(XrealMode.ASSISTANT).resolveActivity(context.packageManager) != null

    fun launch(mode: XrealMode): XrealLaunchResult {
        val intent = buildIntent(mode)
        if (intent.resolveActivity(context.packageManager) == null) {
            return XrealLaunchResult.RuntimeUnavailable
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return XrealLaunchResult.Started
    }

    private fun buildIntent(mode: XrealMode): Intent =
        Intent(ACTION_OPEN_XREAL)
            .setPackage(context.packageName)
            .putExtra(EXTRA_MODE, mode.name)

    companion object {
        const val ACTION_OPEN_XREAL = "com.icarusalmighty.action.OPEN_XREAL"
        const val EXTRA_MODE = "com.icarusalmighty.extra.XREAL_MODE"
    }
}

sealed interface XrealLaunchResult {
    data object Started : XrealLaunchResult
    data object RuntimeUnavailable : XrealLaunchResult
}
