# session-rename Specification

## Purpose
Lets the user rename a session of the paired desktop from the phone, without changing what the desktop shows or
where its keyboard focus is.

## Requirements

### Requirement: Rename a session from the list or its screen

While the desktop accepts `sessions.rename`, every session the desktop lists — an agent or a shell, running or
dormant — SHALL offer to rename it, in its menu in the session list (`session-list`) and on its screen. Renaming SHALL take a single line of at most 200 characters,
starting from the session's current name, and SHALL be possible only while the phone is connected, the
connection is not paused, the shown list is current and the desktop has an open window. Choosing to clear the
name SHALL only empty the field, so a new name can be typed; an empty name SHALL NOT be sent, and the phone SHALL
NOT offer to hand naming back to the session's own title. The app
SHALL NOT change the session's label itself: the new label SHALL appear when the desktop's list shows it.
A name that would not change the label SHALL NOT be sent. The desktop renames without changing its selection or
keyboard focus.

#### Scenario: Renaming

- **WHEN** the user long-presses an agent session in the list and renames it "Fix the login"
- **THEN** the session's label becomes "Fix the login" on the phone, on the desktop's tab and in its rail, and
  the desktop's selection and keyboard focus do not change

#### Scenario: Clearing the name

- **WHEN** the user clears the name in the rename dialog
- **THEN** the field is empty, the dialog stays open, and Rename is unavailable until a name is typed

#### Scenario: A dormant session

- **WHEN** the user long-presses a dormant session in the list and renames it
- **THEN** its label changes and it stays dormant

#### Scenario: From the session's screen

- **WHEN** the user renames a session from its conversation screen
- **THEN** its label changes as when renamed from the list

### Requirement: Say why a session could not be renamed

When the desktop refuses or does not answer a rename, the app SHALL say why and keep the name typed: the session
no longer exists; the desktop has no open window; the desktop did not answer in time, so the name may still
change; the phone is not connected; or another error, by its code.

#### Scenario: The session was closed meanwhile

- **WHEN** the session is closed on the desktop while the user types a new name, and the user confirms it
- **THEN** the screen says the session no longer exists
