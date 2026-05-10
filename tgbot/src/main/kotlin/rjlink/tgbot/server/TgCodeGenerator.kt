package rjlink.tgbot.server

import java.security.SecureRandom

/**
 * Generates 8-character one-shot binding codes for the Telegram bot.
 *
 * The alphabet excludes visually confusable characters (`0/O`, `1/I/L`) so users
 * can read codes off the screen without mistakes. Codes are random; uniqueness
 * is enforced at the storage layer (see [TgBindingStore.isCodeTaken]).
 *
 * Multiple game clients may bind to the same Telegram chat using the same code:
 * `bindByCode` looks up the chat id once and creates a per-nick binding. The code
 * itself remains valid until the "—генерировать код" button is pressed, which replaces it with a fresh value.
 */
class TgCodeGenerator(
    private val random: SecureRandom = SecureRandom()
) {
    private companion object {
        const val CODE_LENGTH = 8
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }

    /** Returns a fresh random code. The result is unbiased modulo the alphabet length. */
    fun random(): String {
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }
}

