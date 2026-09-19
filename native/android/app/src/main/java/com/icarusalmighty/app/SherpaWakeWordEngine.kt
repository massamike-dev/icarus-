package com.icarusalmighty.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.math.abs
import kotlin.concurrent.thread

class SherpaWakeWordEngine(private val context: Context, private val onFailure: (String) -> Unit = {}) : WakeWordEngine {
    private class Capture(val audio: AudioRecord, val kws: KeywordSpotter, val stream: OnlineStream) {
        val running = AtomicBoolean(true)
        lateinit var worker: Thread
    }

    private val lifecycleLock = Any()
    @Volatile private var capture: Capture? = null
    @Volatile private var modelLoaded = false
    @Volatile private var recorderActive = false
    @Volatile private var lastAudioAt = 0L
    @Volatile private var lastAudioElapsed = 0L
    @Volatile private var audioLevel = 0f
    @Volatile private var signalReceived = false
    @Volatile private var microphoneSilenced = false
    @Volatile private var silenceStateKnown = false
    @Volatile private var microphoneSource = "automatic"
    @Volatile private var lastDetectedAt = 0L
    @Volatile private var detectionCount = 0L

    override fun start(onDetected: () -> Unit): Result<Unit> = runCatching {
        synchronized(lifecycleLock) {
            capture?.let {
                check(it.running.get()) { "The previous microphone session is still stopping. Try again." }
                return@runCatching
            }
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is required for Hey ICARUS."
            }
            val modelDir = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
            val preferences = context.getSharedPreferences("icarus_voice", Context.MODE_PRIVATE)
            val settings = WakeDetectionSettings.fromSensitivity(preferences.getInt("sensitivity", 60))
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        decoder = "$modelDir/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        joiner = "$modelDir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    modelType = "zipformer2",
                ),
                keywordsFile = "$modelDir/keywords.txt",
                keywordsScore = settings.score,
                keywordsThreshold = settings.threshold,
                numTrailingBlanks = 2,
            )
            var kws: KeywordSpotter? = null
            var kwsStream: OnlineStream? = null
            var audio: AudioRecord? = null
            try {
                val newSpotter = KeywordSpotter(context.assets, config).also { kws = it }
                val newStream = newSpotter.createStream().also { kwsStream = it }
                val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBytes > 0) { "This phone could not initialize the microphone." }
                microphoneSource = preferences.getString("microphone_source", "automatic") ?: "automatic"
                val audioSource = when (microphoneSource) {
                    "phone" -> MediaRecorder.AudioSource.MIC
                    "bluetooth" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                }
                val newAudio = AudioRecord(
                    audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBytes * 2,
                ).also { audio = it }
                check(newAudio.state == AudioRecord.STATE_INITIALIZED) { "This phone could not initialize the microphone." }
                newAudio.startRecording()
                check(newAudio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone opened but did not begin recording." }
                lastAudioAt = 0L
                lastAudioElapsed = 0L
                audioLevel = 0f
                signalReceived = false
                microphoneSilenced = false
                silenceStateKnown = false
                modelLoaded = true
                recorderActive = true
                val next = Capture(newAudio, newSpotter, newStream)
                next.worker = thread(start = false, name = "icarus-sherpa-wake", isDaemon = true) {
                    processAudio(next, onDetected)
                }
                capture = next
                next.worker.start()
            } catch (error: Throwable) {
                // Constructor/start failures must not leak the microphone or native model.
                capture = null
                recorderActive = false
                modelLoaded = false
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
                runCatching { kwsStream?.release() }
                runCatching { kws?.release() }
                throw error
            }
        }
    }

    private fun processAudio(current: Capture, onDetected: () -> Unit) {
        val pcm = ShortArray(1600)
        var detected = false
        var failure: String? = null
        var lastPolicyCheck = Long.MIN_VALUE
        try {
            while (current.running.get()) {
                val count = current.audio.read(pcm, 0, pcm.size)
                if (!current.running.get()) break
                if (count < 0) error("Microphone read failed ($count).")
                if (count == 0) continue
                var peak = 0
                for (i in 0 until count) peak = maxOf(peak, abs(pcm[i].toInt()))
                audioLevel = (peak / 32768f).coerceIn(0f, 1f)
                if (peak > 0) signalReceived = true
                lastAudioAt = System.currentTimeMillis()
                lastAudioElapsed = SystemClock.elapsedRealtime()
                if (lastPolicyCheck == Long.MIN_VALUE || lastAudioElapsed - lastPolicyCheck >= 500L) {
                    // Android may return valid zero-filled buffers when another app wins
                    // microphone access. A successful read is not proof ICARUS can hear.
                    runCatching { current.audio.activeRecordingConfiguration }.getOrNull()?.let {
                        microphoneSilenced = it.isClientSilenced
                        silenceStateKnown = true
                    }
                    lastPolicyCheck = lastAudioElapsed
                }
                if (microphoneSilenced) continue
                current.stream.acceptWaveform(FloatArray(count) { pcm[it] / 32768.0f }, SAMPLE_RATE)
                while (current.running.get() && current.kws.isReady(current.stream)) {
                    current.kws.decode(current.stream)
                    if (current.kws.getResult(current.stream).keyword.isNotBlank()) {
                        lastDetectedAt = System.currentTimeMillis()
                        detectionCount += 1
                        detected = true
                        current.running.set(false)
                        break
                    }
                }
            }
        } catch (error: Exception) {
            if (current.running.getAndSet(false)) failure = "Wake audio stopped: ${error.message ?: "microphone unavailable"}"
        } finally {
            // Only the owning worker releases resources, after it has stopped using
            // them. stop() must never free a decoder still running on this thread.
            current.running.set(false)
            runCatching { current.audio.stop() }
            runCatching { current.audio.release() }
            runCatching { current.stream.release() }
            runCatching { current.kws.release() }
            synchronized(lifecycleLock) {
                if (capture === current) {
                    capture = null
                    recorderActive = false
                    modelLoaded = false
                    audioLevel = 0f
                }
            }
        }
        // Release the microphone before command speech recognition begins.
        if (detected) onDetected()
        failure?.let(onFailure)
    }

    override fun stop() {
        val current = synchronized(lifecycleLock) {
            capture?.also { it.running.set(false) }
        } ?: return
        runCatching { current.audio.stop() }
        current.worker.interrupt()
        if (Thread.currentThread() !== current.worker) {
            runCatching { current.worker.join(1500) }
        }
    }

    fun diagnostics(): JSONObject {
        val elapsed = lastAudioElapsed
        return JSONObject()
            .put("modelLoaded", modelLoaded)
            .put("recorderActive", recorderActive)
            .put("engineRunning", capture?.running?.get() == true)
            .put("audioReceived", lastAudioAt != 0L)
            .put("signalReceived", signalReceived)
            .put("microphoneSilenced", microphoneSilenced)
            .put("silenceStateKnown", silenceStateKnown)
            .put("microphoneSource", microphoneSource)
            .put("lastAudioAt", lastAudioAt)
            .put("audioAgeMs", if (elapsed == 0L) -1L else (SystemClock.elapsedRealtime() - elapsed).coerceAtLeast(0L))
            .put("audioLevel", audioLevel.toDouble())
            .put("lastDetectedAt", lastDetectedAt)
            .put("detectionCount", detectionCount)
    }

    private companion object { const val SAMPLE_RATE = 16_000 }
}
