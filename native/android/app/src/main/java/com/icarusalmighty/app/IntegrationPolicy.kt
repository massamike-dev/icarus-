package com.icarusalmighty.app

/**
 * Pure policy for optional ICARUS integrations. Keep this free of Android APIs so the
 * fallback rules can be unit-tested without a device/emulator.
 */
data class IntegrationFlags(
    val metaEnabled: Boolean = false,
    val xrealEnabled: Boolean = false,
)

enum class DrivingSurface {
    PHONE,
    XREAL,
}

object IntegrationPolicy {
    val coreCapabilities: Set<String> = linkedSetOf(
        "wake_word",
        "bluetooth_audio",
        "list_bluetooth",
        "open_app",
        "toggle_flashlight",
        "set_volume",
        "set_brightness",
        "make_call",
        "send_sms",
        "take_photo",
        "set_alarm",
        "set_timer",
        "navigate_to",
        "get_location",
        "get_battery",
        "obd_list",
        "obd_connect",
        "obd_snapshot",
        "obd_disconnect",
        "find_videos",
        "compose_video_montage",
        "native_tts",
        "speak_text",
        "stop_speaking",
        "check_update",
        "local_model_status",
        "download_local_model",
        "delete_local_model",
        "local_chat",
        "interpret_command",
        "integration_status",
        "set_integration_enabled",
    )

    private val metaCapabilities: Set<String> = linkedSetOf(
        "meta_status",
        "meta_register",
        "meta_unregister",
        "meta_session_start",
        "meta_session_stop",
        "meta_capture_photo",
        "meta_display",
        "meta_audio_test",
        "meta_mock_enable",
        "meta_mock_disable",
    )

    private val xrealCapabilities: Set<String> = linkedSetOf(
        "xreal_status",
        "open_xreal_hud",
        "update_xreal_hud",
    )

    fun capabilities(flags: IntegrationFlags): List<String> = buildList {
        addAll(coreCapabilities)
        if (flags.metaEnabled) addAll(metaCapabilities)
        if (flags.xrealEnabled) addAll(xrealCapabilities)
    }

    fun shouldInitializeMeta(flags: IntegrationFlags): Boolean = flags.metaEnabled

    fun shouldInitializeXreal(flags: IntegrationFlags): Boolean = flags.xrealEnabled

    fun drivingSurface(flags: IntegrationFlags, xrealRuntimeAvailable: Boolean): DrivingSurface =
        if (flags.xrealEnabled && xrealRuntimeAvailable) DrivingSurface.XREAL else DrivingSurface.PHONE
}
