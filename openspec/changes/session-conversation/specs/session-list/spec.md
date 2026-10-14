## Purpose

Shows the paired desktop's sessions on the phone as the app's main screen, kept current from the desktop, so the
user can see which agents are waiting, working or asking for a choice, and open one.

## ADDED Requirements

### Requirement: The session list is the paired app's main screen

When the phone is paired, opening the app SHALL show the session list. The connection status screen of
`desktop-connection` SHALL remain available from the session list. When the phone is not paired, the app SHALL
show the pairing screen as before.

#### Scenario: Opening the paired app

- **WHEN** the user opens the app while paired
- **THEN** the session list is shown, with a way to the connection status screen

#### Scenario: The status screen is still reachable

- **WHEN** the user chooses the connection status from the session list
- **THEN** the status screen of the connection spike is shown, and going back returns to the session list

### Requirement: Show the desktop's sessions

After each successful authentication, the app SHALL ask the desktop for its session list and SHALL replace the
shown list with every complete list the desktop sends afterwards, so that the list follows the desktop's
without the user refreshing. When answers and list events arrive close together, the list SHALL end at the one
the desktop sent last. Sessions SHALL be grouped by folder in the desktop's folder order, with the sessions
that belong to no folder in their own group labeled as global, and SHALL keep the desktop's order within a
group. Each session SHALL show its label, whether it is an agent or a shell, and whether it is running or
dormant. A running agent session SHALL show its wait state — waiting for input, working, or asking for a
choice — and SHALL show no wait state when the desktop reports it as unknown. A session created by a handoff
SHALL also show its handoff state.

#### Scenario: The list follows the desktop

- **WHEN** an agent on the desktop finishes its turn while the session list is on screen
- **THEN** that session's row changes to waiting for input, without the user doing anything

#### Scenario: A session is closed on the desktop

- **WHEN** the user closes a session on the desktop
- **THEN** it disappears from the phone's list

#### Scenario: A dormant session

- **WHEN** a session on the desktop is hibernated
- **THEN** its row shows it as dormant and shows no wait state

### Requirement: Say when the list is not current

The session list SHALL say, instead of presenting the list as current:

- that the phone is not connected to the desktop, while it is not — keeping the last list received, marked
  with when it was received, or saying that no list has been received yet;
- that the user paused the connection, with a way to resume it;
- that the desktop has no open window and so lists no sessions, when the desktop reports none.

#### Scenario: Disconnected

- **WHEN** the phone loses its connection while the session list is on screen
- **THEN** the screen says the phone is not connected and that the list is the one received at that time

#### Scenario: Paused

- **WHEN** the user has paused the connection and opens the app
- **THEN** the session list says the connection is paused and offers to resume it, and resuming reconnects

#### Scenario: The desktop has no window

- **WHEN** the desktop reports that it has no window
- **THEN** the session list says that spekterm has no open window on the desktop, rather than showing an empty
  list as if there were no sessions

### Requirement: Opening a session

Choosing an agent session SHALL open its conversation (`conversation-view`). Choosing a shell session SHALL say
that shell sessions need the terminal view, which the app does not have yet, and SHALL NOT start watching
anything.

#### Scenario: Opening a shell

- **WHEN** the user chooses a shell session in the list
- **THEN** the app says shell sessions need the terminal view, not available in the app yet
