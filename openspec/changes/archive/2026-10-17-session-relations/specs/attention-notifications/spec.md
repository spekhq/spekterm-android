## ADDED Requirements

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
