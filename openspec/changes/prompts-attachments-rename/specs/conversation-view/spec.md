## MODIFIED Requirements

### Requirement: A dormant session shows as dormant and can be woken

A session that is not running SHALL be shown as dormant, with a way to wake it, and SHALL NOT be shown as an
empty conversation. Waking SHALL start the session on the desktop without taking over the desktop's display;
once the desktop reports it running, its conversation SHALL be shown. When waking fails, the screen SHALL say
why: the desktop has no open window, the desktop could not start the session (with the desktop's reason), the
desktop did not answer in time, or the session no longer exists. A watched session that is put to sleep SHALL be
shown as dormant, and its conversation SHALL be watched again once it runs. A session that is closed, or whose
process ended on its own (the desktop then removes it), while its conversation is open SHALL be shown as no
longer on the desktop. When the desktop ends the watch saying why, the screen SHALL follow that reason at once:
the desktop's window went away; the session was hibernated, so it is dormant; or the session was closed or its
process ended, so it is no longer on the desktop. A reason the app does not know SHALL be taken as the session
no longer running.
Otherwise a closed session SHALL be judged only from the desktop's current list; while the desktop has no open
window it lists no sessions, and that SHALL NOT be taken for the session being closed.
Once the desktop has a window again, the screen SHALL show the session as its list does.
When several of these hold, the screen SHALL say first that the phone is not connected, then that the desktop
has no open window, then that the session was closed, then that it is dormant.

#### Scenario: Waking a dormant session

- **WHEN** the user opens a dormant session and chooses to wake it
- **THEN** the screen shows it is waking, and then shows the session's conversation once it is running

#### Scenario: A dormant session is not an empty conversation

- **WHEN** the user opens a dormant session
- **THEN** the screen says the session is dormant, and does not show an empty conversation

#### Scenario: No window

- **WHEN** the user wakes a session while the desktop has no open window
- **THEN** the screen says spekterm has no open window on the desktop

#### Scenario: The session is hibernated while being read

- **WHEN** the user hibernates a session on the desktop while its conversation is on the phone's screen, and
  then wakes it
- **THEN** the phone shows the session as dormant, and then shows its conversation again, new entries included

#### Scenario: Closed on the desktop

- **WHEN** the user closes a session on the desktop while its conversation is on the phone's screen
- **THEN** the phone says the session is no longer on the desktop, without first saying that the desktop has no
  open window

#### Scenario: The desktop's window closes

- **WHEN** the desktop's window closes while a conversation is on the phone's screen
- **THEN** the phone says spekterm has no open window on the desktop, not that the session was closed

#### Scenario: Hibernated without a "no window" moment

- **WHEN** a session is hibernated, from the phone or on the desktop, while its conversation is on the phone's
  screen
- **THEN** the screen shows the session as dormant with a way to wake it, and never says that the desktop has no
  open window
