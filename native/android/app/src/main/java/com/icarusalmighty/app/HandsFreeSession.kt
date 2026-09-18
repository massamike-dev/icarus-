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
import java.util.UUID
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
    private data class ProposedAction(val command: HandsFreeCommands.Command, val proposalId: String? = null, val session: VoiceSessionState? = null)
    private var pending: ProposedAction? = null
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
                override fun onError(e: Int) { if (!closed) { cancelPending("Confirmation was not heard; the action was not executed."); say("I couldn't hear that command. Try Hey ICARUS again.") } }
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
            if (HandsFreeCommands.confirms(text)) execute(previous) else {
                report(previous, "cancelled", "Cancelled before execution.")
                say("Cancelled.")
            }
            return
        }
        val command = HandsFreeCommands.parse(text)
        if (command != null) accept(ProposedAction(command)) else interpret(text)
    }

    private fun interpret(text: String) {
        if (text.isBlank()) { say("I didn't hear a command."); return }
        val session = VoiceSessionStore.snapshot(context)
        if (session.token.isBlank()) { say("Sign in to ICARUS for flexible commands. Offline commands include battery, flashlight, volume, timers, call, and navigate to."); return }
        val clientTurnId = UUID.randomUUID().toString()
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
                    c.setRequestProperty("Authorization", "Bearer ${session.token}")
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(JSONObject().put("command", text.take(1000))
                        .put("conversationId", session.conversationId ?: JSONObject.NULL)
                        .put("temporary", session.temporary).put("clientTurnId", clientTurnId)
                        .toString().toByteArray(Charsets.UTF_8)) }
                    if (c.responseCode == 404 && session.conversationId != null) throw ConversationUnavailable()
                    check(c.responseCode == 200)
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally { c.disconnect() }
            }
            handler.post {
                if (!closed && turn == epoch) {
                    handler.removeCallbacks(timeout)
                    if (!VoiceSessionStore.isCurrent(context, session)) {
                        say("Your conversation changed. Please repeat that request.")
                        return@post
                    }
                    result.onSuccess { p ->
                        val returnedId = if (p.isNull("conversationId")) null else p.optString("conversationId").ifBlank { null }
                        val current = VoiceSessionStore.acceptConversation(context, session, returnedId)
                        if (current == null) { say("Your conversation changed. Please repeat that request."); return@onSuccess }
                        val action = p.optString("action")
                        val proposalId = p.optString("proposalId").takeIf { Regex("[A-Za-z0-9_-]{1,200}").matches(it) }
                        if (action in HandsFreeCommands.supported) accept(ProposedAction(HandsFreeCommands.Command(action, p.optString("value")), proposalId, current))
                        else say(p.optString("reply", "That action isn't connected yet.").take(500))
                    }.onFailure {
                        if (it is ConversationUnavailable) {
                            VoiceSessionStore.clearConversation(context, session)
                            say("That conversation is no longer available. Please repeat your request to start a new one.")
                        } else say("The command service is unavailable. Try a direct command such as battery or flashlight on.")
                    }
                }
            }
        }
    }

    private class ConversationUnavailable : Exception()

    private fun accept(proposed: ProposedAction) {
        val c = proposed.command
        if (c.action in setOf("make_call", "navigate_to", "open_app", "set_timer")) {
            // Never allow an AI result to suppress confirmation or supply a misleading summary.
            pending = proposed
            val question = when (c.action) {
                "make_call" -> "Call ${c.value}?"
                "navigate_to" -> "Request navigation to ${c.value}?"
                "set_timer" -> "Request a timer for ${c.value} seconds?"
                else -> "Open ${c.value}?"
            }
            say("$question Say yes to confirm, or cancel.") { capture() }
        } else execute(proposed)
    }

    private fun execute(proposed: ProposedAction) {
        if (proposed.session != null && !VoiceSessionStore.isCurrent(context, proposed.session)) {
            say("Your conversation changed. Please repeat that request.")
            return
        }
        val c = proposed.command
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
        report(proposed, if (c.action == "cancel") "cancelled" else "reported", message)
        say(message)
    }

    private fun cancelPending(summary: String) {
        val proposed = pending ?: return
        pending = null
        report(proposed, "cancelled", summary)
    }

    /** Reports the Android reply, including failures or request-only acknowledgements, never inferred success. */
    private fun report(proposed: ProposedAction, status: String, summary: String) {
        val session = proposed.session ?: return
        val proposalId = proposed.proposalId ?: return
        if (session.temporary || session.token.isBlank() || !VoiceSessionStore.isCurrent(context, session)) return
        if (network.isShutdown) return
        network.execute {
            if (!VoiceSessionStore.isCurrent(context, session)) return@execute
            runCatching {
                val origin = BuildConfig.ICARUS_WEB_URL.trimEnd('/')
                require(TrustedWebPolicy.isTrustedUrl(origin, origin))
                val connection = URL("$origin/api/actions/$proposalId/result").openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer ${session.token}")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(JSONObject().put("status", status).put("summary", summary.take(500)).toString().toByteArray(Charsets.UTF_8)) }
                    check(connection.responseCode in 200..299)
                } finally { connection.disconnect() }
            }
            // A lost report is not an execution retry; repeating a call or timer would be unsafe.
        }
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
        cancelPending("Voice turn ended before confirmation; the action was not executed.")
        closed = true; epoch++
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        // Allow a result queued after actual execution to finish even when TTS ends first.
        network.shutdown()
    }
}
