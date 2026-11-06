## ADDED Requirements

### Requirement: Keep the desktop's relay grant

The app SHALL keep, with the pairing and protected as the device secret is, the relay grant the desktop gives this
phone: the team server's address, its certificate fingerprint when one is given, the grant's id and its secret. The
grant SHALL be usable only when the address is `wss://` with a host and no path, the fingerprint is absent or 64
hexadecimal digits, and the id and secret are non-empty. Each answer to `hello` SHALL set the stored grant to the one it
carries, or to none when it carries none or one that is not usable; each `relay.changed` event SHALL do the same with
its value. The app SHALL discard the grant when the team server says it is not valid (`GRANT_INVALID`, or close code
4004 on the connection to the team server), unless the desktop gave a different grant meanwhile, and SHALL discard it
with the pairing. The grant SHALL never be taken from a pairing link or the answer to `pair`, and the secret SHALL never
appear in the connection log, the status screen or a notification. Failing to store the grant SHALL NOT end the
connection.

#### Scenario: The desktop gives a grant

- **WHEN** the desktop's answer to `hello` carries a usable `relay`
- **THEN** the app stores it, and the status screen lists the team server as the last way to reach the desktop

#### Scenario: The desktop takes the grant back while connected

- **WHEN** the phone is connected and the desktop sends `relay.changed` with `relay: null`
- **THEN** the app discards the grant, and later rounds try the direct addresses only

#### Scenario: An older desktop

- **WHEN** the desktop answers `hello` without `relay`
- **THEN** the app holds no grant and connects exactly as it did before the relay existed

#### Scenario: The grant was revoked

- **WHEN** the team server answers `relay.open` with `GRANT_INVALID`
- **THEN** the app discards the grant and does not try the relay again until the desktop gives a new one

### Requirement: Reach the desktop through the team server

With a usable grant, the app SHALL be able to reach the desktop through the team server: it SHALL open a WebSocket over
TLS to the grant's address with the path `/relay`, and SHALL check the team server's certificate before sending
anything — against the grant's fingerprint (the leaf certificate's SHA-256 over its DER, no certificate authority, no
host name) when the grant has one, otherwise against the system's trusted certificate authorities and the host name.
It SHALL then send one `relay.open` request with the protocol versions it speaks, the grant's id and its secret, and
send nothing more until that request is answered. After a successful answer it SHALL open the connection to the desktop
inside it, checked exactly as a direct connection is ("Trust only the pinned certificate"), and authenticate with
`hello` as on a direct connection. It SHALL send its bytes to the team server as binary messages of at most 64 KiB each,
SHALL give the whole attempt — the team server's TLS and upgrade, the wait for the desktop and the desktop's TLS — at
least 25 seconds before giving it up, and SHALL never send `pair` through the team server.

The app SHALL act on the team server's answers and close codes:

- `GRANT_INVALID`, or close code 4004 on the connection to the team server: discard the grant ("Keep the desktop's relay
  grant"); this SHALL never end the pairing.
- `RELAY_DISABLED`, an HTTP 404 answer to the upgrade, or `UNSUPPORTED_VERSION`: stop trying the relay until the grant
  changes or the app restarts.
- A team server certificate that does not match the grant's fingerprint: say that the team server's certificate
  changed, and stop trying the relay on the current network until the phone's network changes, the grant changes or
  the app restarts.
- `BAD_FRAME`, `BAD_PARAMS`, `UNKNOWN_OPERATION`, a successful answer the app cannot use, close code 1003 or 1009: record
  it and stop trying the relay until the grant changes or the app restarts.
- `RATE_LIMITED`: leave the relay out of rounds until `detail.retryAt`, and never more than 15 minutes.
- `DESKTOP_UNAVAILABLE`, `DESKTOP_REFUSED`, `RELAY_BUSY`, `INTERNAL`, any other error or HTTP status, close codes 4010,
  1001 and any other close code, and a connection that drops without a close code: try again in the next round, with
  the backoff of "Reconnect with backoff". The close code 4003 that follows an error answer SHALL be ignored in favor of
  the answer.

A close code or an error from the desktop on the inner connection SHALL be honored as on a direct connection ("Honor
the desktop's close codes"), and only the desktop's codes SHALL decide whether the pairing ends and how long the app
waits before the next round; the team server's answers and close codes SHALL decide only what happens to the grant and
the relay, as listed above, whatever the desktop's connection did.

#### Scenario: Away from the desktop's network

- **WHEN** the phone holds a grant, is on a network from which no direct address answers, and the desktop is connected
  to its team server
- **THEN** the app authenticates with the desktop through the team server, and the desktop's certificate was checked
  against the pairing's pin

#### Scenario: Another certificate inside the relay

- **WHEN** the connection inside the relay presents a certificate whose fingerprint differs from the pairing's pin
- **THEN** the app abandons it without sending a frame and records a certificate mismatch, as for a direct address

#### Scenario: The team server revokes the grant while connected

- **WHEN** the phone is connected through the team server and the team server closes its connection with 4004
- **THEN** the app discards the grant, keeps the pairing, and tries the direct addresses in the next round

#### Scenario: The team server's certificate changed

- **WHEN** the grant carries a fingerprint and the team server presents another certificate
- **THEN** the app sends nothing to it, the status screen says the team server's certificate changed, and the app does
  not try the relay again on that network until the phone's network changes, the desktop gives another grant or the app
  restarts; direct addresses are tried as before

#### Scenario: A large message through the relay

- **WHEN** the desktop sends a message of 1 MiB to the phone through the relay, and the phone sends one of 300 KiB
- **THEN** both arrive whole, and no message the phone sends to the team server is larger than 64 KiB

#### Scenario: The desktop takes its time

- **WHEN** the team server waits 10 seconds for the desktop before answering `relay.open`
- **THEN** the app is still waiting for the answer, and does not count the attempt as failed

#### Scenario: Asked too often

- **WHEN** the team server answers `RATE_LIMITED` with `retryAt` five minutes ahead
- **THEN** rounds in the next five minutes try only the direct addresses, and the status screen says when the relay is
  tried again

#### Scenario: The team server does not relay

- **WHEN** the team server answers `RELAY_DISABLED`
- **THEN** the app does not try the relay again until the grant changes or the app restarts

### Requirement: Show which path is in use

When the connection goes through the team server, the status screen SHALL say so and name the team server's host, and
the ongoing notification SHALL say that the phone is connected through the team server. When it is direct, both SHALL
read as they did before the relay existed.

#### Scenario: Connected through the team server

- **WHEN** the phone authenticates with the desktop through the team server
- **THEN** the ongoing notification says it is connected through the team server, and the status screen names the team
  server's host as the way in use

#### Scenario: Back on the desktop's network

- **WHEN** the phone, connected through the team server, joins the desktop's Wi-Fi
- **THEN** the app races again at once, connects directly, and the status screen and notification no longer mention the
  team server

## MODIFIED Requirements

### Requirement: Trust only the pinned certificate

Every connection to the desktop SHALL be `wss://<address>:<port>/v1` with TLS 1.2 or later — directly, or inside the
team server's relay ("Reach the desktop through the team server"). The app SHALL trust the desktop's
certificate only when the SHA-256 digest of the leaf certificate's DER encoding equals the pairing's
fingerprint, and SHALL check this in both the TLS trust decision and the host name decision; no certificate
authority, no host name and no other certificate SHALL ever be trusted, whichever way the connection goes. Before
sending the first frame, the app SHALL check the pin once more against the certificate of the established connection.
A certificate with no extensions and no subject alternative name SHALL be accepted when it matches the pin. Nothing —
above all the pairing code or the credential — SHALL be sent on a connection whose pin was not confirmed.

#### Scenario: The desktop's certificate is accepted by its pin

- **WHEN** the app connects to the paired desktop, whose certificate is self-signed ECDSA P-256 with no
  extensions, by an IP address that appears nowhere in the certificate
- **THEN** the TLS handshake completes and the app authenticates

#### Scenario: Another certificate is refused before anything is sent

- **WHEN** a server at one of the pairing's addresses presents a certificate whose fingerprint differs from the
  pin
- **THEN** the app abandons that connection without sending a frame, records a certificate mismatch for that
  address, and tries the next address

#### Scenario: The desktop's identity changed

- **WHEN** on three consecutive rounds of attempts at least one address presents a certificate that does not
  match the pin and no address presents a matching one (addresses that cannot be reached do not count either
  way) — as after the desktop resets remote access while the phone is away
- **THEN** the app keeps refusing those certificates, shows on its status screen that the desktop's identity does
  not match the pairing, and raises one notification suggesting pairing again; the pairing is kept

#### Scenario: The identity matches again

- **WHEN** after the identity-changed state, a round completes the pinned handshake on some address
- **THEN** the identity-changed state and its notification are cleared

#### Scenario: The team server's certificate is not the desktop's identity

- **WHEN** the team server presents a certificate that does not match the grant's fingerprint
- **THEN** this does not count toward the desktop's identity having changed

### Requirement: Try the pairing's addresses

The app SHALL keep with the pairing both the addresses of the pairing link and the latest address list the
desktop sent — in the answer to `pair`, in each answer to `hello`, and in each `addresses.changed` event — and
SHALL keep that latest list across restarts of the app and the phone. An answer or event that carries no address
list SHALL leave the stored latest list unchanged; one that carries a list SHALL replace the stored latest list,
and SHALL never replace the link's addresses. The app SHALL read the desktop's addresses the way it reads the
link's — an IPv6 address in square brackets is the same address without them — and SHALL leave out of the latest
list addresses it cannot connect to as given. Failing to store a list SHALL NOT end the connection.

The app SHALL try each address once per round, in this order: the address that last connected directly, unless the
desktop's latest list is non-empty and does not hold it; then the desktop's latest list in its order; then the
link's addresses in their order; then the address that last connected if it was not tried yet. Attempts SHALL be
started in that order and SHALL overlap: the next attempt SHALL start 250 milliseconds after the previous one
started, or at once when every attempt started so far has failed, while the earlier ones continue. Each attempt
SHALL have a bounded connection timeout. When the phone holds a usable relay grant that is not set aside or waiting
("Reach the desktop through the team server"), the team server SHALL be the round's last attempt, started 1 second
after the last direct attempt started, or at once when every attempt started so far has failed. The app SHALL use the
first attempt on which the pinned handshake with the desktop completes, SHALL close every other attempt of the round
before sending any frame on it and start no further attempt, and SHALL remember which address connected when it was a
direct one. At most one connection of a round SHALL ever send a frame to the desktop.

The status screen SHALL show the addresses in the order they are tried, updated when the desktop sends a new
list, followed by the team server while a grant is held; when there are more than three addresses, it MAY show only
how many there are and the address in use until the user asks to see them all, and then SHALL show all of them in that
order.

#### Scenario: Leaving the home network

- **WHEN** the phone leaves the LAN while the desktop is also reachable through Tailscale
- **THEN** the app reconnects through the Tailscale address without user action, and the status screen shows
  that address

#### Scenario: An unreachable first address does not hold up the next

- **WHEN** the address tried first does not answer at all and the one after it is reachable
- **THEN** the app authenticates over the second address within about a second of the round starting, not after
  the first address's connection timeout, and closes the first attempt without sending anything on it

#### Scenario: Two addresses answer

- **WHEN** two of the pairing's addresses reach the desktop
- **THEN** the app authenticates on exactly one connection, closes the other before sending a frame on it, and the
  desktop counts no failed attempt for the closed one

#### Scenario: On the desktop's network with a grant

- **WHEN** the phone holds a grant and a direct address answers within a second of the last direct attempt starting
- **THEN** the app connects directly and never contacts the team server in that round

#### Scenario: Every direct address fails at once

- **WHEN** the phone holds a grant and every direct address fails at once (no route, refused)
- **THEN** the app starts the team server attempt at once, without waiting out the second

#### Scenario: The desktop's address changes while connected

- **WHEN** the phone is connected and the desktop's list of addresses changes (a new LAN address, or the user
  edits the additional address in the desktop's remote access settings)
- **THEN** within a few seconds the status screen shows the new list first in the order, and after the connection
  drops the app tries the new addresses before the link's

#### Scenario: The desktop's address changed while the phone was away

- **WHEN** the desktop's LAN address changed while the phone was not connected, and the phone connects through
  another address (for example Tailscale)
- **THEN** the desktop's new list, received in the answer to `hello`, is tried first on the next reconnection

#### Scenario: An older desktop sends no list

- **WHEN** the desktop answers `hello` without an address list
- **THEN** the app keeps the latest list it stored before, and keeps trying the link's addresses

#### Scenario: The link's addresses stay

- **WHEN** the desktop's latest list no longer holds an address of the pairing link
- **THEN** the app still tries that address, after the desktop's list

#### Scenario: Many addresses

- **WHEN** the pairing holds seven addresses and the user opens the status screen while connected
- **THEN** the screen says there are seven addresses and which one is in use, and asking to see them all shows the
  seven in the order they are tried

### Requirement: Say why the desktop cannot be reached

After a round in which no address connected, the app SHALL keep each address's outcome from that round — timed
out, refused (something at the address answered and refused the connection on the port), no route, name not
found, certificate mismatch, another failure, or not tried — and the phone's network at the round's start: none,
Wi-Fi or Ethernet, mobile data (no Wi-Fi or Ethernet), or other (for example only a tether), and whether a VPN was
on. When the team server was part of the round, the app SHALL also keep its outcome: one of the address outcomes, or
the desktop not connected to the team server, the desktop refused, the team server busy, asked too often, relaying
off, the grant not valid, the team server's certificate, or the team server's certificate changed. It SHALL classify each direct address as:

- **local**: private IPv4 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10), IPv6 unique local
  (fc00::/7) other than Tailscale's, and host names ending `.local`;
- **Tailscale**: 100.64.0.0/10, fd7a:115c:a1e0::/48, and host names ending `.ts.net`;
- **global IPv6**: 2000::/3 — a desktop's own global IPv6 addresses, which home routers usually do not let in;
- **other**: every other address and host name (a public IPv4 address, a DDNS name), which the user may have set
  up to be reachable from anywhere.

From these it SHALL choose one reason, the first that applies:

1. **No network**: the phone had no network.
2. **Not the desktop**: some address, or the connection inside the team server, presented a certificate that does not
   match the pin.
3. **Refused**: some direct address refused. The app SHALL name the address and the port, and say that if it is the
   desktop, spekterm is not running or remote access is off — not that it is the desktop.
4. **Desktop offline**: the team server answered that the desktop is not connected to it. The app SHALL say that the
   team server could not reach the desktop either, so it may be asleep, off or offline, or spekterm may not be running.
5. **Off the desktop's network**: the phone was on mobile data with no VPN on, and no direct address is other.
6. **No answer**: every direct address timed out or had no route. The app SHALL say that the desktop may be asleep or
   off, on another network, or behind a firewall that blocks the port — not only that the network is wrong. When
   some address is on Tailscale and no VPN was on, it SHALL add that, if the phone is not on the desktop's network,
   turning on Tailscale on the phone may help.
7. **Could not reach**: none of the above.

The status screen SHALL show the reason in plain words with what the user can do about it, and SHALL show every
address, in the order tried, with its outcome, even when there are more than three, followed by the team server and its
outcome when it was part of the round, or why it was left out (set aside, waiting until a time, or not on this network). The ongoing
notification SHALL say in a short form that the desktop cannot be reached (no network, refused, not the desktop, or
cannot be reached), without blaming the network for the generic cases.

The reason and the outcomes SHALL be replaced by the next round's when it also ends with no address connected,
and SHALL be cleared when a round completes a pinned handshake on some address (whatever happens on that
connection afterwards) or the user pauses. While the app is paused, waits after throttling, or shows that the
desktop's identity changed or that a version update is needed, the status screen and the ongoing notification
SHALL say that instead of a reason. The connection log SHALL record each attempt's outcome and the network at
each round, with no more about addresses than it already records, and for the team server only its host.

#### Scenario: On mobile data without Tailscale

- **WHEN** the desktop's addresses are 192.168.0.199, two global IPv6 addresses, 100.106.81.33 and
  fd7a:115c:a1e0::6432:5121, the phone is on mobile data with Tailscale off, holds no grant, and a round fails
- **THEN** the status screen says the phone is off the desktop's network and suggests joining its Wi-Fi or turning
  on Tailscale, lists all five addresses with their outcomes, and the ongoing notification says the desktop
  cannot be reached

#### Scenario: The desktop is offline on the team server too

- **WHEN** the phone holds a grant, is on mobile data, every direct address times out, and the team server answers
  that the desktop is not connected
- **THEN** the status screen says the team server could not reach the desktop either and it may be asleep, off or
  offline, and lists the team server after the addresses with that outcome

#### Scenario: No answer with Tailscale off

- **WHEN** the phone is on a Wi-Fi with no VPN, the desktop's addresses include a Tailscale address and no other
  address, and every address times out — as on another Wi-Fi, or on the desktop's own while it sleeps
- **THEN** the status screen says the desktop does not answer and may be asleep or off, on another network, or
  behind a firewall, and adds that turning on Tailscale on the phone may help if it is not on the desktop's network

#### Scenario: spekterm is not running

- **WHEN** the phone is on the desktop's Wi-Fi, the computer is on, spekterm is not running, and the phone was
  not connected when spekterm quit
- **THEN** the status screen names the address that refused on the port and suggests checking that spekterm runs
  with remote access on

#### Scenario: The desktop does not answer

- **WHEN** the phone is on a Wi-Fi with no VPN, the desktop's addresses are a LAN address and global IPv6
  addresses only, and every address times out
- **THEN** the status screen says the desktop does not answer, that it may be asleep or off, on another network,
  or behind a firewall blocking the port (naming it)

#### Scenario: No network

- **WHEN** the phone has no network at all
- **THEN** the status screen says the phone has no network

#### Scenario: Connected again

- **WHEN** a later round connects
- **THEN** the reason and the per-address outcomes are no longer shown, and the ongoing notification says
  connected
