package rjlink.core.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import rjlink.core.packet.CloseCodes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tracks active sessions, indexes them by nick, and runs the heartbeat sweeper.
 *
 * Multiple sessions may share the same nick: one player launching several
 * Minecraft clients simultaneously is a supported scenario. All sessions of a
 * given nick are returned by [findAllByNick] and addressed together by IRC
 * relay and admin operations (kick/ban).
 *
 * Transport-agnostic: WebSocket handling lives in the server module and feeds
 * this manager via [createSession] / [authenticate] / [remove].
 */
class SessionManager(
    private val heartbeatIntervalSeconds: Long,
    private val heartbeatTimeoutSeconds: Long,
    parentJob: Job = SupervisorJob()
) {
    private val log = LoggerFactory.getLogger(SessionManager::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + parentJob)

    private val sessionsById = ConcurrentHashMap<Long, Session>()
    private val sessionsByNick = ConcurrentHashMap<String, CopyOnWriteArraySet<Session>>()
    private val listeners = CopyOnWriteArrayList<SessionListener>()

    interface SessionListener {
        fun onAuthenticated(session: Session) {}
        fun onClosed(session: Session) {}
    }

    fun addListener(listener: SessionListener) { listeners.add(listener) }
    fun removeListener(listener: SessionListener) { listeners.remove(listener) }

    /**
     * Returns one of the sessions bound to [nick], or null if none.
     *
     * When the nick has multiple concurrent sessions the choice is unspecified;
     * callers that need to address all sessions (e.g. IRC relay, admin kick)
     * should use [findAllByNick] instead.
     */
    fun findByNick(nick: String): Session? = sessionsByNick[nick]?.firstOrNull()

    /** Snapshot of every session currently bound to [nick]. */
    fun findAllByNick(nick: String): Collection<Session> =
        sessionsByNick[nick]?.toList() ?: emptyList()

    /** True if at least one active session is bound to [nick]. */
    fun hasNick(nick: String): Boolean = sessionsByNick.containsKey(nick)

    /** Snapshot of currently active (authenticated) sessions across all nicks. */
    fun activeSessions(): Collection<Session> =
        sessionsById.values.filter { it.state == SessionState.ACTIVE }

    /**
     * Create a new session bound to the given outbound channel. The caller must
     * drain the channel and wire [onClose] into the WebSocket close flow.
     */
    fun createSession(
        outbound: Channel<ByteArray>,
        onClose: suspend (Session, Session.CloseReason) -> Unit
    ): Session {
        val id = Session.nextId()
        val session = Session(id, outbound) { s, r ->
            remove(s)
            onClose(s, r)
        }
        sessionsById[id] = session
        return session
    }

    /**
     * Authenticate [session] under [nick].
     *
     * Always returns true: duplicate nicks are allowed because a single user may
     * legitimately drive several clients at once. The boolean is kept in the
     * signature for backward compatibility and future extension (e.g. a per-nick
     * concurrency cap).
     */
    fun authenticate(session: Session, nick: String): Boolean {
        sessionsByNick
            .computeIfAbsent(nick) { CopyOnWriteArraySet() }
            .add(session)
        session.markAuthenticated(nick)
        listeners.forEach { it.onAuthenticated(session) }
        return true
    }

    /** Remove the session from all indices. Safe to call multiple times. */
    fun remove(session: Session) {
        sessionsById.remove(session.id)
        session.nick?.let { nick ->
            sessionsByNick[nick]?.let { set ->
                set.remove(session)
                if (set.isEmpty()) sessionsByNick.remove(nick, set)
            }
        }
        if (session.state != SessionState.CLOSED) session.markClosed()
        listeners.forEach {
            try { it.onClosed(session) } catch (e: Exception) {
                log.warn("Session listener threw on close", e)
            }
        }
    }

    /** Starts the periodic heartbeat sweeper. */
    fun start() {
        scope.launch {
            val intervalMs = heartbeatIntervalSeconds * 1_000
            val timeoutMs = heartbeatTimeoutSeconds * 1_000
            while (isActive) {
                delay(intervalMs)
                val now = System.currentTimeMillis()
                for (session in sessionsById.values.toList()) {
                    if (session.state != SessionState.ACTIVE) continue
                    if (now - session.lastHeartbeatMs > timeoutMs) {
                        log.info("Heartbeat timeout for nick={} sessionId={}", session.nick, session.id)
                        runCatching {
                            session.close(CloseCodes.HEARTBEAT_TIMEOUT, "heartbeat timeout")
                        }.onFailure { log.warn("Failed to close stale session", it) }
                    }
                }
            }
        }
    }

    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
    }
}
