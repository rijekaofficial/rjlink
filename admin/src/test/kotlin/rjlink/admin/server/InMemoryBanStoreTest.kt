package rjlink.admin.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryBanStoreTest {

    @Test
    fun `ban then unban`() {
        val s = InMemoryBanStore()
        s.ban("alice", "spam", "root")
        assertTrue(s.isBanned("alice"))
        assertEquals("spam", s.find("alice")?.reason)
        assertEquals("root", s.find("alice")?.bannedBy)
        assertTrue(s.unban("alice"))
        assertFalse(s.isBanned("alice"))
        assertNull(s.find("alice"))
    }

    @Test
    fun `unban of unknown nick returns false`() {
        assertFalse(InMemoryBanStore().unban("ghost"))
    }

    @Test
    fun `listAll is sorted by bannedAt`() {
        val s = InMemoryBanStore()
        s.ban("a", "r1", null)
        Thread.sleep(2)
        s.ban("b", "r2", null)
        Thread.sleep(2)
        s.ban("c", "r3", null)
        assertEquals(listOf("a", "b", "c"), s.listAll().map { it.nick })
    }
}
