package rjlink.core.client

/**
 * User-facing client configuration. Only the network endpoint and nickname
 * are configurable; all security, timeout and reconnect settings are internal
 * to the library and not exposed.
 *
 * ### Example
 * ```kotlin
 * val config = RjClientConfig("rjlink.example.com", 443, "alice")
 * val client = RjClient(config)
 * ```
 *
 * @param host Server hostname or IP address.
 * @param port Server port (typically 443 for WSS, 8888 for plain WS).
 * @param nick The nickname to authenticate as. Must be non-blank.
 * @throws IllegalArgumentException if any field fails validation.
 */
class RjClientConfig(
    val host: String,
    val port: Int,
    val nick: String
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535" }
        require(nick.isNotBlank()) { "nick must not be blank" }
    }
}
