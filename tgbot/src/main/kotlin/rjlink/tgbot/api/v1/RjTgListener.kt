package rjlink.tgbot.api.v1

/**
 * Callback contract for Telegram-related operation results.
 *
 * Register via [RjTgClient.addListener]. All methods are invoked on the
 * library's internal dispatcher; implementations must not block for long
 * periods.
 *
 * ### Example
 * ```kotlin
 * tg.addListener(object : RjTgListener {
 *     override fun onAuthResult(success: Boolean, message: String) {
 *         if (success) println("Telegram linked: $message")
 *         else println("Link failed: $message")
 *     }
 *     override fun onMessageResult(success: Boolean) {
 *         // called after every tg.sendMessage()
 *     }
 * })
 * ```
 */
interface RjTgListener {
    /**
     * Result of a [tg.auth][RjTgClient.auth] attempt.
     *
     * @param success `true` if the binding was created, `false` if the code
     *                was invalid.
     * @param message Human-readable description (e.g. `"telegram binding created"`
     *                 or `"invalid code; binding removed"`).
     */
    fun onAuthResult(success: Boolean, message: String)

    /**
     * Result of a [tg.sendMessage][RjTgClient.sendMessage] attempt.
     *
     * @param success `true` if the message was delivered to Telegram,
     *                `false` if delivery failed (no binding, API error, etc.).
     */
    fun onMessageResult(success: Boolean)
}
