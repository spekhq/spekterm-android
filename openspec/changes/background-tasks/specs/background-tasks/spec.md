## Purpose

Shows on the phone the background work a running agent session still has — background subagents, shells and
monitors — as of its agent's last report, so that a session whose turn ended is not mistaken for one doing nothing.

## ADDED Requirements

### Requirement: Read a session's background work from the desktop's list

The app SHALL take a session's background work from the session list the desktop sends, and from nothing else: each
complete list replaces what is shown, and the app SHALL keep no background work of its own. It SHALL show background
work only for a running agent session, whatever the desktop sends for a dormant session or a shell. Each task SHALL
have a kind — an agent, a shell, a monitor, or other — and a label or none. A task of a kind the app does not know,
or with no kind, SHALL be shown as other. A malformed task SHALL be left out without affecting the session or the
other tasks, and a malformed background field SHALL read as no background work, never dropping the session. A session
the desktop sends without background work — including every session of a desktop older than the feature — SHALL
show none.

#### Scenario: Work that ends

- **WHEN** a session's row shows a background shell and the desktop then sends a list where that session has none
- **THEN** the row shows no background work, without the user doing anything

#### Scenario: An older desktop

- **WHEN** the desktop sends no background work for any session
- **THEN** the session list and the conversation screens look as they did before the feature

#### Scenario: A kind the app does not know

- **WHEN** a session's background work holds a task whose kind is "workflow"
- **THEN** it is shown as other, with its label

#### Scenario: A malformed entry

- **WHEN** a session's background work holds one task with no id and one valid shell
- **THEN** the session is listed, with one shell

### Requirement: Show background work on the session list

A running agent session's row SHALL show, when it has background work, how much it has by kind, in the order
subagent (the protocol's agent kind), shell, monitor, other, with the language's plural forms. The row SHALL keep showing its wait state, its close offer and
its handoff lines as before; background work SHALL NOT change the wait state shown, the counts of a collapsed group's
heading, or any notification.

#### Scenario: An idle agent with a running shell

- **WHEN** an agent finished its turn, the user already looked at it, and it left a dev server running in the
  background
- **THEN** its row shows "Idle" and says one shell runs in the background

#### Scenario: Several kinds

- **WHEN** a working agent has one background subagent and two background shells
- **THEN** its row shows "Working" and says one subagent and two shells run in the background, the subagent first

### Requirement: Show background work on the conversation screen

A running agent session's conversation screen SHALL show, when the session has background work, a collapsed item
saying how many tasks run in the background; expanding it SHALL list each task in the order the desktop sent, with its
kind and its label, or its kind alone when it has no label, and SHALL say that the list is what the agent last
reported. However many tasks there are, the expanded item SHALL NOT take the place of the conversation, the prompt
card, the close offer or the reply box: past a bounded height it SHALL scroll. It SHALL be shown from the last list received also while the connection is paused or down, and SHALL
follow every new list while the screen is open.

#### Scenario: Looking at the work

- **WHEN** the user opens a session that runs a background subagent and a background shell, and expands the item
- **THEN** both are listed in the desktop's order, each with its kind and label, with the note that this is the
  agent's last report

#### Scenario: A task without a label

- **WHEN** a session's background work holds a monitor with no label
- **THEN** the expanded item lists it by its kind alone

#### Scenario: Many tasks

- **WHEN** a session runs ten background tasks, the user expands the item and opens the keyboard to reply
- **THEN** the conversation, the reply box and any prompt card stay on screen, and the tasks scroll within the item

#### Scenario: While paused

- **WHEN** the user paused the connection and opens a session that had background work in the last list
- **THEN** the screen still shows that work

### Requirement: Show labels as written, as plain text

A task's label SHALL be shown as the desktop sent it, untranslated, as plain text: the app SHALL NOT make a link of a
URL or a path in it, SHALL NOT render it as markdown, and SHALL NOT record it in the connection log.

#### Scenario: A label with a URL

- **WHEN** a monitor's label is "live updates for artifact https://claude.ai/artifact/abc"
- **THEN** the text is shown as written and tapping the URL does nothing
