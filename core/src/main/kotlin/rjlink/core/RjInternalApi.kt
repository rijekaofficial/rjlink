package rjlink.core

/**
 * Marks API that is part of the internal extension surface between RJLink modules.
 *
 * Application code **must not** rely on declarations annotated with this opt-in.
 * Use the public `*.api.v1` entry points instead (e.g. [RjClient][rjlink.core.client.RjClient],
 * [RjIrcClient][rjlink.irc.api.v1.RjIrcClient], etc.).
 *
 * If you are **implementing a new RJLink module** (not just consuming the library),
 * you can opt in explicitly:
 * ```kotlin
 * @OptIn(RjInternalApi::class)
 * class MyModule(private val client: RjClient) {
 *     init {
 *         client.registerHandler("my.") { ... }
 *     }
 * }
 * ```
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is internal to RJLink modules. Use the public *.api.v1 entry points instead."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class RjInternalApi
