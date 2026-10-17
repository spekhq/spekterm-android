## MODIFIED Requirements

### Requirement: Try the pairing's addresses

The app SHALL keep with the pairing both the addresses of the pairing link and the latest address list the
desktop sent — in the answer to `pair`, in each answer to `hello`, and in each `addresses.changed` event — and
SHALL keep that latest list across restarts of the app and the phone. An answer or event that carries no address
list SHALL leave the stored latest list unchanged; one that carries a list SHALL replace the stored latest list,
and SHALL never replace the link's addresses. The app SHALL read the desktop's addresses the way it reads the
link's — an IPv6 address in square brackets is the same address without them — and SHALL leave out of the latest
list addresses it cannot connect to as given. Failing to store a list SHALL NOT end the connection.

The app SHALL try, in this order and each address once: the address that last connected, unless the desktop's
latest list is non-empty and does not hold it; then the desktop's latest list in its order; then the link's
addresses in their order; then the address that last connected if it was not tried yet. Each attempt SHALL have a
bounded connection timeout, and the app SHALL use the first address on which the pinned handshake completes and
SHALL remember which address connected. The status screen SHALL show the addresses in the order they are tried,
updated when the desktop sends a new list; when there are more than three, it MAY show only how many there are
and the address in use until the user asks to see them all, and then SHALL show all of them in that order.

#### Scenario: Leaving the home network

- **WHEN** the phone leaves the LAN while the desktop is also reachable through Tailscale
- **THEN** the app reconnects through the Tailscale address without user action, and the status screen shows
  that address

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

- **WHEN** the pairing holds seven addresses and the user opens the status screen
- **THEN** the screen says there are seven addresses and which one is in use, and asking to see them all shows the
  seven in the order they are tried
