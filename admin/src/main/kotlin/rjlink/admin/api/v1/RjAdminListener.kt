package rjlink.admin.api.v1

/**
 * Callbacks for [RjAdminClient] responses.
 *
 * All methods have **empty default implementations** — override only the
 * ones you need.
 *
 * ### Example
 * ```kotlin
 * admin.addListener(object : RjAdminListener {
 *     override fun onAuthResult(success: Boolean, message: String) {
 *         if (success) admin.listSessions()
 *     }
 *     override fun onSessions(sessions: List<AdminSessionInfo>) {
 *         sessions.forEach { println("${it.nick}  admin=${it.admin}") }
 *     }
 * })
 * ```
 */
interface RjAdminListener {
    /**
     * Result of an [admin.authenticate][RjAdminClient.authenticate] attempt.
     *
     * @param success `true` if the session is now elevated.
     * @param message `"ok"` on success, or the failure reason.
     */
    fun onAuthResult(success: Boolean, message: String) {}

    /**
     * Response to [admin.listSessions][RjAdminClient.listSessions].
     *
     * @param sessions Snapshot of all currently active sessions.
     */
    fun onSessions(sessions: List<AdminSessionInfo>) {}

    /**
     * Response to [admin.listChannels][RjAdminClient.listChannels].
     *
     * @param channels Snapshot of all IRC channels and their members.
     */
    fun onChannels(channels: List<AdminChannelInfo>) {}

    /**
     * Response to [admin.listBans][RjAdminClient.listBans].
     *
     * @param bans All persistent ban entries.
     */
    fun onBans(bans: List<AdminBanInfo>) {}

    /**
     * Result of a [kick][RjAdminClient.kick] command.
     *
     * @param target  The nickname that was targeted.
     * @param success `true` if the session was kicked, `false` if not found.
     * @param reason  The reason text, or `null` on success.
     */
    fun onKickResult(target: String, success: Boolean, reason: String?) {}

    /**
     * Result of a [ban][RjAdminClient.ban] command.
     *
     * @param target  The banned nickname.
     * @param success `true` if the ban was created.
     * @param reason  Ban reason text.
     */
    fun onBanResult(target: String, success: Boolean, reason: String?) {}

    /**
     * Result of an [unban][RjAdminClient.unban] command.
     *
     * @param target  The unbanned nickname.
     * @param success `true` if the ban was removed, `false` if not banned.
     * @param reason  Failure reason, or `null` on success.
     */
    fun onUnbanResult(target: String, success: Boolean, reason: String?) {}

    /**
     * Result of a [broadcast][RjAdminClient.broadcast] command.
     *
     * @param delivered Number of sessions that received the notice.
     */
    fun onBroadcastResult(delivered: Int) {}

    /**
     * Result of a [tgUnbind][RjAdminClient.tgUnbind] command.
     *
     * @param target  The nickname whose binding was targeted.
     * @param success `true` if the binding was removed, `false` if none existed.
     * @param reason  Failure reason, or `null` on success.
     */
    fun onTgUnbindResult(target: String, success: Boolean, reason: String?) {}
}
