# device-pairing Specification

## Purpose
Lets the user pair the phone with their spekterm desktop from the desktop's pairing QR code or link, and keeps
the credential the desktop issues in protected storage until the pairing ends.

## Requirements

### Requirement: Read a pairing link

The app SHALL accept a pairing link of the form
`spekterm://pair?v=1&a=<addresses>&p=<port>&fp=<fingerprint>&c=<code>&n=<name>`, either decoded from a QR code
or pasted as text. It SHALL decode the comma-separated address list (a comma may arrive as `%2C`), accept IPv4,
IPv6 and host-name addresses, normalize the fingerprint to 64 hexadecimal digits ignoring case and `:`
separators, and accept the 26-character code case-insensitively. It SHALL refuse a link whose version is not
`1`, whose fingerprint is not 64 hexadecimal digits after normalization, whose port is not 1–65535, whose code
is not 26 RFC 4648 base32 characters, or that has no address, and it SHALL open no connection for a refused link.

#### Scenario: A pasted link is read

- **WHEN** the user pastes a valid pairing link
- **THEN** the app shows the desktop's name from `n` and starts pairing with the link's addresses, port,
  fingerprint and code

#### Scenario: An encoded address list is split

- **WHEN** the link's `a` is `192.168.50.22%2C100.106.81.33%2Cfd7a%3A115c%3Aa1e0%3A%3A1`
- **THEN** the pairing holds the three addresses `192.168.50.22`, `100.106.81.33` and `fd7a:115c:a1e0::1`, in
  that order

#### Scenario: A link of another version is refused

- **WHEN** the user pastes a link with `v=2`
- **THEN** the app says the link needs a newer version of the app, and opens no connection

#### Scenario: A malformed fingerprint is refused

- **WHEN** the user pastes a link whose `fp` has 63 hexadecimal digits
- **THEN** the app says the link is not a valid pairing link, and opens no connection

### Requirement: Scan the pairing QR code

The app SHALL offer to scan the pairing QR code with the phone's camera, decode it on the phone without any
network service, and treat the decoded text as a pasted link. It SHALL ask for the camera permission only when
the user starts scanning. When the permission is denied, pasting the link SHALL remain available.

The scanner SHALL read the desktop's pairing code when it fills about a fifth of the preview's width or more,
held at an angle, upside down or sideways, and when drawn light on dark. The preview SHALL focus where the user
taps and zoom when the user pinches. A QR code whose text is not a pairing link (`spekterm://pair`) SHALL NOT end
scanning: the scanner SHALL say that the code it sees is not a spekterm pairing code and keep looking. A decoded
pairing link that is refused (for example `v=2`, or a malformed fingerprint) SHALL end scanning with the refusal,
as for a pasted link.

#### Scenario: Scanning the desktop's QR code pairs

- **WHEN** the user scans the QR code shown by the desktop's pairing view
- **THEN** the app reads the link and starts pairing, as for a pasted link

#### Scenario: Scanning without lining it up

- **WHEN** the user points the phone at the desktop's pairing view tilted and off center, the code filling about a
  fifth of the preview's width (closer, or zoomed in by pinching)
- **THEN** the app reads the link and starts pairing

#### Scenario: Another QR code in view

- **WHEN** the scanner reads a QR code holding a web address
- **THEN** it says the code is not a spekterm pairing code, keeps scanning, and reads the pairing code once it is
  in view

#### Scenario: Camera permission denied

- **WHEN** the user denies the camera permission
- **THEN** the app says scanning needs the camera and offers to paste the link instead

### Requirement: Pair with the desktop

The app SHALL try the link's addresses over the pinned connection (`desktop-connection`) the way it tries a
pairing's addresses — starting them in the link's order, staggered, while earlier attempts continue — and use the
first one that completes the TLS handshake with the pinned certificate; every other attempt SHALL be closed
before any frame is sent on it. It SHALL send as the first frame of the chosen connection a `pair` request
carrying `protocol: [1]`, the code, a device name and the device model, and SHALL send the code on no other
connection. On success it SHALL store the pairing and start holding the connection (`desktop-connection`), so
that exactly one authenticated connection of the phone remains. On failure it SHALL tell the user why:
`PAIRING_FAILED` (the code is used, expired, or its view was closed — scan a new one); `THROTTLED`, which arrives
as an error frame with `id: null` followed by close code 4030, or close code 4030 alone (wait a minute);
`UNSUPPORTED_VERSION` (the app or the desktop needs updating); and no address reachable (the phone cannot reach
the desktop on any of its addresses), listing each address with its outcome and saying why in the terms of
`desktop-connection`'s "Say why the desktop cannot be reached".

#### Scenario: Pairing succeeds

- **WHEN** the user scans a fresh code while the desktop's pairing view is open
- **THEN** the desktop lists the phone by its device name, and the app shows the desktop as connected

#### Scenario: A used code is refused

- **WHEN** the user pairs with a code that was already used
- **THEN** the app says the code is no longer valid and asks for a new one, and stores nothing

#### Scenario: No address is reachable

- **WHEN** none of the link's addresses accepts a connection
- **THEN** the app says it could not reach the desktop and why, lists each address it tried with its outcome
  (for example timed out, or refused), and stores nothing

#### Scenario: A later address answers first

- **WHEN** the link's first address does not answer and its second completes the pinned handshake
- **THEN** the app pairs over the second address within about a second of it answering, without waiting for the
  first address's timeout, and the code is sent once

### Requirement: Keep the credential in protected storage

The device secret the desktop issues SHALL be stored only encrypted with a key held in Android Keystore that
cannot be exported. It SHALL NOT appear in plain text in the app's files, preferences, logs, connection log,
notifications, or screens. The pairing SHALL be excluded from backup and device transfer, so a restored or
copied app holds no credential.

#### Scenario: The secret is not on disk in plain text

- **WHEN** the app has paired, and the app's private files are read on a debug build
- **THEN** no file contains the device secret in plain text

#### Scenario: The secret survives a restart of the app

- **WHEN** the app's process is killed after pairing and started again
- **THEN** it authenticates with the stored credential without pairing again

### Requirement: One paired desktop

The app SHALL hold at most one pairing. Pairing again SHALL replace the previous pairing, including its
credential and its attention position.

#### Scenario: Pairing a second time replaces the first

- **WHEN** the user pairs with a new code while already paired
- **THEN** the app holds only the new pairing, and connects only to it

#### Scenario: A failed second pairing keeps the first

- **WHEN** the user tries to pair with a used code while already paired and connected
- **THEN** the app says the code is no longer valid, and keeps the existing pairing and its connection

### Requirement: End the pairing

The app SHALL let the user unpair, which deletes the credential and the attention position and stops the held
connection. Before anything is deleted, the app SHALL ask the user to confirm, saying that the phone will have to
be paired again from the desktop; cancelling SHALL leave the pairing and the connection as they were. The app SHALL
also delete the credential and the attention position and stop connecting when the desktop closes the connection
with code 4004 (revoked or reset), or answers `hello` with `AUTH_FAILED`, and SHALL then tell the user, with a
notification and on the status screen, that the phone must be paired again.

#### Scenario: Revoked on the desktop

- **WHEN** the user revokes the phone on the desktop while it is connected
- **THEN** the app deletes the credential and the attention position, stops connecting, and shows a
  notification asking to pair again

#### Scenario: Revoked while the phone was away

- **WHEN** the user revokes the phone on the desktop while the phone is not connected, and the phone then
  connects and `hello` is answered `AUTH_FAILED`
- **THEN** the app deletes the credential, stops connecting, and asks to pair again

#### Scenario: Unpair from the phone

- **WHEN** the user unpairs in the app and confirms
- **THEN** the credential is deleted, the connection and its foreground service stop, and the app shows the
  pairing screen

#### Scenario: Changing one's mind about unpairing

- **WHEN** the user chooses to unpair and then cancels
- **THEN** the pairing and the connection stay as they were

### Requirement: Open a pairing link from another app

The app SHALL open `spekterm://pair?…` links handed to it by another app — the phone's camera, a QR scanner, a
browser or a messaging app — and read them as a pasted link. A link opened this way SHALL NOT start pairing by
itself: the app SHALL show the name the link gives (`n`), the link's addresses and port, and pair only when the
user confirms. When the phone is already paired, the question SHALL appear over the screen the user is on, and
SHALL say whether the link is for the paired desktop (its fingerprint equals the pairing's: a new code for the same
desktop) or for a different desktop (pairing replaces the current one), whatever name the link gives. Cancelling
SHALL leave everything as it was, including the screen the user was on. A refused link SHALL be refused as a pasted
one is, opening no connection. A link that arrives while a pairing is in progress SHALL be dropped, saying so; one
that arrives while scanning SHALL stop scanning and be asked about.

#### Scenario: Scanned with the phone's camera

- **WHEN** the user scans the desktop's pairing QR code with the phone's own camera app and opens the link it
  offers
- **THEN** the app opens, shows the desktop's name and addresses with a choice to pair or cancel, and pairing
  starts only when the user chooses to pair

#### Scenario: A link for another desktop while paired

- **WHEN** the phone is paired and connected, a conversation is on screen, and a pairing link whose fingerprint
  differs from the pairing's is opened from a web page, giving the paired desktop's name
- **THEN** the app asks over the conversation, saying the link is for a different desktop and that pairing would
  replace the current one; cancelling returns to the conversation as it was, keeps the pairing and its
  connection, and nothing is sent to the link's addresses

#### Scenario: A new code for the same desktop

- **WHEN** the phone is paired and a pairing link with the pairing's fingerprint is opened
- **THEN** the app asks whether to pair again with the same desktop

#### Scenario: A malformed link from another app

- **WHEN** another app opens a pairing link whose `fp` has 63 hexadecimal digits
- **THEN** the app says the link is not a valid pairing link, and opens no connection
