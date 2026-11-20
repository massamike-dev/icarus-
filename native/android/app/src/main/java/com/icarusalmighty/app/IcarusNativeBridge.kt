package com.icarusalmighty.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.icarusalmighty.app.driving.DrivingHudActivity
import com.icarusalmighty.app.spatial.SpatialTelemetryService
import com.icarusalmighty.app.update.PlayUpdateManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class IcarusNativeBridge(
    private val activity: Activity,
    private val metaWearables: MetaWearablesController,
    private val resultDispatcher: (String) -> Unit,
) {
    private val context: Context get() = activity
    private val obd = ObdManager(context)
    private val localModel = LocalModelManager(context)
    private val billing = PlayBillingManager(activity, resultDispatcher)
    private val speechHandler = Handler(Looper.getMainLooper())
    private var voiceOperation: VoiceOperation? = null
    @Volatile private var closed = false
    private class VoiceOperation(val requestId: String, val text: String?, val preview: Boolean) {
        val utteranceId = UUID.randomUUID().toString()
        var engine: TextToSpeech? = null
        var applied: VoicePreferences.Applied? = null
        var timeout: Runnable? = null
        var monitor: Runnable? = null
    }

    fun getStatus(): String = JSONObject(statusJson(context))
        .put("hudControlVersion", 1)
        .put("installSource", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull() ?: "sideload"
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName) ?: "sideload"
        })
        .put("wakeWord", JSONObject()
            .put("permissionGranted", ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("listenerState", WakeWordService.listenerState)
            .put("enabled", WakeWordService.isEnabled(context))
            .put("sensitivity", context.getSharedPreferences("icarus_voice", Context.MODE_PRIVATE).getInt("sensitivity", 60).coerceIn(25, 90))
            .put("lastError", WakeWordService.lastError ?: JSONObject.NULL)
            .apply {
                val diagnostics = WakeWordService.diagnostics()
                diagnostics.keys().forEach { key -> put(key, diagnostics.get(key)) }
            })
        .put("metaWearables", metaWearables.status())
        .toString()

    fun getCapabilities(): String = JSONObject().put("capabilities", JSONArray(CAPABILITIES)).toString()

    fun executeAction(payloadJson: String): String {
        val payload = try { JSONObject(payloadJson) } catch (_: Exception) {
            return error(null, "invalid_payload")
        }
        val requestId = payload.optString("requestId").ifBlank { null }
        val action = payload.optString("action")
        val args = payload.optJSONObject("arguments") ?: JSONObject()

        return try {
            when (action) {
                "configure_voice_session" -> {
                    VoiceSessionStore.configure(context, args)
                    ok(requestId)
                }
                "open_app" -> openApp(requestId, args)
                "set_alarm" -> setAlarm(requestId, args)
                "set_timer" -> setTimer(requestId, args)
                "toggle_flashlight" -> setFlashlight(requestId, args)
                "set_volume" -> setVolume(requestId, args)
                "set_brightness" -> setBrightness(requestId, args)
                "navigate_to" -> navigate(requestId, args)
                "get_battery" -> battery(requestId)
                "take_photo" -> takePhoto(requestId)
                "make_call" -> makeCall(requestId, args)
                "send_sms" -> sendSms(requestId, args)
                "find_videos", "compose_video_montage" -> openMontage(requestId, args)
                "list_bluetooth", "bluetooth_status" -> listBluetooth(requestId)
                "wake_word" -> wakeWord(requestId, args)
                "wake_config" -> wakeConfig(requestId, args)
                "wake_audio_test" -> ok(requestId, JSONObject(getStatus()).getJSONObject("wakeWord"))
                "start_voice_turn" -> {
                    if (WakeWordService.requestTurn()) ok(requestId, JSONObject().put("requestAccepted", true))
                    else error(requestId, "voice_turn_unavailable", "Enable hands-free first and wait for the current voice turn to finish, then try Talk now.")
                }
                "get_voice_settings" -> startVoiceOperation(requestId)
                "set_voice_settings" -> setVoiceSettings(requestId, args)
                "preview_voice" -> startVoiceOperation(requestId, "Ready when you are. Steady, clear, and here to help.", preview = true)
                "open_voice_settings" -> launchForResult(requestId, Intent("com.android.settings.TTS_SETTINGS"), JSONObject().put("opened", true))
                "open_app_settings" -> launchForResult(requestId, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")), JSONObject().put("opened", true))
                "speak_text" -> speakText(requestId, args)
                "stop_speaking" -> stopSpeaking(requestId)
                "session_logout" -> sessionLogout(requestId)
                "check_subscription" -> {
                    if (BuildConfig.PRIVATE_TEST) return error(requestId, "private_test_billing_disabled", "Google Play billing is unavailable in ICARUS Test.")
                    val id = requestId ?: return error(null, "missing_request_id")
                    billing.checkSubscription(id)
                    ""
                }
                "subscribe" -> {
                    if (BuildConfig.PRIVATE_TEST) return error(requestId, "private_test_billing_disabled", "Google Play billing is unavailable in ICARUS Test.")
                    val id = requestId ?: return error(null, "missing_request_id")
                    val sku = firstString(args, "sku", "productId").trim()
                    if (sku.isBlank()) return error(id, "missing_subscription_product")
                    billing.subscribe(id, sku)
                    ""
                }
                "check_update" -> checkUpdate(requestId)
                "local_model_status" -> ok(requestId, localModel.status())
                "download_local_model" -> ok(requestId, localModel.startDownload(args.optBoolean("wifiOnly", true)))
                "delete_local_model" -> ok(requestId, localModel.deleteModel())
                "local_chat" -> {
                    localModel.generate(requestId, firstString(args, "prompt", "message", "text"), resultDispatcher)
                    ""
                }
                "interpret_command" -> {
                    localModel.interpretCommand(requestId, firstString(args, "command", "prompt", "text"), resultDispatcher)
                    ""
                }
                "obd_list" -> listBluetooth(requestId, obdOnly = true)
                "obd_connect" -> obdConnect(requestId, args)
                "obd_snapshot" -> obdSnapshot(requestId)
                "obd_disconnect" -> obdDisconnect(requestId)
                "open_driving_hud" -> openDrivingHud(requestId, args)
                "open_navigation_access_settings" -> openNavigationAccessSettings(requestId)
                "all_files_access_status" -> allFilesAccessStatus(requestId)
                "open_all_files_access_settings" -> openAllFilesAccessSettings(requestId)
                "xreal_status" -> xrealStatus(requestId, args)
                "open_xreal_hud" -> openXrealHud(requestId, args)
                "close_xreal_hud" -> closeXrealHud(requestId, args)
                "update_xreal_hud" -> metaWearables.execute("meta_xreal_update", requestId, args)
                else -> if (action.startsWith("meta_")) metaWearables.execute(action, requestId, args)
                    else error(requestId, "unsupported_action")
            }
        } catch (e: SecurityException) {
            error(requestId, "permission_required", e.message)
        } catch (e: Exception) {
            error(requestId, "native_action_failed", e.message)
        }
    }

    fun close() {
        closed = true
        obd.disconnect()
        activity.runOnUiThread { cancelVoiceOperation("voice_cancelled", "Speech stopped because the app closed.") }
        billing.close()
    }

    private fun openApp(requestId: String?, args: JSONObject): String {
        val target = firstString(args, "appName", "app", "name").lowercase(Locale.US).trim()
        if (target.isBlank()) return error(requestId, "missing_app_name")

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = context.packageManager.queryIntentActivities(launcher, 0)
        val exact = matches.filter {
            it.loadLabel(context.packageManager).toString().lowercase(Locale.US) == target
        }
        if (exact.size != 1) return error(requestId, "exact_app_name_required")
        val best = exact.single()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(best.activityInfo.packageName)
            ?: return error(requestId, "app_not_launchable")
        return launchForResult(requestId, launchIntent, JSONObject().put("app", best.loadLabel(context.packageManager).toString()))
    }

    private fun setAlarm(requestId: String?, args: JSONObject): String {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) return error(requestId, "invalid_alarm_time")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, firstString(args, "label", "message").ifBlank { "ICARUS alarm" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        activity.runOnUiThread { activity.startActivity(intent) }
        return ok(requestId)
    }

    private fun setTimer(requestId: String?, args: JSONObject): String {
        val seconds = when {
            args.has("durationSeconds") -> args.optInt("durationSeconds")
            args.has("seconds") -> args.optInt("seconds")
            args.has("minutes") -> args.optInt("minutes") * 60
            else -> 0
        }
        if (seconds !in 1..86400) return error(requestId, "invalid_timer_duration")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, firstString(args, "label", "message").ifBlank { "ICARUS timer" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launchForResult(requestId, intent, JSONObject().put("seconds", seconds))
    }

    private fun setFlashlight(requestId: String?, args: JSONObject): String {
        requirePermission(Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return error(requestId, "flash_not_available")
        val enabled = if (args.has("enabled")) args.optBoolean("enabled") else true
        manager.setTorchMode(cameraId, enabled)
        return ok(requestId, JSONObject().put("enabled", enabled))
    }

    private fun setVolume(requestId: String?, args: JSONObject): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val level = when {
            args.has("level") -> ((args.optDouble("level", 50.0).coerceIn(0.0, 100.0) / 100.0) * max).roundToInt()
            firstString(args, "direction").equals("up", true) -> (current + 1).coerceAtMost(max)
            firstString(args, "direction").equals("down", true) -> (current - 1).coerceAtLeast(0)
            else -> current
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
        return ok(requestId, JSONObject().put("level", ((level.toDouble() / max) * 100).roundToInt()))
    }

    private fun setBrightness(requestId: String?, args: JSONObject): String {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            activity.runOnUiThread { activity.startActivity(intent) }
            return error(requestId, "write_settings_permission_required")
        }
        val percent = args.optDouble("level", 50.0).coerceIn(1.0, 100.0)
        val value = ((percent / 100.0) * 255).roundToInt().coerceIn(1, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return ok(requestId, JSONObject().put("level", percent.roundToInt()))
    }

    private fun navigate(requestId: String?, args: JSONObject): String {
        val destination = firstString(args, "destination", "query", "address")
        if (destination.isBlank()) return error(requestId, "missing_destination")
        val uri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        return launchForResult(requestId, intent, JSONObject().put("destination", destination))
    }

    private fun battery(requestId: String?): String {
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = manager.isCharging
        return ok(requestId, JSONObject().put("level", level).put("charging", charging))
    }

    private fun takePhoto(requestId: String?): String {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        activity.runOnUiThread { activity.startActivity(intent) }
        return ok(requestId, JSONObject().put("opened", true))
    }

    private fun openMontage(requestId: String?, args: JSONObject): String {
        val query = firstString(args, "query", "subject", "description")
        val intent = Intent(context, MontageActivity::class.java).putExtra(MontageActivity.EXTRA_QUERY, query)
        activity.runOnUiThread { activity.startActivity(intent) }
        return ok(requestId, JSONObject().put("reviewOpened", true).put("query", query))
    }

    @SuppressLint("MissingPermission")
    private fun makeCall(requestId: String?, args: JSONObject): String {
        val number = resolvePhone(args) ?: return error(requestId, "contact_not_found")
        requirePermission(Manifest.permission.CALL_PHONE)
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
        return launchForResult(requestId, intent, JSONObject().put("number", number).put("requestAccepted", true))
    }

    private fun sendSms(requestId: String?, args: JSONObject): String {
        val number = resolvePhone(args) ?: return error(requestId, "contact_not_found")
        val message = firstString(args, "message", "body", "text")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
            .putExtra("sms_body", message)
        activity.runOnUiThread { activity.startActivity(intent) }
        return ok(requestId, JSONObject().put("number", number).put("composerOpened", true))
    }

    @SuppressLint("MissingPermission")
    private fun listBluetooth(requestId: String?, obdOnly: Boolean = false): String {
        if (Build.VERSION.SDK_INT >= 31) requirePermission(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            ?: return error(requestId, "bluetooth_unavailable")
        val devices = JSONArray()
        adapter.bondedDevices.orEmpty()
            .filter { !obdOnly || looksLikeObd(it.name.orEmpty()) }
            .sortedBy { it.name ?: it.address }
            .forEach { device ->
                devices.put(JSONObject().put("name", device.name ?: "Bluetooth device").put("address", device.address))
            }
        return ok(requestId, JSONObject().put("enabled", adapter.isEnabled).put("devices", devices))
    }

    private fun wakeWord(requestId: String?, args: JSONObject): String {
        val enabled = !args.has("enabled") || args.optBoolean("enabled")
        val intent = Intent(context, WakeWordService::class.java)
        if (enabled) {
            val host = activity as? MainActivity ?: return error(requestId, "native_host_unavailable")
            return host.requestWakePermissionFromDisclosure(requestId)
        } else {
            WakeWordService.setEnabled(context, false)
            context.stopService(intent)
        }
        return ok(requestId, JSONObject()
            .put("enabled", false)
            .put("permissionGranted", ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("listenerState", "STOPPED"))
    }

    private fun wakeConfig(requestId: String?, args: JSONObject): String {
        val preferences = context.getSharedPreferences("icarus_voice", Context.MODE_PRIVATE)
        val source = if (args.has("microphoneSource")) args.optString("microphoneSource")
            .takeIf { it in setOf("automatic", "phone", "bluetooth") }
            ?: return error(requestId, "invalid_microphone_source")
        else preferences.getString("microphone_source", "automatic") ?: "automatic"
        val sensitivity = if (args.has("sensitivity")) args.optInt("sensitivity", 60).coerceIn(25, 90)
            else preferences.getInt("sensitivity", 60).coerceIn(25, 90)
        val edit = preferences.edit().putString("microphone_source", source).putInt("sensitivity", sensitivity)
        // Preserve omitted settings. A sensitivity-only change must not reset microphone routing.
        if (args.has("timeoutSeconds")) edit.putInt("timeout_seconds", args.optInt("timeoutSeconds", 30).coerceAtLeast(0))
        listOf("followUpMode" to "follow_up_mode", "listenOnScreenWake" to "listen_on_screen_wake",
            "incomingCalls" to "incoming_calls", "activeDuringCalls" to "active_during_calls").forEach { (argument, key) ->
            if (args.has(argument)) edit.putBoolean(key, args.optBoolean(argument))
        }
        if (!edit.commit()) return error(requestId, "settings_not_saved", "Android could not save listening settings. Try again.")
        return ok(requestId, JSONObject().put("saved", true).put("microphoneSource", source)
            .put("sensitivity", sensitivity).put("appliesAfterRestart", WakeWordService.isEnabled(context)))
    }

    private fun setVoiceSettings(requestId: String?, args: JSONObject): String {
        val previous = VoicePreferences.read(context)
        val settings = try {
            VoiceSettings.create(
                args.optString("profile", previous.profile),
                args.optString("voiceName", previous.voiceName),
                if (args.has("rate")) args.optDouble("rate", Double.NaN).toFloat() else previous.rate,
                if (args.has("pitch")) args.optDouble("pitch", Double.NaN).toFloat() else previous.pitch,
            )
        } catch (e: IllegalArgumentException) {
            return error(requestId, "invalid_voice_settings", e.message)
        }
        if (!VoicePreferences.save(context, settings)) return error(requestId, "settings_not_saved", "Android could not save voice settings. Try again.")
        return ok(requestId, VoicePreferences.summary(settings).put("saved", true))
    }

    private fun speakText(requestId: String?, args: JSONObject): String {
        val text = firstString(args, "text", "content").trim()
        if (text.isBlank()) return error(requestId, "missing_text")
        // The saved profile is authoritative for chat, preview and hands-free replies alike.
        return startVoiceOperation(requestId, text.take(12000))
    }

    private fun turnIsBusy(): Boolean = WakeWordService.listenerState in setOf(
        "PREPARING_VOICE", "INITIALIZING_VOICE", "CAPTURING", "AWAITING_CONFIRMATION", "INTERPRETING", "EXECUTING", "SPEAKING",
    )

    private fun startVoiceOperation(requestId: String?, text: String? = null, preview: Boolean = false): String {
        val id = requestId ?: return error(null, "missing_request_id")
        activity.runOnUiThread {
            if (closed) { resultDispatcher(error(id, "voice_unavailable", "The native app is closing.")); return@runOnUiThread }
            if (voiceOperation != null || (text != null && turnIsBusy())) {
                resultDispatcher(error(id, "voice_busy", "Wait for the current voice request to finish, then try again."))
                return@runOnUiThread
            }
            val operation = VoiceOperation(id, text, preview)
            voiceOperation = operation
            setVoiceTimeout(operation, 15000, "Android speech output did not initialize. Check Android text-to-speech settings.")
            runCatching {
                operation.engine = TextToSpeech(context) { status -> speechHandler.post {
                    if (voiceOperation !== operation) return@post
                    if (status != TextToSpeech.SUCCESS) {
                        failVoiceOperation(operation, "voice_unavailable", "Android text-to-speech could not initialize. Check the installed speech engine.")
                        return@post
                    }
                    prepareVoiceOperation(operation)
                } }
            }.onFailure { failVoiceOperation(operation, "voice_unavailable", "Android speech output could not start. Check text-to-speech settings.") }
        }
        return ""
    }

    private fun prepareVoiceOperation(operation: VoiceOperation) {
        val engine = operation.engine ?: run {
            failVoiceOperation(operation, "voice_unavailable", "Android speech engine is unavailable.")
            return
        }
        val applied = runCatching { VoicePreferences.apply(context, engine) }.getOrElse {
            failVoiceOperation(operation, "voice_unavailable", it.message ?: "Android could not prepare the selected voice.")
            return
        }
        operation.applied = applied
        if (operation.text == null) {
            val result = runCatching { VoicePreferences.describe(engine, applied) }.getOrElse {
                failVoiceOperation(operation, "voice_unavailable", "Android could not list installed voices. Check text-to-speech settings.")
                return
            }
            finishVoiceOperation(operation, ok(operation.requestId, result))
            return
        }
        if (turnIsBusy()) {
            failVoiceOperation(operation, "voice_busy", "A hands-free command is active. Try the voice preview after it finishes.")
            return
        }
        runCatching {
            engine.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { speechHandler.post {
                    if (voiceOperation === operation && id == operation.utteranceId) finishVoiceOperation(operation,
                        ok(operation.requestId, VoicePreferences.summary(applied.settings).put("spoken", true)
                            .put("preview", operation.preview).put("engine", engine.defaultEngine ?: "android_tts")
                            .put("activeVoiceName", applied.activeVoiceName)
                            .apply { applied.message?.let { put("message", it) } }))
                } }
                @Deprecated("Platform callback") override fun onError(id: String?) { speechHandler.post {
                    if (id == operation.utteranceId) failVoiceOperation(operation, "speech_failed", "Android could not play the voice. Check media volume and audio output.")
                } }
                override fun onError(id: String?, errorCode: Int) { speechHandler.post {
                    if (id == operation.utteranceId) failVoiceOperation(operation, "speech_failed", when (errorCode) {
                        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "The selected voice has not finished downloading. Open Android text-to-speech settings."
                        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "The selected voice needs a working internet connection. Choose an offline voice or reconnect."
                        else -> "Android could not play the voice. Check media volume and text-to-speech settings."
                    })
                } }
                override fun onStop(id: String?, interrupted: Boolean) { speechHandler.post {
                    if (id == operation.utteranceId) failVoiceOperation(operation, "speech_interrupted", "Voice playback was interrupted. Try the preview again.")
                } }
            })
            setVoiceTimeout(operation, if (operation.preview) 15000 else 120000, "Voice playback did not finish. Check media volume and text-to-speech settings.")
            if (engine.speak(operation.text, TextToSpeech.QUEUE_FLUSH, null, operation.utteranceId) != TextToSpeech.SUCCESS) {
                failVoiceOperation(operation, "speech_failed", "Android could not start voice playback. Check text-to-speech settings.")
            } else {
                // A command begun after the preview must keep control of the microphone and its own TTS.
                operation.monitor = object : Runnable {
                    override fun run() {
                        if (voiceOperation !== operation) return
                        if (turnIsBusy()) failVoiceOperation(operation, "voice_busy", "Preview stopped because a hands-free command started.")
                        else speechHandler.postDelayed(this, 150)
                    }
                }.also { speechHandler.postDelayed(it, 150) }
            }
        }.onFailure { failVoiceOperation(operation, "speech_failed", "Android could not prepare voice playback. Check text-to-speech settings.") }
    }

    private fun setVoiceTimeout(operation: VoiceOperation, duration: Long, message: String) {
        operation.timeout?.let { speechHandler.removeCallbacks(it) }
        operation.timeout = Runnable { failVoiceOperation(operation, "voice_timeout", message) }
            .also { speechHandler.postDelayed(it, duration) }
    }

    private fun failVoiceOperation(operation: VoiceOperation, code: String, message: String) {
        val response = if (operation.text == null) ok(operation.requestId,
            VoicePreferences.summary(VoicePreferences.read(context)).put("available", false)
                .put("engine", operation.engine?.defaultEngine ?: "android_tts")
                .put("voices", JSONArray()).put("message", message))
        else error(operation.requestId, code, message)
        finishVoiceOperation(operation, response)
    }

    private fun finishVoiceOperation(operation: VoiceOperation, response: String) {
        if (voiceOperation !== operation) return
        voiceOperation = null
        operation.timeout?.let { speechHandler.removeCallbacks(it) }
        operation.monitor?.let { speechHandler.removeCallbacks(it) }
        runCatching { operation.engine?.stop() }
        runCatching { operation.engine?.shutdown() }
        operation.engine = null
        resultDispatcher(response)
    }

    private fun cancelVoiceOperation(code: String, message: String) {
        voiceOperation?.let { finishVoiceOperation(it, error(it.requestId, code, message)) }
    }

    private fun checkUpdate(requestId: String?): String {
        if (BuildConfig.PRIVATE_TEST) return ok(requestId, JSONObject()
            .put("checking", false)
            .put("enabled", false)
            .put("source", "private_install")
            .put("message", "ICARUS Test updates are installed privately. Public updates are disabled."))
        activity.runOnUiThread { PlayUpdateManager.check(activity, silent = false) }
        return ok(requestId, JSONObject().put("checking", true).put("source", "google_play"))
    }

    private fun stopSpeaking(requestId: String?): String {
        WakeWordService.cancelTurn()
        activity.runOnUiThread { cancelVoiceOperation("speech_cancelled", "Speech stopped by you.") }
        return ok(requestId, JSONObject().put("speaking", false))
    }

    private fun sessionLogout(requestId: String?): String {
        VoiceSessionStore.clear(context)
        WakeWordService.setEnabled(context, false)
        context.stopService(Intent(context, WakeWordService::class.java))
        obd.disconnect()
        activity.runOnUiThread { cancelVoiceOperation("speech_cancelled", "Speech stopped because you signed out.") }
        return ok(requestId, JSONObject().put("nativeSessionCleared", true))
    }

    private fun obdConnect(requestId: String?, args: JSONObject): String {
        if (Build.VERSION.SDK_INT >= 31) {
            requirePermission(Manifest.permission.BLUETOOTH_CONNECT)
            requirePermission(Manifest.permission.BLUETOOTH_SCAN)
        }
        val address = firstString(args, "address", "deviceAddress")
        if (address.isBlank()) return error(requestId, "missing_device_address")
        obd.connect(address)
        return ok(requestId, JSONObject().put("connected", true).put("address", address))
    }

    private fun obdSnapshot(requestId: String?): String = ok(requestId, obd.snapshot())

    private fun obdDisconnect(requestId: String?): String {
        obd.disconnect()
        return ok(requestId, JSONObject().put("connected", false))
    }

    private fun openDrivingHud(requestId: String?, args: JSONObject): String {
        val address = firstString(args, "obdAddress", "address").trim()
        obd.disconnect()
        val intent = Intent(context, DrivingHudActivity::class.java).apply {
            if (address.isNotBlank()) putExtra(DrivingHudActivity.EXTRA_OBD_ADDRESS, address)
        }
        return launchForResult(requestId, intent, JSONObject()
            .put("opened", true)
            .put("liveTelemetryRequired", true)
            .put("obdAddressProvided", address.isNotBlank()))
    }

    private fun openNavigationAccessSettings(requestId: String?): String = launchForResult(
        requestId,
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        JSONObject().put("opened", true),
    )

    private fun allFilesAccessStatus(requestId: String?): String {
        if (!BuildConfig.PRIVATE_TEST) return error(requestId, "unsupported_action")
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val granted = supported && Environment.isExternalStorageManager()
        return ok(requestId, JSONObject()
            .put("supported", supported)
            .put("granted", granted)
            .put("testOnly", true))
    }

    private fun openAllFilesAccessSettings(requestId: String?): String {
        if (!BuildConfig.PRIVATE_TEST) return error(requestId, "unsupported_action")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return error(requestId, "unsupported_android_version", "All files access requires Android 11 or newer.")
        }
        return launchForResult(
            requestId,
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ),
            JSONObject()
                .put("opened", true)
                .put("granted", Environment.isExternalStorageManager())
                .put("testOnly", true),
        )
    }

    private fun xrealStatus(requestId: String?, args: JSONObject): String {
        when (SpatialHudTarget.parse(args.optString("target"))) {
            SpatialHudTarget.BUNDLED -> return metaWearables.execute("meta_xreal_status", requestId, args)
            SpatialHudTarget.COMPANION -> Unit
            null -> return error(requestId, "invalid_hud_target")
        }
        val launch = Intent(Intent.ACTION_VIEW, Uri.parse("icarus-spatial://launch"))
            .setPackage(SpatialTelemetryService.COMPANION_PACKAGE)
        val installed = context.packageManager.resolveActivity(launch, 0) != null
        return ok(requestId, JSONObject()
            .put("target", "companion")
            .put("enabled", IntegrationPreferences(context).snapshot().xrealEnabled)
            .put("installed", installed)
            .put("package", SpatialTelemetryService.COMPANION_PACKAGE)
            .put("host", "beam_pro")
            .put("tracking", "unverified")
            .put("glassesConnectionVerified", false)
            .put("liveTelemetryOnly", true))
    }

    private fun openXrealHud(requestId: String?, args: JSONObject): String {
        when (SpatialHudTarget.parse(args.optString("target"))) {
            SpatialHudTarget.BUNDLED -> return metaWearables.execute("meta_xreal_launch", requestId, args)
            SpatialHudTarget.COMPANION -> Unit
            null -> return error(requestId, "invalid_hud_target")
        }
        if (!IntegrationPreferences(context).snapshot().xrealEnabled) {
            return error(requestId, "integration_disabled", "Enable XREAL Integration before opening the HUD.")
        }
        if (Build.VERSION.SDK_INT >= 31) {
            requirePermission(Manifest.permission.BLUETOOTH_CONNECT)
            requirePermission(Manifest.permission.BLUETOOTH_SCAN)
        }
        val address = firstString(args, "obdAddress", "address").trim()
        if (address.isBlank()) return error(requestId, "missing_device_address", "Select a live OBD adapter before opening the XREAL HUD.")
        val token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val launch = Intent(Intent.ACTION_VIEW, Uri.Builder()
            .scheme("icarus-spatial").authority("launch")
            .appendQueryParameter("port", SpatialTelemetryService.PORT.toString())
            .appendQueryParameter("token", token).build())
            .setPackage(SpatialTelemetryService.COMPANION_PACKAGE)
        if (context.packageManager.resolveActivity(launch, 0) == null) {
            return error(requestId, "xreal_companion_not_installed", "Install the ICARUS XREAL companion on Beam Pro before opening the Spatial HUD.")
        }
        activity.runOnUiThread {
            if (closed || activity.isFinishing || activity.isDestroyed) {
                resultDispatcher(error(requestId, "activity_unavailable"))
                return@runOnUiThread
            }
            if (!IntegrationPreferences(context).snapshot().xrealEnabled) {
                resultDispatcher(error(requestId, "integration_disabled"))
                return@runOnUiThread
            }
            val response = try {
                obd.disconnect()
                context.stopService(Intent(context, SpatialTelemetryService::class.java))
                ContextCompat.startForegroundService(context, Intent(context, SpatialTelemetryService::class.java)
                    .setAction(SpatialTelemetryService.ACTION_START)
                    .putExtra(SpatialTelemetryService.EXTRA_OBD_ADDRESS, address)
                    .putExtra(SpatialTelemetryService.EXTRA_TOKEN, token))
                activity.startActivity(launch)
                ok(requestId, JSONObject().put("opened", true).put("launched", true)
                    .put("launchRequested", true).put("target", "companion").put("host", "beam_pro")
                    .put("tracking", "unverified").put("glassesConnectionVerified", false)
                    .put("liveTelemetryRequired", true).put("port", SpatialTelemetryService.PORT))
            } catch (e: Exception) {
                context.stopService(Intent(context, SpatialTelemetryService::class.java))
                error(requestId, "xreal_launch_failed", e.message)
            }
            resultDispatcher(response)
        }
        return ""
    }

    private fun closeXrealHud(requestId: String?, args: JSONObject): String {
        when (SpatialHudTarget.parse(args.optString("target"))) {
            SpatialHudTarget.BUNDLED -> return metaWearables.execute("meta_xreal_close", requestId, args)
            SpatialHudTarget.COMPANION -> Unit
            null -> return error(requestId, "invalid_hud_target")
        }
        val wasRunning = context.stopService(Intent(context, SpatialTelemetryService::class.java))
        return ok(requestId, JSONObject().put("target", "companion")
            .put("telemetryStopRequested", true).put("telemetryWasRunning", wasRunning)
            .put("closeRequested", false)
            .put("message", "The telemetry service was stopped. Close the separate companion app on Beam Pro."))
    }

    private fun resolvePhone(args: JSONObject): String? {
        firstString(args, "phone", "number").takeIf { it.isNotBlank() }?.let {
            return it.takeIf { number -> Regex("\\+?[0-9 ()-]{3,}").matches(number) }
        }
        val contact = firstString(args, "recipient", "contact", "contactName", "name")
        if (contact.isBlank()) return null
        requirePermission(Manifest.permission.READ_CONTACTS)

        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? COLLATE NOCASE",
                arrayOf(contact),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            val numbers = mutableSetOf<String>()
            while (cursor?.moveToNext() == true) {
                numbers.add(cursor.getString(0).replace(Regex("[^+0-9]"), ""))
            }
            numbers.singleOrNull()?.takeIf { Regex("\\+?[0-9]{3,}").matches(it) }
        } finally {
            cursor?.close()
        }
    }

    private fun launchForResult(requestId: String?, intent: Intent, data: JSONObject): String {
        activity.runOnUiThread {
            if (closed || activity.isFinishing || activity.isDestroyed) {
                resultDispatcher(error(requestId, "activity_unavailable"))
                return@runOnUiThread
            }
            val result = try {
                activity.startActivity(intent)
                ok(requestId, data.put("executionStatus", "request_accepted"))
            } catch (e: SecurityException) {
                error(requestId, "permission_required", e.message)
            } catch (e: Exception) {
                error(requestId, "target_app_unavailable", e.message)
            }
            resultDispatcher(result)
        }
        return ""
    }

    private fun requirePermission(permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            activity.runOnUiThread { activity.requestPermissions(arrayOf(permission), permission.hashCode() and 0xffff) }
            throw SecurityException("$permission permission required")
        }
    }

    private fun firstString(args: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            if (args.has(key) && !args.isNull(key)) return args.optString(key, "")
        }
        return ""
    }

    private fun looksLikeObd(name: String): Boolean =
        listOf("obd", "elm", "vlink", "veepeak", "obdlink").any { name.lowercase(Locale.US).contains(it) }

    private fun ok(requestId: String?, data: JSONObject = JSONObject()): String = JSONObject()
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

    companion object {
        val CAPABILITIES = listOf(
            "wake_word", "wake_config", "wake_audio_test", "start_voice_turn",
            "get_voice_settings", "set_voice_settings", "preview_voice", "open_voice_settings", "open_app_settings", "bluetooth_audio", "list_bluetooth", "open_app", "toggle_flashlight",
            "set_volume", "set_brightness", "make_call", "send_sms", "take_photo", "set_alarm",
            "set_timer", "navigate_to", "get_battery", "obd_list", "obd_connect", "obd_snapshot",
            "obd_disconnect", "open_driving_hud", "open_navigation_access_settings", "all_files_access_status", "open_all_files_access_settings", "find_videos", "compose_video_montage", "native_tts", "speak_text", "stop_speaking", "session_logout", "check_subscription", "subscribe", "check_update",
            "local_model_status", "download_local_model", "delete_local_model", "local_chat", "interpret_command",
            "meta_status", "meta_register", "meta_unregister", "meta_session_start", "meta_session_stop",
            "meta_capture_photo", "meta_display", "meta_audio_test", "meta_mock_enable", "meta_mock_disable",
            "xreal_status", "open_xreal_hud", "close_xreal_hud", "update_xreal_hud", "meta_xreal_status", "meta_xreal_launch", "meta_xreal_close", "meta_xreal_update"
        ).filterNot { (BuildConfig.PRIVATE_TEST && it in setOf("check_subscription", "subscribe", "check_update")) || (!BuildConfig.PRIVATE_TEST && it in setOf("all_files_access_status", "open_all_files_access_settings")) }

        fun statusJson(context: Context): String = JSONObject()
            .put("connected", true)
            .put("platform", "android")
            .put("version", BuildConfig.VERSION_NAME)
            .put("applicationId", BuildConfig.APPLICATION_ID)
            .put("privateTest", BuildConfig.PRIVATE_TEST)
            .put("actionProtocolVersion", 1)
            .put("allFilesAccess", JSONObject()
                .put("supported", BuildConfig.PRIVATE_TEST && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                .put("granted", BuildConfig.PRIVATE_TEST && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
                .put("testOnly", BuildConfig.PRIVATE_TEST))
            .put("voiceSession", VoiceSessionStore.status(context))
            .put("voiceSettings", VoicePreferences.summary(VoicePreferences.read(context)))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("capabilities", JSONArray(CAPABILITIES))
            .toString()
    }
}
