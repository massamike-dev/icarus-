package com.icarusalmighty.app.wake

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

object WakeEnrollment {
    fun capture(): Array<FloatArray> {
        val minimum = AudioRecord.getMinBufferSize(AcousticFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AcousticFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
        val samples = ShortArray(AcousticFeatures.WINDOW_SAMPLES)
        recorder.startRecording()
        var offset = 0
        while (offset < samples.size) {
            val read = recorder.read(samples, offset, samples.size - offset)
            if (read > 0) offset += read
        }
        recorder.stop(); recorder.release()
        return AcousticFeatures.extract(samples)
    }
}
