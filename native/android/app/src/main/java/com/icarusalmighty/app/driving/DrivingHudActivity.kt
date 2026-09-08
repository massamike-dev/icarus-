package com.icarusalmighty.app.driving

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.icarusalmighty.app.WakeWordService
import java.util.Locale

class DrivingHudActivity : AppCompatActivity() {
    private lateinit var hud: DrivingHudView
    private var telemetry: DrivingTelemetryController? = null
    private var latestState = DrivingHudState()
    private var speechRecognizer: SpeechRecognizer? = null
    private var initialized = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        initializeTelemetryOnce()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        hud = DrivingHudView(this, ::handleHudAction)
        setContentView(hud)
        requestNeededPermissionsOrStart()
    }

    override fun onResume() {
        super.onResume()
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        telemetry?.stop()
        telemetry = null
        restartWakeListenerIfAllowed()
        super.onDestroy()
    }

    private fun requestNeededPermissionsOrStart() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(this@DrivingHudActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this@DrivingHudActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (missing.isEmpty()) initializeTelemetryOnce() else permissions.launch(missing.toTypedArray())
    }

    private fun initializeTelemetryOnce() {
        if (initialized) return
        initialized = true
        val address = intent.getStringExtra(EXTRA_OBD_ADDRESS)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: findSingleKnownObdAddress()
        telemetry = DrivingTelemetryController(this, address) { state ->
            latestState = state
            hud.render(state)
        }.also { it.start() }
    }

    private fun handleHudAction(action: HudAction) {
        val controller = telemetry ?: return
        val parkedControlsAllowed = latestState.parkedControlsAllowed
        when (action) {
            HudAction.EXIT -> finish()
            HudAction.VOICE -> startVoiceCapture()
            HudAction.SHOW_ENGINE -> {
                if (!parkedControlsAllowed) parkedOnlyLockout() else controller.showEngineDetails()
            }
            HudAction.TOGGLE_DIAGNOSTICS -> {
                if (!parkedControlsAllowed) parkedOnlyLockout() else controller.toggleDiagnostics()
            }
            HudAction.TOGGLE_NAVIGATION -> {
                // Navigation remains glanceable while moving. Expansion requires a confirmed stopped speed.
                if (!parkedControlsAllowed) parkedOnlyLockout() else controller.toggleNavigation()
            }
        }
    }

    private fun parkedOnlyLockout() {
        val message = if (latestState.vehicleMoving == null) {
            "Detailed controls require a confirmed stopped vehicle. Speed is unavailable; use voice."
        } else {
            "Detailed controls are locked while the vehicle is moving. Use voice."
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startVoiceCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is unavailable on this device.", Toast.LENGTH_SHORT).show()
            return
        }

        // Prevent two microphone owners from competing while a HUD command is captured.
        stopService(Intent(this, WakeWordService::class.java))
        speechRecognizer?.destroy()
        telemetry?.setListening(true)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    telemetry?.setListening(false)
                    Toast.makeText(this@DrivingHudActivity, "I couldn't capture that command.", Toast.LENGTH_SHORT).show()
                    restartWakeListenerIfAllowed()
                }

                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    telemetry?.setListening(false)
                    if (transcript.isNotBlank()) handleVoiceCommand(transcript)
                    restartWakeListenerIfAllowed()
                }
            })
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 850L)
            )
        }
    }

    private fun handleVoiceCommand(transcript: String) {
        if (telemetry?.applyVoiceCommand(transcript) == true) return
        val normalized = transcript.lowercase(Locale.US).trim()
        when {
            normalized.startsWith("navigate to ") -> {
                val destination = transcript.substringAfter("navigate to ", "").trim()
                if (destination.isNotBlank()) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")))
                }
            }
            normalized == "exit driving mode" || normalized == "close driving mode" -> finish()
            else -> Toast.makeText(this, "Command not available in Driving HUD: $transcript", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartWakeListenerIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findSingleKnownObdAddress(): String? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return null
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return null
        val candidates = adapter.bondedDevices.orEmpty().filter { device ->
            val name = device.name.orEmpty().lowercase(Locale.US)
            OBD_NAME_MARKERS.any(name::contains)
        }
        return candidates.singleOrNull()?.address
    }

    companion object {
        const val EXTRA_OBD_ADDRESS = "obd_address"
        const val ACTION_OPEN = "com.icarusalmighty.app.OPEN_DRIVING_HUD"
        private val OBD_NAME_MARKERS = listOf(
            "obd", "elm327", "elm 327", "obdlink", "vgate", "veepeak", "gearworks", "carista", "blue driver"
        )
    }
}
