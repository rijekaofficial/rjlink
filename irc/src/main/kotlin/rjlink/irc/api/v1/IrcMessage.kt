package rjlink.irc.api.v1

/**
 * Incoming IRC chat message delivered to [RjIrcListener.onMessageReceived].
 *
 * ### Example
 * ```kotlin
 * val msg: IrcMessage = ...  // from onMessageReceived callback
 * println("<${msg.senderNick}@${msg.target}> ${msg.text}")
 * // Output: <alice@#general> hello everyone!
 * ```
 *
 * @property target    Channel name the message was sent to (e.g. `"#general"`).
 * @property senderNick Nickname of the user who sent the message.
 * @property text      The message body.
 */
data class IrcMessage(
    val target: String,
    val senderNick: String,
    val text: String
)
