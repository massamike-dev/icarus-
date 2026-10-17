package com.icarusalmighty.xreal

import java.lang.ref.WeakReference

/** Main-thread lifecycle bookkeeping; pending launches can be cancelled before onCreate. */
class XrealHudSession {
    @Volatile var launchPending = false
        private set
    @Volatile var activityOpen = false
        private set
    @Volatile var activeMode: XrealMode? = null
        private set
    /** Last logical display observed by the live Activity; not a glasses identity. */
    @Volatile var actualDisplayId: Int? = null
        private set
    private var closeRequested = false
    // The live app controller owns this callback. Never retain its Activity here.
    private var closedListener: WeakReference<() -> Unit>? = null

    fun setOnClosedListener(listener: (() -> Unit)?) {
        closedListener = listener?.let { WeakReference(it) }
    }

    fun launchRequested() {
        launchPending = true
        closeRequested = false
        if (!activityOpen) actualDisplayId = null
    }

    fun launchFailed() {
        launchPending = false
        if (!activityOpen) actualDisplayId = null
    }

    /** True means this activity must immediately finish a cancelled launch. */
    fun activityCreated(mode: XrealMode? = null): Boolean {
        launchPending = false
        activityOpen = true
        activeMode = mode
        actualDisplayId = null
        return closeRequested
    }

    fun updateActualDisplayId(displayId: Int?) {
        actualDisplayId = displayId?.takeIf { activityOpen && it >= 0 }
    }

    fun requestClose(): Boolean {
        val active = launchPending || activityOpen
        if (active) closeRequested = true
        return active
    }

    fun activityDestroyed() {
        activityOpen = false
        activeMode = null
        actualDisplayId = null
        // A replacement launch may already own the listener/lease while the old
        // Activity finishes. Its onCreate (or launch failure) owns that cleanup.
        if (launchPending) return
        val listener = closedListener?.get()
        closedListener = null
        // Back/system finish releases sensor leases just like an explicit close.
        runCatching { listener?.invoke() }
    }
}
