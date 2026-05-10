package rjlink.admin.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import rjlink.core.RjInternalApi
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketCodec
import rjlink.core.packet.PacketTypes
import rjlink.core.server.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(RjInternalApi::class)
class AdminServerModuleTest {

    private fun newManager() = SessionManager(30, 90)

    private suspend fun receivedTypes(channel: Channel<ByteArray>): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val r = channel.tryReceive()
            if (r.isFailure) break
            out.add(PacketCodec.decode(r.getOrThrow()).type)
        }
        return out
    }

    @Test
    fun `valid token elevates session`() = runTest {
        val sm = newManager()
        val module = AdminServerModule(token = "secret", sessions = sm, bans = InMemoryBanStore())
        val ch = Channel<ByteArray>(Channel.UNLIMITED)
        val session = sm.createSession(ch) { _, _ -> }
        sm.authenticate(session, "ops")

        module.handlePacket(
            "ops",
            Packet(PacketTypes.ADMIN_AUTH, 1, mapOf("token" to "secret")),
            session
        )

        assertTrue(session.isAdmin)
        assertEquals(listOf(PacketTypes.ADMIN_AUTH_OK), receivedTypes(ch))
    }

    @Test
    fun `bad token does not elevate`() = runTest {
        val sm = newManager()
        val module = AdminServerModule(token = "secret", sessions = sm, bans = InMemoryBanStore())
        val ch = Channel<ByteArray>(Channel.UNLIMITED)
        val session = sm.createSession(ch) { _, _ -> }
        sm.authenticate(session, "ops")

        module.handlePacket(
            "ops",
            Packet(PacketTypes.ADMIN_AUTH, 1, mapOf("token" to "wrong")),
            session
        )

        assertFalse(session.isAdmin)
        assertEquals(listOf(PacketTypes.ADMIN_AUTH_FAIL), receivedTypes(ch))
    }

    @Test
    fun `non-elevated session is rejected`() = runTest {
        val sm = newManager()
        val module = AdminServerModule(token = "secret", sessions = sm, bans = InMemoryBanStore())
        val ch = Channel<ByteArray>(Channel.UNLIMITED)
        val session = sm.createSession(ch) { _, _ -> }
        sm.authenticate(session, "ops")

        module.handlePacket("ops", Packet(PacketTypes.ADMIN_SESSIONS, 1), session)

        assertEquals(listOf(PacketTypes.ADMIN_AUTH_FAIL), receivedTypes(ch))
    }

    @Test
    fun `ban writes to store and returns ok`() = runTest {
        val sm = newManager()
        val bans = InMemoryBanStore()
        val module = AdminServerModule(token = "secret", sessions = sm, bans = bans)
        val ch = Channel<ByteArray>(Channel.UNLIMITED)
        val session = sm.createSession(ch) { _, _ -> }
        sm.authenticate(session, "ops")
        session.markAdmin()

        module.handlePacket(
            "ops",
            Packet(PacketTypes.ADMIN_BAN, 1, mapOf("nick" to "vasya", "reason" to "spam")),
            session
        )

        assertTrue(bans.isBanned("vasya"))
        assertEquals("spam", bans.find("vasya")?.reason)
        assertEquals(listOf(PacketTypes.ADMIN_BAN_OK), receivedTypes(ch))
    }

    @Test
    fun `list sessions returns active nicks`() = runTest {
        val sm = newManager()
        val module = AdminServerModule(token = "secret", sessions = sm, bans = InMemoryBanStore())

        val opsCh = Channel<ByteArray>(Channel.UNLIMITED)
        val ops = sm.createSession(opsCh) { _, _ -> }
        sm.authenticate(ops, "ops")
        ops.markAdmin()

        val aliceCh = Channel<ByteArray>(Channel.UNLIMITED)
        val alice = sm.createSession(aliceCh) { _, _ -> }
        sm.authenticate(alice, "alice")

        module.handlePacket("ops", Packet(PacketTypes.ADMIN_SESSIONS, 1), ops)

        val payload = PacketCodec.decode(opsCh.tryReceive().getOrThrow())
        assertEquals(PacketTypes.ADMIN_SESSIONS_RESULT, payload.type)
        val nicks = AdminPayload.decode("s", payload.data).map { it["nick"] }.toSet()
        assertEquals(setOf("ops", "alice"), nicks)
    }

    @Test
    fun `kick disconnects all sessions of target nick and returns count`() = runTest {
        val sm = newManager()
        val module = AdminServerModule(token = "secret", sessions = sm, bans = InMemoryBanStore())

        val opsCh = Channel<ByteArray>(Channel.UNLIMITED)
        val ops = sm.createSession(opsCh) { _, _ -> }
        sm.authenticate(ops, "ops")
        ops.markAdmin()

        val t1Ch = Channel<ByteArray>(Channel.UNLIMITED)
        val t1 = sm.createSession(t1Ch) { _, _ -> }
        sm.authenticate(t1, "Test")
        val t2Ch = Channel<ByteArray>(Channel.UNLIMITED)
        val t2 = sm.createSession(t2Ch) { _, _ -> }
        sm.authenticate(t2, "Test")

        module.handlePacket("ops", Packet(PacketTypes.ADMIN_KICK, 11, mapOf("nick" to "Test")), ops)

        val adminReply = PacketCodec.decode(opsCh.tryReceive().getOrThrow())
        assertEquals(PacketTypes.ADMIN_KICK_OK, adminReply.type)
        assertEquals("Test", adminReply.data["nick"])
        assertEquals("2", adminReply.data["count"])

        val t1Types = receivedTypes(t1Ch)
        val t2Types = receivedTypes(t2Ch)
        assertTrue(PacketTypes.SYS_DISCONNECT in t1Types)
        assertTrue(PacketTypes.SYS_DISCONNECT in t2Types)
    }
}
