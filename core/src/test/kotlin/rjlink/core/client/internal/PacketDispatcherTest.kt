package rjlink.core.client.internal

import kotlinx.coroutines.test.runTest
import rjlink.core.packet.Packet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class PacketDispatcherTest {

    @Test
    fun `only handlers whose prefix matches are invoked`() = runTest {
        val dispatcher = PacketDispatcher()
        val ircCount = AtomicInteger()
        val tgCount = AtomicInteger()

        dispatcher.register("irc.") { ircCount.incrementAndGet() }
        dispatcher.register("tg.") { tgCount.incrementAndGet() }

        dispatcher.dispatch(Packet("irc.msg.incoming", 1))
        dispatcher.dispatch(Packet("tg.auth.ok", 2))
        dispatcher.dispatch(Packet("irc.error", 3))
        dispatcher.dispatch(Packet("something.else", 4))

        assertEquals(2, ircCount.get())
        assertEquals(1, tgCount.get())
    }

    @Test
    fun `handler isolation - exception in one does not affect others`() = runTest {
        val dispatcher = PacketDispatcher()
        val delivered = AtomicInteger()
        dispatcher.register("x.") { throw RuntimeException("boom") }
        dispatcher.register("x.") { delivered.incrementAndGet() }

        dispatcher.dispatch(Packet("x.y", 0))
        assertEquals(1, delivered.get())
    }
}
