package com.icarusalmighty.app

/** Pure allow-list and confirmation policy for locally interpreted Android commands. */
object NativeCommandPolicy {
    val allowedActions: Set<String> = linkedSetOf(
        "open_app",
        "set_alarm",
        "set_timer",
        "toggle_flashlight",
        "set_volume",
        "set_brightness",
        "navigate_to",
        "get_battery",
        "take_photo",
        "make_call",
        "send_sms",
        "find_videos",
        "compose_video_montage",
        "list_bluetooth",
        "bluetooth_status",
        "wake_word",
        "obd_list",
        "obd_connect",
        "obd_snapshot",
        "obd_disconnect",
        "unknown",
    )

    private val sensitiveActions: Set<String> = setOf(
        "set_brightness",
        "navigate_to",
        "take_photo",
        "make_call",
        "send_sms",
        "compose_video_montage",
    )

    fun normalizeAction(requested: String): String =
        requested.takeIf { it in allowedActions } ?: "unknown"

    fun requiresConfirmation(action: String): Boolean = action in sensitiveActions

    fun clarificationFor(action: String, recipient: String, message: String): String = when {
        action == "send_sms" && recipient.isBlank() -> "Who would you like me to text?"
        action == "send_sms" && message.isBlank() -> "What would you like me to text $recipient?"
        action == "make_call" && recipient.isBlank() -> "Who would you like me to call?"
        else -> ""
    }
}
