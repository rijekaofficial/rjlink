package rjlink.tgbot.server

/**
 * Coordinates code generation, lookup and nick-to-chat binding for the Telegram bot.
 *
 * Codes are random 8-character strings generated on demand, stored in the binding
 * store and unique across all chats. The previous (HMAC-based) deterministic
 * scheme is gone: with the "Сгенерировать код" button users may rotate their code, which
 * invalidates the old one for any **future** binding attempts (already-bound
 * nicks keep working).
 *
 * Thread-safety is inherited from the underlying [TgBindingStore] implementation.
 */
class TgAuthManager(
    private val store: TgBindingStore,
    private val codes: TgCodeGenerator,
    private val maxGenerationAttempts: Int = 8
) {

    /**
     * Returns the current code for [tgChatId].
     *
     * If a code is already stored, it is returned unchanged (so `/start` is
     * idempotent: the user can send it many times and always sees the same code).
     * Otherwise a new random code is generated and persisted atomically.
     */
    fun getOrCreateCode(tgChatId: Long): String {
        store.findCodeByChatId(tgChatId)?.let { return it }
        return regenerateCode(tgChatId)
    }

    /**
     * Always generates a fresh code for [tgChatId], replacing any existing one.
     *
     * After this call:
     *  - the old code is no longer accepted by [bindByCode];
     *  - existing nick в†” chat bindings continue to work (they reference the
     *    chat id, not the code);
     *  - any future client that wants to bind to this chat must use the new code.
     *
     * @throws IllegalStateException if no unique code can be generated within
     *   [maxGenerationAttempts] (extremely unlikely; see
     *   [TgBindingStore.isCodeTaken]).
     */
    fun regenerateCode(tgChatId: Long): String {
        repeat(maxGenerationAttempts) {
            val candidate = codes.random()
            if (!store.isCodeTaken(candidate)) {
                store.upsertCode(tgChatId, candidate)
                return candidate
            }
        }
        error("could not generate a unique TG code after $maxGenerationAttempts attempts")
    }

    /**
     * Try to bind [nick] to whichever Telegram chat currently owns [code].
     *
     * If the same code is presented by multiple distinct nicks, all of them get
     * bound to the same chat. This is the supported mechanism for sharing one
     * Telegram chat across several Minecraft clients.
     *
     * @return true if the binding was created/updated; false if [code] is unknown
     *   or has been rotated via the "Сгенерировать код" button.
     */
    fun bindByCode(nick: String, code: String): Boolean {
        val chatId = store.findChatIdByCode(code) ?: return false
        store.bind(nick, chatId)
        return true
    }

    /** Removes the binding for [nick]. @return true if a binding was removed. */
    fun unbind(nick: String): Boolean = store.unbind(nick)

    /** @return the chat id [nick] is currently bound to, or null. */
    fun findChatId(nick: String): Long? = store.findByNick(nick)?.tgChatId
}

