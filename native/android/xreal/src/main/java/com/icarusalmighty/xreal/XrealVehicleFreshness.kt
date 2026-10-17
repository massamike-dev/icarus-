package com.icarusalmighty.xreal

/** Receiver-side liveness policy. Clock values must come from the same device boot. */
object XrealVehicleFreshness {
    const val MAX_SNAPSHOT_AGE_MS = 5_000L

    fun deadlineFor(observedAtElapsedRealtimeMs: Long?): Long? = observedAtElapsedRealtimeMs
        ?.takeIf { it >= 0L && it <= Long.MAX_VALUE - MAX_SNAPSHOT_AGE_MS }
        ?.plus(MAX_SNAPSHOT_AGE_MS)

    fun sanitize(state: XrealHudState, nowElapsedRealtimeMs: Long): XrealHudState {
        if (!snapshotIsFresh(state, nowElapsedRealtimeMs)) {
            return XrealHudState(
                assistantStatus = state.assistantStatus,
                primaryText = state.primaryText,
                batteryPercent = state.batteryPercent,
                sourceLabel = "LIVE TELEMETRY UNAVAILABLE",
                roadDetail = "REOPEN XREAL HUD ON PHONE",
            )
        }
        val speedDeadline = state.speedValidUntilElapsedRealtimeMs
        return if (speedDeadline == null || speedDeadline <= nowElapsedRealtimeMs ||
            speedDeadline > state.vehicleSnapshotValidUntilElapsedRealtimeMs!!
        ) {
            state.copy(speedMph = null, vehicleMoving = null, speedValidUntilElapsedRealtimeMs = null)
        } else state
    }

    /** At most two one-shot expiry callbacks per snapshot: speed, then transport. */
    fun nextExpiryAt(state: XrealHudState, nowElapsedRealtimeMs: Long): Long? {
        if (!snapshotIsFresh(state, nowElapsedRealtimeMs)) return null
        val snapshotDeadline = state.vehicleSnapshotValidUntilElapsedRealtimeMs!!
        val speedDeadline = state.speedValidUntilElapsedRealtimeMs?.takeIf {
            (state.speedMph != null || state.vehicleMoving != null) &&
                it > nowElapsedRealtimeMs && it <= snapshotDeadline
        }
        return minOf(snapshotDeadline, speedDeadline ?: snapshotDeadline)
    }

    private fun snapshotIsFresh(state: XrealHudState, nowElapsedRealtimeMs: Long): Boolean {
        val observedAt = state.vehicleSnapshotAtElapsedRealtimeMs ?: return false
        val deadline = state.vehicleSnapshotValidUntilElapsedRealtimeMs ?: return false
        return nowElapsedRealtimeMs >= 0L && observedAt >= 0L && observedAt <= nowElapsedRealtimeMs &&
            deadline > nowElapsedRealtimeMs && deadline > observedAt &&
            deadline - observedAt <= MAX_SNAPSHOT_AGE_MS
    }
}
