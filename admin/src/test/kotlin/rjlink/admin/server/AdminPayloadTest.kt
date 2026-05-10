package rjlink.admin.server

import kotlin.test.Test
import kotlin.test.assertEquals

class AdminPayloadTest {

    @Test
    fun `roundtrip preserves records and order`() {
        val rows = listOf(
            mapOf("nick" to "alice", "admin" to "false"),
            mapOf("nick" to "bob", "admin" to "true")
        )
        val encoded = AdminPayload.encode("s", rows)
        assertEquals("2", encoded["count"])
        assertEquals("alice", encoded["s.0.nick"])
        assertEquals("true", encoded["s.1.admin"])

        val decoded = AdminPayload.decode("s", encoded)
        assertEquals(rows, decoded)
    }

    @Test
    fun `empty list encodes count 0`() {
        val encoded = AdminPayload.encode("x", emptyList())
        assertEquals("0", encoded["count"])
        assertEquals(emptyList(), AdminPayload.decode("x", encoded))
    }

    @Test
    fun `decode tolerates missing count`() {
        assertEquals(emptyList(), AdminPayload.decode("x", emptyMap()))
    }
}
