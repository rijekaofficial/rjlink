package rjlink.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.queryString
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import rjlink.core.RjInternalApi
import rjlink.core.packet.CloseCodes
import rjlink.core.packet.Packet
import rjlink.core.packet.PacketCodec
import rjlink.core.packet.PacketTypes
import rjlink.core.packet.ProtocolLimits
import rjlink.core.packet.string
import rjlink.core.server.Session
import rjlink.core.server.SessionManager
import rjlink.core.server.SessionState
import rjlink.admin.server.AdminServerModule
import rjlink.admin.server.BanStore
import rjlink.irc.server.IrcChannelManager
import rjlink.irc.server.IrcServerModule
import rjlink.server.config.AppConfig
import rjlink.server.config.AppConfigLoader
import rjlink.server.db.DatabaseFactory
import rjlink.server.db.ExposedBanStore
import rjlink.server.db.ExposedTgBindingStore
import rjlink.server.db.UsersRepository
import rjlink.server.routing.ModuleRegistry
import rjlink.server.routing.PacketRouter
import rjlink.tgbot.server.TgAuthManager
import rjlink.tgbot.server.TgBotDriver
import rjlink.tgbot.server.TgCodeGenerator
import rjlink.tgbot.server.TgMessageSender
import rjlink.tgbot.server.TgServerModule
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("rjlink.server.main")

fun main(args: Array<String>) {
    val configPath = args.firstOrNull()?.let(Paths::get) ?: Path.of("config.yaml")
    if (AppConfigLoader.ensureExists(configPath)) {
        log.warn(
            "Config not found, wrote bundled defaults to {}. " +
                "Server is starting in minimal mode (IRC only). " +
                "Edit the file to enable Telegram or admin features and restart.",
            configPath.toAbsolutePath()
        )
    }
    log.info("Loading config from {}", configPath.toAbsolutePath())
    val config = AppConfigLoader.load(configPath)
    RjLinkServer(config).start()
}

/** Wires together all server-side components and owns their lifecycles. */
@OptIn(RjInternalApi::class)
class RjLinkServer(private val config: AppConfig) {

    private val log = LoggerFactory.getLogger(RjLinkServer::class.java)

    private lateinit var sessions: SessionManager
    private lateinit var router: PacketRouter
    private lateinit var registry: ModuleRegistry
    private var tgDriver: TgBotDriver? = null
    private var engine: NettyApplicationEngine? = null
    private var bans: BanStore? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        DatabaseFactory.init(config.database.path)
        val users = UsersRepository()
        sessions = SessionManager(
            heartbeatIntervalSeconds = config.heartbeat.intervalSeconds,
            heartbeatTimeoutSeconds = config.heartbeat.timeoutSeconds
        )
        registry = ModuleRegistry()

        var ircModule: IrcServerModule? = null
        if (config.modules.irc) {
            ircModule = IrcServerModule(sessions)
            registry.register(ircModule)
        }

        var tgAuthManager: TgAuthManager? = null
        if (config.modules.tgbot) {
            val store = ExposedTgBindingStore()
            val codes = TgCodeGenerator()
            val authManager = TgAuthManager(store, codes)
            tgAuthManager = authManager
            val driver = TgBotDriver(config.telegram.botToken, authManager)
            tgDriver = driver
            registry.register(TgServerModule(authManager, driver as TgMessageSender))
            driver.start()
        }

        if (config.admin.enabled) {
            require(config.admin.token.isNotBlank()) {
                "admin.enabled=true requires admin.token to be set in config"
            }
            val store = ExposedBanStore()
            bans = store
            val channelInfo = ircModule?.let { mod ->
                {
                    mod.channels.snapshot().map { (name, members) ->
                        AdminServerModule.ChannelInfo(name, members)
                    }
                }
            } ?: { emptyList() }
            val tgUnbinder: (String) -> Boolean = tgAuthManager?.let { mgr ->
                { nick -> mgr.unbind(nick) }
            } ?: { false }
            registry.register(
                AdminServerModule(
                    token = config.admin.token,
                    sessions = sessions,
                    bans = store,
                    channelInfoProvider = channelInfo,
                    tgUnbinder = tgUnbinder
                )
            )
        }

        // Wire session-closed notifications into every registered module.
        sessions.addListener(object : SessionManager.SessionListener {
            override fun onClosed(session: Session) {
                val nick = session.nick ?: return
                serverScope.launch {
                    registry.all().forEach {
                        runCatching { it.onSessionClosed(nick) }
                    }
                }
            }
        })

        router = PacketRouter(registry)
        sessions.start()

        val server = embeddedServer(Netty, host = config.server.host, port = config.server.port) {
            configureWebSockets(users)
        }
        engine = server.engine
        log.info("RJLink server listening on {}:{} (protocol v{}+)",
            config.server.host, config.server.port, config.minProtocolVersion)
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        server.start(wait = true)
    }

    fun stop() {
        log.info("Shutting down RJLink server")
        runCatching { tgDriver?.stop() }
        runCatching { sessions.shutdown() }
        runCatching { serverScope.cancel() }
        runCatching { engine?.stop(1_000, 2_000) }
    }

    private fun Application.configureWebSockets(users: UsersRepository) {
        install(WebSockets) {
            pingPeriod = 30.seconds
            timeout = 60.seconds
            maxFrameSize = ProtocolLimits.MAX_PACKET_SIZE_BYTES.toLong()
        }
        routing {
            webSocket("/ws") {
                val version = call.request.queryParameters["v"]?.toIntOrNull()
                if (version == null || version < config.minProtocolVersion) {
                    close(CloseReason(CloseCodes.OUTDATED_CLIENT, "outdated client"))
                    return@webSocket
                }

                val outbound = Channel<ByteArray>(capacity = 64)
                val session = sessions.createSession(outbound) { _, reason ->
                    runCatching {
                        close(CloseReason(reason.code, reason.text))
                    }
                }

                val writerJob: Job = launch {
                    for (bytes in outbound) {
                        send(Frame.Binary(true, bytes))
                    }
                }

                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val bytes = frame.readBytes()
                        if (bytes.size > ProtocolLimits.MAX_PACKET_SIZE_BYTES) {
                            session.sendError("4003", "packet size exceeded")
                            close(CloseReason(CloseCodes.PACKET_TOO_LARGE, "packet too large"))
                            break
                        }
                        val packet = runCatching { PacketCodec.decode(bytes) }.getOrElse {
                            session.sendError("400", "malformed packet")
                            close(CloseReason(CloseCodes.PROTOCOL_ERROR, "protocol error"))
                            break
                        }
                        processPacket(packet, session, users)
                    }
                } finally {
                    writerJob.cancel()
                    outbound.close()
                    sessions.remove(session)
                }
            }
        }
    }

    private suspend fun processPacket(packet: Packet, session: Session, users: UsersRepository) {
        if (session.state == SessionState.PENDING_AUTH) {
            if (packet.type != PacketTypes.AUTH) {
                session.sendError("401", "auth required")
                session.close(CloseCodes.AUTH_REQUIRED, "auth required")
                return
            }
            val nick = runCatching { packet.data.string("nick") }.getOrElse {
                session.send(Packet(PacketTypes.AUTH_FAIL, packet.seq, mapOf("reason" to "nick required")))
                session.close(CloseCodes.AUTH_REQUIRED, "bad auth")
                return
            }
            // Reject banned nicks before consuming the slot.
            bans?.find(nick)?.let { ban ->
                session.send(
                    Packet(
                        PacketTypes.AUTH_FAIL,
                        packet.seq,
                        mapOf("reason" to "banned: ${ban.reason}")
                    )
                )
                session.close(CloseCodes.BANNED, "banned")
                return
            }
            // Multiple sessions may share the same nick; this call always succeeds.
            sessions.authenticate(session, nick)
            users.upsert(nick)
            session.touchHeartbeat()
            session.send(Packet(PacketTypes.AUTH_OK, packet.seq, mapOf("nick" to nick)))
            return
        }

        when (packet.type) {
            PacketTypes.AUTH -> {
                session.sendError("409", "already authenticated")
            }
            PacketTypes.HEARTBEAT -> {
                session.touchHeartbeat()
                session.send(Packet(PacketTypes.HEARTBEAT, packet.seq))
            }
            else -> {
                val nick = session.nick ?: return
                router.handle(nick, packet, session)
            }
        }
    }
}
