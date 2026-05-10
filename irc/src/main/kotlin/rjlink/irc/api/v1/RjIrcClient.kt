package rjlink.irc.api.v1

import org.slf4j.LoggerFactory
import rjlink.core.RjInternalApi
import rjlink.core.client.RjClient
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketTypes
import rjlink.core.packet.string
import rjlink.core.packet.stringOrNull
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Public, listener-oriented IRC API built on top of [RjClient].
 *
 * Provides a simple chat-channel abstraction: join a channel, send messages,
 * receive incoming messages via a [RjIrcListener] callback.
 *
 * All methods are **non-suspending** — they return immediately and perform
 * their work on the client's internal coroutine scope.
 *
 * ## Quick start
 *
 * ```kotlin
 * val client = RjClient(RjClientConfig("host", 443, "alice"))
 * val irc    = RjIrcClient(client)
 *
 * irc.addListener(object : RjIrcListener {
 *     override fun onMessageReceived(message: IrcMessage) {
 *         println("<${message.senderNick}@${message.target}> ${message.text}")
 *     }
 *     override fun onError(channel: String?, error: String) {
 *         System.err.println("IRC error on $channel: $error")
 *     }
 * })
 *
 * client.connect()
 * // wait for CONNECTED state...
 * irc.join("#general")
 * irc.sendMessage("#general", "hello everyone!")
 * ```
 *
 * ## Auto-reconnect
 *
 * Joined channels are tracked locally. If the connection drops and the client
 * successfully reconnects, the IRC module automatically re-joins all previously
 * joined channels.
 *
 * @param client The [RjClient] to ride on. Must be the same instance for the
 *               entire lifetime of this [RjIrcClient].
 * @see RjIrcListener
 * @see IrcMessage
 */
@OptIn(RjInternalApi::class)
class RjIrcClient(private val client: RjClient) {

    private val log = LoggerFactory.getLogger(RjIrcClient::class.java)
    private val listeners = CopyOnWriteArrayList<RjIrcListener>()
    private val joinedChannels: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    init {
        client.registerHandler("irc.") { packet -> dispatch(packet) }
        client.registerReSubscribeHook {
            for (channel in joinedChannels.toList()) {
                runCatching {
                    client.send(
                        Packet(
                            type = PacketTypes.IRC_JOIN,
                            seq = client.nextSeq(),
                            data = mapOf("channel" to channel)
                        )
                    )
                }.onFailure { log.warn("Failed to re-join {}: {}", channel, it.message) }
            }
        }
    }

    /**
     * Register a [listener] for IRC events.
     *
     * Callbacks are invoked on the library's internal dispatcher;
     * implementations must not block for long periods.
     */
    fun addListener(listener: RjIrcListener) { listeners.add(listener) }

    /** Remove a previously registered [listener]. */
    fun removeListener(listener: RjIrcListener) { listeners.remove(listener) }

    /**
     * Join an IRC channel.
     *
     * If the client is not yet in the [CONNECTED][RjClient.State.CONNECTED] state,
     * the join packet is queued and will be sent once the connection is established.
     * On reconnect the channel is automatically re-joined.
     *
     * @param channel Channel name (e.g. `"#general"`). Convention: prefix with `#`.
     */
    fun join(channel: String) {
        joinedChannels.add(channel)
        client.launchInternal {
            client.send(
                Packet(
                    type = PacketTypes.IRC_JOIN,
                    seq = client.nextSeq(),
                    data = mapOf("channel" to channel)
                )
            )
        }
    }

    /**
     * Leave an IRC channel.
     *
     * After leaving, the client will no longer receive messages from this
     * channel. The channel is also removed from the auto-rejoin list.
     *
     * @param channel The channel to leave.
     */
    fun leave(channel: String) {
        joinedChannels.remove(channel)
        client.launchInternal {
            client.send(
                Packet(
                    type = PacketTypes.IRC_LEAVE,
                    seq = client.nextSeq(),
                    data = mapOf("channel" to channel)
                )
            )
        }
    }

    /**
     * Send a chat message to a channel.
     *
     * The sender must have previously joined [target]; otherwise the server
     * will reply with an [irc.error][PacketTypes.IRC_ERROR].
     *
     * @param target The channel name (e.g. `"#general"`).
     * @param text   The message body.
     */
    fun sendMessage(target: String, text: String) {
        client.launchInternal {
            client.send(
                Packet(
                    type = PacketTypes.IRC_MSG,
                    seq = client.nextSeq(),
                    data = mapOf("target" to target, "text" to text)
                )
            )
        }
    }

    private fun dispatch(packet: Packet) {
        when (packet.type) {
            PacketTypes.IRC_MSG_INCOMING -> {
                val msg = IrcMessage(
                    target = packet.data.string("target"),
                    senderNick = packet.data.string("senderNick"),
                    text = packet.data.string("text")
                )
                listeners.forEach {
                    runCatching { it.onMessageReceived(msg) }
                        .onFailure { e -> log.warn("IRC listener threw", e) }
                }
            }
            PacketTypes.IRC_ERROR -> {
                val channel = packet.data.stringOrNull("channel")
                val message = packet.data.stringOrNull("message") ?: "unknown error"
                listeners.forEach {
                    runCatching { it.onError(channel, message) }
                        .onFailure { e -> log.warn("IRC error listener threw", e) }
                }
            }
            else -> log.debug("Unhandled IRC packet type={}", packet.type)
        }
    }
}
