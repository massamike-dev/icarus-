package com.icarusalmighty.app.wake

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
import kotlin.concurrent.thread

/** Account-free, on-device open-vocabulary wake-word detector. */
class SherpaWakeWordEngine(private val context: Context) : WakeWordEngine {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    override fun start(onDetected: () -> Unit): Result<Unit> = runCatching {
        if (running.get()) return@runCatching
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission is required for Hey ICARUS." }

        val modelDir = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
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
            keywordsScore = 1.8f,
            keywordsThreshold = 0.30f,
            numTrailingBlanks = 2,
        )

        val newSpotter = KeywordSpotter(context.assets, config)
        val newStream = newSpotter.createStream()
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBytes > 0) { "This phone could not initialize the microphone." }
        val newRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBytes * 2,
        )
        check(newRecorder.state == AudioRecord.STATE_INITIALIZED) {
            "This phone could not initialize the microphone."
        }

        spotter = newSpotter
        stream = newStream
        recorder = newRecorder
        running.set(true)
        newRecorder.startRecording()
        worker = thread(name = "icarus-sherpa-wake", isDaemon = true) {
            processAudio(newRecorder, newSpotter, newStream, onDetected)
        }
    }

    private fun processAudio(
        audioRecord: AudioRecord,
        kws: KeywordSpotter,
        kwsStream: OnlineStream,
        onDetected: () -> Unit,
    ) {
        val pcm = ShortArray(1600)
        try {
            while (running.get()) {
                val count = audioRecord.read(pcm, 0, pcm.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { pcm[it] / 32768.0f }
                kwsStream.acceptWaveform(samples, SAMPLE_RATE)
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

    @Synchronized
    private fun releaseResources() {
        runCatching { recorder?.release() }
        runCatching { stream?.release() }
        runCatching { spotter?.release() }
        recorder = null
        stream = null
        spotter = null
        worker = null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

