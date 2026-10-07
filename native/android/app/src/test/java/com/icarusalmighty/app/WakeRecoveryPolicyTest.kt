package com.icarusalmighty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeRecoveryPolicyTest {
    @Test fun failuresBackOffAndCap() {
        val policy = WakeRecoveryPolicy(initialDelayMs = 1_000, maximumDelayMs = 8_000)

        assertEquals(1_000L, policy.recordFailure())
        assertEquals(2_000L, policy.recordFailure())
        assertEquals(4_000L, policy.recordFailure())
        assertEquals(8_000L, policy.recordFailure())
        assertEquals(8_000L, policy.recordFailure())
        assertEquals(5, policy.snapshot().consecutiveFailures)
    }

    @Test fun aStableMinuteResetsTheBackoff() {
        val policy = WakeRecoveryPolicy(healthyResetMs = 60_000)
        policy.recordFailure()
        policy.recordFailure()
        policy.recordHealthy(10_000)
        policy.recordHealthy(70_000)

        assertEquals(1_000L, policy.recordFailure())
    }

    @Test fun briefHealthyPeriodsDoNotHideRepeatedFailures() {
        val policy = WakeRecoveryPolicy(healthyResetMs = 60_000)
        policy.recordFailure()
        policy.recordHealthy(10_000)
        policy.recordHealthy(69_999)

        assertEquals(2_000L, policy.recordFailure())
    }
}
