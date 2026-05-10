package rjlink.core.client

/**
 * Callback contract for connection-level lifecycle events that the application
 * must react to.
 *
 * Register via [RjClient.addConnectionListener].
 *
 * ### Example
 * ```kotlin
 * client.addConnectionListener(object : RjConnectionListener {
 *     override fun onReconnectFailed() {
 *         println("All reconnect attempts exhausted — giving up.")
 *     }
 * })
 * ```
 */
interface RjConnectionListener {
    /**
     * Called when the internal reconnect strategy has exhausted all retry
     * attempts (10 attempts with exponential backoff from 1 s to 30 s).
     *
     * The client is in [RjClient.State.DISCONNECTED] and will **not**
     * try to reconnect again automatically. The application may call
     * [RjClient.connect] to start a fresh connection attempt.
     */
    fun onReconnectFailed()
}
