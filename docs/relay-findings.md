# Relay findings (`phone-relay`)

Measurements behind the phone's side of the team server relay (spekterm-server protocol section 13). Each entry says
where and how it was measured.

## 1. Layering spike (2026-11-06, Pixel_9 emulator, Android 16 / API 36, debug build)

A throwaway instrumented test spliced a TLS WebSocket (`MockWebServer` with the fixture certificate, playing the team
server's `/relay`) to the fixture desktop over TCP, and opened the unchanged pinned `RemoteClient` over a `Socket`
subclass backed by that WebSocket (design D1, way (b)).

- The inner handshake completed through all three pin checks (trust manager, host name verifier, on open).
- A `hello` answer of 1 MiB arrived whole; a 300 KiB request reached the desktop.
- Conscrypt wrapped the tunnel socket with `com.android.org.conscrypt.ConscryptEngineSocket`; its writes to the tunnel
  were at most 8214 bytes each.
- libcore's hidden `Socket.getFileDescriptor$()` on a plain `new Socket()` returns a `FileDescriptor` object (no
  exception), so Conscrypt's "has a descriptor" test would pass for it; on API 36 the engine socket is chosen anyway
  (Conscrypt's engine-socket default; its flag is a blocked hidden field, so it could not be read).
- Declaring `getFileDescriptor$()` on the subclass: a reflective call through `Socket` reaches the override (it threw
  the subclass's exception). Whether an older Conscrypt (API 29–35) then chooses the engine socket is **not measured**:
  only the API 36 image is installed here. To check on an Android 10 or 11 device or image when one is at hand.

## 2. `RelayClientTest` (2026-11-06, same emulator)

Ten cases against a fake team server, all passing:

- the desktop reached through the relay, `hello` authenticated, a 1 MiB answer and a 300 KiB request whole, no message
  from the phone to the team server over 64 KiB; the only text frame the phone sent was `relay.open`;
- no server name in the desktop's handshake inside the tunnel (`handshakeServerNames` empty), while a direct
  connection by name to the same fake desktop reports `["localhost"]`;
- a desktop with another certificate inside the relay → the desktop's pin mismatch, nothing sent to it;
- a team server whose certificate is not the grant's `fp` → refused before `relay.open`;
- without `fp`: a team server certified by an authority the client trusts, for `localhost` → accepted; the fixture's
  self-signed certificate → refused before `relay.open`;
- `RELAY_DISABLED`, `GRANT_INVALID`, `DESKTOP_REFUSED`, `RATE_LIMITED` with `detail.retryAt`, and an `ok` answer without
  `tunnel` → their outcomes; HTTP 404 and 503 on the upgrade → those statuses;
- `DESKTOP_UNAVAILABLE` answered after 10 s → still waiting at 10 s, then that answer;
- the team server closing the phone's leg with 4004 while connected → the relay leg records 4004 and the desktop's
  connection does not (it sees a drop).

## 3. The descriptor override survives R8 (2026-11-06, `assembleProfile`)

With the `-keepclassmembers` rule in `app/proguard-rules.pro`, the shrunk profile build keeps the method: in
`app-profile.apk` the tunnel socket is class `z74` (`mapping.txt`: `com.spekterm.android.net.TunnelSocket -> z74`),
superclass `java.net.Socket`, and `dexdump` lists a method named `getFileDescriptor$` on it. (Without the rule R8
removes such a method: measured by the change's review with R8 8.10.9 on a minimal class.)

## 4. End to end on the emulator (2026-11-06, `phone-relay` 5.1)

Setup: spekterm-server 0.4.0 (`RELAY=on`, port 8443, self-signed certificate, so the desktop's membership and the
grant carry `fp`); the spekterm desktop from source with its `phone-relay` work (uncommitted, 25/27 tasks), already a
member of that server, remote access on port 47616; the Pixel_9 emulator's debug build paired with it through the pairing
link; `adb reverse tcp:8443 tcp:8443`, so the grant's `wss://localhost:8443` reaches the server from the emulator. The
emulator reaches the desktop's LAN address on either of its networks, so `files/relay-only` (D8) stood in for "away".

| Case | Outcome |
|---|---|
| Turning the desktop's relay setting on while the phone is connected | `relay_grant source=event change=stored host=localhost` within a second |
| (a) `relay-only`, a network change | `attempt route=relay` → `authenticated route=relay` 0.37 s later; the server logged the tunnel opened. Status screen: "Connected through the team server (localhost) since …", the address list ending "Team server (relay) · localhost", "debug: relay only"; ongoing notification: "Connected through the team server" (screenshots checked, not committed: they show the desktop's sessions and inbox) |
| (a) Through the relay: the inbox list (196 items), OpenSpec specs (71) and changes (105) of a large repository, a 26 KB design artifact | all shown; no disconnection |
| (b) `relay-only` removed, Wi-Fi back on while relayed | the network change raced again: `authenticated address=192.168.0.199 route=direct` 0.22 s after the attempt; the server logged no new tunnel request for that round |
| (c) The desktop's remote access off, relayed | the desktop closed with 4005; each relay attempt answered `DESKTOP_REFUSED` and was retried with backoff; the status screen's relay row said "the desktop refused" |
| (d) The desktop's relay setting off, relayed | `relay_grant source=event change=discarded`, then `disconnected failure=EOFException relayCode=4004`; the pairing kept; the server logged the grant revoked and the tunnel closed (1121 bytes to the desktop, 34871 to the phone) |
| (e) The team server stopped, `relay-only` | the relay attempt failed (`SSLHandshakeException` through `adb reverse`, which accepts and closes: "failed" on the relay row; a real network would say "refused"); without `relay-only` the direct address connected at once |
| (f) The app's sockets while relayed, five samples a second apart (`/proc/net/tcp*` by the app's UID) | one established socket, to 127.0.0.1:8443 (the team server through `adb reverse`) |
| The exported log | 32 entries name `host=localhost`; none of the 22 grant ids in the server's log and no secret; `tools/analyze-log.mjs`: "attempts 22 (13 failed) · through relay 17 (4 authenticated)" |

Notes:

- In `relay-only` with no grant the phone has no candidate at all, so it cannot hear of a new grant (grants arrive on an
  authenticated connection). In real use the direct addresses stay in the race; this is the debug switch's limit only.
- A round where the relay answered `DESKTOP_REFUSED` and no direct address was tried (debug `relay-only`, mobile data)
  shows "Off the desktop's network": with no direct outcome the rule applies vacuously. The relay row says what happened.
- The phone never sends `pair` through the relay (pairing uses the link's addresses only), so the desktop's refusal of
  `pair` on a relayed connection is not reachable from the app.

## 5. Regressions (2026-11-06)

- `./gradlew test`: all JVM tests pass (one run of `ReadLinesTest.twoThousandLinesParseQuickly`, a 50 ms timing bound,
  failed once at 67 ms while the emulator was busy and passed on the rerun).
- `connectedDebugAndroidTest` on the emulator: 215 tests, 214 passed; `DesktopRaceTest` is skipped by its assumption
  (it needs a real desktop's addresses as runner arguments) and is reported as an assumption failure.

## 6. On the real phone

Not run in this change: the maintainer removed the Pixel 7 task (2026-11-07). The phone on a real mobile network through
the team server for ten minutes is spekterm-server's task 7.3, run once both clients are done.
