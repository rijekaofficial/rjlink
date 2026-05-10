package rjlink.core.packet

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * A single application-level message in the RJLink protocol.
 *
 * Every packet carries three fields:
 * - [type] — a dot-separated string identifier (e.g. `"irc.join"`, `"tg.send"`);
 *   the server routes the packet to the module that claims the corresponding prefix.
 * - [seq] — a monotonic sequence number set by the client; the server copies it
 *   verbatim into the response so the caller can correlate request ↔ reply.
 *   Server-initiated (push) packets use `seq = 0`.
 * - [data] — a flat string map that carries the payload. All numeric values are
 *   stored as their string representation (e.g. `"42"`). Typed access is provided
 *   by the extension functions [string], [int], [long] and their `*OrNull` variants.
 *
 * ### Wire format
 *
 * Packets are encoded as [CBOR](https://www.rfc-editor.org/rfc/rfc8949) via
 * [kotlinx-serialization-cbor][kotlinx.serialization.cbor.Cbor].
 * Use [PacketCodec.encode] / [PacketCodec.decode] to convert between [Packet]
 * and raw bytes.
 *
 * ### Quick example
 *
 * ```kotlin
 * val packet = Packet(
 *     type = "irc.msg",
 *     seq  = 3,
 *     data = mapOf("target" to "#general", "text" to "hello")
 * )
 * val bytes = PacketCodec.encode(packet)
 * ```
 *
 * @see PacketCodec
 * @see PacketTypes
 * @see string
 * @see int
 * @see long
 */
@Serializable
data class Packet(
    /** Dot-separated packet type identifier, e.g. `"irc.msg"`, `"tg.auth"`. */
    val type: String,
    /**
     * Monotonic sequence number. Client increments on each outbound packet;
     * server echoes it back in the corresponding reply.
     * Server-initiated (push) packets carry `seq = 0`.
     */
    val seq: Int,
    /**
     * Flat string payload. All values are strings; numeric values are stored
     * as their decimal representation (`"42"`, `"1700000000000"`).
     * Empty map when the packet carries no payload (e.g. `heartbeat`).
     */
    val data: Map<String, String> = emptyMap()
)

/**
 * CBOR encoder/decoder for [Packet].
 *
 * Typical usage on the server side (raw WebSocket frame → [Packet]):
 * ```kotlin
 * val packet = PacketCodec.decode(frame.readBytes())
 * ```
 *
 * And on the client side ([Packet] → raw bytes):
 * ```kotlin
 * val bytes = PacketCodec.encode(packet)
 * ws.send(Frame.Binary(true, bytes))
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
object PacketCodec {
    private val cbor = Cbor {
        ignoreUnknownKeys = true
    }

    /** Serialize [packet] into a CBOR byte array. */
    fun encode(packet: Packet): ByteArray = cbor.encodeToByteArray(Packet.serializer(), packet)

    /** Deserialize a CBOR byte array into a [Packet]. Throws on malformed input. */
    fun decode(bytes: ByteArray): Packet = cbor.decodeFromByteArray(Packet.serializer(), bytes)
}

// ──────────────────────────────────────────────────────────────────────────────
// Typed access helpers for Packet.data (Map<String, String>)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Returns the string value for [key], or throws [IllegalArgumentException] if absent.
 *
 * ```kotlin
 * val nick: String = packet.data.string("nick")
 * ```
 */
fun Map<String, String>.string(key: String): String =
    this[key] ?: throw IllegalArgumentException("missing string field '$key'")

/**
 * Returns the string value for [key], or `null` if absent.
 *
 * ```kotlin
 * val reason: String? = packet.data.stringOrNull("reason")
 * ```
 */
fun Map<String, String>.stringOrNull(key: String): String? = this[key]

/**
 * Parses the value for [key] as [Int], or throws [IllegalArgumentException]
 * if the key is absent or the value is not a valid integer.
 *
 * ```kotlin
 * val port: Int = packet.data.int("port")
 * ```
 */
fun Map<String, String>.int(key: String): Int {
    val raw = this[key] ?: throw IllegalArgumentException("missing int field '$key'")
    return raw.toIntOrNull() ?: throw IllegalArgumentException("field '$key' is not an Int: $raw")
}

/**
 * Parses the value for [key] as [Int], or returns `null` if absent / unparseable.
 *
 * ```kotlin
 * val port: Int? = packet.data.intOrNull("port")
 * ```
 */
fun Map<String, String>.intOrNull(key: String): Int? = this[key]?.toIntOrNull()

/**
 * Parses the value for [key] as [Long], or throws [IllegalArgumentException]
 * if the key is absent or the value is not a valid long.
 *
 * ```kotlin
 * val timestamp: Long = packet.data.long("bannedAt")
 * ```
 */
fun Map<String, String>.long(key: String): Long {
    val raw = this[key] ?: throw IllegalArgumentException("missing long field '$key'")
    return raw.toLongOrNull() ?: throw IllegalArgumentException("field '$key' is not a Long: $raw")
}

/**
 * Parses the value for [key] as [Long], or returns `null` if absent / unparseable.
 *
 * ```kotlin
 * val timestamp: Long? = packet.data.longOrNull("bannedAt")
 * ```
 */
fun Map<String, String>.longOrNull(key: String): Long? = this[key]?.toLongOrNull()
