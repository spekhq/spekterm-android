## MODIFIED Requirements

### Requirement: Try the pairing's addresses

The app SHALL keep with the pairing both the addresses of the pairing link and the latest address list the
desktop sent — in the answer to `pair`, in each answer to `hello`, and in each `addresses.changed` event — and
SHALL keep that latest list across restarts of the app and the phone. An answer or event that carries no address
list SHALL leave the stored latest list unchanged; one that carries a list SHALL replace the stored latest list,
and SHALL never replace the link's addresses. The app SHALL read the desktop's addresses the way it reads the
link's — an IPv6 address in square brackets is the same address without them — and SHALL leave out of the latest
list addresses it cannot connect to as given. Failing to store a list SHALL NOT end the connection.

The app SHALL try each address once per round, in this order: the address that last connected, unless the
desktop's latest list is non-empty and does not hold it; then the desktop's latest list in its order; then the
link's addresses in their order; then the address that last connected if it was not tried yet. Attempts SHALL be
started in that order and SHALL overlap: the next attempt SHALL start 250 milliseconds after the previous one
started, or at once when every attempt started so far has failed, while the earlier ones continue. Each attempt
SHALL have a bounded connection timeout. The app SHALL use the first attempt on which the pinned handshake
completes, SHALL close every other attempt of the round before sending any frame on it and start no further
attempt, and SHALL remember which address connected. At most one connection of a round SHALL ever send a frame.

The status screen SHALL show the addresses in the order they are tried, updated when the desktop sends a new
list; when there are more than three, it MAY show only how many there are and the address in use until the user
asks to see them all, and then SHALL show all of them in that order.

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

### Requirement: Reconnect with backoff

When the connection drops or no address can be reached, the app SHALL retry with an exponentially growing delay
starting at one second and capped at one minute, with random jitter that never makes a delay exceed the cap
(except after throttling, below), and SHALL reset the delay once a connection
has authenticated and its attention watch has been answered, so a connection refused right after
authenticating does not cause a reconnection storm. When the phone gains a network or its default network changes, the app SHALL try again at once.
While the app waits to retry, other than after throttling, the status screen SHALL offer "Try now", which starts
the next round at once without changing the backoff delay that follows it.
The app SHALL send its own WebSocket pings and treat a missing reply as a dropped connection, so a connection
that died silently is noticed while the phone is awake.

#### Scenario: The desktop restarts

- **WHEN** the desktop quits and starts again with remote access on
- **THEN** the app reconnects, without user action, within one backoff cap plus one connection attempt after the
  desktop listens again

#### Scenario: The network comes back

- **WHEN** the phone loses all networks and then joins a network from which the desktop is reachable
- **THEN** the app starts a connection attempt at once rather than waiting out the backoff delay

#### Scenario: Trying now

- **WHEN** the app waits to retry after a round in which no address connected, and the user taps "Try now"
- **THEN** a new round starts at once; if it fails too, the next wait is not shorter than it would have been

#### Scenario: No "Try now" while throttled

- **WHEN** the app waits after the desktop throttled it
- **THEN** the status screen offers no "Try now" until the 60 seconds have passed

### Requirement: Honor the desktop's close codes

The app SHALL act on the close code of a connection the desktop closed, and on the error answering `hello`:

- 4004 (revoked or reset), or `hello` answered `AUTH_FAILED`: stop connecting and end the pairing
  (`device-pairing`).
- `hello` answered `UNSUPPORTED_VERSION`: stop connecting, keep the pairing, and say that the app or the desktop
  needs updating, naming the versions in `detail.supported`.
- 4005 (remote access turned off, or the desktop quitting): show "remote access is off, or the desktop quit"
  until a connection authenticates again or a later round of attempts ends with no address connected — from then
  on the app says why the desktop cannot be reached ("Say why the desktop cannot be reached") — and retry with
  backoff.
- 4030, or an error frame with code `THROTTLED` (which arrives with `id: null`, before any request is
  answered): wait at least 60 seconds before the next attempt.
- 4029 (too many connections): retry with backoff.
- 4001, or 4003 in any other case: retry with backoff.

#### Scenario: Remote access turned off

- **WHEN** the user turns remote access off on the desktop while the phone is connected
- **THEN** the ongoing notification says remote access is off or the desktop quit, and the app connects again,
  without user action, within one backoff cap plus one connection attempt of it being turned back on

#### Scenario: The desktop quit, then the next rounds fail

- **WHEN** the desktop closes the connection with 4005 because spekterm quit, and the next round finds every
  address refusing
- **THEN** the status screen no longer says remote access is off, and says instead which address refused on the
  port and to check that spekterm runs with remote access on

#### Scenario: Throttled

- **WHEN** the desktop answers with a `THROTTLED` error frame and closes the connection with code 4030
- **THEN** the app makes no new attempt for at least 60 seconds

#### Scenario: A version the desktop does not support

- **WHEN** `hello` is answered `UNSUPPORTED_VERSION`
- **THEN** the app stops connecting, keeps the pairing, and its status screen says which side needs updating

## ADDED Requirements

### Requirement: Say why the desktop cannot be reached

After a round in which no address connected, the app SHALL keep each address's outcome from that round — timed
out, refused (something at the address answered and refused the connection on the port), no route, name not
found, certificate mismatch, another failure, or not tried — and the phone's network at the round's start: none,
Wi-Fi or Ethernet, mobile data (no Wi-Fi or Ethernet), or other (for example only a tether), and whether a VPN was
on. It SHALL classify each address as:

- **local**: private IPv4 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10), IPv6 unique local
  (fc00::/7) other than Tailscale's, and host names ending `.local`;
- **Tailscale**: 100.64.0.0/10, fd7a:115c:a1e0::/48, and host names ending `.ts.net`;
- **global IPv6**: 2000::/3 — a desktop's own global IPv6 addresses, which home routers usually do not let in;
- **other**: every other address and host name (a public IPv4 address, a DDNS name), which the user may have set
  up to be reachable from anywhere.

From these it SHALL choose one reason, the first that applies:

1. **No network**: the phone had no network.
2. **Not the desktop**: some address presented a certificate that does not match the pin.
3. **Refused**: some address refused. The app SHALL name the address and the port, and say that if it is the
   desktop, spekterm is not running or remote access is off — not that it is the desktop.
4. **Off the desktop's network**: the phone was on mobile data with no VPN on, and no address is other.
5. **No answer**: every address timed out or had no route. The app SHALL say that the desktop may be asleep or
   off, on another network, or behind a firewall that blocks the port — not only that the network is wrong. When
   some address is on Tailscale and no VPN was on, it SHALL add that, if the phone is not on the desktop's network,
   turning on Tailscale on the phone may help.
6. **Could not reach**: none of the above.

The status screen SHALL show the reason in plain words with what the user can do about it, and SHALL show every
address, in the order tried, with its outcome, even when there are more than three. The ongoing notification SHALL
say in a short form that the desktop cannot be reached (no network, refused, not the desktop, or cannot be
reached), without blaming the network for the generic cases.

The reason and the outcomes SHALL be replaced by the next round's when it also ends with no address connected,
and SHALL be cleared when a round completes a pinned handshake on some address (whatever happens on that
connection afterwards) or the user pauses. While the app is paused, waits after throttling, or shows that the
desktop's identity changed or that a version update is needed, the status screen and the ongoing notification
SHALL say that instead of a reason. The connection log SHALL record each attempt's outcome and the network at
each round, with no more about addresses than it already records.

#### Scenario: On mobile data without Tailscale

- **WHEN** the desktop's addresses are 192.168.0.199, two global IPv6 addresses, 100.106.81.33 and
  fd7a:115c:a1e0::6432:5121, the phone is on mobile data with Tailscale off, and a round fails
- **THEN** the status screen says the phone is off the desktop's network and suggests joining its Wi-Fi or turning
  on Tailscale, lists all five addresses with their outcomes, and the ongoing notification says the desktop
  cannot be reached

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
