package com.example.iot

internal data class LoginAttemptResult(
    val remainingAttempts: Int,
    val lockoutMillis: Long
)

internal class LoginAttemptLimiter(
    private val maxAttempts: Int = 3,
    private val lockoutDurationMillis: Long = 60_000
) {
    private var failedAttempts = 0
    private var lockedUntilMillis = 0L

    fun recordFailure(nowMillis: Long): LoginAttemptResult {
        if (nowMillis < lockedUntilMillis) {
            return LoginAttemptResult(0, lockedUntilMillis - nowMillis)
        }

        failedAttempts += 1
        if (failedAttempts >= maxAttempts) {
            failedAttempts = 0
            lockedUntilMillis = nowMillis + lockoutDurationMillis
            return LoginAttemptResult(0, lockoutDurationMillis)
        }

        return LoginAttemptResult(maxAttempts - failedAttempts, 0)
    }

    fun reset() {
        failedAttempts = 0
        lockedUntilMillis = 0L
    }
}
