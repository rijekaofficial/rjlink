package rjlink.server.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Create-or-touch style API for the users table. */
class UsersRepository {
    fun upsert(nick: String) {
        transaction {
            UsersTable.insertIgnore {
                it[UsersTable.nick] = nick
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    fun exists(nick: String): Boolean = transaction {
        UsersTable.selectAll().where { UsersTable.nick eq nick }.any()
    }
}
