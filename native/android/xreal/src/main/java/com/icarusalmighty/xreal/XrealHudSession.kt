package com.icarusalmighty.xreal

/** Main-thread lifecycle bookkeeping; pending launches can be cancelled before onCreate. */
class XrealHudSession {
    @Volatile var launchPending = false
        private set
    @Volatile var activityOpen = false
        private set
    private var closeRequested = false

    fun launchRequested() {
        launchPending = true
        closeRequested = false
    }

    fun launchFailed() {
        launchPending = false
    }

    /** True means this activity must immediately finish a cancelled launch. */
    fun activityCreated(): Boolean {
        launchPending = false
        activityOpen = true
        return closeRequested
    }

    fun requestClose(): Boolean {
        val active = launchPending || activityOpen
        if (active) closeRequested = true
        return active
    }

    fun activityDestroyed() {
        activityOpen = false
    }
}
