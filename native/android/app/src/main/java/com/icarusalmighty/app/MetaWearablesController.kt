package com.icarusalmighty.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Crash-safe optional-integration boundary.
 *
 * MainActivity already routes `meta_*` actions here. 1.4.4 uses that stable dispatch path
 * as a compatibility transport for both independent wearable toggles so the large native
 * bridge does not need a risky rewrite. User-facing settings remain provider-specific.
 *
 * Meta DAT stays startup-guarded until device validation is complete. XREAL is constructed
 * lazily only after the XREAL toggle is enabled.
 */
class MetaWearablesController(
    private val activity: MainActivity,
    private val dispatch: (String) -> Unit,
) {
    private val integrationPreferences = IntegrationPreferences(activity)
    private var xrealController: XrealWearablesController? = null

    private fun flags(): IntegrationFlags = integrationPreferences.snapshot()

    private fun xreal(): XrealWearablesController? {
        if (!flags().xrealEnabled) return null
        return xrealController ?: XrealWearablesController(activity) { flags().xrealEnabled }
            .also { xrealController = it }
    }

    fun status(): JSONObject {
        val current = flags()
        val xrealStatus = if (current.xrealEnabled) xreal()?.status() else JSONObject()
            .put("provider", "xreal")
            .put("enabled", false)
            .put("runtimeAvailable", false)
            .put("fallback", "phone")

        return JSONObject()
            .put("available", false)
            .put("registrationState", if (current.metaEnabled) "SAFE_DISABLED" else "DISABLED_BY_USER")
            .put("deviceCount", 0)
            .put("sessionState", "STOPPED")
            .put("cameraState", "STOPPED")
            .put("displayState", "STOPPED")
            .put("developerMode", BuildConfig.DEBUG)
            .put("reason", if (current.metaEnabled) "meta_dat_startup_guard" else "integration_disabled")
            .put("capabilities", JSONArray())
            .put("integrations", JSONObject()
                .put("meta", JSONObject()
                    .put("enabled", current.metaEnabled)
                    .put("runtimeAvailable", false)
                    .put("reason", if (current.metaEnabled) "meta_dat_startup_guard" else "integration_disabled"))
                .put("xreal", xrealStatus))
            .put("drivingFallback", IntegrationPolicy.drivingSurface(
                current,
                xrealStatus.optBoolean("runtimeAvailable", false),
            ).name.lowercase())
    }

    fun execute(action: String, requestId: String?, args: JSONObject): String {
        return when (action) {
            "meta_integration_status" -> ok(requestId, status())
            "meta_integration_set" -> setIntegration(requestId, args)
            "meta_xreal_status" -> ok(requestId, xreal()?.status() ?: JSONObject()
                .put("provider", "xreal")
                .put("enabled", false)
                .put("runtimeAvailable", false)
                .put("fallback", "phone"))
            "meta_xreal_launch" -> {
                val controller = xreal()
                    ?: return error(requestId, "integration_disabled", "XREAL Integration is turned off in ICARUS settings.")
                ok(requestId, controller.launch(args.optString("mode", "driver")))
            }
            else -> executeMetaAction(action, requestId)
        }
    }

    private fun setIntegration(requestId: String?, args: JSONObject): String {
        val provider = args.optString("provider").trim().lowercase()
        val enabled = args.optBoolean("enabled", false)
        val updated = try {
            integrationPreferences.set(provider, enabled)
        } catch (_: IllegalArgumentException) {
            return error(requestId, "unsupported_integration")
        }
        if (provider == "xreal" && !enabled) xrealController = null
        return ok(requestId, JSONObject()
            .put("provider", provider)
            .put("enabled", enabled)
            .put("metaEnabled", updated.metaEnabled)
            .put("xrealEnabled", updated.xrealEnabled)
            .put("fallback", "phone"))
    }

    private fun executeMetaAction(action: String, requestId: String?): String {
        if (!flags().metaEnabled) {
            return error(requestId, "integration_disabled", "Meta Integration is turned off in ICARUS settings.")
        }
        return error(
            requestId,
            "meta_temporarily_guarded",
            "Meta Integration is enabled, but the DAT runtime remains crash-guarded in ICARUS 1.4.4 until device validation is complete.",
        )
    }

    private fun ok(requestId: String?, data: JSONObject): String = JSONObject()
        .put("ok", true)
        .put("requestId", requestId ?: JSONObject.NULL)
        .put("data", data)
        .toString()

    private fun error(requestId: String?, code: String, message: String? = null): String = JSONObject()
        .put("ok", false)
        .put("requestId", requestId ?: JSONObject.NULL)
        .put("error", code)
        .apply { if (!message.isNullOrBlank()) put("message", message) }
        .toString()

    fun close() {
        xrealController = null
    }
}
