package com.icarusalmighty.app

/** Device-independent validation for the voice settings shared by every speech path. */
data class VoiceSettings(
    val profile: String = "deep_warm",
    val voiceName: String = "",
    val rate: Float = 0.88f,
    val pitch: Float = 0.82f,
    val language: String = "en-US",
) {
    companion object {
        fun create(profile: String, voiceName: String, rate: Float, pitch: Float, language: String = "en-US"): VoiceSettings {
            require(language.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*"))) { "Choose a valid speech language." }
            require(profile in setOf("deep_warm", "standard", "custom")) { "Choose a supported voice profile." }
            require(rate.isFinite() && pitch.isFinite()) { "Voice speed and pitch must be finite numbers." }
            require(voiceName.length <= 512 && voiceName.none { it.isISOControl() }) { "Choose a valid installed voice." }
            return when (profile) {
                "deep_warm" -> VoiceSettings(profile, voiceName, 0.88f, 0.82f, language)
                "standard" -> VoiceSettings(profile, voiceName, 1f, 1f, language)
                else -> VoiceSettings(profile, voiceName, rate.coerceIn(0.5f, 1.5f), pitch.coerceIn(0.5f, 1.5f), language)
            }
        }
    }
}
