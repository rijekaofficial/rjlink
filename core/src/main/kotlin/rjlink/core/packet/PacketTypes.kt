package rjlink.core.packet

/**
 * Canonical packet type identifiers used across the RJLink protocol.
 *
 * Every constant matches the `type` field of a [Packet] exactly.
 * The server routes packets to modules by prefix: a module that
 * declares `supportedTypes = setOf("irc.")` handles all types
 * starting with `"irc."`.
 *
 * ### Namespaces
 *
 * | prefix      | module         | description                                    |
 * |-------------|----------------|------------------------------------------------|
 * | `auth`      | core (server)  | Authentication handshake                       |
 * | `heartbeat` | core (server)  | Keep-alive ping/pong                           |
 * | `sys`       | core (server)  | System-level errors & disconnect notifications |
 * | `irc`       | irc            | IRC-style chat channels                        |
 * | `tg`        | tgbot          | Telegram binding & message relay               |
 * | `admin`     | admin          | Control-plane (kick/ban/broadcast/inspection)  |
 *
 * @see Packet
 */
object PacketTypes {
    // ── Auth / session ──────────────────────────────────────────────────────

    /** Client requests authentication with a nickname. `data: { nick }` */
    const val AUTH = "auth"

    /** Server confirms authentication. `data: { nick }` */
    const val AUTH_OK = "auth.ok"

    /** Server rejects authentication. `data: { reason }` */
    const val AUTH_FAIL = "auth.fail"

    // ── Heartbeat ───────────────────────────────────────────────────────────

    /**
     * Client-to-server ping **and** server-to-client pong.
     * Same type in both directions; `data` is empty.
     */
    const val HEARTBEAT = "heartbeat"

    // ── System ──────────────────────────────────────────────────────────────

    /**
     * Server-side error notification. `data: { code: String, message: String }`.
     *
     * Common codes:
     * - `"400"` — unknown packet type
     * - `"401"` — auth required
     * - `"409"` — already authenticated
     * - `"500"` — internal module error
     * - `"200"` — broadcast notice (not a real error)
     */
    const val SYS_ERROR = "sys.error"

    /** Server-initiated disconnect. `data: { reason }` */
    const val SYS_DISCONNECT = "sys.disconnect"

    // ── IRC ─────────────────────────────────────────────────────────────────

    /** Join an IRC channel. `data: { channel }` */
    const val IRC_JOIN = "irc.join"

    /** Leave an IRC channel. `data: { channel }` */
    const val IRC_LEAVE = "irc.leave"

    /** Send a message to a channel. `data: { target, text }` */
    const val IRC_MSG = "irc.msg"

    /** Incoming message from another user. `data: { target, senderNick, text }` */
    const val IRC_MSG_INCOMING = "irc.msg.incoming"

    /** IRC module error. `data: { channel?, message }` */
    const val IRC_ERROR = "irc.error"

    // ── Telegram ────────────────────────────────────────────────────────────

    /** Bind the current nick to a Telegram chat using an 8-char code. `data: { code }` */
    const val TG_AUTH = "tg.auth"

    /** Binding succeeded. `data: { message }` */
    const val TG_AUTH_OK = "tg.auth.ok"

    /** Binding failed (invalid code). `data: { message }` */
    const val TG_AUTH_FAIL = "tg.auth.fail"

    /** Remove the Telegram binding for the current nick. */
    const val TG_UNBIND = "tg.unbind"

    /** Send a message to the bound Telegram chat. `data: { text }` */
    const val TG_SEND = "tg.send"

    /** Message delivered to Telegram. */
    const val TG_SEND_OK = "tg.send.ok"

    /** Message delivery failed. */
    const val TG_SEND_FAIL = "tg.send.fail"

    // ── Admin (control plane) ───────────────────────────────────────────────
    // All packets in this namespace require session elevation (isAdmin == true).

    /** Request admin elevation. `data: { token }` */
    const val ADMIN_AUTH = "admin.auth"

    /** Elevation granted. */
    const val ADMIN_AUTH_OK = "admin.auth.ok"

    /** Elevation denied. `data: { reason }` */
    const val ADMIN_AUTH_FAIL = "admin.auth.fail"

    /** Request list of active sessions. */
    const val ADMIN_SESSIONS = "admin.sessions"

    /** Session list response. `data: { count, s.<i>.nick, s.<i>.admin, s.<i>.lastHeartbeatAgoMs }` */
    const val ADMIN_SESSIONS_RESULT = "admin.sessions.result"

    /** Request list of IRC channels. */
    const val ADMIN_CHANNELS = "admin.channels"

    /** Channel list response. `data: { count, c.<i>.name, c.<i>.size, c.<i>.members (csv) }` */
    const val ADMIN_CHANNELS_RESULT = "admin.channels.result"

    /** Kick an active session. `data: { nick, reason? }` */
    const val ADMIN_KICK = "admin.kick"

    /** Kick succeeded. `data: { nick }` */
    const val ADMIN_KICK_OK = "admin.kick.ok"

    /** Kick failed. `data: { nick, reason }` */
    const val ADMIN_KICK_FAIL = "admin.kick.fail"

    /** Ban a nick (persistent + kick). `data: { nick, reason? }` */
    const val ADMIN_BAN = "admin.ban"

    /** Ban succeeded. `data: { nick, reason }` */
    const val ADMIN_BAN_OK = "admin.ban.ok"

    /** Ban failed. `data: { nick, reason }` */
    const val ADMIN_BAN_FAIL = "admin.ban.fail"

    /** Remove a ban. `data: { nick }` */
    const val ADMIN_UNBAN = "admin.unban"

    /** Unban succeeded. `data: { nick }` */
    const val ADMIN_UNBAN_OK = "admin.unban.ok"

    /** Unban failed. `data: { nick, reason }` */
    const val ADMIN_UNBAN_FAIL = "admin.unban.fail"

    /** Request list of bans. */
    const val ADMIN_BANS = "admin.bans"

    /** Ban list response. `data: { count, b.<i>.nick, b.<i>.reason, b.<i>.bannedAt, b.<i>.bannedBy }` */
    const val ADMIN_BANS_RESULT = "admin.bans.result"

    /** Broadcast a notice to all non-admin sessions. `data: { text }` */
    const val ADMIN_BROADCAST = "admin.broadcast"

    /** Broadcast result. `data: { delivered }` (number of sessions that received the notice) */
    const val ADMIN_BROADCAST_OK = "admin.broadcast.ok"

    /** Remove a Telegram binding for a nick. `data: { nick }` */
    const val ADMIN_TG_UNBIND = "admin.tg.unbind"

    /** TG unbind succeeded. `data: { nick }` */
    const val ADMIN_TG_UNBIND_OK = "admin.tg.unbind.ok"

    /** TG unbind failed. `data: { nick, reason }` */
    const val ADMIN_TG_UNBIND_FAIL = "admin.tg.unbind.fail"
}

/**
 * Protocol version constant.
 *
 * Sent as the query parameter `v` when opening a WebSocket connection:
 * `wss://host:port/ws?v=1`.
 *
 * The server rejects clients whose version is below `minProtocolVersion`
 * with close code [CloseCodes.OUTDATED_CLIENT].
 */
object ProtocolVersion {
    /** Current protocol version. Increment for breaking changes. */
    const val CURRENT: Int = 1
}

/**
 * WebSocket close codes specific to the RJLink protocol.
 *
 * These codes are sent in the WebSocket `Close` frame and can be inspected
 * on the client side to determine the reason for disconnection.
 *
 * @see ProtocolLimits
 */
object CloseCodes {
    /** Client protocol version is below the server's minimum. */
    const val OUTDATED_CLIENT: Short = 4001

    /** First packet after connect was not `auth`. */
    const val AUTH_REQUIRED: Short = 4002

    /** Incoming frame exceeds [ProtocolLimits.MAX_PACKET_SIZE_BYTES]. */
    const val PACKET_TOO_LARGE: Short = 4003

    /** CBOR decoding failed or unexpected packet structure. */
    const val PROTOCOL_ERROR: Short = 4004

    /** Client did not send heartbeats within the timeout window. */
    const val HEARTBEAT_TIMEOUT: Short = 4005

    /** Another session is already authenticated with the same nick. */
    const val DUPLICATE_NICK: Short = 4006

    /** Nick is present in the ban table. */
    const val BANNED: Short = 4007

    /** Session was kicked by an admin via `admin.kick`. */
    const val KICKED: Short = 4008
}

/**
 * Hard protocol limits enforced by the server.
 *
 * @see CloseCodes.PACKET_TOO_LARGE
 */
object ProtocolLimits {
    /** Maximum allowed size of a single CBOR-encoded WebSocket frame: **64 KB**. */
    const val MAX_PACKET_SIZE_BYTES: Int = 64 * 1024
}
