## MODIFIED Requirements

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
each session's notification opening its own session; any other attention notification SHALL open the session
list.

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
