package com.icarusalmighty.app

/**
 * Bounded exponential backoff for the always-on wake listener.
 *
 * Recovery never gives up while the user has listening enabled, but repeated
 * failures settle at a low-frequency retry interval to protect the battery.
 */
class WakeRecoveryPolicy(
    private val initialDelayMs: Long = 1_000L,
    private val maximumDelayMs: Long = 60_000L,
    private val healthyResetMs: Long = 60_000L,
) {
    private var consecutiveFailures = 0
    private var healthySinceMs: Long? = null

    fun recordFailure(): Long {
        healthySinceMs = null
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(30)
        val shift = (consecutiveFailures - 1).coerceAtMost(20)
        return (initialDelayMs * (1L shl shift)).coerceAtMost(maximumDelayMs)
    }

    fun recordHealthy(nowMs: Long) {
        val since = healthySinceMs
        if (since == null) {
            healthySinceMs = nowMs
        } else if (nowMs - since >= healthyResetMs) {
            consecutiveFailures = 0
        }
    }

    fun snapshot(): WakeRecoverySnapshot = WakeRecoverySnapshot(consecutiveFailures)
}

data class WakeRecoverySnapshot(val consecutiveFailures: Int)
