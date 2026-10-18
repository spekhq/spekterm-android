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
(`session-relations`). A running agent session with background work SHALL
also say how much it has, by kind (`background-tasks`).

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

#### Scenario: Work left running after a turn

- **WHEN** an agent finishes its turn and leaves a background shell running
- **THEN** its row shows its wait state and also says one shell runs in the background
