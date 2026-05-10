package rjlink.tgbot.server

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.extensions.utils.types.buttons.flatReplyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.utils.defaultKtorEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Drives the Telegram Bot API:
 *  - `/start` — issues (or reuses) the user's binding code,
 *  - "Сгенерировать код" reply-keyboard button — generates a fresh code,
 *  - implements [TgMessageSender] for outbound delivery from the server side.
 *
 * All bot replies are in Russian to match the end-user audience. Built on top
 * of `dev.inmo:tgbotapi` long-polling behaviour.
 */
class TgBotDriver(
    botToken: String,
    private val auth: TgAuthManager,
    parent: Job = SupervisorJob()
) : TgMessageSender {

    private val log = LoggerFactory.getLogger(TgBotDriver::class.java)
    private val bot: TelegramBot = telegramBot(botToken) {
        client = HttpClient(defaultKtorEngine) {
            install(HttpTimeout) {
                // getUpdates long-poll may legally stay open; avoid noisy 30s timeouts.
                requestTimeoutMillis = 70_000
                socketTimeoutMillis = 70_000
                connectTimeoutMillis = 30_000
            }
        }
    }
    private val scope = CoroutineScope(Dispatchers.Default + parent)

    private var pollingJob: Job? = null
    private val pollingRetryDelayMs = 2_000L
    private val generateCodeButtonText = "Сгенерировать код"
    private val codeKeyboard = flatReplyKeyboard(resizeKeyboard = true, persistent = true) {
        simpleButton(generateCodeButtonText)
    }

    /** Starts long-polling. Returns immediately; cancelable via [stop]. */
    fun start() {
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    bot.buildBehaviourWithLongPolling(scope) {
                        onCommand("start") { message ->
                            val chatId = message.chat.id.chatId.long
                            val code = auth.getOrCreateCode(chatId)
                            replySafe(message, welcomeMessage(code), withKeyboard = true)
                        }

                        onCommand("help") { message ->
                            replySafe(message, helpMessage(), withKeyboard = true)
                        }

                        onText(
                            initialFilter = { msg ->
                                msg.content.text == generateCodeButtonText
                            }
                        ) { message ->
                            val chatId = message.chat.id.chatId.long
                            val code = auth.regenerateCode(chatId)
                            replySafe(message, newCodeMessage(code), withKeyboard = true)
                        }
                    }.join()
                    if (isActive) {
                        log.warn("Telegram polling finished unexpectedly; restarting in {} ms", pollingRetryDelayMs)
                        delay(pollingRetryDelayMs)
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: HttpRequestTimeoutException) {
                    // getUpdates timeout is expected on unstable links; keep polling alive.
                    log.warn("Telegram polling request timeout; retrying in {} ms", pollingRetryDelayMs)
                    delay(pollingRetryDelayMs)
                } catch (e: Exception) {
                    log.error("Telegram long-polling failed; retrying in {} ms", pollingRetryDelayMs, e)
                    delay(pollingRetryDelayMs)
                }
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        scope.cancel()
    }

    override suspend fun sendText(chatId: Long, text: String): Boolean {
        return try {
            bot.sendMessage(
                chatId = dev.inmo.tgbotapi.types.ChatId(dev.inmo.tgbotapi.types.RawChatId(chatId)),
                text = text
            )
            true
        } catch (e: Exception) {
            log.warn("Telegram sendText failed: {}", e.message)
            false
        }
    }

    private suspend fun dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext.replySafe(
        message: dev.inmo.tgbotapi.types.message.abstracts.CommonMessage<*>,
        text: String,
        withKeyboard: Boolean = false
    ) {
        runCatching {
            if (withKeyboard) sendMessage(message.chat, text, replyMarkup = codeKeyboard)
            else sendMessage(message.chat, text)
        }
            .onFailure { log.warn("Failed to reply: {}", it.message) }
    }

    private fun welcomeMessage(code: String): String = """
        Ваш код для привязки: $code

        Кнопка снизу:
        «Сгенерировать код» — выпустить новый код
        /help — показать помощь
    """.trimIndent()

    private fun newCodeMessage(code: String): String = """
        Новый код: $code

        Старый код больше недействителен. Уже привязанные клиенты
        продолжат работать без изменений — повторно вводить код им не нужно.
    """.trimIndent()

    private fun helpMessage(): String = """
        Команды:

        /start — показать ваш текущий код привязки
        /help  — эта подсказка
        Кнопка «Сгенерировать код» — выпустить новый код (старый перестанет работать)

        Привязка в чит-клиенте: .tg connect <КОД>
        Один код может использоваться несколькими клиентами одновременно.
    """.trimIndent()
}
