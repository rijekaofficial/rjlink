package rjlink.irc.server

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of IRC channels and their members.
 *
 * Channels are created lazily on first join and removed when the last member leaves.
 * All operations are thread-safe.
 */
class IrcChannelManager(
    /** Hard limit on members per channel. */
    val maxMembersPerChannel: Int = 200
) {
    private val channels = ConcurrentHashMap<String, MutableSet<String>>()

    enum class JoinResult { JOINED, ALREADY_MEMBER, CHANNEL_FULL }

    fun join(nick: String, channel: String): JoinResult {
        val set = channels.computeIfAbsent(channel) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        synchronized(set) {
            if (nick in set) return JoinResult.ALREADY_MEMBER
            if (set.size >= maxMembersPerChannel) return JoinResult.CHANNEL_FULL
            set.add(nick)
            return JoinResult.JOINED
        }
    }

    /** @return true if nick was in the channel. */
    fun leave(nick: String, channel: String): Boolean {
        val set = channels[channel] ?: return false
        val removed: Boolean
        synchronized(set) {
            removed = set.remove(nick)
            if (set.isEmpty()) channels.remove(channel, set)
        }
        return removed
    }

    /** Remove [nick] from every channel. Returns the set of channels that were affected. */
    fun leaveAll(nick: String): Set<String> {
        val affected = mutableSetOf<String>()
        for ((channel, members) in channels) {
            synchronized(members) {
                if (members.remove(nick)) {
                    affected.add(channel)
                    if (members.isEmpty()) channels.remove(channel, members)
                }
            }
        }
        return affected
    }

    fun members(channel: String): Set<String> =
        channels[channel]?.let { set -> synchronized(set) { set.toSet() } } ?: emptySet()

    fun contains(nick: String, channel: String): Boolean =
        channels[channel]?.contains(nick) ?: false

    fun channelCount(): Int = channels.size

    /** Consistent snapshot of every channel and its members. */
    fun snapshot(): List<Pair<String, List<String>>> =
        channels.map { (channel, members) ->
            channel to synchronized(members) { members.toList() }
        }
}
