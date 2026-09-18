package com.icarusalmighty.app

/** Both controls make matching easier as sensitivity increases. */
internal data class WakeDetectionSettings(val score: Float, val threshold: Float) {
    companion object {
        fun fromSensitivity(value: Int): WakeDetectionSettings {
            val offset = value.coerceIn(25, 90) - 60
            // Preserve the shipped keyword's effective values at the default setting.
            return WakeDetectionSettings(1.8f + offset * 0.02f, 0.30f - offset * 0.004f)
        }
    }
}
