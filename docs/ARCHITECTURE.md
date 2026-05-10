# RJLink — Architecture

Overview document: how the modules are structured, which classes are
responsible for what, and the order of operations at runtime.

## 1. Gradle Modules

```
rjlink/
├── core/    ─── protocol, serialization, base client, SessionManager
├── irc/     ─── IRC (server-side + client-side API)
├── tgbot/   ─── Telegram (server-side + client-side API)
├── admin/   ─── Admin (control plane: kick/ban/broadcast/inspection)
└── server/  ─── entry point, Application, PacketRouter, ModuleRegistry, DB
```

Dependencies:

```
server ─► core, irc, tgbot, admin
irc    ─► core
tgbot  ─► core
admin  ─► core
core   ─► (only external: kotlin, coroutines, ktor-client, cbor, slf4j)
```

## 2. Package Structure

```
rjlink.core
├── packet             — Packet, PacketCodec, PacketTypes, ProtocolVersion,
│                         CloseCodes, ProtocolLimits, extension accessors
├── client             — RjClient, RjClientConfig, RjConnectionListener
├── client.internal    — ConnectionHandler, PacketDispatcher, ReconnectStrategy
├── server             — Session, SessionState, SessionManager, ServerModule
├── exception          — RjException, ConnectionException, AuthException,
│                         ProtocolException, TimeoutException
└── RjInternalApi      — opt-in annotation for cross-module internal API

rjlink.irc.server      — IrcServerModule, IrcChannelManager
rjlink.irc.api.v1      — RjIrcClient, RjIrcListener, IrcMessage

rjlink.tgbot.server    — TgServerModule, TgAuthManager, TgCodeGenerator,
                          TgBindingStore, TgMessageSender, TgBotDriver
rjlink.tgbot.api.v1    — RjTgClient, RjTgListener

rjlink.admin.server    — AdminServerModule, AdminPayload, BanStore, InMemoryBanStore
rjlink.admin.api.v1    — RjAdminClient, RjAdminListener, AdminSessionInfo,
                          AdminChannelInfo, AdminBanInfo

rjlink.server
├── Application         — main, RjLinkServer, WebSocket routing
├── config              — AppConfig, AppConfigLoader (kaml YAML)
├── routing             — PacketRouter, ModuleRegistry
└── db                  — DatabaseFactory, UsersTable, TgBindingsTable,
                          TgCodesTable, BansTable, UsersRepository,
                          ExposedTgBindingStore, ExposedBanStore
```

## 3. Client — Runtime

```
┌──────────────────────────────────────────────────────────────────────┐
│                           RjClient                                    │
│                                                                       │
│  state: AtomicReference<State>                                        │
│  userDisconnected: AtomicBoolean                                      │
│  reconnectRunning: AtomicBoolean                                      │
│                                                                       │
│  ┌────────────────────────────┐    ┌──────────────────────────────┐ │
│  │  ConnectionHandler         │    │  PacketDispatcher            │ │
│  │  (ktor-client + WS)       │    │  prefix → List<Handler>      │ │
│  │  - connect / send          │    │  registry of client modules  │ │
│  │  - readLoop (in)           │    │  dispatch(packet)             │ │
│  │  - heartbeatLoop           │    └──────────────────────────────┘ │
│  └────────────────────────────┘                                      │
│                                                                       │
│  ReconnectStrategy            reSubscribeHooks: List<suspend()>      │
│  exponential backoff          re-join IRC channels after reconnect  │
└──────────────────────────────────────────────────────────────────────┘
           ▲                                 ▲
           │ @RjInternalApi send/seq/        │ @RjInternalApi registerHandler
           │ launchInternal                  │
           │                                 │
    ┌──────┴────────┐                 ┌──────┴────────┐
    │  RjIrcClient  │                 │  RjTgClient   │
    │  api.v1       │                 │  api.v1       │
    └───────────────┘                 └───────────────┘
                                         ┌──────┴────────┐
                                         │ RjAdminClient │
                                         │ api.v1        │
                                         └───────────────┘
```

Key properties:

- **All public methods are non-suspend.** Work is dispatched through
  `client.launchInternal { ... }` into an internal `CoroutineScope`.
- **Transparent reconnect.** Modules register
  `registerReSubscribeHook` to re-send their state (joins).
- **Opt-in boundary.** Everything outside `*.api.v1` is annotated
  `@RjInternalApi`; usage outside RJLink modules requires
  an explicit `@OptIn`.

## 4. Server — Runtime

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Ktor Netty Engine                            │
│                                                                      │
│  webSocket("/ws") {                                                  │
│      1) check query parameter "v" vs minProtocolVersion             │
│      2) create Session (state=PENDING_AUTH)                         │
│      3) allocate outbound: Channel<ByteArray>                       │
│      4) start writerJob (drain channel → Frame.Binary)               │
│      5) main loop: incoming → decode → processPacket                │
│         ▲                                                            │
│         └── waits for auth → authenticate → active                  │
│  }                                                                   │
└─────────────────────────────────────────────────────────────────────┘
                  │                                  ▲
                  ▼                                  │
┌─────────────────────────────────────┐    ┌────────┴────────────────┐
│  SessionManager                      │    │   PacketRouter           │
│  - sessionsByNick (nick -> set<Session>) │ │   handle(nick, pkt, s)  │
│  - sessionsById                      │    │      │                   │
│  - heartbeat sweeper (coroutine)    │    │      ▼                   │
│  - createSession / authenticate /   │    │  ModuleRegistry          │
│    remove                            │    │  resolve(type) → module │
└─────────────────────────────────────┘    └──────────────────────────┘
                                                       │
                         ┌─────────────────────────────┴────────────┐
                         ▼                                          ▼
                ┌────────────────────┐                 ┌─────────────────────┐
                │ IrcServerModule    │                 │ TgServerModule      │
                │ IrcChannelManager  │                 │ TgAuthManager       │
                │ (ConcurrentHashMap │                 │ TgBindingStore (DB) │
                │   channels/members)│                 │ TgMessageSender     │
                └────────────────────┘                 │   = TgBotDriver     │
                                                       │   (long polling,    │
                                                       │    ktgbotapi)       │
                                                       └─────────────────────┘
                                                                  │
                                                                  ▼
                                                       ┌──────────────────────┐
                                                       │ Exposed + SQLite     │
                                                       │ users / tg_bindings  │
                                                       │ tg_codes / bans      │
                                                       └──────────────────────┘
```

### 4.1. Incoming Packet Flow

```
Netty Frame.Binary
    │
    ├─ size > 64 KB ? ──► sys.error(4003) + close(4003)
    │
    ▼
PacketCodec.decode
    │
    ├─ error? ───► sys.error(400) + close(4004)
    │
    ▼
Session.state == PENDING_AUTH?
    │
    ├─ yes: accept only "auth".
    │       nick banned?  ──► auth.fail + close(4007)
    │       duplicate nick? allowed (another session is added to nick set)
    │       then: users.upsert(nick), authenticate, send auth.ok
    │
    └─ no:  type == "heartbeat"? ──► touch + reply heartbeat
            type == "auth"?      ──► sys.error(409)
            otherwise: PacketRouter.handle(nick, packet, session)
                        │
                        ├─ module == null ? ──► sys.error(400)
                        │
                        └─ module.handlePacket(...)
                               │
                               └─ exception ? ──► sys.error(500)
```

### 4.2. Outgoing Packet Flow

Modules call `session.send(Packet)`:

1. `PacketCodec.encode` → `ByteArray`.
2. Under `sendMutex` → into `outbound: Channel<ByteArray>`.
3. Writer coroutine of the WebSocket handler reads from the channel and
   sends `Frame.Binary`.

Delivery order within a single session is preserved (FIFO channel +
per-session mutex on enqueue).

## 5. Database

Four tables, all primary keys are simple and accessible from Exposed v1 code:

| table          | PK             | purpose                                          |
|----------------|----------------|--------------------------------------------------|
| `users`        | `nick`         | ever-seen nicknames + `created_at` ms            |
| `tg_bindings`  | `nick`         | current nick ↔ Telegram chat binding             |
| `tg_codes`     | `tg_chat_id`   | deterministic codes for `/start`                 |
| `bans`         | `nick`         | persistent ban entries                            |

Access: `UsersRepository`, `ExposedTgBindingStore`, `ExposedBanStore`
(implementations of the `TgBindingStore` and `BanStore` interfaces).

## 6. Extensibility

- **New server module** — see [SERVER.md#9.1-adding-a-new-module](./SERVER.md).
  Simply implement `ServerModule` and register it in `RjLinkServer`.
- **New client API** — create a class that accepts `RjClient`,
  register via
  `client.registerHandler(prefix) { packet -> ... }` and, if needed,
  `client.registerReSubscribeHook { ... }`. Both methods are
  annotated `@RjInternalApi`, so `@OptIn(RjInternalApi::class)` is required.
- **New transport** — `ConnectionHandler` encapsulates ktor-client;
  you can replace it with anything as long as the contract
  `connect(onPacket, onClosed)` / `send(packet)` / `disconnect()` is preserved.

## 7. Thread Safety

| component                                        | model                                              |
|--------------------------------------------------|----------------------------------------------------|
| `Session.state` / `nick` / `lastHeartbeat`      | `AtomicReference` / `AtomicLong`                   |
| `Session.send` ordering                          | internal `Mutex` + Channel FIFO                    |
| `SessionManager` indexes                         | `ConcurrentHashMap`                                |
| `IrcChannelManager`                              | `ConcurrentHashMap<String, ConcurrentHashMap.KeySetView>` + `synchronized(set)` for check-and-insert |
| `PacketDispatcher.handlers`                      | `CopyOnWriteArrayList`                             |
| `RjClient` listeners / hooks                     | `CopyOnWriteArrayList`                             |
| `ReconnectStrategy`                              | not thread-safe; called only from a single coroutine (reconnect loop) |
| DB (Exposed)                                     | JDBC-level; operations wrapped in `transaction { ... }` |

## 8. State Machines

Client:

```
DISCONNECTED → CONNECTING → AUTHENTICATING → CONNECTED
                     ↑                            │
                     └──── scheduleReconnect() ◄──┘
```

Server session:

```
PENDING_AUTH ─auth.ok──► ACTIVE ──close──► CLOSED
     │                     │
     └──────── close ──────┴──► CLOSED
```
