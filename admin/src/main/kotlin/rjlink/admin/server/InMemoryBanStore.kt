package rjlink.admin.server

import java.util.concurrent.ConcurrentHashMap

/** Simple in-memory [BanStore] implementation, useful for tests. */
class InMemoryBanStore : BanStore {

    private val bans = ConcurrentHashMap<String, BanStore.BanEntry>()

    override fun ban(nick: String, reason: String, bannedBy: String?): BanStore.BanEntry {
        val entry = BanStore.BanEntry(nick, reason, System.currentTimeMillis(), bannedBy)
        bans[nick] = entry
        return entry
    }

    override fun unban(nick: String): Boolean = bans.remove(nick) != null
    override fun isBanned(nick: String): Boolean = bans.containsKey(nick)
    override fun find(nick: String): BanStore.BanEntry? = bans[nick]
    override fun listAll(): List<BanStore.BanEntry> = bans.values.sortedBy { it.bannedAt }
}
