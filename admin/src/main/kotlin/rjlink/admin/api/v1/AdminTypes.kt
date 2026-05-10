package rjlink.admin.api.v1

/**
 * Snapshot of a single active session, returned by [RjAdminClient.listSessions].
 *
 * Several [AdminSessionInfo] entries can share the same [nick] — that is the
 * normal case when one player runs multiple clients at once. The unique
 * discriminator inside one server process is [id].
 *
 * @property id                Server-assigned monotonic session id.
 * @property nick              Authenticated nickname.
 * @property admin             Whether this session has admin elevation.
 * @property lastHeartbeatAgoMs Milliseconds since the last heartbeat from this session.
 */
data class AdminSessionInfo(
    val id: Long,
    val nick: String,
    val admin: Boolean,
    val lastHeartbeatAgoMs: Long
)

/**
 * Snapshot of an IRC channel, returned by [RjAdminClient.listChannels].
 *
 * @property name    Channel name (e.g. `"#general"`).
 * @property size    Current number of members.
 * @property members List of nicknames currently in the channel.
 */
data class AdminChannelInfo(
    val name: String,
    val size: Int,
    val members: List<String>
)

/**
 * Persistent ban entry, returned by [RjAdminClient.listBans].
 *
 * @property nick        Banned nickname.
 * @property reason      Ban reason text.
 * @property bannedAtMs  Timestamp (epoch millis) when the ban was created.
 * @property bannedBy    Nickname of the admin who created the ban, or `null`.
 */
data class AdminBanInfo(
    val nick: String,
    val reason: String,
    val bannedAtMs: Long,
    val bannedBy: String?
)
