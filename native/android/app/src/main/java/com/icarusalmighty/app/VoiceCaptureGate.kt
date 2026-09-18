package com.icarusalmighty.app

/** Each Android recognizer may deliver one terminal result; replaced callbacks are stale. */
class VoiceCaptureGate {
    private var generation = 0L
    private var active: Long? = null

    fun start(): Long = (++generation).also { active = it }

    fun consume(id: Long): Boolean {
        if (active != id) return false
        active = null
        return true
    }

    fun cancel() { active = null }
}
