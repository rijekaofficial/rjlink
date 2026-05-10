package rjlink.admin.server

import org.slf4j.LoggerFactory
import rjlink.core.RjInternalApi
import rjlink.core.packet.CloseCodes
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketTypes
import rjlink.core.packet.string
import rjlink.core.packet.stringOrNull
import rjlink.core.server.ServerModule
import rjlink.core.server.Session
import rjlink.core.server.SessionManager
import java.security.MessageDigest

/**
 * Server-side control-plane module: token-gated session management.
 *
 * Handles [PacketTypes.ADMIN_AUTH] for elevation, then dispatches kick/ban/broadcast/
 * inspection commands. All state-mutating operations require a previously elevated
 * session ([Session.isAdmin] == true); otherwise the request is rejected.
 *
 * @param token expected secret. Compared in constant time.
 * @param bans persistent ban store.
 * @param channelInfoProvider opaque way to retrieve IRC-channel snapshot without
 *        a hard dependency on the IRC module.
 * @param tgUnbinder opaque hook to remove a Telegram binding by nick (no-op if TG
 *        module is not installed).
 */
@OptIn(RjInternalApi::class)
class AdminServerModule(
    private val token: String,
    private val sessions: SessionManager,
    private val bans: BanStore,
    private val channelInfoProvider: () -> List<ChannelInfo> = { emptyList() },
    private val tgUnbinder: (String) -> Boolean = { false }
) : ServerModule {

    /** Snapshot of a single IRC channel for `admin.channels`. */
    data class ChannelInfo(val name: String, val members: List<String>)

    private val log = LoggerFactory.getLogger(AdminServerModule::class.java)

    override val name: String = "admin"
    override val supportedTypes: Set<String> = setOf("admin.")

    init {
        require(token.isNotBlank()) { "admin token must not be blank" }
    }

    override suspend fun handlePacket(nick: String, packet: Packet, session: Session) {
        if (packet.type == PacketTypes.ADMIN_AUTH) {
            handleAuth(packet, session)
            return
        }
        if (!session.isAdmin) {
            session.send(
                Packet(
                    type = PacketTypes.ADMIN_AUTH_FAIL,
                    seq = packet.seq,
                    data = mapOf("reason" to "admin elevation required")
                )
            )
            return
        }
        when (packet.type) {
            PacketTypes.ADMIN_SESSIONS -> handleListSessions(packet, session)
            PacketTypes.ADMIN_CHANNELS -> handleListChannels(packet, session)
            PacketTypes.ADMIN_KICK -> handleKick(nick, packet, session)
            PacketTypes.ADMIN_BAN -> handleBan(nick, packet, session)
            PacketTypes.ADMIN_UNBAN -> handleUnban(packet, session)
            PacketTypes.ADMIN_BANS -> handleListBans(packet, session)
            PacketTypes.ADMIN_BROADCAST -> handleBroadcast(packet, session)
            PacketTypes.ADMIN_TG_UNBIND -> handleTgUnbind(packet, session)
            else -> log.debug("Unknown admin packet type: {}", packet.type)
        }
    }

    private suspend fun handleAuth(packet: Packet, session: Session) {
        val supplied = runCatching { packet.data.string("token") }.getOrElse { "" }
        if (!constantTimeEquals(supplied, token)) {
            log.warn("Failed admin elevation for nick={}", session.nick)
            session.send(
                Packet(
                    type = PacketTypes.ADMIN_AUTH_FAIL,
                    seq = packet.seq,
                    data = mapOf("reason" to "invalid token")
                )
            )
            return
        }
        session.markAdmin()
        log.info("Admin elevation granted to nick={}", session.nick)
        session.send(Packet(PacketTypes.ADMIN_AUTH_OK, packet.seq))
    }

    private suspend fun handleListSessions(packet: Packet, session: Session) {
        val rows = sessions.activeSessions().map {
            mapOf(
                "id" to it.id.toString(),
                "nick" to (it.nick ?: ""),
                "admin" to it.isAdmin.toString(),
                "lastHeartbeatAgoMs" to (System.currentTimeMillis() - it.lastHeartbeatMs).toString()
            )
        }
        session.send(
            Packet(
                type = PacketTypes.ADMIN_SESSIONS_RESULT,
                seq = packet.seq,
                data = AdminPayload.encode("s", rows)
            )
        )
    }

    private suspend fun handleListChannels(packet: Packet, session: Session) {
        val rows = channelInfoProvider().map {
            mapOf(
                "name" to it.name,
                "size" to it.members.size.toString(),
                "members" to it.members.joinToString(",")
            )
        }
        session.send(
            Packet(
                type = PacketTypes.ADMIN_CHANNELS_RESULT,
                seq = packet.seq,
                data = AdminPayload.encode("c", rows)
            )
        )
    }

    private suspend fun handleKick(adminNick: String, packet: Packet, session: Session) {
        val target = runCatching { packet.data.string("nick") }.getOrElse {
            session.send(failPacket(PacketTypes.ADMIN_KICK_FAIL, packet.seq, "?", "nick required"))
            return
        }
        val reason = packet.data.stringOrNull("reason") ?: "kicked by $adminNick"
        val targets = sessions.findAllByNick(target)
        if (targets.isEmpty()) {
            session.send(failPacket(PacketTypes.ADMIN_KICK_FAIL, packet.seq, target, "session not found"))
            return
        }
        for (t in targets) {
            runCatching {
                t.send(Packet(PacketTypes.SYS_DISCONNECT, 0, mapOf("reason" to reason)))
                t.close(CloseCodes.KICKED, "kicked")
            }
        }
        log.info("admin {} kicked {} ({} sessions, reason={})", adminNick, target, targets.size, reason)
        session.send(
            Packet(
                type = PacketTypes.ADMIN_KICK_OK,
                seq = packet.seq,
                data = mapOf("nick" to target, "count" to targets.size.toString())
            )
        )
    }

    private suspend fun handleBan(adminNick: String, packet: Packet, session: Session) {
        val target = runCatching { packet.data.string("nick") }.getOrElse {
            session.send(failPacket(PacketTypes.ADMIN_BAN_FAIL, packet.seq, "?", "nick required"))
            return
        }
        val reason = packet.data.stringOrNull("reason") ?: "banned"
        bans.ban(target, reason, adminNick)
        // Boot every active session under that nick.
        val live = sessions.findAllByNick(target)
        for (t in live) {
            runCatching {
                t.send(Packet(PacketTypes.SYS_DISCONNECT, 0, mapOf("reason" to "banned: $reason")))
                t.close(CloseCodes.BANNED, "banned")
            }
        }
        log.info("admin {} banned {} ({} sessions kicked, reason={})", adminNick, target, live.size, reason)
        session.send(
            Packet(
                type = PacketTypes.ADMIN_BAN_OK,
                seq = packet.seq,
                data = mapOf("nick" to target, "reason" to reason, "count" to live.size.toString())
            )
        )
    }

    private suspend fun handleUnban(packet: Packet, session: Session) {
        val target = runCatching { packet.data.string("nick") }.getOrElse {
            session.send(failPacket(PacketTypes.ADMIN_UNBAN_FAIL, packet.seq, "?", "nick required"))
            return
        }
        val ok = bans.unban(target)
        session.send(
            Packet(
                type = if (ok) PacketTypes.ADMIN_UNBAN_OK else PacketTypes.ADMIN_UNBAN_FAIL,
                seq = packet.seq,
                data = mapOf("nick" to target).let {
                    if (ok) it else it + ("reason" to "not banned")
                }
            )
        )
    }

    private suspend fun handleListBans(packet: Packet, session: Session) {
        val rows = bans.listAll().map {
            mapOf(
                "nick" to it.nick,
                "reason" to it.reason,
                "bannedAt" to it.bannedAt.toString(),
                "bannedBy" to (it.bannedBy ?: "")
            )
        }
        session.send(
            Packet(
                type = PacketTypes.ADMIN_BANS_RESULT,
                seq = packet.seq,
                data = AdminPayload.encode("b", rows)
            )
        )
    }

    private suspend fun handleBroadcast(packet: Packet, session: Session) {
        val text = runCatching { packet.data.string("text") }.getOrElse {
            session.send(
                Packet(
                    type = PacketTypes.ADMIN_BROADCAST_OK,
                    seq = packet.seq,
                    data = mapOf("delivered" to "0", "error" to "text required")
                )
            )
            return
        }
        var delivered = 0
        for (peer in sessions.activeSessions()) {
            if (peer.isAdmin) continue
            runCatching {
                peer.send(
                    Packet(
                        type = PacketTypes.SYS_ERROR, // re-using sys.error as a notice channel
                        seq = 0,
                        data = mapOf("code" to "200", "message" to "[broadcast] $text")
                    )
                )
                delivered += 1
            }
        }
        session.send(
            Packet(
                type = PacketTypes.ADMIN_BROADCAST_OK,
                seq = packet.seq,
                data = mapOf("delivered" to delivered.toString())
            )
        )
    }

    private suspend fun handleTgUnbind(packet: Packet, session: Session) {
        val target = runCatching { packet.data.string("nick") }.getOrElse {
            session.send(failPacket(PacketTypes.ADMIN_TG_UNBIND_FAIL, packet.seq, "?", "nick required"))
            return
        }
        val ok = tgUnbinder(target)
        session.send(
            Packet(
                type = if (ok) PacketTypes.ADMIN_TG_UNBIND_OK else PacketTypes.ADMIN_TG_UNBIND_FAIL,
                seq = packet.seq,
                data = mapOf("nick" to target).let {
                    if (ok) it else it + ("reason" to "no binding")
                }
            )
        )
    }

    private fun failPacket(type: String, seq: Int, nick: String, reason: String) =
        Packet(type = type, seq = seq, data = mapOf("nick" to nick, "reason" to reason))

    private fun constantTimeEquals(a: String, b: String): Boolean {
        // Length leak is acceptable; comparing identical lengths in constant time matters more.
        if (a.length != b.length) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
