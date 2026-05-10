package rjlink.core.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rjlink.core.RjInternalApi
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketCodec
import rjlink.core.packet.PacketTypes
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class SessionState { PENDING_AUTH, ACTIVE, CLOSED }

/**
 * Represents a single connected client on the server side.
 *
 * The session exposes a non-suspending [send] API backed by an internal outbound [Channel].
 * The transport implementation is responsible for draining the channel and pushing frames
 * into the underlying WebSocket.
 */
class Session internal constructor(
    val id: Long,
    private val outbound: Channel<ByteArray>,
    private val onCloseHook: suspend (Session, CloseReason) -> Unit
) {
    data class CloseReason(val code: Short, val text: String)

    private val stateRef = AtomicReference(SessionState.PENDING_AUTH)
    private val nickRef = AtomicReference<String?>(null)
    private val lastHeartbeatRef = AtomicLong(System.currentTimeMillis())
    private val isAdminRef = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sendMutex = Mutex()

    val state: SessionState get() = stateRef.get()
    val nick: String? get() = nickRef.get()
    val lastHeartbeatMs: Long get() = lastHeartbeatRef.get()
    val isAdmin: Boolean get() = isAdminRef.get()

    /** Mark this session as administratively elevated. Idempotent. */
    @RjInternalApi
    fun markAdmin() { isAdminRef.set(true) }

    internal fun markAuthenticated(nick: String) {
        nickRef.set(nick)
        stateRef.set(SessionState.ACTIVE)
    }

    internal fun markClosed() {
        stateRef.set(SessionState.CLOSED)
    }

    /** Mark this session as having sent a heartbeat just now. Called by server transport. */
    @RjInternalApi
    fun touchHeartbeat() {
        lastHeartbeatRef.set(System.currentTimeMillis())
    }

    /** Serialize and enqueue a packet. Thread-safe; order is preserved per-session. */
    suspend fun send(packet: Packet) {
        if (state == SessionState.CLOSED) return
        val bytes = PacketCodec.encode(packet)
        sendMutex.withLock { outbound.send(bytes) }
    }

    /** Send a sys.error packet with the given code and message. */
    suspend fun sendError(code: String, message: String) {
        send(
            Packet(
                type = PacketTypes.SYS_ERROR,
                seq = 0,
                data = mapOf("code" to code, "message" to message)
            )
        )
    }

    /** Request graceful close via the session manager. */
    suspend fun close(code: Short, reason: String) {
        if (stateRef.getAndSet(SessionState.CLOSED) == SessionState.CLOSED) return
        onCloseHook(this, CloseReason(code, reason))
    }

    companion object {
        private val idGen = AtomicInteger(0)
        internal fun nextId(): Long = idGen.incrementAndGet().toLong()
    }
}
