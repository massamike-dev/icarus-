package com.icarusalmighty.app

interface WakeWordEngine {
    fun start(onDetected: () -> Unit): Result<Unit>
    fun stop()
}