package rjlink.core.packet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PacketCodecTest {

    @Test
    fun `round-trip preserves all fields`() {
        val original = Packet(
            type = "irc.msg",
            seq = 42,
            data = mapOf("target" to "#general", "text" to "hello, world")
        )
        val decoded = PacketCodec.decode(PacketCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip works for empty data map`() {
        val original = Packet(type = "heartbeat", seq = 1, data = emptyMap())
        assertEquals(original, PacketCodec.decode(PacketCodec.encode(original)))
    }

    @Test
    fun `string extension returns value and throws when missing`() {
        val data = mapOf("nick" to "alice")
        assertEquals("alice", data.string("nick"))
        assertFailsWith<IllegalArgumentException> { data.string("missing") }
    }

    @Test
    fun `int extension parses numeric string`() {
        val data = mapOf("count" to "42", "bad" to "xx")
        assertEquals(42, data.int("count"))
        assertFailsWith<IllegalArgumentException> { data.int("bad") }
        assertFailsWith<IllegalArgumentException> { data.int("missing") }
        assertNull(data.intOrNull("bad"))
    }
}
