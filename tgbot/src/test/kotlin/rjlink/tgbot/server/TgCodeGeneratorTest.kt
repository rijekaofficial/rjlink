package rjlink.tgbot.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TgCodeGeneratorTest {

    @Test
    fun `random returns 8 character codes`() {
        val gen = TgCodeGenerator()
        repeat(20) { assertEquals(8, gen.random().length) }
    }

    @Test
    fun `random uses only the safe alphabet`() {
        val gen = TgCodeGenerator()
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toSet()
        repeat(100) {
            val code = gen.random()
            assertTrue(code.all { it in alphabet }, "code '$code' contains illegal char")
        }
    }

    @Test
    fun `random produces distinct codes overwhelmingly often`() {
        val gen = TgCodeGenerator()
        val seen = HashSet<String>()
        repeat(1_000) { seen.add(gen.random()) }
        // 32^8 ≈ 1.1e12 distinct codes; collisions across 1k samples are practically zero.
        assertTrue(seen.size >= 999, "expected near-1000 distinct codes, got ${seen.size}")
    }

    @Test
    fun `two generators produce different codes`() {
        // Sanity: two independently-seeded SecureRandom instances should not align.
        val a = TgCodeGenerator().random()
        val b = TgCodeGenerator().random()
        assertNotEquals(a, b)
    }
}
