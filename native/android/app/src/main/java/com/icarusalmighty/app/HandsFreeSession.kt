package com.icarusalmighty.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
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
import java.util.UUID
import java.util.concurrent.Executors

/** One service-owned turn: capture -> validate -> confirm -> execute -> speak -> rearm. */
class HandsFreeSession(
    private val context: Context,
    private val state: (String) -> Unit,
    private val finished: () -> Unit,
    private val onError: (String) -> Unit = {},
) {
    private val runtimeGeneration = RuntimeMode.generation()
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var epoch = 0
    private val captures = VoiceCaptureGate()
    private val launchCancellation = AppLaunchCancellationGate()
    private var launchDeadline: Runnable? = null
    private var phase = "INITIALIZING_VOICE"
    private var afterSpeech: (() -> Unit)? = null
    private data class ProposedAction(val command: HandsFreeCommands.Command, val proposalId: String? = null, val session: VoiceSessionState? = null, val modelInterpreted: Boolean = false)
    private var pending: ProposedAction? = null
    private var expectedSpeechId: String? = null
    private val timeout = Runnable { if (!closed) timedOut() }

    fun begin() {
        if (closed || tts != null) return
        transition("INITIALIZING_VOICE", 15000)
        runCatching {
            tts = TextToSpeech(context) { status -> handler.post {
                if (closed || phase != "INITIALIZING_VOICE") return@post
                if (status != TextToSpeech.SUCCESS) {
                    speechFailed("Android text-to-speech could not initialize. Check the installed speech engine in Android settings.")
                    return@post
                }
                runCatching {
                    val engine = checkNotNull(tts) { "Android speech engine is unavailable." }
                    VoicePreferences.apply(context, engine)
                }.onFailure {
                    speechFailed(it.message ?: "Android could not prepare the saved voice. Check text-to-speech settings.")
                    return@post
                }
                runCatching {
                    tts?.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) { handler.post { completeSpeech(id) } }
                        @Deprecated("Platform callback") override fun onError(id: String?) {
                            handler.post { if (id != null && id == expectedSpeechId) speechFailed("Android could not play the spoken reply. Check media volume and audio output.") }
                        }
                        override fun onError(id: String?, errorCode: Int) {
                            handler.post { if (id != null && id == expectedSpeechId) speechFailed(speechError(errorCode)) }
                        }
                        override fun onStop(id: String?, interrupted: Boolean) {
                            handler.post { if (id != null && id == expectedSpeechId) speechFailed("Speech playback was interrupted before the reply finished.") }
                        }
                    })
                }.onFailure {
                    speechFailed("Android speech output could not be prepared. Check text-to-speech settings.")
                    return@post
                }
                ready = true
                // The acknowledgement makes wake detection observable and prevents
                // capture from racing TTS initialization or recording its own reply.
                say("Yes?") { capture() }
            } }
        }.onFailure {
            speechFailed("Android text-to-speech could not start. Check the installed speech engine in Android settings.")
        }
    }

    private fun capture() {
        if (closed) return
        if (!RuntimeMode.isCurrent(runtimeGeneration)) { finish(); return }
        stopCapture()
        val captureId = captures.start()
        transition(if (pending == null) "CAPTURING" else "AWAITING_CONFIRMATION", 20000)
        runCatching {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                captureFailed(captureId, "Android speech recognition is unavailable. Enable or install a speech recognition service.")
                return
            }
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(v: Float) = Unit
                override fun onBufferReceived(b: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(p: Bundle?) = Unit
                override fun onEvent(t: Int, p: Bundle?) = Unit
                override fun onError(e: Int) { captureFailed(captureId, recognitionError(e)) }
                override fun onResults(results: Bundle?) {
                    if (!closed && captures.consume(captureId)) {
                        stopCapture()
                        handle(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                    }
                }
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US"))
        }.onFailure {
            captureFailed(captureId, "Microphone capture could not start. Check ICARUS microphone permission and Android speech recognition.")
        }
    }

    private fun captureFailed(captureId: Long, message: String) {
        if (closed || !captures.consume(captureId)) return
        stopCapture()
        cancelPending("Confirmation was not heard; the action was not executed.")
        onError(message)
        say("$message Try Hey ICARUS again.")
    }

    private fun stopCapture() {
        // Invalidate callbacks before destroy(), which may itself deliver onError.
        captures.cancel()
        launchCancellation.cancel()
        launchDeadline?.let(handler::removeCallbacks)
        launchDeadline = null
        val previous = recognizer
        recognizer = null
        runCatching { previous?.destroy() }
    }

    private fun handle(text: String) {
        if (!RuntimeMode.isCurrent(runtimeGeneration)) { finish(); return }
        handler.removeCallbacks(timeout)
        val command = HandsFreeCommands.parse(text)
        // Explicit stop/home always wins, including while awaiting confirmation.
        if (command?.action in setOf("stop_listening", "go_home", "close_app", "cancel", "unsupported_force_stop")) {
            cancelPending("Cancelled before execution.")
            execute(ProposedAction(checkNotNull(command)))
            return
        }
        val previous = pending
        pending = null
        if (previous != null) {
            if (HandsFreeCommands.confirms(text)) execute(previous) else {
                report(previous, "cancelled", "Cancelled before execution.")
                say("Cancelled.")
            }
            return
        }
        if (command != null) accept(ProposedAction(command)) else interpret(text)
    }

    private fun interpret(text: String) {
        if (text.isBlank()) { say("I didn't hear a command."); return }
        if (RuntimeMode.current(context) == RuntimeMode.LOCAL) { interpretLocally(text); return }
        val session = VoiceSessionStore.snapshot(context)
        if (session.token.isBlank()) { say("Sign in to ICARUS for flexible commands. Offline commands include battery, flashlight, volume, timers, call, and navigate to."); return }
        val clientTurnId = UUID.randomUUID().toString()
        transition("INTERPRETING", 80000)
        val turn = ++epoch
        network.execute {
            val result = runCatching {
                check(RuntimeMode.current(context) == RuntimeMode.CLOUD && VoiceSessionStore.isCurrent(context, session))
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
                    if (c.responseCode != 200) throw CommandServiceUnavailable(c.responseCode)
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally { c.disconnect() }
            }
            handler.post {
                if (!closed && turn == epoch && RuntimeMode.isCurrent(runtimeGeneration)) {
                    handler.removeCallbacks(timeout)
                    if (RuntimeMode.current(context) != RuntimeMode.CLOUD || !VoiceSessionStore.isCurrent(context, session)) {
                        say("Your conversation changed. Please repeat that request.")
                        return@post
                    }
                    result.onSuccess { p ->
                        val returnedId = if (p.isNull("conversationId")) null else p.optString("conversationId").ifBlank { null }
                        val current = VoiceSessionStore.acceptConversation(context, session, returnedId)
                        if (current == null) { say("Your conversation changed. Please repeat that request."); return@onSuccess }
                        val action = p.optString("action")
                        val proposalId = p.optString("proposalId").takeIf { Regex("[A-Za-z0-9_-]{1,200}").matches(it) }
                        if (action in HandsFreeCommands.supported) accept(ProposedAction(HandsFreeCommands.Command(action, p.optString("value")), proposalId, current, modelInterpreted = true))
                        else say(p.optString("reply", "That action isn't connected yet.").take(500))
                    }.onFailure {
                        if (it is ConversationUnavailable) {
                            VoiceSessionStore.clearConversation(context, session)
                            say("That conversation is no longer available. Please repeat your request to start a new one.")
                        } else if (it is CommandServiceUnavailable && it.status in setOf(401, 403)) {
                            onError("The command service did not accept the current sign-in. Sign in again in ICARUS.")
                            say("Sign in to ICARUS again for flexible commands. Direct commands such as battery or flashlight on still work.")
                        } else {
                            onError("The command service could not answer. Check the connection and the ICARUS server.")
                            say("The command service is unavailable. Try a direct command such as battery or flashlight on.")
                        }
                    }
                }
            }
        }
    }

    private fun interpretLocally(text: String) {
        transition("INTERPRETING", 120000)
        val turn = ++epoch
        LocalModelManager.get(context).interpretCommand(null, text) { raw -> handler.post {
            if (closed || turn != epoch || !RuntimeMode.isCurrent(runtimeGeneration)) return@post
            handler.removeCallbacks(timeout)
            if (RuntimeMode.current(context) != RuntimeMode.LOCAL) {
                say("Your assistant mode changed. Please repeat that request.")
                return@post
            }
            val result = runCatching { JSONObject(raw) }.getOrNull()
            if (result == null || !result.optBoolean("ok")) {
                val message = result?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "I couldn't interpret that on this device. Try a direct command such as battery or flashlight on."
                onError(message)
                say(message)
                return@post
            }
            val data = result.optJSONObject("data") ?: JSONObject()
            val command = LocalCommandResult.handsFree(data)
            if (command != null) accept(ProposedAction(command, modelInterpreted = true))
            else say(data.optString("clarification").ifBlank {
                "That command needs review in ICARUS. Try a direct command such as battery, flashlight on, or navigate to a destination."
            }.take(500))
        } }
    }

    private class ConversationUnavailable : Exception()
    private class CommandServiceUnavailable(val status: Int) : Exception()

    private fun accept(proposed: ProposedAction) {
        if (!RuntimeMode.isCurrent(runtimeGeneration)) { finish(); return }
        val c = proposed.command
        if (c.action == "open_app" && !proposed.modelInterpreted) {
            prepareAppLaunch(proposed)
            return
        }
        if (HandsFreeActionPolicy.requiresConfirmation(c.action, proposed.modelInterpreted)) {
            // Never allow an AI result to suppress confirmation or supply a misleading summary.
            pending = proposed
            val question = when (c.action) {
                "make_call" -> "Call ${c.value}?"
                "navigate_to" -> "Request navigation to ${c.value}?"
                "set_timer" -> "Request a timer for ${c.value} seconds?"
                "toggle_flashlight" -> "Turn the flashlight ${c.value}?"
                "set_volume" -> "Set media volume to ${c.value} percent?"
                "stop_listening" -> "Turn off hands-free listening?"
                "open_app" -> "Open ${c.value}?"
                "go_home", "close_app" -> "Return to the home screen?"
                else -> "Request this device action?"
            }
            say("$question Say yes to confirm, or cancel.") { capture() }
        } else execute(proposed)
    }

    private fun resolveApp(name: String) = HandsFreeAppLaunch.resolve(name,
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    ) { it.loadLabel(context.packageManager).toString() }

    private fun prepareAppLaunch(proposed: ProposedAction) {
        if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            say("Unlock your phone and repeat this command.")
            return
        }
        runCatching {
            val app = resolveApp(proposed.command.value)
            if (app == null) { say("Say the exact name of one installed app."); return }
            val intent = context.packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)
            if (intent == null) { say("I couldn't open that app."); return }
            pending = proposed
            say(HandsFreeAppLaunch.announcement(app.loadLabel(context.packageManager).toString())) {
                captureLaunchCancellation(proposed, intent)
            }
        }.onFailure { say("I couldn't open that app.") }
    }

    /** TTS has completed before this starts. Wait three seconds of microphone-ready time,
     * then drain the final recognition result so a boundary 'stop' cannot race the launch.
     * Missing readiness/results or recognition errors cancel rather than launch blindly.
     */
    private fun captureLaunchCancellation(proposed: ProposedAction, intent: Intent) {
        if (closed || !RuntimeMode.isCurrent(runtimeGeneration) || !WakeWordService.isEnabled(context)) { finish(); return }
        stopCapture()
        val captureId = captures.start()
        val windowId = launchCancellation.start()
        transition("LISTENING_FOR_CANCEL", 15000)
        fun fail(message: String) {
            if (closed || !captures.consume(captureId)) return
            stopCapture()
            cancelPending("App launch cancelled because cancellation listening was unavailable.")
            onError(message)
            say("Cancelled.")
        }
        fun result(text: String, silence: Boolean = false) {
            if (closed || !captures.consume(captureId)) return
            val canLaunch = silence && launchCancellation.finishWithSilence(windowId)
            stopCapture()
            if (!RuntimeMode.isCurrent(runtimeGeneration) || !WakeWordService.isEnabled(context)) { finish(); return }
            if (canLaunch && pending === proposed) {
                pending = null
                transition("EXECUTING")
                val failure = runCatching {
                    check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.exceptionOrNull()
                if (failure == null) {
                    report(proposed, "reported", "App launch requested from Android.")
                    finish() // The concise opening announcement was already spoken.
                } else {
                    report(proposed, "reported", "Android could not open the app.")
                    say("I couldn't open that app.")
                }
            } else {
                cancelPending("Cancelled before execution.")
                val command = HandsFreeCommands.parse(text)
                if (command?.action in setOf("stop_listening", "go_home", "close_app", "cancel", "unsupported_force_stop")) execute(ProposedAction(checkNotNull(command)))
                else say("Cancelled.")
            }
        }
        runCatching {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) { fail("Android speech recognition is unavailable."); return }
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    if (closed || !launchCancellation.microphoneReady(windowId)) return
                    launchDeadline = Runnable {
                        if (closed || !launchCancellation.windowElapsed(windowId)) return@Runnable
                        if (!RuntimeMode.isCurrent(runtimeGeneration) || !WakeWordService.isEnabled(context)) { finish(); return@Runnable }
                        transition("FINALIZING_APP_LAUNCH", 8000)
                        // Do not truncate 'stop listening' begun just before the deadline.
                        if (!launchCancellation.awaitingSpeechResult(windowId)) {
                            runCatching { r.stopListening() }.onFailure { fail("Android could not finish cancellation listening.") }
                        }
                    }.also { handler.postDelayed(it, AppLaunchCancellationGate.WINDOW_MS) }
                }
                override fun onBeginningOfSpeech() { launchCancellation.heardSpeech(windowId) }
                override fun onRmsChanged(v: Float) = Unit
                override fun onBufferReceived(b: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(t: Int, p: Bundle?) = Unit
                override fun onPartialResults(p: Bundle?) {
                    val text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) launchCancellation.heardSpeech(windowId)
                    // Bare 'stop' can become 'stop listening'; 'close' can become a named app.
                    // Wait for final results before navigating or resolving a target.
                    if (HandsFreeCommands.parse(text)?.action == "stop_listening" ||
                        text.trim().lowercase() in setOf("cancel", "never mind", "nevermind")) result(text)
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    result(text, silence = text.isBlank())
                }
                override fun onError(e: Int) {
                    if (e in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) result("", silence = true)
                    else fail(recognitionError(e))
                }
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, AppLaunchCancellationGate.WINDOW_MS))
        }.onFailure { fail("Microphone capture could not start for app-launch cancellation.") }
    }

    private fun execute(proposed: ProposedAction) {
        if (!RuntimeMode.isCurrent(runtimeGeneration)) { finish(); return }
        if (proposed.session != null && !VoiceSessionStore.isCurrent(context, proposed.session)) {
            say("Your conversation changed. Please repeat that request.")
            return
        }
        val c = proposed.command
        transition("EXECUTING")
        val message = runCatching {
            when (c.action) {
                "cancel" -> "Cancelled."
                "unsupported_force_stop" -> "I can't force-stop apps. I can return to the home screen."
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
        if (RuntimeMode.current(context) != RuntimeMode.CLOUD || session.temporary || session.token.isBlank() || !VoiceSessionStore.isCurrent(context, session)) return
        if (network.isShutdown) return
        network.execute {
            if (RuntimeMode.current(context) != RuntimeMode.CLOUD || !VoiceSessionStore.isCurrent(context, session)) return@execute
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
            "go_home", "close_app" -> {
                if (c.action == "close_app" && resolveApp(c.value) == null) return "Say the exact name of one installed app."
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            }
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
                val app = resolveApp(c.value) ?: return "Say the exact name of one installed app."
                context.packageManager.getLaunchIntentForPackage(app.activityInfo.packageName) ?: error("No launch intent")
            }
            else -> return "That action isn't connected for hands-free use yet."
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (c.action in setOf("go_home", "close_app")) return "Home."
        return "Request sent to Android. If it doesn't open, Android may require you to open ICARUS first."
    }

    private fun say(text: String, next: () -> Unit = { finish() }) {
        if (closed) return
        if (!RuntimeMode.isCurrent(runtimeGeneration)) { finish(); return }
        stopCapture()
        transition("SPEAKING", 45000)
        afterSpeech = next
        if (!ready || tts == null) {
            speechFailed("Android speech output is not ready. Check text-to-speech settings.")
            return
        }
        speakNow(text)
    }
    private fun speakNow(text: String) {
        expectedSpeechId = "turn-${++epoch}"
        val result = runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, expectedSpeechId) }.getOrNull()
        if (result != TextToSpeech.SUCCESS) speechFailed("Android could not start the spoken reply. Check media volume and text-to-speech settings.")
    }
    private fun completeSpeech(id: String?) {
        if (closed || id == null || id != expectedSpeechId) return
        expectedSpeechId = null
        handler.removeCallbacks(timeout)
        val callback = afterSpeech; afterSpeech = null; callback?.invoke()
    }

    private fun transition(value: String, timeoutMs: Long? = null) {
        phase = value
        state(value)
        handler.removeCallbacks(timeout)
        if (timeoutMs != null) handler.postDelayed(timeout, timeoutMs)
    }

    private fun timedOut() {
        when (phase) {
            "INITIALIZING_VOICE" -> speechFailed("Android speech output did not initialize within 15 seconds. Check text-to-speech settings.")
            "CAPTURING", "AWAITING_CONFIRMATION" -> {
                stopCapture()
                cancelPending("Confirmation timed out; the action was not executed.")
                onError("Android speech recognition did not return a result within 20 seconds.")
                say("Speech recognition timed out. Try Hey ICARUS again.")
            }
            "LISTENING_FOR_CANCEL", "FINALIZING_APP_LAUNCH" -> {
                stopCapture()
                cancelPending("App launch cancelled because cancellation listening timed out.")
                onError("Android did not complete app-launch cancellation listening.")
                say("Cancelled.")
            }
            "INTERPRETING" -> {
                epoch++
                val local = RuntimeMode.current(context) == RuntimeMode.LOCAL
                onError(if (local) "On-device interpretation timed out." else "The ICARUS cloud command service timed out.")
                say(if (local) "On-device interpretation timed out. Direct commands still work. If the model stays busy, restart ICARUS. Nothing was sent to the cloud."
                    else "The cloud command service timed out. Try a direct command such as battery or flashlight on.")
            }
            "SPEAKING" -> speechFailed("Android speech playback did not finish. Check media volume, audio output, and text-to-speech settings.")
            else -> { onError("The voice turn timed out before it completed."); finish() }
        }
    }

    private fun speechFailed(message: String) {
        if (closed) return
        onError(message)
        finish()
    }

    private fun recognitionError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Android could not record command audio. Another app may be using the microphone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Android speech recognition needs microphone permission."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Android speech recognition is busy."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Android speech recognition could not connect. Check your internet connection."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I couldn't hear a command."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The Android speech recognizer does not support English."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English speech recognition data is unavailable on this device."
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The Android speech recognition service is unavailable."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Android speech recognition needs a moment before another request."
        else -> "Android speech recognition stopped with error $code."
    }

    private fun speechError(code: Int): String = when (code) {
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Android speech voice data has not finished downloading."
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "The selected Android speech voice needs a working internet connection."
        TextToSpeech.ERROR_OUTPUT -> "Android could not play the spoken reply. Check media volume and audio output."
        else -> "Android text-to-speech failed with error $code. Check text-to-speech settings."
    }

    private fun finish() { if (!closed) { close(); finished() } }
    fun close() {
        if (closed) return
        cancelPending("Voice turn ended before confirmation; the action was not executed.")
        closed = true; epoch++
        handler.removeCallbacksAndMessages(null)
        expectedSpeechId = null
        afterSpeech = null
        stopCapture()
        val previousSpeech = tts
        tts = null
        runCatching { previousSpeech?.stop() }
        runCatching { previousSpeech?.shutdown() }
        // Allow a result queued after actual execution to finish even when TTS ends first.
        network.shutdown()
    }
}
