## MODIFIED Requirements

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
when the desktop reports it as unknown. A session created by a handoff SHALL also show its handoff state.

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

## ADDED Requirements

### Requirement: Collapse folder groups

The user SHALL be able to collapse and expand each group, the global one included, by tapping its heading, and
to collapse or expand every group at once from the session list. A collapsed group SHALL show only its
heading. A collapsed heading SHALL say how many of its sessions ask for a choice and how many wait for input,
when any do, so that collapsing hides no session that needs the user. Groups SHALL be expanded until the user
collapses them. Which groups are collapsed SHALL be kept on the phone across restarts of the app and the
phone, for the current pairing only: pairing again or unpairing SHALL forget it. A folder that is collapsed,
disappears from the desktop and comes back with the same identity SHALL still be collapsed.

#### Scenario: Collapsing a folder

- **WHEN** the user taps the heading of an expanded group
- **THEN** the group's sessions are hidden and its heading remains, and tapping it again shows them

#### Scenario: Attention in a collapsed folder

- **WHEN** an agent in a collapsed group starts asking for a choice
- **THEN** the collapsed heading says that one session asks for a choice, without the user expanding it

#### Scenario: Collapsed across a restart

- **WHEN** the user collapses a group, and the app is closed and opened again
- **THEN** the group is still collapsed

#### Scenario: Collapsing everything

- **WHEN** the user chooses to collapse all groups
- **THEN** every group shows only its heading, and choosing to expand all shows every session again

#### Scenario: A new folder

- **WHEN** a folder that the user never collapsed gets its first session on the desktop
- **THEN** its group appears expanded
