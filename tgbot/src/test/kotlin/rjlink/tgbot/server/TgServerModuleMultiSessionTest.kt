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
import kotlin.test.assertTrue

@OptIn(RjInternalApi::class)
class TgServerModuleMultiSessionTest {

    private class RecordingSender : TgMessageSender {
        val sent = mutableListOf<Pair<Long, String>>()
        override suspend fun sendText(chatId: Long, text: String): Boolean {
            sent += chatId to text
            return true
        }
    }

    private fun receivedTypes(channel: Channel<ByteArray>): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val r = channel.tryReceive()
            if (r.isFailure) break
            out += PacketCodec.decode(r.getOrThrow()).type
        }
        return out
    }

    @Test
    fun `bind in one session allows send from another session of same nick`() = runTest {
        val auth = TgAuthManager(InMemoryTgBindingStore(), TgCodeGenerator())
        val sender = RecordingSender()
        val module = TgServerModule(auth, sender)
        val sm = SessionManager(30, 90)

        val ch1 = Channel<ByteArray>(Channel.UNLIMITED)
        val s1 = sm.createSession(ch1) { _, _ -> }
        sm.authenticate(s1, "Test")

        val ch2 = Channel<ByteArray>(Channel.UNLIMITED)
        val s2 = sm.createSession(ch2) { _, _ -> }
        sm.authenticate(s2, "Test")

        val code = auth.getOrCreateCode(777L)
        module.handlePacket("Test", Packet(PacketTypes.TG_AUTH, 1, mapOf("code" to code)), s1)
        module.handlePacket("Test", Packet(PacketTypes.TG_SEND, 2, mapOf("text" to "hello from s2")), s2)

        assertTrue(PacketTypes.TG_AUTH_OK in receivedTypes(ch1))
        assertTrue(PacketTypes.TG_SEND_OK in receivedTypes(ch2))
        assertEquals(listOf(777L to "hello from s2"), sender.sent)
    }

    @Test
    fun `unbind from one session affects all sessions of same nick`() = runTest {
        val auth = TgAuthManager(InMemoryTgBindingStore(), TgCodeGenerator())
        val sender = RecordingSender()
        val module = TgServerModule(auth, sender)
        val sm = SessionManager(30, 90)

        val ch1 = Channel<ByteArray>(Channel.UNLIMITED)
        val s1 = sm.createSession(ch1) { _, _ -> }
        sm.authenticate(s1, "Test")

        val ch2 = Channel<ByteArray>(Channel.UNLIMITED)
        val s2 = sm.createSession(ch2) { _, _ -> }
        sm.authenticate(s2, "Test")

        val code = auth.getOrCreateCode(777L)
        module.handlePacket("Test", Packet(PacketTypes.TG_AUTH, 1, mapOf("code" to code)), s1)
        module.handlePacket("Test", Packet(PacketTypes.TG_UNBIND, 2), s2)
        module.handlePacket("Test", Packet(PacketTypes.TG_SEND, 3, mapOf("text" to "after unbind")), s1)

        assertTrue(PacketTypes.TG_SEND_FAIL in receivedTypes(ch1))
        assertTrue(sender.sent.isEmpty())
    }
}
