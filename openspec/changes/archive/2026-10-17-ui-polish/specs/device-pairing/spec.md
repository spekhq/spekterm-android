## MODIFIED Requirements

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
