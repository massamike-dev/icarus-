package com.icarusalmighty.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    @Volatile private var modelLoaded = false
    @Volatile private var recorderActive = false
    @Volatile private var lastAudioAt = 0L
    @Volatile private var audioLevel = 0f

    override fun start(onDetected: () -> Unit): Result<Unit> = runCatching {
        if (running.get()) return@runCatching
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required for Hey ICARUS."
        }
        val modelDir = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        val preferences = context.getSharedPreferences("icarus_voice", Context.MODE_PRIVATE)
        val sensitivity = preferences.getInt("sensitivity", 60).coerceIn(25, 90)
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
            keywordsScore = 1.35f + ((90 - sensitivity) / 100f),
            keywordsThreshold = 0.42f - ((sensitivity - 25) / 500f),
            numTrailingBlanks = 2,
        )
        val kws = KeywordSpotter(context.assets, config)
        modelLoaded = true
        val kwsStream = kws.createStream()
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "This phone could not initialize the microphone." }
        val requestedSource = preferences.getString("microphone_source", "automatic")
        val audioSource = when (requestedSource) {
            "phone" -> MediaRecorder.AudioSource.MIC
            "bluetooth" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val audio = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBytes * 2,
        )
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "This phone could not initialize the microphone." }
        spotter = kws
        stream = kwsStream
        recorder = audio
        running.set(true)
        audio.startRecording()
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone opened but did not begin recording." }
        recorderActive = true
        worker = thread(name = "icarus-sherpa-wake", isDaemon = true) {
            processAudio(audio, kws, kwsStream, onDetected)
        }
    }

    private fun processAudio(audio: AudioRecord, kws: KeywordSpotter, kwsStream: OnlineStream, onDetected: () -> Unit) {
        val pcm = ShortArray(1600)
        try {
            while (running.get()) {
                val count = audio.read(pcm, 0, pcm.size)
                if (count < 0) error("Microphone read failed ($count).")
                if (count == 0) continue
                var peak = 0
                for (i in 0 until count) peak = maxOf(peak, abs(pcm[i].toInt()))
                audioLevel = (peak / 32768f).coerceIn(0f, 1f)
                lastAudioAt = System.currentTimeMillis()
                kwsStream.acceptWaveform(FloatArray(count) { pcm[it] / 32768.0f }, SAMPLE_RATE)
                while (running.get() && kws.isReady(kwsStream)) {
                    kws.decode(kwsStream)
                    if (kws.getResult(kwsStream).keyword.isNotBlank()) {
                        kws.reset(kwsStream)
                        running.set(false)
                        onDetected()
                        return
                    }
                }
            }
        } catch (error: Exception) {
            if (running.getAndSet(false)) onFailure("Wake audio stopped: ${error.message ?: "microphone unavailable"}")
        } finally {
            releaseResources()
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        worker?.interrupt()
        if (Thread.currentThread() !== worker) worker?.join(500)
        releaseResources()
    }

    @Synchronized private fun releaseResources() {
        recorderActive = false
        runCatching { recorder?.release() }
        runCatching { stream?.release() }
        runCatching { spotter?.release() }
        recorder = null
        stream = null
        spotter = null
        worker = null
    }

    fun diagnostics(): JSONObject = JSONObject()
        .put("modelLoaded", modelLoaded)
        .put("recorderActive", recorderActive)
        .put("lastAudioAt", lastAudioAt)
        .put("audioLevel", audioLevel.toDouble())

    private companion object { const val SAMPLE_RATE = 16_000 }
}
