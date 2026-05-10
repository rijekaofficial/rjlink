package rjlink.server.routing

import org.slf4j.LoggerFactory
import rjlink.core.packet.Packet
import rjlink.core.server.Session

/** Dispatches authenticated inbound packets to the owning server module. */
class PacketRouter(private val registry: ModuleRegistry) {

    private val log = LoggerFactory.getLogger(PacketRouter::class.java)

    suspend fun handle(nick: String, packet: Packet, session: Session) {
        val module = registry.resolve(packet.type)
        if (module == null) {
            log.debug("No module handles type={}", packet.type)
            session.sendError(code = "400", message = "unknown packet type")
            return
        }
        try {
            module.handlePacket(nick, packet, session)
        } catch (e: Exception) {
            log.warn("Module {} threw for type={}: {}", module.name, packet.type, e.message)
            session.sendError(code = "500", message = "internal error")
        }
    }
}
