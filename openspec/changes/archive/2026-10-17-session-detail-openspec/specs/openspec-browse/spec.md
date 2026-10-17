## ADDED Requirements

### Requirement: Open a folder's OpenSpec from a session's screen

While the desktop accepts `openspec.changes`, a session's screen SHALL carry, in its top bar, a control that opens
the OpenSpec of the session's folder, when the desktop marks that folder as having OpenSpec. This is in addition
to the session list's ways in (`session-list`). The control SHALL be labeled for accessibility with the folder's
name, as the control in a folder group's heading is. It SHALL be offered for every such session, agent or shell,
running or dormant. It SHALL also be offered while the phone is not connected, as the session list's controls are,
and the OpenSpec screens then say what they can show. The folders it is judged against are those of the last
session list the phone holds. Going back from the folder's OpenSpec SHALL return to the session's screen.

It SHALL NOT be shown for a session of the global item, for a session whose folder is not among the folders of the
last session list the phone holds, for a session whose folder is not marked as having OpenSpec, or while the
desktop does not accept `openspec.changes`.

#### Scenario: Reading an agent working on a change

- **WHEN** the user is on the screen of a session in a folder with OpenSpec and taps its OpenSpec control
- **THEN** that folder's changes and specs open, and the phone stops watching the session's conversation while
  they are on screen

#### Scenario: Back to the session

- **WHEN** the agent writes two messages while the user is on the folder's OpenSpec, and the user then goes back
  to the session's screen
- **THEN** both messages are shown once each, no earlier entry is repeated, and the desktop stops sending the
  phone that folder's changes within a few seconds

#### Scenario: Not connected

- **WHEN** the phone is not connected and the user opens a session in a folder with OpenSpec
- **THEN** the session's screen still shows the OpenSpec control

#### Scenario: A folder without OpenSpec

- **WHEN** the user opens a session whose folder the desktop does not mark as having OpenSpec
- **THEN** its screen shows no OpenSpec control

#### Scenario: A global session

- **WHEN** the user opens a session of the global item
- **THEN** its screen shows no OpenSpec control

#### Scenario: A desktop without OpenSpec reads

- **WHEN** the paired desktop does not accept `openspec.changes`
- **THEN** no session's screen shows the OpenSpec control
