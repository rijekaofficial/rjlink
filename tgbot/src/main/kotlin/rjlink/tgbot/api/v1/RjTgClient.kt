package rjlink.tgbot.api.v1

import org.slf4j.LoggerFactory
import rjlink.core.RjInternalApi
import rjlink.core.client.RjClient
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketTypes
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Public Telegram client API.
 *
 * Allows the client to bind the user's nickname to a Telegram chat
 * (via an 8-character code obtained from the bot), send messages into that
 * chat, and unbind.
 *
 * All methods are **non-suspending** — they return immediately and perform
 * their work on the client's internal coroutine scope. Responses arrive
 * asynchronously via [RjTgListener] callbacks.
 *
 * ## Quick start
 *
 * ```kotlin
 * val client = RjClient(RjClientConfig("host", 443, "alice"))
 * val tg     = RjTgClient(client)
 *
 * tg.addListener(object : RjTgListener {
 *     override fun onAuthResult(success: Boolean, message: String) {
 *         if (success) println("Telegram bound!")
 *         else println("Binding failed: $message")
 *     }
 *     override fun onMessageResult(success: Boolean) {
 *         if (!success) println("TG message delivery failed")
 *     }
 * })
 *
 * client.connect()
 * // wait for CONNECTED...
 * tg.auth("AB34KXYZ")   // code from Telegram bot
 * tg.sendMessage("hello from the client!")
 * ```
 *
 * @param client The [RjClient] to ride on.
 * @see RjTgListener
 */
@OptIn(RjInternalApi::class)
class RjTgClient(private val client: RjClient) {

    private val log = LoggerFactory.getLogger(RjTgClient::class.java)
    private val listeners = CopyOnWriteArrayList<RjTgListener>()

    init {
        client.registerHandler("tg.") { packet -> dispatch(packet) }
    }

    /**
     * Register a [listener] for Telegram operation results.
     */
    fun addListener(listener: RjTgListener) { listeners.add(listener) }

    /** Remove a previously registered [listener]. */
    fun removeListener(listener: RjTgListener) { listeners.remove(listener) }

    /**
     * Bind the current nickname to a Telegram chat using an 8-character code.
     *
     * The user obtains the code by messaging `/start` to the RJLink Telegram bot.
     * The server verifies the code and creates a binding record.
     * If the code is invalid, the server returns `tg.auth.fail` and clears any
     * previous Telegram binding for this nick.
     *
     * The result arrives asynchronously via [RjTgListener.onAuthResult].
     *
     * @param code The 8-character code (e.g. `"AB34KXYZ"`).
     */
    fun auth(code: String) {
        client.launchInternal {
            client.send(
                Packet(
                    type = PacketTypes.TG_AUTH,
                    seq = client.nextSeq(),
                    data = mapOf("code" to code)
                )
            )
        }
    }

    /**
     * Remove the Telegram binding for the current nickname.
     *
     * After unbinding, [sendMessage] will fail until a new binding is established.
     */
    fun unbind() {
        client.launchInternal {
            client.send(Packet(PacketTypes.TG_UNBIND, client.nextSeq()))
        }
    }

    /**
     * Send a text message to the Telegram chat bound to the current nickname.
     *
     * The result arrives asynchronously via [RjTgListener.onMessageResult].
     *
     * @param text The message to send.
     */
    fun sendMessage(text: String) {
        client.launchInternal {
            client.send(
                Packet(
                    type = PacketTypes.TG_SEND,
                    seq = client.nextSeq(),
                    data = mapOf("text" to text)
                )
            )
        }
    }

    private fun dispatch(packet: Packet) {
        when (packet.type) {
            PacketTypes.TG_AUTH_OK -> fireAuth(true, packet.data["message"] ?: "ok")
            PacketTypes.TG_AUTH_FAIL -> fireAuth(false, packet.data["message"] ?: "failed")
            PacketTypes.TG_SEND_OK -> fireMessage(true)
            PacketTypes.TG_SEND_FAIL -> fireMessage(false)
            else -> log.debug("Unhandled TG packet type={}", packet.type)
        }
    }

    private fun fireAuth(success: Boolean, message: String) {
        listeners.forEach {
            runCatching { it.onAuthResult(success, message) }
                .onFailure { e -> log.warn("TG auth listener threw", e) }
        }
    }

    private fun fireMessage(success: Boolean) {
        listeners.forEach {
            runCatching { it.onMessageResult(success) }
                .onFailure { e -> log.warn("TG message listener threw", e) }
        }
    }
}
