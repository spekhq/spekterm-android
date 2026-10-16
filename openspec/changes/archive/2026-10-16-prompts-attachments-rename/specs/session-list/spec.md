## ADDED Requirements

### Requirement: A session's menu

Long-pressing a session in the list SHALL open a menu of the actions the phone can take on that session, for every
listed session — agent or shell, running or dormant — whenever the desktop offers at least one of them; when it
offers none, a long press SHALL do nothing. The menu SHALL hold, each only when the desktop accepts it: renaming
(`session-rename`), hibernating when the session is running, waking when it is dormant, and closing
(`session-hibernate-close`). The same
actions SHALL be offered on the session's own screen. Tapping a session SHALL still open it (`Opening a
session`); a long press SHALL NOT open it.

#### Scenario: Long-pressing a running session

- **WHEN** the user long-presses a running session in the list of a desktop that accepts renaming, hibernating
  and closing
- **THEN** a menu opens on that session offering to rename, hibernate and close it, and the session is not opened

#### Scenario: Long-pressing a dormant session

- **WHEN** the user long-presses a dormant session
- **THEN** its menu opens offering to rename, wake and close it, not to hibernate it, and the session stays
  dormant until the user chooses to wake it

#### Scenario: A desktop that offers renaming only

- **WHEN** the user long-presses a session in the list of a desktop that accepts renaming but not hibernating or
  closing
- **THEN** the menu offers only to rename it

#### Scenario: A desktop that offers no session action

- **WHEN** the user long-presses a session in the list of a desktop that accepts none of renaming, hibernating
  and closing
- **THEN** no menu opens
