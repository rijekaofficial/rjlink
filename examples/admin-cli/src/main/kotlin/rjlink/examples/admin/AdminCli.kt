package rjlink.examples.admin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import rjlink.admin.api.v1.AdminBanInfo
import rjlink.admin.api.v1.AdminChannelInfo
import rjlink.admin.api.v1.AdminSessionInfo
import rjlink.admin.api.v1.RjAdminClient
import rjlink.admin.api.v1.RjAdminListener
import rjlink.core.client.RjClient
import rjlink.core.client.RjClientConfig
import rjlink.core.client.RjConnectionListener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Interactive admin REPL.
 *
 * Usage:
 * ```
 * ./gradlew :examples:admin-cli:run --args="<host> <port> <token> [nick]"
 * ```
 *
 * Defaults: `127.0.0.1 18080 <token-from-RJLINK_ADMIN_TOKEN-env> __admin__`.
 *
 * The CLI elevates a regular session via `admin.auth` and then dispatches
 * REPL commands to [RjAdminClient]. Output is printed asynchronously when
 * server responses arrive; type `/help` for the command list.
 */
fun main(args: Array<String>) {
    System.setProperty("rjlink.client.useWss", "false")

    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 18080
    val token = args.getOrNull(2)
        ?: System.getenv("RJLINK_ADMIN_TOKEN")
        ?: error("admin token required: pass as 3rd arg or RJLINK_ADMIN_TOKEN env")
    val nick = args.getOrNull(3) ?: "__admin__"

    runBlocking { Repl(host, port, token, nick).run() }
}

private class Repl(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val nick: String
) {
    private val client = RjClient(RjClientConfig(host, port, nick))
    private val admin = RjAdminClient(client)

    private val authResult = CompletableDeferred<Boolean>()

    suspend fun run() {
        wireListeners()
        println("[admin] connecting to $host:$port as '$nick'…")
        client.connect()

        // Wait until regular auth completes.
        waitForState(RjClient.State.CONNECTED, 5_000)
        println("[admin] connected, requesting elevation…")

        admin.authenticate(token)
        if (!authResult.await()) {
            System.err.println("[admin] elevation failed")
            client.shutdown()
            exitProcess(1)
        }
        println("[admin] elevation OK. Type /help to see commands.\n")
        replLoop()
        client.shutdown()
    }

    private fun wireListeners() {
        client.addConnectionListener(object : RjConnectionListener {
            override fun onReconnectFailed() {
                println("\n[admin] connection lost, reconnect failed")
            }
        })
        admin.addListener(object : RjAdminListener {
            override fun onAuthResult(success: Boolean, message: String) {
                authResult.complete(success)
                if (!success) System.err.println("[admin] auth fail: $message")
            }
            override fun onSessions(sessions: List<AdminSessionInfo>) = printSessions(sessions)
            override fun onChannels(channels: List<AdminChannelInfo>) = printChannels(channels)
            override fun onBans(bans: List<AdminBanInfo>) = printBans(bans)
            override fun onKickResult(target: String, success: Boolean, reason: String?) =
                printResult("kick", target, success, reason)
            override fun onBanResult(target: String, success: Boolean, reason: String?) =
                printResult("ban", target, success, reason)
            override fun onUnbanResult(target: String, success: Boolean, reason: String?) =
                printResult("unban", target, success, reason)
            override fun onBroadcastResult(delivered: Int) =
                println("\n[admin] broadcast delivered to $delivered session(s)")
            override fun onTgUnbindResult(target: String, success: Boolean, reason: String?) =
                printResult("tg-unbind", target, success, reason)
        })
    }

    private suspend fun replLoop() = withContext(Dispatchers.IO) {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            print("admin> ")
            val line = reader.readLine() ?: break
            if (handleCommand(line.trim())) break
        }
    }

    /** Returns true to exit. */
    private fun handleCommand(line: String): Boolean {
        if (line.isEmpty()) return false
        val parts = line.split(' ', limit = 3)
        return when (parts[0]) {
            "/help", "/?" -> { printHelp(); false }
            "/sessions", "/s" -> { admin.listSessions(); false }
            "/channels", "/c" -> { admin.listChannels(); false }
            "/bans" -> { admin.listBans(); false }
            "/kick" -> {
                val nick = parts.getOrNull(1) ?: return alsoFalse("usage: /kick <nick> [reason]")
                admin.kick(nick, parts.getOrNull(2)); false
            }
            "/ban" -> {
                val nick = parts.getOrNull(1) ?: return alsoFalse("usage: /ban <nick> [reason]")
                admin.ban(nick, parts.getOrNull(2)); false
            }
            "/unban" -> {
                val nick = parts.getOrNull(1) ?: return alsoFalse("usage: /unban <nick>")
                admin.unban(nick); false
            }
            "/broadcast", "/bc" -> {
                val text = line.removePrefix(parts[0]).trim()
                if (text.isEmpty()) return alsoFalse("usage: /broadcast <text>")
                admin.broadcast(text); false
            }
            "/tgunbind" -> {
                val nick = parts.getOrNull(1) ?: return alsoFalse("usage: /tgunbind <nick>")
                admin.tgUnbind(nick); false
            }
            "/quit", "/exit", "/q" -> true
            else -> alsoFalse("unknown command: ${parts[0]}. /help to list commands")
        }
    }

    private fun alsoFalse(msg: String): Boolean { println(msg); return false }

    private fun printHelp() {
        println("""
            commands:
              /sessions, /s             list active sessions
              /channels, /c             list IRC channels and members
              /bans                     list current bans
              /kick   <nick> [reason]   kick a session (no persistence)
              /ban    <nick> [reason]   kick + persistent ban
              /unban  <nick>            remove a ban
              /broadcast, /bc <text>    push a notice to every non-admin session
              /tgunbind <nick>          remove the Telegram binding for <nick>
              /quit, /exit, /q          disconnect and exit
              /help, /?                 this help
        """.trimIndent())
    }

    private fun printSessions(rows: List<AdminSessionInfo>) {
        // Group by nick so duplicates (one user, several clients) are visually clustered.
        val byNick = rows.groupBy { it.nick }
        println("\n[sessions: ${rows.size} across ${byNick.size} nick(s)]")
        if (rows.isEmpty()) return
        println("  %-6s %-24s %-6s %-12s".format("id", "nick", "admin", "hb-ago"))
        byNick.values.flatten().forEach {
            println("  %-6d %-24s %-6s %dms".format(it.id, it.nick, it.admin.toString(), it.lastHeartbeatAgoMs))
        }
    }

    private fun printChannels(rows: List<AdminChannelInfo>) {
        println("\n[channels: ${rows.size}]")
        if (rows.isEmpty()) return
        println("  %-24s %-5s %s".format("name", "size", "members"))
        rows.forEach {
            println("  %-24s %-5d %s".format(it.name, it.size, it.members.joinToString(",")))
        }
    }

    private fun printBans(rows: List<AdminBanInfo>) {
        println("\n[bans: ${rows.size}]")
        if (rows.isEmpty()) return
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        println("  %-20s %-19s %-12s %s".format("nick", "when", "by", "reason"))
        rows.forEach {
            println(
                "  %-20s %-19s %-12s %s".format(
                    it.nick,
                    fmt.format(Instant.ofEpochMilli(it.bannedAtMs)),
                    it.bannedBy ?: "-",
                    it.reason
                )
            )
        }
    }

    private fun printResult(op: String, target: String, success: Boolean, reason: String?) {
        if (success) println("\n[admin] $op $target: OK")
        else println("\n[admin] $op $target: FAIL${reason?.let { " ($it)" } ?: ""}")
    }

    private suspend fun waitForState(state: RjClient.State, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (client.getState() != state) {
            if (System.currentTimeMillis() > deadline)
                error("timeout waiting for $state, got ${client.getState()}")
            kotlinx.coroutines.delay(50)
        }
    }
}
