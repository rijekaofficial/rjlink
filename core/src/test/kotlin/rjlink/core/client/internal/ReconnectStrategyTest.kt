package rjlink.core.client.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReconnectStrategyTest {

    @Test
    fun `delays follow exponential backoff`() {
        val strategy = ReconnectStrategy(initialDelayMs = 1000, maxDelayMs = 30_000, maxAttempts = 10)
        assertEquals(1_000, strategy.nextDelayMs())
        assertEquals(2_000, strategy.nextDelayMs())
        assertEquals(4_000, strategy.nextDelayMs())
        assertEquals(8_000, strategy.nextDelayMs())
        assertEquals(16_000, strategy.nextDelayMs())
        assertEquals(30_000, strategy.nextDelayMs()) // capped
        assertEquals(30_000, strategy.nextDelayMs())
    }

    @Test
    fun `returns null once attempts are exhausted`() {
        val strategy = ReconnectStrategy(initialDelayMs = 10, maxDelayMs = 100, maxAttempts = 3)
        repeat(3) { strategy.nextDelayMs() }
        assertNull(strategy.nextDelayMs())
    }

    @Test
    fun `reset restarts the sequence`() {
        val strategy = ReconnectStrategy(initialDelayMs = 100, maxDelayMs = 1000, maxAttempts = 5)
        strategy.nextDelayMs()
        strategy.nextDelayMs()
        strategy.reset()
        assertEquals(100, strategy.nextDelayMs())
    }
}
