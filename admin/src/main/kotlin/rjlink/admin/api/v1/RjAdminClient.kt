package rjlink.admin.api.v1

import org.slf4j.LoggerFactory
import rjlink.admin.server.AdminPayload
import rjlink.core.RjInternalApi
import rjlink.core.client.RjClient
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketTypes
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Public admin (control-plane) API.
 *
 * Provides kick, ban, broadcast and inspection commands that operate on the
 * server's active sessions and persistent ban table. All methods require the
 * session to be **elevated** via [authenticate] first.
 *
 * All methods are **non-suspending** — they return immediately and perform
 * their work on the client's internal coroutine scope. Responses arrive
 * asynchronously via [RjAdminListener] callbacks.
 *
 * ## Quick start
 *
 * ```kotlin
 * val client = RjClient(RjClientConfig("host", 443, "__admin__"))
 * val admin  = RjAdminClient(client)
 *
 * admin.addListener(object : RjAdminListener {
 *     override fun onAuthResult(success: Boolean, message: String) {
 *         if (success) admin.listSessions()
 *     }
 *     override fun onSessions(sessions: List<AdminSessionInfo>) {
 *         sessions.forEach { println("${it.nick}  admin=${it.admin}") }
 *     }
 *     // ... override other callbacks as needed
 * })
 *
 * client.connect()
 * // wait for CONNECTED...
 * admin.authenticate(System.getenv("RJLINK_ADMIN_TOKEN"))
 * ```
 *
 * ## Security
 *
 * The admin token is compared in constant time on the server side.
 * Never log or expose the token. After successful elevation the session
 * is marked `isAdmin = true`; without elevation all `admin.*` commands
 * receive `admin.auth.fail (reason="admin elevation required")`.
 *
 * @param client The [RjClient] to ride on. Use a dedicated nick (e.g. `"__admin__"`).
 * @see RjAdminListener
 * @see AdminSessionInfo
 * @see AdminChannelInfo
 * @see AdminBanInfo
 */
@OptIn(RjInternalApi::class)
class RjAdminClient(private val client: RjClient) {

    private val log = LoggerFactory.getLogger(RjAdminClient::class.java)
    private val listeners = CopyOnWriteArrayList<RjAdminListener>()

    init {
        client.registerHandler("admin.") { packet -> dispatch(packet) }
    }

    /** Register a [listener] for admin command results. */
    fun addListener(l: RjAdminListener) { listeners.add(l) }

    /** Remove a previously registered [listener]. */
    fun removeListener(l: RjAdminListener) { listeners.remove(l) }

    /**
     * Request admin elevation using the secret token from `config.yaml`.
     *
     * The result arrives via [RjAdminListener.onAuthResult]. Only after a
     * successful elevation are the remaining methods accepted by the server.
     *
     * @param token The admin token (typically 48+ random bytes, base64-encoded).
     */
    fun authenticate(token: String) = send(PacketTypes.ADMIN_AUTH, mapOf("token" to token))

    /**
     * Request the list of currently active sessions.
     *
     * The result arrives via [RjAdminListener.onSessions].
     */
    fun listSessions() = send(PacketTypes.ADMIN_SESSIONS)

    /**
     * Request the list of IRC channels and their members.
     *
     * The result arrives via [RjAdminListener.onChannels].
     */
    fun listChannels() = send(PacketTypes.ADMIN_CHANNELS)

    /**
     * Request the list of persistent bans.
     *
     * The result arrives via [RjAdminListener.onBans].
     */
    fun listBans() = send(PacketTypes.ADMIN_BANS)

    /**
     * Kick an active session. The target's connection is closed with
     * close code `4008 KICKED`; no persistent record is created.
     *
     * The result arrives via [RjAdminListener.onKickResult].
     *
     * @param nick   The nickname to kick.
     * @param reason Optional reason shown to the kicked user (default: `"kicked by <admin>"`).
     */
    fun kick(nick: String, reason: String? = null) =
        send(PacketTypes.ADMIN_KICK, dataOf("nick" to nick, "reason" to reason))

    /**
     * Ban a nickname persistently **and** kick any active session under that nick.
     *
     * The ban survives server restarts. Future authentication attempts from
     * this nick will receive `auth.fail (reason="banned: <reason>")` and
     * close code `4007 BANNED`.
     *
     * The result arrives via [RjAdminListener.onBanResult].
     *
     * @param nick   The nickname to ban.
     * @param reason Ban reason (default: `"banned"`).
     */
    fun ban(nick: String, reason: String? = null) =
        send(PacketTypes.ADMIN_BAN, dataOf("nick" to nick, "reason" to reason))

    /**
     * Remove a persistent ban for [nick].
     *
     * The result arrives via [RjAdminListener.onUnbanResult].
     *
     * @param nick The nickname to unban.
     */
    fun unban(nick: String) = send(PacketTypes.ADMIN_UNBAN, mapOf("nick" to nick))

    /**
     * Broadcast a text notice to every non-admin active session.
     *
     * The notice is delivered as `sys.error code=200 message="[broadcast] <text>"`,
     * which allows older clients that don't understand admin packets to still
     * display the message.
     *
     * The result arrives via [RjAdminListener.onBroadcastResult].
     *
     * @param text The announcement text.
     */
    fun broadcast(text: String) = send(PacketTypes.ADMIN_BROADCAST, mapOf("text" to text))

    /**
     * Remove the Telegram binding for [nick].
     *
     * After unbinding, the user can no longer send messages to Telegram
     * from that nick until they re-bind with a new code.
     *
     * The result arrives via [RjAdminListener.onTgUnbindResult].
     *
     * @param nick The nickname whose Telegram binding to remove.
     */
    fun tgUnbind(nick: String) = send(PacketTypes.ADMIN_TG_UNBIND, mapOf("nick" to nick))

    private fun send(type: String, data: Map<String, String> = emptyMap()) {
        client.launchInternal {
            client.send(Packet(type = type, seq = client.nextSeq(), data = data))
        }
    }

    private fun dataOf(vararg pairs: kotlin.Pair<String, String?>): Map<String, String> {
        val m = HashMap<String, String>(pairs.size)
        for ((k, v) in pairs) if (v != null) m[k] = v
        return m
    }

    private fun dispatch(packet: Packet) {
        when (packet.type) {
            PacketTypes.ADMIN_AUTH_OK ->
                fire { it.onAuthResult(true, "ok") }
            PacketTypes.ADMIN_AUTH_FAIL ->
                fire { it.onAuthResult(false, packet.data["reason"] ?: "fail") }

            PacketTypes.ADMIN_SESSIONS_RESULT -> {
                val sessions = AdminPayload.decode("s", packet.data).map {
                    AdminSessionInfo(
                        id = it["id"]?.toLongOrNull() ?: 0L,
                        nick = it["nick"].orEmpty(),
                        admin = it["admin"].toBoolean(),
                        lastHeartbeatAgoMs = it["lastHeartbeatAgoMs"]?.toLongOrNull() ?: -1
                    )
                }
                fire { it.onSessions(sessions) }
            }
            PacketTypes.ADMIN_CHANNELS_RESULT -> {
                val channels = AdminPayload.decode("c", packet.data).map {
                    AdminChannelInfo(
                        name = it["name"].orEmpty(),
                        size = it["size"]?.toIntOrNull() ?: 0,
                        members = it["members"]?.takeIf { s -> s.isNotEmpty() }
                            ?.split(",") ?: emptyList()
                    )
                }
                fire { it.onChannels(channels) }
            }
            PacketTypes.ADMIN_BANS_RESULT -> {
                val bans = AdminPayload.decode("b", packet.data).map {
                    AdminBanInfo(
                        nick = it["nick"].orEmpty(),
                        reason = it["reason"].orEmpty(),
                        bannedAtMs = it["bannedAt"]?.toLongOrNull() ?: 0,
                        bannedBy = it["bannedBy"].takeUnless { v -> v.isNullOrEmpty() }
                    )
                }
                fire { it.onBans(bans) }
            }

            PacketTypes.ADMIN_KICK_OK ->
                fire { it.onKickResult(packet.data["nick"].orEmpty(), true, null) }
            PacketTypes.ADMIN_KICK_FAIL ->
                fire { it.onKickResult(packet.data["nick"].orEmpty(), false, packet.data["reason"]) }

            PacketTypes.ADMIN_BAN_OK ->
                fire { it.onBanResult(packet.data["nick"].orEmpty(), true, packet.data["reason"]) }
            PacketTypes.ADMIN_BAN_FAIL ->
                fire { it.onBanResult(packet.data["nick"].orEmpty(), false, packet.data["reason"]) }

            PacketTypes.ADMIN_UNBAN_OK ->
                fire { it.onUnbanResult(packet.data["nick"].orEmpty(), true, null) }
            PacketTypes.ADMIN_UNBAN_FAIL ->
                fire { it.onUnbanResult(packet.data["nick"].orEmpty(), false, packet.data["reason"]) }

            PacketTypes.ADMIN_BROADCAST_OK ->
                fire { it.onBroadcastResult(packet.data["delivered"]?.toIntOrNull() ?: 0) }

            PacketTypes.ADMIN_TG_UNBIND_OK ->
                fire { it.onTgUnbindResult(packet.data["nick"].orEmpty(), true, null) }
            PacketTypes.ADMIN_TG_UNBIND_FAIL ->
                fire { it.onTgUnbindResult(packet.data["nick"].orEmpty(), false, packet.data["reason"]) }

            else -> log.debug("unhandled admin packet type={}", packet.type)
        }
    }

    private inline fun fire(crossinline block: (RjAdminListener) -> Unit) {
        for (l in listeners) {
            try { block(l) } catch (e: Exception) { log.warn("admin listener threw", e) }
        }
    }
}
