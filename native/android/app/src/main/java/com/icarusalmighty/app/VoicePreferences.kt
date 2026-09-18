package com.icarusalmighty.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One persisted voice configuration, used by previews, chat speech and wake replies. */
object VoicePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("icarus_voice", Context.MODE_PRIVATE)

    fun read(context: Context): VoiceSettings {
        val p = prefs(context)
        return runCatching { VoiceSettings.create(
            p.getString("tts_profile", "deep_warm") ?: "deep_warm",
            p.getString("tts_voice_name", "") ?: "",
            p.getFloat("tts_rate", 0.88f), p.getFloat("tts_pitch", 0.82f),
        ) }.getOrDefault(VoiceSettings())
    }

    fun save(context: Context, settings: VoiceSettings): Boolean = prefs(context).edit()
        .putString("tts_profile", settings.profile)
        .putString("tts_voice_name", settings.voiceName)
        .putFloat("tts_rate", settings.rate)
        .putFloat("tts_pitch", settings.pitch)
        .commit()

    fun summary(settings: VoiceSettings): JSONObject = JSONObject()
        .put("supported", true).put("profile", settings.profile)
        .put("voiceName", settings.voiceName).put("rate", settings.rate.toString().toDouble())
        .put("pitch", settings.pitch.toString().toDouble())

    private fun englishVoices(engine: TextToSpeech): List<Voice> = engine.voices.orEmpty()
        .filter { it.locale.language.equals("en", true) &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
        .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }
            .thenBy { !it.locale.country.equals("US", true) }.thenBy { it.name })

    data class Applied(val settings: VoiceSettings, val activeVoiceName: String, val message: String? = null)

    /** Call only after a successful TTS initialization callback. */
    fun apply(context: Context, engine: TextToSpeech): Applied {
        val settings = read(context)
        val language = engine.setLanguage(Locale.US)
        check(language >= TextToSpeech.LANG_AVAILABLE) {
            if (language == TextToSpeech.LANG_MISSING_DATA)
                "English voice data is missing. Install a voice in Android text-to-speech settings."
            else "An English voice is unavailable. Choose an English voice in Android text-to-speech settings."
        }
        val choices = englishVoices(engine)
        val requested = choices.firstOrNull { it.name == settings.voiceName }
        val candidates = listOfNotNull(requested) + choices.filter { it !== requested }
        val selected = candidates.firstOrNull { engine.setVoice(it) == TextToSpeech.SUCCESS }
        check(selected != null || engine.voice?.locale?.language.equals("en", true)) {
            "Android could not load an English voice. Check text-to-speech settings."
        }
        check(engine.setSpeechRate(settings.rate) == TextToSpeech.SUCCESS &&
            engine.setPitch(settings.pitch) == TextToSpeech.SUCCESS) {
            "The selected Android engine could not apply voice speed and pitch."
        }
        val activeName = engine.voice?.name.orEmpty()
        return Applied(settings, activeName, if (settings.voiceName.isNotEmpty() && settings.voiceName != activeName)
            "The saved voice is unavailable in this engine. Using an available English voice; choose and preview another voice if needed."
        else null)
    }

    fun describe(engine: TextToSpeech, applied: Applied): JSONObject = summary(applied.settings)
        .put("available", true)
        .put("engine", engine.defaultEngine ?: "android_tts")
        .put("activeVoiceName", applied.activeVoiceName)
        .put("voices", JSONArray().apply {
            englishVoices(engine).forEach { voice -> put(JSONObject()
                .put("name", voice.name)
                .put("label", "${voice.locale.getDisplayName(Locale.US)} · ${voice.name}")
                .put("locale", voice.locale.toLanguageTag())
                .put("networkRequired", voice.isNetworkConnectionRequired)) }
        })
        .apply { applied.message?.let { put("message", it) } }
}
