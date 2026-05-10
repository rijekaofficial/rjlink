package rjlink.core.exception

/**
 * Base sealed class for all errors thrown by the RJLink library.
 * All messages are in English.
 *
 * Catch a specific subclass when you need fine-grained handling:
 * ```kotlin
 * try {
 *     client.connect()
 * } catch (e: AuthException) {
 *     // nick rejected
 * } catch (e: ConnectionException) {
 *     // network issue
 * }
 * ```
 *
 * Or catch the base class for generic handling:
 * ```kotlin
 * catch (e: RjException) {
 *     logger.error("RJLink error: ${e.message}")
 * }
 * ```
 */
sealed class RjException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A network-level failure: could not establish a WebSocket connection,
 * the connection was lost, or an outgoing packet exceeded the 64 KB frame limit.
 */
class ConnectionException(message: String, cause: Throwable? = null) : RjException(message, cause)

/**
 * The server rejected the nickname during authentication
 * (nick already in use, banned, etc.).
 *
 * When this exception is thrown the client does **not** retry — the
 * application must decide what to do (pick a different nick, inform the user, etc.).
 */
class AuthException(message: String, cause: Throwable? = null) : RjException(message, cause)

/**
 * A protocol-level violation: malformed CBOR, unexpected packet type
 * in the current session state, or a missing required field.
 */
class ProtocolException(message: String, cause: Throwable? = null) : RjException(message, cause)

/**
 * An operation timed out (e.g. waiting for `auth.ok` took longer than
 * the internal deadline).
 */
class TimeoutException(message: String, cause: Throwable? = null) : RjException(message, cause)
