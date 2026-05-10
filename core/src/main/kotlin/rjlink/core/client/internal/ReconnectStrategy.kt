package rjlink.core.client.internal

/**
 * Exponential backoff: 1s → 2s → 4s → 8s → 16s → 30s, capped at 30s, up to 10 attempts.
 * Not configurable from outside the library.
 */
internal class ReconnectStrategy(
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    val maxAttempts: Int = 10
) {
    private var attempt = 0

    val currentAttempt: Int get() = attempt

    /** @return delay in ms for the next attempt, or null if [maxAttempts] is exhausted. */
    fun nextDelayMs(): Long? {
        if (attempt >= maxAttempts) return null
        val exp = initialDelayMs.toDouble() * Math.pow(2.0, attempt.toDouble())
        attempt += 1
        return exp.toLong().coerceAtMost(maxDelayMs)
    }

    fun reset() { attempt = 0 }
}
