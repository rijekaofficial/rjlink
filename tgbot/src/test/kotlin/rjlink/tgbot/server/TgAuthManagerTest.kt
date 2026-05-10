package rjlink.tgbot.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TgAuthManagerTest {

    private fun newManager() = TgAuthManager(InMemoryTgBindingStore(), TgCodeGenerator())

    // --- code lifecycle ---

    @Test
    fun `getOrCreateCode is idempotent for the same chat`() {
        val m = newManager()
        val first = m.getOrCreateCode(42L)
        val second = m.getOrCreateCode(42L)
        assertEquals(first, second)
    }

    @Test
    fun `regenerateCode produces a different code`() {
        val m = newManager()
        val before = m.getOrCreateCode(42L)
        val after = m.regenerateCode(42L)
        assertNotEquals(before, after)
        // After regenerate, getOrCreate returns the new code.
        assertEquals(after, m.getOrCreateCode(42L))
    }

    @Test
    fun `bind by old code fails after regenerate`() {
        val m = newManager()
        val old = m.getOrCreateCode(7L)
        m.regenerateCode(7L)
        assertFalse(m.bindByCode("alice", old))
    }

    @Test
    fun `bind by new code succeeds after regenerate`() {
        val m = newManager()
        m.getOrCreateCode(7L)
        val fresh = m.regenerateCode(7L)
        assertTrue(m.bindByCode("alice", fresh))
        assertEquals(7L, m.findChatId("alice"))
    }

    // --- existing bindings outlive code rotation ---

    @Test
    fun `existing bindings keep working after regenerate`() {
        val m = newManager()
        val code = m.getOrCreateCode(99L)
        m.bindByCode("alice", code)
        m.regenerateCode(99L)

        // alice still resolves to chat 99 even though the code is gone.
        assertEquals(99L, m.findChatId("alice"))
    }

    // --- multi-client per code ---

    @Test
    fun `several nicks can bind through the same code`() {
        val m = newManager()
        val code = m.getOrCreateCode(123L)
        assertTrue(m.bindByCode("alice", code))
        assertTrue(m.bindByCode("bob", code))
        assertTrue(m.bindByCode("eve", code))

        assertEquals(123L, m.findChatId("alice"))
        assertEquals(123L, m.findChatId("bob"))
        assertEquals(123L, m.findChatId("eve"))
    }

    // --- generic ---

    @Test
    fun `bind by unknown code returns false`() {
        assertFalse(newManager().bindByCode("alice", "UNKNOWN1"))
    }

    @Test
    fun `unbind removes binding`() {
        val m = newManager()
        val code = m.getOrCreateCode(7L)
        m.bindByCode("alice", code)
        assertTrue(m.unbind("alice"))
        assertNull(m.findChatId("alice"))
    }
}
