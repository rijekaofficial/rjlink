package rjlink.server.routing

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketCodec
import rjlink.core.packet.PacketTypes
import rjlink.core.server.ServerModule
import rjlink.core.server.Session
import rjlink.core.server.SessionManager

class ModuleRegistryTest {

    @Test
    fun `resolve returns null for empty registry`() {
        val registry = ModuleRegistry()
        assertNull(registry.resolve("irc.join"))
    }

    @Test
    fun `resolve finds module by prefix`() {
        val registry = ModuleRegistry()
        val module = StubModule("irc", setOf("irc."))
        registry.register(module)
        assertSame(module, registry.resolve("irc.join"))
    }

    @Test
    fun `resolve returns null for unknown prefix`() {
        val registry = ModuleRegistry()
        registry.register(StubModule("irc", setOf("irc.")))
        assertNull(registry.resolve("tg.auth"))
    }

    @Test
    fun `all returns all registered modules`() {
        val registry = ModuleRegistry()
        registry.register(StubModule("irc", setOf("irc.")))
        registry.register(StubModule("tg", setOf("tg.")))
        assertEquals(2, registry.all().size)
    }

    private class StubModule(
        override val name: String,
        override val supportedTypes: Set<String>
    ) : ServerModule {
        override suspend fun handlePacket(nick: String, packet: Packet, session: Session) {}
        override suspend fun onSessionClosed(nick: String) {}
        override suspend fun start() {}
        override suspend fun stop() {}
    }
}

class PacketRouterTest {

    private val manager = SessionManager(30, 90)

    private suspend fun makeSession(): Pair<Session, Channel<ByteArray>> {
        val ch = Channel<ByteArray>(Channel.BUFFERED)
        val session = manager.createSession(ch) { _, _ -> }
        return session to ch
    }

    private suspend fun awaitPacket(ch: Channel<ByteArray>): Packet {
        val bytes = ch.receive()
        return PacketCodec.decode(bytes)
    }

    @Test
    fun `unknown type sends sys error 400`() = runTest {
        val registry = ModuleRegistry()
        val router = PacketRouter(registry)
        val (session, ch) = makeSession()
        router.handle("alice", Packet("unknown.type", 1), session)
        val sent = awaitPacket(ch)
        assertEquals(PacketTypes.SYS_ERROR, sent.type)
        assertEquals("400", sent.data["code"])
    }

    @Test
    fun `matching module dispatches correctly`() = runTest {
        val registry = ModuleRegistry()
        var receivedNick: String? = null
        var receivedType: String? = null
        registry.register(object : ServerModule {
            override val name = "irc"
            override val supportedTypes = setOf("irc.")
            override suspend fun handlePacket(nick: String, packet: Packet, session: Session) {
                receivedNick = nick
                receivedType = packet.type
            }
            override suspend fun onSessionClosed(nick: String) {}
            override suspend fun start() {}
            override suspend fun stop() {}
        })
        val router = PacketRouter(registry)
        val (session, _) = makeSession()
        router.handle("alice", Packet("irc.join", 2, mapOf("channel" to "#test")), session)
        assertEquals("alice", receivedNick)
        assertEquals("irc.join", receivedType)
    }

    @Test
    fun `module exception sends sys error 500`() = runTest {
        val registry = ModuleRegistry()
        registry.register(object : ServerModule {
            override val name = "broken"
            override val supportedTypes = setOf("broken.")
            override suspend fun handlePacket(nick: String, packet: Packet, session: Session) {
                throw RuntimeException("oops")
            }
            override suspend fun onSessionClosed(nick: String) {}
            override suspend fun start() {}
            override suspend fun stop() {}
        })
        val router = PacketRouter(registry)
        val (session, ch) = makeSession()
        router.handle("alice", Packet("broken.cmd", 1), session)
        val sent = awaitPacket(ch)
        assertEquals(PacketTypes.SYS_ERROR, sent.type)
        assertEquals("500", sent.data["code"])
    }
}
