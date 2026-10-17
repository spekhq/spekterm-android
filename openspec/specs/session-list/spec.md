# session-list Specification

## Purpose
Shows the paired desktop's sessions on the phone as the app's main screen, kept current from the desktop, so the
user can see which agents are waiting, working or asking for a choice, and open one.

## Requirements

### Requirement: The session list is the paired app's main screen

When the phone is paired, opening the app SHALL show the session list. The connection status screen of
`desktop-connection` SHALL remain available from the session list, through the list's menu, as the app's
settings: it SHALL be titled as settings and SHALL show the connection's state first. The session list's own
notices that point to the connection status SHALL keep leading there directly. When the phone is not paired,
the app SHALL show the pairing screen as before.

#### Scenario: Opening the paired app

- **WHEN** the user opens the app while paired
- **THEN** the session list is shown, with a way to the connection status screen

#### Scenario: The status screen is still reachable

- **WHEN** the user chooses settings from the session list's menu
- **THEN** the status screen of the connection spike is shown, titled as settings with the connection's state
  first, and going back returns to the session list

### Requirement: Show the desktop's sessions

After each successful authentication, the app SHALL ask the desktop for its session list and SHALL replace the
shown list with every complete list the desktop sends afterwards, so that the list follows the desktop's
without the user refreshing. When answers and list events arrive close together, the list SHALL end at the one
the desktop sent last. Sessions SHALL be grouped by folder in the desktop's folder order, with the sessions
that belong to no folder in their own group labeled as global, and SHALL keep the desktop's order within a
group. Each group SHALL begin with a heading set apart from the session rows, showing the folder's name, its
branch when the desktop reports one, and the number of sessions in the group. While the sessions of a group
are scrolled through, that group's heading SHALL stay visible at the top of the list. Each session SHALL show
its label, whether it is an agent or a shell, and whether it is running or dormant. A running agent session
SHALL show its wait state — waiting for input, working, or asking for a choice — and SHALL show no wait state
when the desktop reports it as unknown; a session whose agent offered to close it (`close-offer`) SHALL show the
offer in place of waiting for input, and keep showing working or asking for a choice with the offer. A session created by a handoff SHALL also show its handoff state.

#### Scenario: The list follows the desktop

- **WHEN** an agent on the desktop finishes its turn while the session list is on screen
- **THEN** that session's row changes to waiting for input, without the user doing anything

#### Scenario: A session is closed on the desktop

- **WHEN** the user closes a session on the desktop
- **THEN** it disappears from the phone's list

#### Scenario: A dormant session

- **WHEN** a session on the desktop is hibernated
- **THEN** its row shows it as dormant and shows no wait state

#### Scenario: Telling folders apart

- **WHEN** the desktop has sessions in two folders that are worktrees of one repository, on different branches
- **THEN** each group's heading shows the folder's name, its branch and its number of sessions, and is set
  apart from the rows

#### Scenario: Scrolling through a long group

- **WHEN** the user scrolls through a group with more sessions than fit on the screen
- **THEN** that group's heading stays at the top of the list until the next group's heading reaches it

#### Scenario: An agent that asks to close itself

- **WHEN** an agent on the desktop finishes a turn in which it asked to close its own session
- **THEN** its row shows the offer to close it instead of waiting for input

### Requirement: Say when the list is not current

The session list SHALL say, instead of presenting the list as current:

- that the phone is not connected to the desktop, while it is not — keeping the last list received, marked
  with when it was received, or saying that no list has been received yet — together with the reason the
  connection status knows (connecting or waiting to retry, remote access off or the desktop quit, the
  desktop's identity changed, a version the other side does not support, the connection stopped) and a way to
  the connection status screen;
- that the user paused the connection, with a way to resume it;
- that the desktop has no open window and so lists no sessions, when the desktop reports none.

#### Scenario: Disconnected

- **WHEN** the phone loses its connection while the session list is on screen
- **THEN** the screen says the phone is not connected and that the list is the one received at that time

#### Scenario: Remote access turned off

- **WHEN** the user turns remote access off on the desktop while the session list is on screen
- **THEN** the screen says remote access is off or the desktop quit, and offers the connection status

#### Scenario: Reconnected

- **WHEN** the phone reconnects after being disconnected while the desktop's sessions changed
- **THEN** the list shows the desktop's current sessions and no longer says it is not current

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

### Requirement: Collapse folder groups

The user SHALL be able to collapse and expand each group, the global one included, by tapping its heading, and
to collapse or expand every group at once from the session list. A collapsed group SHALL show only its
heading. A collapsed heading SHALL say how many of its sessions ask for a choice, how many ask to close
(`close-offer`) and how many wait for input, when any do, so that collapsing hides no session that needs the user;
each session SHALL be counted once: as asking for a choice when it does, otherwise as asking to close when its agent
offered to close it, otherwise as waiting for input when it does. Groups SHALL
be expanded until the user collapses them. Which groups are collapsed SHALL be kept on the phone across restarts of
the app and the phone, for the current pairing only: pairing again or unpairing SHALL forget it. A folder that is
collapsed, disappears from the desktop and comes back with the same identity SHALL still be collapsed.

#### Scenario: Collapsing a folder

- **WHEN** the user taps the heading of an expanded group
- **THEN** the group's sessions are hidden and its heading remains, and tapping it again shows them

#### Scenario: Attention in a collapsed folder

- **WHEN** an agent in a collapsed group starts asking for a choice
- **THEN** the collapsed heading says that one session asks for a choice, without the user expanding it

#### Scenario: An offer in a collapsed folder

- **WHEN** an agent in a collapsed group asks to close its own session
- **THEN** the collapsed heading says that one session asks to close, and does not count it as waiting

#### Scenario: An offer that also asks for a choice, collapsed

- **WHEN** a session in a collapsed group was offered for closing and its agent waits on a choice
- **THEN** the collapsed heading counts it once, as asking for a choice

#### Scenario: Collapsed across a restart

- **WHEN** the user collapses a group, and the app is closed and opened again
- **THEN** the group is still collapsed

#### Scenario: Collapsing everything

- **WHEN** the user chooses to collapse all groups
- **THEN** every group shows only its heading, and choosing to expand all shows every session again

#### Scenario: A new folder

- **WHEN** a folder that the user never collapsed gets its first session on the desktop
- **THEN** its group appears expanded

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

### Requirement: Open a folder's OpenSpec from the session list

While the desktop accepts `openspec.changes`, the session list SHALL offer a way to each folder's OpenSpec
(`openspec-browse`):

- The heading of a folder group whose folder the desktop marks as having OpenSpec SHALL carry a control that opens
  that folder's OpenSpec. It SHALL be labeled for accessibility with the folder's name. Tapping it SHALL NOT
  collapse or expand the group, which stays the heading's own tap.
- The list's menu SHALL offer "OpenSpec" while at least one folder of the desktop has OpenSpec. It SHALL list every
  such folder in the desktop's order, including folders with no session, and open the chosen folder's OpenSpec.

Neither SHALL be shown for the global group, for folders the desktop does not mark as having OpenSpec, or while the
desktop does not accept `openspec.changes`.

#### Scenario: From a folder's heading

- **WHEN** a folder with OpenSpec has sessions on the desktop and the user taps the OpenSpec control in its heading
- **THEN** that folder's OpenSpec opens, and the group's collapsed or expanded state does not change

#### Scenario: A folder without sessions

- **WHEN** a folder with OpenSpec has no session and the user chooses "OpenSpec" from the list's menu
- **THEN** the folder is listed there and opens its OpenSpec

#### Scenario: A desktop without OpenSpec reads

- **WHEN** the paired desktop does not accept `openspec.changes`
- **THEN** no heading shows the OpenSpec control and the menu has no "OpenSpec"
