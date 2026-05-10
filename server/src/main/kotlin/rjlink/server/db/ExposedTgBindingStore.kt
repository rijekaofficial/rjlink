package rjlink.server.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import rjlink.tgbot.server.TgBindingStore

/** SQLite/Exposed-backed implementation of [TgBindingStore]. */
class ExposedTgBindingStore : TgBindingStore {

    override fun upsertCode(tgChatId: Long, code: String): String = transaction {
        TgCodesTable.upsert {
            it[TgCodesTable.tgChatId] = tgChatId
            it[TgCodesTable.code] = code
        }
        code
    }

    override fun findChatIdByCode(code: String): Long? = transaction {
        TgCodesTable.selectAll()
            .where { TgCodesTable.code eq code }
            .map { it[TgCodesTable.tgChatId] }
            .singleOrNull()
    }

    override fun findCodeByChatId(tgChatId: Long): String? = transaction {
        TgCodesTable.selectAll()
            .where { TgCodesTable.tgChatId eq tgChatId }
            .map { it[TgCodesTable.code] }
            .singleOrNull()
    }

    override fun isCodeTaken(code: String): Boolean = transaction {
        TgCodesTable.selectAll().where { TgCodesTable.code eq code }.any()
    }

    override fun bind(nick: String, tgChatId: Long) {
        transaction {
            TgBindingsTable.upsert {
                it[TgBindingsTable.nick] = nick
                it[TgBindingsTable.tgChatId] = tgChatId
            }
        }
    }

    override fun unbind(nick: String): Boolean = transaction {
        TgBindingsTable.deleteWhere { TgBindingsTable.nick eq nick } > 0
    }

    override fun findByNick(nick: String): TgBindingStore.Binding? = transaction {
        TgBindingsTable.selectAll()
            .where { TgBindingsTable.nick eq nick }
            .map { TgBindingStore.Binding(it[TgBindingsTable.nick], it[TgBindingsTable.tgChatId]) }
            .singleOrNull()
    }
}
