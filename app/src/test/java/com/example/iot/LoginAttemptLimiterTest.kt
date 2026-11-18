package com.example.iot

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginAttemptLimiterTest {
    @Test
    fun locksAfterMaximumFailedAttempts() {
        val limiter = LoginAttemptLimiter()

        repeat(2) { index ->
            assertEquals(2 - index, limiter.recordFailure(1000L + index).remainingAttempts)
        }

        val result = limiter.recordFailure(2000L)

        assertEquals(0, result.remainingAttempts)
        assertEquals(60_000L, result.lockoutMillis)
        assertEquals(59_000L, limiter.recordFailure(3000L).lockoutMillis)
    }

    @Test
    fun allowsNewAttemptsAfterLockoutExpires() {
        val limiter = LoginAttemptLimiter()
        repeat(3) { limiter.recordFailure(1000L) }

        val result = limiter.recordFailure(61_000L)

        assertEquals(2, result.remainingAttempts)
        assertEquals(0L, result.lockoutMillis)
    }
}
