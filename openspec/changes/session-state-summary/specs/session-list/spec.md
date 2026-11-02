## ADDED Requirements

### Requirement: Summary of the sessions by state

While the session list shows at least one session, it SHALL show above the sessions, staying in place while they scroll,
a summary that counts every listed session — every group, collapsed or not — by state. Each session SHALL be counted
once, in the first of these states that applies:

1. **needs you**: a running agent that asks for a choice, whose agent offered to close it (`close-offer`), or that
   waits for input and is not marked as seen by the desktop — the sessions the collapsed headings count;
2. **working**: a running agent that works;
3. **running**: a running shell, and a running agent whose wait state is unknown;
4. **idle**: a running agent waiting for input that the desktop marks as seen;
5. **dormant**: a session that is not running.

The states SHALL be listed in that order, each only when it has at least one session, each with the dot its rows show
and its number, and each SHALL be labeled for accessibility with its state and its number. Every state SHALL be
shown with its word (`app-appearance`, "State is not told by color alone"); when they do not fit on one line, the
summary SHALL continue on the next rather than hide any. The summary SHALL be shown also while the list is not current,
counting the list it shows.

Tapping a state SHALL filter the list to the sessions in that state, mark that state as selected and show the list
from its top; tapping it again SHALL clear the filter and tapping another state SHALL filter by that one. While the
list is filtered:

- every group with a session in that state SHALL be shown with only those sessions, whether or not the group is
  collapsed, and groups with none SHALL be left out;
- within a group the sessions SHALL be in the order the whole list shows them, all at the top level, each created by
  a handoff saying where it came from (`session-relations`);
- the list SHALL offer to show all sessions.

When no listed session is in the selected state any more, the filter SHALL stay, its state SHALL stay in the summary,
in its place, with the number 0, and the list SHALL say that no session is in that state, with the offer to show all.
The filter SHALL stay while the user opens a session from the filtered list and comes back to it. The filter SHALL be
cleared when the user starts a new session from the session list, and when the list has no session to show (the
desktop lists none, or has no window).

#### Scenario: Counting the sessions

- **WHEN** the desktop lists one agent asking for a choice, one waiting for input not seen, one waiting for input seen,
  one working, one shell and one hibernated session
- **THEN** the summary shows needs you 2, working 1, running 1, idle 1 and dormant 1, in that order

#### Scenario: A working agent that offered to close

- **WHEN** an agent that offered to close its session is working again
- **THEN** it is counted under needs you and not under working, and filtering by working does not show it

#### Scenario: A collapsed folder is counted

- **WHEN** a collapsed group holds an agent waiting for input that the user has not seen
- **THEN** the summary counts it under needs you

#### Scenario: Finding what needs the user

- **WHEN** the user taps needs you while three sessions in two folders need them, one of them in a collapsed folder
- **THEN** the list shows only those three sessions under their two folders' headings, and tapping needs you again
  shows every session with the collapsed folder still collapsed

#### Scenario: Answering in a row

- **WHEN** the user filters by needs you, opens a session, answers it and goes back
- **THEN** the list is still filtered by needs you, and the answered session is no longer in it once the desktop
  reports it working

#### Scenario: Only reading one

- **WHEN** the user filters by needs you, opens a session waiting for input, reads it without answering and goes back
- **THEN** the session shows as idle and is no longer in the filtered list, as reading it on the phone marks it seen

#### Scenario: Nothing left

- **WHEN** the last session needing the user starts working while the list is filtered by needs you
- **THEN** needs you shows 0 and stays selected, and the list says no session needs the user and offers to show all

#### Scenario: Starting a session while filtered

- **WHEN** the user starts a new session while the list is filtered
- **THEN** the filter is cleared, so the new session is in the list when the user comes back to it

#### Scenario: A handed-off session in the filter

- **WHEN** a session created by a handoff waits for input under a source that is working, and the user filters by
  needs you
- **THEN** the session is shown on its own, saying where it came from

#### Scenario: Only one state

- **WHEN** every listed session is working
- **THEN** the summary shows only working, with the number of sessions

## MODIFIED Requirements

### Requirement: Show the desktop's sessions

After each successful authentication, the app SHALL ask the desktop for its session list and SHALL replace the
shown list with every complete list the desktop sends afterwards, so that the list follows the desktop's
without the user refreshing. When answers and list events arrive close together, the list SHALL end at the one
the desktop sent last. Sessions SHALL be grouped by folder in the desktop's folder order, with the sessions
that belong to no folder in their own group labeled as global. Within a group, sessions SHALL be shown in the
desktop's order, arranged by handoff as `session-relations` describes, except while the list is filtered by state
(`Summary of the sessions by state`). Each group SHALL begin with a heading set
apart from the session rows, showing the folder's name, its branch when the desktop reports one, and the number of
sessions in the group — while the list is filtered by state, the number of the group's sessions it shows. While the sessions of a group are scrolled through, that group's heading SHALL stay visible
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

#### Scenario: A heading in a filtered list

- **WHEN** a group holds four sessions, one of them working, and the list is filtered by working
- **THEN** the group's heading shows 1

### Requirement: Collapse folder groups

The user SHALL be able to collapse and expand each group, the global one included, by tapping its heading, and
to collapse or expand every group at once from the session list, except while the list is filtered by state
(`Summary of the sessions by state`), when neither is offered. A collapsed group SHALL show only its
heading, except while the list is filtered by state, which shows a collapsed group's sessions in that state without
changing whether it is collapsed. Outside a filter by state, a collapsed heading SHALL say how many of its sessions ask for a choice, how many ask to close
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

#### Scenario: A collapsed folder in a filtered list

- **WHEN** the list is filtered by working and a collapsed group holds a working session
- **THEN** the group's heading and that session are shown, tapping the heading does not hide it, and once the filter
  is cleared the group is collapsed

### Requirement: Open a folder's OpenSpec from the session list

While the desktop accepts `openspec.changes`, the session list SHALL offer a way to each folder's OpenSpec
(`openspec-browse`):

- The heading of a folder group whose folder the desktop marks as having OpenSpec SHALL carry a control that opens
  that folder's OpenSpec. It SHALL be labeled for accessibility with the folder's name. Tapping it SHALL NOT
  collapse or expand the group, which stays the heading's own tap except while the list is filtered by state
  (`Summary of the sessions by state`).
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
