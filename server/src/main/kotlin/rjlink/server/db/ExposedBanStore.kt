package rjlink.server.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import rjlink.admin.server.BanStore

/** SQLite/Exposed-backed implementation of [BanStore]. */
class ExposedBanStore : BanStore {

    override fun ban(nick: String, reason: String, bannedBy: String?): BanStore.BanEntry {
        val now = System.currentTimeMillis()
        transaction {
            BansTable.upsert {
                it[BansTable.nick] = nick
                it[BansTable.reason] = reason
                it[bannedAt] = now
                it[BansTable.bannedBy] = bannedBy
            }
        }
        return BanStore.BanEntry(nick, reason, now, bannedBy)
    }

    override fun unban(nick: String): Boolean = transaction {
        BansTable.deleteWhere { BansTable.nick eq nick } > 0
    }

    override fun isBanned(nick: String): Boolean = transaction {
        BansTable.selectAll().where { BansTable.nick eq nick }.any()
    }

    override fun find(nick: String): BanStore.BanEntry? = transaction {
        BansTable.selectAll().where { BansTable.nick eq nick }
            .map { it.toBanEntry() }
            .singleOrNull()
    }

    override fun listAll(): List<BanStore.BanEntry> = transaction {
        BansTable.selectAll()
            .orderBy(BansTable.bannedAt)
            .map { it.toBanEntry() }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toBanEntry() =
        BanStore.BanEntry(
            nick = this[BansTable.nick],
            reason = this[BansTable.reason],
            bannedAt = this[BansTable.bannedAt],
            bannedBy = this[BansTable.bannedBy]
        )
}
