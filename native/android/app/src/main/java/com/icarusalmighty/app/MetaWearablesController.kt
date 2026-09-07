package com.icarusalmighty.app

import android.graphics.Bitmap
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import androidx.activity.result.ActivityResultLauncher
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

class MetaWearablesController(
    private val activity: MainActivity,
    private val dispatch: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val selector = AutoDeviceSelector()
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var display: Display? = null
    private var sessionJob: Job? = null
    private var streamJob: Job? = null
    private var displayJob: Job? = null
    private val sessionReady = mutableListOf<() -> Unit>()
    private var registration = "UNAVAILABLE"
    private var deviceCount = 0
    private var pendingCameraPermission: (() -> Unit)? = null
    private var tts: TextToSpeech? = null

    private val wearablePermissionLauncher: ActivityResultLauncher<Permission> =
        activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val status = result.getOrDefault(PermissionStatus.Denied)
            val continuation = pendingCameraPermission
            pendingCameraPermission = null
            if (status == PermissionStatus.Granted) continuation?.invoke()
            else event("meta_permission", "Camera permission was not granted")
        }

    init {
        Wearables.initialize(activity.applicationContext)
        scope.launch {
            Wearables.registrationState.collect {
                registration = it.name
                event("meta_registration_state", registration)
            }
        }
        scope.launch {
            Wearables.registrationErrorStream.collect { event("meta_registration_error", it.description) }
        }
        scope.launch {
            Wearables.devices.collect {
                deviceCount = it.size
                event("meta_devices", deviceCount.toString())
            }
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("available", true)
        .put("registrationState", registration)
        .put("deviceCount", deviceCount)
        .put("sessionState", session?.state?.value?.name ?: "STOPPED")
        .put("cameraState", stream?.state?.value?.name ?: "STOPPED")
        .put("displayState", display?.state?.value?.name ?: "STOPPED")
        .put("developerMode", BuildConfig.DEBUG)
        .put("capabilities", JSONArray(listOf("registration", "camera", "display", "audio_route", "mock_device")))

    fun execute(action: String, requestId: String?, args: JSONObject): String = when (action) {
        "meta_status" -> response(true, requestId, status())
        "meta_register" -> {
            activity.runOnUiThread { Wearables.startRegistration(activity) }
            accepted(requestId, "registration_started")
        }
        "meta_unregister" -> {
            activity.runOnUiThread { Wearables.startUnregistration(activity) }
            accepted(requestId, "unregistration_started")
        }
        "meta_session_start" -> {
            ensureSession(requestId) { complete(requestId, JSONObject().put("session", "STARTED")) }
            accepted(requestId, "session_starting")
        }
        "meta_session_stop" -> {
            stop()
            response(true, requestId, JSONObject().put("session", "STOPPING"))
        }
        "meta_capture_photo" -> {
            requestCameraCapture(requestId)
            accepted(requestId, "camera_preparing")
        }
        "meta_display" -> {
            sendDisplayCard(
                requestId,
                firstString(args, "title").ifBlank { "ICARUS" },
                firstString(args, "message", "text", "content").ifBlank { "ICARUS is connected" },
            )
            accepted(requestId, "display_preparing")
        }
        "meta_audio_test" -> {
            testAudio(requestId, firstString(args, "text", "message").ifBlank { "ICARUS audio connected" })
            accepted(requestId, "audio_test_started")
        }
        "meta_mock_enable" -> {
            if (!BuildConfig.DEBUG) response(false, requestId, error = "mock_device_debug_only")
            else {
                MockDeviceKit.getInstance(activity.applicationContext).enable()
                response(true, requestId, JSONObject().put("mockDeviceKit", "enabled"))
            }
        }
        "meta_mock_disable" -> {
            MockDeviceKit.getInstance(activity.applicationContext).disable()
            response(true, requestId, JSONObject().put("mockDeviceKit", "disabled"))
        }
        else -> response(false, requestId, error = "unsupported_meta_action")
    }

    private fun ensureSession(requestId: String?, ready: () -> Unit) {
        val current = session
        if (current?.state?.value == DeviceSessionState.STARTED) {
            ready()
            return
        }
        sessionReady += ready
        if (current != null) return
        Wearables.createSession(selector)
            .onSuccess { created ->
                session = created
                sessionJob?.cancel()
                sessionJob = scope.launch {
                    created.state.collect { state ->
                        event("meta_session_state", state.name)
                        if (state == DeviceSessionState.STARTED) {
                            val callbacks = sessionReady.toList()
                            sessionReady.clear()
                            callbacks.forEach { it() }
                        } else if (state == DeviceSessionState.STOPPED) {
                            clearCapabilities()
                            session = null
                        }
                    }
                }
                scope.launch {
                    created.errors.collect { fail(requestId, "meta_session_error", it.description) }
                }
                created.start()
            }
            .onFailure { error, _ ->
                sessionReady.clear()
                fail(requestId, "meta_session_failed", error.description)
            }
    }

    private fun requestCameraCapture(requestId: String?) {
        scope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA)
                .onSuccess { status ->
                    if (status == PermissionStatus.Granted) ensureCamera(requestId)
                    else {
                        pendingCameraPermission = { ensureCamera(requestId) }
                        wearablePermissionLauncher.launch(Permission.CAMERA)
                    }
                }
                .onFailure { error, _ -> fail(requestId, "meta_permission_check_failed", error.description) }
        }
    }

    private fun ensureCamera(requestId: String?) {
        stream?.takeIf { it.state.value == StreamState.STREAMING }?.let {
            capture(it, requestId)
            return
        }
        ensureSession(requestId) {
            val currentSession = session ?: return@ensureSession
            if (stream != null) return@ensureSession
            currentSession.addCamera(
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24, compressVideo = true)
            ).onSuccess { added ->
                camera = added
                val active = added.stream
                stream = active
                streamJob?.cancel()
                streamJob = scope.launch {
                    var captured = false
                    active.state.collect { state ->
                        event("meta_camera_state", state.name)
                        if (state == StreamState.STREAMING && !captured) {
                            captured = true
                            capture(active, requestId)
                        }
                    }
                }
                active.start().onFailure { error, _ ->
                    fail(requestId, "meta_camera_start_failed", error.description)
                    clearCamera()
                }
            }.onFailure { error, _ -> fail(requestId, "meta_camera_attach_failed", error.description) }
        }
    }

    private fun capture(active: Stream, requestId: String?) {
        scope.launch {
            active.capturePhoto()
                .onSuccess {
                    val file = savePhoto(it)
                    complete(requestId, JSONObject().put("captured", true).put("file", file.absolutePath))
                }
                .onFailure { error, _ -> fail(requestId, "meta_photo_failed", error.description) }
        }
    }

    private fun savePhoto(photo: PhotoData): File {
        val directory = File(activity.filesDir, "meta-captures").apply { mkdirs() }
        return when (photo) {
            is PhotoData.Bitmap -> File(directory, "icarus-${System.currentTimeMillis()}.jpg").also { file ->
                FileOutputStream(file).use { photo.bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            }
            is PhotoData.HEIC -> File(directory, "icarus-${System.currentTimeMillis()}.heic").also { file ->
                val buffer: ByteBuffer = photo.data.duplicate().apply { rewind() }
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                file.writeBytes(bytes)
            }
        }
    }

    private fun sendDisplayCard(requestId: String?, title: String, message: String) {
        ensureSession(requestId) {
            val current = session ?: return@ensureSession
            display?.takeIf { it.state.value == DisplayState.STARTED }?.let {
                sendCard(it, requestId, title, message)
                return@ensureSession
            }
            if (display != null) return@ensureSession
            current.addDisplay()
                .onSuccess { added ->
                    display = added
                    displayJob?.cancel()
                    displayJob = scope.launch {
                        var sent = false
                        added.state.collect { state ->
                            event("meta_display_state", state.name)
                            if (state == DisplayState.STARTED && !sent) {
                                sent = true
                                sendCard(added, requestId, title, message)
                            }
                        }
                    }
                }
                .onFailure { error, _ -> fail(requestId, "meta_display_attach_failed", error.description) }
        }
    }

    private fun sendCard(target: Display, requestId: String?, title: String, message: String) {
        scope.launch {
            target.sendContent {
                flexBox(gap = 12, padding = 24, background = FlexBoxBackground.CARD) {
                    text(title.take(80), style = TextStyle.HEADING)
                    text(message.take(500), style = TextStyle.BODY, color = TextColor.SECONDARY)
                }
            }.onSuccess {
                complete(requestId, JSONObject().put("displayed", true))
            }.onFailure { error, _ -> fail(requestId, "meta_display_failed", error.description) }
        }
    }

    private fun testAudio(requestId: String?, message: String) {
        activity.runOnUiThread {
            activity.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
            tts?.let {
                it.speak(message, TextToSpeech.QUEUE_FLUSH, null, "icarus-meta-audio")
                complete(requestId, JSONObject().put("played", true).put("route", "android_media"))
                return@runOnUiThread
            }
            tts = TextToSpeech(activity.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "icarus-meta-audio")
                    complete(requestId, JSONObject().put("played", true).put("route", "android_media"))
                } else fail(requestId, "meta_audio_failed", "Android text-to-speech initialization failed")
            }
        }
    }

    fun close() {
        stop()
        tts?.stop()
        tts?.shutdown()
        scope.cancel()
    }

    private fun stop() {
        clearCapabilities()
        session?.stop()
        session = null
        sessionReady.clear()
    }

    private fun clearCapabilities() {
        streamJob?.cancel()
        displayJob?.cancel()
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        stream = null
        display = null
    }

    private fun clearCamera() {
        streamJob?.cancel()
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        stream = null
    }

    private fun complete(requestId: String?, data: JSONObject) = dispatch(response(true, requestId, data))
    private fun fail(requestId: String?, code: String, message: String? = null) =
        dispatch(response(false, requestId, error = code, message = message))

    private fun event(type: String, value: String) = dispatch(
        JSONObject().put("ok", true).put("event", type)
            .put("data", JSONObject().put("value", value)).toString()
    )

    private fun accepted(requestId: String?, state: String) =
        response(true, requestId, JSONObject().put("accepted", true).put("state", state))

    private fun response(
        ok: Boolean,
        requestId: String?,
        data: JSONObject = JSONObject(),
        error: String? = null,
        message: String? = null,
    ): String = JSONObject()
        .put("ok", ok)
        .put("requestId", requestId ?: JSONObject.NULL)
        .put("data", data)
        .apply {
            if (error != null) put("error", error)
            if (!message.isNullOrBlank()) put("message", message)
        }.toString()

    private fun firstString(args: JSONObject, vararg keys: String): String {
        keys.forEach { if (args.has(it) && !args.isNull(it)) return args.optString(it, "") }
        return ""
    }
}
