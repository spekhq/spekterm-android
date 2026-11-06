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
