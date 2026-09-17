package com.icarusalmighty.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** One service-owned turn: capture -> validate -> confirm -> execute -> speak -> rearm. */
class HandsFreeSession(private val context: Context, private val state: (String) -> Unit, private val finished: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var epoch = 0
    private var afterSpeech: (() -> Unit)? = null
    private var queued: String? = null
    private var pending: HandsFreeCommands.Command? = null
    private var expectedSpeechId: String? = null
    private val timeout = Runnable { if (!closed) finish() }

    fun begin() {
        state("CAPTURING")
        tts = TextToSpeech(context) { status -> handler.post {
            if (!closed && status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { handler.post { completeSpeech(id) } }
                    @Deprecated("Platform callback") override fun onError(id: String?) { handler.post { if (id == expectedSpeechId) finish() } }
                })
                queued?.let { speakNow(it) }
            } else if (!closed) finish()
        } }
        capture()
    }

    private fun capture() {
        if (closed) return
        state(if (pending == null) "CAPTURING" else "AWAITING_CONFIRMATION")
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { say("Speech recognition is unavailable on this device."); return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(v: Float) = Unit
                override fun onBufferReceived(b: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(p: Bundle?) = Unit
                override fun onEvent(t: Int, p: Bundle?) = Unit
                override fun onError(e: Int) { if (!closed) { pending = null; say("I couldn't hear that command. Try Hey ICARUS again.") } }
                override fun onResults(results: Bundle?) {
                    if (!closed) handle(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
            })
            runCatching { r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")) }
                .onFailure { say("Microphone capture could not start. Check ICARUS permissions.") }
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 20000)
    }

    private fun handle(text: String) {
        handler.removeCallbacks(timeout)
        val previous = pending
        pending = null
        if (previous != null) {
            if (HandsFreeCommands.confirms(text)) execute(previous) else say("Cancelled.")
            return
        }
        val command = HandsFreeCommands.parse(text)
        if (command != null) accept(command) else interpret(text)
    }

    private fun interpret(text: String) {
        if (text.isBlank()) { say("I didn't hear a command."); return }
        val token = context.getSharedPreferences("icarus_session", Context.MODE_PRIVATE).getString("token", "").orEmpty()
        if (token.isBlank()) { say("Sign in to ICARUS for flexible commands. Offline commands include battery, flashlight, volume, timers, call, and navigate to."); return }
        state("INTERPRETING")
        val turn = ++epoch
        handler.postDelayed(timeout, 80000)
        network.execute {
            val result = runCatching {
                val origin = BuildConfig.ICARUS_WEB_URL.trimEnd('/')
                require(TrustedWebPolicy.isTrustedUrl(origin, origin))
                val c = URL("$origin/api/commands/interpret").openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = false
                    c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = 70000; c.doOutput = true
                    c.setRequestProperty("Authorization", "Bearer $token")
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(JSONObject().put("command", text.take(1000)).toString().toByteArray()) }
                    check(c.responseCode == 200)
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally { c.disconnect() }
            }
            handler.post {
                if (!closed && turn == epoch) {
                    handler.removeCallbacks(timeout)
                    result.onSuccess { p ->
                        val action = p.optString("action")
                        if (action in HandsFreeCommands.supported) accept(HandsFreeCommands.Command(action, p.optString("value")))
                        else say(p.optString("reply", "That action isn't connected yet.").take(500))
                    }.onFailure { say("The command service is unavailable. Try a direct command such as battery or flashlight on.") }
                }
            }
        }
    }

    private fun accept(c: HandsFreeCommands.Command) {
        if (c.action in setOf("make_call", "navigate_to", "open_app", "set_timer")) {
            // Never allow an AI result to suppress confirmation or supply a misleading summary.
            pending = c
            val question = when (c.action) {
                "make_call" -> "Call ${c.value}?"
                "navigate_to" -> "Request navigation to ${c.value}?"
                "set_timer" -> "Request a timer for ${c.value} seconds?"
                else -> "Open ${c.value}?"
            }
            say("$question Say yes to confirm, or cancel.") { capture() }
        } else execute(c)
    }

    private fun execute(c: HandsFreeCommands.Command) {
        state("EXECUTING")
        val message = runCatching {
            when (c.action) {
                "cancel" -> "Cancelled."
                "stop_listening" -> { WakeWordService.setEnabled(context, false); "Hands-free listening is off. Enable it in ICARUS to start again." }
                "get_battery" -> "Battery is ${context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent."
                "toggle_flashlight" -> {
                    require(c.value in setOf("on", "off"))
                    val manager = context.getSystemService(CameraManager::class.java)
                    val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                        ?: error("No flashlight available")
                    manager.setTorchMode(id, c.value == "on"); "Flashlight ${c.value}."
                }
                "set_volume" -> {
                    val percent = c.value.toInt(); require(percent in 0..100)
                    val manager = context.getSystemService(AudioManager::class.java)
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * percent / 100, 0)
                    "Media volume set to $percent percent."
                }
                else -> launch(c)
            }
        }.getOrElse { if (it is SecurityException) "Permission is required. Open ICARUS app permissions to allow this action." else "I couldn't complete that action. Nothing is confirmed." }
        say(message)
    }

    private fun launch(c: HandsFreeCommands.Command): String {
        if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return "Unlock your phone and repeat this command."
        val intent = when (c.action) {
            "make_call" -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
                val number = if (Regex("\\+?[0-9 ()-]{3,}").matches(c.value)) c.value else {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
                    val numbers = mutableSetOf<String>()
                    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(c.value), null)?.use { cursor ->
                        while (cursor.moveToNext()) numbers.add(cursor.getString(0).replace(Regex("[^+0-9]"), ""))
                    }
                    if (numbers.size != 1) return "I need one exact contact with one number. Say the full contact name or phone number."
                    numbers.single()
                }
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
            }
            "navigate_to" -> { require(c.value.isNotBlank()); Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(c.value)}")) }
            "set_timer" -> {
                val seconds = c.value.toInt(); require(seconds in 1..86400)
                Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            "open_app" -> {
                val apps = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.loadLabel(context.packageManager).toString().equals(c.value, true) }
                if (apps.size != 1) return "Say the exact name of one installed app."
                context.packageManager.getLaunchIntentForPackage(apps.single().activityInfo.packageName) ?: error("No launch intent")
            }
            else -> return "That action isn't connected for hands-free use yet."
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Request sent to Android. If it doesn't open, Android may require you to open ICARUS first."
    }

    private fun say(text: String, next: () -> Unit = { finish() }) {
        if (closed) return
        recognizer?.destroy(); recognizer = null
        state("SPEAKING")
        afterSpeech = next
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 45000)
        queued = text
        if (ready) speakNow(text)
    }
    private fun speakNow(text: String) {
        queued = null
        expectedSpeechId = "turn-${++epoch}"
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, expectedSpeechId) == TextToSpeech.ERROR) finish()
    }
    private fun completeSpeech(id: String?) {
        if (closed || id != expectedSpeechId) return
        expectedSpeechId = null
        handler.removeCallbacks(timeout)
        val callback = afterSpeech; afterSpeech = null; callback?.invoke()
    }
    private fun finish() { if (!closed) { close(); finished() } }
    fun close() {
        if (closed) return
        closed = true; epoch++; pending = null
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        network.shutdownNow()
    }
}
