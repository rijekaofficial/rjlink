package rjlink.core.client.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.http.URLProtocol
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import rjlink.core.exception.ConnectionException
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketCodec
import rjlink.core.packet.ProtocolLimits
import rjlink.core.packet.PacketTypes
import rjlink.core.packet.ProtocolVersion
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-level WebSocket transport for the client.
 *
 * Responsibilities:
 *  - open a WSS connection to `wss://host:port/ws?v=N`
 *  - encode/decode CBOR frames
 *  - enforce the 64 KB frame limit on incoming traffic
 *  - run a heartbeat sender
 *  - invoke the provided callbacks for inbound packets / close events
 *
 * Reconnection logic lives one level up in `RjClient`.
 */
internal class ConnectionHandler(
    private val host: String,
    private val port: Int,
    private val useWss: Boolean,
    private val heartbeatIntervalSeconds: Long,
    private val parentScope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(ConnectionHandler::class.java)

    private val httpClient: HttpClient = HttpClient(CIO) {
        install(WebSockets) {
            maxFrameSize = ProtocolLimits.MAX_PACKET_SIZE_BYTES.toLong()
        }
    }

    private var session: WebSocketSession? = null
    private val writeMutex = Mutex()
    private val seq = AtomicInteger(0)
    private var heartbeatJob: Job? = null
    private var readerJob: Job? = null

    suspend fun connect(
        onPacket: suspend (Packet) -> Unit,
        onClosed: suspend (Throwable?) -> Unit
    ) {
        try {
            val ws = httpClient.webSocketSession {
                url {
                    protocol = if (useWss) URLProtocol.WSS else URLProtocol.WS
                    this.host = this@ConnectionHandler.host
                    this.port = this@ConnectionHandler.port
                    pathSegments = listOf("ws")
                    parameters.append("v", ProtocolVersion.CURRENT.toString())
                }
            }
            session = ws

            readerJob = parentScope.launch(Dispatchers.IO) {
                runCatching { readLoop(ws, onPacket) }
                    .onFailure { log.debug("Read loop ended: {}", it.message) }
                heartbeatJob?.cancel()
                session = null
                onClosed(null)
            }

            heartbeatJob = parentScope.launch {
                heartbeatLoop()
            }
        } catch (e: Exception) {
            throw ConnectionException("failed to connect to $host:$port", e)
        }
    }

    private suspend fun readLoop(ws: WebSocketSession, onPacket: suspend (Packet) -> Unit) {
        for (frame in ws.incoming) {
            when (frame) {
                is Frame.Binary -> {
                    val bytes = frame.data
                    if (bytes.size > ProtocolLimits.MAX_PACKET_SIZE_BYTES) {
                        log.warn("Dropping oversized frame: {} bytes", bytes.size)
                        ws.close()
                        return
                    }
                    val packet = try {
                        PacketCodec.decode(bytes)
                    } catch (e: Exception) {
                        log.warn("Failed to decode packet: {}", e.message)
                        continue
                    }
                    onPacket(packet)
                }
                is Frame.Close -> return
                else -> { /* text/ping/pong — ignore */ }
            }
        }
    }

    private suspend fun heartbeatLoop() {
        val intervalMs = heartbeatIntervalSeconds * 1_000
        while (parentScope.isActive && session != null) {
            kotlinx.coroutines.delay(intervalMs)
            val s = session ?: return
            try {
                send(Packet(type = PacketTypes.HEARTBEAT, seq = nextSeq(), data = emptyMap()))
            } catch (e: Exception) {
                log.debug("Heartbeat send failed: {}", e.message)
                runCatching { s.close() }
                return
            }
        }
    }

    fun nextSeq(): Int = seq.incrementAndGet()

    suspend fun send(packet: Packet) {
        val ws = session ?: throw ConnectionException("not connected")
        val bytes = PacketCodec.encode(packet)
        if (bytes.size > ProtocolLimits.MAX_PACKET_SIZE_BYTES) {
            throw ConnectionException("outgoing packet exceeds 64 KB limit")
        }
        writeMutex.withLock {
            ws.send(Frame.Binary(true, bytes))
        }
    }

    suspend fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { session?.close() }
        session = null
        readerJob?.cancel()
        readerJob = null
    }

    fun shutdown() {
        runCatching { httpClient.close() }
    }
}
