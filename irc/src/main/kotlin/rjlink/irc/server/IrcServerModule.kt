package rjlink.irc.server

import org.slf4j.LoggerFactory
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketTypes
import rjlink.core.packet.string
import rjlink.core.server.ServerModule
import rjlink.core.server.Session
import rjlink.core.server.SessionManager

/**
 * Handles IRC-style multi-user channels: join, leave, message relay.
 */
class IrcServerModule(
    private val sessions: SessionManager,
    /** Optional shared channel manager. When null, the module owns a private one. */
    val channels: IrcChannelManager = IrcChannelManager()
) : ServerModule {

    constructor(sessions: SessionManager, maxMembersPerChannel: Int) :
        this(sessions, IrcChannelManager(maxMembersPerChannel))

    private val log = LoggerFactory.getLogger(IrcServerModule::class.java)

    override val name: String = "irc"
    override val supportedTypes: Set<String> = setOf("irc.")

    override suspend fun handlePacket(nick: String, packet: Packet, session: Session) {
        when (packet.type) {
            PacketTypes.IRC_JOIN -> handleJoin(nick, packet, session)
            PacketTypes.IRC_LEAVE -> handleLeave(nick, packet)
            PacketTypes.IRC_MSG -> handleMessage(nick, packet, session)
            else -> log.debug("Unknown IRC packet type: {}", packet.type)
        }
    }

    override suspend fun onSessionClosed(nick: String) {
        // Channel membership is keyed by nick, not by session id. Only remove the
        // nick from its channels when the player has no other active sessions —
        // otherwise dropping one of several concurrent clients would silently
        // pull the user out of every channel for the still-connected ones.
        if (sessions.findAllByNick(nick).isEmpty()) {
            channels.leaveAll(nick)
        }
    }

    private suspend fun handleJoin(nick: String, packet: Packet, session: Session) {
        val channel = runCatching { packet.data.string("channel") }.getOrElse {
            sendIrcError(session, null, "channel field required")
            return
        }
        when (channels.join(nick, channel)) {
            IrcChannelManager.JoinResult.JOINED -> log.debug("nick={} joined {}", nick, channel)
            IrcChannelManager.JoinResult.ALREADY_MEMBER -> { /* idempotent */ }
            IrcChannelManager.JoinResult.CHANNEL_FULL ->
                sendIrcError(session, channel, "channel is full")
        }
    }

    private fun handleLeave(nick: String, packet: Packet) {
        val channel = runCatching { packet.data.string("channel") }.getOrNull() ?: return
        channels.leave(nick, channel)
    }

    private suspend fun handleMessage(nick: String, packet: Packet, session: Session) {
        val target = runCatching { packet.data.string("target") }.getOrElse {
            sendIrcError(session, null, "target field required")
            return
        }
        val text = runCatching { packet.data.string("text") }.getOrElse {
            sendIrcError(session, target, "text field required")
            return
        }
        if (!channels.contains(nick, target)) {
            sendIrcError(session, target, "not a member of channel")
            return
        }

        val outgoing = Packet(
            type = PacketTypes.IRC_MSG_INCOMING,
            seq = 0,
            data = mapOf(
                "target" to target,
                "senderNick" to nick,
                "text" to text
            )
        )
        // Deliver to every session of every channel member — including other
        // sessions of the sender's own nick, which gives the player echo across
        // their concurrently-running clients. Skip only the originating session.
        for (member in channels.members(target)) {
            for (memberSession in sessions.findAllByNick(member)) {
                if (memberSession === session) continue
                runCatching { memberSession.send(outgoing) }.onFailure {
                    log.debug(
                        "Failed to relay IRC message to nick={} sessionId={}: {}",
                        member, memberSession.id, it.message
                    )
                }
            }
        }
    }

    private suspend fun sendIrcError(session: Session, channel: String?, message: String) {
        val data = buildMap {
            if (channel != null) put("channel", channel)
            put("message", message)
        }
        session.send(Packet(type = PacketTypes.IRC_ERROR, seq = 0, data = data))
    }
}
