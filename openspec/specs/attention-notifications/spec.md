# attention-notifications Specification

## Purpose
Turns the desktop's attention stream — an agent waiting, an agent asking for a choice, a finished handoff, a new
inbox item — into system notifications on the phone, without losing or repeating events across reconnections.

## Requirements

### Requirement: Watch the attention stream from the last position

After each successful authentication, the app SHALL call `attention.watch`, passing as `after` the position
(`epoch` and `seq`) of the last attention event it handled, or no position right after pairing. It SHALL store
the position of each event once the event is handled, so the position survives the app's process being killed
and the phone restarting.

#### Scenario: Events raised while disconnected are delivered

- **WHEN** the app has already handled at least one attention event since the desktop last started remote
  access, the phone loses its connection, an agent on the desktop raises two attention events, and the phone
  reconnects
- **THEN** the app receives exactly those two events, in order, with `complete: true`, and notifies for each

#### Scenario: The position survives the process

- **WHEN** the app's process is killed after handling an event, and the service is restarted and reconnects
- **THEN** the app watches from that event's position and does not notify for it again

### Requirement: Notify for each attention event that is not suppressed

For each live `attention` event, and for each event of a `complete: true` answer, whose `suppressed` is false,
the app SHALL raise a system notification, subject to the replacement rules below and to one exception: while a
session's conversation is visible on the phone (`conversation-view`), the app SHALL raise no notification for
that session, SHALL remove any it shows for that session, and SHALL still store the event's position, as for a
suppressed event; once the conversation is no longer visible, notifications for that session resume. The
notification SHALL state what happened according to the kind —
`waiting` (an agent waits for input), `choice` (an agent asks for a choice, naming `tool`), `handoff-done` (a
handoff finished, with `summary`), `intake` (a new inbox item) — together with `title` and `folderName`. Text
from the event SHALL be shown as plain text exactly as received, without further shortening, removal or
markup rendering. A newer event for the same session SHALL replace that session's earlier notification; inbox
items SHALL be keyed by the item. Within one `complete: true` answer, only the latest event for each session or
item SHALL be posted, and the answer SHALL sound or vibrate at most once. Tapping a notification SHALL open the
app: a notification about a session SHALL open that session's conversation with the session list behind it,
each session's notification opening its own session; a notification about a new inbox item (`intake`) SHALL be
resolved against the inbox the app holds (waiting up to 5 seconds for a current one): an item still pending SHALL open the Inbox
(`inbox`) at that item; an accepted item whose session the current session list holds SHALL open that session's conversation; an
accepted item whose session is not listed yet SHALL open the session list; an item not held while the held inbox is current SHALL
open the session list; and an item not held because no current inbox arrived in time SHALL open the Inbox, which says it is not
current — each with the session list behind it; any other attention notification SHALL open the session list.

#### Scenario: An agent waits for input

- **WHEN** an agent on the desktop finishes its turn while the user is not looking at that session
- **THEN** the phone shows a notification naming the session's title and folder, saying the agent waits for
  input

#### Scenario: An agent asks for a choice

- **WHEN** an agent on the desktop raises a permission prompt
- **THEN** the phone shows a notification saying the agent asks for a choice, naming the tool

#### Scenario: A second event for the same session

- **WHEN** a `waiting` event follows a `choice` event for the same session
- **THEN** the phone shows one notification for that session, the `waiting` one

#### Scenario: A replay with two events for the same session

- **WHEN** a `complete: true` answer holds a `choice` and then a `waiting` event for the same session
- **THEN** the phone shows one notification for that session, the `waiting` one, and alerts once

#### Scenario: The agent finishes while the user reads its conversation on the phone

- **WHEN** an agent finishes its turn while its conversation is visible on the phone
- **THEN** the phone raises no notification for it, and stores the event's position

#### Scenario: Opening a conversation clears its notification

- **WHEN** the user opens a session's conversation from the session list while a notification for that session
  is shown
- **THEN** that notification is removed

#### Scenario: Tapping a waiting notification

- **WHEN** the user taps the notification saying an agent is waiting for input
- **THEN** that session's conversation opens, and going back shows the session list

#### Scenario: Two sessions' notifications

- **WHEN** two sessions have notifications and the user taps the older one
- **THEN** the conversation of that older notification's session opens

#### Scenario: Tapping while the app shows another conversation

- **WHEN** the app shows one session's conversation and the user taps another session's notification
- **THEN** the other session's conversation opens, and going back shows the session list

#### Scenario: Tapping a new inbox item's notification

- **WHEN** a Slack mention reaches the desktop's inbox and the user taps the phone's notification about it while it is pending
- **THEN** the Inbox opens showing the mention, and going back shows the session list

#### Scenario: Tapping a handoff's notification

- **WHEN** an agent's handoff arrives, its session opens and its agent starts, and the user then taps the phone's notification about
  it
- **THEN** the session list opens, showing the new session

#### Scenario: Tapping after the app was closed

- **WHEN** the app's process was killed, the phone cannot reach the desktop, and the user taps an inbox item's notification
- **THEN** the Inbox opens and says it is not current

### Requirement: Do not notify for a suppressed event

For an attention event with `suppressed: true`, the app SHALL raise no notification, SHALL remove any
notification it shows for that session, and SHALL still store the event's position.

#### Scenario: The user is looking at the session on the desktop

- **WHEN** an agent finishes its turn in the session the user is looking at on the desktop
- **THEN** the phone raises no notification for it

### Requirement: Summarize an incomplete answer

When `attention.watch` answers `complete: false`, the app SHALL NOT raise a notification for each of its events.
If the answer holds any event that is not suppressed, it SHALL raise one notification stating how many
unsuppressed events the answer holds and that some events may have been missed; a later summary SHALL replace
an earlier one. Whether or not it raises a summary, it SHALL store the position of the answer's last event.

#### Scenario: First watch after pairing

- **WHEN** the app watches for the first time after pairing, and the desktop holds 37 events
- **THEN** the phone shows one notification summarizing them, not 37

#### Scenario: The desktop restarted while the phone was away

- **WHEN** the desktop restarts while the phone is disconnected, raises an event, and the phone reconnects
- **THEN** the phone shows one summary notification saying some events may have been missed

### Requirement: Ask for the notification permission

On Android 13 and later, the app SHALL ask for the notification permission when pairing succeeds. When it is
denied, the status screen SHALL say that attention notifications are off and offer to open the system setting;
the connection SHALL still be held and positions still stored. Every state the app otherwise announces with a
notification — the connection stopped, pair again, the desktop's identity changed — SHALL also be shown on the
status screen, so it remains visible without the permission; it SHALL be kept across the app's process ending
and the phone restarting, until it is resolved (resumed, paired again, identity matching again) or the user
dismisses it.

#### Scenario: Notifications denied

- **WHEN** the user denies the notification permission
- **THEN** the status screen says attention notifications are off and offers the system setting, and the app
  stays connected

#### Scenario: Revoked while notifications are denied

- **WHEN** the notification permission is denied, the desktop revokes the phone, and the app's process is
  killed before the user opens it
- **THEN** opening the app shows that the phone must be paired again, not merely the pair screen

### Requirement: Remove a waiting notification once the desktop no longer counts the session as waiting

When a session list from the desktop shows a running agent session waiting for input and marked as seen, and the
previous list applied on the same connection did not show it so (or there was none), the app SHALL remove that
session's notification if the one shown was raised because the agent waited for input or asked for a choice. A
notification about a finished handoff or a new inbox item SHALL NOT be removed this way. A list that keeps showing
the session as seen SHALL NOT remove a notification raised after it. A desktop that sends no seen mark SHALL remove
nothing this way.

#### Scenario: Looked at on the desktop

- **WHEN** an agent finishes its turn, the phone notifies, and the user then looks at that session on the desktop
- **THEN** the phone's notification for that session goes away by itself

#### Scenario: A finished handoff is not removed

- **WHEN** a handed-off agent's report is adopted after its turn ended while the user looked at it, the phone
  notifies that the handoff finished, and the phone then reconnects
- **THEN** the "handoff finished" notification stays

#### Scenario: A new turn ends before the list catches up

- **WHEN** a seen agent is given a reply, finishes its next turn unseen, and the phone notifies before the desktop's
  next list marks it as not seen
- **THEN** the new notification stays
