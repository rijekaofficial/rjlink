package rjlink.tgbot.server

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe in-memory [TgBindingStore]. Useful for tests. */
class InMemoryTgBindingStore : TgBindingStore {

    private val codes = ConcurrentHashMap<Long, String>()      // chatId  -> code
    private val codesByValue = ConcurrentHashMap<String, Long>() // code   -> chatId
    private val bindings = ConcurrentHashMap<String, TgBindingStore.Binding>()

    override fun upsertCode(tgChatId: Long, code: String): String {
        codes.put(tgChatId, code)?.let { codesByValue.remove(it) }
        codesByValue[code] = tgChatId
        return code
    }

    override fun findChatIdByCode(code: String): Long? = codesByValue[code]

    override fun findCodeByChatId(tgChatId: Long): String? = codes[tgChatId]

    override fun isCodeTaken(code: String): Boolean = codesByValue.containsKey(code)

    override fun bind(nick: String, tgChatId: Long) {
        bindings[nick] = TgBindingStore.Binding(nick, tgChatId)
    }

    override fun unbind(nick: String): Boolean = bindings.remove(nick) != null

    override fun findByNick(nick: String): TgBindingStore.Binding? = bindings[nick]
}
