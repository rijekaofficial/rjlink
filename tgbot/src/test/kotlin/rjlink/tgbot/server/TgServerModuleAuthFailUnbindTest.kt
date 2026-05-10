package rjlink.tgbot.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import rjlink.core.RjInternalApi
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketCodec
import rjlink.core.packet.PacketTypes
import rjlink.core.server.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(RjInternalApi::class)
class TgServerModuleAuthFailUnbindTest {

    private class NoopSender : TgMessageSender {
        override suspend fun sendText(chatId: Long, text: String): Boolean = true
    }

    @Test
    fun `wrong tg auth code removes existing binding`() = runTest {
        val auth = TgAuthManager(InMemoryTgBindingStore(), TgCodeGenerator())
        val module = TgServerModule(auth, NoopSender())
        val sm = SessionManager(30, 90)
        val ch = Channel<ByteArray>(Channel.UNLIMITED)
        val session = sm.createSession(ch) { _, _ -> }
        sm.authenticate(session, "Test")

        val validCode = auth.getOrCreateCode(123L)
        module.handlePacket("Test", Packet(PacketTypes.TG_AUTH, 1, mapOf("code" to validCode)), session)
        assertEquals(123L, auth.findChatId("Test"))

        module.handlePacket("Test", Packet(PacketTypes.TG_AUTH, 2, mapOf("code" to "WRONG123")), session)

        val authFail = PacketCodec.decode(ch.tryReceive().getOrThrow()) // first reply (auth ok)
        val second = PacketCodec.decode(ch.tryReceive().getOrThrow())   // second reply (auth fail)
        assertEquals(PacketTypes.TG_AUTH_OK, authFail.type)
        assertEquals(PacketTypes.TG_AUTH_FAIL, second.type)
        assertEquals("invalid code; binding removed", second.data["message"])
        assertNull(auth.findChatId("Test"))
    }
}
