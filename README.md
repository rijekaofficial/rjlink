# RJLink

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-orange?logo=openjdk&logoColor=white)](https://adoptium.net)
[![Protocol](https://img.shields.io/badge/protocol-v1-blue)](docs/PROTOCOL.md)
[![License](https://img.shields.io/badge/license-proprietary-red)](LICENSE)

Real-time communication infrastructure for Java/Kotlin applications.
A server (Ktor/Netty) and a client library (`rjlink-core`, `rjlink-irc`,
`rjlink-tgbot`, `rjlink-admin`) that hides all network plumbing behind
a simple listener-oriented API.

## Features

- **IRC-style channels** — join, leave, send and receive messages in real time
- **Telegram relay** — bind a Telegram chat and send/receive messages bidirectionally
- **Admin control plane** — kick, ban, broadcast, and inspect sessions over the same WebSocket connection
- **Auto-reconnect** — exponential backoff with transparent re-subscription (channels rejoined automatically)
- **Compact binary protocol** — CBOR over WebSocket, frames capped at 64 KB
- **Java-friendly API** — all public methods are non-suspending; works from plain Java with no coroutine boilerplate

## Quick Example

### Kotlin

```kotlin
val client = RjClient(RjClientConfig(host = "srv.example", port = 443, nick = "user1"))
val irc = RjIrcClient(client)

irc.addListener(object : RjIrcListener {
    override fun onMessageReceived(m: IrcMessage) =
        println("[${m.target}] ${m.senderNick}: ${m.text}")
    override fun onError(channel: String?, error: String) =
        System.err.println("IRC error: $error")
})

client.connect()
irc.join("#general")
irc.sendMessage("#general", "hello")
```

### Java

```java
RjClient client = new RjClient(new RjClientConfig("srv.example", 443, "user1"));
RjIrcClient irc = new RjIrcClient(client);

irc.addListener(new RjIrcListener() {
    @Override public void onMessageReceived(IrcMessage m) {
        System.out.println("[" + m.getTarget() + "] " + m.getSenderNick() + ": " + m.getText());
    }
    @Override public void onError(String channel, String error) {
        System.err.println("IRC error: " + error);
    }
});

client.connect();
irc.join("#general");
irc.sendMessage("#general", "hello");
```

See [docs/CLIENT.md](docs/CLIENT.md) for the full integration guide.

## Build

```bash
./gradlew build                 # compile + test all modules
./gradlew :server:installDist   # self-contained server distribution
./gradlew :core:jar :irc:jar :tgbot:jar :admin:jar   # client jars
```

## Run the Server

```bash
cp config.yaml.example config.yaml
# edit telegram.botToken and telegram.secretKey
./gradlew :server:run --args="config.yaml"
```

Or using the distribution:

```bash
./gradlew :server:installDist
./server/build/install/server/bin/server config.yaml
```

## Documentation

| document                                       | audience                                            |
|------------------------------------------------|-----------------------------------------------------|
| [docs/QUICKSTART.md](docs/QUICKSTART.md)       | **start here** — up and running in 5 minutes        |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)   | module overview and their relationships             |
| [docs/PROTOCOL.md](docs/PROTOCOL.md)           | wire protocol (WebSocket + CBOR)                    |
| [docs/CLIENT.md](docs/CLIENT.md)               | client developer (library integration)              |
| [docs/SERVER.md](docs/SERVER.md)               | server administrator (deployment)                   |
| [docs/ADMIN.md](docs/ADMIN.md)                 | admin panel: CLI + RjAdminClient                    |

## Tech Stack

- **Kotlin 2.3.21**, JVM 21 target (compiles with JDK 21+)
- **Ktor 3.4.0** (Netty engine, WebSockets)
- **Exposed 1.2.0** + **SQLite** (xerial 3.53.0.0)
- **kotlinx-serialization-cbor 1.9.0** / **kotlinx-coroutines 1.10.2**
- **dev.inmo:tgbotapi 33.1.0** (Telegram Bot API)
- **kaml 0.85.0** (YAML config)

## Project Structure

```
rjlink/
├── core/      — protocol, CBOR serialization, RjClient, SessionManager
├── irc/       — IRC module (server + client API)
├── tgbot/     — Telegram module (server + client API)
├── admin/     — control plane: AdminServerModule + RjAdminClient
├── server/    — entry point, PacketRouter, ModuleRegistry, SQLite
└── examples/
    ├── demo-client  — two-client smoke test for IRC message exchange
    └── admin-cli    — interactive admin panel in the terminal
```

## Test Coverage

```bash
./gradlew test
```

Current coverage (38 tests, 0 failures):

| module   | tests                                                              |
|----------|--------------------------------------------------------------------|
| `core`   | `PacketCodec`, `PacketDispatcher`, `ReconnectStrategy`, `SessionManager` |
| `irc`    | `IrcChannelManager`                                                |
| `tgbot`  | `TgCodeGenerator`, `TgAuthManager`                                 |
| `admin`  | `AdminPayload`, `InMemoryBanStore`, `AdminServerModule` (auth, ban, kick, list) |

## Versioning & Security

**Protocol versioning.** The protocol version is passed as the query
parameter `v` during handshake. The server rejects clients with
`v < minProtocolVersion` with close code `4001 outdated client`.
Current version: `1`.

**API stability.** Public client APIs live in `*.api.v1` and guarantee
backward compatibility within the major version of RJLink. Anything
annotated `@rjlink.core.RjInternalApi` is not part of the public
contract.

**Security brief.**

- In production — WSS only with a valid certificate.
- Nickname is the sole identifier (a deliberate simplification).
- Telegram codes = HMAC-SHA256(`secretKey`, `chatId`), 8 characters,
  alphabet without ambiguous `0/O/1/I`.
- Any frame > 64 KB closes the session with close code `4003`.

Details: [docs/PROTOCOL.md#8-security](docs/PROTOCOL.md#8-security)
and [docs/SERVER.md#11-pre-production-checklist](docs/SERVER.md#11-pre-production-checklist).
