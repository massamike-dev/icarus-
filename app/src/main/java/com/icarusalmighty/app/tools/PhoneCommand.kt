package com.icarusalmighty.app.tools

sealed interface PhoneCommand {
    val description: String
    val requiresConfirmation: Boolean

    data class OpenApp(val appName: String) : PhoneCommand { override val description = "Open $appName"; override val requiresConfirmation = false }
    data class Alarm(val hour: Int, val minute: Int) : PhoneCommand { override val description = "Set alarm for %d:%02d".format(hour, minute); override val requiresConfirmation = false }
    data class Timer(val seconds: Int) : PhoneCommand { override val description = "Set timer for $seconds seconds"; override val requiresConfirmation = false }
    data object Flashlight : PhoneCommand { override val description = "Toggle flashlight"; override val requiresConfirmation = false }
    data class Volume(val percent: Int) : PhoneCommand { override val description = "Set media volume to $percent%"; override val requiresConfirmation = false }
    data class Brightness(val percent: Int) : PhoneCommand { override val description = "Set brightness to $percent%"; override val requiresConfirmation = true }
    data class Navigate(val destination: String) : PhoneCommand { override val description = "Navigate to $destination"; override val requiresConfirmation = true }
    data class Dial(val numberOrName: String) : PhoneCommand { override val description = "Open dialer for $numberOrName"; override val requiresConfirmation = true }
    data class Sms(val recipient: String, val message: String) : PhoneCommand { override val description = "Compose text to $recipient: $message"; override val requiresConfirmation = true }
    data class Calendar(val title: String) : PhoneCommand { override val description = "Create calendar event: $title"; override val requiresConfirmation = true }
    data object Camera : PhoneCommand { override val description = "Open camera"; override val requiresConfirmation = true }
    data object Battery : PhoneCommand { override val description = "Read battery level"; override val requiresConfirmation = false }
    data object ConversationMode : PhoneCommand { override val description = "Open hands-free conversation mode"; override val requiresConfirmation = false }
    data class FindVideos(val query: String) : PhoneCommand { override val description = "Find videos matching: $query"; override val requiresConfirmation = true }
    data class Montage(val query: String) : PhoneCommand { override val description = "Prepare a montage from: $query"; override val requiresConfirmation = true }
    data class Unsupported(val raw: String) : PhoneCommand { override val description = "Send to ICARUS for interpretation: $raw"; override val requiresConfirmation = true }
}
