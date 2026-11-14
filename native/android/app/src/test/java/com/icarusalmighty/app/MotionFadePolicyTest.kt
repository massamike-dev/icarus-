package com.icarusalmighty.app
import com.icarusalmighty.app.driving.MotionFadePolicy
import org.junit.Assert.*
import org.junit.Test
class MotionFadePolicyTest {
    private fun show(p: MotionFadePolicy) { repeat(100) { p.step(0.0, 0.0, 0.01, true) } }
    @Test fun settlesBeforeAppearing() {
        val p = MotionFadePolicy(); repeat(40) { p.step(0.0, 0.0, 0.01, true) }
        assertEquals(0f, p.alpha, 0f); show(p); assertEquals(1f,p.alpha,0f)
    }
    @Test fun staysHiddenWhileLookingAway() {
        val p=MotionFadePolicy(); show(p)
        repeat(100) { p.step(20.0,0.0,0.01,true) }; assertEquals(0f,p.alpha,0f)
        repeat(100) { p.step(9.0,0.0,0.01,true) }; assertEquals(0f,p.alpha,0f)
        show(p); assertEquals(1f,p.alpha,0f)
    }
    @Test fun fastMotionHidesAndJitterDoesNot() {
        val p=MotionFadePolicy(); show(p)
        repeat(20) { p.step(2.0,5.0,0.01,true) }; assertEquals(1f,p.alpha,0f)
        repeat(10) { p.step(2.0,60.0,0.01,true) }; assertEquals(0f,p.alpha,0f)
    }
    @Test fun staleOrInvalidInputHidesImmediately() {
        val p=MotionFadePolicy(); show(p); p.step(0.0,0.0,0.01,false); assertEquals(0f,p.alpha,0f)
        show(p); p.step(0.0,0.0,0.2,true); assertEquals(0f,p.alpha,0f)
        show(p); p.step(Double.NaN,0.0,0.01,true); assertEquals(0f,p.alpha,0f)
    }
}
