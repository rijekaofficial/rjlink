package rjlink.tgbot.server

/**
 * Persistent storage of Telegram chat <-> nick bindings and per-chat auth codes.
 *
 * Implementations must be thread-safe. The server module holds exactly one instance,
 * wired to the SQLite-backed Exposed layer (see `rjlink.server.db`).
 */
interface TgBindingStore {

    data class Binding(val nick: String, val tgChatId: Long)

    /**
     * Persist (or update) the auth code for a chat. Replaces any previous code
     * stored for [tgChatId]; the previous code is no longer accepted by
     * [findChatIdByCode]. Returns the stored code.
     */
    fun upsertCode(tgChatId: Long, code: String): String

    /**
     * @return the chat id whose **current** code matches [code], or null if
     *   the code is unknown or has been replaced via [upsertCode].
     */
    fun findChatIdByCode(code: String): Long?

    /** @return the code currently stored for [tgChatId], or null. */
    fun findCodeByChatId(tgChatId: Long): String?

    /**
     * @return true if some chat already owns this code. Used by the auth manager
     *   to avoid generating a colliding random code (probability ~10⁻¹² per pair).
     */
    fun isCodeTaken(code: String): Boolean

    /**
     * Create or replace the nick ↔ chat binding for [nick].
     *
     * Multiple distinct nicks may be bound to the same [tgChatId]; this is the
     * mechanism that lets several Minecraft clients deliver to the same Telegram
     * chat through one shared code.
     */
    fun bind(nick: String, tgChatId: Long)

    /** Remove the binding for [nick]. @return true if a row was deleted. */
    fun unbind(nick: String): Boolean

    /** @return the binding for [nick], or null. */
    fun findByNick(nick: String): Binding?
}
