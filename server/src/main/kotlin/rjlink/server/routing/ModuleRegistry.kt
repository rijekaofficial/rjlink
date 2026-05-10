package rjlink.server.routing

import rjlink.core.server.ServerModule

/** Simple registry of server-side modules; resolution is linear which is fine for <20 modules. */
class ModuleRegistry {
    private val modules = mutableListOf<ServerModule>()

    fun register(module: ServerModule) { modules.add(module) }

    fun resolve(packetType: String): ServerModule? =
        modules.firstOrNull { it.supports(packetType) }

    fun all(): List<ServerModule> = modules.toList()
}
