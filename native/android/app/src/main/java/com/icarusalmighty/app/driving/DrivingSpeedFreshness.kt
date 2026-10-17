package com.icarusalmighty.app.driving

import kotlin.math.roundToInt

/**
 * Pure speed-evidence policy. All times are from the same monotonic elapsed-realtime clock.
 * A callback, JSON receipt, or rounded display value is not fresh stopped-vehicle evidence.
 * The controller owns this object on the main thread; it never calls it from the OBD worker.
 */
class DrivingSpeedFreshness {
    enum class Source { GPS, OBD }

    data class Reading(
        val speedMph: Int? = null,
        val vehicleMoving: Boolean? = null,
        val validUntilElapsedRealtimeMs: Long? = null,
        val source: Source? = null,
    )

    private data class Sample(val speedMph: Double, val observedAtElapsedRealtimeMs: Long)
    private class Input {
        var sample: Sample? = null
        // Missing/invalid/disconnected input must not erase fresh evidence of motion and
        // thereby let the other source's zero unlock controls. This veto keeps its old deadline.
        var movementVeto: Sample? = null
        var newestObservationMs: Long = Long.MIN_VALUE
    }

    private val gpsInputs = mutableMapOf<String, Input>()
    private var gpsInvalidatedThroughMs = Long.MIN_VALUE
    private val obd = Input()

    fun recordGps(
        speedMph: Double?,
        observedAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
        provider: String = "gps",
    ) {
        val input = gpsInputs.getOrPut(provider) { Input().apply { newestObservationMs = gpsInvalidatedThroughMs } }
        record(input, speedMph, observedAtElapsedRealtimeMs, nowElapsedRealtimeMs)
    }

    fun recordObd(speedMph: Double?, observedAtElapsedRealtimeMs: Long, nowElapsedRealtimeMs: Long) =
        record(obd, speedMph, observedAtElapsedRealtimeMs, nowElapsedRealtimeMs)

    // Reject in-flight and cached samples measured before permission/provider/disconnection loss.
    fun clearGps(invalidatedAtElapsedRealtimeMs: Long) {
        gpsInvalidatedThroughMs = maxOf(gpsInvalidatedThroughMs, invalidatedAtElapsedRealtimeMs)
        gpsInputs.values.forEach { clear(it, invalidatedAtElapsedRealtimeMs) }
    }
    fun clearObd(invalidatedAtElapsedRealtimeMs: Long) = clear(obd, invalidatedAtElapsedRealtimeMs)

    private fun clear(input: Input, invalidatedAtMs: Long) {
        input.sample = null
        input.newestObservationMs = maxOf(input.newestObservationMs, invalidatedAtMs)
    }

    fun current(nowElapsedRealtimeMs: Long): Reading {
        val inputs = listOf(Source.OBD to obd) + gpsInputs.values.map { Source.GPS to it }
        val candidates = inputs.mapNotNull { (source, input) ->
            input.sample?.takeIf { isFresh(it.observedAtElapsedRealtimeMs, nowElapsedRealtimeMs) }
                ?.let { source to it }
        }
        // Any fresh positive raw speed wins over a conflicting zero, even if it rounds to 0 MPH.
        val selected = candidates.filter { it.second.speedMph > 0.0 }.maxByOrNull { it.second.speedMph }
            ?: candidates.firstOrNull() ?: return Reading()
        val recentMotion = inputs.any { (_, input) ->
            input.movementVeto?.let { isFresh(it.observedAtElapsedRealtimeMs, nowElapsedRealtimeMs) } == true
        }
        return Reading(
            speedMph = selected.second.speedMph.roundToInt(),
            vehicleMoving = selected.second.speedMph > 0.0 || recentMotion,
            validUntilElapsedRealtimeMs = selected.second.observedAtElapsedRealtimeMs + MAX_AGE_MS,
            source = selected.first,
        )
    }

    private fun record(input: Input, speedMph: Double?, observedAtMs: Long, nowMs: Long) {
        // A delayed positive observation remains a conservative veto even when a newer
        // missing/invalid packet already advanced the watermark. Never extend its original age.
        if (isFresh(observedAtMs, nowMs) && speedMph != null && speedMph.isFinite() && speedMph > 0.0 &&
            observedAtMs >= (input.movementVeto?.observedAtElapsedRealtimeMs ?: Long.MIN_VALUE)) {
            input.movementVeto = Sample(speedMph, observedAtMs)
        }
        // Out-of-order delivery cannot discard newer display evidence.
        if (observedAtMs < input.newestObservationMs) return
        if (!isFresh(observedAtMs, nowMs)) {
            input.sample = null
            return
        }
        if (observedAtMs == input.newestObservationMs) {
            // Identical delivery does not extend life; conflicting data for that time fails closed.
            if (input.sample?.speedMph != speedMph) {
                input.sample = null
            }
            return
        }
        input.newestObservationMs = observedAtMs
        input.sample = speedMph?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { Sample(it, observedAtMs) }
        input.sample?.let { sample ->
            // Only a valid, newer zero from this same input clears its movement veto early.
            input.movementVeto = sample.takeIf { it.speedMph > 0.0 }
        }
    }

    companion object {
        const val MAX_AGE_MS = 3_000L
        const val EXPIRY_CHECK_INTERVAL_MS = 500L

        fun isFresh(observedAtElapsedRealtimeMs: Long?, nowElapsedRealtimeMs: Long): Boolean =
            observedAtElapsedRealtimeMs != null && observedAtElapsedRealtimeMs > 0L &&
                nowElapsedRealtimeMs >= observedAtElapsedRealtimeMs &&
                nowElapsedRealtimeMs - observedAtElapsedRealtimeMs < MAX_AGE_MS &&
                observedAtElapsedRealtimeMs <= Long.MAX_VALUE - MAX_AGE_MS
    }
}
