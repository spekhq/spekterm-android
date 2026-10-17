## MODIFIED Requirements

### Requirement: Show the desktop's sessions

After each successful authentication, the app SHALL ask the desktop for its session list and SHALL replace the
shown list with every complete list the desktop sends afterwards, so that the list follows the desktop's
without the user refreshing. When answers and list events arrive close together, the list SHALL end at the one
the desktop sent last. Sessions SHALL be grouped by folder in the desktop's folder order, with the sessions
that belong to no folder in their own group labeled as global. Within a group, sessions SHALL be shown in the
desktop's order, arranged by handoff as `session-relations` describes. Each group SHALL begin with a heading set
apart from the session rows, showing the folder's name, its branch when the desktop reports one, and the number of
sessions in the group. While the sessions of a group are scrolled through, that group's heading SHALL stay visible
at the top of the list. Each session SHALL show its label, whether it is an agent or a shell, and whether it is
running or dormant. A running agent session SHALL show its wait state — waiting for input, working, or asking for a
choice — and SHALL show no wait state when the desktop reports it as unknown. A running agent session waiting for
input that the desktop marks as seen by the user since its turn ended SHALL show as idle instead of waiting for
input; when the desktop marks it as not seen, or sends no such mark (an older desktop), it SHALL show waiting for
input. A session whose agent offered to
close it (`close-offer`) SHALL show the offer in place of waiting for input or idle, and keep showing working or
asking for a choice with the offer. A session created by a handoff SHALL also show its handoff state and, when it is not shown
under its source, where it came from; a session that handed off others SHALL say how many and how many are done
(`session-relations`).

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

#### Scenario: An agent the user already looked at

- **WHEN** an agent finishes its turn and the user then looks at that session on the desktop
- **THEN** its row on the phone changes from waiting for input to idle, without the user doing anything

#### Scenario: Read on the phone

- **WHEN** the user opens a waiting agent's conversation on the phone and goes back to the list
- **THEN** its row shows idle

#### Scenario: The next turn ends

- **WHEN** an idle agent is given a reply, works, and finishes its turn while nobody looks at it
- **THEN** its row shows waiting for input again

#### Scenario: An older desktop

- **WHEN** the desktop marks no session as seen or not seen
- **THEN** every running agent waiting for input shows waiting for input, as before

#### Scenario: A desktop without handoffs

- **WHEN** no session in the desktop's list was created by a handoff
- **THEN** every session is shown at the top level of its group, in the desktop's order, as before

### Requirement: Collapse folder groups

The user SHALL be able to collapse and expand each group, the global one included, by tapping its heading, and
to collapse or expand every group at once from the session list. A collapsed group SHALL show only its
heading. A collapsed heading SHALL say how many of its sessions ask for a choice, how many ask to close
(`close-offer`) and how many wait for input, when any do, so that collapsing hides no session that needs the user;
each session SHALL be counted once: as asking for a choice when it does, otherwise as asking to close when its agent
offered to close it, otherwise as waiting for input when it does and the desktop does not mark it as seen (an idle session is not
counted). Groups SHALL
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

#### Scenario: A seen agent in a collapsed folder

- **WHEN** a collapsed group holds one agent waiting for input that the user has not seen and one the user has
- **THEN** the collapsed heading says that one session waits for the user

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

Long-pressing a session in the list SHALL open a menu of what the phone can do with that session, for every listed
session — agent or shell, running or dormant — whenever there is at least one item; when there is none, a long
press SHALL do nothing. The menu SHALL hold, each only when it applies: "Handoff", for a session with a handoff
relation (`session-relations`), whatever the desktop accepts; and, each only when the desktop accepts it: renaming
(`session-rename`), hibernating when the session is running, waking when it is dormant, and closing
(`session-hibernate-close`). The same items SHALL be offered on the session's own screen. Tapping a session SHALL
still open it (`Opening a session`); a long press SHALL NOT open it.

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

- **WHEN** the user long-presses a session with no handoff relation in the list of a desktop that accepts none of
  renaming, hibernating and closing
- **THEN** no menu opens

#### Scenario: A handed-off session on a desktop that offers no session action

- **WHEN** the user long-presses a session created by a handoff in the list of a desktop that accepts none of
  renaming, hibernating and closing
- **THEN** a menu opens offering only "Handoff"
