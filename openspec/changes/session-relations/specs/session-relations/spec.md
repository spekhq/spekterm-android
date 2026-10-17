## Purpose

Shows on the phone how the desktop's sessions relate by handoff — which session handed off which, what each was
asked to do and what it reported — the way the desktop's rail and handoff note show it.

## ADDED Requirements

### Requirement: A session exists for relations only while the phone can see it

For every relation the phone shows, a session SHALL count as existing when it is in the session list the phone
holds — the last one received, whether current or not — and belongs to the global item or to a folder that list
names. A session of a folder the list does not name, and a
session missing from the list, SHALL NOT count as existing. A dormant session SHALL count as existing. A session
whose handoff relation the desktop sends malformed SHALL be shown as a session without one, never left out.

#### Scenario: The source was closed

- **WHEN** a session was handed off from a session that has since been closed on the desktop
- **THEN** the phone treats its source as gone

#### Scenario: The source is hibernated

- **WHEN** a session was handed off from a session that is now hibernated
- **THEN** the phone treats its source as existing

### Requirement: Nest handed-off sessions under their source in the list

Within each group of the session list, a session handed off from an existing session of the same group SHALL be
shown under that session, indented one level deeper than it, together with everything nested under it. Sessions
under the same source, and the group's top-level sessions, SHALL keep the desktop's order. Indentation SHALL stop
growing after three levels; deeper sessions SHALL be drawn at the third level. A session whose source does not
exist, is in another group, is itself, or lies on a loop of sources SHALL be shown at the top level of its group.
The group of sessions whose folder the list does not name SHALL show all its sessions at the top level. No
session SHALL be left out of the list because of its relations, and the groups, their order, their headings and
collapsing SHALL be unaffected.

#### Scenario: A handoff within one folder

- **WHEN** an agent in a folder hands off to a new session in the same folder
- **THEN** the new session is shown directly under the agent's session, indented

#### Scenario: A handoff to another folder

- **WHEN** an agent in one folder hands off to a session in another folder
- **THEN** the new session is shown at the top level of the other folder's group

#### Scenario: Two handoffs from one session

- **WHEN** one session handed off two sessions in its own folder
- **THEN** both are shown under it, in the desktop's order, at the same indentation

#### Scenario: A source closed while its handoffs remain

- **WHEN** the session that handed off others is closed on the desktop
- **THEN** the sessions it handed off move to the top level of their group, and none disappears

#### Scenario: A loop of sources

- **WHEN** the desktop's list names two sessions of one folder as each other's source
- **THEN** both are shown at the top level of their group

#### Scenario: A deep chain

- **WHEN** a folder holds a chain of five sessions, each handed off from the previous one
- **THEN** the second, third and fourth are indented one level more each, and the fifth is drawn at the same
  indentation as the fourth

### Requirement: Say where a handoff came from and how it stands

A session the desktop marks as created by a handoff SHALL say so on its row, also when it has no state to show.
When it is shown at the top level, its row SHALL name its source: the source's group and current label when the
source exists; otherwise that it came from a session that has closed, with the source's title when the desktop kept
one. When it is shown under its source, its row SHALL NOT repeat the source. The row SHALL show the handoff's state
— working, not done yet (the agent stopped before reporting done), or done — worded so that it never contradicts the
row's own wait state, and SHALL show none when the desktop reports none to say or a state the phone does not know.
The state SHALL follow the desktop's session list, also while the handoff screen shows it.

#### Scenario: From another folder

- **WHEN** a session in folder B was handed off from the session "planner" in folder A
- **THEN** its row says it came from A › planner, with its handoff state

#### Scenario: The source was renamed

- **WHEN** the source session is renamed on the desktop
- **THEN** the row of the session handed off from it names the new label

#### Scenario: From a closed session

- **WHEN** the source session was closed and the desktop kept its title "planner"
- **THEN** the row says it came from planner, closed

#### Scenario: Shown under its source

- **WHEN** a handoff is shown under its source in the same folder
- **THEN** its row says it is a handoff, with its state, and does not name the source

#### Scenario: Under its source with nothing to say

- **WHEN** a handoff shown under its source is hibernated before reporting done, so the desktop reports no handoff
  state
- **THEN** its row still says it is a handoff

#### Scenario: A handoff that stopped, seen

- **WHEN** a handed-off agent stops at its prompt without reporting done, and the user has looked at it, on a
  desktop that marks sessions as seen
- **THEN** its row shows idle and no handoff state, and nothing on the row says it waits for the user

#### Scenario: A finished handoff

- **WHEN** a handed-off session reports that its work is done
- **THEN** its row shows the handoff as done, without the user refreshing

### Requirement: Say what was handed off from a session

An existing session from which at least one existing session was handed off, in any folder, SHALL say on its row
how many, and how many of them are done: some done, or all done. A session that does not exist (one of a folder
the list does not name) SHALL NOT say so.

#### Scenario: Two handoffs, one done

- **WHEN** a session handed off two sessions that still exist, one in another folder, and one of them is done
- **THEN** its row says it handed off 2 sessions, 1 of 2 done

#### Scenario: A handoff closed

- **WHEN** one of the two sessions handed off from it is closed on the desktop
- **THEN** its row says it handed off 1 session

### Requirement: A handoff screen for every related session

A session that came from a handoff, or an existing session from which an existing session was handed off, SHALL
offer "Handoff" in its
menu, both in the list and on its own screen, whether or not the desktop accepts any session action and whether
or not the phone is connected. "Handoff" SHALL open a screen for that session showing, each when it applies:

- for a session that came from a handoff, wherever it sits in the list: where it came from — its source's folder
  and current label, or that the source has closed, with its title when kept — opening the source session when
  chosen if that session exists;
- for a session that came from a handoff, when the desktop offers handoff notes: the handoff note as the desktop
  stored it, asked from the desktop each time the screen opens, again when a new report arrives and again when the
  connection comes back without the note loaded; or that the desktop kept no note; or why it could not be read,
  with a way to try again;
- for a session that came from a handoff: the latest report and when it was made — the newer of what the note's
  answer and the desktop's handoff list say, also when the desktop kept no note — or that there is none yet;
- the existing sessions handed off from it, in the desktop's order, each with its label, its folder, its handoff
  state as the session list says it and its latest report summary, each opening that session when chosen.

The screen SHALL follow the desktop's session list while it is shown. It SHALL decide what to show in this order:
while the connection is paused or the phone is not connected, the relations from the last list that had a window
and why it is not current, saying that the note needs the connection; while the desktop has no open window, the relations from the
last list that had one and that the desktop has no window; when a current list with a window lacks the session,
that the session is no longer on the desktop; otherwise its content.

#### Scenario: Reading what a session was asked to do

- **WHEN** the user chooses "Handoff" in the menu of a session created by a handoff
- **THEN** a screen opens showing where it came from, the full handoff note and the latest report

#### Scenario: Following the relations

- **WHEN** on a session's handoff screen the user chooses one of the sessions handed off from it
- **THEN** that session opens, and going back returns to the handoff screen

#### Scenario: A handed-off session starts waiting

- **WHEN** on a session's handoff screen, one of the sessions handed off from it stops working without reporting
  done, while nobody looks at it
- **THEN** its state on the screen changes from working to not done yet, as its row does

#### Scenario: The desktop reloads its window

- **WHEN** the desktop's window is reloaded while a handoff screen is shown
- **THEN** the screen says the desktop has no open window and keeps showing the relations, and does not say the
  session is gone

#### Scenario: A report without a note

- **WHEN** the desktop kept no note for a handoff but its handoff list holds a report for it
- **THEN** the screen says the desktop kept no note and shows the report with its time

#### Scenario: Shown under its source

- **WHEN** the user opens "Handoff" for a session shown under its source in the same folder
- **THEN** the screen names the source and opens it when chosen

#### Scenario: A report arrives while the screen is open

- **WHEN** a handed-off session reports completion while its handoff screen is shown
- **THEN** the screen shows the new report without the user refreshing

#### Scenario: A desktop that kept no note

- **WHEN** the desktop answers that it has no note for the session
- **THEN** the screen says the desktop kept no note for this handoff, and still shows the rest

#### Scenario: A session that only handed off

- **WHEN** the user opens "Handoff" for a session that was not created by a handoff but handed off two sessions
- **THEN** the screen shows those two sessions with their states and reports, and no source, note or report of
  its own

#### Scenario: Not connected

- **WHEN** the user opens "Handoff" while the phone is not connected to the desktop
- **THEN** the screen shows the relations from the last list and says the note needs the connection, and loads
  the note by itself once the connection is back

### Requirement: Handoff text is shown as plain text and loads nothing

The handoff note, report summaries and session labels on the handoff screen and in the list SHALL be shown as plain
text, with their line breaks kept where they are shown in full. They SHALL NOT be interpreted as markup, SHALL NOT
make links, and SHALL NOT cause anything to be loaded from the network or opened.

#### Scenario: A note with markup and a link

- **WHEN** a handoff note contains Markdown headings, an image reference and a URL
- **THEN** the screen shows them as the literal characters, loads nothing, and tapping the URL opens nothing

### Requirement: Log handoff requests without their content

The connection log SHALL record each answer to asking for the handoff list and for a handoff note, with its error
code when refused. It SHALL NOT record a session label, a note, a report or a source title.

#### Scenario: Exporting the log after reading a note

- **WHEN** the user exports the connection log after opening a handoff screen
- **THEN** the log shows the note request and its outcome, and none of the note's text
