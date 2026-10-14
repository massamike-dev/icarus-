package com.icarusalmighty.app

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Native is the authority for routing. Old installs and missing/corrupt preferences default local. */
object RuntimeMode {
    private val epoch = AtomicLong(0)
    fun generation(): Long = epoch.get()
    fun isCurrent(generation: Long): Boolean = epoch.get() == generation
    const val LOCAL = "local"
    const val CLOUD = "cloud"
    const val LOCAL_ORIGIN = "https://appassets.androidplatform.net"
    const val LOCAL_URL = "$LOCAL_ORIGIN/assets/web/index.html"

    fun current(context: Context): String = normalize(
        context.getSharedPreferences("icarus_runtime", Context.MODE_PRIVATE).getString("mode", LOCAL)
    )

    fun normalize(value: String?): String = if (value == CLOUD) CLOUD else LOCAL

    fun set(context: Context, mode: String) {
        require(mode == LOCAL || mode == CLOUD) { "invalid_runtime_mode" }
        require(mode != CLOUD || cloudOrigin() != null) { "cloud_not_configured" }
        epoch.incrementAndGet()
        context.getSharedPreferences("icarus_runtime", Context.MODE_PRIVATE).edit().putString("mode", mode).apply()
        // Invalidate any cloud reply or pending command that crossed the mode switch.
        VoiceSessionStore.clear(context)
        WakeWordService.cancelTurn()
    }

    fun cloudOrigin(): String? = TrustedWebPolicy.originRule(BuildConfig.ICARUS_WEB_URL)
        ?.takeUnless { it == LOCAL_ORIGIN }

    fun activeUrl(context: Context): String = if (current(context) == CLOUD) cloudOrigin().orEmpty() else LOCAL_URL

    fun status(context: Context): JSONObject = JSONObject()
        .put("mode", current(context))
        .put("localUrl", LOCAL_URL)
        .put("cloudUrl", cloudOrigin() ?: JSONObject.NULL)
        .put("cloudAvailable", cloudOrigin() != null)
        .put("localCoreFree", true)
        .put("automaticCloudFallback", false)
}
