package rjlink.admin.server

/**
 * Persistent ban storage. Implementations must be thread-safe.
 *
 * The server-side admin module never touches a concrete database: it asks the
 * application to plug in any [BanStore]. The default implementation lives in
 * [`server`][rjlink.server.db] and is backed by Exposed/SQLite.
 */
interface BanStore {

    data class BanEntry(
        val nick: String,
        val reason: String,
        val bannedAt: Long,
        val bannedBy: String?
    )

    /** Add or replace a ban. Returns the persisted entry. */
    fun ban(nick: String, reason: String, bannedBy: String?): BanEntry

    /** Remove the ban for [nick]. Returns true if a row was deleted. */
    fun unban(nick: String): Boolean

    fun isBanned(nick: String): Boolean

    fun find(nick: String): BanEntry?

    fun listAll(): List<BanEntry>
}
