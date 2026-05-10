package rjlink.server.db

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ExposedBanStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: ExposedBanStore

    @BeforeEach
    fun setup() {
        val dbPath = tempDir.resolve("test.db").toString()
        DatabaseFactory.init(dbPath)
        store = ExposedBanStore()
    }

    @Test
    fun `ban creates entry`() {
        val entry = store.ban("alice", "spam", "admin")
        assertEquals("alice", entry.nick)
        assertEquals("spam", entry.reason)
        assertEquals("admin", entry.bannedBy)
        assertTrue(store.isBanned("alice"))
    }

    @Test
    fun `find returns entry`() {
        store.ban("bob", "toxic", null)
        val found = store.find("bob")
        assertNotNull(found)
        assertEquals("bob", found!!.nick)
        assertEquals("toxic", found.reason)
        assertNull(found.bannedBy)
    }

    @Test
    fun `unban removes entry`() {
        store.ban("charlie", "test", null)
        assertTrue(store.unban("charlie"))
        assertFalse(store.isBanned("charlie"))
    }

    @Test
    fun `unban returns false for unknown nick`() {
        assertFalse(store.unban("nobody"))
    }

    @Test
    fun `listAll returns sorted entries`() {
        store.ban("aaa", "r1", null)
        store.ban("zzz", "r2", null)
        val all = store.listAll()
        assertEquals(2, all.size)
        assertEquals("aaa", all[0].nick)
        assertEquals("zzz", all[1].nick)
    }
}
