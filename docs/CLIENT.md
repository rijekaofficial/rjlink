# RJLink — Client Developer Guide

This document is for Java/Kotlin application developers integrating
RJLink as a library.

## 1. Artifacts

| jar                   | contains                                                      |
|-----------------------|---------------------------------------------------------------|
| `rjlink-core.jar`     | protocol, `RjClient`, session, `RjInternalApi`, exceptions   |
| `rjlink-irc.jar`      | `RjIrcClient`, `RjIrcListener`, `IrcMessage`                |
| `rjlink-tgbot.jar`    | `RjTgClient`, `RjTgListener`                                  |
| `rjlink-admin.jar`    | `RjAdminClient`, `RjAdminListener`, admin data classes        |

`rjlink-irc`, `rjlink-tgbot`, and `rjlink-admin` each depend on `rjlink-core`.
Include only the modules you need — if your application doesn't use Telegram,
simply don't include `rjlink-tgbot`.

Transitive dependencies also pulled in:

- `kotlin-stdlib`, `kotlinx-coroutines-core`
- `kotlinx-serialization-core`, `kotlinx-serialization-cbor`
- `io.ktor:ktor-client-core`, `ktor-client-cio`, `ktor-client-websockets`
- `slf4j-api`

You provide your own SLF4J binding (logback, slf4j-simple, etc.).

## 2. Minimal Integration

### Kotlin

```kotlin
import rjlink.core.client.RjClient
import rjlink.core.client.RjClientConfig
import rjlink.core.client.RjConnectionListener
import rjlink.irc.api.v1.RjIrcClient
import rjlink.irc.api.v1.RjIrcListener
import rjlink.irc.api.v1.IrcMessage
import rjlink.tgbot.api.v1.RjTgClient
import rjlink.tgbot.api.v1.RjTgListener

val client = RjClient(
    RjClientConfig(
        host = "rjlink.example.com",
        port = 443,
        nick = username
    )
)

client.addConnectionListener(object : RjConnectionListener {
    override fun onReconnectFailed() {
        println("RJLink: connection lost and all retries exhausted")
    }
})

val irc = RjIrcClient(client)
irc.addListener(object : RjIrcListener {
    override fun onMessageReceived(m: IrcMessage) {
        println("[${m.target}] ${m.senderNick}: ${m.text}")
    }
    override fun onError(channel: String?, error: String) {
        System.err.println("IRC: $error")
    }
})

val tg = RjTgClient(client)
tg.addListener(object : RjTgListener {
    override fun onAuthResult(success: Boolean, message: String) {
        println(if (success) "Telegram linked" else "Telegram: $message")
    }
    override fun onMessageResult(success: Boolean) {
        if (!success) System.err.println("Telegram send failed")
    }
})

client.connect()   // non-suspend, fire-and-forget
irc.join("#general")
irc.sendMessage("#general", "hi")
```

### Java

```java
import rjlink.core.client.RjClient;
import rjlink.core.client.RjClientConfig;
import rjlink.core.client.RjConnectionListener;
import rjlink.irc.api.v1.RjIrcClient;
import rjlink.irc.api.v1.RjIrcListener;
import rjlink.irc.api.v1.IrcMessage;
import rjlink.tgbot.api.v1.RjTgClient;
import rjlink.tgbot.api.v1.RjTgListener;

RjClient client = new RjClient(
    new RjClientConfig("rjlink.example.com", 443, username)
);

client.addConnectionListener(new RjConnectionListener() {
    @Override public void onReconnectFailed() {
        System.out.println("RJLink: connection lost and all retries exhausted");
    }
});

RjIrcClient irc = new RjIrcClient(client);
irc.addListener(new RjIrcListener() {
    @Override public void onMessageReceived(IrcMessage m) {
        System.out.println("[" + m.getTarget() + "] " + m.getSenderNick() + ": " + m.getText());
    }
    @Override public void onError(String channel, String error) {
        System.err.println("IRC: " + error);
    }
});

RjTgClient tg = new RjTgClient(client);
tg.addListener(new RjTgListener() {
    @Override public void onAuthResult(boolean success, String message) {
        System.out.println(success ? "Telegram linked" : "Telegram: " + message);
    }
    @Override public void onMessageResult(boolean success) {
        if (!success) System.err.println("Telegram send failed");
    }
});

client.connect();
irc.join("#general");
irc.sendMessage("#general", "hi");
```

## 3. Connection Lifecycle

```
    connect()        ok                          connect loss / exhausted
DISCONNECTED ───► CONNECTING ───► AUTHENTICATING ───► CONNECTED
      ▲                                                    │
      │                                                    │
      └──────────── disconnect() ──────────────────────────┘
      ▲                                                    │
      │          onReconnectFailed()                       │
      └─── all reconnect attempts exhausted ◄─────────────┘
```

- `client.connect()` — non-suspend, starts connection in the background.
- `client.disconnect()` — non-suspend, ends the session and **disables**
  auto-reconnect.
- `client.getState()` — current client state (`RjClient.State.CONNECTED`, etc.).
- `client.shutdown()` — releases the Ktor HTTP client. Call when
  unloading your application module.

### 3.1. Reconnect

Internally: exponential backoff `1s → 2s → 4s → 8s → 16s → 30s` (capped),
up to **10** attempts. Not configurable externally. After a successful
reconnect:

1. The client re-authenticates automatically.
2. All `registerReSubscribeHook` callbacks are invoked. In particular,
   `RjIrcClient` re-sends `irc.join` for every locally tracked channel.

If all 10 attempts fail — `RjConnectionListener.onReconnectFailed()`
is called on all registered listeners. Further attempts are only
possible via an explicit `client.connect()`.

### 3.2. Authentication Errors

`auth.fail` (nick taken, empty, banned, etc.) → reconnect is not triggered.
The client transitions to `DISCONNECTED` and stays there until an
explicit `connect()` with changed conditions.

## 4. Thread Safety and Listeners

- All public methods of the client APIs are **non-suspend**. Internally
  they launch coroutines in the client's scope.
- Listener callbacks are invoked on the library's dispatcher
  (`Dispatchers.Default`). If you need to dispatch to a specific
  thread (e.g. a UI thread), dispatch explicitly.
- Listeners can be registered/removed from any thread. Listeners are
  stored in `CopyOnWriteArrayList`; a throw from one listener does
  not affect the others.

## 5. Exceptions

All library errors are subclasses of `rjlink.core.exception.RjException`:

| class                 | when thrown                                      |
|-----------------------|--------------------------------------------------|
| `ConnectionException` | WebSocket failed to connect, connection lost     |
| `AuthException`       | `auth.fail` — nick taken, banned, etc.           |
| `ProtocolException`   | invalid CBOR, unexpected packet structure        |
| `TimeoutException`    | server response did not arrive in reasonable time |

Messages are always in English. In your application you typically don't
catch these directly — state transitions are signaled through
`RjConnectionListener`.

## 6. Security by Default

- In production the client strictly uses **WSS** with a valid
  certificate chain (JVM trust store).
- Certificate verification cannot be relaxed via public API. For local
  development there is a system property `-Drjlink.client.useWss=false`,
  which disables TLS entirely — use only for localhost testing.

## 7. Logging

Logger: SLF4J with categories:

- `rjlink.core.client.RjClient`
- `rjlink.core.client.internal.ConnectionHandler`
- `rjlink.irc.api.v1.RjIrcClient`
- `rjlink.tgbot.api.v1.RjTgClient`
- `rjlink.admin.api.v1.RjAdminClient`

Recommended level for production builds: `WARN` or `ERROR`.

## 8. API Stability

Packages `*.api.v1` guarantee backward compatibility within the major
version of RJLink. Classes annotated with
`@rjlink.core.RjInternalApi` are **not** part of the public contract
and may change without notice; you should not depend on them.
The compiler requires `@OptIn(RjInternalApi::class)` for such
usages.

## 9. Full Bridge Class Example

A complete wrapper that encapsulates client construction, listener
setup, and exposes a simple API to the rest of your application.

### Kotlin

```kotlin
import rjlink.core.client.RjClient
import rjlink.core.client.RjClientConfig
import rjlink.core.client.RjConnectionListener
import rjlink.irc.api.v1.RjIrcClient
import rjlink.irc.api.v1.RjIrcListener
import rjlink.irc.api.v1.IrcMessage
import rjlink.tgbot.api.v1.RjTgClient
import rjlink.tgbot.api.v1.RjTgListener

class RjLinkBridge(username: String) {

    private val client = RjClient(RjClientConfig("rjlink.example.com", 443, username))
    private val irc = RjIrcClient(client)
    private val tg = RjTgClient(client)

    init {
        client.addConnectionListener(object : RjConnectionListener {
            override fun onReconnectFailed() = println("RJLink offline")
        })

        irc.addListener(object : RjIrcListener {
            override fun onMessageReceived(m: IrcMessage) =
                println("[${m.target}] ${m.senderNick}: ${m.text}")
            override fun onError(channel: String?, error: String) =
                System.err.println("IRC: $error")
        })

        tg.addListener(object : RjTgListener {
            override fun onAuthResult(success: Boolean, message: String) =
                println(if (success) "Telegram linked" else "TG: $message")
            override fun onMessageResult(success: Boolean) {
                if (!success) System.err.println("TG send failed")
            }
        })
    }

    fun start() = client.connect()
    fun stop()  = client.shutdown()

    fun join(ch: String) = irc.join(ch)
    fun say(ch: String, text: String) = irc.sendMessage(ch, text)
    fun linkTg(code: String) = tg.auth(code)
    fun tgSend(text: String) = tg.sendMessage(text)
}
```

### Java

```java
import rjlink.core.client.RjClient;
import rjlink.core.client.RjClientConfig;
import rjlink.core.client.RjConnectionListener;
import rjlink.irc.api.v1.RjIrcClient;
import rjlink.irc.api.v1.RjIrcListener;
import rjlink.irc.api.v1.IrcMessage;
import rjlink.tgbot.api.v1.RjTgClient;
import rjlink.tgbot.api.v1.RjTgListener;

public class RjLinkBridge {

    private final RjClient client;
    private final RjIrcClient irc;
    private final RjTgClient tg;

    public RjLinkBridge(String username) {
        client = new RjClient(new RjClientConfig("rjlink.example.com", 443, username));
        irc = new RjIrcClient(client);
        tg = new RjTgClient(client);

        client.addConnectionListener(new RjConnectionListener() {
            @Override public void onReconnectFailed() {
                System.out.println("RJLink offline");
            }
        });

        irc.addListener(new RjIrcListener() {
            @Override public void onMessageReceived(IrcMessage m) {
                System.out.println("[" + m.getTarget() + "] " + m.getSenderNick() + ": " + m.getText());
            }
            @Override public void onError(String channel, String error) {
                System.err.println("IRC: " + error);
            }
        });

        tg.addListener(new RjTgListener() {
            @Override public void onAuthResult(boolean success, String message) {
                System.out.println(success ? "Telegram linked" : "TG: " + message);
            }
            @Override public void onMessageResult(boolean success) {
                if (!success) System.err.println("TG send failed");
            }
        });
    }

    public void start() { client.connect(); }
    public void stop()  { client.shutdown(); }

    public void join(String ch) { irc.join(ch); }
    public void say(String ch, String text) { irc.sendMessage(ch, text); }
    public void linkTg(String code) { tg.auth(code); }
    public void tgSend(String text) { tg.sendMessage(text); }
}
```
