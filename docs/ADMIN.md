# RJLink — Administration

The server exposes a control plane through the same WebSocket endpoint
used by regular clients. Any session can "elevate" itself to admin level
by sending the correct `admin.token` from the config.

## 1. Enabling

In `config.yaml`:

```yaml
admin:
  enabled: true
  token: "long-random-secret"
```

Generate a token:

```bash
openssl rand -base64 48
```

Without `enabled: true`, the `admin` module is not registered and all
`admin.*` packets return `sys.error code=400`.

## 2. CLI Panel

Ready-made interactive client:

```bash
./gradlew :examples:admin-cli:installDist
./examples/admin-cli/build/install/admin-cli/bin/admin-cli \
    <host> <port> <token> [nick]
```

Alternatively, via environment variable:

```bash
export RJLINK_ADMIN_TOKEN="..."
./examples/admin-cli/build/install/admin-cli/bin/admin-cli 127.0.0.1 18080
```

After connecting — REPL:

```
admin> /help
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
```

### 2.1. Example Session

```
admin> /sessions
[sessions: 3]
  nick                     admin  hb-ago
  bob                      false  21306ms
  alice                    false  21283ms
  __admin__                true   49ms

admin> /channels
[channels: 1]
  name                     size  members
  #demo                    2     bob,alice

admin> /ban bob spamming
[admin] ban bob: OK

admin> /sessions
[sessions: 2]
  alice                    false  23082ms
  __admin__                true   1848ms

admin> /bans
[bans: 1]
  nick   when                 by         reason
  bob    2026-05-03 14:39:19  __admin__  spamming

admin> /unban bob
[admin] unban bob: OK

admin> /broadcast hello everyone
[admin] broadcast delivered to 1 session(s)

admin> /quit
```

## 3. What Each Command Does

| command       | server side                                                                          |
|---------------|--------------------------------------------------------------------------------------|
| `/sessions`   | `SessionManager.activeSessions()` → nick, `isAdmin`, last-heartbeat age ms          |
| `/channels`   | `IrcChannelManager.snapshot()` → name, size, member list                            |
| `/bans`       | `BanStore.listAll()` → nick, time, who banned, reason                               |
| `/kick`       | `Session.send(sys.disconnect)` + `Session.close(4008 KICKED)` (no DB record)        |
| `/ban`        | `BanStore.ban(...)` + kick active session (close `4007 BANNED`)                     |
| `/unban`      | `BanStore.unban(...)`                                                                |
| `/broadcast`  | every non-admin `ACTIVE` session receives `sys.error code=200 message="[broadcast] …"`. Delivered = `delivered` |
| `/tgunbind`   | `TgAuthManager.unbind(nick)` — deletes record from `tg_bindings`                    |

After `/ban`, any reconnect attempt from the same nick will receive
`auth.fail (reason="banned: <reason>")` and close `4007 BANNED`.
The client's `RjClient` in this case **does not retry** —
it propagates `AuthException` to the reconnect loop, which sees it and
stops further attempts.

## 4. Security

- The token is compared in **constant time** (`MessageDigest.isEqual`),
  so a timing attack on content is infeasible (length leaks, but the
  token has a fixed length from config anyway).
- A session becomes "admin" only after a successful `admin.auth`.
  All other `admin.*` packets from a non-elevated session
  receive `admin.auth.fail (reason="admin elevation required")`.
- The token is never logged. Elevation is logged as
  `Admin elevation granted to nick=...`.
- The admin nickname has no special status — choose any nick,
  as long as it's free. By convention `__admin__` is used,
  but it's just a string.

## 5. Direct Database Access (Without Admin Panel)

An alternative way to manage the server — `sqlite3` on the VPS:

```bash
# ban manually
sqlite3 rjlink.db \
  "INSERT OR REPLACE INTO bans(nick, reason, banned_at, banned_by) \
   VALUES ('someuser', 'manual', strftime('%s','now')*1000, 'sysadmin')"

# unban
sqlite3 rjlink.db "DELETE FROM bans WHERE nick='someuser'"

# unbind TG
sqlite3 rjlink.db "DELETE FROM tg_bindings WHERE nick='someuser'"
```

Changes are picked up on the next `auth`/`tg.send` — no restart needed.
Active sessions of users banned via SQL are **not** kicked
automatically — only `/ban` through AdminServerModule does that.

## 6. Using RjAdminClient from Your Own Code

If you need programmatic control instead of a CLI panel (monitoring,
automation, web dashboard):

### Kotlin

```kotlin
import rjlink.core.client.RjClient
import rjlink.core.client.RjClientConfig
import rjlink.admin.api.v1.RjAdminClient
import rjlink.admin.api.v1.RjAdminListener
import rjlink.admin.api.v1.AdminSessionInfo

val client = RjClient(RjClientConfig("localhost", 18080, "monitor"))
val admin = RjAdminClient(client)

admin.addListener(object : RjAdminListener {
    override fun onAuthResult(success: Boolean, message: String) {
        if (success) admin.listSessions()
    }
    override fun onSessions(sessions: List<AdminSessionInfo>) {
        sessions.forEach { println("${it.nick} hb=${it.lastHeartbeatAgoMs}ms") }
    }
})

client.connect()
// wait for auth.ok via RjConnectionListener / state poll
admin.authenticate(System.getenv("RJLINK_ADMIN_TOKEN"))
```

### Java

```java
import rjlink.core.client.RjClient;
import rjlink.core.client.RjClientConfig;
import rjlink.admin.api.v1.RjAdminClient;
import rjlink.admin.api.v1.RjAdminListener;
import rjlink.admin.api.v1.AdminSessionInfo;

RjClient client = new RjClient(new RjClientConfig("localhost", 18080, "monitor"));
RjAdminClient admin = new RjAdminClient(client);

admin.addListener(new RjAdminListener() {
    @Override public void onAuthResult(boolean success, String message) {
        if (success) admin.listSessions();
    }
    @Override public void onSessions(List<AdminSessionInfo> sessions) {
        for (AdminSessionInfo info : sessions) {
            System.out.println(info.getNick() + " hb=" + info.getLastHeartbeatAgoMs() + "ms");
        }
    }
});

client.connect();
// wait for auth.ok via RjConnectionListener / state poll
admin.authenticate(System.getenv("RJLINK_ADMIN_TOKEN"));
```

All methods of `RjAdminClient` are non-suspend; responses arrive
asynchronously through `RjAdminListener`. See the CLI source as a
reference:
`examples/admin-cli/src/main/kotlin/rjlink/examples/admin/AdminCli.kt`.

## 7. Extending

To add a new admin command:

1. Declare the type in `PacketTypes` (`ADMIN_*`).
2. Handle it in `AdminServerModule.handlePacket` — after checking
   `session.isAdmin`.
3. Add a method to `RjAdminClient` + the corresponding callback in
   `RjAdminListener`.
4. Add a REPL command to `examples/admin-cli`.

Tests are written as in
`admin/src/test/kotlin/rjlink/admin/server/AdminServerModuleTest.kt`:
inject `Session.markAdmin()` directly and verify outgoing packets
through a test `Channel`.
