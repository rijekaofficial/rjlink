package rjlink.irc.api.v1

/**
 * Callback contract for IRC events.
 *
 * Register via [RjIrcClient.addListener]. All methods are invoked on the
 * library's internal dispatcher; implementations must not block for long
 * periods — offload heavy work if needed.
 *
 * ### Example
 * ```kotlin
 * irc.addListener(object : RjIrcListener {
 *     override fun onMessageReceived(message: IrcMessage) {
 *         chatHud.addLine("[${message.target}] <${message.senderNick}> ${message.text}")
 *     }
 *     override fun onError(channel: String?, error: String) {
 *         logger.warn("IRC error on $channel: $error")
 *     }
 * })
 * ```
 */
interface RjIrcListener {
    /**
     * A new chat message arrived from another user.
     *
     * @param message The incoming message with [target][IrcMessage.target] channel,
     *                [senderNick][IrcMessage.senderNick] and [text][IrcMessage.text].
     */
    fun onMessageReceived(message: IrcMessage)

    /**
     * An IRC-level error occurred (e.g. channel is full, not a member, etc.).
     *
     * @param channel The channel the error relates to, or `null` if not channel-specific.
     * @param error   Human-readable error description.
     */
    fun onError(channel: String?, error: String)
}
