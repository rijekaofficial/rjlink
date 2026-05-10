package rjlink.core.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import rjlink.core.RjInternalApi
import rjlink.core.client.internal.ConnectionHandler
import rjlink.core.client.internal.PacketDispatcher
import rjlink.core.client.internal.ReconnectStrategy
import rjlink.core.exception.AuthException
import rjlink.core.exception.ConnectionException
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketTypes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The main entry point for the RJLink client library.
 *
 * Manages the full connection lifecycle: WebSocket transport, authentication,
 * automatic reconnection with exponential back-off, heartbeat, and dispatch
 * of inbound packets to registered client modules.
 *
 * All public methods are **non-suspending** — they return immediately and
 * perform their work on the client's internal coroutine scope.
 *
 * ## Quick start
 *
 * ```kotlin
 * // 1. Create configuration
 * val config = RjClientConfig("rjlink.example.com", 443, "alice")
 *
 * // 2. Create client
 * val client = RjClient(config)
 *
 * // 3. (Optional) listen for connection events
 * client.addConnectionListener(object : RjConnectionListener {
 *     override fun onReconnectFailed() {
 *         System.err.println("Reconnect exhausted!")
 *     }
 * })
 *
 * // 4. Connect — authenticates automatically using the nick from config
 * client.connect()
 *
 * // 5. Use module APIs (RjIrcClient, RjTgClient, etc.)
 *
 * // 6. When done
 * client.shutdown()
 * ```
 *
 * ## Reconnection
 *
 * If the WebSocket connection drops unexpectedly, the client automatically
 * retries with exponential back-off (1 s → 30 s, up to 10 attempts).
 * After a successful reconnect the client re-authenticates and replays
 * any module-specific re-subscription hooks (e.g. IRC channel joins).
 *
 * If all 10 attempts fail, [RjConnectionListener.onReconnectFailed] is
 * invoked and the client stays in [State.DISCONNECTED]. The application
 * can call [connect] again to start a fresh attempt.
 *
 * ## WSS
 *
 * By default the client connects over `wss://` and validates the server
 * certificate. For local development you can disable this with the
 * system property `-Drjlink.client.useWss=false`.
 *
 * @param config Connection parameters (host, port, nick).
 * @see RjClientConfig
 * @see RjConnectionListener
 * @see State
 */
class RjClient(private val config: RjClientConfig) {

    /**
     * The client's connection state.
     *
     * Transitions:
     * ```
     * DISCONNECTED → CONNECTING → AUTHENTICATING → CONNECTED
     *       ↑              │             │              │
     *       └──────────────┴─────────────┴──────────────┘
     *                    (on error / disconnect)
     * ```
     *
     * Use [getState] to poll the current value from any thread.
     */
    enum class State {
        /** Not connected; no reconnect in progress. */
        DISCONNECTED,

        /** WebSocket handshake in progress. */
        CONNECTING,

        /** WebSocket is open; waiting for `auth.ok` / `auth.fail`. */
        AUTHENTICATING,

        /** Authenticated and ready to send/receive application packets. */
        CONNECTED
    }

    private val log = LoggerFactory.getLogger(RjClient::class.java)

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private val useWss: Boolean =
        System.getProperty("rjlink.client.useWss")?.toBooleanStrictOrNull() ?: true

    private val heartbeatIntervalSeconds: Long = 30

    private val connection = ConnectionHandler(
        host = config.host,
        port = config.port,
        useWss = useWss,
        heartbeatIntervalSeconds = heartbeatIntervalSeconds,
        parentScope = scope
    )

    private val dispatcher = PacketDispatcher()
    private val connectionListeners = CopyOnWriteArrayList<RjConnectionListener>()
    private val stateRef = AtomicReference(State.DISCONNECTED)
    private val stateMutex = Mutex()
    private val reconnect = ReconnectStrategy()

    private val userDisconnected = AtomicBoolean(false)
    private val reconnectRunning = AtomicBoolean(false)

    private val reSubscribeHooks = CopyOnWriteArrayList<suspend () -> Unit>()

    // ── Internal API (for module implementors) ──────────────────────────────

    /**
     * Register a handler for all inbound packets whose [type][Packet.type]
     * starts with [prefix].
     *
     * For example, a module that handles all IRC packets would call:
     * ```kotlin
     * client.registerHandler("irc.") { packet -> ... }
     * ```
     *
     * @throws IllegalStateException if called after [connect].
     */
    @RjInternalApi
    fun registerHandler(prefix: String, handler: suspend (Packet) -> Unit) {
        dispatcher.register(prefix) { packet -> handler(packet) }
    }

    /**
     * Register a callback to be invoked after each successful reconnect
     * and re-authentication. Used by modules to restore server-side state
     * (e.g. re-join IRC channels).
     */
    @RjInternalApi
    fun registerReSubscribeHook(hook: suspend () -> Unit) {
        reSubscribeHooks.add(hook)
    }

    /**
     * Send a packet over the current connection.
     *
     * @throws ConnectionException if the client is not connected.
     */
    @RjInternalApi
    suspend fun send(packet: Packet) { connection.send(packet) }

    /**
     * Allocate the next monotonic sequence number for an outbound packet.
     */
    @RjInternalApi
    fun nextSeq(): Int = connection.nextSeq()

    /**
     * Run [block] in the client's internal coroutine scope.
     * Useful for module methods that need to call [send] but are
     * themselves non-suspending.
     */
    @RjInternalApi
    fun launchInternal(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns the current [State] of the client. Safe to call from any thread.
     *
     * Typical usage in a game loop or UI tick:
     * ```kotlin
     * if (client.getState() == RjClient.State.CONNECTED) {
     *     irc.sendMessage("#general", "hello")
     * }
     * ```
     */
    fun getState(): State = stateRef.get()

    /**
     * Register a [listener] for connection-level events.
     *
     * @see RjConnectionListener
     */
    fun addConnectionListener(listener: RjConnectionListener) { connectionListeners.add(listener) }

    /**
     * Remove a previously registered [listener].
     */
    fun removeConnectionListener(listener: RjConnectionListener) { connectionListeners.remove(listener) }

    /**
     * Initiate a connection to the server.
     *
     * This method returns immediately. The client transitions through
     * [State.CONNECTING] → [State.AUTHENTICATING] → [State.CONNECTED]
     * on success, or back to [State.DISCONNECTED] on failure.
     *
     * If the connection drops, the client will automatically attempt
     * to reconnect with exponential back-off (up to 10 attempts).
     *
     * ```kotlin
     * client.connect()
     * // poll getState() or rely on RjConnectionListener callbacks
     * ```
     */
    fun connect() {
        userDisconnected.set(false)
        scope.launch { openAndAuth(firstTime = true) }
    }

    /**
     * Gracefully disconnect from the server.
     *
     * Suppresses automatic reconnection — the client will stay in
     * [State.DISCONNECTED] until [connect] is called again.
     */
    fun disconnect() {
        userDisconnected.set(true)
        scope.launch {
            stateMutex.withLock {
                stateRef.set(State.DISCONNECTED)
                connection.disconnect()
            }
        }
    }

    /**
     * Disconnect and release all underlying resources (HTTP client,
     * coroutine scope). Call this when the client is no longer needed.
     *
     * After `shutdown()` the client instance must not be reused.
     */
    fun shutdown() {
        disconnect()
        connection.shutdown()
        supervisor.cancel()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private suspend fun openAndAuth(firstTime: Boolean) {
        val authResult = kotlinx.coroutines.CompletableDeferred<Boolean>()
        try {
            stateRef.set(State.CONNECTING)

            connection.connect(
                onPacket = { packet -> handleIncoming(packet, authResult) },
                onClosed = { onTransportClosed() }
            )

            stateRef.set(State.AUTHENTICATING)
            connection.send(
                Packet(
                    type = PacketTypes.AUTH,
                    seq = connection.nextSeq(),
                    data = mapOf("nick" to config.nick)
                )
            )

            val ok = authResult.await()
            if (!ok) {
                stateRef.set(State.DISCONNECTED)
                userDisconnected.set(true)
                connection.disconnect()
                throw AuthException("authentication failed")
            }

            stateRef.set(State.CONNECTED)
            reconnect.reset()

            if (!firstTime) {
                for (hook in reSubscribeHooks) {
                    runCatching { hook() }.onFailure {
                        log.warn("Re-subscribe hook failed: {}", it.message)
                    }
                }
            }
        } catch (e: AuthException) {
            log.warn("Auth failed, not retrying")
            throw e
        } catch (e: Exception) {
            log.warn("Connect/auth failed: {}", e.message)
            stateRef.set(State.DISCONNECTED)
            if (!userDisconnected.get()) scheduleReconnect()
        }
    }

    private suspend fun handleIncoming(
        packet: Packet,
        authResult: kotlinx.coroutines.CompletableDeferred<Boolean>
    ) {
        when (packet.type) {
            PacketTypes.AUTH_OK -> authResult.complete(true)
            PacketTypes.AUTH_FAIL -> {
                log.warn("Auth failed: {}", packet.data["reason"])
                authResult.complete(false)
            }
            PacketTypes.HEARTBEAT -> { /* pong from server */ }
            PacketTypes.SYS_DISCONNECT -> {
                log.info("Server disconnect: {}", packet.data["reason"])
            }
            PacketTypes.SYS_ERROR -> {
                log.warn("Server error: {} {}", packet.data["code"], packet.data["message"])
            }
            else -> dispatcher.dispatch(packet)
        }
    }

    private fun onTransportClosed() {
        if (userDisconnected.get()) return
        if (stateRef.get() == State.DISCONNECTED) return
        stateRef.set(State.DISCONNECTED)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (userDisconnected.get()) return
        if (!reconnectRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (!userDisconnected.get()) {
                    val delayMs = reconnect.nextDelayMs()
                    if (delayMs == null) {
                        log.warn("Reconnect attempts exhausted")
                        stateRef.set(State.DISCONNECTED)
                        connectionListeners.forEach {
                            runCatching { it.onReconnectFailed() }.onFailure { e ->
                                log.warn("onReconnectFailed handler threw", e)
                            }
                        }
                        return@launch
                    }
                    delay(delayMs)
                    if (userDisconnected.get()) return@launch
                    try {
                        openAndAuth(firstTime = false)
                        if (stateRef.get() == State.CONNECTED) return@launch
                    } catch (e: Exception) {
                        log.info("Reconnect attempt {} failed: {}", reconnect.currentAttempt, e.message)
                    }
                }
            } finally {
                reconnectRunning.set(false)
            }
        }
    }
}
