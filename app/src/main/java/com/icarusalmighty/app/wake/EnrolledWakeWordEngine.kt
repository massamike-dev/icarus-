package com.icarusalmighty.app.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class EnrolledWakeWordEngine(private val context: Context) : WakeWordEngine {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null

    override fun start(onWake: () -> Unit): Result<Unit> = runCatching {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission missing" }
        val templates = WakeTemplateStore(context).load()
        check(templates.size >= 3) { "Enroll Hey ICARUS at least three times" }
        if (!running.compareAndSet(false, true)) return Result.success(Unit)
        val minimum = AudioRecord.getMinBufferSize(AcousticFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AcousticFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096)).also { it.startRecording() }
        thread(name = "icarus-wake", isDaemon = true) {
            val window = ShortArray(AcousticFeatures.WINDOW_SAMPLES)
            var filled = 0
            var cooldownUntil = 0L
            while (running.get()) {
                val read = audioRecord?.read(window, filled, window.size - filled) ?: break
                if (read <= 0) continue
                filled += read
                if (filled == window.size) {
                    if (System.currentTimeMillis() >= cooldownUntil) {
                        val features = AcousticFeatures.extract(window)
                        val score = templates.minOf { AcousticFeatures.distance(features, it) }
                        if (score < MATCH_THRESHOLD) {
                            cooldownUntil = System.currentTimeMillis() + 5000
                            running.set(false)
                            runCatching { audioRecord?.stop() }
                            audioRecord?.release(); audioRecord = null
                            Handler(Looper.getMainLooper()).post(onWake)
                            return@thread
                        }
                    }
                    window.copyInto(window, 0, window.size / 2, window.size)
                    filled = window.size / 2
                }
            }
            runCatching { audioRecord?.stop() }
            audioRecord?.release(); audioRecord = null
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        audioRecord?.release(); audioRecord = null
    }

    companion object { private const val MATCH_THRESHOLD = 0.72f }
}
