# RJLink — Quickstart in 5 Minutes

Scenario: run a server locally, connect two clients,
exchange messages. Everything works without a real Telegram token
(`tgbot` is disabled).

## 0. Requirements

- JDK 21+ in `PATH` (`java -version`).
- This repository.

## 1. Build Everything

```bash
./gradlew build
```

You should see `BUILD SUCCESSFUL`. This also runs the unit test suite.

## 2. Start the Server Locally

The repo includes `config.local.yaml` — listens on `127.0.0.1:18080`,
TG disabled.

In one terminal:

```bash
./gradlew :server:run --args="config.local.yaml"
```

Wait for the line `RJLink server listening on 127.0.0.1:18080 (protocol v1+)`.

## 3. Run the Demo Client

In **another** terminal:

```bash
./gradlew :examples:demo-client:run
```

Expected output:

```
[demo] both clients authenticated
[demo] alice says hi
[bob] <alice@#demo> hello bob
[alice] <bob@#demo> hi alice!
[demo] done
```

What happened:

1. Two clients (`alice`, `bob`) connected via `ws://`.
2. Each sent `auth { nick }`, the server replied `auth.ok`.
3. Both sent `irc.join { channel: "#demo" }`.
4. `alice` sent `irc.msg { target, text }`; the server
   broadcast `irc.msg.incoming` to everyone in the channel except the sender —
   so only `bob` saw it.
5. Same thing in reverse.
6. Both called `client.shutdown()`.

## 4. What's Inside the Demo Client

[examples/demo-client/src/main/kotlin/rjlink/examples/DemoClient.kt](../examples/demo-client/src/main/kotlin/rjlink/examples/DemoClient.kt) —
this is a minimal reference for using the public API. Copy the
`RjClient` + `RjIrcClient` construction from there into your own project.

## 5. Connect from Your Own Java/Kotlin Project

### 5.1. Get the Jars

```bash
./gradlew :core:jar :irc:jar :tgbot:jar :admin:jar
ls core/build/libs/ irc/build/libs/ tgbot/build/libs/ admin/build/libs/
```

### 5.2. Add to Your Project

Transitive dependencies you need to include (see [docs/CLIENT.md#1-artifacts](CLIENT.md#1-artifacts)):

- `org.jetbrains.kotlin:kotlin-stdlib:2.3.21`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2`
- `org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.9.0`
- `io.ktor:ktor-client-core:3.4.0`
- `io.ktor:ktor-client-cio:3.4.0`
- `io.ktor:ktor-client-websockets:3.4.0`
- `org.slf4j:slf4j-api:2.0.16`

You provide your own SLF4J binding (logback, slf4j-simple, etc.).

### 5.3. Minimum Code

#### Kotlin

```kotlin
import rjlink.core.client.RjClient
import rjlink.core.client.RjClientConfig
import rjlink.irc.api.v1.RjIrcClient
import rjlink.irc.api.v1.RjIrcListener
import rjlink.irc.api.v1.IrcMessage

val client = RjClient(RjClientConfig("rjlink.example.com", 443, "myNick"))
val irc = RjIrcClient(client)

irc.addListener(object : RjIrcListener {
    override fun onMessageReceived(m: IrcMessage) =
        println("[${m.target}] ${m.senderNick}: ${m.text}")
    override fun onError(channel: String?, error: String) {}
})

client.connect()
irc.join("#general")
irc.sendMessage("#general", "hello")
```

#### Java

```java
import rjlink.core.client.RjClient;
import rjlink.core.client.RjClientConfig;
import rjlink.irc.api.v1.RjIrcClient;
import rjlink.irc.api.v1.RjIrcListener;
import rjlink.irc.api.v1.IrcMessage;

RjClient client = new RjClient(new RjClientConfig("rjlink.example.com", 443, "myNick"));
RjIrcClient irc = new RjIrcClient(client);

irc.addListener(new RjIrcListener() {
    @Override public void onMessageReceived(IrcMessage m) {
        System.out.println("[" + m.getTarget() + "] " + m.getSenderNick() + ": " + m.getText());
    }
    @Override public void onError(String channel, String error) {}
});

client.connect();
irc.join("#general");
irc.sendMessage("#general", "hello");
```

Full reference — [docs/CLIENT.md](CLIENT.md).

## 6. Enable Telegram

1. Create a bot via [@BotFather](https://t.me/BotFather), get `botToken`.
2. Generate a `secretKey`:
   ```bash
   openssl rand -base64 32
   ```
3. Copy `config.yaml.example` → `config.yaml`, fill in
   `telegram.botToken`, `telegram.secretKey`. Set `modules.tgbot: true`.
4. Restart the server with `config.yaml`.
5. In Telegram, send `/start` to the bot — you'll receive an 8-character code.
6. In your client:

   **Kotlin:**
   ```kotlin
   val tg = RjTgClient(client)
   tg.auth("AB34KXYZ")     // binding
   tg.sendMessage("hello") // to Telegram
   ```

   **Java:**
   ```java
   RjTgClient tg = new RjTgClient(client);
   tg.auth("AB34KXYZ");     // binding
   tg.sendMessage("hello"); // to Telegram
   ```

Details — [docs/SERVER.md#6-telegram-bot](SERVER.md#6-telegram-bot).

## 7. Deploy to a VPS

Ready-made distribution:

```bash
./gradlew :server:distZip
ls server/build/distributions/        # server-1.0.0.zip, server-1.0.0.tar
```

Copy the zip to the VPS and unpack — that's it. The server self-bootstraps
the config on first start:

```bash
unzip server-1.0.0.zip
cd server-1.0.0
./bin/server
# WARN  Config not found, wrote bundled defaults to .../config.yaml.
# INFO  RJLink server listening on 0.0.0.0:8888 (protocol v1+)
```

Edit the generated `config.yaml` (Telegram token, admin token, ports,
TLS) and restart. systemd unit example —
[docs/SERVER.md §7](SERVER.md#7-running-as-a-systemd-service).

## 8. Troubleshooting

| symptom                                                 | check                                                  |
|---------------------------------------------------------|--------------------------------------------------------|
| `connection refused` in client                          | is the server running? correct host/port in `RjClientConfig`? |
| Client waits and never reaches CONNECTED                | for `ws://` you need `-Drjlink.client.useWss=false`    |
| Server closes with close code 4001                       | `minProtocolVersion` > your client's version           |
| Server closes with close code 4006                       | another session is already connected with that nick    |
| Server closes with close code 4003                       | you are sending a frame > 64 KB                        |
| `irc.error: not a member of channel`                   | call `irc.join("#chan")` first, then `sendMessage`     |
| `tg.auth.fail: invalid code`                            | you are running the server with a different `secretKey` than when the code was issued |
| `Telegram long-polling failed` in logs                  | invalid `botToken` or no internet access               |

## 9. Admin Panel

`config.local.yaml` already has the `admin` module enabled:

```yaml
admin:
  enabled: true
  token: "local-admin-token"
```

While the server is running and there are live sessions (e.g. after running
`./examples/demo-client/build/install/demo-client/bin/demo-client 127.0.0.1 18080 60`),
open a third terminal:

```bash
./examples/admin-cli/build/install/admin-cli/bin/admin-cli \
    127.0.0.1 18080 local-admin-token
```

```
[admin] connecting to 127.0.0.1:18080 as '__admin__'
[admin] connected, requesting elevation…
[admin] elevation OK. Type /help to see commands.
admin> /sessions
[sessions: 3]
  alice                    false  120ms
  bob                      false  130ms
  __admin__                true   12ms
admin> /ban bob spamming
[admin] ban bob: OK
admin> /broadcast hello everyone
[admin] broadcast delivered to 1 session(s)
admin> /quit
```

Full guide: [docs/ADMIN.md](ADMIN.md).

## 10. Next Steps

- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — what lives where.
- [docs/PROTOCOL.md](PROTOCOL.md) — packet format, if you need a non-JVM client.
- [docs/CLIENT.md](CLIENT.md) — detailed integration guide.
- [docs/SERVER.md](SERVER.md) — deployment, systemd, backups, extending with modules.
- [docs/ADMIN.md](ADMIN.md) — admin panel, RjAdminClient, extending the control plane.
