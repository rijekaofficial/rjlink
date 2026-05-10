package rjlink.server.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Initializes the SQLite database, ensuring all tables exist. */
object DatabaseFactory {
    fun init(sqlitePath: String): Database {
        val db = Database.connect(
            url = "jdbc:sqlite:$sqlitePath",
            driver = "org.sqlite.JDBC"
        )
        transaction(db) {
            SchemaUtils.create(UsersTable, TgBindingsTable, TgCodesTable, BansTable)
        }
        return db
    }
}
