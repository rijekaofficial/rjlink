package rjlink.core.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionManagerTest {

    private fun newManager() = SessionManager(30, 90)

    private suspend fun newSession(manager: SessionManager) =
        manager.createSession(Channel(capacity = Channel.UNLIMITED)) { _, _ -> }

    @Test
    fun `authenticate creates nick binding`() = runTest {
        val manager = newManager()
        val session = newSession(manager)
        assertTrue(manager.authenticate(session, "alice"))
        assertSame(session, manager.findByNick("alice"))
    }

    @Test
    fun `multiple sessions can share a nick`() = runTest {
        val manager = newManager()
        val s1 = newSession(manager)
        val s2 = newSession(manager)
        val s3 = newSession(manager)

        assertTrue(manager.authenticate(s1, "Test"))
        assertTrue(manager.authenticate(s2, "Test"))
        assertTrue(manager.authenticate(s3, "Test"))

        val all = manager.findAllByNick("Test")
        assertEquals(3, all.size)
        assertTrue(s1 in all && s2 in all && s3 in all)
        assertEquals(3, manager.activeSessions().size)
    }

    @Test
    fun `findByNick returns one of several when nick has dupes`() = runTest {
        val manager = newManager()
        val s1 = newSession(manager); val s2 = newSession(manager)
        manager.authenticate(s1, "Test"); manager.authenticate(s2, "Test")

        val any = manager.findByNick("Test")
        assertNotNull(any)
        assertTrue(any === s1 || any === s2)
    }

    @Test
    fun `remove of one session keeps the others`() = runTest {
        val manager = newManager()
        val s1 = newSession(manager); val s2 = newSession(manager)
        manager.authenticate(s1, "Test"); manager.authenticate(s2, "Test")

        manager.remove(s1)
        assertEquals(setOf(s2), manager.findAllByNick("Test").toSet())
        assertTrue(manager.hasNick("Test"))
    }

    @Test
    fun `remove of last session clears the nick`() = runTest {
        val manager = newManager()
        val s1 = newSession(manager)
        manager.authenticate(s1, "solo")
        manager.remove(s1)
        assertNull(manager.findByNick("solo"))
        assertFalse(manager.hasNick("solo"))
        assertTrue(manager.findAllByNick("solo").isEmpty())
    }

    @Test
    fun `active sessions returns only authenticated ones`() = runTest {
        val manager = newManager()
        val s1 = newSession(manager)
        val s2 = newSession(manager)
        manager.authenticate(s1, "alice")
        assertNotNull(manager.findByNick("alice"))
        val active = manager.activeSessions()
        assertTrue(active.contains(s1))
        assertFalse(active.contains(s2))
    }
}
