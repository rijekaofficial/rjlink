package rjlink.tgbot.server

/**
 * Abstraction over the actual Telegram "send message" call.
 *
 * Wrapping the Bot API behind an interface keeps [TgServerModule] trivially testable
 * without network calls, and lets us swap the underlying library if needed.
 */
interface TgMessageSender {
    suspend fun sendText(chatId: Long, text: String): Boolean
}
