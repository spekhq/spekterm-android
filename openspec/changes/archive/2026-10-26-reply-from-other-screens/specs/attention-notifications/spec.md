## MODIFIED Requirements

### Requirement: Notify for each attention event that is not suppressed

For each live `attention` event, and for each event of a `complete: true` answer, whose `suppressed` is false,
the app SHALL raise a system notification, subject to the replacement rules below and to one exception: while a
session's conversation, or a screen carrying its reply bar (`conversation-reply`), is visible on the phone
(`conversation-view`), the app SHALL raise no notification for that session, SHALL remove any it shows for that
session, and SHALL still store the event's position, as for a suppressed event; once none of them is visible any
more, notifications for that session resume. The
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

#### Scenario: The agent finishes while a change with its reply bar is on screen

- **WHEN** an agent finishes its turn while a change opened from its conversation, carrying its reply bar, is visible
  on the phone
- **THEN** the phone raises no notification for it, and the bar says the session is idle
