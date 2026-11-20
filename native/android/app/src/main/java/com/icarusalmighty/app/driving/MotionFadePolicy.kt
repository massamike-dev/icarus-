package com.icarusalmighty.app.driving

/** Phone-rotation prototype. Angles are degrees; time is monotonic seconds. */
class MotionFadePolicy {
    var alpha = 0f
        private set
    private var stable = 0.0
    private var hidden = true
    fun reset() { alpha = 0f; stable = 0.0; hidden = true }
    fun step(angle: Double, speed: Double, dt: Double, valid: Boolean): Float {
        if (!valid || !angle.isFinite() || !speed.isFinite() || !dt.isFinite() || dt <= 0 || dt > 0.1) {
            reset(); return alpha
        }
        if (angle >= 12 || speed >= 45) { hidden = true; stable = 0.0 }
        if (hidden) {
            stable = if (angle <= 7 && speed <= 12) stable + dt else 0.0
            if (stable >= 0.5) hidden = false
        }
        alpha = if (hidden) (alpha - (dt / 0.08).toFloat()).coerceAtLeast(0f)
                else (alpha + (dt / 0.3).toFloat()).coerceAtMost(1f)
        return alpha
    }
}
