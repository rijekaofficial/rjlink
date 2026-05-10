package rjlink.core.client.internal

import org.slf4j.LoggerFactory
import rjlink.core.packet.Packet
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routes incoming packets to registered client-side handlers.
 *
 * Each client module (IRC, TG) registers a [Handler] for the packet type prefixes it cares
 * about. The dispatcher is thread-safe and non-blocking.
 */
internal class PacketDispatcher {
    private val log = LoggerFactory.getLogger(PacketDispatcher::class.java)

    fun interface Handler {
        suspend fun handle(packet: Packet)
    }

    private data class Entry(val prefix: String, val handler: Handler)

    private val handlers = CopyOnWriteArrayList<Entry>()

    fun register(prefix: String, handler: Handler) {
        handlers.add(Entry(prefix, handler))
    }

    fun unregister(handler: Handler) {
        handlers.removeAll { it.handler === handler }
    }

    suspend fun dispatch(packet: Packet) {
        var matched = false
        for (entry in handlers) {
            if (packet.type == entry.prefix.trimEnd('.') || packet.type.startsWith(entry.prefix)) {
                matched = true
                try {
                    entry.handler.handle(packet)
                } catch (e: Exception) {
                    log.warn("Client handler threw for type={} : {}", packet.type, e.message)
                }
            }
        }
        if (!matched) {
            log.debug("Unhandled packet type={}", packet.type)
        }
    }
}
