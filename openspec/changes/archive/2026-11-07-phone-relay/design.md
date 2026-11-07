## Context

The phone holds one pinned WebSocket to its desktop (`net/RemoteClient.kt`), opened by a staggered race over the
pairing's addresses (`net/AddressRace.kt`, `service/ConnectionService.kt`): attempts start 250 ms apart, or at once
when every attempt so far has failed, and the first pinned handshake wins. Trust in the desktop is the SHA-256 of its
leaf certificate, checked in the trust manager, in the host name verifier and on open (`net/Pinning.kt`).

spekterm-server 0.4.0 relays for a member's own phone. Its `docs/protocol.md` section 13 is the contract: the phone
opens `wss://<server>/relay`, sends one text request `relay.open { protocol: [1], grant, secret }`, and once the server
answers `{ protocol: 1, tunnel }` (it waits up to 10 s for the desktop to accept), the connection carries binary
messages only, spliced to the desktop's leg unchanged. The bytes are the phone's TLS to the desktop's remote-access
listener. Messages over 256 KiB end the tunnel; clients cut writes into messages of at most 64 KiB. The server's
design (`openspec/changes/phone-relay/design.md` there, D10) lists what the phone must do; this design settles how.

The desktop side (spekterm's own `phone-relay` change, agreed with that session on 2026-11-06, draft until its review):
the `hello` answer gains optional `relay: { url, fp?, grant, secret }` — `url` is `wss://host[:port]` with no path, `fp`
64 hex digits present only when the team membership is pinned, `grant` an opaque id, `secret` opaque base64url — and a
new event `relay.changed { relay: Relay | null }` carries the complete current value while connected. A `hello` answer
without `relay` means the desktop holds no grant for this phone. The `pair` answer never carries it. On a relayed
connection the desktop answers `hello` exactly as on a direct one, refuses `pair`, and throttles failed `hello`s per
grant (10 in 60 s → `THROTTLED`, 4030, for 60 s). A relayed `hello` is accepted only with the credential of the phone
the grant was made for (another device's is `AUTH_FAILED`), so `AUTH_FAILED` through the relay means what it means
directly. Unpairing the phone revokes its grant; leaving the team (or turning the team feature off) sends
`relay.changed { relay: null }` at once.

## Goals / Non-Goals

**Goals:**

- Reach the desktop through its team server when, and only when, the desktop has given this phone a grant.
- Keep direct access first: a direct address that answers within a second of the last direct attempt starting always
  wins, and the team server is then not contacted at all.
- Run the existing pinned client, unchanged, inside the relay; weaken no check.
- Act on every answer and close code of the relay as section 13.6 says.
- Tell the user which path is in use, and why the relay did not help when nothing connects.

**Non-Goals:**

- Push of any kind; pairing through the relay; access to someone else's desktop.
- Moving from the relay back to direct while connected. The phone returns to direct at the next round, which a
  network change starts at once (as today).
- A phone-side setting to turn the relay off: the desktop owns the grant and its setting.

## Decisions

### D1. Layering: a `Socket` backed by the relay WebSocket, under the unchanged OkHttp client

The inner TLS and WebSocket must run over the outer WebSocket's byte stream. Two ways were open:

- **(a)** An `SSLEngine` over the outer WebSocket, with the inner WebSocket framed by hand: masking, fragmentation,
  ping/pong, the close handshake and the 1 MiB message reassembly, re-implemented next to OkHttp's, and
  `RemoteConnection` rebuilt on it. The pin would be re-implemented on the engine too.
- **(b)** A `java.net.Socket` whose streams are the outer WebSocket's binary messages, handed to OkHttp through a
  per-tunnel `SocketFactory`; OkHttp puts the existing `sslSocketFactory`, trust manager and host name verifier on top
  and runs its own WebSocket over it.

**Chosen: (b).** Measured on the Pixel_9 emulator (Android 16, API 36, debug build) with a fake relay that splices a TLS
WebSocket to the fixture desktop (spike `RelaySpikeTest`, recorded in `docs/relay-findings.md` by task 2.3): the inner
pinned handshake completed through all three checks; a `hello` answer of 1 MiB arrived whole; a 300 KiB request from the
phone reached the desktop; Conscrypt wrapped the tunnel socket with `ConscryptEngineSocket`, whose writes were at most
8214 bytes each. The inner client's TLS setup, its pin, `RemoteConnection` and the remote protocol code stay exactly as
they are; the inner client differs only in its socket factory, `Proxy.NO_PROXY` and `retryOnConnectionFailure(false)`.

What OkHttp 5.5 asks of the socket (read from its bytecode by the review), and how the tunnel socket answers:

- **No proxy.** The inner client is built with `Proxy.NO_PROXY`. Otherwise, on a Wi-Fi with an HTTP proxy, OkHttp would
  write a cleartext `CONNECT` into the tunnel (it uses the socket factory for `DIRECT` and `HTTP` proxies), and with a
  SOCKS proxy it would bypass the factory and dial the network.
- `SocketFactory.createSocket()` once per route. The inner URL is `https://127.0.0.1:<pairing port>/v1`: an IP literal,
  so OkHttp resolves nothing and has one route, and Conscrypt sends no SNI for an IP literal (to be confirmed by 2.3).
  The factory hands out its one socket once; a second call throws `SocketException`.
- `connect(address, timeout)`: marks the socket connected; the address is ignored (the tunnel is already open).
- `soTimeout`: kept and honored by reads (`SocketTimeoutException`); OkHttp sets 0 after the upgrade.
- `isConnected` (checked by Conscrypt), `isClosed`, `isInputShutdown`, `isOutputShutdown` (read by OkHttp's health
  check): from the socket's own state.
- `tcpNoDelay`, `keepAlive`, `soLinger`, `shutdownInput/Output`: accepted, no effect.
- Addresses and ports: loopback and the URL's port, for OkHttp's bookkeeping only.
- `close()`: cancels the outer WebSocket and ends reads and writes.
- **Only `IOException`s.** OkHttp rethrows any other exception from a call on its dispatcher thread after
  `onFailure`, which ends the process. Every failure of the tunnel socket and its factory is an `IOException`
  (`InterruptedException` while waiting becomes `InterruptedIOException`).
- **The file descriptor.** Conscrypt's `createSocket(Socket, …)` wraps a socket that has a file descriptor in its
  descriptor-based `SSLSocket`, which does TLS on that descriptor directly — wrong for a socket whose bytes are not on
  any descriptor. A plain `Socket()` reports a (dead) descriptor through libcore's hidden `getFileDescriptor$()` (seen
  on API 36). On API 36 Conscrypt uses its engine socket by default, so the descriptor is never asked for there; older
  Conscrypt versions (the app supports Android 10, API 29) may not. The tunnel socket therefore declares
  `getFileDescriptor$()` throwing `UnsupportedOperationException`, which Conscrypt's `hasFileDescriptor` treats as "no
  descriptor", choosing the engine socket. Verified only so far: on API 36, a virtual call to `getFileDescriptor$()` on
  the tunnel socket reaches the override; whether an API 29–35 Conscrypt then picks the engine socket is not measured
  (no older image or device at hand). The method is not in `android.jar`, so **R8 would remove it** from minified builds
  (the review ran R8 8.10.9 on a minimal class and it did); `proguard-rules.pro` keeps it with a
  `-keepclassmembers` rule, and task 2.4 checks the minified build still has it. Declaring the method calls no hidden
  API. If an older platform still picks the descriptor socket, the handshake fails — with an `IOException` from
  Conscrypt, or possibly another exception, which is why the relay attempt runs the inner open as any other and the
  risk is recorded (Risks).

Writes and reads:

- **Cutting**: each write is sent as binary messages of at most 64 KiB (`CHUNK = 65536`), whatever the TLS layer hands
  down. Today Conscrypt hands down at most one record at a time, but nothing guarantees that.
- **Back-pressure out**: a write waits while the outer WebSocket's queue holds more than 256 KiB, so OkHttp's 16 MiB
  outgoing limit (which closes the WebSocket) is never approached; the waiting thread is the inner writer's. A waiting
  write fails at once when the socket is closed or the outer WebSocket ends.
- **Back-pressure in**: received messages go into a queue bounded at 1 MiB of data; when it is full, the outer reader
  thread waits, so TCP pushes back on the server (whose flow control then pauses the desktop's leg, protocol 13.3).
  This happens only when the inner reader stops reading, which the server tolerates for 120 s.
- **End**: the end marker does not count toward the bound, so ending never blocks. When the outer WebSocket closes or
  fails, or the socket is closed, waiting reads and the blocked outer reader are woken; reads return end of stream after
  the queued bytes (none after `close()`), and writes fail. The inner OkHttp WebSocket then fails like a dropped
  connection.

### D2. The relay attempt

`RelayClient.open(grant, pin, port)` — one candidate of a round, bounded as a whole by **30 s**
(`RELAY_OPEN_TIMEOUT_MS`; the protocol asks for at least 25: outer TLS and upgrade, up to 10 s waiting for the desktop,
the inner TLS and upgrade). Steps:

1. **Outer connection** to `<url>/relay`, built with OkHttp's URL builder from the grant's scheme, host and port (never
   by string concatenation). With `fp`: the same `Pin` class as the desktop's (leaf SHA-256 in the trust manager and the
   host name verifier, no CA, no host name), confirmed again on `onOpen`. Without `fp`: the base client's trust (system
   store, host name; injectable for tests). Connect timeout 5 s; call timeout 15 s for TCP, TLS and the upgrade. No
   frame is sent before `onOpen` (and, with `fp`, the third check).
2. **`relay.open`**, a text frame `{ "t": "req", "id": 1, "m": "relay.open", "p": { "protocol": [1], "grant",
   "secret" } }`, then nothing until its answer (up to 20 s). A binary message before the answer fails the attempt as a
   protocol fault (D4).
3. **Inner connection**: on `ok` with `protocol: 1` and a `tunnel`, the tunnel socket takes over the outer WebSocket's
   binary messages and `RemoteClient` opens `https://127.0.0.1:<port>/v1` over it with the pairing's pin (`clientFor(pin)`
   plus the tunnel factory, `Proxy.NO_PROXY`, no retry). The attempt opens when the inner pinned handshake completes —
   the same `OpenResult.Open` as a direct address, together with a `RelayLeg` that records how the outer connection
   ended (its close code, or a failure) for the session to read when it ends.

On every failure path the outer WebSocket is cancelled, not closed: a close handshake on a dead network waits 60 s in
OkHttp before giving up. The outer WebSocket sends no pings of its own: the server pings it, OkHttp answers, and the
inner WebSocket's own 30 s pings detect a dead path end to end.

The relay is never used for `pair`: the pairing screen keeps calling only the link's addresses directly.

### D3. The relay in the race

`race` becomes generic over typed candidates, `Route.Direct(address)` and `Route.Relay(host)`, each with its own start
delay: a candidate starts its delay after the previous one started, or at once when every attempt started so far has
finished without opening; the first candidate starts at once. Direct addresses keep 250 ms; the relay's delay is
**1000 ms**. Results, the status screen's attempts and the unreachability report are keyed by `Route`, so a team server
on the same host as one of the desktop's addresses never mixes with it. A round's candidates are the direct addresses
in today's order, then the relay when the phone holds a usable grant that the gate allows (D4). So:

- On the desktop's network a direct address answers well within that second and the relay is never started (the
  server sees nothing); if a direct address opens after the relay started, the race keeps whichever opened first.
- Off it, direct attempts to private addresses hang until their 5 s connect timeout while the relay starts after one
  second and answers about as fast as the server reaches the desktop.
- When every direct address fails at once (no route, refused), the relay starts at once.

`lastAddress` is written only for a `Route.Direct` winner. The pairing screen's use of `race` (direct only) is unchanged.

### D4. Answers and closes

The relay attempt and the relayed session end in one of these, and the service acts on them (protocol 13.6):

| What happened | Phone |
|---|---|
| Outer TCP/TLS failure, upgrade timeout | Outcome as for a direct address (timed out, refused, no route, name not found, failed); next round as usual. |
| Outer certificate refused, no `fp` | Outcome "team server certificate"; next round as usual (a hostile Wi-Fi may be in between). |
| Outer certificate refused, `fp` present | Outcome "team server certificate changed": leave the relay out until the default network changes, the grant changes or the app restarts — not retried on the same network (protocol §2: say so instead of retrying), tried again elsewhere in case a captive portal or interceptor was in the way. |
| HTTP 404 on upgrade, `RELAY_DISABLED` | Set the relay aside until the grant changes or the app restarts. |
| Another HTTP status on upgrade (401, 403, 502, 503 from a proxy in front) | Outcome "failed"; next round as usual. |
| `UNSUPPORTED_VERSION` | Set aside until the grant changes or the app restarts (an upgrade of the app restarts it). |
| `GRANT_INVALID`; close 4004 on the relay leg | Discard the grant (D5). Never ends the pairing. |
| `DESKTOP_UNAVAILABLE`, `DESKTOP_REFUSED`, `RELAY_BUSY`, `INTERNAL` | Retry: the next round tries the relay again; the round's backoff as today. |
| `RATE_LIMITED` | Skip the relay in rounds until `detail.retryAt` (at most 15 minutes ahead; a missing or past value means one minute). |
| `BAD_FRAME`, `BAD_PARAMS`, `UNKNOWN_OPERATION`; a binary frame before the answer; an `ok` answer without `protocol: 1` or without `tunnel`; close 1003 or 1009 | A protocol fault: set the relay aside until the grant changes or the app restarts, and log it. |
| Close 4003 after an error answer | Ignored: the answer decides; the phone cancels its side as soon as it has the answer. |
| Close 4010, 1001, 1000, 1011, 4001, any other code; a drop with no close code; an unknown error code | Retry. |
| Inner certificate refused | The desktop's pin mismatch, exactly as for a direct address (it counts toward "identity changed"). |
| Inner close code or `hello` error | Exactly as today (`ConnectionPolicy`): the desktop's 4004 and `AUTH_FAILED` still end the pairing; 4030 waits 60 s. |

**The two 4004s never mix.** The relay leg's end is kept in `RelayLeg`, never written into the inner connection's
`CloseInfo`, so `ConnectionPolicy` only ever sees the desktop's own codes. When a relayed session ends, a pure
`SessionEnd.decide(innerClose, innerError, relayLeg)` returns two independent things: the `ConnectionPolicy` decision,
from the desktop's own codes only (unpair, unsupported, throttled wait, backoff — a drop when the inner connection
ended without a code), and the gate's action from the relay leg's end, applied whatever the inner connection did (the
relay leg's 4004 always discards the grant).

"Set aside" and "leave out until" live in memory (`RelayGate`, held by the service's process), keyed by the grant value
so a new grant clears them, and gone after a restart of the app; the certificate case is also cleared by a change of
the default network. Outer outcomes never reach the identity tracker: a pure function maps an attempt's outcome to the
tracker's input, and the team server's outcomes map to none.

### D5. The grant

`Pairing.relay: RelayGrant?` (`url`, `fp`, `grant`, `secret`), stored in the encrypted pairing file with the device
secret; `toString()` redacts the secret. Usable when `url` is `wss://` with a host, an optional port, and nothing else
— no user info, no path (a lone trailing `/` is dropped), no query, no fragment; `fp` absent or 64 hex digits; `grant`
and `secret` non-empty strings. Updates:

- each `hello` answer: its `relay` if usable, otherwise none (absent, `null`, or unusable alike) — written with
  `updateIf`, so an unchanged grant writes nothing;
- `relay.changed` (`EventRoute.RELAY`): the same with its `relay` field;
- `GRANT_INVALID` or the relay leg's 4004: none, only if the stored grant is still the one that was refused;
- unpairing deletes it with the pairing; pairing again starts without one.

A store failure is logged and the connection goes on, as for the address list.

### D6. What the user sees

- `Status.via` (`DIRECT`, `RELAY`). Connected through the relay, the status screen says "Connected through the team
  server (`host`)" and the ongoing notification "Connected through the team server"; the address in use is the team
  server's host.
- While a grant is held, the status screen's address list ends with a row "Team server (relay) · `host`", with its
  outcome during and after a round, like the addresses. When the relay is set aside or left out until a time or a
  network change, that row says so ("not tried: the team server does not relay", "not tried until 14:05", "not tried
  on this network: the team server's certificate changed").
- Relay outcomes beyond the address ones: desktop not connected to the team server, desktop refused, team server busy,
  asked too often, relay off on the server, grant no longer valid, team server certificate, team server certificate
  changed.
- A new reason in "Say why the desktop cannot be reached", after **Refused** and before **Off the desktop's network**:
  **Desktop offline**, when the relay answered `DESKTOP_UNAVAILABLE`: the team server reached nothing either, so the
  desktop is asleep, off, offline, or spekterm is not running or not on the team. Address kinds, **Refused**, **Off the
  desktop's network** and **No answer** look at direct addresses only; **Not the desktop** counts the inner connection
  through the relay as well.

### D7. The log

`ATTEMPT` and its outcome for the relay carry `route=relay` and the team server's host (never the grant id or the
secret). New kinds: `RELAY_GRANT` (`source`: `hello`, `event`, `refused`; `change`: `stored`, `replaced`, `discarded`),
`RELAY_ANSWER` (the error code, and `retryAt` for `RATE_LIMITED`), `RELAY_ASIDE` (why, and until when).
`AUTHENTICATED` carries `route`. `tools/analyze-log.mjs` counts relay failures with the others.

### D8. Testing without leaving the desktop's network

A relayed connection is only raced when direct fails, which a test at home cannot arrange. In a debuggable build only
(`ApplicationInfo.FLAG_DEBUGGABLE`, as `ui/QrScanner.kt` already checks; the release and profile builds are not
debuggable), the presence of a file `relay-only` in the app's files directory (created with `adb shell run-as
com.spekterm.android.debug touch files/relay-only`) leaves the direct addresses out of every round. The status screen
of such a build says "debug: relay only" while it is in effect.

## Risks / Trade-offs

- [Older Conscrypt picks the descriptor socket] → the `getFileDescriptor$()` override, kept through R8 (D1). Not
  measured below API 36. If it does not help on some device, the inner handshake on the descriptor path fails (nothing
  goes out in clear); should Conscrypt throw something other than an `IOException` there, OkHttp would rethrow it and
  the app could crash. Recorded in `docs/relay-findings.md` as a check to run on an Android 10/11 device or image when
  one is at hand.
- [The relay wins at home when direct is slow] → a direct address that opens more than a second after the last direct
  start (a slow LAN, Tailscale through a DERP relay), after the relay already opened, loses until the next round. Accepted: the relay still reaches the same desktop
  through the same pin; a network change or any drop races again.
- [More latency through the relay] → bounded by the server's 64 KiB window and 512 KiB/s cap; the inner pings (30 s)
  tolerate it (server design D4).
- [The team server learns when the phone connects, from where, through which grant, and how many bytes] → inherent in
  a relay (server protocol 13.7); it cannot read or change them. It is the user's own team's server, chosen by the
  user's desktop. The listing, the forms notes and the privacy policy say so.
- [Draft fields] → the `relay` field and `relay.changed` are agreed but spekterm's change is not reviewed yet; a rename
  there is a small change here (`Frames`, `EventRoute`).

## Migration Plan

Additive. A pairing stored before has no `relay`; the first `hello` with a grant stores it. A desktop that sends no
grant leaves the phone exactly as today. Release with the next app version after spekterm's `phone-relay` ships;
direct-only users see no change.

## Open Questions

None blocking. The true away-from-home measurement (phone on mobile data through the team server for at least ten
minutes) is spekterm-server's task 7.3, run once both clients are done.
