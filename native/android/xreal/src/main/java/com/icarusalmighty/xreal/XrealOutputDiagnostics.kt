package com.icarusalmighty.xreal

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject

/**
 * Permission-free observations of Android logical displays, never hardware detection.
 * nebulaOS may place this ordinary 2D Activity in an OS-managed spatial window.
 * Neither a secondary display nor its absence verifies an XREAL glasses connection.
 */
object XrealOutputDiagnostics {
    fun capture(context: Context, actualDisplayId: Int?): XrealOutputSnapshot {
        return try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return XrealOutputSnapshot(
                    actualActivityDisplayId = actualDisplayId,
                    displayEnumerationSucceeded = false,
                    diagnosticError = "display_service_unavailable",
                )
            val displays = manager.displays.map { display ->
                XrealLogicalDisplay(
                    id = display.displayId,
                    name = display.name,
                    isDefault = display.displayId == Display.DEFAULT_DISPLAY,
                    isPresentation = display.flags and Display.FLAG_PRESENTATION != 0,
                    isPrivate = display.flags and Display.FLAG_PRIVATE != 0,
                    flags = display.flags,
                    state = display.state,
                    isValid = display.isValid,
                )
            }
            XrealOutputSnapshot(
                actualActivityDisplayId = actualDisplayId,
                accessibleLogicalDisplays = displays,
                displayEnumerationSucceeded = true,
            )
        } catch (_: Exception) {
            // A failed observation must not block launch or imply disconnection.
            XrealOutputSnapshot(
                actualActivityDisplayId = actualDisplayId,
                displayEnumerationSucceeded = false,
                diagnosticError = "display_enumeration_failed",
            )
        }
    }
}

data class XrealLogicalDisplay(
    val id: Int,
    val name: String,
    val isDefault: Boolean,
    val isPresentation: Boolean,
    val isPrivate: Boolean,
    val flags: Int,
    val state: Int,
    val isValid: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        // Physical/virtual type is not exposed by the supported public Display API.
        // Do not infer it from a display name, ID, resolution, or presentation flag.
        .put("type", "unknown")
        .put("isDefault", isDefault)
        .put("isPresentation", isPresentation)
        .put("isPrivate", isPrivate)
        .put("flags", flags)
        .put("state", state)
        .put("isValid", isValid)
}

data class XrealOutputSnapshot(
    val actualActivityDisplayId: Int? = null,
    val accessibleLogicalDisplays: List<XrealLogicalDisplay> = emptyList(),
    val displayEnumerationSucceeded: Boolean = false,
    val diagnosticError: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("outputPath", "os_managed_2d_window")
        .put("outputVerification", "unverified")
        .put("glassesConnectionVerified", false)
        .put("actualActivityDisplayId", actualActivityDisplayId ?: JSONObject.NULL)
        .put("accessibleLogicalDisplays", JSONArray().apply {
            accessibleLogicalDisplays.forEach { put(it.toJson()) }
        })
        .put("displayEnumerationSucceeded", displayEnumerationSucceeded)
        .apply { diagnosticError?.let { put("diagnosticError", it) } }
}
