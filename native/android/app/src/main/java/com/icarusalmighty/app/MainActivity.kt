package com.icarusalmighty.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var pendingWake = false

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWakeWordService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWake = intent.getBooleanExtra(EXTRA_WAKE_WORD, false)

        webView = WebView(this)
        webView.clearCache(true)
        setContentView(webView)
        configureWebView()
        requestWakePermissionIfNeeded()
        loadIcarus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_WAKE_WORD, false)) {
            dispatchWakeWord()
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = "$userAgentString ICARUSNative/${BuildConfig.VERSION_NAME}"
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                notifyNativeStatus()
                if (pendingWake) {
                    pendingWake = false
                    dispatchWakeWord()
                }
            }
        }
        webView.addJavascriptInterface(IcarusNativeBridge(this, webView), "ICARUS_NATIVE")
    }

    private fun loadIcarus() {
        val url = BuildConfig.ICARUS_WEB_URL.trim()
        if (url.startsWith("https://")) {
            webView.loadUrl(url)
            return
        }
        webView.loadDataWithBaseURL(
            null,
            """
            <!doctype html><html><body style='margin:0;background:#020817;color:#eee;font-family:sans-serif'>
            <div style='max-width:720px;margin:12vh auto;padding:32px'>
            <h1 style='color:#d4af37'>I.C.A.R.U.S. Native Host</h1>
            <p>The Android bridge is installed, but ICARUS_WEB_URL has not been set.</p>
            <p>Set the published Base44 HTTPS URL in <code>native/android/gradle.properties</code>, rebuild, and install again.</p>
            </div></body></html>
            """.trimIndent(),
            "text/html",
            "utf-8",
            null
        )
    }

    private fun requestWakePermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWakeWordService()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun notifyNativeStatus() {
        webView.post {
            webView.evaluateJavascript(
                "window.ICARUS_NATIVE_STATUS && window.ICARUS_NATIVE_STATUS(${JSONObject.quote(IcarusNativeBridge.statusJson(this))});",
                null
            )
        }
    }

    private fun dispatchWakeWord() {
        if (!::webView.isInitialized) {
            pendingWake = true
            return
        }
        webView.post {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('icarus-wake-word',{detail:{phrase:'hey icarus'}}));",
                null
            )
        }
    }

    companion object {
        const val EXTRA_WAKE_WORD = "wake_word"
    }
}

class IcarusNativeBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    private val context: Context get() = activity
    private val obd = ObdManager(context)
    private var tts: TextToSpeech? = null
    private var pendingSpeech: Triple<String, Float, Float>? = null

    @JavascriptInterface
    fun getStatus(): String = statusJson(context)

    @JavascriptInterface
    fun getCapabilities(): String = JSONObject().put("capabilities", JSONArray(CAPABILITIES)).toString()

    @JavascriptInterface
    fun executeAction(payloadJson: String): String {
        val payload = try { JSONObject(payloadJson) } catch (_: Exception) {
            return error(null, "invalid_payload")
        }
        val requestId = payload.optString("requestId").ifBlank { null }
        val action = payload.optString("action")
        val args = payload.optJSONObject("arguments") ?: JSONObject()

        return try {
            when (action) {
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
                "speak_text" -> speakText(requestId, args)
                "stop_speaking" -> stopSpeaking(requestId)
                "obd_list" -> listBluetooth(requestId, obdOnly = true)
                "obd_connect" -> obdConnect(requestId, args)
                "obd_snapshot" -> obdSnapshot(requestId)
                "obd_disconnect" -> obdDisconnect(requestId)
                else -> error(requestId, "unsupported_action")
            }
        } catch (e: SecurityException) {
            error(requestId, "permission_required", e.message)
        } catch (e: Exception) {
            error(requestId, "native_action_failed", e.message)
        }
    }

    private fun openApp(requestId: String?, args: JSONObject): String {
        val target = firstString(args, "appName", "app", "name").lowercase(Locale.US).trim()
        if (target.isBlank()) return error(requestId, "missing_app_name")

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = context.packageManager.queryIntentActivities(launcher, 0)
        val best = matches.firstOrNull {
            it.loadLabel(context.packageManager).toString().lowercase(Locale.US) == target
        } ?: matches.firstOrNull {
            it.loadLabel(context.packageManager).toString().lowercase(Locale.US).contains(target)
        } ?: return error(requestId, "app_not_found")

        val launchIntent = context.packageManager.getLaunchIntentForPackage(best.activityInfo.packageName)
            ?: return error(requestId, "app_not_launchable")
        activity.runOnUiThread { activity.startActivity(launchIntent) }
        return ok(requestId, JSONObject().put("app", best.loadLabel(context.packageManager).toString()))
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
        if (seconds <= 0) return error(requestId, "invalid_timer_duration")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, firstString(args, "label", "message").ifBlank { "ICARUS timer" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        activity.runOnUiThread { activity.startActivity(intent) }
        return ok(requestId, JSONObject().put("seconds", seconds))
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
        activity.runOnUiThread { activity.startActivity(intent) }
        return ok(requestId, JSONObject().put("destination", destination))
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

    private fun makeCall(requestId: String?, args: JSONObject): String {
        val number = resolvePhone(args) ?: return error(requestId, "contact_not_found")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
        activity.runOnUiThread { activity.startActivity(intent) }
        return ok(requestId, JSONObject().put("number", number))
    }

    private fun sendSms(requestId: String?, args: JSONObject): String {
        val number = resolvePhone(args) ?: return error(requestId, "contact_not_found")
        val message = firstString(args, "message", "body", "text")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
            .putExtra("sms_body", message)
        activity.runOnUiThread { activity.startActivity(intent) }
        return ok(requestId, JSONObject().put("number", number).put("composerOpened", true))
    }

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
        if (enabled) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
        return ok(requestId, JSONObject().put("enabled", enabled))
    }

    private fun speakText(requestId: String?, args: JSONObject): String {
        val text = firstString(args, "text", "content").trim()
        if (text.isBlank()) return error(requestId, "missing_text")
        val rate = args.optDouble("rate", 1.0).toFloat().coerceIn(0.5f, 1.5f)
        val pitch = args.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 1.5f)
        pendingSpeech = Triple(text.take(12000), rate, pitch)
        activity.runOnUiThread {
            val existing = tts
            if (existing != null) {
                existing.setSpeechRate(rate)
                existing.setPitch(pitch)
                existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, "icarus-native-speech")
            } else {
                tts = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        pendingSpeech?.let { (queuedText, queuedRate, queuedPitch) ->
                            tts?.setSpeechRate(queuedRate)
                            tts?.setPitch(queuedPitch)
                            tts?.speak(queuedText, TextToSpeech.QUEUE_FLUSH, null, "icarus-native-speech")
                        }
                    }
                    pendingSpeech = null
                }
            }
        }
        return ok(requestId, JSONObject().put("speaking", true).put("engine", "android_tts"))
    }

    private fun stopSpeaking(requestId: String?): String {
        activity.runOnUiThread { tts?.stop() }
        pendingSpeech = null
        return ok(requestId, JSONObject().put("speaking", false))
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

    private fun resolvePhone(args: JSONObject): String? {
        firstString(args, "phone", "number").takeIf { it.isNotBlank() }?.let { return it }
        val contact = firstString(args, "contact", "name")
        if (contact.isBlank()) return null
        requirePermission(Manifest.permission.READ_CONTACTS)

        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$contact%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
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
            "wake_word", "bluetooth_audio", "list_bluetooth", "open_app", "toggle_flashlight",
            "set_volume", "set_brightness", "make_call", "send_sms", "take_photo", "set_alarm",
            "set_timer", "navigate_to", "get_battery", "obd_list", "obd_connect", "obd_snapshot",
            "obd_disconnect", "find_videos", "compose_video_montage", "native_tts", "speak_text", "stop_speaking"
        )

        fun statusJson(context: Context): String = JSONObject()
            .put("connected", true)
            .put("platform", "android")
            .put("version", BuildConfig.VERSION_NAME)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("capabilities", JSONArray(CAPABILITIES))
            .toString()
    }
}

class ObdManager(private val context: Context) {
    private var socket: android.bluetooth.BluetoothSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    @Synchronized
    fun connect(address: String) {
        disconnect()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            ?: error("Bluetooth unavailable")
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        adapter.cancelDiscovery()
        s.connect()
        socket = s
        input = BufferedInputStream(s.inputStream)
        output = BufferedOutputStream(s.outputStream)

        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { command(it, 2500) }
    }

    @Synchronized
    fun disconnect() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null
        output = null
        socket = null
    }

    @Synchronized
    fun snapshot(): JSONObject {
        if (socket?.isConnected != true) error("OBD adapter not connected")

        val rpm = parsePid(command("010C"), "410C")?.let { bytes ->
            if (bytes.size >= 2) ((bytes[0] * 256) + bytes[1]) / 4.0 else null
        }
        val speed = parsePid(command("010D"), "410D")?.firstOrNull()?.toDouble()
        val coolant = parsePid(command("0105"), "4105")?.firstOrNull()?.let { it - 40.0 }
        val load = parsePid(command("0104"), "4104")?.firstOrNull()?.let { it * 100.0 / 255.0 }
        val throttle = parsePid(command("0111"), "4111")?.firstOrNull()?.let { it * 100.0 / 255.0 }
        val fuel = parsePid(command("012F"), "412F")?.firstOrNull()?.let { it * 100.0 / 255.0 }
        val voltage = Regex("(\\d{1,2}(?:\\.\\d+)?)V", RegexOption.IGNORE_CASE)
            .find(command("ATRV"))?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        return JSONObject()
            .put("connected", true)
            .put("rpm", rpm ?: JSONObject.NULL)
            .put("speedMph", speed?.times(0.621371) ?: JSONObject.NULL)
            .put("coolantF", coolant?.let { (it * 9.0 / 5.0) + 32.0 } ?: JSONObject.NULL)
            .put("engineLoadPercent", load ?: JSONObject.NULL)
            .put("throttlePercent", throttle ?: JSONObject.NULL)
            .put("fuelPercent", fuel ?: JSONObject.NULL)
            .put("voltage", voltage ?: JSONObject.NULL)
            .put("timestamp", System.currentTimeMillis())
    }

    private fun command(cmd: String, timeoutMs: Long = 1800): String {
        val out = output ?: error("OBD output unavailable")
        val inp = input ?: error("OBD input unavailable")
        while (inp.available() > 0) inp.read()
        out.write((cmd.trim() + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()

        val buffer = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            while (inp.available() > 0) {
                val b = inp.read()
                if (b < 0) break
                val c = b.toChar()
                if (c == '>') return buffer.toString()
                buffer.append(c)
            }
            Thread.sleep(20)
        }
        return buffer.toString()
    }

    private fun parsePid(raw: String, prefix: String): List<Int>? {
        val clean = raw.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        val index = clean.indexOf(prefix)
        if (index < 0) return null
        val payload = clean.substring(index + prefix.length)
        if (payload.length < 2) return emptyList()
        return payload.chunked(2).mapNotNull { if (it.length == 2) it.toIntOrNull(16) else null }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

@Deprecated("Replaced by the account-free Sherpa engine")
class LegacySpeechRecognizerWakeService : Service(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening for “Hey Icarus”"))
        startRecognizer()
    }

    override fun onDestroy() {
        stopping = true
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecognizer() {
        if (stopping || !SpeechRecognizer.isRecognitionAvailable(this)) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }
        try {
            recognizer?.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            )
        } catch (_: Exception) {
            restartLater()
        }
    }

    private fun inspect(results: Bundle?) {
        val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (phrases.any { it.lowercase(Locale.US).contains("hey icarus") || it.lowercase(Locale.US).contains("hey, icarus") }) {
            val launch = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_WAKE_WORD, true)
            try { startActivity(launch) } catch (_: Exception) {}
        }
    }

    private fun restartLater(delayMs: Long = 450) {
        if (stopping) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ startRecognizer() }, delayMs)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { restartLater(250) }
    override fun onError(error: Int) { restartLater(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 500) }
    override fun onResults(results: Bundle?) { inspect(results); restartLater() }
    override fun onPartialResults(partialResults: Bundle?) { inspect(partialResults) }
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ICARUS wake word", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("I.C.A.R.U.S.")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "icarus_wake_word"
        private const val NOTIFICATION_ID = 4401
    }
}