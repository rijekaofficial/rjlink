package rjlink.tgbot.server

import org.slf4j.LoggerFactory
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketTypes
import rjlink.core.packet.string
import rjlink.core.server.ServerModule
import rjlink.core.server.Session

/**
 * Server-side Telegram module.
 *
 * Handles in-band auth (`tg.auth`), explicit unbind (`tg.unbind`) and outbound message
 * relay to Telegram (`tg.send`). Inbound Telegram messages (bot → user) are handled by
 * [TgBotDriver], which owns the actual Bot API connection.
 */
class TgServerModule(
    private val auth: TgAuthManager,
    private val sender: TgMessageSender
) : ServerModule {

    private val log = LoggerFactory.getLogger(TgServerModule::class.java)

    override val name: String = "tgbot"
    override val supportedTypes: Set<String> = setOf("tg.")

    override suspend fun handlePacket(nick: String, packet: Packet, session: Session) {
        when (packet.type) {
            PacketTypes.TG_AUTH -> handleAuth(nick, packet, session)
            PacketTypes.TG_UNBIND -> handleUnbind(nick)
            PacketTypes.TG_SEND -> handleSend(nick, packet, session)
            else -> log.debug("Unknown TG packet type: {}", packet.type)
        }
    }

    private suspend fun handleAuth(nick: String, packet: Packet, session: Session) {
        val code = runCatching { packet.data.string("code") }.getOrElse {
            session.send(Packet(PacketTypes.TG_AUTH_FAIL, 0, mapOf("message" to "code required")))
            return
        }
        val ok = auth.bindByCode(nick, code)
        val reply = if (ok) {
            Packet(PacketTypes.TG_AUTH_OK, 0, mapOf("message" to "telegram binding created"))
        } else {
            // Security behavior: if the user enters a wrong code, drop any previous
            // binding for this nick so stale/compromised links do not stay active.
            auth.unbind(nick)
            Packet(PacketTypes.TG_AUTH_FAIL, 0, mapOf("message" to "invalid code; binding removed"))
        }
        session.send(reply)
    }

    private fun handleUnbind(nick: String) {
        auth.unbind(nick)
    }

    private suspend fun handleSend(nick: String, packet: Packet, session: Session) {
        val text = runCatching { packet.data.string("text") }.getOrElse {
            session.send(Packet(PacketTypes.TG_SEND_FAIL, 0, emptyMap()))
            return
        }
        val chatId = auth.findChatId(nick)
        if (chatId == null) {
            session.send(Packet(PacketTypes.TG_SEND_FAIL, 0, emptyMap()))
            return
        }
        val ok = runCatching { sender.sendText(chatId, text) }.getOrElse { e ->
            log.warn("Telegram send failed for nick={}: {}", nick, e.message)
            false
        }
        session.send(
            if (ok) Packet(PacketTypes.TG_SEND_OK, 0, emptyMap())
            else Packet(PacketTypes.TG_SEND_FAIL, 0, emptyMap())
        )
    }
}
