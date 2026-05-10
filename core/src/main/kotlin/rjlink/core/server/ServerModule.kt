package rjlink.core.server

import rjlink.core.packet.Packet

/**
 * Pluggable server-side feature that handles a subset of packet types.
 *
 * Modules are registered in a `ModuleRegistry` (in the `server` module) and resolved
 * by packet type via `PacketRouter`.
 */
interface ServerModule {
    /** Unique module name, used in logs. */
    val name: String

    /** Packet type prefixes (e.g. "irc.", "tg.") this module is responsible for. */
    val supportedTypes: Set<String>

    /** True if this module wants to handle the given packet type. */
    fun supports(type: String): Boolean =
        supportedTypes.any { prefix -> type == prefix.trimEnd('.') || type.startsWith(prefix) }

    /** Handle a packet addressed to this module. Must be non-blocking. */
    suspend fun handlePacket(nick: String, packet: Packet, session: Session)

    /** Called by the session manager when an authenticated session disconnects. */
    suspend fun onSessionClosed(nick: String) {}

    /** Called once at server startup. */
    suspend fun start() {}

    /** Called once at server shutdown. */
    suspend fun stop() {}
}
