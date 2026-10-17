# desktop-connection Specification

## Purpose
Keeps one authenticated, certificate-pinned connection to the paired spekterm desktop open in the background,
so the app learns of events without a relay or a push service, and recovers it when it drops.

## Requirements

### Requirement: Trust only the pinned certificate

Every connection SHALL be `wss://<address>:<port>/v1` with TLS 1.2 or later. The app SHALL trust the desktop's
certificate only when the SHA-256 digest of the leaf certificate's DER encoding equals the pairing's
fingerprint, and SHALL check this in both the TLS trust decision and the host name decision; no certificate
authority, no host name and no other certificate SHALL ever be trusted. Before sending the first frame, the app
SHALL check the pin once more against the certificate of the established connection. A certificate with no
extensions and no subject alternative name SHALL be accepted when it matches the pin. Nothing — above all the
pairing code or the credential — SHALL be sent on a connection whose pin was not confirmed.

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

### Requirement: Authenticate with the stored credential

On a paired phone, the first frame of every connection SHALL be `hello` with `protocol: [1]`, the device id and
the device secret, sent within 10 seconds of the handshake. The app SHALL send no other request before `hello`
is answered successfully.

#### Scenario: Reconnecting authenticates

- **WHEN** the app opens a connection to the paired desktop
- **THEN** its first frame is `hello`, and the desktop accepts it

### Requirement: Hold the connection in a foreground service

While the phone is paired, the app SHALL keep the connection open from a foreground service, whose ongoing
notification states the desktop's name and the connection state (connected, connecting, waiting to retry,
remote access off or the desktop quit). The service SHALL keep running when the screen is off, when the user
leaves or swipes away the app, and while the phone is idle. After the phone restarts, the service SHALL start
again once the phone is unlocked. The service SHALL ask the system to restart it after its process is killed;
when such a restart reaches the app but the system refuses to let the service start from the background, the
app SHALL post a notification saying the connection stopped, and tapping it SHALL resume the connection.
(Whether the system restarts the service at all after a kill — and after a vendor's battery manager or a
force stop it does not — is recorded in the findings, not promised here.) The user SHALL be able to pause the connection
from the ongoing notification and from the status screen, without unpairing; a paused connection SHALL stay
paused across restarts of the app and the phone until the user resumes it.

#### Scenario: Pausing from the notification

- **WHEN** the user chooses Pause on the ongoing notification
- **THEN** the connection closes, the foreground service and its notification go away, and the service does
  not start again after the phone restarts until the user resumes it in the app

#### Scenario: The system refuses a restart

- **WHEN** the system kills the app's process and refuses to start the foreground service again from the
  background
- **THEN** the phone shows a notification saying the connection to the desktop stopped, and tapping it
  reconnects

#### Scenario: Screen off overnight

- **WHEN** the phone is left unplugged with the screen off for at least eight hours while paired, the desktop
  stays on, and inbox items arrive on the desktop during that time
- **THEN** in the morning the app is connected, the phone has shown a notification for every one of those items,
  and the connection log holds every disconnection with its cause and every attention event with its receive
  time

#### Scenario: The app is swiped away

- **WHEN** the user swipes the app away from the recent apps
- **THEN** the foreground service keeps running and the connection stays open

#### Scenario: The phone restarts

- **WHEN** the phone restarts while paired, and the user unlocks it
- **THEN** the service starts and connects without the user opening the app

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

### Requirement: Keep a connection log

The app SHALL record, with timestamps, each connection attempt and its address, each authentication, each
disconnection with its close code or failure, each network change, each change of the phone's idle mode and
power-save mode, the app's standby bucket, each attention event received (its position, kind, the desktop's
`at`, the receive time, whether it was live or part of a watch answer, and whether it was notified, suppressed
or summarized), and the battery level at each of these. The log SHALL hold no credential, pairing code or
notification text (no title, summary, tool or folder name), SHALL be bounded in size, and SHALL be exportable
from the status screen as a text file.

#### Scenario: Exporting the log

- **WHEN** the user exports the connection log from the status screen
- **THEN** the phone's share sheet offers a text file with the recorded entries, and it contains no device
  secret and no pairing code

### Requirement: Ignore what the app does not know

The app SHALL treat a field it does not recognize, in any frame, and an event kind it does not recognize as
neither an error nor a reason to drop the connection: the unknown field and the unknown event SHALL be skipped,
and the rest of the frame, the frames after it, and the connection SHALL be handled as if it were absent.

#### Scenario: An unknown event arrives

- **WHEN** the desktop sends an event of a kind the app does not know while connected
- **THEN** the connection stays up, nothing about it is shown, and the events after it are handled normally

#### Scenario: An answer carries an unknown field

- **WHEN** the desktop's answer to `hello` carries a field the app does not know
- **THEN** the app authenticates and continues as if the field were absent

### Requirement: Offer a control only when the desktop accepts its operation

The app SHALL keep the list of operations the desktop's answer to `hello` or `pair` names, from the latest
authentication, and SHALL show renaming a session, attaching files and answering prompts only when the desktop
listed `sessions.rename`, `attachments.put` and `conversation.answer` respectively. An answer that names no
operations SHALL count as listing none of them, and the app SHALL then behave as it did before these controls
existed. The list SHALL be kept while the phone is disconnected — the controls stay, unusable until the phone
reconnects — and SHALL be forgotten when the user unpairs or pairs again.

#### Scenario: An older desktop

- **WHEN** the phone authenticates with a desktop whose answer names no operations, and the agent asks for a
  choice
- **THEN** the conversation offers no rename, no attach and no answers, and says to answer the choice on the
  desktop

#### Scenario: A desktop that offers them

- **WHEN** the phone authenticates with a desktop whose answer lists `sessions.rename`, `attachments.put` and
  `conversation.answer`
- **THEN** the conversation screen offers renaming, attaching and, for a prompt the desktop says can be answered,
  the answers

#### Scenario: Disconnected

- **WHEN** the connection to a desktop that offers attaching ends while a conversation is on screen
- **THEN** the attach control stays on screen but cannot be used until the phone reconnects

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
