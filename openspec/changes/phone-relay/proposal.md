## Why

Today the phone reaches its desktop only directly: on the desktop's LAN, through Tailscale, or through a port the user
forwards. Away from those, it cannot connect at all. spekterm-server 0.4.0 (change `phone-relay` in that repository)
added an optional, blind relay for members of a team: the phone and the desktop each open a WebSocket to the team
server's `/relay` path and the server splices them, passing the phone's TLS to the desktop through unread. spekterm's
own `phone-relay` change hands a paired phone the grant it needs. This change is the phone's side: use the relay as a
second path when the desktop has given a grant, keeping direct access the default and first choice.

Decided upstream (2026-11-06) and not reopened here: direct stays the default; the relay is optional, only through a
team server the desktop is a member of; no push; no pairing through the relay; no access across people.

## What Changes

- **Keep the grant the desktop gives.** The `hello` answer may carry `relay: { url, fp?, grant, secret }`, and a new
  `relay.changed` event carries the current value (or `null`) while connected. The phone stores it with the pairing
  (encrypted, like the device secret), replaces it when it changes, and discards it when a `hello` answer has none,
  when the event says `null`, on unpairing, and when the team server says the grant is invalid (`GRANT_INVALID`,
  close 4004 on the relay leg).
- **The relay joins the address race last.** With a grant, each round races the direct addresses exactly as today and
  then the relay, started 1 second after the last direct attempt started (or at once when every direct attempt has
  already failed). On the desktop's network the direct path wins; elsewhere the relay answers about a second later.
  A network change starts a new round as today, so returning to the desktop's network returns to direct.
- **Two certificate checks.** The connection to the team server is pinned to `fp` when the grant has one, otherwise
  checked against the system trust store and the host name. Inside it, the TLS to the desktop is checked exactly as
  today (leaf pin in the trust manager and the host name verifier, and again on open). Nothing goes to the team server
  before its check passes; no byte of the desktop's TLS before the server answers `relay.open`.
- **The desktop's connection runs inside the relay's**, through a `java.net.Socket` backed by the relay WebSocket and
  handed to the existing OkHttp client — the inner client, its pin and the remote protocol code are unchanged. Every
  write to the relay is cut into messages of at most 64 KiB. The whole relay attempt may take up to 30 seconds (the
  server waits up to 10 for the desktop), not the 10 of a direct address.
- **The relay's answers decide what happens next**, per the server's protocol section 13.6: discard the grant; stop
  trying the relay on that server until the grant changes or the app restarts; back off as today; or wait until the
  server's `retryAt`.
- **The user sees the path.** The status screen and the ongoing notification say when the connection goes through the
  team server; the status screen lists the relay as the last candidate with its outcome, and the reasons for "cannot be
  reached" gain one: the desktop is not connected to the team server either.
- **The "no relay" statements change** — README, the main spec's purpose, the OpenSpec project context, the Google Play
  listing and its feature graphic, and the Play forms notes — to "direct by default; optionally through your own team's spekterm-server, still
  end-to-end encrypted to your desktop".

Not changed: the remote protocol version (1); pairing (still direct only — the phone never sends `pair` through the
relay); the phone needs no team membership and never sees a member credential; no push.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `desktop-connection`: purpose no longer says "without a relay"; "Try the pairing's addresses" gains the relay as the
  last candidate; "Trust only the pinned certificate" covers the inner TLS through the relay; new requirements "Keep the
  desktop's relay grant", "Reach the desktop through the team server" (outer check, ordering, cutting, timeout, codes)
  and "Show which path is in use"; "Say why the desktop cannot be reached" gains the relay's outcome and a reason.

## Impact

- Code: `net/` — new relay client (outer WebSocket, `relay.open`, the tunnel socket and its socket factory), the race
  over candidates with their own start delays; `store/Stores.kt` (`Pairing.relay`); `net/Frames.kt` (reading `relay`,
  `relay.open`); `service/ConnectionService.kt` (candidates, grant updates from `hello` and `relay.changed`, the relay's
  codes), `service/EventRoute.kt`, `service/Unreachability.kt`, `service/Status.kt`, `ui/StatusScreen.kt`, the
  notification text, strings (en, zh-TW), `log/ConnectionLog.kt`.
- Tests: JVM tests for the race order, the grant's life, the code table and the cutting; instrumented tests on the
  emulator for the tunnel (two pins, a 1 MiB message each way) against a fake relay; an end-to-end run against a local
  spekterm-server with `RELAY=on` and a spekterm desktop that hands out grants.
- Build: `app/proguard-rules.pro` (keep the tunnel socket's descriptor override in minified builds).
- Docs: README, `openspec/config.yaml`, `play/listings/*`, `play/forms.md`, `play/source/feature.html` and the feature
  graphic rendered from it, `docs/relay-findings.md` (new: the spike and the end-to-end runs). The Data safety answer and
  the foreground-service description are re-entered in the Play Console at the next release (outside the codebase).
- Outside this repository: the app's privacy policy on spekterm.com (in the spekterm repository) must say what the
  team server sees when the relay is used.
- Depends on: spekterm's `phone-relay` (the `relay` field and `relay.changed`, `docs/remote-protocol.md`) and
  spekterm-server 0.4.0. Both are in progress; the end-to-end run waits for both.
