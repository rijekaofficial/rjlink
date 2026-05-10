package rjlink.server.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val telegram: TelegramConfig = TelegramConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val admin: AdminConfig = AdminConfig(),
    val heartbeat: HeartbeatConfig = HeartbeatConfig(),
    val modules: ModuleFlags = ModuleFlags(),
    val minProtocolVersion: Int = 1
)

@Serializable
data class AdminConfig(
    /** When false, the admin module is not registered at all. */
    val enabled: Boolean = false,
    /** Shared secret required to elevate a session to admin. Must be non-blank if enabled. */
    val token: String = ""
)

@Serializable
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8888,
    val wss: Boolean = false,
    val keystorePath: String? = null,
    val keystorePassword: String? = null
)

@Serializable
data class TelegramConfig(
    /**
     * BotFather token. Required only when `modules.tgbot` is true; ignored
     * otherwise so the default config can leave it empty.
     */
    val botToken: String = "",
    /**
     * Legacy field. Until RJLink ≤ 1.0, codes were derived as
     * `HMAC-SHA256(secretKey, chatId)`. Codes are now random and stored in
     * `tg_codes`, so this value is unused. Kept as optional for backward
     * compatibility with existing configs.
     */
    val secretKey: String = ""
)

@Serializable
data class DatabaseConfig(
    val path: String = "./rjlink.db"
)

@Serializable
data class HeartbeatConfig(
    val intervalSeconds: Long = 30,
    val timeoutSeconds: Long = 90
)

@Serializable
data class ModuleFlags(
    val irc: Boolean = true,
    val tgbot: Boolean = true
)

/**
 * Loads (and optionally bootstraps) the YAML config.
 *
 * The bundled `default-config.yaml` resource is the source of truth for first-run
 * defaults — when [ensureExists] is called against a missing path, the resource
 * is copied verbatim so users see all the comments and explanatory text.
 */
object AppConfigLoader {

    private const val DEFAULT_RESOURCE = "/default-config.yaml"

    /** Parse the YAML file at [path]. The file must already exist; see [ensureExists]. */
    fun load(path: Path): AppConfig {
        val text = Files.readString(path, Charsets.UTF_8)
        return Yaml.default.decodeFromString(AppConfig.serializer(), text)
    }

    /**
     * If [path] does not exist, write the bundled default config to it (creating
     * parent directories as needed) and return `true`. If [path] already exists,
     * do nothing and return `false`.
     *
     * The default config starts the server in a safe minimal mode: IRC enabled,
     * Telegram and admin modules disabled. Operators are expected to edit the
     * file before enabling production features.
     *
     * @throws java.io.IOException if the file cannot be written.
     * @throws IllegalStateException if the bundled resource cannot be located
     *   (would only happen with a corrupted jar).
     */
    fun ensureExists(path: Path): Boolean {
        if (Files.exists(path)) return false
        path.parent?.let { Files.createDirectories(it) }
        val bytes = AppConfigLoader::class.java.getResourceAsStream(DEFAULT_RESOURCE)
            ?.use { it.readAllBytes() }
            ?: error("default-config.yaml resource is missing from the jar")
        Files.write(path, bytes)
        return true
    }
}
