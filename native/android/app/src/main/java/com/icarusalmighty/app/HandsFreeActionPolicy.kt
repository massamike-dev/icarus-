package com.icarusalmighty.app

/** Deterministic shortcuts keep their behavior; untrusted model proposals never silently mutate settings. */
object HandsFreeActionPolicy {
    fun requiresConfirmation(action: String, modelInterpreted: Boolean): Boolean =
        action in setOf("make_call", "navigate_to", "set_timer") ||
            (modelInterpreted && action !in setOf("get_battery", "cancel"))
}

/** Resolved labels, rather than the recognizer's raw text, are spoken to the user. */
object HandsFreeAppLaunch {
    fun <T> resolve(name: String, candidates: List<T>, label: (T) -> String): T? =
        candidates.filter { label(it).equals(name.trim(), ignoreCase = true) }.singleOrNull()

    fun announcement(displayName: String) = "Opening ${displayName.trim()}."
}

/** A launch may proceed only after microphone readiness, the grace period, and final silence.
 * Tokens fence delayed callbacks from earlier captures; cancellation is terminal.
 */
class AppLaunchCancellationGate {
    companion object { const val WINDOW_MS = 3000L }
    private var generation = 0L
    private var active: Long? = null
    private var ready = false
    private var elapsed = false
    private var speechObserved = false

    fun start(): Long = (++generation).also { active = it; ready = false; elapsed = false; speechObserved = false }
    fun microphoneReady(id: Long): Boolean {
        if (active != id || ready) return false
        ready = true
        return true
    }
    fun heardSpeech(id: Long) { if (active == id) speechObserved = true }
    fun awaitingSpeechResult(id: Long) = active == id && speechObserved
    fun windowElapsed(id: Long): Boolean {
        if (active != id || !ready || elapsed) return false
        elapsed = true
        return true
    }
    fun finishWithSilence(id: Long): Boolean {
        if (active != id) return false
        val allowed = ready && elapsed && !speechObserved
        cancel()
        return allowed
    }
    fun cancel() { active = null; ready = false; elapsed = false; speechObserved = false }
}
