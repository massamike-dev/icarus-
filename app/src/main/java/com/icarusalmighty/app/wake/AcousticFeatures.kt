package com.icarusalmighty.app.wake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

object AcousticFeatures {
    const val SAMPLE_RATE = 16_000
    const val WINDOW_SAMPLES = SAMPLE_RATE * 2
    private const val FRAME = 320
    private const val HOP = 160
    private val frequencies = floatArrayOf(250f, 350f, 500f, 700f, 950f, 1250f, 1650f, 2150f, 2800f, 3600f, 4600f, 5900f)

    fun extract(samples: ShortArray): Array<FloatArray> {
        if (samples.size < FRAME) return emptyArray()
        val result = ArrayList<FloatArray>()
        var offset = 0
        while (offset + FRAME <= samples.size) {
            val frame = FloatArray(FRAME) { i -> samples[offset + i] / 32768f }
            val vector = FloatArray(frequencies.size + 2)
            var energy = 0f
            var crossings = 0
            for (i in frame.indices) {
                energy += frame[i] * frame[i]
                if (i > 0 && (frame[i] >= 0) != (frame[i - 1] >= 0)) crossings++
            }
            vector[0] = ln(1e-6f + sqrt(energy / FRAME))
            vector[1] = crossings.toFloat() / FRAME
            frequencies.forEachIndexed { index, hz -> vector[index + 2] = ln(1e-6f + goertzel(frame, hz)) }
            normalize(vector)
            result += vector
            offset += HOP
        }
        return result.toTypedArray()
    }

    private fun goertzel(frame: FloatArray, hz: Float): Float {
        val coefficient = (2.0 * cos(2.0 * PI * hz / SAMPLE_RATE)).toFloat()
        var previous = 0f; var previous2 = 0f
        frame.forEach { sample ->
            val value = sample + coefficient * previous - previous2
            previous2 = previous; previous = value
        }
        return sqrt((previous2 * previous2 + previous * previous - coefficient * previous * previous2).coerceAtLeast(0f)) / frame.size
    }

    private fun normalize(values: FloatArray) {
        val mean = values.average().toFloat()
        val scale = sqrt(values.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / values.size).coerceAtLeast(1e-4f)
        for (i in values.indices) values[i] = (values[i] - mean) / scale
    }

    fun distance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE
        val previous = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }.also { it[0] = 0f }
        val current = FloatArray(b.size + 1)
        for (i in a.indices) {
            current.fill(Float.POSITIVE_INFINITY)
            for (j in b.indices) {
                var local = 0f
                for (k in a[i].indices) { val d = a[i][k] - b[j][k]; local += d * d }
                current[j + 1] = sqrt(local / a[i].size) + minOf(current[j], previous[j + 1], previous[j])
            }
            current.copyInto(previous)
        }
        return previous[b.size] / (a.size + b.size)
    }
}
