package rjlink.irc.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import rjlink.core.RjInternalApi
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketCodec
import rjlink.core.packet.PacketTypes
import rjlink.core.server.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the multi-session-per-nick behaviour of [IrcServerModule]:
 *  - a message from one client of a user echoes to that user's other clients;
 *  - dropping one client of a user does not silently leave channels for the
 *    other clients of the same user.
 */
@OptIn(RjInternalApi::class)
class IrcServerModuleMultiSessionTest {

    private fun received(channel: Channel<ByteArray>): List<Packet> {
        val out = mutableListOf<Packet>()
        while (true) {
            val r = channel.tryReceive()
            if (r.isFailure) break
            out.add(PacketCodec.decode(r.getOrThrow()))
        }
        return out
    }

    @Test
    fun `message from one session echoes to other sessions of the same nick`() = runTest {
        val sm = SessionManager(30, 90)
        val module = IrcServerModule(sm)

        val ch1 = Channel<ByteArray>(Channel.UNLIMITED)
        val test1 = sm.createSession(ch1) { _, _ -> }
        sm.authenticate(test1, "Test")

        val ch2 = Channel<ByteArray>(Channel.UNLIMITED)
        val test2 = sm.createSession(ch2) { _, _ -> }
        sm.authenticate(test2, "Test")

        val ch3 = Channel<ByteArray>(Channel.UNLIMITED)
        val other = sm.createSession(ch3) { _, _ -> }
        sm.authenticate(other, "alice")

        // Both Test sessions and alice are in the channel.
        module.handlePacket("Test", Packet(PacketTypes.IRC_JOIN, 1, mapOf("channel" to "#a")), test1)
        module.handlePacket("alice", Packet(PacketTypes.IRC_JOIN, 1, mapOf("channel" to "#a")), other)

        // test1 sends a message.
        module.handlePacket(
            "Test",
            Packet(PacketTypes.IRC_MSG, 2, mapOf("target" to "#a", "text" to "hello")),
            test1
        )

        // The originator (test1) must NOT receive the echo.
        assertTrue(received(ch1).none { it.type == PacketTypes.IRC_MSG_INCOMING })

        // Other Test session AND alice must receive it.
        val test2Msgs = received(ch2).filter { it.type == PacketTypes.IRC_MSG_INCOMING }
        val aliceMsgs = received(ch3).filter { it.type == PacketTypes.IRC_MSG_INCOMING }
        assertEquals(1, test2Msgs.size)
        assertEquals("hello", test2Msgs[0].data["text"])
        assertEquals(1, aliceMsgs.size)
    }

    @Test
    fun `closing one of several sessions does not remove nick from channels`() = runTest {
        val sm = SessionManager(30, 90)
        val module = IrcServerModule(sm)

        val ch1 = Channel<ByteArray>(Channel.UNLIMITED)
        val s1 = sm.createSession(ch1) { _, _ -> }
        sm.authenticate(s1, "Test")
        val ch2 = Channel<ByteArray>(Channel.UNLIMITED)
        val s2 = sm.createSession(ch2) { _, _ -> }
        sm.authenticate(s2, "Test")

        module.handlePacket("Test", Packet(PacketTypes.IRC_JOIN, 1, mapOf("channel" to "#a")), s1)

        // Drop the second session — Test still has another active client.
        sm.remove(s2)
        module.onSessionClosed("Test")

        assertTrue("Test" in module.channels.members("#a"))
    }

    @Test
    fun `closing the last session removes nick from channels`() = runTest {
        val sm = SessionManager(30, 90)
        val module = IrcServerModule(sm)

        val ch = Channel<ByteArray>(Channel.UNLIMITED)
        val s = sm.createSession(ch) { _, _ -> }
        sm.authenticate(s, "solo")
        module.handlePacket("solo", Packet(PacketTypes.IRC_JOIN, 1, mapOf("channel" to "#a")), s)

        sm.remove(s)
        module.onSessionClosed("solo")

        assertTrue(module.channels.members("#a").isEmpty())
    }
}
