package rjlink.irc.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrcChannelManagerTest {

    @Test
    fun `join adds member and reports JOINED`() {
        val m = IrcChannelManager()
        assertEquals(IrcChannelManager.JoinResult.JOINED, m.join("alice", "#a"))
        assertTrue(m.contains("alice", "#a"))
    }

    @Test
    fun `double join is idempotent`() {
        val m = IrcChannelManager()
        m.join("alice", "#a")
        assertEquals(IrcChannelManager.JoinResult.ALREADY_MEMBER, m.join("alice", "#a"))
        assertEquals(setOf("alice"), m.members("#a"))
    }

    @Test
    fun `channel becomes empty after last leave`() {
        val m = IrcChannelManager()
        m.join("a", "#x")
        m.leave("a", "#x")
        assertEquals(0, m.channelCount())
    }

    @Test
    fun `channel full`() {
        val m = IrcChannelManager(maxMembersPerChannel = 2)
        m.join("a", "#x")
        m.join("b", "#x")
        assertEquals(IrcChannelManager.JoinResult.CHANNEL_FULL, m.join("c", "#x"))
    }

    @Test
    fun `leaveAll removes nick from every channel`() {
        val m = IrcChannelManager()
        m.join("a", "#x")
        m.join("a", "#y")
        m.join("b", "#x")
        val affected = m.leaveAll("a")
        assertEquals(setOf("#x", "#y"), affected)
        assertFalse(m.contains("a", "#x"))
        assertTrue(m.contains("b", "#x"))
    }
}
