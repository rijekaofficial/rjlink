package rjlink.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import rjlink.core.client.RjClient
import rjlink.core.client.RjClientConfig
import rjlink.core.client.RjConnectionListener
import rjlink.irc.api.v1.IrcMessage
import rjlink.irc.api.v1.RjIrcClient
import rjlink.irc.api.v1.RjIrcListener

/**
 * End-to-end demo: two clients connect to a local RJLink server,
 * join the same IRC channel, exchange messages and disconnect.
 *
 * This file doubles as a **smoke-test** and as a **reference implementation**
 * showing how to wire the public API of the RJLink client library.
 *
 * ## How to run
 *
 * ```bash
 * # Start the server first (see docs/QUICKSTART.md)
 * ./gradlew :server:run
 *
 * # Then run the demo (defaults to 127.0.0.1:18080)
 * ./gradlew :examples:demo-client:run
 *
 * # Or with custom host/port
 * ./gradlew :examples:demo-client:run --args="myserver.example.com 443"
 * ```
 *
 * ## What happens
 *
 * 1. Two clients ("alice" and "bob") are created with [RjClientConfig].
 * 2. Each client gets an [RjIrcClient] for IRC functionality.
 * 3. Listeners are registered for incoming messages and connection events.
 * 4. Both clients connect and authenticate automatically.
 * 5. They join channel `#demo` and exchange two messages.
 * 6. After a short delay the clients shut down.
 */
fun main(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 18080
    val keepaliveSec = args.getOrNull(2)?.toIntOrNull() ?: 0

    // ── Step 1: Disable WSS for local development ──────────────────────────
    // The default is wss:// (TLS). For a local server without TLS, set this
    // system property before creating any RjClient instances.
    System.setProperty("rjlink.client.useWss", "false")

    runBlocking {
        // ── Step 2: Build two clients with IRC module ──────────────────────
        val alice = buildClient(host, port, "alice")
        val bob = buildClient(host, port, "bob")

        // ── Step 3: Connect (authenticates automatically) ───────────────────
        alice.client.connect()
        bob.client.connect()

        // ── Step 4: Wait for authentication to complete ──────────────────
        // In a real application you'd poll getState() in your main loop
        // or rely on a RjConnectionListener callback.
        waitForState(alice.client, RjClient.State.CONNECTED)
        waitForState(bob.client, RjClient.State.CONNECTED)
        println("[demo] both clients authenticated")

        // ── Step 5: Join the same channel ─────────────────────────────────
        alice.irc.join("#demo")
        bob.irc.join("#demo")
        delay(300) // give the server time to process both joins

        // ── Step 6: Exchange messages ───────────────────────────────────────
        // Alice sends → Bob receives (via RjIrcListener.onMessageReceived)
        println("[demo] alice says hi")
        alice.irc.sendMessage("#demo", "hello bob")
        delay(500)

        // Bob sends → Alice receives
        bob.irc.sendMessage("#demo", "hi alice!")
        delay(500)

        // ── Step 7: Optional keepalive for admin testing ──────────────────
        // Pass a third arg > 0 to keep the session alive so you can test
        // admin commands (kick, ban, broadcast) from admin-cli.
        if (keepaliveSec > 0) {
            println("[demo] keepalive for ${keepaliveSec}s (admin can list/kick/ban now)")
            delay(keepaliveSec * 1000L)
        }

        // ── Step 8: Clean shutdown ─────────────────────────────────────────
        alice.client.shutdown()
        bob.client.shutdown()
        println("[demo] done")
    }
}

/**
 * Holds a [RjClient] and its [RjIrcClient] together.
 */
private class ClientBundle(val client: RjClient, val irc: RjIrcClient)

/**
 * Creates a fully wired client with IRC module and listeners.
 *
 * This is the pattern application developers would follow:
 * 1. Create [RjClientConfig] with server address and nickname
 * 2. Create [RjClient] from the config
 * 3. Create module clients ([RjIrcClient], etc.) passing the [RjClient]
 * 4. Register listeners for events you care about
 * 5. Call [RjClient.connect] to start
 */
private fun buildClient(host: String, port: Int, nick: String): ClientBundle {
    val config = RjClientConfig(host, port, nick)
    val client = RjClient(config)
    val irc = RjIrcClient(client)

    // Listen for connection-level events (e.g. reconnect exhaustion)
    client.addConnectionListener(object : RjConnectionListener {
        override fun onReconnectFailed() {
            System.err.println("[$nick] all reconnect attempts exhausted")
        }
    })

    // Listen for IRC messages and errors
    irc.addListener(object : RjIrcListener {
        override fun onMessageReceived(m: IrcMessage) {
            // In a real application you'd render this in the UI
            println("[$nick] <${m.senderNick}@${m.target}> ${m.text}")
        }
        override fun onError(channel: String?, error: String) {
            System.err.println("[$nick] IRC error on $channel: $error")
        }
    })

    return ClientBundle(client, irc)
}

/**
 * Polls [client.getState] until it reaches [state] or [timeoutMs] elapses.
 *
 * In production code you would typically not need this — you'd check state
 * in your application's main loop or handle events via [RjConnectionListener].
 */
private suspend fun waitForState(
    client: RjClient,
    state: RjClient.State,
    timeoutMs: Long = 5_000
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (client.getState() != state) {
        if (System.currentTimeMillis() > deadline)
            error("timed out waiting for $state (got ${client.getState()})")
        delay(50)
    }
}
